package com.yaoahos.mypureplayer.ui.play

import com.yaoahos.mypureplayer.ui.HideShellBottomBar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.yaoahos.mypureplayer.ui.components.collectPressedWithTap
import com.yaoahos.mypureplayer.ui.components.flashClick
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.Playlist
import com.yaoahos.mypureplayer.data.SmbServer
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.ui.MainScaffold
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.ConfirmDialog
import com.yaoahos.mypureplayer.ui.components.FabInsets
import com.yaoahos.mypureplayer.ui.components.GlassChoiceDialog
import com.yaoahos.mypureplayer.ui.components.GlassChoiceRow
import com.yaoahos.mypureplayer.ui.components.GlassFab
import com.yaoahos.mypureplayer.ui.components.ListSelection
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.glassPressable
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.SelectionAction
import com.yaoahos.mypureplayer.ui.components.SelectionActionBar
import com.yaoahos.mypureplayer.ui.components.appGlass
import com.yaoahos.mypureplayer.ui.components.appGlassSurface
import com.yaoahos.mypureplayer.ui.components.mediaCardOutline
import com.yaoahos.mypureplayer.ui.components.selectionCardOutline
import kotlinx.coroutines.launch

/** 「正在播放」在导航里用的虚拟列表 id，不落盘，数据直接取播放队列 */
const val NOW_PLAYING_ID = "__now_playing__"

/**
 * 播放列表页（底部主 Tab）。
 *
 * 页面就是一份列表，不再有「正在播放 / 我的播放列表」分组标题：
 *  - 第一项固定是名为「正在播放」的置顶列表，内容即播放队列，点进去查看/搜索/清空/排序；
 *  - 其余是自建列表，按「后置顶的在前」排序，且始终排在「正在播放」下方。
 * 长按自建列表可打开 / 重命名 / 删除 / 置顶，右下角加号只负责创建。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistScreen(navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val queue by Playback.queue.collectAsStateWithLifecycle()
    val playlists by Graph.playlists.playlists.collectAsStateWithLifecycle()

    val selection = remember { ListSelection() }
    val selectedLists = remember(playlists, selection.ids) {
        playlists.filter { selection.contains(it.id) }
    }
    val soloList = selectedLists.singleOrNull()

    var pendingDelete by remember { mutableStateOf<List<Playlist>?>(null) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var createOpen by remember { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }

    HideShellBottomBar(selection.active)
    BackHandler(enabled = selection.active) { selection.exit() }

    MainScaffold(
        topBar = {
            if (selection.active) {
                PageTopBar(
                    title = "已选择 ${selectedLists.size} 项",
                    subtitle = "轻点勾选或取消 · 右上角可全选",
                    onBack = { selection.exit() },
                    backIcon = Icons.Rounded.Close,
                    backLabel = "退出选择",
                    actions = {
                        val allSelected = playlists.isNotEmpty() && selectedLists.size == playlists.size
                        IconButton(onClick = {
                            if (allSelected) selection.clear()
                            else selection.replaceAll(playlists.map { it.id })
                        }) {
                            Icon(
                                Icons.Rounded.SelectAll,
                                contentDescription = if (allSelected) "取消全选" else "全选",
                            )
                        }
                    },
                )
            } else {
                PageTopBar(
                    title = "播放列表",
                    subtitle = "正在播放 ${queue.size} 个视频 · 自建 ${playlists.size} 个列表",
                    onBack = null,
                )
            }
        },
        bottomBar = {
            if (selection.active) {
                val canAct = selectedLists.isNotEmpty()
                val allPinned = canAct && selectedLists.all { it.pinnedAt > 0 }
                SelectionActionBar(
                    actions = listOf(
                        SelectionAction(Icons.AutoMirrored.Rounded.OpenInNew, "打开", enabled = canAct && soloList != null) {
                            soloList?.let { navController.navigate(Routes.playlistDetail(it.id)) }
                            selection.exit()
                        },
                        SelectionAction(Icons.Rounded.Edit, "编辑", enabled = canAct && soloList != null) {
                            soloList?.let {
                                renameTarget = it
                                renameText = it.name
                            }
                            selection.exit()
                        },
                        SelectionAction(Icons.Rounded.DeleteOutline, "删除", enabled = canAct, danger = true) {
                            // 只弹确认框，取消时保留选中项；真正删除在 onConfirm 里
                            pendingDelete = selectedLists
                        },
                        SelectionAction(Icons.Rounded.PushPin, if (allPinned) "取消置顶" else "置顶", enabled = canAct) {
                            val targets = selectedLists
                            val pin = !allPinned
                            selection.exit()
                            scope.launch {
                                targets.forEach { Graph.playlists.setPinned(it.id, pin) }
                                Notice.success(if (pin) "已置顶 ${targets.size} 个列表" else "已取消置顶")
                            }
                        },
                    ),
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = PageMetrics.HPad,
                    end = PageMetrics.HPad,
                    top = 8.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 「正在播放」永远排第一，不参与长按/置顶（它自己就是最顶的那个）。
                // 多选态下这一项不可点：它不在可删除/可置顶的集合里，
                // 点进去只会把用户带到详情页、并在返回时多出一次"退选择"，属于误触。
                item(key = "now-playing") {
                    NowPlayingEntry(
                        count = queue.size,
                        enabled = !selection.active,
                        onClick = { flashClick { navController.navigate(Routes.playlistDetail(NOW_PLAYING_ID)) } },
                    )
                }
                items(playlists, key = { "pl-${it.id}" }) { pl ->
                    val isSelected = selection.contains(pl.id)
                    // 与其它列表页一样：按下有淡片，波纹关掉（矩形波纹压在圆角卡上很明显）
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectPressedWithTap()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (selection.active) {
                                    Modifier.selectionCardOutline(
                                        selected = isSelected,
                                        selecting = true,
                                        pressed = pressed,
                                    )
                                } else {
                                    Modifier.mediaCardOutline(pressed = pressed)
                                }
                            )
                            .combinedClickable(
                                interactionSource = interaction,
                                indication = null,
                                role = Role.Button,
                                onClickLabel = if (selection.active) "选中/取消选择" else "打开",
                                onLongClickLabel = "管理列表",
                                onLongClick = { selection.enter(pl.id) },
                            ) {
                                if (selection.active) selection.toggle(pl.id)
                                else flashClick { navController.navigate(Routes.playlistDetail(pl.id)) }
                            }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.FormatListBulleted,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                pl.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (pl.pinnedAt > 0) "${pl.videos.size} 个视频 · 已置顶" else "${pl.videos.size} 个视频",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (pl.pinnedAt > 0) {
                            Icon(
                                Icons.Rounded.PushPin,
                                contentDescription = "已置顶",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                if (playlists.isEmpty()) {
                    item(key = "playlists-empty") {
                        Text(
                            "还没有自建列表。点右下角 + 创建一个，长按列表可以重命名、删除或置顶。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // 多选态下不显示加号：此刻底部是批量操作栏，加号既多余又容易被误触
            if (!selection.active) {
                GlassFab(
                    onClick = { newName = ""; createOpen = true },
                    contentDescription = "创建播放列表",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = FabInsets.margin, bottom = FabInsets.margin),
                )
            }
        }
    }

    // 批量删除列表
    pendingDelete?.let { targets ->
        ConfirmDialog(
            title = "删除播放列表",
            message = if (targets.size == 1) {
                "确定删除「${targets.first().name}」吗？只删除列表本身，不会删除任何视频文件。"
            } else {
                "确定删除选中的 ${targets.size} 个播放列表吗？只删除列表本身，不会删除任何视频文件。"
            },
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                scope.launch {
                    targets.forEach { Graph.playlists.delete(it.id) }
                    pendingDelete = null
                    selection.exit()
                    Notice.success("已删除 ${targets.size} 个播放列表")
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // 重命名（由长按「编辑」触发）
    renameTarget?.let { target ->
        AlertDialog(
            shape = RoundedCornerShape(26.dp),
            containerColor = appGlass().panelStrong,
            tonalElevation = 0.dp,
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名列表") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renameText.trim()
                        if (name.isEmpty()) {
                            Notice.error("请输入名称")
                            return@TextButton
                        }
                        renameTarget = null
                        scope.launch {
                            Graph.playlists.rename(target.id, name)
                            Notice.success("已重命名")
                        }
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    // 创建播放列表（命名）
    if (createOpen) {
        AlertDialog(
            shape = RoundedCornerShape(26.dp),
            containerColor = appGlass().panelStrong,
            tonalElevation = 0.dp,
            onDismissRequest = { createOpen = false },
            title = { Text("创建播放列表") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("名称") },
                    placeholder = { Text("例如：周末观影") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isEmpty()) {
                            Notice.error("请输入名称")
                            return@TextButton
                        }
                        createOpen = false
                        scope.launch {
                            val pl = Graph.playlists.create(name)
                            Notice.success("已创建「${pl.name}」")
                            navController.navigate(Routes.playlistDetail(pl.id))
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { createOpen = false }) { Text("取消") } },
        )
    }
}

/**
 * 置顶的「正在播放」列表项。
 *
 * 外观与普通自建列表完全一致（同一套玻璃底、同一图标、同样的「N 个视频 · 已置顶」副标题），
 * 它就是一个名为「正在播放」的置顶列表 —— 当前播放内容与进度放进它的详情页顶部展示。
 * 多选态下 [enabled] = false：它不是可勾选/可删除的实体列表，避免误触多一层返回。
 */
@Composable
private fun NowPlayingEntry(
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appGlassSurface(RoundedCornerShape(PageMetrics.CardRadius))
            .glassPressable(
                enabled = enabled,
                role = Role.Button,
                radius = PageMetrics.CardRadius,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.FormatListBulleted,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text("正在播放", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "$count 个视频 · 已置顶",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Rounded.PushPin,
            contentDescription = "已置顶",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 添加视频来源面板：与「选择文件夹 / 选择共享位置」同一套居中的玻璃圆条弹窗，
 * 不再用底部 ModalBottomSheet（那套容器色发灰、和全站玻璃卡是两种材质）。
 * 被播放主页与自建列表详情共用。
 */
@Composable
fun AddVideoSheet(
    videos: List<VideoItem>,
    servers: List<SmbServer>,
    onDismiss: () -> Unit,
    onLocal: () -> Unit,
    onNetwork: () -> Unit,
) {
    GlassChoiceDialog(title = "添加视频", onDismiss = onDismiss) {
        Text("添加视频", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassChoiceRow(
                icon = Icons.Rounded.Folder,
                title = "从本机文件夹添加",
                subtitle = if (videos.isEmpty()) "还没有扫描到本地视频" else "已扫描 ${videos.size} 个本地视频",
                enabled = videos.isNotEmpty(),
                onClick = onLocal,
            )
            GlassChoiceRow(
                icon = Icons.Rounded.Cloud,
                title = "从网络共享添加",
                subtitle = if (servers.isEmpty()) "还没有添加 SMB 共享" else "已添加 ${servers.size} 个共享位置",
                enabled = servers.isNotEmpty(),
                onClick = onNetwork,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    }
}

/** 排序：预览与「确定」共用一份逻辑（详情页使用） */
fun sortedVideos(items: List<VideoItem>, key: com.yaoahos.mypureplayer.data.SortKey, ascending: Boolean): List<VideoItem> {
    val comparator: Comparator<VideoItem> = when (key) {
        com.yaoahos.mypureplayer.data.SortKey.NAME -> compareBy { it.title.lowercase() }
        com.yaoahos.mypureplayer.data.SortKey.DATE -> compareBy { it.dateModified }
        com.yaoahos.mypureplayer.data.SortKey.DURATION -> compareBy { com.yaoahos.mypureplayer.media.Thumbnails.displayDuration(it) }
        com.yaoahos.mypureplayer.data.SortKey.SIZE -> compareBy { it.sizeBytes }
    }
    return items.sortedWith(if (ascending) comparator else comparator.reversed())
}
