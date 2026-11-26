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

    /**
     * 目录地址的上一级；已经在 [root]（或根本不在它下面）时返回 null。
     *
     * 只做字符串层级的截断，不碰凭据：地址里残留的 user:pass@ 在 [split] 里已经去掉，
     * 界面用的是不含口令的 smb:// 地址，这里原样返回前缀即可。
     */
    fun parentOf(path: String, root: String): String? {
        val p = path.trimEnd('/')
        val r = root.trimEnd('/')
        if (p.isEmpty()) return null
        // 跨共享时不乱退：必须落在同一个 root 之下，且按路径分段比较。
        // 纯 startsWith 会把 smb://host/share2/a 当成 smb://host/share 的子目录；
        // 主机 / 共享名大小写也不该把人挡在返回键外面，所以比较忽略大小写、返回原文。
        val within = r.isEmpty() || p.equals(r, true) || p.startsWith("$r/", true)
        if (!within) return null
        val cut = p.substringBeforeLast('/', "")
        // cut == r：已经到共享根，交给调用方退出页面，而不是退到 smb://host
        if (cut.isEmpty() || cut.length <= r.length || cut.equals(r, true)) return null
        return cut
    }

    /**
     * [path] 是否落在 [root]（含其子目录）之内。比较忽略末尾斜杠与大小写。
     *
     * 用途：历史 / 播放列表里的旧条目可能指向改配置前的目录 —— 共享名第一段还匹配，
     * 但完整路径已不在服务器当前配置的共享目录下（配置 Backup/Link、条目却在
     * Backup/PT/临时）。这类「不同源」条目不能再拿旧地址跳转，否则会把用户带到
     * 配置范围之外，属于安全问题。调用方据此报「网络位置已移除」，不发起导航。
     */
    fun within(path: String, root: String): Boolean {
        val p = path.trim().trimEnd('/')
        val r = root.trim().trimEnd('/')
        if (p.isEmpty() || r.isEmpty()) return false
        return p.equals(r, true) || p.startsWith("$r/", true)
    }

    /**
     * 从共享根到 [target] 的完整目录链，含首尾（第一项恒为 [root]，最后一项为 [target]）。
     *
     * 用途：「在文件夹中显示」跳到深层目录时，把每一级都压成独立的 NavHost 目的地。
     * 这样进入动画只播一次（NavHost 只看最终栈顶），而返回是逐级 popBackStack ——
     * 天然沿目录树退回，不需要在单个页面里用 AnimatedContent 手改层序。
     *
     * [target] 不在 [root] 之下时返回空链：调用方（FileLocation）会先判 [within] 并报错，
     * 绝不能为不同源条目拼出一条跨目录的假链，把用户带到配置范围之外。
     */
    fun chainFromRoot(root: String, target: String): List<String> {
        val r = root.trimEnd('/').ifBlank { "smb:///" }
        val p = target.trimEnd('/')
        if (p.isEmpty()) return listOf(r)
        if (!within(p, r)) return emptyList()
        val chain = ArrayDeque<String>()
        var cur = p
        chain.addFirst(cur)
        while (true) {
            val parent = parentOf(cur, r) ?: break
            chain.addFirst(parent)
            cur = parent
        }
        // parentOf 到共享根就停，链首一般已是链根；若还没到则补上。
        if (!chain.first().equals(r, true) && r.isNotBlank()) {
            chain.addFirst(r)
        }
        return chain.toList()
    }
}
