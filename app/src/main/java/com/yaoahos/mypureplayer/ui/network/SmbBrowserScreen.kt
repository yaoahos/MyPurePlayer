package com.yaoahos.mypureplayer.ui.network

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.AppSettings
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.media.Thumbnails
import com.yaoahos.mypureplayer.data.SmbClient
import com.yaoahos.mypureplayer.data.SmbErrors
import com.yaoahos.mypureplayer.data.SmbEntry
import com.yaoahos.mypureplayer.data.SmbServer
import com.yaoahos.mypureplayer.data.SortKey
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.DurationBadge
import com.yaoahos.mypureplayer.ui.components.EmptyState
import com.yaoahos.mypureplayer.ui.components.FolderInfoDialog
import com.yaoahos.mypureplayer.ui.components.GlassEdge
import com.yaoahos.mypureplayer.ui.components.VideoThumb
import com.yaoahos.mypureplayer.ui.components.mediaCardOutline
import com.yaoahos.mypureplayer.ui.components.appGlassBar
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.MediaFolderGridCard
import com.yaoahos.mypureplayer.ui.components.MediaSectionLabel
import com.yaoahos.mypureplayer.ui.components.ListRowContainer
import com.yaoahos.mypureplayer.ui.components.ListSelection
import com.yaoahos.mypureplayer.ui.components.MediaFolderRow
import com.yaoahos.mypureplayer.ui.components.MediaInfoDialog
import com.yaoahos.mypureplayer.ui.components.MediaSortDraft
import com.yaoahos.mypureplayer.ui.components.mediaDuration
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.PlaylistTargetSheet
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.PullRefreshBox
import com.yaoahos.mypureplayer.ui.components.SearchField
import com.yaoahos.mypureplayer.ui.components.SelectionAction
import com.yaoahos.mypureplayer.ui.components.SelectionActionBar
import com.yaoahos.mypureplayer.ui.components.SortLayoutSheet
import com.yaoahos.mypureplayer.ui.components.adaptiveMediaGridMinSize
import com.yaoahos.mypureplayer.ui.components.listRowMinSize
import com.yaoahos.mypureplayer.ui.components.VideoGridCard
import com.yaoahos.mypureplayer.ui.components.VideoListRow
import kotlinx.coroutines.launch

/**
 * SMB 目录浏览。
 *
 * 风格与本地页保持一致：搜索 / 排序 / 宫格列表、真实缩略图、长按操作面板。
 * 网络资源只读 —— 面板里只有播放、加入播放列表、信息，没有重命名 / 复制 / 删除 / 保险箱。
 * 自身及所有子目录都没有视频的文件夹直接隐藏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbBrowserScreen(
    navController: NavHostController,
    serverId: Long,
    initialPath: String,
    addMode: Boolean = false,
    targetPlaylist: String = "",
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 待用户选择目标列表的视频；非空时弹出 PlaylistTargetSheet。
    // 目标不再从路由参数预设，统一由用户自己挑（"不默认放进正在播放"）。
    var pendingAdd by remember { mutableStateOf<List<VideoItem>?>(null) }
    suspend fun commitAdd(targetId: String, name: String, items: List<VideoItem>) {
        if (items.isEmpty()) return
        if (targetId.isEmpty()) {
            Playback.addToQueue(items)
            Notice.success("已加入「正在播放」${items.size} 个视频")
        } else {
            val n = Graph.playlists.addVideos(targetId, items)
            if (n > 0) Notice.success("已加入「$name」$n 个视频")
            else Notice.error("「$name」不存在或没有新增视频")
        }
    }
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var server by remember(serverId) { mutableStateOf(Graph.servers.byId(serverId)) }
    // 进出文件夹是新路由实例：用内存缓存同步预填列表与加载态，
    // 第一帧即有内容，不会闪「加载中/刷新图标」（cachedList 是纯内存查找，组合期调用安全）
    var entries by remember(server?.id, initialPath) {
        val srv = Graph.servers.byId(serverId)
        val p = initialPath.ifBlank { srv?.rootPath ?: "smb:///" }
        val cached = srv?.let { SmbClient.cachedList(it, p) }
        mutableStateOf(cached?.let { SmbClient.videoOnly(it) } ?: emptyList())
    }
    var loading by remember(server?.id, initialPath) {
        val srv = Graph.servers.byId(serverId)
        val p = initialPath.ifBlank { srv?.rootPath ?: "smb:///" }
        mutableStateOf(srv == null || SmbClient.cachedList(srv, p) == null)
    }
    // 只有用户主动下拉刷新才让顶部弧线转圈；进文件夹/首开不闪
    var refreshing by remember { mutableStateOf(false) }
    // 本次 reload 是不是下拉手势发起的：它决定「顶部转圈」还是「内容区转圈」，
    // 两者只能有一个在转（否则同屏两个加载指示，看起来像卡住又像在加载）
    var pullReload by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var gridMode by rememberSaveable { mutableStateOf(settings.smbBrowserGrid) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(settings.smbBrowserSort) }
    var ascending by rememberSaveable { mutableStateOf(settings.smbBrowserAscending) }
    LaunchedEffect(settings.smbBrowserSort, settings.smbBrowserAscending, settings.smbBrowserGrid) {
        sortName = settings.smbBrowserSort
        ascending = settings.smbBrowserAscending
        gridMode = settings.smbBrowserGrid
    }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var sortDraft by remember { mutableStateOf<MediaSortDraft?>(null) }
    var infoItem by remember { mutableStateOf<VideoItem?>(null) }
    var infoFolder by remember { mutableStateOf<Pair<SmbEntry, List<VideoItem>>?>(null) }
    // 长按只进入选择态，动作集中在顶栏 + 底部操作栏
    val selection = remember { ListSelection() }

    // 添加模式一进来就是「空选择」状态：复选框常驻、可以全选，
    // 与本地文件夹选择页保持一致，不再要求用户先点一下才出现选择界面。
    LaunchedEffect(addMode) { if (addMode) selection.begin() }

    LaunchedEffect(serverId) {
        if (server == null) {
            Graph.servers.load()
            server = Graph.servers.byId(serverId)
        }
    }

    val activeServer = server
    val path = initialPath.ifBlank { activeServer?.rootPath ?: "smb:///" }

    LaunchedEffect(activeServer?.id, path, reloadKey) {
        val srv = activeServer ?: return@LaunchedEffect
        error = null
        // 只有下拉刷新（reloadKey 递增且来自下拉手势）才驱动顶部弧线；
        // 进文件夹/首开走缓存静默刷新，不再闪图标
        val isPull = pullReload
        pullReload = false
        if (isPull) refreshing = true
        // 先用缓存把列表画出来：进出文件夹、从播放页返回都是瞬开，
        // 后台再刷新（下拉刷新会带 force 真正去打一次 NAS）。
        val cached = SmbClient.cachedList(srv, path)
        if (cached != null) {
            entries = SmbClient.videoOnly(cached)
            loading = false
        } else {
            loading = true
        }
        val listed = runCatching { SmbClient.list(srv, path, force = isPull) }
        listed.onSuccess { entries = SmbClient.videoOnly(it) }
            .onFailure {
                // 有缓存时刷新失败不要把已有内容替换成错误页，用户至少还能继续浏览
                if (cached == null) error = SmbErrors.describe(it)
            }
        loading = false
        refreshing = false
    }

    // 进入目录即恢复封面抽帧（播放网络视频时会临时暂停，把通道让给首帧）
    LaunchedEffect(Unit) { Thumbnails.resumeRemoteThumbs() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        // 浏览期间保持 Wi-Fi 低延迟：否则 2.4G 省电会把每个 SMB 往返拖到 20ms 以上，
        // 列目录、抽帧、播放首帧全跟着慢（详见 WifiPerf 的说明）。
        com.yaoahos.mypureplayer.data.WifiPerf.acquire(context)
        onDispose {
            Thumbnails.resumeRemoteThumbs()
            com.yaoahos.mypureplayer.data.WifiPerf.release()
        }
    }

    val sortKey = remember(sortName) { runCatching { SortKey.valueOf(sortName) }.getOrDefault(SortKey.NAME) }
    // 网络视频的时长靠抽帧补出来，补一条重排一次；其他排序方式不受影响
    val durationVersion by Thumbnails.durationVersion.collectAsStateWithLifecycle()
    val durationTick = if (sortKey == SortKey.DURATION) durationVersion else 0
    val videos = remember(entries, path, activeServer?.id) {
        activeServer?.let { SmbClient.toVideoItems(it, entries, path) } ?: emptyList()
    }
    val filteredVideos = remember(videos, query, sortKey, ascending, durationTick) {
        val base = videos.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        val comparator: Comparator<VideoItem> = when (sortKey) {
            SortKey.NAME -> compareBy { it.title.lowercase() }
            SortKey.DATE -> compareBy { it.dateModified }
            SortKey.DURATION -> compareBy { mediaDuration(it) }
            SortKey.SIZE -> compareBy { it.sizeBytes }
        }
        base.sortedWith(if (ascending) comparator else comparator.reversed())
    }
    val folders = remember(entries) { entries.filter { it.isDirectory } }
    val filteredFolders = remember(folders, query, sortKey, ascending) {
        val base = folders.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        val cmp: Comparator<SmbEntry> = when (sortKey) {
            SortKey.NAME -> compareBy { it.name.lowercase() }
            SortKey.DATE -> compareBy { it.lastModified }
            SortKey.SIZE -> compareBy { it.sizeBytes }
            // 文件夹没有时长：按名称兜底，至少不会是服务器返回的乱序
            SortKey.DURATION -> compareBy { it.name.lowercase() }
        }
        base.sortedWith(if (ascending) cmp else cmp.reversed())
    }
    // 文件夹与视频始终混排展示，不再区分浏览方式
    val visibleVideos = filteredVideos

    if (activeServer == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val selectedVideos = remember(visibleVideos, selection.ids) {
        visibleVideos.filter { selection.contains("v:${it.id}") }
    }
    val selectedFolders = remember(filteredFolders, selection.ids) {
        filteredFolders.filter { selection.contains("d:${it.url}") }
    }
    val selectedCount = selectedVideos.size + selectedFolders.size
    val soloVideo = selectedVideos.singleOrNull()
    val soloFolder = selectedFolders.singleOrNull()

    // 添加模式下选择态是常驻的：返回键应当直接退出页面，而不是"退选择"
    BackHandler(enabled = selection.active && !addMode) { selection.exit() }

    val title = if (path.trimEnd('/') == activeServer.rootPath.trimEnd('/')) {
        activeServer.name
    } else {
        Formats.baseName(path).ifBlank { activeServer.name }
    }
    PageTopBarHost(
        title = title,
        subtitle = if (addMode) {
            // 根目录常常只有文件夹没有视频，写"0 个视频"会像出错了。
            // 带"可选"二字，避免用户以为全部数量都要加入播放列表。
            "${visibleVideos.size} 个视频可选"
        } else path.removePrefix("smb://"),
        onBack = { navController.popBackStack() },
        searchOpen = searchOpen,
        query = query,
        onQueryChange = { query = it },
        onCloseSearch = { searchOpen = false; query = "" },
        onToggleSearch = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
        onOpenSort = {
            sortDraft = MediaSortDraft(gridMode, sortName, ascending)
            sortSheetOpen = true
        },
        // 添加模式的选择态由页面自己控制，不走"多选态顶栏"
        addMode = addMode,
        selectionActive = selection.active && !addMode,
        selectionCount = selectedCount,
        onExitSelection = { selection.exit() },
        onToggleSelectAll = {
            // 添加模式也把文件夹纳入全选：用户要能整文件夹加入播放列表
            val allKeys = filteredFolders.map { "d:${it.url}" } + visibleVideos.map { "v:${it.id}" }
            val allSelected = allKeys.isNotEmpty() && selectedCount == allKeys.size
            if (allSelected) selection.clear() else selection.replaceAll(allKeys)
        },
        allSelected = {
            val allKeys = filteredFolders.map { "d:${it.url}" } + visibleVideos.map { "v:${it.id}" }
            allKeys.isNotEmpty() && selectedCount == allKeys.size
        },
        bottomBar = {
            if (addMode) {
                // 与本地文件夹选择页完全同构：玻璃底栏 + 「已选 N 个」+ 加入列表
                Box(
                    Modifier
                        .fillMaxWidth()
                        .appGlassBar(GlassEdge.TOP)
                        .navigationBarsPadding(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已选 ${selectedCount} 项",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.Button(
                            onClick = {
                                // 选中的视频 + 选中文件夹的直属视频（不递归），按 id 去重。
                                // 这里先把条目取出来，目标列表由随后弹出的选择器决定。
                                scope.launch {
                                    val fromFolders = selectedFolders.flatMap { folder ->
                                        runCatching { SmbClient.list(activeServer, folder.url) }
                                            .getOrNull()?.let { SmbClient.videoOnly(it) }
                                            ?.let { SmbClient.toVideoItems(activeServer, it, folder.url) }.orEmpty()
                                    }
                                    val seen = HashSet<String>()
                                    val items = (selectedVideos + fromFolders).filter { seen.add(it.id) }
                                    if (items.isEmpty()) Notice.error("所选文件夹里没有视频")
                                    else pendingAdd = items
                                }
                            },
                            enabled = selectedCount > 0,
                        ) {
                            Text("加入列表")
                        }
                    }
                }
            } else if (selection.active) {
                val canAct = selectedCount > 0
                SelectionActionBar(
                    actions = listOf(
                        SelectionAction(
                            icon = if (soloFolder != null) Icons.Rounded.Folder else Icons.Rounded.PlayArrow,
                            label = if (soloFolder != null) "打开" else "播放",
                            enabled = canAct && selectedCount == 1,
                        ) {
                            when {
                                soloVideo != null -> playFromFolder(navController, activeServer, entries, path, soloVideo)
                                soloFolder != null -> navController.navigate(
                                    Routes.smb(activeServer.id, soloFolder.url, addMode, targetPlaylist)
                                )
                            }
                            selection.exit()
                        },
                        SelectionAction(Icons.Rounded.Info, "详情", enabled = canAct && selectedCount == 1) {
                            if (soloVideo != null) infoItem = soloVideo
                            if (soloFolder != null) {
                                scope.launch {
                                    val list = runCatching { SmbClient.list(activeServer, soloFolder.url) }
                                        .getOrNull()?.let { SmbClient.videoOnly(it) }
                                        ?.let { SmbClient.toVideoItems(activeServer, it, soloFolder.url) }.orEmpty()
                                    infoFolder = soloFolder to list
                                }
                            }
                            selection.exit()
                        },
                        SelectionAction(Icons.AutoMirrored.Rounded.FormatListBulleted, "加入列表", enabled = canAct) {
                            scope.launch {
                                // 多选：选中的视频 + 每个选中文件夹的直属视频（不递归子目录），按 id 去重
                                val fromFolders = selectedFolders.flatMap { folder ->
                                    runCatching { SmbClient.list(activeServer, folder.url) }
                                        .getOrNull()?.let { SmbClient.videoOnly(it) }
                                        ?.let { SmbClient.toVideoItems(activeServer, it, folder.url) }.orEmpty()
                                }
                                val seen = HashSet<String>()
                                val list = (selectedVideos + fromFolders).filter { seen.add(it.id) }
                                if (list.isNotEmpty()) pendingAdd = list else Notice.error("这里没有视频")
                            }
                            selection.exit()
                        },
                    ),
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
    ) { padding ->
        PullRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { pullReload = true; reloadKey++ },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (searchOpen) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClose = { searchOpen = false; query = "" },
                        placeholder = "搜索视频或文件夹",
                    )
                }
                when {
                    error != null -> EmptyState(
                        icon = Icons.Rounded.Folder,
                        title = "无法打开这个位置",
                        message = error ?: "",
                        // 首次进目录失败时不能只有一行错误文字：给一个明确的行动出口，
                        // 而不是让用户去发现隐藏的下拉刷新手势
                        actionLabel = "重试",
                        onAction = { pullReload = false; reloadKey++ },
                    )

                    // 无缓存的首开/进目录：内容区给一个居中转圈。
                    // 下拉刷新的转圈在顶部弧线，这里用 refreshing 把它让开，
                    // 保证任何时刻屏幕上只有一个加载指示。
                    loading && !refreshing && visibleVideos.isEmpty() && filteredFolders.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }

                    visibleVideos.isEmpty() && filteredFolders.isEmpty() -> EmptyState(
                        icon = Icons.Rounded.Movie,
                        title = "这里没有视频",
                        message = "下拉可以重新加载；只显示视频文件与文件夹。",
                    )

                    // 加入播放列表模式：固定用列表形式（不受浏览页的宫格设置影响）。
                    // 文件夹左侧=进入目录、右侧复选框=勾选整个目录的直属视频；
                    // 视频整行=勾选/取消选择，右侧复选框同步显示状态。
                    addMode -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = PageMetrics.HPad,
                            end = PageMetrics.HPad,
                            top = 4.dp,
                            bottom = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (filteredFolders.isNotEmpty()) {
                            item { MediaSectionLabel("文件夹") }
                        }
                        items(filteredFolders, key = { "d${it.url}" }) { folder ->
                            AddModeFolderRow(
                                name = folder.name,
                                checked = selection.contains("d:${folder.url}"),
                                onEnterFolder = {
                                    navController.navigate(
                                        Routes.smb(activeServer.id, folder.url, addMode, targetPlaylist)
                                    )
                                },
                                onToggle = { selection.toggle("d:${folder.url}") },
                            )
                        }
                        if (visibleVideos.isNotEmpty()) {
                            item { MediaSectionLabel("视频") }
                        }
                        items(visibleVideos, key = { it.id }) { video ->
                            AddModeVideoRow(
                                item = video,
                                checked = selection.contains("v:${video.id}"),
                                onToggle = { selection.toggle("v:${video.id}") },
                            )
                        }
                    }

                    gridMode -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = adaptiveMediaGridMinSize()),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = PageMetrics.HPad, end = PageMetrics.HPad, top = 8.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (filteredFolders.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { MediaSectionLabel("文件夹") }
                        }
                        items(filteredFolders, key = { "d${it.url}" }) { folder ->
                            MediaFolderGridCard(
                                name = folder.name,
                                subtitle = "",
                                onClick = {
                                    // 添加模式下文件夹可勾选（整文件夹直属视频加入）；
                                    // 长按需进目录继续浏览。普通浏览则单击进目录、长按进选择。
                                    if (addMode) {
                                        selection.toggle("d:${folder.url}")
                                    } else if (selection.active) {
                                        selection.toggle("d:${folder.url}")
                                    } else {
                                        navController.navigate(Routes.smb(activeServer.id, folder.url, addMode, targetPlaylist))
                                    }
                                },
                                onLongClick = {
                                    if (addMode) {
                                        navController.navigate(Routes.smb(activeServer.id, folder.url, addMode, targetPlaylist))
                                    } else {
                                        selection.enter("d:${folder.url}")
                                    }
                                },
                                selected = selection.contains("d:${folder.url}"),
                                selecting = selection.active,
                            )
                        }
                        if (filteredFolders.isNotEmpty() && visibleVideos.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { MediaSectionLabel("视频") }
                        }
                        items(visibleVideos, key = { it.id }) { item ->
                            VideoGridCard(
                                item = item,
                                onClick = {
                                    if (selection.active) {
                                        selection.toggle("v:${item.id}")
                                    } else if (addMode) {
                                        // 添加模式下单击即勾选，明确入选后再点底部「加入列表」
                                        selection.enter("v:${item.id}")
                                    } else {
                                        playFromFolder(navController, activeServer, entries, path, item)
                                    }
                                },
                                onLongClick = { selection.enter("v:${item.id}") },
                                selected = selection.contains("v:${item.id}"),
                                selecting = selection.active,
                            )
                        }
                    }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = listRowMinSize()),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = PageMetrics.HPad, end = PageMetrics.HPad, top = 4.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (filteredFolders.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { MediaSectionLabel("文件夹") }
                        }
                        items(filteredFolders, key = { "d${it.url}" }) { folder ->
                            ListRowContainer {
                                MediaFolderRow(
                                    name = folder.name,
                                    subtitle = "",
                                    onClick = {
                                        if (addMode) {
                                            selection.toggle("d:${folder.url}")
                                        } else if (selection.active) {
                                            selection.toggle("d:${folder.url}")
                                        } else {
                                            navController.navigate(Routes.smb(activeServer.id, folder.url, addMode, targetPlaylist))
                                        }
                                    },
                                    onLongClick = {
                                        if (addMode) {
                                            navController.navigate(Routes.smb(activeServer.id, folder.url, addMode, targetPlaylist))
                                        } else {
                                            selection.enter("d:${folder.url}")
                                        }
                                    },
                                    selected = selection.contains("d:${folder.url}"),
                                    selecting = selection.active,
                                )
                            }
                        }
                        if (filteredFolders.isNotEmpty() && visibleVideos.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { MediaSectionLabel("视频") }
                        }
                        items(visibleVideos, key = { it.id }) { item ->
                            ListRowContainer {
                                VideoListRow(
                                    item = item,
                                    onClick = {
                                        if (selection.active) {
                                            selection.toggle("v:${item.id}")
                                        } else if (addMode) {
                                            selection.enter("v:${item.id}")
                                        } else {
                                            playFromFolder(navController, activeServer, entries, path, item)
                                        }
                                    },
                                    onLongClick = { selection.enter("v:${item.id}") },
                                    selected = selection.contains("v:${item.id}"),
                                    selecting = selection.active,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (sortSheetOpen) {
        SortLayoutSheet(
            onDismiss = {
                // onPreview 已经实时应用了用户的选择，这里不能再拿打开面板时的旧快照
                // 覆盖回去——否则点遮罩关闭会把刚选的排序/布局悄悄撤销，看起来像「排序没生效」。
                sortDraft = null
                sortSheetOpen = false
            },
            initialGrid = gridMode,
            initialSort = sortKey,
            initialAscending = ascending,
            entries = listOf(SortKey.NAME, SortKey.DATE, SortKey.DURATION, SortKey.SIZE),
            onPreview = { grid, key, asc ->
                gridMode = grid
                sortName = key.name
                ascending = asc
            },
            onApply = { grid, key, asc ->
                gridMode = grid
                sortName = key.name
                ascending = asc
                sortDraft = null
                scope.launch { Graph.settings.setSmbBrowserLayout(grid, key.name, asc) }
            },
        )
    }

    infoFolder?.let { (folder, list) ->
        FolderInfoDialog(
            name = folder.name,
            location = folder.url.removePrefix("smb://"),
            videoCount = list.size,
            totalSize = list.sumOf { it.sizeBytes },
            source = "网络共享",
            onDismiss = { infoFolder = null },
        )
    }

    infoItem?.let { MediaInfoDialog(item = it, onDismiss = { infoItem = null }) }

    // 目标播放列表由用户自己选，加入后直接回到列表详情页
    pendingAdd?.let { items ->
        PlaylistTargetSheet(
            onDismiss = { pendingAdd = null },
            onPick = { targetId, name ->
                pendingAdd = null
                scope.launch {
                    commitAdd(targetId, name, items)
                    // 回到列表详情页；若不是从播放列表进来的（addMode=false），popBackStack 无匹配则停在当前页
                    if (addMode) navController.popBackStack(Routes.PLAYLIST_DETAIL, inclusive = false)
                }
            },
        )
    }
}

/** 这里单独包一层 Scaffold + PageTopBar，是因为网络子页不属于四个主 Tab */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageTopBarHost(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSort: () -> Unit,
    addMode: Boolean,
    selectionActive: Boolean,
    selectionCount: Int,
    onExitSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    allSelected: () -> Boolean,
    bottomBar: @Composable () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        // 内层页面统一铺一张玻璃渐变底：这里之前是 Scaffold 默认的不透明 background，
        // 于是网络浏览/添加页在整站里显得发灰、没有玻璃质感。
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxSize().glassPageBackground(),
        topBar = {
            when {
                // 添加模式：不显示"已选择 N 项"和退出选择按钮 —— 选择态是常驻的，
                // 这里只保留标题、全选与搜索/排序，与本地文件夹选择页的信息层级一致。
                addMode -> PageTopBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = onBack,
                    // 加入页右上角只保留一个全选图标，信息层级与本地选择页一致
                    actions = {
                        IconButton(onClick = onToggleSelectAll) {
                            Icon(
                                Icons.Rounded.SelectAll,
                                contentDescription = if (allSelected()) "取消全选" else "全选",
                            )
                        }
                    },
                )
                selectionActive -> PageTopBar(
                    title = "已选择 $selectionCount 项",
                    subtitle = "轻点勾选或取消 · 右上角可全选",
                    onBack = onExitSelection,
                    backIcon = Icons.Rounded.Close,
                    backLabel = "退出选择",
                    actions = {
                        IconButton(onClick = onToggleSelectAll) {
                            Icon(
                                Icons.Rounded.SelectAll,
                                contentDescription = if (allSelected()) "取消全选" else "全选",
                            )
                        }
                    },
                )
                else -> PageTopBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = onToggleSearch) {
                            Icon(Icons.Rounded.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = onOpenSort) {
                            Icon(Icons.Rounded.Tune, contentDescription = "排列与排序")
                        }
                    },
                )
            }
        },
        bottomBar = bottomBar,
    ) { padding -> content(padding) }
}

/**
 * 播放网络视频：把同目录所有视频排进播放列表，并从当前这一个开始。
 */
private fun playFromFolder(
    navController: NavHostController,
    server: SmbServer,
    entries: List<SmbEntry>,
    folderPath: String,
    item: VideoItem,
) {
    val list: List<VideoItem> = SmbClient.toVideoItems(server, entries, folderPath)
    val index = list.indexOfFirst { it.uri == item.uri }
    val queue = if (index >= 0) list else listOf(item)
    Playback.play(queue, if (index >= 0) index else 0)
    navController.navigate(Routes.PLAYER)
}

/**
 * 加入模式下的文件夹行：左侧主区域点击进入子目录继续浏览，右侧复选框才控制是否把
 * 该目录的直属视频勾选进待加入集合。两者点击区域分开，避免「想进目录却误勾选」。
 */
@Composable
private fun AddModeFolderRow(
    name: String,
    checked: Boolean,
    onEnterFolder: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mediaCardOutline(pressed = false)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "打开文件夹", onClick = onEnterFolder)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(8.dp))
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/**
 * 加入模式下的视频行：整行可点即勾选/取消，右侧复选框同步显示状态（与本地选择页同构）。
 */
@Composable
private fun AddModeVideoRow(
    item: VideoItem,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mediaCardOutline(pressed = checked)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 100.dp, height = 58.dp)) {
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
