package com.yaoahos.mypureplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.Role
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.SmbClient
import com.yaoahos.mypureplayer.data.SmbEntry
import com.yaoahos.mypureplayer.data.SmbErrors
import com.yaoahos.mypureplayer.data.SmbServer
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.glassPressable

/**
 * 「从网络目录选择字幕」。
 *
 * 之前这是一个独立路由页面：从横屏播放页点「打开网络字幕目录」会跳到竖屏的
 * 字幕选择页，选完再跳回播放页 —— 屏幕来回转两次，播放被打断。
 * 现在改成播放页之上的浮层（Dialog），不换路由、不改方向，手感就是
 * 「在字幕面板里继续翻目录」。
 *
 * 只列目录与字幕文件：这是选字幕的地方，视频条目在这里没有意义。
 */
@Composable
fun NetworkSubtitlePickerDialog(
    serverId: Long,
    initialPath: String,
    onDismiss: () -> Unit,
) {
    var server by remember(serverId) { mutableStateOf<SmbServer?>(Graph.servers.byId(serverId)) }
    var path by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(serverId) {
        if (server == null) {
            Graph.servers.load()
            server = Graph.servers.byId(serverId)
        }
    }

    val srv = server

    // 面包屑：根级用共享根（rootPath），显示友好名「共享根目录」，其后按 / 逐段铺开。
    // 完整真实 URL 仍由面板副标题显示。
    val crumbs = remember(srv?.id, path) {
        val root = (srv?.rootPath ?: "smb:///").trimEnd('/')
        val clean = path.trimEnd('/')
        val list = mutableListOf<PickerCrumb>()
        list += PickerCrumb("共享根目录", root)
        if (clean.length > root.length && clean.startsWith(root)) {
            val rest = clean.removePrefix(root).trimStart('/')
            if (rest.isNotEmpty()) {
                var acc = root
                rest.split('/').forEach { seg ->
                    if (seg.isEmpty()) return@forEach
                    acc = "$acc/$seg"
                    list += PickerCrumb(seg, acc)
                }
            }
        }
        list
    }

    LaunchedEffect(srv?.id, path, reloadKey) {
        val active = srv ?: return@LaunchedEffect
        loading = true
        error = null
        runCatching { SmbClient.list(active, path) }
            .onSuccess { all ->
                entries = all.filter { it.isDirectory || Formats.isSubtitle(it.name) }
            }
            .onFailure { error = SmbErrors.describe(it) }
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        // 宽度由我们自己控制：横屏要能一次看到更多文件名，竖屏也不该被默认宽度挤成一条
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        PlayerPickerPanel(
            onDismiss = onDismiss,
            title = "选择网络字幕",
            onBack = {
                // 回到上一层目录；已站在共享根就关闭浮层
                val root = (srv?.rootPath ?: "smb:///").trimEnd('/')
                val cut = path.trimEnd('/').substringBeforeLast('/')
                if (cut.length < root.length || path.trimEnd('/') == root) onDismiss() else path = cut
            },
            actions = {
                val glass = playerGlass()
                IconButton(onClick = { path = srv?.rootPath ?: "smb:///" }) {
                    Icon(Icons.Rounded.Home, contentDescription = "回到共享根目录", tint = glass.onGlass)
                }
                IconButton(onClick = { reloadKey++ }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = glass.onGlass)
                }
            },
            breadcrumb = {
                PickerBreadcrumb(crumbs = crumbs, onNavigate = { p -> path = p })
            },
        ) {
            val glass = playerGlass()
            when {
                loading -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        Modifier.size(28.dp),
                        strokeWidth = 2.5.dp,
                        color = glass.accent,
                    )
                }

                error != null -> Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "无法打开这个目录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = glass.onGlass,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.onGlassSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.TextButton(onClick = { reloadKey++ }) {
                        Text("重试", color = glass.accent)
                    }
                }

                entries.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.Subtitles,
                        contentDescription = null,
                        tint = glass.onGlassSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这里没有字幕，可点上方面包屑或进入子目录继续找",
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.onGlassSecondary,
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 8.dp, end = 8.dp, top = 6.dp, bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(entries, key = { it.url }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .glassPressable(
                                    radius = 12.dp,
                                    role = Role.Button,
                                    fillOverride = PlayerPressFill,
                                ) {
                                    if (entry.isDirectory) {
                                        path = entry.url
                                    } else {
                                        Playback.selectSubtitleUrl(entry.url, entry.name)
                                        onDismiss()
                                        Notice.success("已加载字幕：${entry.name}")
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                                contentDescription = null,
                                tint = glass.accent,
                            )
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = glass.onGlass,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!entry.isDirectory) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        Formats.formatSize(entry.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = glass.onGlassSecondary,
                                    )
                                }
                            }
                            if (entry.isDirectory) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = glass.onGlassSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}