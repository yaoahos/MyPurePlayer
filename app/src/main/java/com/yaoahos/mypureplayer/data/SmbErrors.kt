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
    private const val INVALID_PARAMETER = 0xC000000DL

    /**
     * 是不是「服务器不接受这个 READ 长度」。只有这种情况才该把块长从 1MB 降到 512KB；
     * 网络断连、权限错误等也降级只会多一次注定失败的往返，并永久损失一半吞吐。
     */
    fun isInvalidParameter(t: Throwable): Boolean =
        flatten(t).filterIsInstance<SMBApiException>().any { it.statusCode == INVALID_PARAMETER }

    /**
     * 目标文件或目录在共享里不存在（被删除、改名、移走）。
     *
     * 历史记录 / 播放列表里的条目常常是「共享还在、文件没了」：这时不该再让用户
     * 去检查目录名与大小写，也不能把这次失败当瞬时故障重试一遍。
     */
    fun isNotFound(t: Throwable): Boolean {
        val chain = flatten(t)
        if (chain.filterIsInstance<SMBApiException>().any {
                it.statusCode == OBJECT_NAME_NOT_FOUND || it.statusCode == OBJECT_PATH_NOT_FOUND
            }
        ) {
            return true
        }
        val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        return "cannot find the file" in text ||
            "object name not found" in text ||
            "object path not found" in text
    }

    /**
     * 是不是「账号/密码这一关没过」。
     *
     * 只用来判断一个前提：服务器已经明确拒绝了认证。这时如果配置里的账号或密码是空的，
     * 结论就比服务器返回的状态码更准确——服务器只会笼统说「拒绝访问」，
     * 分不清是没填、填错还是没权限，而配置缺什么一眼能看出来。
     *
     * 不能把「ACCESS_DENIED」之外的网络类错误算进来：对方关机时密码填没填都连不上。
     */
    fun isAuthFailure(t: Throwable): Boolean {
        if (t is SmbFailure) {
            // 诊断层已经把网络层（解析、445）排除过了，剩下拒登就是认证问题
            return t.short in AUTH_SHORTS
        }
        val chain = flatten(t)
        if (chain.filterIsInstance<SMBApiException>().any { it.statusCode in AUTH_STATUSES }) {
            return true
        }
        val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        return AUTH_PHRASES.any { it in text }
    }

    private val AUTH_STATUSES = setOf(
        ACCESS_DENIED,
        NETWORK_ACCESS_DENIED,
        LOGON_FAILURE,
        WRONG_PASSWORD,
        NO_SUCH_USER,
        ACCOUNT_DISABLED,
        ACCOUNT_LOCKED_OUT,
    )

    private val AUTH_SHORTS = setOf(
        "密码错误", "账号不存在", "账号已禁用", "账号已锁定", "账号或密码不对", "没有访问权限",
    )

    private val AUTH_PHRASES = listOf(
        "logon failure", "wrong password", "unknown user name", "access is denied",
        "access denied", "session setup failed", "account is disabled", "account is locked",
    )

    /**
     * 顶部提示：先核对「账号 / 密码有没有填」，再翻译底层异常。
     *
     * 只给 [Throwable] 的版本只能看到服务器返回的「拒绝访问」，于是「没填账号」
     * 「没填密码」「密码填错了」全都被显示成同一句——用户改了半天也不知道改哪一格。
     * 这里带上服务器配置，缺哪一格直接说哪一格。
     */
    fun headline(server: SmbServer, t: Throwable): String {
        if (isAuthFailure(t)) {
            if (server.username.isBlank()) return "账号为空"
            if (server.password.isBlank()) return "密码为空"
        }
        return headline(t)
    }

    /** 返回一句「原因 + 该怎么办」，用于提示条与错误页。 */
    fun describe(t: Throwable): String {
        // 把完整异常链打到 logcat：Android 上的失败原因和 JVM 常常不同，
        // 只靠一句英文没法定位（tag: SmbErrors）
        android.util.Log.w("SmbErrors", "describe: " + t.javaClass.name, t)
        // 诊断层已经给过结论，别再翻一次
        if (t is SmbFailure) return t.message.orEmpty()

        val chain = flatten(t)

        chain.filterIsInstance<UnknownHostException>().firstOrNull()?.let {
            return "找不到这台设备：请确认地址，且手机与它连在同一个网络"
        }
        chain.filterIsInstance<SocketTimeoutException>().firstOrNull()?.let {
            return "连接超时：对方没响应，可能休眠或防火墙拦了 445"
        }
        chain.filterIsInstance<NoRouteToHostException>().firstOrNull()?.let {
            return "网络不通：请确认手机和它在同一局域网"
        }
        chain.filterIsInstance<ConnectException>().firstOrNull()?.let {
            return "无法连接：对方没开机、没开共享，或 445 被拦截"
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
            // 认证阶段失败通常只给这一句英文，实际九成是账号密码问题；
            // 但「拒绝访问」也可能是登录成功、共享没授权，措辞要留出这个余地
            message.contains("Session setup failed", ignoreCase = true) ->
                "账号或密码不对：请确认用户名与密码；域环境还要填「域」"
            message.contains("Logon failure", ignoreCase = true) ->
                "登录失败：账号或密码对不上，也可能是账号被禁用或锁定"
            message.contains("device attached to the system is not functioning", ignoreCase = true) ->
                "连接中断：确认账号有 SMB 权限、且没强制加密"
            message.contains("STATUS_", ignoreCase = true) -> "连接失败：${statusWord(message)}"
            // 兜底不把 smbj 原始英文堆给用户；完整异常已写入 logcat 便于排查
            else -> "连接失败：请检查地址与网络后重试"
        }
    }

    /**
     * 页面内错误条用的整句：同样先核对凭证有没有填全，再翻译底层异常。
     *
     * 服务器拒登时只会给「拒绝访问」，用户看不出是自己少填了一格。
     */
    fun describe(server: SmbServer, t: Throwable): String {
        if (t !is SmbFailure && isAuthFailure(t)) {
            if (server.username.isBlank()) {
                return "这个共享需要账号：请先填写用户名（NAS / Windows 上的本地账号，不是邮箱或手机号）"
            }
            if (server.password.isBlank()) {
                return "「${server.username}」的密码是空的：请填写密码后重试"
            }
        }
        return describe(t)
    }

    /**
     * 播放页用的短提示：只说「是什么问题」，不再附带一整段怎么办。
     *
     * 播放页只是黑底上的一行字，写给"要不要换一条"看；排查细节留给网络页的
     * 连接测试与诊断（[describe]），完整异常链照旧写进 logcat。
     */
    fun brief(t: Throwable): String {
        if (isNotFound(t)) return "视频文件不存在"
        val chain = flatten(t)
        if (chain.any {
                it is UnknownHostException || it is ConnectException ||
                    it is NoRouteToHostException || it is SocketTimeoutException
            }
        ) {
            return "无法连接网络位置"
        }
        val status = chain.filterIsInstance<SMBApiException>()
            .firstOrNull { it.statusCode != 0L }?.statusCode
        return when (status) {
            ACCESS_DENIED, NETWORK_ACCESS_DENIED -> "没有访问权限"
            BAD_NETWORK_NAME, NETWORK_NAME_DELETED -> "共享目录不存在"
            WRONG_PASSWORD -> "密码错误"
            LOGON_FAILURE -> "账号或密码不对"
            NO_SUCH_USER -> "账号不存在"
            ACCOUNT_DISABLED -> "账号已禁用"
            ACCOUNT_LOCKED_OUT -> "账号已锁定"
            else -> {
                val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
                when {
                    "access is denied" in text || "access denied" in text -> "没有访问权限"
                    "wrong password" in text -> "密码错误"
                    "unknown user name" in text -> "账号不存在"
                    "logon failure" in text -> "账号或密码不对"
                    "network name cannot be found" in text -> "共享目录不存在"
                    else -> "无法打开网络文件"
                }
            }
        }
    }

    /** smbj 的消息形如 `STATUS_ACCESS_DENIED (0xc0000022): ...`，取状态名足够定位 */
    private fun statusWord(message: String): String =
        Regex("STATUS_[A-Z_]+").find(message)?.value ?: message.take(60)

    /**
     * 顶部提示用的短结论，不超过 8 个字。
     *
     * [describe] 是「原因 + 怎么办」的整句，留给错误页与测试面板；顶部提示条只停
     * 两三秒、宽度只有一行，整句一定被截断，用户既读不完也记不住。那里只要一个
     * 能被一眼归类的结论，外面再拼上服务器名。
     */
    fun headline(t: Throwable): String {
        // 诊断层已经分过层（地址为空 / 解析不到 / 445 不通），直接用它的短结论
        if (t is SmbFailure) return t.short.ifBlank { "连接失败" }
        val chain = flatten(t)
        when {
            chain.any { it is UnknownHostException } -> return "找不到设备"
            chain.any { it is SocketTimeoutException } -> return "连接超时"
            chain.any { it is NoRouteToHostException } -> return "网络不通"
            chain.any { it is ConnectException } -> return "无法连接"
        }
        val status = chain.filterIsInstance<SMBApiException>()
            .firstOrNull { it.statusCode != 0L }?.statusCode
        when (status) {
            ACCESS_DENIED, NETWORK_ACCESS_DENIED -> return "没有访问权限"
            BAD_NETWORK_NAME, NETWORK_NAME_DELETED -> return "共享目录不存在"
            WRONG_PASSWORD -> return "密码错误"
            // 0xC000006D 只说「登录失败」，可能是密码错、也可能是账号不存在或被禁用，
            // 不能像 0xC000006A 那样断言是密码
            LOGON_FAILURE -> return "账号或密码不对"
            NO_SUCH_USER -> return "账号不存在"
            ACCOUNT_DISABLED -> return "账号已禁用"
            ACCOUNT_LOCKED_OUT -> return "账号已锁定"
            OBJECT_NAME_NOT_FOUND, OBJECT_PATH_NOT_FOUND -> return "路径不存在"
            NOT_SUPPORTED -> return "不支持该 SMB 版本"
        }
        val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            "access is denied" in text || "access denied" in text -> "没有访问权限"
            "wrong password" in text -> "密码错误"
            "unknown user name" in text || "no such user" in text -> "账号不存在"
            "logon failure" in text || "session setup failed" in text -> "账号或密码不对"
            "account is disabled" in text -> "账号已禁用"
            "account is locked" in text -> "账号已锁定"
            "network name cannot be found" in text -> "共享目录不存在"
            "cannot find the file" in text -> "路径不存在"
            "not supported" in text -> "不支持该 SMB 版本"
            else -> "连接失败"
        }
    }

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
            // 措辞里带「密码」字样的先判，比笼统的 access denied 更具体
            "wrong password" in text ->
                "密码错误：这个账号的密码对不上（注意大小写与键盘输入法）"

            "unknown user name" in text || "no such user" in text ->
                "账号不存在：请确认用户名；域账号要单独填「域」"

            "logon failure" in text || "session setup failed" in text ->
                "账号或密码不对：请确认用户名与密码；域环境还要填「域」"

            "account is disabled" in text -> "账号已禁用：请在服务器上启用该账号后重试"
            "account is locked" in text -> "账号已锁定：密码错太多次被锁，请在服务器上解锁后重试"

            "access is denied" in text || "access denied" in text ->
                "没有权限：账号密码能通过，但这个共享没有授权给该账号"

            "network name cannot be found" in text || "bad network name" in text ->
                "共享目录不存在：请检查共享名"

            "cannot find the file" in text || "object name not found" in text ->
                "路径不存在：请确认目录名、大小写与层级"

            "connection refused" in text || "failed to connect" in text ->
                "无法连接：对方没开机、没开共享，或 445 被拦截"

            "connection timed out" in text || "read timed out" in text ->
                "连接超时：对方没响应，可能休眠或防火墙拦了 445"

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
            // 会话建立成功、但进这个共享被拒：账号密码本身可能是对的，缺的是共享授权
            ACCESS_DENIED ->
                "没有权限：账号密码能通过，但这个共享没有授权给该账号"

            NETWORK_ACCESS_DENIED ->
                "没有权限：服务器不允许这个账号从当前网络访问共享"

            BAD_NETWORK_NAME, NETWORK_NAME_DELETED ->
                "共享目录不存在：请检查共享名"

            // 0xC000006A 是「密码错」，能确定；0xC000006D 只说「登录失败」，
            // 服务器没有透露更细的原因，措辞不能直接断言密码错
            WRONG_PASSWORD ->
                "密码错误：这个账号的密码对不上（注意大小写与键盘输入法）"

            LOGON_FAILURE ->
                "账号或密码不对：请确认用户名与密码；域环境还要填「域」"

            NO_SUCH_USER ->
                "账号不存在：请确认用户名；域账号要单独填「域」"

            ACCOUNT_DISABLED ->
                "账号已禁用：请在服务器上启用该账号后重试"

            ACCOUNT_LOCKED_OUT ->
                "账号已锁定：密码错太多次被锁，请在服务器上解锁后重试"

            OBJECT_NAME_NOT_FOUND, OBJECT_PATH_NOT_FOUND ->
                "路径不存在：请确认目录名、大小写与层级"

            NOT_SUPPORTED -> "对方不支持这种访问方式（可能关闭了 SMB2/SMB3）"

            // 状态码没覆盖到：优先用英文措辞兜底，最后才贴状态名
            else -> englishHint(message)
                ?: "连接失败：${statusWord(message)}"
        }
    }

    private fun byDosCode(code: Long): String = when (code) {
        5L -> "没有权限：账号密码能通过，但这个共享没有授权给该账号"
        2L, 3L -> "路径不存在：请检查共享名与目录是否写对"
        53L, 67L -> "找不到网络路径：请确认共享名存在且已开启共享"
        86L -> "密码错误：这个账号的密码对不上（注意大小写与键盘输入法）"
        1326L -> "账号或密码不对：请确认用户名与密码；域环境还要填「域」"
        1331L -> "账号已禁用：请在服务器上启用该账号后重试"
        1907L -> "账号需要先在服务器上修改密码才能登录"
        else -> "连接失败（错误码 $code）"
    }
}
