package com.yaoahos.mypureplayer.data

import android.util.Log
import com.hierynomus.mssmb2.SMBApiException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** 已经翻译成人话的 SMB 失败，message 可直接展示，technical 写进日志便于排查 */
class SmbFailure(
    message: String,
    val technical: String = "",
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 连接失败时补一次分层探测，把「卡在哪一步」说清楚。
 *
 * 用户没法从一句 smbj 的英文状态里判断是地址写错、端口没开，还是账号不对。
 * 这里分三段：
 *   1. 主机能不能解析
 *   2. 445 端口能不能连上
 *   3. 真正的 SMB 会话错误（由 SmbErrors 翻译）
 */
object SmbDiagnostics {

    private const val TAG = "SmbDiag"
    private const val CONNECT_TIMEOUT_MS = 6000

    fun explain(server: SmbServer, error: Throwable): SmbFailure {
        val resolved = runCatching { InetAddress.getByName(server.host) }.getOrNull()
        val ip = resolved?.hostAddress
        val tcpOk = ip != null && canConnect(ip, 445)

        val base = SmbErrors.describe(error)
        val hint = when {
            server.host.isBlank() ->
                "服务器地址是空的：请填写 IP 或主机名，例如 192.168.1.5"

            resolved == null ->
                "解析不到「${server.host}」：地址可能写错，或者这是一台只能靠 NetBIOS 广播发现的设备，先改用它的 IP 试试"

            !tcpOk ->
                "能解析到 $ip，但 445 端口连不上：这台设备可能关了文件共享、防火墙拦了 445，或者手机和它不在同一个网络"

            else -> base
        }

        val technical = buildString {
            appendLine("host=${server.host}")
            appendLine("ip=${ip ?: "-"}")
            appendLine("share=${server.share.ifBlank { "-" }}")
            appendLine("user=${server.username.ifBlank { "(anonymous)" }}")
            appendLine("domain=${server.domain.ifBlank { "-" }}")
            appendLine("tcp445=$tcpOk")
            appendLine("error=${error.javaClass.name}: ${error.message}")
            (error as? SMBApiException)?.let {
                appendLine("ntstatus=0x%08X".format(it.statusCode))
            }
            error.stackTrace.take(5).forEach { appendLine("  at $it") }
        }
        Log.w(TAG, technical)

        return SmbFailure(hint, technical, error)
    }

    private fun canConnect(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)
}
