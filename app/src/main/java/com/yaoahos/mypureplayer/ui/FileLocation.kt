package com.yaoahos.mypureplayer.ui

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.MediaSource
import com.yaoahos.mypureplayer.data.SmbPath
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.ui.components.Notice

/**
 * 从播放列表 / 历史记录跳到视频所在的文件夹。
 *
 * 关键约定（用户明确要求）：**跳过去之后返回按目录树走，不再回到来源页**。
 * 本地与网络走**同一套压栈骨架**：先垫对应标签页（顺手把来源页剔出返回栈），
 * 再把「从外到内的目录目的地链」逐级压栈。进入动画只播一次（NavHost 只看最终栈顶），
 * 返回全靠逐级 popBackStack，不引入任何页内目录状态：
 *
 *  - 网络视频：链是共享根 → 目标目录的每一级，返回沿目录树退：
 *    最深目录 → 父目录 → … → 共享根 → 网络页。
 *  - 本地视频：本地只有两层（首页 + 一层文件夹），链里只放一个 localFolder 目的地；
 *    返回 pop 一次就回到带常驻底栏的首页 —— 与网络是同一套机制，只是链长为一。
 *  - 串流没有目录概念，不提供入口（[available] 返回 false）。
 *
 * 之所以要"垫标签页"：四个标签页是应用的根，先把来源页从栈里剔掉、落到对应标签，
 * 用户退完目录后站在一个有意义的位置，而不是凭空弹回历史 / 播放列表。
 */
object FileLocation {

    /** 该条目有没有可定位的目录。串流地址没有本地/网络文件夹。 */
    fun available(item: VideoItem): Boolean = when (item.source) {
        MediaSource.SMB -> item.folderKey.isNotBlank() || item.uri.startsWith("smb://", true)
        MediaSource.LOCAL -> item.folderKey.isNotBlank()
        MediaSource.STREAM -> false
    }

    /**
     * 目标视频的稳定标识，随路由传到目录页用于高亮。
     *
     * 本地 MediaStore 行 id 重扫后会变，不能用；真实路径与文件名稳定。SMB 的 uri 即
     * `smb://host/share/.../文件名`，目录页重新列目录时一致。三者都带上，[matches] 任一
     * 命中即可，避免某个字段在历史快照里缺失导致高亮落空。
     */
    fun focusKeyOf(item: VideoItem): String = when (item.source) {
        MediaSource.SMB -> item.uri.ifBlank { item.fileName }
        MediaSource.LOCAL -> item.localPath?.takeIf { it.isNotBlank() }
            ?: item.uri.takeIf { it.isNotBlank() }
            ?: item.fileName
        MediaSource.STREAM -> ""
    }

    /** 目录页里的条目是否就是 focus 指向的目标；uri / 真实路径 / 文件名任一命中。 */
    fun matches(item: VideoItem, focus: String): Boolean {
        if (focus.isBlank()) return false
        return item.uri.equals(focus, true) ||
            item.localPath?.equals(focus, true) == true ||
            item.fileName.equals(focus, true)
    }

    fun open(navController: NavHostController, item: VideoItem) {
        // 统一只做两件事：算好「垫底标签页 + 从外到内的目录目的地链」，然后共用一段压栈。
        // 返回时全交给 NavHost 逐级 pop；本地链长 1、网络链长 N，机制完全一致。
        val (tabRoute, folderChain) = when (item.source) {
            MediaSource.SMB -> {
                // 服务器被删掉后历史 / 播放列表里的条目还在，此时点下去不能一声不响：
                // 用户会以为按钮失灵。直接说明原因并停在这里。
                val server = Graph.servers.forSmbItem(item.serverId, item.uri)
                if (server == null) {
                    Notice.warn("这台网络服务器已不在列表中")
                    return
                }
                val folder = item.folderKey.ifBlank { item.uri.substringBeforeLast('/', "") }
                if (folder.isBlank()) return
                // 安全：历史 / 播放列表可能残留改配置前的旧条目，共享名第一段还匹配，
                // 但完整路径已不在服务器当前配置的共享目录下。此时绝不跳转 ——
                // 否则会把用户带到配置范围之外，直接报「网络位置已移除」。
                if (!SmbPath.within(folder, server.rootPath)) {
                    Notice.warn("网络位置已移除")
                    return
                }
                // focus 带的是目标视频的稳定标识（uri / 真实路径 / 文件名），只有最深的
                // 目标目录那一层需要高亮；中间的各级目录传空，避免返回时父目录也亮着。
                val chain = SmbPath.chainFromRoot(server.rootPath, folder)
                val last = chain.lastIndex
                Routes.NETWORK to chain.mapIndexed { index, path ->
                    Routes.smb(server.id, path, focus = if (index == last) focusKeyOf(item) else "")
                }
            }

            MediaSource.LOCAL -> {
                val folder = item.folderKey
                if (folder.isBlank()) return
                Routes.LOCAL to listOf(Routes.localFolder(folder, focus = focusKeyOf(item)))
            }

            MediaSource.STREAM -> return
        }

        // 先把对应标签垫在栈底并把来源页剔掉，落点固定在该标签页。
        navController.navigate(tabRoute) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
            launchSingleTop = true
        }
        // 从外到内逐级压目录；返回栈本身即目录树历史，按返回键逐级 pop 露出父目录。
        folderChain.forEach { path -> navController.navigate(path) }
    }
}
