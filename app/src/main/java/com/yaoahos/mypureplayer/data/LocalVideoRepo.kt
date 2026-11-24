package com.yaoahos.mypureplayer.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.yaoahos.mypureplayer.core.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                // MediaStore 里的行不一定跟着物理文件一起消失：在文件管理器里删掉视频后，
                // 旧行可能还留着。只查库不校验文件，列表里就会出现"点进去文件不存在"的死条目，
                // 而且手动刷新只会再查一次同样的库，怎么刷都刷不掉。
                // DATA 不可得时不猜（部分机型返回空），沿用 contentUri 播放。
                if (!data.isNullOrBlank() && !File(data).exists()) continue
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
