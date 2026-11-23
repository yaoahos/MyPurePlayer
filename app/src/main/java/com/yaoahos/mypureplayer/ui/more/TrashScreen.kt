package com.yaoahos.mypureplayer.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.MediaSource
import com.yaoahos.mypureplayer.data.SortKey
import com.yaoahos.mypureplayer.data.TrashEntry
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.ui.components.ActionDivider
import com.yaoahos.mypureplayer.ui.components.ActionRow
import com.yaoahos.mypureplayer.ui.components.AdaptiveSheet
import com.yaoahos.mypureplayer.ui.components.ConfirmDialog
import com.yaoahos.mypureplayer.ui.components.EmptyState
import com.yaoahos.mypureplayer.ui.components.InfoRow
import com.yaoahos.mypureplayer.ui.components.ListRowContainer
import com.yaoahos.mypureplayer.ui.components.ListSelection
import com.yaoahos.mypureplayer.ui.components.MediaFolderGridCard
import com.yaoahos.mypureplayer.ui.components.MediaFolderRow
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.SelectionAction
import com.yaoahos.mypureplayer.ui.components.SelectionActionBar
import com.yaoahos.mypureplayer.ui.components.SelectionBadge
import com.yaoahos.mypureplayer.ui.components.SelectionMoreSheet
import com.yaoahos.mypureplayer.ui.components.SearchField
import com.yaoahos.mypureplayer.ui.components.SortLayoutSheet
import com.yaoahos.mypureplayer.ui.components.VideoGridCard
import com.yaoahos.mypureplayer.ui.components.VideoListRow
import com.yaoahos.mypureplayer.ui.components.adaptiveMediaGridMinSize
import com.yaoahos.mypureplayer.ui.components.appGlass
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.listRowMinSize
import com.yaoahos.mypureplayer.ui.local.startPlayback
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站：删除的本地视频 / 整个文件夹暂存在这里，可随时恢复；
 * 只有在这里再次删除才会真正抹掉。
 *
 * 「怎么删除的就怎么恢复」：删的是文件夹，这里就是一条文件夹记录，
 * 恢复时整目录搬回原位（目录名、内部文件都不变）；删的是单个视频，就是一条视频记录。
 * 所以列表不再把文件夹拆成 N 个视频 —— 那样恢复出来的只是散落的文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by Graph.trash.entries.collectAsStateWithLifecycle()

    var confirmClear by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<List<TrashEntry>?>(null) }
    // 点按/长按卡片都会选中该条并弹出动作面板；面板关闭后点按其它条目继续加选。
    val selection = remember { ListSelection() }
    var infoTarget by remember { mutableStateOf<TrashEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // 搜索：与本地页/历史记录同一套交互，返回键先收搜索框再退出选择态
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selectedEntries = remember(entries, selection.ids) {
        entries.filter { selection.contains(it.id.toString()) }
    }

    BackHandler(enabled = selection.active) { selection.exit() }
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
    }

    // 排序：与本地页/播放列表同一个面板、同一套用词（排列与排序 → 排序）
    var sortKey by remember { mutableStateOf(SortKey.DATE) }
    var ascending by remember { mutableStateOf(false) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    // 宫格 / 列表：与本地页、播放列表共用同一个「排列与排序」面板。
    // 默认给宫格 —— 本地视频页默认就是宫格，两边打开第一眼是同一个版式。
    var gridMode by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) { Graph.trash.load() }

    fun entryItem(entry: TrashEntry): VideoItem {
        val file = Graph.trash.fileOf(entry)
        return VideoItem(
            id = "trash:${entry.id}",
            title = entry.fileName.substringBeforeLast('.', entry.fileName),
            fileName = entry.fileName,
            uri = "file://${file.absolutePath}",
            source = MediaSource.LOCAL,
            durationMs = entry.durationMs,
            sizeBytes = entry.sizeBytes,
            dateModified = entry.deletedAt,
            folderName = entry.originalFolderName,
            folderKey = "trash",
            width = entry.width,
            height = entry.height,
            localPath = file.absolutePath,
        )
    }

    fun onEntryClick(entry: TrashEntry) {
        if (selection.active) {
            selection.toggle(entry.id.toString())
        } else {
            // 回收站里点按与长按都只是进入选择态，动作放到顶栏 + 底部操作栏。
            selection.enter(entry.id.toString())
        }
    }

    fun onEntryLongClick(entry: TrashEntry) {
        selection.enter(entry.id.toString())
    }

    fun restoreEntries(targets: List<TrashEntry>) {
        if (targets.isEmpty()) return
        scope.launch {
            var ok = 0
            val failed = mutableListOf<String>()
            targets.forEach { entry ->
                Graph.trash.restore(entry).fold(
                    onSuccess = { ok++ },
                    onFailure = { failed += it.message ?: entry.fileName },
                )
            }
            Graph.local.scan()
            message = when {
                failed.isEmpty() && ok == 1 -> "已恢复：${targets.first().fileName}"
                failed.isEmpty() -> "已恢复 $ok 项"
                else -> "已恢复 $ok 项，${failed.size} 项失败：${failed.first()}"
            }
            selection.exit()
        }
    }

    val shown = remember(entries, sortKey, ascending, query) {
        val comparator: Comparator<TrashEntry> = when (sortKey) {
            SortKey.NAME -> compareBy { it.fileName.lowercase() }
            SortKey.DATE -> compareBy { it.deletedAt }
            SortKey.DURATION -> compareBy { it.durationMs }
            SortKey.SIZE -> compareBy { it.sizeBytes }
        }
        val matched = if (query.isBlank()) entries else entries.filter {
            it.fileName.contains(query, ignoreCase = true) ||
                it.originalPath.contains(query, ignoreCase = true) ||
                it.originalFolderName.contains(query, ignoreCase = true)
        }
        matched.sortedWith(if (ascending) comparator else comparator.reversed())
    }

    Scaffold(
        // 透明容器：背景由根部的常驻玻璃层统一绘制，内部页面与底栏页共用同一张底
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // 页面级不透明背景：转场时遮住下面那一页，避免残影
        modifier = Modifier.fillMaxSize().glassPageBackground(),
        topBar = {
            PageTopBar(
                title = if (selection.active) "已选择 ${selection.count} 项" else "回收站",
                // 只说明"这里是什么"，不再报数量、不再教怎么点：
                // 条目数在每张卡片上有，操作方式点一下就知道，标题下面塞两行提示只会变成噪音。
                subtitle = when {
                    selection.active -> "轻点勾选或取消 · 右上角可全选"
                    query.isNotBlank() -> "找到 ${shown.size} 项"
                    else -> "本地 / 群晖删除的文件"
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
                        // 搜索时「全选」只作用于当前可见的结果
                        val allSelected = shown.isNotEmpty() && shown.all { selection.contains(it.id.toString()) }
                        IconButton(onClick = {
                            // 取消全选只清勾选项，不退出选择态
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
                        if (entries.isNotEmpty()) {
                            // 排序：删掉的是一个文件夹时也要能按名字找回来
                            IconButton(onClick = { sortSheetOpen = true }) {
                                Icon(Icons.Rounded.Tune, contentDescription = "排序")
                            }
                            // 清空是破坏性操作：图标也用错误色，不再和普通动作长一样
                            IconButton(onClick = { confirmClear = true }) {
                                Icon(
                                    Icons.Rounded.DeleteSweep,
                                    contentDescription = "清空回收站",
                                    tint = MaterialTheme.colorScheme.error,
                                )
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
                // 只有选中的是单个视频（文件夹不可播放）时才给出「播放」
                val playableVideo = selectedEntries.singleOrNull()?.takeIf { !it.isFolder }
                SelectionActionBar(
                    actions = listOf(
                        SelectionAction(Icons.Rounded.PlayArrow, "播放", enabled = playableVideo != null) {
                            playableVideo?.let { startPlayback(navController, entryItem(it)) }
                            selection.exit()
                        },
                        SelectionAction(Icons.Rounded.Info, "详情", enabled = canAct && single) {
                            infoTarget = selectedEntries.first()
                        },
                        SelectionAction(Icons.Rounded.Restore, "恢复", enabled = canAct) {
                            restoreEntries(selectedEntries.toList())
                        },
                        // 彻底删除不可撤销，收进「更多」
                        SelectionAction(Icons.Rounded.DeleteForever, "彻底删除", enabled = canAct, danger = true) {
                            deleteTarget = selectedEntries.toList()
                        },
                    ),
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    forceMore = true,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = PageMetrics.HPad, vertical = 6.dp),
                )
            }

            if (searchOpen) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onClose = { searchOpen = false; query = "" },
                    placeholder = "搜索回收站",
                )
            }

            if (shown.isEmpty()) {
                EmptyState(
                    icon = if (query.isNotBlank()) Icons.Rounded.Search else Icons.Rounded.DeleteOutline,
                    title = if (query.isNotBlank()) "没有匹配的内容" else "回收站是空的",
                    message = if (query.isNotBlank()) {
                        "换个关键词试试。"
                    } else {
                        "本地视频或文件夹删除后会先进入这里，可以恢复；在回收站内删除才会彻底移除。"
                    },
                )
                return@Column
            }

            // 列宽、卡片、缩略图全部沿用本地视频那套组件：
            // 回收站以前自己写了一套 150dp 的宫格，手机上只有 2 列、卡片明显比本地页大一号，
            // 同一批文件在两个页面之间跳来跳去尺寸不一致。现在两边的格子数与卡片尺寸必然相同。
            if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = adaptiveMediaGridMinSize()),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PageMetrics.HPad,
                        end = PageMetrics.HPad,
                        top = 8.dp,
                        bottom = 28.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { entry ->
                        if (entry.isFolder) {
                            MediaFolderGridCard(
                                name = entry.fileName,
                                subtitle = folderSubtitle(entry),
                                onClick = { onEntryClick(entry) },
                                onLongClick = { onEntryLongClick(entry) },
                                selected = selection.contains(entry.id.toString()),
                                selecting = selection.active,
                                subtitleMaxLines = 2,
                            )
                        } else {
                            VideoGridCard(
                                item = entryItem(entry),
                                onClick = { onEntryClick(entry) },
                                onLongClick = { onEntryLongClick(entry) },
                                selected = selection.contains(entry.id.toString()),
                                selecting = selection.active,
                                metaOverride = trashVideoMeta(entry),
                                metaMaxLines = 2,
                            )
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = listRowMinSize()),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PageMetrics.HPad,
                        end = PageMetrics.HPad,
                        top = 4.dp,
                        bottom = 28.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(shown, key = { it.id }) { entry ->
                        ListRowContainer {
                            if (entry.isFolder) {
                                MediaFolderRow(
                                    name = entry.fileName,
                                    subtitle = folderSubtitle(entry),
                                    onClick = { onEntryClick(entry) },
                                    onLongClick = { onEntryLongClick(entry) },
                                    selected = selection.contains(entry.id.toString()),
                                    selecting = selection.active,
                                    showSubtitle = true,
                                )
                            } else {
                                VideoListRow(
                                    item = entryItem(entry),
                                    onClick = { onEntryClick(entry) },
                                    onLongClick = { onEntryLongClick(entry) },
                                    selected = selection.contains(entry.id.toString()),
                                    selecting = selection.active,
                                    metaOverride = trashVideoMeta(entry),
                                    metaMaxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    infoTarget?.let { entry ->
        TrashInfoDialog(entry = entry, onDismiss = { infoTarget = null })
    }

    if (sortSheetOpen) {
        SortLayoutSheet(
            onDismiss = { sortSheetOpen = false },
            title = "排列与排序",
            initialGrid = gridMode,
            initialSort = sortKey,
            initialAscending = ascending,
            entries = listOf(SortKey.NAME, SortKey.DATE, SortKey.DURATION, SortKey.SIZE),
            onPreview = { grid, key, asc ->
                // 实时预览：选完不必等「确定」才看到效果
                gridMode = grid
                sortKey = key
                ascending = asc
            },
            onApply = { grid, key, asc ->
                gridMode = grid
                sortKey = key
                ascending = asc
                sortSheetOpen = false
            },
        )
    }

    deleteTarget?.let { targets ->
        ConfirmDialog(
            title = if (targets.size == 1 && targets.first().isFolder) "彻底删除文件夹" else "彻底删除",
            message = if (targets.size == 1) {
                if (targets.first().isFolder) {
                    "「${targets.first().fileName}」以及里面的全部文件将被永久删除，无法恢复。"
                } else {
                    "「${targets.first().fileName}」将被永久删除，无法恢复。"
                }
            } else {
                "已选中的 ${targets.size} 项将被永久删除，无法恢复。"
            },
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    Graph.trash.deletePermanently(targets)
                    message = if (targets.size == 1) "已彻底删除" else "已彻底删除 ${targets.size} 项"
                    selection.exit()
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "清空回收站",
            message = "回收站中的 ${entries.size} 项内容将被永久删除，无法恢复。",
            confirmText = "清空",
            destructive = true,
            onConfirm = {
                confirmClear = false
                scope.launch {
                    Graph.trash.clear()
                    message = "回收站已清空"
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/**
 * 回收站卡片上的「原来在哪」：只取父目录名，完整路径放进详情。
 * 卡片宽度只有八十多 dp，塞完整路径必然被省略号切成没有信息量的半截。
 */
private fun trashLocation(entry: TrashEntry): String {
    val parent = entry.originalPath.substringBeforeLast('/')
    val name = parent.substringAfterLast('/').ifBlank { parent }
    return name.ifBlank { "未知位置" }
}

/** 回收站视频卡副标题：位置 · 大小 · 时长，三项都能在卡片上直接看到 */
private fun trashVideoMeta(entry: TrashEntry): String = buildString {
    append(trashLocation(entry))
    if (entry.sizeBytes > 0L) append(" · ${Formats.formatSize(entry.sizeBytes)}")
    if (entry.durationMs > 0L) append(" · ${Formats.formatDuration(entry.durationMs)}")
}

/** 文件夹那一行/那一格的副标题：原位置 + 个数 + 大小 */
private fun folderSubtitle(entry: TrashEntry): String = buildString {
    append(trashLocation(entry))
    if (entry.fileCount > 0) append(" · ${entry.fileCount} 个视频")
    append(" · ")
    append(Formats.formatSize(entry.sizeBytes))
}

/**
 * 回收站条目详情。
 *
 * 这里必须显示**原位置**而不是回收站里的存放路径：用户来这里就是想确认
 * "这东西原来在哪"，给他一条 `Android/data/.../trash/1723_xxx.mp4` 毫无用处。
 */
@Composable
private fun TrashInfoDialog(entry: TrashEntry, onDismiss: () -> Unit) {
    val deletedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(entry.deletedAt))
    AlertDialog(
        shape = RoundedCornerShape(26.dp),
        containerColor = appGlass().panelStrong,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text(if (entry.isFolder) "文件夹详情" else "视频详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("名称", entry.fileName)
                InfoRow(
                    "原位置",
                    if (entry.isFolder) entry.originalPath
                    else entry.originalPath.substringBeforeLast('/', ""),
                    copyable = true,
                )
                if (entry.isFolder) {
                    InfoRow("视频", "${entry.fileCount} 个")
                } else {
                    InfoRow(
                        "时长",
                        if (entry.durationMs > 0L) Formats.formatDuration(entry.durationMs) else "未知",
                    )
                    InfoRow(
                        "分辨率",
                        if (entry.width > 0 && entry.height > 0) "${entry.width} × ${entry.height}"
                        else "未知",
                    )
                }
                InfoRow("大小", Formats.formatSize(entry.sizeBytes))
                InfoRow("删除时间", deletedAt)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
