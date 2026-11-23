package com.yaoahos.mypureplayer.data

/**
 * smb:// 地址与 smbj 的「共享 + 相对路径」模型互转。
 *
 * jcifs 可以直接吃 `smb://host/share/a/b` 这样的整串地址，smbj 则必须先把共享连上
 * （TREE_CONNECT），再给共享内的相对路径。界面与持久化里统一保存的仍是不含口令的
 * smb:// 地址，进入传输层时再拆开，这样历史记录、播放列表、字幕地址都不用改格式。
 */
internal object SmbPath {

    /** [share] 为共享名（空表示用户没填默认共享）；[path] 为共享内相对路径，'/' 分隔且不带前导斜杠 */
    data class Loc(val share: String, val path: String)

    fun split(url: String): Loc {
        val raw = url.trim()
        val body = when {
            raw.startsWith("smb://", ignoreCase = true) -> raw.substring(6)
            raw.startsWith("//") -> raw.substring(2)
            else -> raw
        }
        // 残留的 user:pass@ 一律丢掉：凭据由连接持有，不该出现在路径里。
        // 但 userinfo 只可能出现在 authority 段（host 之前）；路径里的 '@' 是文件名的
        // 一部分（如 [7³ACG@OurBits]），若一并剥离会把 host/共享名/上层目录全切掉，
        // 结果 share 为空，页面报「请先填写默认共享目录」。
        val authorityEnd = body.indexOf('/')
        val head = if (authorityEnd >= 0) body.substring(0, authorityEnd) else body
        val tail = if (authorityEnd >= 0) body.substring(authorityEnd) else ""
        val noUser = head.substringAfterLast('@') + tail
        val segments = noUser.split('/').filter { it.isNotEmpty() }
        if (segments.size <= 1) return Loc("", "")
        return Loc(
            share = segments[1],
            path = segments.drop(2).joinToString("/"),
        )
    }

    /** 拼回 smb:// 地址，用于把目录项还给界面（与旧的 SmbFile.path 语义一致） */
    fun join(host: String, loc: Loc): String = buildString {
        append("smb://").append(host).append('/')
        if (loc.share.isNotEmpty()) {
            append(loc.share)
            if (loc.path.isNotEmpty()) append('/').append(loc.path)
        }
    }
}
