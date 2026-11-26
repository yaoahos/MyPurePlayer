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
    /**
     * 顶部提示用的短结论（不超过 8 个字），例如「445 端口不通」。
     *
     * [message] 是「原因 + 怎么办」的完整句子，放在错误页或测试面板里；
     * 顶部提示条只停留两三秒、宽度只有一行，必须再短一级。
     */
    val short: String = "",
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
        // 服务器只说「拒绝访问」时，分不清是没填账号、没填密码还是填错了。
        // 配置里缺哪一格是一眼看得到的，这一层比猜服务器状态更准。
        val authIssue = SmbErrors.isAuthFailure(error)
        val hint = when {
            server.host.isBlank() ->
                "服务器地址是空的：请填写 IP 或主机名，例如 192.168.1.5"

            resolved == null ->
                "解析不到「${server.host}」：地址可能写错，或者这是一台只能靠 NetBIOS 广播发现的设备，先改用它的 IP 试试"

            !tcpOk ->
                "能解析到 $ip，但 445 端口连不上：这台设备可能关了文件共享、防火墙拦了 445，或者手机和它不在同一个网络"

            authIssue && server.username.isBlank() ->
                "这个共享需要账号：请填写用户名（NAS / Windows 上的本地账号，不是邮箱或手机号）"

            authIssue && server.password.isBlank() ->
                "「${server.username}」的密码是空的：请填写密码后重试"

            else -> base
        }
        // 顶部提示只要一句结论：分层探测已经把「卡在哪一步」分清楚了
        val short = when {
            server.host.isBlank() -> "地址为空"
            resolved == null -> "找不到设备"
            !tcpOk -> "445 端口不通"
            authIssue && server.username.isBlank() -> "账号为空"
            authIssue && server.password.isBlank() -> "密码为空"
            else -> SmbErrors.headline(error)
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

        return SmbFailure(hint, technical, error, short)
    }

    private fun canConnect(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)
}
