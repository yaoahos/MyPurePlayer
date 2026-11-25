package com.yaoahos.mypureplayer.ui.play

import com.yaoahos.mypureplayer.ui.components.GlassDialogButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.SortKey
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.ConfirmDialog
import com.yaoahos.mypureplayer.ui.components.EmptyState
import com.yaoahos.mypureplayer.ui.components.FabInsets
import com.yaoahos.mypureplayer.ui.components.GlassFab
import com.yaoahos.mypureplayer.ui.components.GlassEdge
import com.yaoahos.mypureplayer.ui.components.GlassChoiceDialog
import com.yaoahos.mypureplayer.ui.components.GlassChoiceRow
import com.yaoahos.mypureplayer.ui.components.ListSelection
import java.io.File
import com.yaoahos.mypureplayer.ui.components.MediaInfoDialog
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.launchUi
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.SearchField
import com.yaoahos.mypureplayer.ui.components.SelectionAction
import com.yaoahos.mypureplayer.ui.components.SelectionActionBar
import com.yaoahos.mypureplayer.ui.components.SortLayoutSheet
import com.yaoahos.mypureplayer.ui.components.VideoGridCard
import com.yaoahos.mypureplayer.ui.components.VideoThumb
import com.yaoahos.mypureplayer.ui.components.adaptiveMediaGridMinSize
import com.yaoahos.mypureplayer.ui.components.appGlass
import com.yaoahos.mypureplayer.ui.components.appGlassBar
import com.yaoahos.mypureplayer.ui.components.appGlassSurface
import com.yaoahos.mypureplayer.ui.components.label
import com.yaoahos.mypureplayer.ui.components.glassPressOverlay
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.glassPressable
import com.yaoahos.mypureplayer.ui.components.mediaCardOutline
import com.yaoahos.mypureplayer.ui.components.rememberMediaDuration
import com.yaoahos.mypureplayer.ui.components.rememberResumePosition
import com.yaoahos.mypureplayer.ui.components.selectionCardOutline
import kotlinx.coroutines.launch

/**
 * 播放列表内部：既能管理自建列表，也能管理置顶的「正在播放」（[NOW_PLAYING_ID]）。
 *
 * - 点视频 = 用本列表作为队列起播；
 * - 长按 = 选择态，可播放 / 详情 / 移除（不删文件）；
 * - 顶栏提供搜索 / 清空 / 排序，重命名与删除已移到主页长按菜单；
 * - 右下角 + = 添加视频（本机文件夹 / 网络共享，均只取直属视频）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(navController: NavHostController, playlistId: String) {
    val scope = rememberCoroutineScope()
    val isNowPlaying = playlistId == NOW_PLAYING_ID
    val playlists by Graph.playlists.playlists.collectAsStateWithLifecycle()
    val queue by Playback.queue.collectAsStateWithLifecycle()
    // 当前播放项与实时进度：列表里正在播放的那一行要显示「位置 / 时长」和一条进度线，
    // 否则用户看不出这一项播到哪了。
    val playerState by Playback.state.collectAsStateWithLifecycle()
    // 每条视频的观看进度来自历史续播位置（本地卡片同款）；收集它才能在播完一条后刷新本页。
    val history by Graph.history.history.collectAsStateWithLifecycle()
    val videos by Graph.local.videos.collectAsStateWithLifecycle()
    val servers by Graph.servers.servers.collectAsStateWithLifecycle()
    val pl = playlists.firstOrNull { it.id == playlistId }

    val title = if (isNowPlaying) "正在播放" else pl?.name ?: "播放列表"
    val allMembers: List<VideoItem> = if (isNowPlaying) queue else pl?.videos.orEmpty()
    val gone = !isNowPlaying && pl == null

    // 自建播放列表里的本地条目也可能是被系统相册/文件管理器删掉的死记录。
    // 每次切回页面按真实文件对账一次，避免点进去才看到"文件不存在"。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launchUi {
            Graph.playlists.load()
            Graph.local.scan(silent = true)
            val current = Graph.playlists.playlists.value.firstOrNull { it.id == playlistId }
            // 逐个 File.exists() 是系统调用：列表几百条时不能在主线程做
            val stale = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                current?.videos?.filter { item ->
                    item.localPath?.let { path -> path.isNotBlank() && !File(path).exists() } == true
                }.orEmpty()
            }
            if (stale.isNotEmpty()) Graph.playlists.dropGone(stale)
        }
    }

    // 「最近播放」项：当前正在播放的最优先；否则在自建列表里取历史中最近播放且属于本列表的一条。
    // 用来给那一行显示选中态（蓝竖条 + 蓝序号 + 淡蓝底），让用户一眼看出上次看到哪。
    val memberIds = remember(allMembers) { allMembers.map { it.id }.toSet() }
    val recentId: String? = playerState.item?.id
        ?: if (isNowPlaying) allMembers.firstOrNull()?.id
        else history.firstOrNull { it.videoId in memberIds }?.videoId

    val selection = remember { ListSelection() }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var infoItem by remember { mutableStateOf<VideoItem?>(null) }
    var pendingRemove by remember { mutableStateOf<Set<String>?>(null) }
    var pendingClear by remember { mutableStateOf(false) }
    // 待确认的「删除历史」目标：只清观看记录，不移出列表
    var pendingHistoryDelete by remember { mutableStateOf<List<VideoItem>?>(null) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var sortKey by remember { mutableStateOf(SortKey.DATE) }
    var ascending by remember { mutableStateOf(true) }
    var gridMode by remember { mutableStateOf(false) }
    var previewSort by remember { mutableStateOf<Pair<SortKey, Boolean>?>(null) }
    var previewGrid by remember { mutableStateOf<Boolean?>(null) }
    var addSheetOpen by remember { mutableStateOf(false) }
    var localFoldersOpen by remember { mutableStateOf(false) }
    var serverPickerOpen by remember { mutableStateOf(false) }

    val members = remember(allMembers, query) {
        val q = query.trim()
        if (q.isEmpty()) allMembers
        else allMembers.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.fileName.contains(q, ignoreCase = true) ||
                it.folderName.contains(q, ignoreCase = true) ||
                it.uri.contains(q, ignoreCase = true)
        }
    }
    val shownMembers = previewSort?.let { (k, asc) -> sortedVideos(members, k, asc) } ?: members
    val selectedItems = remember(shownMembers, selection.ids) {
        shownMembers.filter { selection.contains(it.id) }
    }
    val soloItem = selectedItems.singleOrNull()
    val historyKeys = remember(history) {
        history.mapTo(HashSet()) { it.videoId to it.source }
    }
    val hasHistory = remember(selectedItems, historyKeys) {
        selectedItems.any { (it.id to it.source) in historyKeys }
    }

    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
    }
    BackHandler(enabled = !searchOpen && selection.active) { selection.exit() }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxSize().glassPageBackground(),
        topBar = {
            PageTopBar(
                title = title,
                subtitle = if (selection.active) "轻点勾选或取消" else "共 ${allMembers.size} 个视频",
                onBack = {
                    when {
                        searchOpen -> { searchOpen = false; query = "" }
                        selection.active -> selection.exit()
                        else -> navController.popBackStack()
                    }
                },
                backIcon = if (searchOpen || selection.active) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                backLabel = if (searchOpen) "关闭搜索" else if (selection.active) "退出选择" else "返回",
                actions = {
                    if (!searchOpen && !selection.active && !gone && allMembers.isNotEmpty()) {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Rounded.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { pendingClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "清空")
                        }
                        IconButton(onClick = { sortSheetOpen = true }) {
                            Icon(Icons.Rounded.Tune, contentDescription = "排序")
                        }
                    } else if (selection.active) {
                        IconButton(onClick = {
                            if (selectedItems.size == members.size) selection.clear()
                            else selection.replaceAll(members.map { it.id })
                        }) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = "全选")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selection.active) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .appGlassBar(GlassEdge.TOP)
                        .navigationBarsPadding(),
                ) {
                    SelectionActionBar(
                        actions = listOf(
                            SelectionAction(Icons.Rounded.PlayArrow, "播放", enabled = soloItem != null) {
                                soloItem?.let { item ->
                                    val start = allMembers.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                                    Playback.playChecked(allMembers, start) {
                                        navController.navigate(Routes.PLAYER)
                                    }
                                }
                                selection.exit()
                            },
                            SelectionAction(Icons.Rounded.Info, "详情", enabled = soloItem != null) {
                                soloItem?.let { infoItem = it }
                            },
                            SelectionAction(Icons.Rounded.DeleteOutline, "移除", enabled = selectedItems.isNotEmpty(), danger = true) {
                                // 只弹确认框，退出选择态放到确认之后；取消时保留选中项
                                pendingRemove = selectedItems.map { it.id }.toSet()
                            },
                            // 只清观看记录，不移出播放列表；选中项没有历史时置灰。
                            // 用 forceMore 收进「更多」，前三个主操作保持原样。
                            SelectionAction(
                                Icons.Rounded.History,
                                "删除历史",
                                enabled = selectedItems.isNotEmpty() && hasHistory,
                            ) {
                                val targets = selectedItems
                                if (targets.isNotEmpty()) pendingHistoryDelete = targets
                            },
                        ),
                        forceMore = true,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (searchOpen) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClose = { searchOpen = false; query = "" },
                        placeholder = "搜索列表内的视频",
                    )
                }
                when {
                    gone -> EmptyState(
                        icon = Icons.Rounded.Info,
                        title = "播放列表不存在",
                        message = "这个列表可能已被删除。",
                    )
                    // 空列表不给按钮：右下角已经有 + 了，同一个动作摆两处反而分散注意。
                    // 说明文字直接指向那个加号即可（早先这里还自己拼过一行 Button，
                    // 会被 EmptyState 内部的 fillMaxSize 挤到屏幕最底，已一并删掉）。
                    // 「正在播放」只由真实播放行为填充，所以它连文案都不同。
                    allMembers.isEmpty() -> EmptyState(
                        icon = Icons.Rounded.PlayArrow,
                        title = "列表还是空的",
                        message = if (isNowPlaying) {
                            "打开任意视频会自动把同目录的视频加入这里，不能手动添加。"
                        } else {
                            "点右下角 + 把视频或整个文件夹加进来。"
                        },
                    )
                    members.isEmpty() -> EmptyState(
                        icon = Icons.Rounded.Search,
                        title = "没有匹配的视频",
                        message = "换个关键词试试。",
                    )
                    else -> {
                        val useGrid = previewGrid ?: gridMode
                        // 播放这一项时的起播位置：成员列表以 allMembers 为准，
                        // 搜索/排序只影响展示，不应改变队列的实际顺序。
                        fun playAt(item: VideoItem) {
                            val start = allMembers.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                            Playback.playChecked(allMembers, start) {
                                navController.navigate(Routes.PLAYER)
                            }
                        }
                        // 点卡片先闪一次再播放/跳页：立即跳页会让卡片当场销毁，
                        // 按压淡片一帧都没画出来（用户看到的就是"点了没反应"）。
                        fun flashPlayAt(item: VideoItem) = flashClick { playAt(item) }
                        // 「正在播放」详情页顶部 = 正在播放的内容（旧的播放列表就是这样）：
                        // 当前视频缩略图 + 播放状态 + 实时进度，点一下回到播放器。
                        // 冷启动后播放器状态还没恢复，用队列第一项兜底，保证顶部始终有内容。
                        val heroItem: VideoItem? = playerState.item ?: allMembers.firstOrNull()
                        val hero: @Composable () -> Unit = {
                            if (isNowPlaying && heroItem != null) {
                                NowPlayingHero(
                                    current = heroItem,
                                    playing = playerState.playing,
                                    // 冷启动时播放器状态还没恢复，用视频自身时长兜底，保证参照图里的
                                    // 「位置 / 时长」一行始终有内容；只有确实是当前项才用实时时长。
                                    durationMs = if (playerState.item?.id == heroItem.id && playerState.durationMs > 0L) {
                                        playerState.durationMs
                                    } else {
                                        rememberMediaDuration(heroItem)
                                    },
                                    count = allMembers.size,
                                    onClick = { flashClick { navController.navigate(Routes.PLAYER) } },
                                )
                            }
                        }
                        val playAll: @Composable () -> Unit = {
                            // 与全站同一套玻璃语言：不用实心色块按钮，改用一张 tinted 玻璃行
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = appGlass().tintSoft,
                                        shape = RoundedCornerShape(PageMetrics.CardRadius),
                                    )
                                    // 只留一圈细描边：这条按钮又宽又矮，appGlassSurface 的
                                    // 高光贴图拉伸后会在中部留下一道浅色亮带（用户标注的白条）。
                                    .border(
                                        width = 1.dp,
                                        color = appGlass().borderTop,
                                        shape = RoundedCornerShape(PageMetrics.CardRadius),
                                    )
                                    .glassPressable(role = Role.Button, radius = PageMetrics.CardRadius) {
                                        flashClick {
                                            Playback.playChecked(members, 0) {
                                                navController.navigate(Routes.PLAYER)
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "播放全部（${members.size}）",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }
                        if (useGrid) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = adaptiveMediaGridMinSize()),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = PageMetrics.HPad,
                                    end = PageMetrics.HPad,
                                    top = 8.dp,
                                    bottom = 96.dp,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (isNowPlaying && heroItem != null) {
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "now-hero") { hero() }
                                }
                                item(span = { GridItemSpan(maxLineSpan) }, key = "play-all") { playAll() }
                                items(shownMembers, key = { it.id }) { item ->
                                    VideoGridCard(
                                        item = item,
                                        onClick = {
                                            if (selection.active) selection.toggle(item.id) else playAt(item)
                                        },
                                        onLongClick = { selection.enter(item.id) },
                                        selected = selection.contains(item.id),
                                        selecting = selection.active,
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = PageMetrics.HPad,
                                    end = PageMetrics.HPad,
                                    top = 8.dp,
                                    bottom = 96.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (isNowPlaying && heroItem != null) {
                                    item(key = "now-hero") { hero() }
                                }
                                item(key = "play-all") { playAll() }
                                itemsIndexed(shownMembers, key = { _, item -> item.id }) { index, item ->
                                    val isSelected = selection.contains(item.id)
                                    // 「最近播放」呈选中态：蓝竖条 + 蓝序号 + 淡蓝底。
                                    // 与本地卡片不同，这里逐条显示观看进度：当前项用实时位置，其余读历史续播位置。
                                    val isRecentItem = recentId == item.id
                                    val isCurrent = playerState.item?.id == item.id
                                    val mediaTotal = rememberMediaDuration(item)
                                    val total = if (isCurrent && playerState.durationMs > 0L) {
                                        playerState.durationMs
                                    } else {
                                        mediaTotal
                                    }
                                    val resumeMs = if (isCurrent) {
                                        // 只让当前这一行订阅 200ms 进度流，避免整页随进度重组。
                                        Playback.positionMs.collectAsStateWithLifecycle().value
                                    } else {
                                        // 订阅历史表：删除观看记录后这一行的进度条立刻消失
                                        rememberResumePosition(item)
                                    }
                                    val progressRatio = if (total > 0L && resumeMs > 0L) {
                                        (resumeMs.toFloat() / total).coerceIn(0f, 1f)
                                    } else 0f
                                    val interaction = remember { MutableInteractionSource() }
                                    val pressed by interaction.collectPressedWithTap()
                                    val shape = RoundedCornerShape(PageMetrics.CardRadius)
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
                                                } else if (isRecentItem) {
                                                    // 「最近播放」整卡染一层淡色，按压再叠一层统一淡片
                                                    Modifier
                                                        .appGlassSurface(shape, tinted = true)
                                                        .glassPressOverlay(pressed, shape, foreground = true)
                                                } else Modifier.mediaCardOutline(pressed = pressed)
                                            )
                                            .combinedClickable(
                                                interactionSource = interaction,
                                                indication = null,
                                                role = Role.Button,
                                                onClickLabel = if (selection.active) "选中/取消选择" else "播放",
                                                onLongClickLabel = "选择",
                                                onLongClick = { selection.enter(item.id) },
                                            ) {
                                                if (selection.active) selection.toggle(item.id) else flashPlayAt(item)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // 左侧蓝色竖条：仅最近播放项显示，用一个零宽占位对齐其他行
                                        Box(Modifier.width(3.dp).height(40.dp)) {
                                            if (isRecentItem && !selection.active) {
                                                Box(
                                                    Modifier
                                                        .width(3.dp)
                                                        .height(40.dp)
                                                        .clip(RoundedCornerShape(2.dp))
                                                        .background(MaterialTheme.colorScheme.primary),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.size(8.dp))
                                        // 序号：最近播放项着主色，其余为次要色
                                        Text(
                                            "${index + 1}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (isRecentItem && !selection.active) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.width(24.dp),
                                        )
                                        Box(Modifier.size(width = 104.dp, height = 60.dp)) {
                                            VideoThumb(item, Modifier.fillMaxSize(), cornerRadius = PageMetrics.ThumbRadius)
                                        }
                                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                            Text(
                                                item.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (total > 0L) {
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    if (progressRatio > 0f) {
                                                        "${Formats.formatDuration(resumeMs)} / ${Formats.formatDuration(total)}"
                                                    } else {
                                                        Formats.formatDuration(total)
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isRecentItem) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                    maxLines = 1,
                                                )
                                            }
                                            // 只有真有观看进度的视频才画进度条，不只是当前播放项。
                                            // 看过 0 秒的视频（没点开过）不画：否则每条都挂一根空的灰色轨道。
                                            if (progressRatio > 0f) {
                                                Spacer(Modifier.height(4.dp))
                                                LinearProgressIndicator(
                                                    progress = { progressRatio },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(3.dp)
                                                        .clip(RoundedCornerShape(2.dp)),
                                                    color = if (isRecentItem) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                    },
                                                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!isNowPlaying) {
                GlassFab(
                    onClick = { addSheetOpen = true },
                    contentDescription = "添加视频",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = FabInsets.margin, bottom = FabInsets.margin),
                )
            }
        }
    }

    // 排序面板：预览实时生效，确定后整体写回成员顺序
    if (sortSheetOpen) {
        SortLayoutSheet(
            onDismiss = { sortSheetOpen = false; previewSort = null; previewGrid = null },
            title = "排列与排序",
            showLayout = true,
            initialGrid = gridMode,
            initialSort = sortKey,
            initialAscending = ascending,
            entries = listOf(SortKey.NAME, SortKey.DATE, SortKey.DURATION, SortKey.SIZE),
            onPreview = { grid, key, asc -> previewSort = key to asc; previewGrid = grid },
            onApply = { grid, key, asc ->
                val ordered = sortedVideos(allMembers, key, asc)
                sortKey = key
                ascending = asc
                gridMode = grid
                previewSort = null
                previewGrid = null
                sortSheetOpen = false
                if (isNowPlaying) {
                    Playback.reorderQueue(ordered)
                } else {
                    scope.launchUi { Graph.playlists.reorder(playlistId, ordered) }
                }
                Notice.success("已按「${key.label}」重新排列")
            },
        )
    }

    infoItem?.let { MediaInfoDialog(item = it, onDismiss = { infoItem = null }) }

    pendingHistoryDelete?.let { targets ->
        ConfirmDialog(
            title = "删除观看记录",
            message = "已选中的 ${targets.size} 个视频的观看记录将被删除，视频仍保留在列表里。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                pendingHistoryDelete = null
                scope.launchUi {
                    val n = Graph.history.removeAll(targets.map { it.id })
                    Notice.success(if (n > 0) "已删除 $n 条观看记录" else "没有可删除的观看记录")
                }
                selection.exit()
            },
            onDismiss = { pendingHistoryDelete = null },
        )
    }

    pendingRemove?.let { ids ->
        ConfirmDialog(
            title = if (isNowPlaying) "移出正在播放" else "从列表移除",
            message = if (isNowPlaying) {
                "已选中的 ${ids.size} 个视频将从正在播放移除，不会删除本地文件。"
            } else {
                "已选中的 ${ids.size} 个视频将从「$title」移除，不会删除本地文件。"
            },
            confirmText = "移除",
            destructive = true,
            onConfirm = {
                if (isNowPlaying) {
                    ids.mapNotNull { id -> queue.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                        .sortedDescending()
                        .forEach { Playback.removeAt(it) }
                    pendingRemove = null
                    selection.exit()
                    Notice.success("已移出正在播放")
                } else {
                    scope.launchUi {
                        Graph.playlists.removeVideos(playlistId, ids)
                        pendingRemove = null
                        selection.exit()
                        Notice.success("已移除 ${ids.size} 个视频")
                    }
                }
            },
            onDismiss = { pendingRemove = null },
        )
    }

    if (pendingClear) {
        ConfirmDialog(
            title = if (isNowPlaying) "清空正在播放" else "清空列表",
            message = "确定清空「$title」中的 ${allMembers.size} 个视频吗？不会删除本地文件。",
            confirmText = "清空",
            destructive = true,
            onConfirm = {
                if (isNowPlaying) Playback.clearQueue()
                else scope.launchUi { Graph.playlists.reorder(playlistId, emptyList()) }
                pendingClear = false
                selection.exit()
                Notice.success("已清空「$title」")
            },
            onDismiss = { pendingClear = false },
        )
    }

    if (addSheetOpen) {
        AddVideoSheet(
            videos = videos,
            servers = servers,
            onDismiss = { addSheetOpen = false },
            onLocal = { addSheetOpen = false; localFoldersOpen = true },
            onNetwork = { addSheetOpen = false; serverPickerOpen = true },
        )
    }

    if (localFoldersOpen) {
        val groups = remember(videos) { Graph.local.folders() }
        GlassChoiceDialog(title = "选择文件夹", onDismiss = { localFoldersOpen = false }) {
            Text("选择文件夹", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Spacer(Modifier.height(2.dp))
            LazyColumn(
                Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(groups, key = { it.key }) { group ->
                    GlassChoiceRow(
                        icon = Icons.Rounded.Folder,
                        title = group.name,
                        subtitle = "${group.videos.size} 个视频",
                    ) {
                        localFoldersOpen = false
                        navController.navigate(
                            Routes.picker(group.key, targetPlaylist = if (isNowPlaying) "" else playlistId)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                GlassDialogButton(onClick = { localFoldersOpen = false }) { Text("取消") }
            }
        }
    }

    if (serverPickerOpen) {
        GlassChoiceDialog(title = "选择共享位置", onDismiss = { serverPickerOpen = false }) {
            Text("选择共享位置", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Spacer(Modifier.height(2.dp))
            LazyColumn(
                Modifier.heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(servers, key = { it.id }) { server ->
                    GlassChoiceRow(
                        icon = Icons.Rounded.Cloud,
                        title = server.name,
                        subtitle = server.displayHost,
                    ) {
                        serverPickerOpen = false
                        navController.navigate(
                            Routes.smb(
                                server.id,
                                server.rootPath,
                                addMode = true,
                                targetPlaylist = if (isNowPlaying) "" else playlistId,
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                GlassDialogButton(onClick = { serverPickerOpen = false }) { Text("取消") }
            }
        }
    }
}

/**
 * 「正在播放」详情页顶部的当前播放内容：缩略图 + 播放状态 + 标题 + 实时进度，
 * 与旧的播放列表顶部展示一致。整张卡可点，点一下回到播放器。
 */
@Composable
private fun NowPlayingHero(
    current: VideoItem?,
    playing: Boolean,
    durationMs: Long,
    count: Int,
    onClick: () -> Unit,
) {
    if (current == null) return
    // 200ms 进度流只驱动这张卡片，不让它把整个列表（含所有卡片）一起重组。
    val positionMs by Playback.positionMs.collectAsStateWithLifecycle()
    val accent = com.yaoahos.mypureplayer.ui.components.appGlass().accent
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    // 按参照图布局：左侧缩略图 + 中间「状态 / 标题 / 位置·时长」+ 右侧「▶ 播放页」按钮，
    // 底部一条进度线。标题与时间允许两行（参照图就是折行的），整卡不再是点击区，
    // 进播放页统一走右侧那个明确的按钮。
    val statusLine = if (playing) "正在播放" else "已暂停"
    val timeLine = if (durationMs > 0L) {
        "${Formats.formatDuration(positionMs)} /\n${Formats.formatDuration(durationMs)}"
    } else {
        ""
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appGlassSurface(RoundedCornerShape(PageMetrics.CardRadius), strong = true)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 92.dp, height = 58.dp)) {
                VideoThumb(current, Modifier.fillMaxSize(), cornerRadius = PageMetrics.ThumbRadius)
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    statusLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    current.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (timeLine.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        timeLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            // 「▶ 播放页」：明确的进播放器入口，替代之前整卡点击
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(accent.copy(alpha = 0.16f))
                    .glassPressable(role = Role.Button, radius = 24.dp, onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "播放页",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // 没开始播（位置还是 0）时不画空轨道：与列表行、宫格卡片的判断保持一致。
        if (durationMs > 0L && positionMs > 0L) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accent,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
            )
        }
    }
}
