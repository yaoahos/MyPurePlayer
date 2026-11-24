package com.yaoahos.mypureplayer.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.AppSettings
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.HistoryEntry
import com.yaoahos.mypureplayer.data.MediaSource
import com.yaoahos.mypureplayer.data.SmbClient
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.ActionDivider
import com.yaoahos.mypureplayer.ui.components.ActionRow
import com.yaoahos.mypureplayer.ui.components.AdaptiveSheet
import com.yaoahos.mypureplayer.ui.components.ConfirmDialog
import com.yaoahos.mypureplayer.ui.components.EmptyState
import com.yaoahos.mypureplayer.ui.components.ListSelection
import com.yaoahos.mypureplayer.ui.components.MediaInfoDialog
import com.yaoahos.mypureplayer.ui.components.VideoThumb
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.PlaylistTargetSheet
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.SelectionAction
import com.yaoahos.mypureplayer.ui.components.SelectionActionBar
import com.yaoahos.mypureplayer.ui.components.SelectionBadge
import com.yaoahos.mypureplayer.ui.components.SelectionMoreSheet
import com.yaoahos.mypureplayer.ui.components.SearchField
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.mediaCardOutline
import com.yaoahos.mypureplayer.ui.components.selectionCardOutline
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by Graph.history.history.collectAsStateWithLifecycle()
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var clearConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    // 「加入列表」的目标由用户自己选
    var pendingAdd by remember { mutableStateOf<List<com.yaoahos.mypureplayer.data.VideoItem>?>(null) }
    // 长按 = 选中这一条 + 弹出动作面板；多选状态下点按其它条目只切换选中。
    val selection = remember { ListSelection() }
    var infoTarget by remember { mutableStateOf<VideoItem?>(null) }
    // 搜索：与本地页/保险箱同一套交互，返回键先收搜索框再退出选择态
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selectedEntries = remember(history, selection.ids) {
        history.filter { selection.contains(it.id.toString()) }
    }
    val shown = remember(history, query) {
        if (query.isBlank()) history
        else history.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.fileName.contains(query, ignoreCase = true) ||
                it.folderName.contains(query, ignoreCase = true) ||
                it.uri.contains(query, ignoreCase = true)
        }
    }

    BackHandler(enabled = selection.active) { selection.exit() }
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
    }

    /** 历史条目只存了播放必需字段；详情弹窗尽量用库里的最新 item 补全大小/分辨率/位置。 */
    fun openInfo(entry: HistoryEntry) {
        scope.launch { infoTarget = entry.fullItem() }
    }

    LaunchedEffect(Unit) {
        Graph.history.load()
        // 兜底：文件可能是在系统相册 / 文件管理器里被删掉的，那条路径不经过本应用的删除流程，
        // 历史里就会留下一点就报错的死记录。开页时清一次，比让用户自己发现更好。
        Graph.history.pruneMissing()
    }

    // 页面停留期间也可能被别的应用删文件：每次切回本页都按 File.exists 对账一次。
    // 只清真不存在的，不会动刚刚播放到一半的记录。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { Graph.history.pruneMissing() }
    }

    Scaffold(
        // 透明容器：背景由根部的常驻玻璃层统一绘制，内部页面与底栏页共用同一张底
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // 页面级不透明背景：转场时遮住下面那一页，避免残影
        modifier = Modifier.fillMaxSize().glassPageBackground(),
        topBar = {
            PageTopBar(
                title = if (selection.active) "已选择 ${selection.count} 条" else "历史记录",
                subtitle = when {
                    selection.active -> "轻点勾选或取消 · 右上角可全选"
                    query.isNotBlank() -> "找到 ${shown.size} 条记录"
                    else -> "点按继续上次看到的位置"
                },
                onBack = {
                    if (selection.active) {
                        selection.exit()
                    } else navController.popBackStack()
                },
                backIcon = if (selection.active) Icons.Rounded.Close else null,
                backLabel = if (selection.active) "退出选择" else "返回",
                actions = {
                    if (selection.active) {
                        // 搜索时「全选」只作用于当前可见的结果，不会把被过滤掉的记录也勾上
                        val allSelected = shown.isNotEmpty() && shown.all { selection.contains(it.id.toString()) }
                        IconButton(onClick = {
                            // 取消全选只清勾选项，不退出选择态（顶栏 X 才是退出）
                            if (allSelected) selection.clear()
                            else selection.replaceAll(shown.map { it.id.toString() })
                        }) {
                            Icon(
                                Icons.Rounded.SelectAll,
                                contentDescription = if (allSelected) "取消全选" else "全选",
                            )
                        }
                    } else {
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "搜索")
                        }
                        if (history.isNotEmpty()) {
                            IconButton(onClick = { clearConfirm = true }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "清空历史")
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selection.active) {
                val single = selectedEntries.size == 1
                val canAct = selectedEntries.isNotEmpty()
                SelectionActionBar(
                    actions = listOf(
                        SelectionAction(Icons.Rounded.PlayArrow, "播放", enabled = canAct && single) {
                            resume(navController, scope, selectedEntries.first(), settings.resumePlayback)
                            selection.exit()
                        },
                        SelectionAction(Icons.Rounded.Info, "详情", enabled = canAct && single) {
                                openInfo(selectedEntries.first())
                        },
                        // 删除只改历史记录，放前三格；「加入列表」是唯一进「更多」的动作
                        SelectionAction(Icons.Rounded.DeleteOutline, "删除", enabled = canAct, danger = true) {
                            pendingDelete = selectedEntries.toList()
                        },
                        SelectionAction(
                            Icons.AutoMirrored.Rounded.FormatListBulleted,
                            "加入列表",
                            enabled = canAct,
                        ) {
                            pendingAdd = selectedEntries.map { it.toStub() }
                        },
                    ),
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    forceMore = true,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searchOpen) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onClose = { searchOpen = false; query = "" },
                    placeholder = "搜索观看记录",
                )
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        icon = if (query.isNotBlank()) Icons.Rounded.Search else Icons.Rounded.History,
                        title = if (query.isNotBlank()) "没有匹配的内容" else "还没有观看记录",
                        message = if (query.isNotBlank()) {
                            "换个关键词试试。"
                        } else {
                            "播放过的视频会出现在这里，下次可以从上次的位置继续。"
                        },
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PageMetrics.HPad,
                        end = PageMetrics.HPad,
                        top = 4.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { entry ->
                        HistoryRow(
                            entry = entry,
                            selected = selection.contains(entry.id.toString()),
                            selecting = selection.active,
                            onClick = {
                                if (selection.active) {
                                    selection.toggle(entry.id.toString())
                                } else {
                                    flashClick { resume(navController, scope, entry, settings.resumePlayback) }
                                }
                            },
                            onLongClick = {
                                // 长按只进入选择态：不弹「更多操作」面板。
                                // 更多动作由底部操作栏的「更多」按钮显式打开。
                                selection.enter(entry.id.toString())
                            },
                        )
                    }
                }
            }
        }
    }

    infoTarget?.let { item ->
        MediaInfoDialog(item = item, onDismiss = { infoTarget = null })
    }

    pendingDelete?.let { entries ->
        ConfirmDialog(
            title = "删除观看记录",
            message = if (entries.size == 1) {
                "确定删除「${entries.first().title}」的这条观看记录吗？不会删除视频文件。"
            } else {
                "已选中的 ${entries.size} 条观看记录将被删除，不会删除视频文件。"
            },
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                scope.launch { Graph.history.removeByIds(entries.map { it.id }) }
                pendingDelete = null
                selection.exit()
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (clearConfirm) {
        ConfirmDialog(
            title = "清空历史记录",
            message = "清空后无法恢复，确定继续吗？",
            confirmText = "清空",
            destructive = true,
            onConfirm = {
                scope.launch { Graph.history.clear() }
                clearConfirm = false
            },
            onDismiss = { clearConfirm = false },
        )
    }

    pendingAdd?.let { items ->
        PlaylistTargetSheet(
            onDismiss = { pendingAdd = null },
            onPick = { targetId, name ->
                pendingAdd = null
                if (targetId.isEmpty()) {
                    Playback.addToQueue(items)
                    Notice.success("已加入「正在播放」${items.size} 个视频")
                } else {
                    scope.launch {
                        val n = Graph.playlists.addVideos(targetId, items)
                        if (n > 0) Notice.success("已加入「$name」$n 个视频")
                        else Notice.error("「$name」没有新增视频")
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean = false,
    selecting: Boolean = false,
) {
    val stub = remember(entry.id) { entry.toStub() }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedWithTap()
    // 与回收站 / 保险箱 / 串流同一套：玻璃行卡 + 20dp 圆角 + 104×60 缩略图。
    // 早先这里是「裸行」——没有卡片底，只有一条缩略图，切到隔壁页就像换了个 App。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectionCardOutline(pressed = pressed, selected = selected, selecting = selecting)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onClickLabel = if (selecting) "选中/取消选择" else "继续观看",
                onLongClick = onLongClick,
                onLongClickLabel = "更多操作",
                role = Role.Button,
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 104.dp, height = 60.dp)) {
            VideoThumb(stub, Modifier.fillMaxSize(), cornerRadius = PageMetrics.ThumbRadius)
            SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append("看到 ${Formats.formatDuration(entry.positionMs)}")
                    if (entry.durationMs > 0) append(" / ${Formats.formatDuration(entry.durationMs)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Formats.formatAgo(entry.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

private fun HistoryEntry.toStub(): VideoItem = VideoItem(
    id = videoId,
    title = title,
    fileName = fileName.ifBlank { title },
    uri = uri,
    source = source,
    durationMs = durationMs,
    folderName = folderName,
    folderKey = folderKey,
    serverId = serverId,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    dateModified = dateModified,
    localPath = localPath,
)

/** 用媒体库里的当前条目补全历史记录里没有保存的详情字段 */
private suspend fun HistoryEntry.fullItem(): VideoItem {
    val base = toStub()
    val fresh = when (source) {
        MediaSource.LOCAL -> Graph.local.videos.value.firstOrNull { it.id == videoId }
        MediaSource.SMB -> {
            // 共享已被移除 / 默认目录改过时不拿历史地址去连，详情就用手头保存的快照
            val server = Graph.servers.forSmbItem(serverId, uri)
            server?.let { srv ->
                runCatching { SmbClient.toVideoItems(srv, SmbClient.list(srv, folderKey), folderKey) }
                    .getOrNull()?.firstOrNull { it.id == videoId }
            }
        }
        MediaSource.STREAM -> null
    } ?: return base
    return base.copy(
        sizeBytes = base.sizeBytes.takeIf { it > 0 } ?: fresh.sizeBytes,
        width = base.width.takeIf { it > 0 } ?: fresh.width,
        height = base.height.takeIf { it > 0 } ?: fresh.height,
        folderName = base.folderName.ifBlank { fresh.folderName },
        dateModified = base.dateModified.takeIf { it > 0 } ?: fresh.dateModified,
    )
}

/**
 * 继续观看：能还原出同目录队列就还原，还原不了就单条播放。
 */
private fun resume(
    navController: NavHostController,
    scope: kotlinx.coroutines.CoroutineScope,
    entry: HistoryEntry,
    resumePosition: Boolean,
) {
    val start = if (resumePosition) entry.positionMs else 0L
    val stub = entry.toStub()
    scope.launch {
        val list: List<VideoItem> = when (entry.source) {
            MediaSource.LOCAL -> Graph.local.videosInFolder(entry.folderKey).ifEmpty { listOf(stub) }
            MediaSource.SMB -> {
                val server = Graph.servers.forSmbItem(entry.serverId, entry.uri)
                val remote = if (server != null) {
                    runCatching {
                        SmbClient.toVideoItems(server, SmbClient.list(server, entry.folderKey), entry.folderKey)
                    }.getOrDefault(emptyList())
                } else emptyList()
                remote.ifEmpty { listOf(stub) }
            }

            MediaSource.STREAM -> listOf(stub)
        }
        // 重新扫描后 MediaStore 行号可能变化，id 找不到时用真实路径再匹配一次，
        // 否则会默默播成同目录的第一条（用户感知就是「点历史播错了/打不开」）
        val index = list.indexOfFirst {
            it.id == stub.id || (stub.localPath != null && it.localPath == stub.localPath)
        }
        // 同目录里已经找不到这一条：只拿它自己去做存在性检查，查不到就提示，
        // 不能按 0 起播改成同目录的第一条
        val queue = if (index >= 0) list else listOf(stub)
        Playback.playChecked(queue, index.coerceAtLeast(0), start) {
            navController.navigate(Routes.PLAYER)
        }
    }
}
