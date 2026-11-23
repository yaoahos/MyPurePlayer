package com.yaoahos.mypureplayer.data

import com.hierynomus.mssmb2.SMBApiException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把 smbj 抛出的底层异常翻译成用户能照着改的中文说明。
 *
 * 界面不能直接贴 `STATUS_ACCESS_DENIED (0xc0000022)` 这类原始状态码 ——
 * 用户看不出是账号问题还是共享名写错，也没法据此操作。
 */
object SmbErrors {

    // 常用 NTSTATUS
    private const val ACCESS_DENIED = 0xC0000022L
    private const val BAD_NETWORK_NAME = 0xC00000CCL
    private const val LOGON_FAILURE = 0xC000006DL
    private const val WRONG_PASSWORD = 0xC000006AL
    private const val NO_SUCH_USER = 0xC0000064L
    private const val ACCOUNT_DISABLED = 0xC0000072L
    private const val ACCOUNT_LOCKED_OUT = 0xC0000234L
    private const val OBJECT_NAME_NOT_FOUND = 0xC0000034L
    private const val OBJECT_PATH_NOT_FOUND = 0xC000003AL
    private const val NETWORK_NAME_DELETED = 0xC00000C9L
    private const val NOT_SUPPORTED = 0xC00000BBL
    private const val NETWORK_ACCESS_DENIED = 0xC00000CAL

    /** 返回一句「原因 + 该怎么办」，用于提示条与错误页。 */
    fun describe(t: Throwable): String {
        // 把完整异常链打到 logcat：Android 上的失败原因和 JVM 常常不同，
        // 只靠一句英文没法定位（tag: SmbErrors）
        android.util.Log.w("SmbErrors", "describe: " + t.javaClass.name, t)
        // 诊断层已经给过结论，别再翻一次
        if (t is SmbFailure) return t.message.orEmpty()

        val chain = flatten(t)

        chain.filterIsInstance<UnknownHostException>().firstOrNull()?.let {
            return "找不到这台设备（${it.message.orEmpty()}）：请确认地址没有写错，且手机与它连在同一个网络"
        }
        chain.filterIsInstance<SocketTimeoutException>().firstOrNull()?.let {
            return "连接超时：对方没有响应，可能在休眠，或被防火墙拦了 445 端口"
        }
        chain.filterIsInstance<NoRouteToHostException>().firstOrNull()?.let {
            return "网络不可达：请确认手机和这台设备在同一局域网"
        }
        chain.filterIsInstance<ConnectException>().firstOrNull()?.let {
            return "无法建立连接：对方可能没开机、没开文件共享，或 445 端口被拦截"
        }

        // smbj 把 NTSTATUS 放在 SMBApiException.statusCode 里（long，无符号）
        chain.filterIsInstance<SMBApiException>()
            .firstOrNull { it.statusCode != 0L }
            ?.let { return byStatus(it.statusCode, it.message.orEmpty()) }

        // 有时库里只把状态渲染成英文句子，没有带状态码，按措辞兜底翻译
        val message = chain.mapNotNull { it.message }.firstOrNull { it.isNotBlank() }.orEmpty()
        englishHint(message)?.let { return it }

        return when {
            message.isBlank() -> "连接失败：未知错误"
            // 认证阶段失败通常只给这一句英文，实际九成是账号密码问题
            message.contains("Session setup failed", ignoreCase = true) ||
                message.contains("Logon failure", ignoreCase = true) ->
                "认证失败：用户名或密码不对，或该账号没有访问这个共享的权限"
            message.contains("device attached to the system is not functioning", ignoreCase = true) ->
                "连接中断：服务器拒绝了这次会话，请确认账号有 SMB 权限、且没有强制加密"
            message.contains("STATUS_", ignoreCase = true) -> "连接失败：${statusWord(message)}"
            // 兜底不把 smbj 原始英文堆给用户；完整异常已写入 logcat 便于排查
            else -> "连接失败：请检查地址与网络后重试"
        }
    }

    /** smbj 的消息形如 `STATUS_ACCESS_DENIED (0xc0000022): ...`，取状态名足够定位 */
    private fun statusWord(message: String): String =
        Regex("STATUS_[A-Z_]+").find(message)?.value ?: message.take(60)

    /**
     * 展平异常链。
     *
     * smbj 的 SMBApiException 有标准 cause，不像 jcifs 需要额外读 rootCause 字段；
     * 但 TransportException 会把真实原因包在 cause 里，所以仍然要一路走到底。
     */
    private fun flatten(t: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        var current: Throwable? = t
        while (current != null && out.size < 12 && out.none { it === current }) {
            out += current
            current = current.cause
        }
        return out
    }

    private fun englishHint(message: String): String? {
        val text = message.lowercase()
        return when {
            "access is denied" in text || "access denied" in text ->
                "被拒绝访问：账号或密码不对，也可能该账号没有访问这个共享的权限"

            "network name cannot be found" in text || "bad network name" in text ->
                "找不到这个共享目录：请检查共享名是否写对"

            "logon failure" in text || "wrong password" in text || "unknown user name" in text ->
                "用户名或密码错误"

            "account is disabled" in text -> "该账号已被禁用"
            "account is locked" in text -> "该账号已被锁定，请在服务器上解锁后重试"

            "cannot find the file" in text || "object name not found" in text ->
                "路径不存在：请确认目录名、大小写与层级"

            "connection refused" in text || "failed to connect" in text ->
                "无法建立连接：对方可能没开机、没开文件共享，或 445 端口被拦截"

            "connection timed out" in text || "read timed out" in text ->
                "连接超时：对方没有响应，可能在休眠，或被防火墙拦了 445 端口"

            "not supported" in text -> "对方不支持这种访问方式（可能关闭了 SMB2/SMB3）"
            "not_connected" in text || "not connected" in text ->
                "与服务器的连接已断开：请返回网络页重新进入"
            else -> null
        }
    }

    private fun byStatus(status: Long, message: String): String {
        // 少数服务器在协商阶段返回很小的状态码，按数值区间分开翻译
        if (status in 1L..0xFFFFL) return byDosCode(status)
        return when (status) {
            ACCESS_DENIED, NETWORK_ACCESS_DENIED ->
                "被拒绝访问：账号或密码不对，也可能该账号没有访问这个共享的权限"

            BAD_NETWORK_NAME, NETWORK_NAME_DELETED ->
                "找不到这个共享目录：请检查共享名是否写对"

            LOGON_FAILURE, WRONG_PASSWORD ->
                "用户名或密码错误：请用设备上的本地账号（不是邮箱或手机号）；" +
                    "Windows 域环境还要填「域」，群晖/威联通则确认该账号有 SMB 访问权限"
            NO_SUCH_USER -> "账号不存在：请检查用户名（域账号需要单独填「域」）"
            ACCOUNT_DISABLED -> "该账号已被禁用"
            ACCOUNT_LOCKED_OUT -> "该账号已被锁定，请在服务器上解锁后重试"

            OBJECT_NAME_NOT_FOUND, OBJECT_PATH_NOT_FOUND ->
                "路径不存在：请确认目录名、大小写与层级"

            NOT_SUPPORTED -> "对方不支持这种访问方式（可能关闭了 SMB2/SMB3）"

            // 状态码没覆盖到：优先用英文措辞兜底，最后才贴状态名
            else -> englishHint(message)
                ?: "连接失败：${statusWord(message)}"
        }
    }

    private fun byDosCode(code: Long): String = when (code) {
        5L -> "被拒绝访问：账号或密码不对，也可能该账号没有访问这个共享的权限"
        2L, 3L -> "路径不存在：请检查共享名与目录是否写对"
        53L, 67L -> "找不到网络路径：请确认共享名存在且已开启共享"
        86L -> "密码错误"
        1326L -> "用户名或密码错误"
        else -> "连接失败（错误码 $code）"
    }
}
