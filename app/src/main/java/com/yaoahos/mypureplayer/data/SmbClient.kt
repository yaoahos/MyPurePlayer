package com.yaoahos.mypureplayer.data

import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.yaoahos.mypureplayer.core.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 一次连接探测的结果。
 * [hint] 非空表示「连上了，但还有需要用户处理的点」，例如没填默认共享目录。
 */
data class SmbProbe(
    val shares: List<String>,
    val hint: String? = null,
)

/**
 * SMB2/SMB3 目录浏览与随机读取。
 *
 * 传输层是 smbj 0.15：一条 TCP 连接上可以同时挂多个请求（SMB2 多路复用），
 * 完整支持 SMB3 的签名与传输加密，仅支持 SMB2/3。
 *
 * 分工：浏览 / 过滤 / 字幕探测 / 抽帧 / 播放全部走这里，不再把 smb:// 交给外部播放器。
 */
object SmbClient {

    private data class Key(
        val id: Long,
        val host: String,
        val user: String,
        val pass: String,
        val domain: String,
    )

    /** 播放用连接（列表、字幕、播放数据源） */
    private val connections = ConcurrentHashMap<Key, SmbConnection>()

    /**
     * 预览专用连接（抽帧、读封面）。
     *
     * smbj 本身支持一条连接上并发多请求，理论上不必再拆。但抽帧是一屏十几个文件
     * 同时挖头部，会把 SequenceWindow 的并发槽占满，让随后的播放读排在后面。
     * 拆成两条连接后，抽帧再密也不会影响起播与快进，代价只是一条空闲 TCP。
     */
    private val previewConnections = ConcurrentHashMap<Key, SmbConnection>()

    private fun keyOf(server: SmbServer) =
        Key(server.id, server.host, server.username, server.password, server.domain)

    private fun hostOf(server: SmbServer): String = SmbInput.parse(server.host).host

    /**
     * 取连接并在失效时重建一次。
     *
     * NAS 休眠、Wi-Fi 切换、路由器重启都会让已有 TCP 静默失效，smbj 抛
     * TransportException / SMBRuntimeException。这类失败重连一次通常就好了；
     * 而「路径不存在」「拒绝访问」这类业务错误重试没有意义，直接抛给上层翻译。
     */
    private fun <T> withConnection(
        server: SmbServer,
        preview: Boolean,
        block: (SmbConnection) -> T,
    ): T {
        val map = if (preview) previewConnections else connections
        val key = keyOf(server)
        // 建连（最长 20s 的 TCP 握手）放在 map 之外做，再用 putIfAbsent 原子登记：
        // computeIfAbsent 会把建连关进桶锁，同 key 并发请求会被一起阻塞到超时。
        var conn = map[key]
        if (conn == null || !conn.isAlive) {
            // 缓存的连接可能已被 NAS 单方面断开（休眠唤醒、Wi-Fi 切换）。
            // 拿这种「死连接」去做读写，要等满读写超时（几十秒）才抛 TransportException，
            // 表现就是历史里点开视频转圈很久。取用时先探活，死的直接换一条。
            val stale = conn
            val fresh = SmbConnection.open(server)
            val raced = if (stale == null) {
                map.putIfAbsent(key, fresh)
            } else if (map.replace(key, stale, fresh)) {
                runCatching { stale.close() }
                null
            } else {
                map[key]
            }
            if (raced != null) {
                runCatching { fresh.close() }
                conn = raced
            } else {
                conn = fresh
            }
        }
        return try {
            block(conn)
        } catch (t: Throwable) {
            if (!isTransportFailure(t)) throw t
            Log.w("SmbClient", "连接失效，重建一次：${server.host}", t)
            // 原子替换：两个线程同时重建时只有 replace 成功的那条进 map，
            // 输家关掉自己新建的连接，改用赢家那条 —— 否则先建的那条会永久泄漏。
            val fresh = SmbConnection.open(server)
            if (map.replace(key, conn, fresh)) {
                runCatching { conn.close() }
                block(fresh)
            } else {
                runCatching { fresh.close() }
                val winner = map[key] ?: run {
                    // 极端情况：赢家刚建完又被第三个线程移走。兜底重建一条
                    val again = SmbConnection.open(server)
                    map[key] = again
                    again
                }
                block(winner)
            }
        }
    }

    private fun isTransportFailure(t: Throwable): Boolean {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 8) {
            if (current is TransportException) return true
            // smbj 在共享/连接已关闭时抛的是普通 SMBRuntimeException，
            // 消息里带 connection / closed / transport。这类错误不是业务错误：
            // 多见于另一线程刚重建连接、或 NAS 休眠后连接被回收，重建一次即可。
            val msg = current.message.orEmpty().lowercase()
            if (current is SMBRuntimeException &&
                (msg.contains("connection") || msg.contains("not connected") ||
                    msg.contains("transport") || msg.contains("closed"))
            ) {
                return true
            }
            if (current is IOException && msg.contains("socket")) return true
            current = current.cause
            depth++
        }
        return false
    }

    /** 目录列表缓存。
     *
     * 进出文件夹、从播放页返回、切回网络页都会重新列一次目录，
     * 缓存一份最近结果，界面可以先用它把列表画出来，再在后台刷新，
     * 这样「进文件夹要等一下」就变成「立刻有内容」。
     */
    private data class CachedDir(val entries: List<SmbEntry>, val at: Long)
    private val dirCache = ConcurrentHashMap<Pair<Long, String>, CachedDir>()
    // 网络共享是只读浏览，目录结构几乎不会变；30 秒太短，进出两层文件夹再回来就过期，
    // 每次都要重列一次（多个 SMB 往返，表现为「进文件夹转圈卡一下」）。放到 5 分钟，
    // 配合下拉刷新 force 强制重列，既秒开又不牺牲数据新鲜度。
    private const val DIR_CACHE_MS = 5 * 60_000L

    /** 同步读缓存：没有则返回 null（不阻塞 UI） */
    fun cachedList(server: SmbServer, path: String): List<SmbEntry>? =
        dirCache[server.id to normalizeDir(path)]?.entries

    /** 服务器信息变更后清掉连接与各类缓存 */
    fun invalidate(serverId: Long) {
        connections.keys.filter { it.id == serverId }.forEach { connections.remove(it)?.close() }
        previewConnections.keys.filter { it.id == serverId }
            .forEach { previewConnections.remove(it)?.close() }
        dirCache.keys.filter { it.first == serverId }.forEach { dirCache.remove(it) }
        previewHandles.keys.filter { it.startsWith("$serverId|") }.forEach { evictPreviewHandle(it) }
    }

    fun normalizeDir(path: String): String {
        val p = if (path.startsWith("smb://")) path else "smb://$path"
        return if (p.endsWith("/")) p else "$p/"
    }

    suspend fun list(
        server: SmbServer,
        path: String,
        force: Boolean = false,
    ): List<SmbEntry> = withContext(Dispatchers.IO) {
        val cacheKey = server.id to normalizeDir(path)
        if (!force) {
            dirCache[cacheKey]?.let {
                if (System.currentTimeMillis() - it.at < DIR_CACHE_MS) return@withContext it.entries
            }
        }
        val loc = SmbPath.split(nativeUrl(normalizeDir(path)))
        val host = hostOf(server)
        val files = withConnection(server, preview = false) { it.list(loc) }
        val out = files.mapNotNull { fi ->
            runCatching {
                val name = fi.fileName?.trimEnd('/')?.trim() ?: return@mapNotNull null
                if (name.isEmpty() || name == "." || name == "..") return@mapNotNull null
                val isDir = fi.isDirectoryEntry()
                // 群晖/威联通/Windows 的系统目录（#recycle、@eaDir、$RECYCLE.BIN 等）
                // 不是用户的媒体目录：混在列表里既排在前面误触，点进去又是空的或几十万条
                // 回收站条目，会被当成「反复进入同一个目录」。直接不进列表。
                if (isDir && isSystemDir(name)) return@mapNotNull null
                if (!isDir && !Formats.isVideo(name) && !Formats.isSubtitle(name)) return@mapNotNull null
                val childPath = if (loc.path.isEmpty()) name else "${loc.path}/$name"
                SmbEntry(
                    name = name,
                    url = SmbPath.join(host, SmbPath.Loc(loc.share, childPath)),
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else fi.endOfFile,
                    lastModified = runCatching { fi.lastWriteTime.toEpochMillis() }.getOrDefault(0L),
                )
            }.getOrNull()
        }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        dirCache[cacheKey] = CachedDir(out, System.currentTimeMillis())
        out
    }

    /** 只保留目录与视频（字幕不展示在浏览列表里，仅在字幕选择器中可见） */
    fun videoOnly(entries: List<SmbEntry>): List<SmbEntry> =
        entries.filter { it.isDirectory || Formats.isVideo(it.name) }

    /**
     * 探测共享里的某个文件现在还能不能打开。
     *
     * 就是一次 CREATE + CLOSE：比「列一遍父目录再找文件名」少一次往返，而且能同时
     * 发现「文件被删/改名」与「账号没有读权限」。探测走预览连接，不占用播放那条，
     * 免得用户点一下就把正在播的流挤在后面。
     */
    suspend fun probe(server: SmbServer, url: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val loc = SmbPath.split(nativeUrl(url))
            if (loc.share.isEmpty() || loc.path.isEmpty()) {
                throw SmbFailure("这不是一个共享里的文件：$url")
            }
            withConnection(server, preview = true) { it.openReader(loc).close() }
        }
    }

    /**
     * 共享里的系统/回收站目录，逐个按 NAS 厂商与 Windows 的实际名字匹配。
     *
     * 名字统一小写比较；`#recycle` 在每个共享根目录都有一份，而且里面往往还能再看到
     * 上一层的影子条目，用户点开一次后返回列表它会再次排在最前面（以 # 开头按字典序最前），
     * 很容易再点进去——这就是「#recycle 反复进入」。隐藏比让用户自己避开更可靠。
     * 需要查看回收站请用应用自己的回收站（本地页），SMB 侧不支持删除，也没有恢复语义。
     */
    private fun isSystemDir(name: String): Boolean {
        val n = name.trim().lowercase()
        // 各家 NAS 的系统目录清一色用 # / @ / . 开头：
        // 群晖 #recycle、#recycle.20240101、#snapshot、@eaDir、@Recently-Snapshot，
        // 威联通 @Recycle，Windows $RECYCLE.BIN、System Volume Information。
        // 按前缀直接排除，比逐个枚举更完整（新增版本目录也不用再改代码）。
        if (n.isEmpty() || n.startsWith("#") || n.startsWith("@") || n.startsWith(".")) return true
        if (n.startsWith("${'$'}")) return true
        return n in setOf(
            "system volume information", "lost+found", "recycler", "msdia80.vhd",
        )
    }

    /**
     * 列目录里的外挂字幕。
     *
     * 复用 [list] 的目录缓存：播放开始时再单独列一次目录会和首帧抢同一批请求。
     * 缓存命中时这里零网络请求。
     */
    suspend fun subtitlesIn(server: SmbServer, folder: String): List<SmbEntry> =
        list(server, folder)
            .filter { !it.isDirectory && Formats.isSubtitle(it.name) }

    /**
     * 把远程字幕复制到本地缓存。播放器对带账号密码的 smb:// 字幕地址支持不稳定，
     * 字幕文件又很小，落成本地文件后挂载最稳；同时避免播放时一直占用播放连接。
     */
    suspend fun cacheSubtitle(server: SmbServer, url: String, target: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                target.parentFile?.mkdirs()
                val loc = SmbPath.split(nativeUrl(url))
                val reader = withConnection(server, preview = true) { it.openReader(loc) }
                reader.use { r ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = r.read(buf, 0, buf.size)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                Unit
            }
        }

    /**
     * 探测连接是否可用。
     *
     * 填了「默认共享目录」就验那个共享，能列出来才算通。
     * 没填则只能验证到「连接 + 认证」这一步：SMB 协议没有枚举共享列表的接口，
     * smbj 也不提供（jcifs 当年是靠 IPC$ + SRVSVC RPC 自己拼的），
     * 所以这里直接引导用户填共享名，而不是报一个看不懂的错。
     */
    suspend fun test(server: SmbServer): Result<SmbProbe> = withContext(Dispatchers.IO) {
        runCatching {
            if (server.share.isNotBlank()) {
                val loc = SmbPath.split(nativeUrl(normalizeDir(server.rootPath)))
                val entries = withConnection(server, preview = false) { it.list(loc) }
                return@runCatching SmbProbe(entries.map { it.fileName }.take(8))
            }
            // 只做「能不能连上并认证」这一件事
            withConnection(server, preview = false) { it.isAlive }
            SmbProbe(
                emptyList(),
                hint = "已连上服务器；请在「默认共享目录」里填共享名（如 media）—— SMB 协议不提供共享列表",
            )
        }.recoverCatching { e ->
            // 失败时补一次分层探测，让提示能指出是解析、端口还是认证的问题
            throw SmbDiagnostics.explain(server, e)
        }
    }

    /** 把 SMB 目录条目映射成统一的视频条目 */
    fun toVideoItems(server: SmbServer, entries: List<SmbEntry>, folderPath: String): List<VideoItem> {
        val folderName = Formats.baseName(folderPath).ifBlank { server.name }
        return entries
            .filter { !it.isDirectory && Formats.isVideo(it.name) }
            .map { entry ->
                VideoItem(
                    id = entry.url,
                    title = Formats.stripExtension(entry.name),
                    fileName = entry.name,
                    uri = entry.url,
                    source = MediaSource.SMB,
                    sizeBytes = entry.sizeBytes,
                    dateModified = entry.lastModified,
                    folderName = folderName,
                    folderKey = normalizeDir(folderPath),
                    serverId = server.id,
                )
            }
            .sortedBy { it.fileName.lowercase() }
    }

    /**
     * 生成带口令的播放地址：外部播放器/投屏需要 user/pass，
     * 而界面与持久化里保存的是不含口令的地址，避免口令散落在历史记录/列表中。
     */
    fun playableUrl(server: SmbServer, item: VideoItem): String = playableUrl(server, item.uri)

    fun playableUrl(server: SmbServer, rawUrl: String): String {
        // 只取 smb:// 之后的第一个 '/' 后面那段，避免服务器地址与地址栏里写的不完全一致时
        // 把主机名重复拼进路径
        val withoutScheme = rawUrl.removePrefix("smb://")
        val slash = withoutScheme.indexOf('/')
        val path = if (slash >= 0) withoutScheme.substring(slash + 1) else ""
        val encodedPath = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }
        val auth = if (server.username.isBlank()) {
            ""
        } else {
            val user = encodeSegment(server.username)
            val pass = encodeSegment(server.password)
            val domainPrefix = if (server.domain.isBlank()) "" else "${encodeSegment(server.domain)};"
            "$domainPrefix$user:$pass@"
        }
        return "smb://$auth${server.host}/$encodedPath"
    }

    /**
     * [playableUrl] 的「不含凭据」版本：MediaItem / DataSpec / 媒体会话里只放这一份。
     * 路径仍保持百分号编码（与持久化 URL 一致），进程内凭据由 [SmbPlaybackRegistry] 关联。
     */
    fun playableUrlWithoutCredentials(rawUrl: String): String {
        val body = rawUrl.removePrefix("smb://")
        val slash = body.indexOf('/')
        val host = (if (slash >= 0) body.substring(0, slash) else body).substringAfterLast('@')
        if (host.isBlank()) return rawUrl
        val path = if (slash >= 0) body.substring(slash) else ""
        return "smb://$host$path"
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /**
     * 把 [playableUrl] 产出的地址还原成真实字符。
     *
     * playableUrl() 为了持久化安全对每一段路径做了百分号编码，中文目录、带空格的
     * 片名都会变成 %E4%B8%AD / %20。这两处必须在进入传输层之前解码：
     * smbj 的 openFile() 收的是共享内真实路径，不做 URL 解码，
     * 否则服务器回 STATUS_OBJECT_NAME_NOT_FOUND，上层只看到一句
     * "The system cannot find the path specified"。
     *
     * Uri.getPath() 会解回真实字符；userinfo（账号口令）在这里直接丢掉，
     * 凭据由连接持有，不需要也不应该出现在路径里。
     */
    fun nativeUrl(rawUrl: String): String {
        if (!rawUrl.startsWith("smb://", ignoreCase = true)) return rawUrl
        // 不能用 Uri.parse().path：Android 的 Uri 会把 '#' 当 fragment、'?' 当 query，
        // 而 SMB 文件名里这两个字符完全合法。浏览页产生的地址是原样拼接的（见 SmbPath.join），
        // 一旦文件名带 # 或 ?，path 就在那里截断，SmbPath.split 拿到的共享内路径少一截，
        // 表现为「进不去这个文件夹 / 这个视频打不开」。
        // 这里手工切出 host，把 authority 之后整段都当路径，只解百分号、不解释分隔符。
        val body = rawUrl.substring(6)
        val slash = body.indexOf('/')
        if (slash < 0) return rawUrl
        val host = body.substring(0, slash).substringAfterLast('@')
        if (host.isBlank()) return rawUrl
        // playableUrl() 会做百分号编码，Uri.decode 只还原 %xx，不会把 '+' 误当空格
        val path = Uri.decode(body.substring(slash))
        return "smb://$host$path"
    }

    fun randomAccess(server: SmbServer, url: String): SmbRandomReader {
        val loc = SmbPath.split(nativeUrl(url))
        return withConnection(server, preview = false) { it.openReader(loc) }
    }

    /** 预览专用随机读：走独立连接，抽帧不会拖住正在播放的数据流 */
    fun randomAccessPreview(server: SmbServer, url: String): SmbRandomReader {
        val loc = SmbPath.split(nativeUrl(url))
        return withConnection(server, preview = true) { it.openReader(loc) }
    }

    // ------------------------------------------------------------ 预览读（抽帧 / 侧车封面）

    /**
     * 预览句柄池。
     *
     * 每次 readHead 都新建句柄意味着一整套 CREATE + QUERY_INFO + CLOSE，
     * 而侧车封面探测一口要试好几个文件名 —— 一屏二十张卡片光是「找封面」
     * 就能打上百次请求。这里按 URL 复用句柄，只做偏移读。
     */
    private class PreviewHandle(val reader: SmbRandomReader, var lastAccess: Long)
    private val previewHandles = ConcurrentHashMap<String, PreviewHandle>()
    private val previewLock = Any()

    // 句柄闲置多久就关掉：抽帧是一屏十几个文件挨个读，留太久会同时挂着十几个文件句柄
    private const val PREVIEW_IDLE_MS = 20_000L

    // 同时最多保留几个预览句柄。smbj 没有 jcifs 那种很小的连接池，但句柄本身
    // 在服务器侧也要占资源，仍然收着点。
    private const val PREVIEW_MAX_HANDLES = 8

    private fun previewHandleKey(server: SmbServer, url: String) = "${server.id}|$url"

    private fun previewHandle(server: SmbServer, url: String): SmbRandomReader = synchronized(previewLock) {
        val now = System.currentTimeMillis()
        val itr = previewHandles.entries.iterator()
        while (itr.hasNext()) {
            val e = itr.next()
            if (now - e.value.lastAccess > PREVIEW_IDLE_MS) {
                runCatching { e.value.reader.close() }
                itr.remove()
            }
        }
        val key = previewHandleKey(server, url)
        previewHandles[key]?.let {
            it.lastAccess = now
            return it.reader
        }
        // 超上限：先把最久没碰过的关掉，再建新的。
        // 读操作全程在同一把锁里，所以不会出现「正在读的句柄被关掉」。
        while (previewHandles.size >= PREVIEW_MAX_HANDLES) {
            val oldest = previewHandles.entries.minByOrNull { it.value.lastAccess } ?: break
            runCatching { oldest.value.reader.close() }
            previewHandles.remove(oldest.key)
        }
        val reader = randomAccessPreview(server, url)
        previewHandles[key] = PreviewHandle(reader, now)
        reader
    }

    private fun evictPreviewHandle(key: String) {
        val entry = synchronized(previewLock) { previewHandles.remove(key) } ?: return
        runCatching { entry.reader.close() }
    }

    /**
     * 从远程文件任意位置读一段字节（预览专用连接）。
     * 失败（文件不存在 / 连接被回收）时驱逐坏句柄，返回 null 而不是抛异常。
     */
    suspend fun readRange(
        server: SmbServer,
        url: String,
        position: Long,
        length: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (length <= 0) return@withContext null
        runCatching {
            val reader = previewHandle(server, url)
            synchronized(previewLock) {
                reader.seek(position)
                val buffer = ByteArray(length)
                var off = 0
                while (off < length) {
                    val read = reader.read(buffer, off, length - off)
                    if (read <= 0) break
                    off += read
                }
                when {
                    off <= 0 -> null
                    off == length -> buffer
                    else -> buffer.copyOf(off)
                }
            }
        }.onFailure {
            Log.w("SmbClient", "readRange failed: $url @$position+$length")
            evictPreviewHandle(previewHandleKey(server, url))
        }.getOrNull()
    }

    /**
     * 只读取文件头部，用于网络视频抽帧。
     * 抽帧只需要片头几秒的画面，读固定长度（默认 3MB）比让解码器一路 seek 便宜得多。
     */
    suspend fun readHead(server: SmbServer, url: String, maxBytes: Int): ByteArray? =
        readRange(server, url, 0L, maxBytes)
}

/**
 * 供 MediaMetadataRetriever 使用的 SMB 随机读数据源，
 * 这样网络视频也能像本地文件一样抽取缩略图与时长。
 */
class SmbMediaDataSource(
    private val server: SmbServer,
    private val url: String,
    /** 总读取预算：超过就返回 EOF 中止解码，避免解码器把整个文件拉下来 */
    private val budgetBytes: Long = 24L * 1024 * 1024,
) : MediaDataSource() {

    @Volatile
    private var handle: SmbRandomReader? = null
    private var size: Long = -1L
    private var consumed = 0L
    private var requests = 0
    @Volatile
    private var closed = false

    /**
     * 多槽预读窗口。
     *
     * 两个作用：
     * 1. Android 的 MediaExtractor 通过 MediaDataSource 读容器是按页来的 —— 实测每次
     *    只要 2048 字节。一次 SMB 请求只回 2KB 的话，一个 MKV 的头部解析要几百次请求、
     *    十几秒。一次读满 [READ_AHEAD_BYTES] 后，窗口内的请求纯内存返回。
     * 2. 解码器会在「片尾的 Cues 索引」和「第一个视频块」之间反复横跳。
     *    单窗口时每次跳回来都要重新下载同一段（实测大半是重复读），
     *    多留几槽后两处都常驻，网络请求数直接降到原来的几分之一。
     */
    private class Window(val start: Long, val length: Int, val data: ByteArray)
    private val windows = ArrayList<Window>(WINDOW_SLOTS)

    private fun serveFromCache(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val itr = windows.listIterator()
        while (itr.hasNext()) {
            val w = itr.next()
            val rel = position - w.start
            // 必须整段都落在窗口里才算命中：只回一部分（短读）会被 MediaExtractor
            // 当成「流到底了」，解码器随即放弃，表现为「读了好几 MB 却一帧都取不到」
            if (rel < 0 || rel + size > w.length) continue
            System.arraycopy(w.data, rel.toInt(), buffer, offset, size)
            itr.remove()
            windows.add(0, w)
            return size
        }
        return -1
    }

    val bytesRead: Long get() = consumed

    private fun ensure(): SmbRandomReader {
        handle?.let { return it }
        val reader = SmbClient.randomAccessPreview(server, url)
        handle = reader
        size = reader.length()
        return reader
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (buffer.isEmpty()) return 0
        // 已经 close（抽帧超时先撤了）就一律 EOF，绝不再碰连接
        if (closed) return -1
        if (consumed >= budgetBytes) return -1
        // windows 是普通 ArrayList，部分 ROM 的 MediaMetadataRetriever 会多线程并发调
        // readAt/getSize：第一次窗口查找也必须进锁，否则遍历与另一线程的结构性修改
        // （add/removeAt）并发，轻则读到撕裂窗口，重则 ConcurrentModificationException。
        val hit = synchronized(this) { serveFromCache(position, buffer, offset, size) }
        if (hit > 0) return hit

        return synchronized(this) {
            val reader = ensure()
            // 进锁后再查一次：并发调用时别人可能已经把这段读进来了
            val again = serveFromCache(position, buffer, offset, size)
            if (again > 0) return@synchronized again

            // 2) 未命中：一次读一大块进窗口（不超过预算），再把请求的那段拷出去。
            // 注意要按「窗口大小」读，而不是按请求大小读 —— 解码器每次只要 2KB，
            // 照单全收就等于把几百次小往返原样放过来了。
            val target = maxOf(READ_AHEAD_BYTES.toLong(), size.toLong())
            val want = minOf(target, budgetBytes - consumed).toInt()
            if (want <= 0) return@synchronized -1
            requests++
            reader.seek(position)
            val chunk = ByteArray(want)
            // 单次 read 不保证填满，循环读到 want 或真正 EOF 为止
            var read = 0
            while (read < want) {
                val n = reader.read(chunk, read, want - read)
                if (n <= 0) break
                read += n
            }
            if (read <= 0) return@synchronized if (consumed >= budgetBytes) -1 else 0
            consumed += read
            windows.add(0, Window(position, read, chunk))
            while (windows.size > WINDOW_SLOTS) windows.removeAt(windows.size - 1)
            val n = minOf(size, read)
            System.arraycopy(chunk, 0, buffer, offset, n)
            n
        }
    }

    private companion object {
        /** 预读窗口：一次 SMB 读满 1MB，把「每次 2KB 一小口」的往返开销摊到最薄 */
        const val READ_AHEAD_BYTES = 1024 * 1024

        /** 常驻窗口数：容器头 / Cues 索引 / 视频块 三处需要同时在线 */
        const val WINDOW_SLOTS = 4
    }

    override fun getSize(): Long {
        if (size >= 0) return size
        return try {
            synchronized(this) { ensure().length().also { size = it } }
        } catch (t: Throwable) {
            -1L
        }
    }

    override fun close() {
        synchronized(this) {
            closed = true
            runCatching { handle?.close() }
            handle = null
        }
    }
}
