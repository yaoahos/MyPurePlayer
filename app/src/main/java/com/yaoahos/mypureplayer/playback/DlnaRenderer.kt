package com.yaoahos.mypureplayer.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

private const val TAG = "Dlna"

/** 通过 SSDP 发现的 DLNA/UPnP MediaRenderer */
data class DlnaDevice(
    val name: String,
    val location: String,
    val controlUrl: String,
    val host: String,
)

/**
 * DLNA 投屏：SSDP 发现 MediaRenderer + AVTransport SOAP 推送。
 *
 * 播放内核用 Media3 后没有现成的 DLNA 投屏通道，而 Media3 Cast 只覆盖 Chromecast
 * 且依赖 Google Play Services；这里直接实现标准 UPnP AVTransport，国产电视与盒子
 * 都能扫到并控制。
 */
object DlnaRenderer {

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val ST = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** SSDP M-SEARCH 发现局域网内所有 MediaRenderer，并读取设备描述 */
    suspend fun discover(timeoutMs: Int = 5_000): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val group = InetAddress.getByName(SSDP_ADDR)
        val locations = LinkedHashSet<String>()
        MulticastSocket().use { socket ->
                socket.soTimeout = 1_000
                runCatching { socket.joinGroup(group) }
                // UDP 会丢包：同一个 M-SEARCH 连发三次，电视的应答才稳定收得到。
                // 只发一次时经常「面板空一会儿才冒出一台」，就是第一个包丢了。
                repeat(3) { round ->
                    // 第一轮按 MediaRenderer 精确匹配；后两轮用 ssdp:all 兜底，
                    // 部分国产电视只响应 ssdp:all（ST 里不带 device 类型）。
                    val st = if (round == 0) ST else "ssdp:all"
                    val request = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 2\r\n")
                        append("ST: $st\r\n")
                        append("CONNECTION: close\r\n\r\n")
                    }
                    val packet = DatagramPacket(
                        request.toByteArray(Charsets.US_ASCII),
                        request.length,
                        group,
                        SSDP_PORT,
                    )
                    runCatching { socket.send(packet) }
                        .onFailure { Log.w(TAG, "SSDP 发送失败", it) }
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(8192)
                while (System.currentTimeMillis() < deadline) {
                    val response = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(response)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (t: Throwable) {
                        Log.w(TAG, "SSDP 接收异常", t)
                        break
                    }
                    val text = String(response.data, 0, response.length, Charsets.ISO_8859_1)
                    if (!text.startsWith("HTTP/1.", ignoreCase = true)) continue
                    if (!text.contains("MediaRenderer", ignoreCase = true)) continue
                    Regex("""(?im)^LOCATION:\s*(.+)$""").find(text)
                        ?.groupValues?.get(1)?.trim()?.let { locations.add(it) }
                }
        }
        Log.i(TAG, "SSDP 发现 ${locations.size} 个 MediaRenderer 描述地址")
        locations.mapNotNull { parseDevice(it) }.distinctBy { it.controlUrl }
    }

    private suspend fun parseDevice(location: String): DlnaDevice? = withContext(Dispatchers.IO) {
        runCatching {
            // 先看目标地址再决定要不要连：SSDP 应答里的 LOCATION 是设备自报的，
            // 一条伪造应答就能把明文请求引到公网。局域网投屏只需要私有网段。
            val uri = URI(location)
            if (!isPrivateHost(uri.host.orEmpty())) {
                Log.w(TAG, "跳过非局域网设备描述：$location")
                return@runCatching null
            }
            val connection = URL(location).openConnection() as HttpURLConnection
            // 不跟随 3xx：LOCATION 已是设备自报地址，自动跳转会绕过上面的局域网校验
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            val xml = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val name = tag(xml, "friendlyName")?.ifBlank { null } ?: "DLNA 设备"
            val service = Regex("""(?is)<service>(.*?)</service>""").findAll(xml)
                .map { it.groupValues[1] }
                .firstOrNull { it.contains("AVTransport", ignoreCase = true) }
                ?: return@runCatching null
            val controlPath = tag(service, "controlURL") ?: return@runCatching null
            val base = "${uri.scheme}://${uri.host}:${if (uri.port > 0) uri.port else 80}"
            DlnaDevice(
                name = name,
                location = location,
                controlUrl = resolveUrl(base, controlPath),
                host = uri.host.orEmpty(),
            ).also { Log.i(TAG, "DLNA 设备: $name -> ${it.controlUrl}") }
        }.onFailure {
            Log.w(TAG, "解析 DLNA 设备描述失败: $location", it)
        }.getOrNull()
    }

    /**
     * 把视频 URL 推给电视并开始播放。
     *
     * 电视对「SetAVTransportURI 之后立刻 Play」的处理并不一致：部分机型此时还没把
     * 传输状态切到 STOPPED/PAUSED_PLAYBACK，直接 Play 会被忽略（表现为点了投屏电视没反应，
     * 再点一次又能放）。这里给它 250ms 落状态，Play 失败再重试一次。
     */
    suspend fun play(
        device: DlnaDevice,
        url: String,
        title: String,
        mime: String = "video/mp4",
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = buildMetadata(url, title, mime)
        val setUriBody = buildString {
            append("<InstanceID>0</InstanceID>")
            append("<CurrentURI>${escape(url)}</CurrentURI>")
            append("<CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>")
        }
        if (!soap(device, "SetAVTransportURI", setUriBody)) return@withContext false
        kotlinx.coroutines.delay(250)
        if (soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")) {
            return@withContext true
        }
        kotlinx.coroutines.delay(400)
        soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    /** 停止电视端播放 */
    suspend fun stop(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        soap(device, "Stop", "<InstanceID>0</InstanceID>")
    }

    private fun soap(device: DlnaDevice, action: String, body: String): Boolean = runCatching {
        val target = URI(device.controlUrl).host.orEmpty()
        if (!isPrivateHost(target)) {
            Log.w(TAG, "拒绝向非局域网地址发送控制指令：${device.controlUrl}")
            return@runCatching false
        }
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  $body
                </u:$action>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val connection = URL(device.controlUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
        connection.setRequestProperty(
            "SOAPACTION",
            "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"",
        )
        connection.doOutput = true
        connection.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code !in 200..299) {
            val error = runCatching {
                connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull().orEmpty()
            Log.w(TAG, "SOAP $action 返回 $code: ${error.take(240)}")
        }
        code in 200..299
    }.onFailure {
        Log.w(TAG, "SOAP $action 失败: ${device.controlUrl}", it)
    }.getOrDefault(false)

    /**
     * 明文 HTTP 只对局域网开口。
     *
     * `network_security_config` 里全局开了 `cleartextTrafficPermitted`——DLNA 的
     * 设备描述与 AVTransport SOAP 只能走明文，且电视的 IP 要到运行时才知道，
     * 没法用 `domain-config` 枚举。这条判断把「明文只对局域网」从注释变成代码约束：
     * 只允许回环、链路本地与 RFC1918 / IPv6 ULA 这些私有地址，任何公网目标都拒绝。
     */
    private fun isPrivateHost(host: String): Boolean {
        if (host.isBlank()) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isAnyLocalAddress
        ) {
            return true
        }
        // Java 的 isSiteLocalAddress 对 IPv6 只覆盖 fec0::/10，唯一本地地址 fc00::/7 要单独判断
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }

    private fun tag(xml: String, name: String): String? =
        Regex("""(?is)<$name\b[^>]*>(.*?)</$name>""").find(xml)?.groupValues?.get(1)?.trim()

    private fun resolveUrl(base: String, path: String): String = when {
        path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) -> path
        path.startsWith("/") -> "$base$path"
        else -> {
            val slash = base.lastIndexOf('/')
            if (slash >= 0) base.substring(0, slash + 1) + path else "$base/$path"
        }
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun buildMetadata(url: String, title: String, mime: String): String = """
        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
          <item id="0" parentID="-1" restricted="false">
            <dc:title>${escape(title)}</dc:title>
            <upnp:class>object.item.videoItem</upnp:class>
            <res protocolInfo="http-get:*:$mime:*">${escape(url)}</res>
          </item>
        </DIDL-Lite>
    """.trimIndent()
}
