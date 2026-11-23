package com.yaoahos.mypureplayer.data

/**
 * 把用户填进「服务器地址」的各种写法统一成传输层能直接用的 host + share。
 *
 * 常见的粘贴形式：
 *   192.168.1.5
 *   192.168.1.5:445
 *   smb://192.168.1.5/media/
 *   \\192.168.1.5\media
 *   smb://user:pass@192.168.1.5/share/子目录
 *
 * 传输层只认 `host` 与 `共享名` 两段，多一个冒号或反斜杠都会解析失败，
 * 所以在落库前就拆干净，避免「明明地址能 ping 通，却报路径不存在」。
 */
object SmbInput {

    data class Parsed(val host: String, val share: String)

    fun parse(raw: String): Parsed {
        var s = raw.trim().replace('\u3000', ' ').trim().trim('"', '\'').trim()
        if (s.isEmpty()) return Parsed("", "")

        // 协议前缀（大小写都认）
        val lower = s.lowercase()
        s = when {
            lower.startsWith("smb://") -> s.substring(6)
            lower.startsWith("cifs://") -> s.substring(7)
            lower.startsWith("smb:") -> s.substring(4)
            else -> s
        }

        // Windows UNC：\\host\share
        if (s.startsWith("\\\\")) s = s.substring(2)
        else if (s.startsWith("//")) s = s.substring(2)

        // 反斜杠统一成斜杠
        s = s.replace('\\', '/')

        // 内嵌凭据 user:pass@host
        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)

        s = s.trim('/')
        if (s.isEmpty()) return Parsed("", "")

        val parts = s.split('/').filter { it.isNotBlank() }
        // SMB 客户端不走自定义端口，这里把 :445 之类切掉
        val host = parts.firstOrNull().orEmpty().substringBefore(':').trim()
        val share = parts.drop(1).joinToString("/").trim()
        return Parsed(host, share)
    }

    private val IP_WITH_TAIL = Regex("""^(\d{1,3}(?:\.\d{1,3}){3})([A-Za-z0-9_\-\u4e00-\u9fa5].*)$""")

    /**
     * 修复历史坏数据：早期版本把「地址」和「共享目录」直接字符串相加，落库成
     * `192.168.1.5Backup`，共享目录却是空，连接时只会报「找不到这台设备」。
     *
     * 合法主机名里 IP 后面不可能紧跟字母，所以命中这个形态就按 IP + 名称拆回去。
     * 返回 null 表示无需修复。
     */
    fun repair(host: String, share: String): Parsed? {
        if (share.isNotBlank()) return null
        val hit = IP_WITH_TAIL.find(host.trim()) ?: return null
        val ip = hit.groupValues[1]
        val tail = hit.groupValues[2].trim().trim('/')
        if (tail.isBlank()) return null
        return Parsed(ip, tail)
    }
}
