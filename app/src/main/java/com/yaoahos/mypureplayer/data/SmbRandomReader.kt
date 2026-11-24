package com.yaoahos.mypureplayer.data

import com.hierynomus.smbj.share.File
import java.io.Closeable
import java.io.IOException

/**
 * smbj 文件句柄上的「随机读」。
 *
 * 原有实现直接用 jcifs 的 SmbRandomAccessFile（seek + read）。smbj 没有对应的游标式
 * 类，只有 `File.read(buffer, fileOffset, offset, length)` 这种按偏移读的方法，
 * 所以这里补一层，让播放数据源、DLNA 拉流、抽帧三条路径都能原样复用。
 *
 * 并发：同一个句柄可能被多个线程共用（播放数据源与流服务各持一个 DataSource）。
 * 游标式 seek + read 是两次独立调用，中间若被别的线程插入 seek，读到的位置就错了。
 * 所以对外既提供游标式接口（单线程场景），也提供 [readAt] —— 定位与读在同一次
 * 加锁里完成，多线程安全。
 *
 * 单次请求上限 [PREFERRED_CHUNK]：smbj 把 length 原样放进 READ 请求，服务器会以
 * STATUS_INVALID_PARAMETER 拒绝超过自己 MaxReadSize 的请求。先按 1MB 发，
 * 被拒时自动降到 [FALLBACK_CHUNK]（旧实现验证过的 512KB）并锁定。
 */
class SmbRandomReader internal constructor(
    private val file: File,
    private val totalLength: Long,
) : Closeable {

    private var position = 0L
    private val lock = Any()

    /** 当前生效的单次 READ 块长：首次按 PREFERRED_CHUNK，被服务器拒绝后降级 */
    @Volatile
    private var maxChunk: Int = PREFERRED_CHUNK

    @Volatile
    private var closed = false

    fun length(): Long = totalLength

    fun seek(pos: Long) {
        synchronized(lock) { position = pos.coerceAtLeast(0L) }
    }

    /** 从当前游标读，返回实际字节数；读到文件末尾返回 -1 */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        val n = readLocked(position, buffer, offset, length)
        if (n > 0) position += n
        n
    }

    /**
     * 从指定偏移读，不改变游标。定位与读在同一次加锁内完成，
     * 多个线程共用一个句柄时不会串位。
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        synchronized(lock) { readLocked(position, buffer, offset, length) }

    private fun readLocked(pos: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (closed) return -1
        if (pos >= totalLength) return -1
        val want = minOf(length.toLong(), totalLength - pos, maxChunk.toLong()).toInt()
        val n = try {
            file.read(buffer, pos, offset, want)
        } catch (t: Exception) {
            // 只有 STATUS_INVALID_PARAMETER 才是「服务器不认这么大的 READ」。
            // 断网/权限错误等过去也会被误判成块长问题，白跑一次失败往返，还永久降到 512KB。
            if (maxChunk > FALLBACK_CHUNK && SmbErrors.isInvalidParameter(t)) {
                maxChunk = FALLBACK_CHUNK
                return readLocked(pos, buffer, offset, length)
            }
            throw if (t is IOException) t else IOException("SMB 读取失败", t)
        }
        return if (n <= 0) -1 else n
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { file.close() }
    }

    private companion object {
        /**
         * 单次 READ 的目标块长。smbj 会把 length 原样塞进 READ 请求，超过服务器
         * MaxReadSize 会被 STATUS_INVALID_PARAMETER 拒绝：先按 1MB 试，
         * 被拒后自动降到 [FALLBACK_CHUNK] 并锁定。DS220+ 这类 x86 NAS 支持 1MB，
         * 一次读满 1MB 能把索引解析、seek 预读的往返次数直接减半。
         */
        const val PREFERRED_CHUNK = 1024 * 1024
        const val FALLBACK_CHUNK = 512 * 1024
    }
}
