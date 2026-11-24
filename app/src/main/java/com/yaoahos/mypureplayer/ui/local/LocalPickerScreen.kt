package com.yaoahos.mypureplayer.ui.local

import androidx.compose.foundation.interaction.MutableInteractionSource
import com.yaoahos.mypureplayer.ui.components.collectPressedWithTap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.mediaCardOutline
import com.yaoahos.mypureplayer.ui.components.GlassEdge
import com.yaoahos.mypureplayer.ui.components.appGlassBar
import com.yaoahos.mypureplayer.ui.components.DurationBadge
import com.yaoahos.mypureplayer.ui.components.EmptyState
import com.yaoahos.mypureplayer.ui.components.PlaylistTargetSheet
import com.yaoahos.mypureplayer.ui.components.VideoThumb

/**
 * 本地文件夹批量选择：把某个文件夹里的全部或部分视频加入播放列表。
 *
 * 视频挑好后点「加入列表」会弹出目标选择器，由用户决定加到「正在播放」还是某个自建列表，
 * 不再默认塞进正在播放。videosInFolder 只按 bucketId 取该文件夹**直属**视频，
 * 子文件夹的视频 bucketId 不同，天然不会被包含——这正是「不递归嵌套文件夹」的要求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPickerScreen(navController: NavHostController, folderKey: String, targetPlaylist: String = "") {
    val scope = rememberCoroutineScope()
    val videos = remember(folderKey) { Graph.local.videosInFolder(folderKey) }
    val selected = remember { mutableStateListOf<String>() }
    val folderName = videos.firstOrNull()?.folderName?.takeIf { it.isNotBlank() } ?: "文件夹"
    val allSelected = videos.isNotEmpty() && selected.size == videos.size
    // 点「加入列表」后待定加入的视频；非空时弹出目标列表选择器
    var pendingAdd by remember { mutableStateOf<List<com.yaoahos.mypureplayer.data.VideoItem>?>(null) }

    Scaffold(
        // 透明容器：背景由根部的常驻玻璃层统一绘制，内部页面与底栏页共用同一张底
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // 页面级不透明背景：转场时遮住下面那一页，避免残影
        modifier = Modifier.fillMaxSize().glassPageBackground(),
        topBar = {
            PageTopBar(
                title = folderName,
                subtitle = "${videos.size} 个可选视频",
                onBack = { navController.popBackStack() },
                actions = {
                    IconButton(
                        onClick = {
                            if (allSelected) {
                                selected.clear()
                            } else {
                                selected.clear()
                                selected.addAll(videos.map { it.id })
                            }
                        },
                    ) {
                        Icon(
                            Icons.Rounded.SelectAll,
                            contentDescription = if (allSelected) "取消全选" else "全选",
                        )
                    }
                },
            )
        },
        bottomBar = {
            // 底部操作条走玻璃栏：与顶栏、底栏同一套材质，不再是浮在页面上的一块实心色板
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .appGlassBar(GlassEdge.TOP),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已选 ${selected.size} 个",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val items = videos.filter { it.id in selected }
                            if (items.isEmpty()) return@Button
                            // 不预设目标，交给用户选
                            pendingAdd = items
                        },
                        enabled = selected.isNotEmpty(),
                    ) { Text("加入列表") }
                }
            }
        },
    ) { padding ->
        if (videos.isEmpty()) {
            // 文件夹里的视频可能在导航后被删除或移入保险箱，不能让页面变成纯空白
            EmptyState(
                icon = Icons.Rounded.Movie,
                title = "这里没有视频",
                message = "文件夹里已经没有可选的视频了。",
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(
                start = PageMetrics.HPad,
                end = PageMetrics.HPad,
                top = 4.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(videos, key = { it.id }) { item ->
                val checked = item.id in selected
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectPressedWithTap()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 与其它列表页同一套行卡：玻璃底 + 20dp 圆角 + 104×60 缩略图。
                        // 勾选态走 selected（左侧竖条 + 薄纱），不再借用 pressed 冒充选中 ——
                        // 那样一进页面就有一半卡片是"按下"色，真正的按下反而看不出变化。
                        .mediaCardOutline(pressed = pressed, selected = checked)
                        // 整行 toggleable：TalkBack 能读出选中状态，触屏点哪都生效。
                        // indication = null：按压反馈已由 mediaCardOutline 统一绘制，
                        // 默认波纹会在这张圆角卡上铺一块直角矩形。
                        .toggleable(
                            interactionSource = interaction,
                            indication = null,
                            value = checked,
                            role = Role.Checkbox,
                            onValueChange = { next ->
                                if (next) selected.add(item.id) else selected.remove(item.id)
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 104.dp, height = 60.dp)) {
                        VideoThumb(item, Modifier.fillMaxSize(), cornerRadius = PageMetrics.ThumbRadius)
                        DurationBadge(
                            Formats.formatDuration(item.durationMs),
                            Modifier.align(Alignment.BottomEnd).padding(3.dp),
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    ) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            Formats.formatSize(item.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(checked = checked, onCheckedChange = null)
                }
            }
        }
    }

    // 目标播放列表由用户自己选，加入后回到上一级
    pendingAdd?.let { items ->
        PlaylistTargetSheet(
            onDismiss = { pendingAdd = null },
            onPick = { targetId, name ->
                pendingAdd = null
                if (targetId.isEmpty()) {
                    Playback.addToQueue(items)
                    com.yaoahos.mypureplayer.ui.components.Notice.success("已加入「正在播放」${items.size} 个视频")
                    navController.popBackStack()
                } else {
                    scope.launch {
                        val n = Graph.playlists.addVideos(targetId, items)
                        if (n > 0) {
                            com.yaoahos.mypureplayer.ui.components.Notice.success("已加入「$name」$n 个视频")
                        } else {
                            com.yaoahos.mypureplayer.ui.components.Notice.error("「$name」没有新增视频")
                        }
                        navController.popBackStack()
                    }
                }
            },
        )
    }
}
