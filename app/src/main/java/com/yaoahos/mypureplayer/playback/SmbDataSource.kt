package com.yaoahos.mypureplayer.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import com.yaoahos.mypureplayer.data.SmbClient
import com.yaoahos.mypureplayer.data.SmbRandomReader
import com.yaoahos.mypureplayer.data.SmbServer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 注册 SMB 播放会话的连接信息。ExoPlayer 打开 smb:// DataSpec 时需要 SmbServer
 * （账号/口令/域），但 MediaItem 里只带播放 URL，所以在这里按 URL 关联一次。
 */
object SmbPlaybackRegistry {
    private data class Entry(val server: SmbServer, var lastAccess: Long)
    private val map = ConcurrentHashMap<String, Entry>()

    /** 上限：超过后按 lastAccess 淘汰最旧的，防止会话期内无限增长（Entry 持有含口令的对象） */
    private const val MAX_ENTRIES = 96

    fun register(url: String, server: SmbServer) {
        val now = SystemClock.elapsedRealtime()
        map[url] = Entry(server, now)
        // 去掉 userinfo 的 URL 也登记一份，避免 ExoPlayer 规范化 URI 后匹配不上
        runCatching {
            val u = Uri.parse(url)
            val stripped = Uri.Builder().scheme(u.scheme).authority(u.host).path(u.path).build().toString()
            if (stripped != url) map.putIfAbsent(stripped, Entry(server, now))
        }
        // 再登记一份解码后的原生路径（无 userinfo）：DataSource 实际是用它去开的
        runCatching {
            val native = SmbClient.nativeUrl(url)
            if (native != url) map.putIfAbsent(native, Entry(server, now))
        }
        if (map.size > MAX_ENTRIES) {
            map.entries.sortedBy { it.value.lastAccess }
                .take(map.size - MAX_ENTRIES)
                .forEach { map.remove(it.key) }
        }
    }

    fun serverFor(url: String): SmbServer? {
        val e = map[url] ?: return null
        e.lastAccess = SystemClock.elapsedRealtime()
        return e.server
    }
}

/**
 * 全局共享的 SMB 随机读句柄池。seek 时复用同一句柄，避免每次 Range 都重新打开文件
 * （每次 open 都是 CREATE + QUERY_INFO 加一次 CLOSE，是「打开慢、快进卡」的主因）。
 * 同一句柄可能被多个 DataSource 实例共享，读取走 [SmbRandomReader.readAt]：
 * 定位与读在同一次加锁内完成，不同文件之间互不阻塞。
 *
 * 两个针对「快进要等一会 / 偶尔卡住」的关键点：
 *  - size 缓存：文件长度是固定属性，缓存后 seek/open 不再每次都走 length() 网络往返；
 *  - invalidate：SMB 连接断开（服务器休眠/网络切换）后立刻驱逐坏句柄，下次重建，
 *    不让失效连接滞留在池里持续报错。
 */
object SmbHandlePool {
    // smbj 的同一连接支持并发请求，句柄本身也自带锁，不再需要过去那把全局串行锁：
    // 不同文件的读可以真正同时进行（同一文件的两个 DataSource 也各自走 readAt 原子定位）。
    class Entry(val raf: SmbRandomReader, var size: Long = -1L, var lastAccess: Long) {
        /** 正在使用该句柄的 DataSource 数：>0 时空闲回收不能关它（否则播放会被静默截断） */
        var refs: Int = 0

        /** 读取期间持续刷新访问时间，长时间顺序播放不会被别的 acquire 误回收 */
        fun touch() {
            lastAccess = SystemClock.elapsedRealtime()
        }
    }
    private val map = ConcurrentHashMap<String, Entry>()
    private val lock = Any()

    fun acquire(url: String, server: SmbServer): Entry = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        // 3 分钟无引用且未访问的句柄回收，避免连接泄漏；正在使用的（refs>0）绝不回收
        val itr = map.entries.iterator()
        while (itr.hasNext()) {
            val e = itr.next().value
            if (e.refs <= 0 && now - e.lastAccess > 3 * 60 * 1000L) {
                runCatching { e.raf.close() }
                itr.remove()
            }
        }
        val existing = map[url]
        if (existing != null) {
            existing.lastAccess = now
            existing.refs++
            existing
        } else {
            val entry = Entry(SmbClient.randomAccess(server, url), lastAccess = now)
            entry.refs = 1
            map[url] = entry
            entry
        }
    }

    /** DataSource 关闭时归还引用：降到 0 后句柄才参与空闲回收 */
    fun release(entry: Entry) = synchronized(lock) {
        if (entry.refs > 0) entry.refs--
        entry.touch()
    }

    /** 缓存文件长度：命中后零网络往返，这是快进秒开的关键。 */
    fun sizeOf(entry: Entry): Long = synchronized(lock) {
        if (entry.size < 0L) entry.size = entry.raf.length()
        entry.lastAccess = SystemClock.elapsedRealtime()
        entry.size
    }

    /** 驱逐并关闭坏句柄，下次 acquire 会重建连接。 */
    fun invalidate(url: String) {
        synchronized(lock) { map.remove(url) }?.let { runCatching { it.raf.close() } }
    }
}

/**
 * 真正实现 ExoPlayer 的 DataSource 接口，让 Media3 直接随机读 SMB 文件。
 * 相比「SMB → 本地 HTTP Range → ExoPlayer」的回环中转：
 *  - 省掉每层 Range 解析与线程切换；
 *  - seek 只是一次本地 seek + 一次 SMB READ，不再重新握手；
 *  - 播放中反复 open(DataSpec) 复用同一句柄，首开与快进都是秒级。
 */
class SmbExoDataSource : BaseDataSource(true) {
    private var url: String = ""
    private var server: SmbServer? = null
    private var sourceUri: Uri? = null
    private var entry: SmbHandlePool.Entry? = null
    private var remaining = 0L
    private var absolutePos = 0L // 下一次 read 的绝对偏移，用于失效恢复与原子 seek
    private var opened = false

    /**
     * 预读缓冲（惰性分配）。
     *
     * ExoPlayer 每次只要 64KB，直连 SMB 时这就是一个来回；155 分钟的电影要几千个来回，
     * 首帧与快进都被这串往返拖住。这里按 1MB 一次读满，顺序播放时回来 16 次请求只发 1 次，
     * 缓冲窗口内的 seek 更是完全不用碰网络（快进后立刻有数据可解）。
     *
     * Media3 在 seek、轨道切换、字幕探测时都会重新 open(DataSpec)，过去每次 open 都
     * 立即分配 1MB，快进时就是一串分配/GC 抖动——现在推迟到首次真正读数据时才建。
     */
    private var cache: ByteArray? = null
    private var cacheStart = -1L
    private var cacheLength = 0

    private fun resetCache() {
        cacheStart = -1L
        cacheLength = 0
    }

    override fun open(dataSpec: DataSpec): Long {
        val raw = dataSpec.uri.toString()
        // 播放地址是给持久化用的（路径带百分号编码），传输层不认编码后的路径，
        // 必须先还原成真实字符，否则 open 阶段就是 STATUS_OBJECT_NAME_NOT_FOUND。
        url = SmbClient.nativeUrl(raw)
        sourceUri = dataSpec.uri
        val srv = SmbPlaybackRegistry.serverFor(raw) ?: SmbPlaybackRegistry.serverFor(url)
            ?: throw IOException("SMB 会话已失效，请返回网络页重新打开视频")
        server = srv
        val e = SmbHandlePool.acquire(url, srv)
        entry = e
        val total = SmbHandlePool.sizeOf(e)
        val pos = dataSpec.position.coerceAtMost(total)
        absolutePos = pos
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            (total - pos).coerceAtLeast(0L)
        } else {
            minOf(dataSpec.length, total - pos).coerceAtLeast(0L)
        }
        // Media3 的 seek 也走 open(DataSpec)，如果新位置落在预读窗口内，
        // 直接复用缓存——缓冲窗口内的快进/快退完全不碰网络，立刻有数据可解。
        // 只有位置跑到窗口外（或首次打开）才丢弃缓存重新预读。
        val hitOffset = pos - cacheStart
        if (cacheLength <= 0 || hitOffset < 0 || hitOffset >= cacheLength) {
            resetCache()
        }
        opened = true
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val e = entry ?: return C.RESULT_END_OF_INPUT
        e.touch()
        val want = minOf(readLength.toLong(), remaining).toInt()

        // 1) 命中预读窗口：纯内存拷贝，零网络往返（快进落在窗口内时尤其明显）
        val buf = cache ?: ByteArray(READ_AHEAD_BYTES).also { cache = it }
        val hitOffset = absolutePos - cacheStart
        if (cacheLength > 0 && hitOffset >= 0 && hitOffset < cacheLength) {
            val n = minOf(want, cacheLength - hitOffset.toInt())
            System.arraycopy(buf, hitOffset.toInt(), buffer, offset, n)
            absolutePos += n
            remaining -= n
            bytesTransferred(n)
            return n
        }

        // 2) 未命中：一次读满 512KB（不超过剩余长度），readAt 自带原子定位
        val chunk = minOf(READ_AHEAD_BYTES.toLong(), remaining).toInt()
        val read = readIntoCache(e, chunk, buf)
        if (read <= 0) {
            remaining = 0L
            return C.RESULT_END_OF_INPUT
        }
        cacheStart = absolutePos
        cacheLength = read
        val n = minOf(want, read)
        System.arraycopy(buf, 0, buffer, offset, n)
        absolutePos += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    /** 把 [size] 字节从 [absolutePos] 读进预读缓冲；连接失效时重建句柄再试一次 */
    private fun readIntoCache(
        e: SmbHandlePool.Entry,
        size: Int,
        buf: ByteArray,
    ): Int {
        val first = try {
            e.raf.readAt(absolutePos, buf, 0, size)
        } catch (t: Throwable) {
            // SMB 连接失效：驱逐坏句柄并重建一次，避免卡在坏连接上
            SmbHandlePool.invalidate(url)
            val srv = server ?: return -1
            val fresh = SmbHandlePool.acquire(url, srv)
            entry = fresh
            try {
                fresh.raf.readAt(absolutePos, buf, 0, size)
            } catch (t2: Throwable) {
                return -1
            }
        }
        return first
    }

    override fun getUri(): Uri? = sourceUri

    override fun close() {
        if (opened) {
            transferEnded()
            opened = false
        }
        resetCache()
        // 句柄交回池里复用（归还引用后才参与空闲回收），不在此关闭
        entry?.let { SmbHandlePool.release(it) }
        entry = null
    }

    private companion object {
        /** 预读块大小：1MB（内网吞吐下一次约 50-60ms），比 512KB 少一半请求数 */
    const val READ_AHEAD_BYTES = 1024 * 1024
    }
}

/**
 * 按 URI scheme 分发：smb:// 走直连数据源，其余（content/file/http）走 Media3 默认源。
 * DataSource.Factory.createDataSource() 拿不到 URI，只能在 open(DataSpec) 时决定，
 * 所以用一个「路由」DataSource 包一层。
 */
class SmbRoutingDataSource(private val context: Context) : BaseDataSource(false) {
    private var delegate: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        val d: DataSource = if (scheme?.equals("smb", ignoreCase = true) == true) {
            SmbExoDataSource()
        } else {
            DefaultDataSource(context, false)
        }
        delegate = d
        return d.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        // open 失败或 close 之后 Media3 异常路径再进来时不能崩：按 EOF 收场
        val d = delegate ?: return C.RESULT_END_OF_INPUT
        val n = d.read(buffer, offset, readLength)
        if (n > 0) bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = delegate?.getUri()

    override fun close() {
        delegate?.close()
        delegate = null
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbRoutingDataSource(context)
    }
}
