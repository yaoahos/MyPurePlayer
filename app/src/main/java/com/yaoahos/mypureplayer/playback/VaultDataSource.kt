package com.yaoahos.mypureplayer.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.yaoahos.mypureplayer.data.Crypto
import java.io.File
import java.io.IOException

/**
 * 保险箱密文的播放数据源：播放器以为在读普通文件，其实每次读都在现场解密。
 *
 * 为什么需要它：MV4 之前是整文件 CBC，播放只能先把整个视频解密到缓存目录
 * （等几十秒、峰值占双倍空间、磁盘不够时根本播不了）。MV4 是分块 GCM，
 * 每块可以单独解，于是可以做到「读到哪解到哪」——[Crypto.BlockReader]
 * 会把当前块解出来缓存，顺序播放命中缓存，拖动进度条直接跳到目标块。
 *
 * 边界：一个 DataSource 实例对应一路读取，[Crypto.BlockReader] 不是线程安全的，
 * Media3 对同一路 DataSource 的读取是串行的，所以不需要额外加锁。
 */
class VaultDataSource(private val file: File) : BaseDataSource(/* isNetwork = */ false) {

    private var reader: Crypto.BlockReader? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        // 密钥只在解锁会话内存在。自动上锁后内存里的 DEK 会被清掉，
        // 此时读密文必然失败，必须给一个能指引用户"先去解锁"的错误，而不是笼统的解密失败。
        val key = Crypto.currentKeyOrNull()
            ?: throw IOException("保险箱已锁定，请先解锁再播放")
        val blockReader = try {
            Crypto.BlockReader(file, key)
        } catch (t: Throwable) {
            throw IOException(t.message ?: "无法读取保险箱文件", t)
        }
        reader = blockReader
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            (blockReader.plainLength() - position).coerceAtLeast(0L)
        } else {
            dataSpec.length
        }
        transferStarted(dataSpec)
        opened = true
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val max = minOf(length.toLong(), bytesRemaining).toInt()
        val read = try {
            reader?.read(position, buffer, offset, max) ?: -1
        } catch (t: Throwable) {
            // GCM tag 不匹配会走到这里：内容被改过或密钥不对，播放器要能明确报错
            throw IOException("解密失败：${t.message ?: "文件已损坏"}", t)
        }
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = Uri.fromFile(file)

    override fun close() {
        reader?.close()
        reader = null
        // open() 失败时 Media3 也会调 close()。基类的 transferEnded() 在「从未开始传输」
        // 时会 checkState 失败抛异常，把一次普通的打开失败升级成崩溃。
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val file: File) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(file)
    }
}
