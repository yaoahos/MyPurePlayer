package com.yaoahos.mypureplayer.data

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.yaoahos.mypureplayer.core.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地媒体文件的通用操作。
 *
 * Android 11+ 分区存储下，应用默认无法用 File API 改名/移动/删除**其它应用创建**的媒体文件，
 * 也不能把文件移出媒体目录。因此除「分享」外的所有文件管理操作都要求用户开启
 * 「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE）；未开启时由界面引导跳转系统设置。
 */
object FileOps {

    fun isScopedStorage(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 是否已具备完整的文件读写能力 */
    fun hasAllFiles(context: Context): Boolean =
        !isScopedStorage() || Environment.isExternalStorageManager()

    /** 跳转到「所有文件访问权限」授权页 */
    fun allFilesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /** 让媒体库重新收录 / 移除某个路径 */
    fun scan(context: Context, vararg paths: String) {
        val valid = paths.filter { it.isNotBlank() }.toTypedArray()
        if (valid.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context, valid, null, null)
        }
    }

    /**
     * 触发媒体库重扫，并**等到扫描回调**再返回 `path → content uri`。
     *
     * 为什么需要等：文件从回收站/保险箱放回原处后，MediaStore 里那条还是"已删除"，
     * `scanFile(..., null)` 只把任务丢给系统、立刻返回，紧接着查库大概率查不到 ——
     * 表现就是"恢复了文件，历史里却没有它"。回调是在写入媒体库之后才来的。
     *
     * 超时/文件不存在时返回已有结果（可能为空），调用方自行决定是否降级。
     */
    suspend fun scanAndAwait(
        context: Context,
        paths: Collection<String>,
        timeoutMs: Long = 4_000L,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val list = paths.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return@withContext emptyMap()
        val result = java.util.concurrent.ConcurrentHashMap<String, String>()
        val latch = java.util.concurrent.CountDownLatch(list.size)
        val started = runCatching {
            MediaScannerConnection.scanFile(context, list.toTypedArray(), null) { path, uri ->
                if (!path.isNullOrBlank() && uri != null) result[path] = uri.toString()
                latch.countDown()
            }
        }.isSuccess
        if (!started) return@withContext emptyMap()
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        result.toMap()
    }

    /** 在 [dir] 里挑一个不重名的目标文件 */
    fun uniqueTarget(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "_$index" else "_$index.$ext"
            candidate = File(dir, "$base$suffix")
            index++
        }
        return candidate
    }

    /** 流式复制，返回是否成功 */
    fun copyFile(src: File, dst: File, onProgress: (Float) -> Unit = {}): Boolean = runCatching {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input ->
            dst.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                val total = src.length().coerceAtLeast(1L)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    done += read
                    output.write(buffer, 0, read)
                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        true
    }.getOrElse {
        runCatching { dst.delete() }
        false
    }

    /** 用系统分享面板分享一个视频 */
    fun share(context: Context, item: VideoItem) {
        val uri = Uri.parse(item.uri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(item.fileName)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, item.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "分享视频"))
        }
    }

    /** MIME 统一走 Formats.videoMimeOf；未知封装给分享面板回 video 通配 */
    fun mimeOf(fileName: String): String = Formats.videoMimeOf(fileName, fallback = "video/*")

    // ------------------------------------------------------------ 批量文件操作

    /** 重命名（只改基础名，扩展名保持不变） */
    suspend fun rename(context: Context, item: VideoItem, baseName: String): Result<String> =
        withContext(Dispatchers.IO) {
            val path = item.localPath ?: return@withContext Result.failure(
                IllegalStateException("这个视频没有真实文件路径"),
            )
            val source = File(path)
            if (!source.exists()) return@withContext Result.failure(IllegalStateException("文件已不存在"))

            val ext = item.fileName.substringAfterLast('.', "")
            val clean = baseName.trim().ifBlank { item.title }
            val name = if (ext.isBlank()) clean else "$clean.$ext"
            if (name == item.fileName) return@withContext Result.success(path)

            val target = File(source.parentFile, name)
            if (target.exists()) return@withContext Result.failure(IllegalStateException("同名文件已存在"))

            if (!source.renameTo(target)) {
                return@withContext Result.failure(
                    IllegalStateException("重命名失败，请开启「所有文件访问权限」"),
                )
            }
            scan(context, path, target.absolutePath)
            Result.success(target.absolutePath)
        }

    /** 复制到指定目录 */
    suspend fun copyTo(context: Context, item: VideoItem, dir: File): Result<String> =
        withContext(Dispatchers.IO) {
            val path = item.localPath ?: return@withContext Result.failure(
                IllegalStateException("这个视频没有真实文件路径"),
            )
            val source = File(path)
            if (!source.exists()) return@withContext Result.failure(IllegalStateException("文件已不存在"))
            if (!dir.exists() && !dir.mkdirs()) {
                return@withContext Result.failure(IllegalStateException("目标目录不可写"))
            }
            val target = uniqueTarget(dir, item.fileName)
            if (!copyFile(source, target)) {
                return@withContext Result.failure(IllegalStateException("复制失败，请检查存储空间"))
            }
            scan(context, target.absolutePath)
            Result.success(target.absolutePath)
        }

    /** 移动到指定目录 */
    suspend fun moveTo(context: Context, item: VideoItem, dir: File): Result<String> =
        withContext(Dispatchers.IO) {
            val path = item.localPath ?: return@withContext Result.failure(
                IllegalStateException("这个视频没有真实文件路径"),
            )
            val source = File(path)
            if (!source.exists()) return@withContext Result.failure(IllegalStateException("文件已不存在"))
            if (!dir.exists() && !dir.mkdirs()) {
                return@withContext Result.failure(IllegalStateException("目标目录不可写"))
            }
            val target = uniqueTarget(dir, item.fileName)
            val ok = source.renameTo(target) || (copyFile(source, target) && source.delete())
            if (!ok) {
                runCatching { target.delete() }
                return@withContext Result.failure(IllegalStateException("移动失败，请检查写入权限"))
            }
            scan(context, path, target.absolutePath)
            Result.success(target.absolutePath)
        }

    /** 设备上已有的视频目录（不含应用私有目录），用于「复制 / 移动到」选择 */
    fun knownVideoDirs(videos: List<VideoItem>): List<File> =
        videos.mapNotNull { it.localPath }
            .mapNotNull { File(it).parentFile }
            .filter { it.isDirectory && !it.absolutePath.contains("/Android/data/") }
            .distinctBy { it.absolutePath }
            .sortedBy { it.absolutePath }
}
