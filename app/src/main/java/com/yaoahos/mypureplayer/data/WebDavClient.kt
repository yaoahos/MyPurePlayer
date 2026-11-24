package com.yaoahos.mypureplayer.data

import android.util.Base64
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 极简 WebDAV 客户端：只需要 MKCOL / PUT / GET / PROPFIND 几个动作，不为一个备份功能拖入 OkHttp。
 *
 * **为什么不用 HttpURLConnection**：它内置一张方法白名单（GET/POST/HEAD/PUT/DELETE/OPTIONS…），
 * 调用 `setRequestMethod("PROPFIND")` 直接抛
 * `ProtocolException: Expected one of [...] but was PROPFIND`，MKCOL 同理。
 * 反射改私有字段在 HTTPS 的代理连接上还会漏改底层对象，服务端照样回 405。
 * 所以这里用 Socket 自己写一个只够备份用的 HTTP/1.1 客户端，任意方法都能发。
 *
 * 所有方法都是阻塞的，调用方必须在 IO 协程里执行。
 */
object WebDavClient {

    private const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 3

    data class Result(val code: Int, val body: ByteArray)

    /** 目录项：名字、大小、修改时间。云端备份靠它列出「各设备各一份」。 */
    data class Entry(
        val name: String,
        val size: Long,
        val modified: Long,
        val isDir: Boolean,
    )

    fun normalizeUrl(raw: String): String {
        val url = raw.trim().trimEnd('/')
        val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("WebDAV 地址格式不正确") }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("WebDAV 地址必须以 http:// 或 https:// 开头")
        }
        return url
    }

    fun put(url: String, username: String, password: String, bytes: ByteArray) {
        val result = request("PUT", normalizeUrl(url), username, password, bytes)
        requireSuccess(result.code, "配置上传失败")
    }

    fun get(url: String, username: String, password: String): ByteArray {
        val result = request("GET", normalizeUrl(url), username, password, null)
        requireSuccess(result.code, "配置下载失败")
        return result.body
    }

    /**
     * 逐级建目录。已存在的目录服务端通常回 405（Method Not Allowed），按成功处理；
     * 先建父级再建子级，所以出现 409（父目录不存在）就是真的没权限/路径错。
     */
    fun ensureFolder(baseUrl: String, folder: String, username: String, password: String) {
        val base = normalizeUrl(baseUrl)
        val segments = folder.trim().trim('/').split('/').filter { it.isNotBlank() }
        var current = base
        for (segment in segments) {
            current += "/" + URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            val code = request("MKCOL", current, username, password, null).code
            if (code !in 200..299 && code != 405) throw IllegalStateException(describe(code, "创建备份文件夹失败"))
        }
    }

    /** 用 PROPFIND 探测目录本身：2xx（含 207 Multi-Status）说明地址与账号可用。 */
    fun check(baseUrl: String, username: String, password: String) {
        val result = request("PROPFIND", normalizeUrl(baseUrl), username, password, ByteArray(0))
        if (result.code !in 200..299) {
            throw IllegalStateException(describe(result.code, "连接失败"))
        }
    }

    /**
     * 列出目录内容（Depth: 1）。
     *
     * [allowMissing] 为真时把 404 当成「目录还不存在」返回空列表：这个目录是应用自己建的，
     * 首次使用（一次都还没上传）时它本来就不存在，报「HTTP 404」只会让人以为备份坏了。
     */
    fun list(
        dirUrl: String,
        username: String,
        password: String,
        allowMissing: Boolean = true,
    ): List<Entry> {
        val url = normalizeUrl(dirUrl).trimEnd('/') + "/"
        val body = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/>" +
                "</d:prop></d:propfind>"
            ).toByteArray(Charsets.UTF_8)
        val result = request("PROPFIND", url, username, password, body, depth = 1)
        if (result.code == 404 && allowMissing) return emptyList()
        requireSuccess(result.code, "读取云端备份列表失败")
        return parseEntries(String(result.body, Charsets.UTF_8), url)
    }

    /** GET 但把「没有这份文件」当成 null：覆写前取旧版做回滚点时不该因为首次上传而失败。 */
    fun getOrNull(url: String, username: String, password: String): ByteArray? =
        runCatching { get(url, username, password) }.getOrNull()

    /** 删除云端文件；404 视为已经不存在。 */
    fun delete(url: String, username: String, password: String) {
        val result = request("DELETE", normalizeUrl(url), username, password, null)
        if (result.code !in 200..299 && result.code != 404) {
            throw IllegalStateException(describe(result.code, "删除云端备份失败"))
        }
    }

    fun describe(code: Int, fallback: String): String = when (code) {
        401 -> "认证失败，请检查账号密码"
        403 -> "没有访问权限，请检查 WebDAV 服务的权限设置"
        404 -> "路径不存在，请检查地址与备份文件夹"
        405 -> "服务端不允许该操作"
        409 -> "父目录不存在，请检查地址与备份文件夹"
        500, 502, 503, 504 -> "服务端错误，多为存储离线或超时（HTTP $code）"
        in 200..299 -> fallback
        else -> "$fallback（HTTP $code）"
    }

    private fun requireSuccess(code: Int, fallback: String) {
        if (code !in 200..299) throw IllegalStateException(describe(code, fallback))
    }

    /** 解析 207 Multi-Status：取每个 response 的 href、大小与修改时间，跳过目录自身。 */
    private fun parseEntries(xml: String, requestUrl: String): List<Entry> {
        if (xml.isBlank()) return emptyList()
        val doc = runCatching {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(xml.byteInputStream(Charsets.UTF_8))
        }.getOrElse { throw IllegalStateException("云端返回的目录列表无法解析") }

        val nodes = doc.getElementsByTagNameNS("DAV:", "response")
        val out = mutableListOf<Entry>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val href = childText(node, "DAV:", "href")
            if (href.isBlank()) continue
            val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
            // 目录自身也会出现在列表里，跳过
            val full = if (decoded.startsWith("http", true)) {
                decoded
            } else {
                val base = runCatching { URI(requestUrl) }.getOrNull()
                val origin = if (base != null) "${base.scheme}://${base.rawAuthority}" else ""
                if (decoded.startsWith("/")) origin + decoded else "$origin/$decoded"
            }
            if (full.trimEnd('/') == requestUrl.trimEnd('/')) continue
            val name = decoded.trimEnd('/').substringAfterLast('/')
            if (name.isBlank()) continue

            val prop = firstChild(node, "DAV:", "propstat")
                ?.let { firstChild(it, "DAV:", "prop") }
            val isDir = prop?.getElementsByTagNameNS("DAV:", "collection")?.length?.let { it > 0 } == true
            val size = prop?.let { childText(it, "DAV:", "getcontentlength") }?.toLongOrNull() ?: 0L
            val modified = prop?.let { childText(it, "DAV:", "getlastmodified") }
                ?.let(::parseHttpDate) ?: 0L
            out += Entry(name, size, modified, isDir)
        }
        return out
    }

    private fun childText(parent: Element, ns: String, tag: String): String {
        val list = parent.getElementsByTagNameNS(ns, tag)
        return if (list.length > 0) list.item(0).textContent.orEmpty().trim() else ""
    }

    private fun firstChild(parent: Element, ns: String, tag: String): Element? {
        val list = parent.getElementsByTagNameNS(ns, tag)
        return list.item(0) as? Element
    }

    /** WebDAV 的 getlastmodified 是 RFC 1123 格式，解析失败按 0 处理（只影响显示排序）。 */
    private fun parseHttpDate(value: String): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time ?: 0L
    }.getOrDefault(0L)

    private fun request(
        method: String,
        url: String,
        username: String,
        password: String,
        body: ByteArray?,
        redirects: Int = 0,
        depth: Int = 0,
    ): Result {
        val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("WebDAV 地址格式不正确") }
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("WebDAV 地址格式不正确")
        val host = uri.host ?: throw IllegalArgumentException("WebDAV 地址格式不正确")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        val path = (uri.rawPath?.takeIf { it.isNotBlank() } ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")

        val socket: Socket = if (scheme == "https") {
            SSLSocketFactory.getDefault().createSocket()
        } else {
            Socket()
        }
        try {
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            if (socket is SSLSocket) {
                // 不设置这个参数时 JSSE 只校验证书链、不校验证书主机名，
                // 攻击者用任意域名的合法证书就能在 HTTPS 备份链路上冒充服务器。
                val params = socket.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                socket.sslParameters = params
                socket.startHandshake()
            }

            val head = StringBuilder()
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(host)
                .append(if (port == 80 || port == 443) "" else ":$port").append("\r\n")
            head.append("Connection: close\r\n")
            head.append("User-Agent: MyPurePlayer/1.0\r\n")
            if (username.isNotBlank()) {
                val token = Base64.encodeToString(
                    "$username:$password".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )
                head.append("Authorization: Basic ").append(token).append("\r\n")
            }
            if (method == "PROPFIND") {
                head.append("Depth: ").append(depth).append("\r\n")
                head.append("Content-Type: application/xml; charset=utf-8\r\n")
            } else if (body != null) {
                // 备份内容已整体加密，就是一段不透明字节，不必声明成 json
                head.append("Content-Type: application/octet-stream\r\n")
            }
            head.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
            head.append("\r\n")

            socket.getOutputStream().apply {
                write(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
                if (body != null) write(body)
                flush()
            }

            val input = BufferedInputStream(socket.getInputStream())
            val status = readLine(input) ?: throw IllegalStateException("WebDAV 服务没有返回响应")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IllegalStateException("WebDAV 响应格式不正确")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).lowercase()] = line.substring(i + 1).trim()
            }

            val bytes = when {
                headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> readChunked(input)
                headers["content-length"]?.toLongOrNull() != null -> readExact(input, headers["content-length"]!!.toLong())
                code == 204 || code in 100..199 -> ByteArray(0)
                else -> input.readBytes()
            }

            val location = headers["location"]
            if (code in 300..399 && !location.isNullOrBlank() && redirects < MAX_REDIRECTS) {
                val target = runCatching { URI(location) }.getOrNull()?.let { uri.resolve(it) }
                    ?: return Result(code, bytes)
                val targetPort = if (target.port > 0) target.port else if (scheme == "https") 443 else 80
                // 凭据随 Basic 头发给每一次请求，只允许在同协议 + 同主机 + 同端口内跟随，
                // 否则一个恶意/被劫持的服务端用 302 就能把备份账号密码带走。
                if (target.scheme?.lowercase() != scheme ||
                    target.host?.equals(host, ignoreCase = true) != true ||
                    targetPort != port
                ) return Result(code, bytes)
                return request(method, target.toString(), username, password, body, redirects + 1, depth)
            }
            return Result(code, bytes)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** HTTP 头按 ISO-8859-1 读；遇到 \n 结束，顺手去掉 \r。 */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(StandardCharsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun readExact(input: InputStream, length: Long): ByteArray {
        if (length <= 0L) return ByteArray(0)
        val out = ByteArrayOutputStream(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val buffer = ByteArray(8 * 1024)
        var left = length
        while (left > 0L) {
            val n = input.read(buffer, 0, buffer.size.coerceAtMost(left.toInt()))
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    /** WebDAV 服务端常用 chunked 返回 207 XML，必须解出来（虽然这里只看状态码）。 */
    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // 读完尾部的 trailer 头
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                }
                break
            }
            out.write(readExact(input, size.toLong()))
            readLine(input) // 块尾的 CRLF
        }
        return out.toByteArray()
    }
}
