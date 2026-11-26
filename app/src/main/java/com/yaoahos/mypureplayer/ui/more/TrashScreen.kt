package com.yaoahos.mypureplayer.ui.more

import com.yaoahos.mypureplayer.ui.components.GlassDialogButton
import com.yaoahos.mypureplayer.ui.components.GlassButtonTone
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.MediaSource
import com.yaoahos.mypureplayer.data.SortKey
import com.yaoahos.mypureplayer.data.TrashEntry
import com.yaoahos.mypureplayer.data.VideoItem
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
import com.yaoahos.mypureplayer.ui.components.MediaSortDraft
import com.yaoahos.mypureplayer.ui.components.SelectionAction
import com.yaoahos.mypureplayer.ui.components.SelectionActionBar
import com.yaoahos.mypureplayer.ui.components.SelectionBadge
import com.yaoahos.mypureplayer.ui.components.SelectionMoreSheet
import com.yaoahos.mypureplayer.ui.components.SearchField
import com.yaoahos.mypureplayer.ui.components.SortLayoutSheet
import com.yaoahos.mypureplayer.ui.components.VideoGridCard
import com.yaoahos.mypureplayer.ui.components.VideoListRow
import com.yaoahos.mypureplayer.ui.components.adaptiveMediaGridMinSize
import com.yaoahos.mypureplayer.ui.components.alertDialogGlassSurface
import com.yaoahos.mypureplayer.ui.components.flashClick
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.listRowMinSize
import com.yaoahos.mypureplayer.ui.local.startPlayback
import kotlinx.coroutines.flow.first
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
    // 「清除播放进度」是否点亮要按选中项里到底有没有进度判断：
    // 回收站的进度有两处来源 —— 历史记录（卡片进度条读它）与原路径上的进度表（清时会一起删）。
    val history by Graph.history.history.collectAsStateWithLifecycle()
    val marks by Graph.progress.marks.collectAsStateWithLifecycle()

    var confirmClear by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<List<TrashEntry>?>(null) }
    // 单击视频直接播放、长按进入选择态 —— 与本地/历史/保险箱全站一致。
    // 选中后由底部那套四格操作栏（播放/详情/恢复/彻底删除）接管，不再另起一套菜单。
    val selection = remember { ListSelection() }
    var infoTarget by remember { mutableStateOf<TrashEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // 搜索：与本地页/历史记录同一套交互，返回键先收搜索框再退出选择态
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selectedEntries = remember(entries, selection.ids) {
        entries.filter { selection.contains(it.id.toString()) }
    }
    // 文件夹没有播放进度；视频要有进度才允许清（历史里的 trash:<id> 或原路径的进度表）。
    val hasProgress = remember(selectedEntries, history, marks) {
        selectedEntries.any { entry ->
            !entry.isFolder && (
                history.any { it.videoId == "trash:${entry.id}" && it.positionMs > 0L } ||
                    marks.any { it.key == entry.originalPath && it.positionMs > 0L }
                )
        }
    }

    BackHandler(enabled = selection.active) { selection.exit() }
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
    }

    // 排列与排序：退出再进来要保住，所以初值取自持久化设置，确定时再写回 DataStore。
    val appSettings by Graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = Graph.settings.currentOrNull() ?: com.yaoahos.mypureplayer.data.AppSettings(),
    )
    var sortKey by remember { mutableStateOf(runCatching { SortKey.valueOf(appSettings.trashSort) }.getOrDefault(SortKey.DATE)) }
    var ascending by remember { mutableStateOf(appSettings.trashAscending) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var sortDraft by remember { mutableStateOf<MediaSortDraft?>(null) }
    // 宫格 / 列表：与本地页、播放列表共用同一个「排列与排序」面板。
    var gridMode by rememberSaveable { mutableStateOf(appSettings.trashGrid) }
    LaunchedEffect(appSettings.trashGrid, appSettings.trashSort, appSettings.trashAscending) {
        gridMode = appSettings.trashGrid
        sortKey = runCatching { SortKey.valueOf(appSettings.trashSort) }.getOrDefault(SortKey.DATE)
        ascending = appSettings.trashAscending
    }

    LaunchedEffect(Unit) {
        Graph.trash.load()
        // 自动清理放在「进入回收站页」而不是冷启动：冷启动时用户在别的页面，
        // 文件被悄悄删掉却不知道发生了什么；在这里清完能立刻给出提示，
        // 列表也已经刷新到清理后的样子。
        val settings = Graph.settings.settings.first()
        if (settings.trashAutoClean) {
            val result = Graph.trash.autoClean(settings.trashRetentionDays)
            if (result.removed > 0) {
                message = "已自动清理 ${result.removed} 个超过 ${settings.trashRetentionDays} 天" +
                    "的视频，释放 ${Formats.formatSize(result.freedBytes)}"
            }
        }
    }

    // 存储卡被拔掉时，卡上的条目仍留在列表里（否则会"凭空消失"，插回来才又出现），
    // 但文件此刻读不到。这里标记出来，避免点开才发现播不了。
    // 放在 IO 里算：条目多时逐条 stat 也不该占主线程。
    var missingIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(entries) {
        missingIds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            entries.filterNot { Graph.trash.fileOf(it).exists() }.mapTo(HashSet()) { it.id }
        }
    }
    fun unavailable(entry: TrashEntry): Boolean = entry.id in missingIds

    /**
     * 卡片副标题。
     *
     * 读不到文件时把「原来在哪」整段换成原因，而不是在末尾追加 ——
     * 卡片宽度只够显示两行，追加的提示必然被省略号切掉，用户反而看不到。
     */
    fun cardMeta(entry: TrashEntry): String = when {
        unavailable(entry) && entry.isFolder ->
            "存储卡未插入 · ${entry.fileCount} 个视频 · ${Formats.formatSize(entry.sizeBytes)}"
        unavailable(entry) -> "存储卡未插入 · ${Formats.formatSize(entry.sizeBytes)}"
        entry.isFolder -> folderSubtitle(entry)
        else -> trashVideoMeta(entry)
    }

    fun entryItem(entry: TrashEntry): VideoItem {
        val file = Graph.trash.fileOf(entry)
        // 物理文件是随机名 + `.trash`，扩展名不再能说明封装格式：
        // - `fileName` 仍填原文件名，标题、MIME、缩略图的容器提示都靠它；
        // - 播放器靠内容嗅探识别封装（LocalStreamServer 的 MIME 同样取 fileName），
        //   所以这里不能把 uri 里的文件名当成类型依据。
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
        } else if (unavailable(entry)) {
            message = "存储卡未插入，插回后可播放或恢复"
        } else if (!entry.isFolder) {
            // 单击视频 = 播放（与本地/历史/保险箱一致）。先闪一下再跳播放器。
            // 回收站里的文件是完整搬到暂存区的，可直接播放，不必先恢复。
            flashClick { startPlayback(navController, entryItem(entry)) }
        } else {
            // 文件夹没法直接播放：单击进入选择态，用底部操作条恢复/删除。
            selection.enter(entry.id.toString())
        }
    }

    fun onEntryLongClick(entry: TrashEntry) {
        // 长按进入选择态，由底部四格操作栏（播放/详情/恢复/彻底删除）接管。
        // 已在多选态时长按只切换勾选，与全站其它列表一致。
        selection.enter(entry.id.toString())
    }

    /**
     * 页面上所有「写操作」协程统一从这里起。
     *
     * 数据层已经把异常收敛成 Result，但页面协程本身没有异常兜底 —— 只要漏出一个，
     * 用户看到的就是闪退而不是提示（「恢复完文件就闪退」就是这么发生的）。
     * 取消信号必须原样抛出：那是页面退出时的正常流程，不该被当成失败提示。
     */
    fun launchUi(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                message = t.message?.takeIf { it.isNotBlank() } ?: "操作失败，请重试"
            }
        }
    }

    fun restoreEntries(targets: List<TrashEntry>) {
        if (targets.isEmpty()) return
        launchUi {
            var ok = 0
            val failed = mutableListOf<String>()
            try {
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
            } finally {
                // 出错也要退出选择态：列表已经变了，底部栏再挂着旧勾选只会让人误操作
                selection.exit()
            }
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
                    // 回收站只处理本机文件：网络共享上的视频没有本地路径，删除走的是
                    // 服务器自己那一套，不会进这里。之前写「本地 / 群晖」是把注释里的
                    // 兼容说明误写成了界面文案。
                    entries.isEmpty() -> "删除的本地视频会先放在这里"
                    // 占用一眼可见：回收站是"暂存"，用户需要知道它到底压着多少空间
                    else -> "${entries.size} 项 · 占用 ${
                        Formats.formatSize(entries.sumOf { it.sizeBytes })
                    }"
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
                            IconButton(onClick = {
                                sortDraft = MediaSortDraft(gridMode, sortKey.name, ascending)
                                sortSheetOpen = true
                            }) {
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
                        // 回收站里的视频也在播放时留下进度，但不在历史页里，只能在这里清
                        SelectionAction(
                            // 不能沿用「删除历史」的 History 图标：本页「恢复」是同类回转箭头，
                            // 两个图标挨着看不出区别。用扫帚表达"清掉记录、不动文件"。
                            Icons.Rounded.DeleteSweep,
                            "清除播放进度",
                            enabled = hasProgress,
                        ) {
                            val videos = selectedEntries.filter { !it.isFolder }
                            val keys = videos.map { "trash:${it.id}" }
                            // 正在播的回收站视频仍在队列里，不挡住轮询会立刻写回历史
                            com.yaoahos.mypureplayer.playback.Playback.suppressProgressRewrite(keys)
                            scope.launch {
                                Graph.history.removeAll(keys)
                                // 恢复时会按原路径把暂存进度写回历史，这里要一起清掉
                                videos.forEach { Graph.progress.drop(it.originalPath) }
                            }
                            Notice.success("已清除 ${videos.size} 个视频的播放进度")
                            selection.exit()
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
                                subtitle = cardMeta(entry),
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
                                metaOverride = cardMeta(entry),
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
                                    subtitle = cardMeta(entry),
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
                                    metaOverride = cardMeta(entry),
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
            onDismiss = {
                // 取消回滚到打开面板时的快照；确定时 onApply 已先清空 sortDraft
                sortDraft?.let { draft ->
                    gridMode = draft.grid
                    sortKey = runCatching { SortKey.valueOf(draft.key) }.getOrDefault(SortKey.DATE)
                    ascending = draft.ascending
                }
                sortDraft = null
                sortSheetOpen = false
            },
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
                sortDraft = null
                sortSheetOpen = false
                scope.launch { Graph.settings.setTrashLayout(grid, key.name, asc) }
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
                launchUi {
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
                launchUi {
                    val left = Graph.trash.clear()
                    // 有删不掉的要如实说：说「已清空」而这些条目下次启动又冒出来，
                    // 会让用户以为清空功能坏了
                    message = if (left == 0) "回收站已清空" else "已清空，$left 项因文件被占用没能删除"
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
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.alertDialogGlassSurface(),
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
        confirmButton = { GlassDialogButton(onClick = onDismiss) { Text("关闭") } },
    )
}
