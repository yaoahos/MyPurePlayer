package com.yaoahos.mypureplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * 回收站：删除的本地视频先移动到应用私有的外部目录（不占用媒体库，卸载即清理），
 * 只有「回收站内再次删除」才会真正抹掉数据。
 */
class TrashRepo(
    private val context: Context,
    /**
     * 文件被放回原处之后回调「恢复出来的绝对路径」。
     *
     * 恢复后需要在历史里重新记一条（用户要求），而历史写入依赖媒体库的新 id，
     * 只能由掌握全部恢复路径的这一层统一上报，界面不必在每个恢复入口各补一次。
     */
    private val onRestored: suspend (List<String>) -> Unit = {},
) {

    private val root: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "trash")

    private val store = JsonStore(
        File(context.filesDir, "trash.json"),
        ListSerializer(TrashEntry.serializer()),
    ) { emptyList() }

    private val _entries = MutableStateFlow<List<TrashEntry>>(emptyList())
    val entries: StateFlow<List<TrashEntry>> = _entries.asStateFlow()

    private val mutex = Mutex()

    suspend fun load() {
        _entries.value = store.read().sortedByDescending { it.deletedAt }
        cleanOrphans()
    }

    fun fileOf(entry: TrashEntry): File = File(root, entry.storedName)

    fun totalSize(): Long = _entries.value.sumOf { it.sizeBytes }

    /** 把视频移入回收站；文件不可读或没有权限时返回失败 */
    suspend fun moveToTrash(item: VideoItem): Result<TrashEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = item.localPath
                ?: return@withLock Result.failure(IllegalStateException("这个视频没有真实文件路径"))
            val source = File(path)
            if (!source.exists()) return@withLock Result.failure(IllegalStateException("文件已不存在"))

            moveInto(
                source = source,
                originalPath = path,
                fileName = item.fileName,
                folderName = item.folderName,
                sizeBytes = item.sizeBytes,
                durationMs = item.durationMs,
                width = item.width,
                height = item.height,
                isFolder = false,
                fileCount = 1,
            )
        }
    }

    /**
     * 把**整个文件夹里的视频**移入回收站。
     *
     * 语义是"同名文件夹 + 只搬视频"，不是"把原来的目录整体搬走"：
     *  - 在回收站里建一个与原名相同的文件夹，把**视频文件**逐个搬进去；
     *  - 原目录里其它文件（字幕、封面、.nomedia、文档……）原地不动；
     *  - 原目录搬空之后才删掉这个空目录，还剩文件就保留。
     *
     * 为什么不能整目录 rename：一个"视频文件夹"里往往混着非视频文件，
     * 整目录搬走等于把它们一起删了 —— 在回收站里还看不出来（只显示视频），
     * 恢复时又整目录搬回来，用户很难意识到中间那段时间它们已经不在原处。
     * 保险箱那边更严重：整目录加密会把字幕、图片一起吞掉。
     *
     * 恢复时反向操作：把回收站这个同名文件夹里的视频逐个搬回原目录，
     * 原目录不存在就重建；搬空之后回收站里的空文件夹一并清掉。
     */
    suspend fun moveFolderToTrash(
        videos: List<VideoItem>,
        sizeBytes: Long = 0L,
    ): Result<TrashEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val paths = videos.mapNotNull { it.localPath }.distinct()
            val dir = paths.firstOrNull()?.let { File(it).parentFile }
                ?: return@withLock Result.failure(IllegalStateException("没有找到文件夹位置"))
            if (!dir.isDirectory) {
                return@withLock Result.failure(IllegalStateException("文件夹已不存在"))
            }
            // 只认确实躺在这个目录里的文件。
            // 这条记录恢复时会被整体放回 dir，如果混进了别的目录的文件，
            // 恢复就会把它们搬到这个目录里 —— 等于悄悄改了文件的位置。
            val members = paths.filter { File(it).parentFile?.absolutePath == dir.absolutePath }

            val stored = "${System.currentTimeMillis()}_${dir.name}"
            val bucket = File(root, stored)
            if (!bucket.mkdirs() && !bucket.isDirectory) {
                return@withLock Result.failure(IllegalStateException("没有写入权限，请开启「所有文件访问权限」"))
            }

            var moved = 0
            var bytes = 0L
            var failed = 0
            members.forEach { p ->
                val src = File(p)
                // 期间被别处删掉/移走的直接跳过，不算失败
                if (!src.isFile) return@forEach
                val dest = File(bucket, src.name)
                if (movePath(src, dest)) {
                    moved++
                    bytes += dest.length()
                } else {
                    failed++
                    runCatching { if (dest.exists()) dest.delete() }
                }
            }

            if (moved == 0) {
                runCatching { bucket.deleteRecursively() }
                return@withLock Result.failure(
                    IllegalStateException("没有写入权限，请开启「所有文件访问权限」"),
                )
            }

            // 原目录搬空了才删；还留着非视频文件就原样保留
            runCatching {
                val left = dir.listFiles()
                if (left != null && left.isEmpty()) dir.delete()
            }

            if (failed > 0) {
                // 只剩一部分，仍然要留下一条记录，否则没搬走的那些会"凭空消失"
                android.util.Log.w("TrashRepo", "folder to trash: $failed files kept in place")
            }

            val entry = TrashEntry(
                id = System.currentTimeMillis(),
                fileName = dir.name,
                originalPath = dir.absolutePath,
                originalFolderName = dir.name,
                sizeBytes = if (bytes > 0L) bytes else sizeBytes,
                durationMs = 0L,
                width = 0,
                height = 0,
                deletedAt = System.currentTimeMillis(),
                storedName = stored,
                isFolder = true,
                fileCount = moved,
            )
            val next = listOf(entry) + _entries.value
            store.write(next)
            _entries.value = next
            FileOps.scan(context, dir.absolutePath)
            Result.success(entry)
        }
    }

    /** 实际搬运 + 记账；文件与文件夹共用一条路径 */
    private suspend fun moveInto(
        source: File,
        originalPath: String,
        fileName: String,
        folderName: String,
        sizeBytes: Long,
        durationMs: Long,
        width: Int,
        height: Int,
        isFolder: Boolean,
        fileCount: Int,
    ): Result<TrashEntry> {
        val stored = "${System.currentTimeMillis()}_$fileName"
        val target = File(root, stored)
        root.mkdirs()

        if (!movePath(source, target)) {
            runCatching { if (target.isDirectory) target.deleteRecursively() else target.delete() }
            return Result.failure(
                IllegalStateException("没有写入权限，请开启「所有文件访问权限」"),
            )
        }

        val entry = TrashEntry(
            id = System.currentTimeMillis(),
            fileName = fileName,
            originalPath = originalPath,
            originalFolderName = folderName,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            width = width,
            height = height,
            deletedAt = System.currentTimeMillis(),
            storedName = stored,
            isFolder = isFolder,
            fileCount = fileCount,
        )
        val next = listOf(entry) + _entries.value
        store.write(next)
        _entries.value = next
        // 通知媒体库原路径已失效
        FileOps.scan(context, originalPath)
        return Result.success(entry)
    }

    /** rename 优先（同分区瞬间完成），跨分区退化为递归复制 + 删除 */
    private fun movePath(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        return runCatching {
            source.copyRecursively(target, overwrite = false) && source.deleteRecursively()
        }.getOrDefault(false)
    }

    /** 恢复到原目录；同名文件已存在时自动改名 */
    suspend fun restore(entry: TrashEntry): Result<String> {
        val restored = mutableListOf<String>()
        val result = withContext(Dispatchers.IO) {
            mutex.withLock {
                val source = fileOf(entry)
                if (!source.exists()) {
                    return@withLock Result.failure(IllegalStateException("回收站里的文件已丢失"))
                }

                if (entry.isFolder) return@withLock restoreFolder(entry, source, restored)

                val parent = File(entry.originalPath).parentFile
                    ?: return@withLock Result.failure(IllegalStateException("原目录不可用"))
                if (!parent.exists() && !parent.mkdirs()) {
                    return@withLock Result.failure(IllegalStateException("无法创建原目录"))
                }

                val target = if (File(entry.originalPath).exists()) {
                    FileOps.uniqueTarget(parent, entry.fileName)
                } else {
                    File(entry.originalPath)
                }
                if (target.exists()) {
                    return@withLock Result.failure(IllegalStateException("目标位置已有同名文件"))
                }

                if (!movePath(source, target)) {
                    return@withLock Result.failure(IllegalStateException("恢复失败，请检查存储权限"))
                }

                removeEntry(entry)
                FileOps.scan(context, target.absolutePath)
                restored += target.absolutePath
                Result.success(target.absolutePath)
            }
        }
        // 回写历史必须在锁外：它要扫媒体库、写 history.json，占着回收站的锁会把
        // 后续的删除/恢复排队等到它做完。
        if (restored.isNotEmpty()) onRestored(restored)
        return result
    }

    /**
     * 恢复一个文件夹：把回收站里这个同名文件夹中的视频逐个搬回原目录。
     *
     * 原目录可能已经不在（用户删过、或被系统清理），这里按需重建；
     * 目标位置有同名文件就自动改名，绝不覆盖 —— 覆盖等于又删了一次用户的文件。
     * 全部搬完再把回收站里的空壳删掉；只要还剩一个没搬走，这条记录就继续留着，
     * 让用户能再试一次（并看到还剩几个）。
     */
    private suspend fun restoreFolder(
        entry: TrashEntry,
        bucket: File,
        restored: MutableList<String>,
    ): Result<String> {
        val files = bucket.listFiles().orEmpty().filter { it.isFile }
        if (files.isEmpty()) {
            // 只剩空壳：没有可恢复的内容，直接收掉这条记录
            runCatching { bucket.deleteRecursively() }
            removeEntry(entry)
            return Result.failure(IllegalStateException("回收站里的文件夹已经空了"))
        }

        val dir = File(entry.originalPath)
        if (!dir.exists() && !dir.mkdirs()) {
            return Result.failure(IllegalStateException("无法创建原目录"))
        }

        var moved = 0
        var failed = 0
        var lastError: String? = null
        files.forEach { f ->
            val target = if (File(dir, f.name).exists()) FileOps.uniqueTarget(dir, f.name)
            else File(dir, f.name)
            if (movePath(f, target)) {
                moved++
                restored += target.absolutePath
                FileOps.scan(context, target.absolutePath)
            } else {
                failed++
                if (lastError == null) lastError = "无法写入「${dir.absolutePath}」"
            }
        }

        if (failed == 0) {
            runCatching { bucket.deleteRecursively() }
            removeEntry(entry)
            return Result.success(dir.absolutePath)
        }

        // 部分成功：更新剩余数量与体积，让回收站里这条记录仍是"真实的样子"
        val left = bucket.listFiles().orEmpty().filter { it.isFile }
        val updated = entry.copy(
            fileCount = left.size,
            sizeBytes = left.sumOf { it.length() },
        )
        val next = _entries.value.map { if (it.id == entry.id) updated else it }
        store.write(next)
        _entries.value = next
        return Result.failure(
            IllegalStateException(
                "已恢复 $moved 个，还有 $failed 个没能放回（${lastError ?: "请检查存储权限"}）",
            ),
        )
    }

    private suspend fun removeEntry(entry: TrashEntry) {
        val next = _entries.value.filterNot { it.id == entry.id }
        store.write(next)
        _entries.value = next
    }

    /** 彻底删除单条 */
    suspend fun deletePermanently(entry: TrashEntry) = deletePermanently(listOf(entry))

    /** 多选彻底删除：同一把锁内完成删除与一次落盘 */
    suspend fun deletePermanently(entries: Collection<TrashEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ids = entries.mapTo(HashSet()) { it.id }
            entries.forEach {
                runCatching {
                    val f = fileOf(it)
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
            val next = _entries.value.filterNot { it.id in ids }
            store.write(next)
            _entries.value = next
        }
    }

    /** 清空回收站 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _entries.value.forEach {
                runCatching {
                    val f = fileOf(it)
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
            root.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
            store.write(emptyList())
            _entries.value = emptyList()
        }
    }

    /**
     * 清理记录里已不存在文件的孤儿条目（比如用户手动清理了应用数据）。
     *
     * 这里必须整体放到 IO 线程：`load()` 的调用方可能是主线程，之前用 `runBlocking` 包
     * `store.write()`，等于在主线程上同步做一次全量 JSON 落盘 + rename，回收站条目多时
     * 会在启动进入「更多」页时卡一下。
     */
    private suspend fun cleanOrphans() = withContext(Dispatchers.IO) {
        val snapshot = _entries.value
        val alive = snapshot.filter { fileOf(it).exists() }
        if (alive.size != snapshot.size) {
            store.write(alive)
            _entries.value = alive
        }
    }
}
