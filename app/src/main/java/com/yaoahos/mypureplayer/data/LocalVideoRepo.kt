package com.yaoahos.mypureplayer.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.yaoahos.mypureplayer.core.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 本地文件夹分组 */
data class FolderGroup(
    val key: String,
    val name: String,
    val videos: List<VideoItem>,
)

/**
 * 本地视频库：通过 MediaStore 扫描，只保留视频扩展名白名单内的条目。
 * 音频、图片、文档一律不会进入列表。
 */
class LocalVideoRepo(private val context: Context) {

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** 后台校验死条目用；不占用扫描主路径，避免进文件夹被 File.exists() 拖慢 */
    private val verifyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param silent 静默扫描：不切换 [_scanning]，用于切 tab / ON_RESUME / 首次加载，
     *   避免明明内容没变却闪一下顶部转圈。只有用户主动下拉刷新才传 false 显示转圈。
     */
    suspend fun scan(silent: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (!silent) _scanning.value = true
        try {
            val found = queryMediaStore()
            // 切 tab / ON_RESUME 会重复扫描。内容没变时不替换列表对象，
            // 避免整页重组造成"切到首页闪一下"。只有真正增删/改动才更新。
            if (found != _videos.value) _videos.value = found
            // 死条目校验放到后台：旧实现在扫描主路径上逐条 File.exists()，
            // SD 卡休眠 / 媒体库很大时会把「进文件夹」拖成几十秒（用户反馈的
            // 「偶尔刷新几十秒才展示」）。现在列表先出，再异步摘掉不存在的条目。
            verifyScope.launch { dropMissing(found) }
            found.size
        } catch (e: SecurityException) {
            android.util.Log.w("LocalVideoRepo", "扫描被拒绝", e)
            _videos.value.size
        } catch (t: Throwable) {
            android.util.Log.e("LocalVideoRepo", "扫描失败", t)
            _videos.value.size
        } finally {
            if (!silent) _scanning.value = false
        }
    }

    private fun queryMediaStore(): List<VideoItem> {
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATA,
        )

        // MediaStore.Video 本身已只含视频；扩展名白名单在下面逐行做，避免
        // 把十几种 LIKE 条件下推进 query()，那在部分设备上反而更慢且大小写行为不一致。
        val result = ArrayList<VideoItem>()
        val cursor: Cursor? = context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
        )

        cursor?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val bucketIdIdx = c.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameIdx = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val wIdx = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val hIdx = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            val dataIdx = c.getColumnIndex(MediaStore.Video.Media.DATA)

            while (c.moveToNext()) {
                val name = c.getString(nameIdx) ?: continue
                if (!Formats.isVideo(name)) continue
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                if (size <= 0L) continue

                val id = c.getLong(idIdx)
                val data = if (dataIdx >= 0) c.getString(dataIdx) else null
                // MediaStore 行可能比物理文件活得久（文件管理器删除不经过本应用）。
                // 这里不再逐条 File.exists()：那是扫描路径上的同步 I/O，慢存储上
                // 几百条就能拖几十秒。改成扫完由 [dropMissing] 在后台对账摘除。
                val contentUri = ContentUris.withAppendedId(collection, id)
                val bucketId = if (bucketIdIdx >= 0 && !c.isNull(bucketIdIdx)) c.getString(bucketIdIdx) else null
                val bucketName = if (bucketNameIdx >= 0) c.getString(bucketNameIdx) else null

                val folderKey = bucketId ?: data?.substringBeforeLast('/', "") ?: ""
                val folderName = bucketName?.takeIf { it.isNotBlank() }
                    ?: data?.substringBeforeLast('/')?.substringAfterLast('/')
                    ?: ""

                result.add(
                    VideoItem(
                        id = contentUri.toString(),
                        title = Formats.stripExtension(name),
                        fileName = name,
                        uri = contentUri.toString(),
                        source = MediaSource.LOCAL,
                        durationMs = if (durIdx >= 0) c.getLong(durIdx) else 0L,
                        sizeBytes = size,
                        dateModified = if (dateIdx >= 0) c.getLong(dateIdx) * 1000L else 0L,
                        folderName = folderName,
                        folderKey = folderKey,
                        width = if (wIdx >= 0) c.getInt(wIdx) else 0,
                        height = if (hIdx >= 0) c.getInt(hIdx) else 0,
                        localPath = data,
                    ),
                )
            }
        }

        return result
    }

    /**
     * 立刻从本地列表摘掉已经不在原位的条目，不依赖 MediaStore 的异步扫描回调。
     *
     * 删除走的是 `MediaScannerConnection.scanFile(...)` 无回调版本，媒体库更新有延迟。
     * 如果只等刷新去重新 query，删除后短时间内那条死记录还在；用户在文件管理器里删的
     * 文件更是根本不经过本应用。这里按 id 或原路径对账，立刻把列表收紧。
     */
    /**
     * 后台对账：MediaStore 里有行、但真实文件已经不在了。
     *
     * 只处理拿得到 localPath 的条目；DATA 不可得时宁可留着（不能误删用户可见的条目）。
     * 发现死条目后走 [dropGone] 从当前列表摘掉，与手动删除后的即时清理同一条路径。
     */
    private fun dropMissing(items: List<VideoItem>) {
        if (items.isEmpty()) return
        val gone = items.filter { item ->
            item.localPath?.let { path -> path.isNotBlank() && !File(path).exists() } == true
        }
        if (gone.isNotEmpty()) dropGone(gone)
    }

    fun dropGone(items: Collection<VideoItem>) {
        if (items.isEmpty()) return
        val ids = items.map { it.id }.toSet()
        val paths = items.mapNotNull { it.localPath }.toSet()
        val next = _videos.value.filterNot { item ->
            item.id in ids || (item.localPath != null && item.localPath in paths)
        }
        if (next.size != _videos.value.size) _videos.value = next
    }

    fun videosInFolder(folderKey: String): List<VideoItem> =
        _videos.value
            .filter { it.folderKey == folderKey }
            .sortedWith(compareBy({ it.fileName.lowercase(Locale.getDefault()) }))

    /**
     * 按 MediaStore id 取最新的条目。
     *
     * 权限授权要离开应用去系统设置，回来时列表可能已经变了；文件操作必须拿这条
     * 重新查过的记录，不能拿授权前记住的旧对象 —— 旧对象里的路径可能已失效，
     * 表现就是「授权后重试提示文件不存在」。
     */
    suspend fun freshById(id: String): VideoItem? {
        scan(silent = true)
        return _videos.value.firstOrNull { it.id == id }
    }

    /**
     * 在已加载的本地库里重新定位一条记录。
     *
     * 播放列表、历史记录里存的本地条目是「加入那一刻」的快照：`content://` 行号在
     * 重新扫描媒体库、文件被移动/重命名后可能失效，从别的设备恢复的备份更是整批
     * 对不上。播放前用这个函数把旧快照换成当前库里真实存在的条目，避免出现
     * 「地址没变却打不开」。
     *
     * 匹配顺序：真实路径 → id → 文件名（同名多条时用大小消歧）。
     */
    fun relocate(item: VideoItem): VideoItem? {
        val all = _videos.value
        if (all.isEmpty()) return null
        item.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            all.firstOrNull { it.localPath == path }?.let { return it }
        }
        all.firstOrNull { it.id == item.id }?.let { return it }
        val sameName = all.filter { it.fileName.equals(item.fileName, ignoreCase = true) }
        return when {
            sameName.isEmpty() -> null
            item.sizeBytes > 0L ->
                sameName.firstOrNull { it.sizeBytes == item.sizeBytes } ?: sameName.first()
            else -> sameName.first()
        }
    }

    /** 按文件夹 key（MediaStore bucket id）重新取这一组视频 */
    suspend fun freshFolder(key: String): FolderGroup? {
        scan(silent = true)
        val items = _videos.value.filter { it.folderKey == key }
        return items.firstOrNull()?.let { FolderGroup(key, it.folderName, items) }
    }

    fun folders(): List<FolderGroup> =
        _videos.value
            .groupBy { it.folderKey }
            .map { (key, list) ->
                FolderGroup(
                    key = key,
                    name = list.firstOrNull()?.folderName?.takeIf { it.isNotBlank() } ?: "其它",
                    videos = list.sortedWith(compareBy { it.fileName.lowercase(Locale.getDefault()) }),
                )
            }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
}
