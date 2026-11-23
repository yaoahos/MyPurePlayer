package com.yaoahos.mypureplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 极简 JSON 文件存储：单进程、全量读写、mutex 串行化。
 * 数据量很小（服务器列表、播放列表、历史），无需引入数据库。
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val fallback: () -> T,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    private val mutex = Mutex()

    @Volatile
    private var cached: T? = null

    suspend fun read(): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            cached ?: load().also { cached = it }
        }
    }

    suspend fun write(value: T) = withContext(Dispatchers.IO) {
        mutex.withLock { writeLocked(value) }
    }

    /**
     * 读-改-写必须在同一把锁内完成：block(read()) 与 write() 之间若被别的写者
     * 插进来，后写的一方会用不含对方条目的旧快照覆盖落盘 —— 表现为历史/播放列表
     * 「刚记的条目凭空消失」。write 内部也用这把 mutex，所以这里复用同一个
     * writeLocked 而不是再调一次 write（Mutex 不可重入，嵌套会死锁）。
     */
    suspend fun update(block: (T) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = block(cached ?: load().also { cached = it })
            writeLocked(next)
            next
        }
    }

    private fun writeLocked(value: T) {
        val text = json.encodeToString(serializer, value)
        // 原子写：先写临时文件再 rename 覆盖。直接用 writeText 会在写入中途被杀时
        // 留下半截 JSON，下次读到 fallback（空列表），造成历史/播放列表莫名丢失。
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val ok = runCatching {
            file.parentFile?.mkdirs()
            tmp.writeText(text)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val options = arrayOf(
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
                try {
                    java.nio.file.Files.move(tmp.toPath(), file.toPath(), *options)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    // 某些文件系统不支持原子移动，退化为普通替换（仍不会留下半截内容）
                    java.nio.file.Files.move(
                        tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                true
            } else {
                // 低版本用 renameTo：Android 基于 POSIX rename，目标存在时原子覆盖
                tmp.renameTo(file)
            }
        }.getOrDefault(false)
        if (!ok) {
            runCatching { tmp.delete() }
            throw IllegalStateException("配置写入失败：${file.name}")
        }
        cached = value
    }

    private fun load(): T {
        if (!file.exists()) return fallback()
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .getOrElse { t ->
                // 损坏不能无声吞掉：一来下次任意写盘会把空表落盘、销毁现场，
                // 二来「数据凭空消失」这类问题没有日志就完全无法排查。
                // 把损坏文件改名留底，既能恢复现场又不影响本次兜底。
                android.util.Log.e(TAG, "读取 ${file.name} 失败，已回退到空表：${t.message}", t)
                runCatching {
                    val keep = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
                    file.renameTo(keep)
                }
                fallback()
            }
    }

    private companion object {
        const val TAG = "JsonStore"
    }
}
