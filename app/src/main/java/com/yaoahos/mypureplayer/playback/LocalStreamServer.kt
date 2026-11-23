package com.yaoahos.mypureplayer.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.SmbClient
import com.yaoahos.mypureplayer.data.SmbRandomReader
import com.yaoahos.mypureplayer.data.SmbServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地视频流服务（127.0.0.1）。
 *
 * 为什么需要它：Android 10+ 分区存储下应用拿不到视频的真实文件路径，而 libVLC 通过
 * `Media(FileDescriptor)` / `Media(AssetFileDescriptor)` 拿到的是「长度未知」的 fd，
 * 内核无法 seek，播几百毫秒就卡住或直接 EndReached（MediaStore 返回的 declaredLength = -1）。
 *
 * 这里在回环地址上开一个支持 HTTP Range 的小服务，把 content:// 以标准 HTTP 资源的形式
 * 交给 libVLC —— 长度、Content-Range、随机 seek 全部正常，且不需要把大文件复制到缓存。
 */
object LocalStreamServer {

    private const val TAG = "LocalStream"
    /** SMB 单次读缓冲：64KB 时一次 2MB 探测要 32 次 SMB READ 往返，放大到 256KB 降到 4~8 次 */
    private const val CHUNK = 256 * 1024

    /** 同时服务的连接数上限：防止异常客户端刷连接占满线程池 */
    private val servePermits = kotlinx.coroutines.sync.Semaphore(8)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry = ConcurrentHashMap<String, Entry>()

    private data class Entry(
        val uri: Uri,
        val mime: String,
        val sizeHint: Long,
        /** 非空的 SMB 条目由 SmbRandomAccessFile 供数，用于 DLNA 电视经 HTTP 拉流 */
        val smbServer: SmbServer? = null,
        val smbUrl: String? = null,
    ) {
        /**
         * 最近一次被访问的时间（单调时钟），用于 token 滑动过期。
         *
         * token 本身不带时间信息，同网段任何人拿到一次 URL 就能一直读；
         * 这里让「超过 TTL 没有任何新请求」的条目失效 —— 既给了有效期，
         * 又不会把播了十几个小时的流踢掉（播放中每隔几秒就有 Range 请求）。
         * 用 elapsedRealtime 而不是 wall clock，改系统时间不会让 token 永久有效。
         */
        @Volatile
        var lastAccess: Long = SystemClock.elapsedRealtime()

        /**
         * SMB 播放句柄复用：播放过程中 Media3 会连续发几十上百个 Range 请求，
         * 每次都新建句柄意味着多 3 次 SMB 往返（Open/QueryInfo/Close），
         * 这是快进"一卡一卡"的主因。缓存同一个句柄，按偏移读。
         * 读取走 [SmbRandomReader.readAt]：定位与读在同一次加锁内完成，
         * serve() 每个请求一个协程也不会串位。
         */
        @Volatile
        var smbHandle: SmbRandomReader? = null
        val smbLock = Any()
        @Volatile
        var smbSize: Long = -1L

        fun closeSmbHandle() {
            synchronized(smbLock) {
                runCatching { smbHandle?.close() }
                smbHandle = null
                smbSize = -1L
            }
        }
    }

    /** token 空闲多久后失效：6 小时没有任何请求即认为这个 URL 已经没人用了 */
    private const val TOKEN_TTL_MS = 6 * 60 * 60 * 1000L

    /** 清掉过期条目，顺便避免 registry 随播放过的每个文件无限增长 */
    private fun pruneExpired() {
        val now = SystemClock.elapsedRealtime()
        registry.entries.removeAll { (now - it.value.lastAccess > TOKEN_TTL_MS).also { expired ->
            if (expired) it.value.closeSmbHandle()
        } }
    }

    @Volatile
    private var port: Int = 0
    private var serverSocket: ServerSocket? = null

    @Synchronized
    private fun ensureStarted(context: Context): Int {
        if (port > 0) return port
        // 监听所有网卡：本机播放器仍走 127.0.0.1，投屏时局域网设备用同一个端口访问。
        // token 是随机 UUID，外部设备猜不到，不会暴露整个存储目录。
        val socket = ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
        serverSocket = socket
        port = socket.localPort
        Log.i(TAG, "stream server on 0.0.0.0:$port")
        scope.launch { acceptLoop(context.applicationContext, socket) }
        return port
    }

    /** 把本地视频登记成一个 http 地址（本机播放器用回环地址） */
    fun urlFor(context: Context, item: com.yaoahos.mypureplayer.data.VideoItem): String =
        buildUrl(context, item, "127.0.0.1")

    /** 投屏给局域网设备用的地址（同一个 token，换成设备在 LAN 上可达的 IP） */
    fun lanUrlFor(context: Context, item: com.yaoahos.mypureplayer.data.VideoItem): String? {
        val ip = localIpAddress() ?: return null
        return buildUrl(context, item, ip)
    }

    /**
     * SMB 视频投屏地址：电视无法识别 smb://，由本服务用 SMB 随机读转成
     * 支持 Range 的 HTTP 流，电视按需拉取而不必先把几个 GB 文件下载下来。
     */
    fun lanUrlForSmb(
        context: Context,
        item: com.yaoahos.mypureplayer.data.VideoItem,
        server: SmbServer,
    ): String? {
        val ip = localIpAddress() ?: return null
        return smbUrl(context, item, server, ip)
    }

    private fun smbUrl(
        context: Context,
        item: com.yaoahos.mypureplayer.data.VideoItem,
        server: SmbServer,
        host: String,
    ): String {
        val port = ensureStarted(context)
        pruneExpired()
        val existing = registry.entries.firstOrNull {
            it.value.smbUrl == item.uri && it.value.smbServer?.id == server.id
        }?.key
        val token = existing ?: UUID.randomUUID().toString().replace("-", "").also {
            registry[it] = Entry(
                uri = Uri.EMPTY,
                mime = mimeOf(item.fileName),
                sizeHint = item.sizeBytes,
                smbServer = server,
                smbUrl = item.uri,
            )
        }
        val ext = Formats.extensionOf(item.fileName).ifBlank { "mp4" }
        return "http://$host:$port/v/$token.$ext"
    }

    private fun buildUrl(
        context: Context,
        item: com.yaoahos.mypureplayer.data.VideoItem,
        host: String,
    ): String {
        val p = ensureStarted(context)
        pruneExpired()
        val existing = registry.entries.firstOrNull { it.value.uri.toString() == item.uri }?.key
        val token = existing ?: UUID.randomUUID().toString().replace("-", "").also {
            registry[it] = Entry(Uri.parse(item.uri), mimeOf(item.fileName), item.sizeBytes)
        }
        val ext = Formats.extensionOf(item.fileName).ifBlank { "mp4" }
        return "http://$host:$p/v/$token.$ext"
    }

    /** 取当前 Wi-Fi / 以太网的 IPv4，优先家用网段 */
    private fun localIpAddress(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { ni -> ni.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
        candidates.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") }
            ?: candidates.firstOrNull()
    }.getOrNull()

    /** MIME 全项目统一映射（见 [Formats.videoMimeOf]），未知封装回 octet-stream */
    private fun mimeOf(fileName: String): String = Formats.videoMimeOf(fileName)

    private suspend fun acceptLoop(context: Context, socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                // accept 遇到瞬时资源错误时不能停服，否则本机播放与投屏会一起失效；
                // 只有 socket 已关闭才退出循环。
                if (socket.isClosed) break
                delay(200)
                continue
            }
            scope.launch {
                // 并发上限：异常客户端（连接后不发数据）最多占满 8 个许可，
                // 不会把 IO 线程池耗尽影响正常播放
                servePermits.withPermit { runCatching { serve(context, client) } }
            }
        }
    }

    private fun serve(context: Context, socket: Socket) {
        socket.use { s ->
            s.soTimeout = 20_000
            // 顺带清理过期条目：投屏结束后若不再生成新 URL，SMB 句柄也能被回收
            pruneExpired()
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]

            var rangeHeader: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(':').trim()
                }
            }

            val name = path.substringBefore('?').substringAfterLast('/')
            val token = name.substringBefore('.')
            val entry = registry[token]
            if (entry == null) {
                writeSimple(s, 404, "Not Found", "")
                return
            }
            // 滑动过期：播放过程中每个 Range 请求都会刷新时间，只有彻底没人用的地址才失效
            if (SystemClock.elapsedRealtime() - entry.lastAccess > TOKEN_TTL_MS) {
                registry.remove(token)
                Log.w(TAG, "串流地址已过期，拒绝访问")
                writeSimple(s, 410, "Gone", "地址已过期")
                return
            }
            entry.lastAccess = SystemClock.elapsedRealtime()

            val smbServer = entry.smbServer
            val smbUrl = entry.smbUrl
            if (smbServer != null && smbUrl != null) {
                serveSmb(s, entry, smbServer, smbUrl, method, rangeHeader)
                return
            }

            val pfd = runCatching { context.contentResolver.openFileDescriptor(entry.uri, "r") }
                .getOrNull() ?: run {
                writeSimple(s, 404, "Not Found", "")
                return
            }

            pfd.use { descriptor ->
                val total = descriptor.statSize.takeIf { it > 0 } ?: entry.sizeHint
                if (total <= 0) {
                    writeSimple(s, 500, "Internal Server Error", "")
                    return
                }

                val r = resolveRange(rangeHeader, total)
                if (r == null) {
                    writeSimple(s, 416, "Range Not Satisfiable", "Content-Range: bytes */$total\r\n")
                    return
                }
                val start = r.first
                val end = r.second
                val partial = r.third

                val length = end - start + 1
                val header = buildString {
                    append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${entry.mime}\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: $length\r\n")
                    if (partial) append("Content-Range: bytes $start-$end/$total\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }

                if (method == "HEAD") {
                    s.getOutputStream().write(header.toByteArray())
                    s.getOutputStream().flush()
                    return
                }

                FileInputStream(descriptor.fileDescriptor).use { fis ->
                    fis.channel.position(start)
                    val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                    out.write(header.toByteArray())
                    var remaining = length
                    val buffer = ByteArray(CHUNK)
                    while (remaining > 0) {
                        val want = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = fis.read(buffer, 0, want)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                    out.flush()
                }
            }
        }
    }

    /**
     * 解析 Range 头，返回 (start, end, 是否 206)。
     * 无 Range 头按整段 200 处理，`bytes=N-` 一直给到文件结尾：
     * 两者都是流式发送（每次 256KB），不会按文件大小分配内存。
     */
    private fun resolveRange(
        rangeHeader: String?,
        total: Long,
    ): Triple<Long, Long, Boolean>? {
        if (total <= 0L) return null
        var start = 0L
        var end = total - 1
        var partial = false
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").substringBefore(',')
            val fromStr = spec.substringBefore('-').trim()
            val toStr = spec.substringAfter('-').trim()
            partial = true
            if (fromStr.isNotEmpty()) {
                start = (fromStr.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                if (toStr.isNotEmpty()) {
                    end = toStr.toLongOrNull() ?: (total - 1)
                }
            } else if (toStr.isNotEmpty()) {
                // bytes=-N 表示末尾 N 字节
                val suffix = (toStr.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                start = (total - suffix).coerceAtLeast(0L)
            }
        }
        end = end.coerceAtMost(total - 1)
        if (start > end || start >= total) return null
        return Triple(start, end, partial)
    }

    /**
     * 用 SMB 随机读给 DLNA 电视提供一个支持 Range 的 HTTP 流。
     *
     * 关键点是「边读边发」而不是先读进内存再发：
     * 过去每个请求最多 2MB，电视要连续放一部高码率片子，就得每 0.1~0.3 秒重开一次
     * TCP 连接、重新发一次 Range——投屏表现就是画面一顿一顿、缓冲圈反复转甚至放不出来。
     * 现在一个请求按 Range 一直流到结尾（电视关连接即自然结束），
     * 每次只占一个 256KB 缓冲，内存可控，吞吐也不再被请求数卡住。
     */
    private fun serveSmb(
        socket: Socket,
        entry: Entry,
        server: SmbServer,
        url: String,
        method: String,
        rangeHeader: String?,
    ) {
        // 复用同一个 SMB 句柄：首次打开时创建一次，之后每个 Range 只做 seek+read，
        // 不再重复 Open/QueryInfo/Close 三次 SMB 往返（快进卡顿的主因）。
        val raf: SmbRandomReader? = synchronized(entry.smbLock) {
            entry.smbHandle ?: runCatching { SmbClient.randomAccess(server, url) }
                .getOrNull()?.also { entry.smbHandle = it }
        }
        if (raf == null) {
            writeSimple(socket, 404, "Not Found", "")
            return
        }
        // 文件大小缓存：优先用登记时的 sizeHint，省掉每次 raf.length() 的 QUERY_INFO 往返。
        val total = synchronized(entry.smbLock) {
            if (entry.smbSize <= 0L) {
                entry.smbSize = runCatching { raf.length() }.getOrDefault(0L)
            }
            entry.smbSize
        }.let { if (it > 0L) it else entry.sizeHint }
        if (total <= 0L) {
            writeSimple(socket, 500, "Internal Server Error", "")
            return
        }
        val r = resolveRange(rangeHeader, total)
        if (r == null) {
            writeSimple(socket, 416, "Range Not Satisfiable", "Content-Range: bytes */$total\r\n")
            return
        }
        val start = r.first
        val end = r.second
        val partial = r.third

        val length = end - start + 1
        val header = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${entry.mime}\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (partial) append("Content-Range: bytes $start-$end/$total\r\n")
            append("Connection: close\r\n\r\n")
        }
        if (method == "HEAD") {
            runCatching {
                socket.getOutputStream().write(header.toByteArray())
                socket.getOutputStream().flush()
            }
            return
        }

        // 电视端播放器普遍开了交互式小缓冲，TCP 发送窗口小了就会被判定为卡顿
        runCatching { socket.sendBufferSize = 512 * 1024 }
        runCatching { socket.tcpNoDelay = true }

        val out = BufferedOutputStream(socket.getOutputStream(), CHUNK)
        out.write(header.toByteArray())
        out.flush()

        var pos = start
        val buf = ByteArray(CHUNK)
        try {
            while (pos <= end) {
                val want = minOf(buf.size.toLong(), end - pos + 1).toInt()
                // 定位与读在同一次加锁内完成（readAt），并发 Range 请求不会让读位串掉
                val n = synchronized(entry.smbLock) {
                    runCatching { raf.readAt(pos, buf, 0, want) }.getOrDefault(-1)
                }
                if (n <= 0) {
                    // 连接可能已经失效：驱逐坏句柄，下次请求会重建
                    Log.w(TAG, "SMB 读取中断 offset=$pos")
                    entry.closeSmbHandle()
                    break
                }
                out.write(buf, 0, n)
                pos += n
            }
            out.flush()
        } catch (t: Throwable) {
            // 电视快进/换片会直接断开旧连接，这里属于正常收尾，不当错误上报
            Log.d(TAG, "投屏连接结束于 offset=$pos: ${t.javaClass.simpleName}")
        }
    }

    private fun writeSimple(socket: Socket, code: Int, reason: String, extraHeaders: String) {
        val body = ""
        val response = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Length: ${body.length}\r\n")
            append("Connection: close\r\n")
            append(extraHeaders)
            append("\r\n")
            append(body)
        }
        runCatching {
            socket.getOutputStream().write(response.toByteArray())
            socket.getOutputStream().flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val ch = input.read()
            if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
            if (ch == '\n'.code) return sb.toString().trimEnd('\r')
            if (ch == '\r'.code) continue
            sb.append(ch.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }
}
