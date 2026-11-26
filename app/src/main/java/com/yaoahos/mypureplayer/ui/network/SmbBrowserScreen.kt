package com.yaoahos.mypureplayer.ui.network

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
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
import com.yaoahos.mypureplayer.data.SmbPath
import com.yaoahos.mypureplayer.data.SmbServer
import com.yaoahos.mypureplayer.data.SortKey
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.ui.FileLocation
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.ConfirmDialog
import com.yaoahos.mypureplayer.ui.components.DurationBadge
import com.yaoahos.mypureplayer.ui.components.EmptyState
import com.yaoahos.mypureplayer.ui.components.FolderInfoDialog
import com.yaoahos.mypureplayer.ui.components.GlassEdge
import com.yaoahos.mypureplayer.ui.components.VideoThumb
import com.yaoahos.mypureplayer.ui.components.mediaCardOutline
import com.yaoahos.mypureplayer.ui.components.appGlassBar
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.glassPressable
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
import com.yaoahos.mypureplayer.ui.components.ScrollableFill
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
    /** 「在文件夹中显示」带来的目标视频标识；命中该卡片则高亮并滚动到它。 */
    focus: String = "",
) {
    // 目录层级完全由 NavHost 返回栈承载：每进一层压一个 smb 目的地、返回逐级 pop。
    // 无论是从网络首页正常浏览，还是「在文件夹中显示」跳进深层目录（进入时已把
    // 从共享根到目标的每一级都压进栈），这里看到的都只是当前这一层，转场只有 NavHost
    // 那一套（PageMotion），不再有 jump 分支 / 页内 AnimatedContent / 手改 zIndex。
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
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(initialValue = Graph.settings.currentOrNull() ?: AppSettings())
    // 「删除历史」按选中视频是否有观看记录决定是否置灰
    val history by Graph.history.history.collectAsStateWithLifecycle()
    var server by remember(serverId) { mutableStateOf(Graph.servers.byId(serverId)) }
    // 本实例显示的目录就是路由参数里的那一层；每层一个独立目的地，预填缓存按它取，
    // 返回时露出的是栈里已有的父目录实例，不会「先画下层再跳成正确内容」。
    val path = initialPath.ifBlank { server?.rootPath ?: "smb:///" }
    // 进出文件夹是新路由实例：用内存缓存同步预填列表与加载态，
    // 第一帧即有内容，不会闪「加载中/刷新图标」（cachedList 是纯内存查找，组合期调用安全）
    var entries by remember(server?.id, path) {
        val srv = Graph.servers.byId(serverId)
        val cached = srv?.let { SmbClient.cachedList(it, path) }
        mutableStateOf(cached?.let { SmbClient.videoOnly(it) } ?: emptyList())
    }
    var loading by remember(server?.id, path) {
        val srv = Graph.servers.byId(serverId)
        mutableStateOf(srv == null || SmbClient.cachedList(srv, path) == null)
    }
    // 只有用户主动下拉刷新才让顶部弧线转圈；进文件夹/首开不闪
    var refreshing by remember { mutableStateOf(false) }
    // 本次 reload 是不是下拉手势发起的：它决定「顶部转圈」还是「内容区转圈」，
    // 两者只能有一个在转（否则同屏两个加载指示，看起来像卡住又像在加载）
    var pullReload by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    // 列目录是一次阻塞式网络调用：连续下拉会让多次 list 同时打 NAS，
    // 谁最后结束谁清 spinner，转圈就被拖到最慢那次。同一时刻只允许一次在飞。
    var reloadRunning by remember { mutableStateOf(false) }
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
    // 待确认的「删除历史」目标：只清观看记录，不动 NAS 文件
    var pendingHistoryDelete by remember { mutableStateOf<List<VideoItem>?>(null) }
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

    // 返回的唯一定义：顶栏返回按钮与系统返回键都走这里。
    // 目录层级就是返回栈，一律 popBackStack —— 退到父目录、再退到网络首页，
    // 动画与全站页面转场完全一致（PageMotion）。
    fun stepBack() {
        navController.popBackStack()
    }

    LaunchedEffect(activeServer?.id, path, reloadKey) {
        val srv = activeServer ?: return@LaunchedEffect
        // 同一目录同时只允许一次列目录在飞：阻塞式 smbj 调用不会因为协程取消而中断，
        // 多次叠加会一起抢连接，spinner 要等最慢那次才停。
        if (reloadRunning) return@LaunchedEffect
        reloadRunning = true
        error = null
        // 只有下拉刷新（reloadKey 递增且来自下拉手势）才驱动顶部弧线；
        // 进文件夹/首开走缓存静默刷新，不再闪图标
        val isPull = pullReload
        pullReload = false
        try {
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
            // 超时分两档：有缓存时短超时快速失败、继续看旧列表；没缓存（首次进目录 /
            // 冷启动）必须给足建连时间——SMB 握手本身就要 20s，再加认证与列目录，
            // 一律 20s 会在握手没结束时就把页面判成「响应超时进不去」。
            // 注意这里只取消协程，阻塞的 smbj 调用仍会跑完，所以上限按最坏冷连接给。
            val listTimeout = if (cached != null) 20_000L else 70_000L
            val listed = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(listTimeout) {
                    SmbClient.list(srv, path, force = isPull)
                }
            }
                // 取消不是失败：否则缓存为空时会把"取消"误报成错误页。
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            when {
                listed.isFailure -> {
                    if (cached == null) error = SmbErrors.describe(srv, listed.exceptionOrNull()!!)
                }
                listed.getOrNull() == null -> {
                    if (cached == null) error = "网络位置响应超时，请下拉重试"
                }
                else -> entries = SmbClient.videoOnly(listed.getOrNull()!!)
            }
        } finally {
            // 任何路径（包括外部取消）都要收旗，否则会一直转圈
            loading = false
            refreshing = false
            reloadRunning = false
        }
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

    // 每次进入目录，给这一批网络视频一次重新生成缩略图的机会。
    // 旧逻辑对「连续两次取不出画面」落盘失败标记（有效期一周），用户第一次进目录时
    // 网络抖动被误判后，之后再进同一个文件夹也永远只剩图标。
    LaunchedEffect(entries, path, activeServer?.id) {
        val srv = activeServer ?: return@LaunchedEffect
        val items = SmbClient.toVideoItems(srv, entries, path)
        // 逐条算 key（MD5）+ 删失败标记都是文件操作，目录里几百个视频就是上千次
        // 系统调用，占着主线程进目录会明显掉帧
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Thumbnails.allowRetry(context, items)
        }
        // 首屏之外的封面在空闲时预热：滚下去时直接命中缓存，
        // 不再出现「每翻一屏都要等十几秒才长图」。
        // 跳过前 12 张 —— 它们就是首屏，卡片自己会请求，预热只会和它们抢许可。
        Thumbnails.prefetch(context, items.drop(12).take(24), srv)
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

    // 「在文件夹中显示」的定位描边只在刚跳进来时有意义：它标的是"你要找的就是这一条"。
    // 播完这条视频再返回，描边还亮着会被误认成多选选中态（用户 2026-11-27 反馈）。
    // 路由参数不可变，这里留一份可清空的状态；以 focus 作 key，换目标重新跳转时重新生效。
    var activeFocus by rememberSaveable(focus) { mutableStateOf(focus) }

    // 「在文件夹中显示」的目标定位：列表 state 必须在下面 activeServer 提前返回之前创建，
    // 否则服务器缺失时重组会跳过 remember、回来后 state 归属错位。
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val focusIndex = remember(visibleVideos, activeFocus) {
        if (activeFocus.isBlank()) -1
        else visibleVideos.indexOfFirst { FileLocation.matches(it, activeFocus) }
    }
    LaunchedEffect(activeFocus, focusIndex, visibleVideos.size, filteredFolders.size, gridMode, addMode) {
        if (focusIndex < 0) return@LaunchedEffect
        // 等目标出现在列表里（首帧可能还没加载完）再定位；瞬时滚动，不引入新动画。
        // 列表前面还有「文件夹」分区与若干文件夹行，得算上偏移；addMode 用的是另一种布局。
        //
        // 两个分区标题（「文件夹」「视频」）只在有文件夹时出现 —— 视频标题与文件夹分区
        // 是同一个 if 里的，没有子文件夹时列表里没有任何前置项。少算这一档会多滚两格，
        // 在宫格里正好把被定位的那一条滚出屏幕（看起来像没定位）。
        val headerItems = if (filteredFolders.isEmpty()) 0 else 2 + filteredFolders.size
        // addMode 是 LazyColumn（listState）；普通浏览的宫格与列表都是基于 gridState 的
        // LazyVerticalGrid（列表模式只是把格子缩到一行高），这里必须跟布局走。
        if (addMode) {
            listState.scrollToItem((headerItems + focusIndex).coerceAtLeast(0))
        } else {
            gridState.scrollToItem((headerItems + focusIndex).coerceAtLeast(0))
        }
    }

    if (activeServer == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    /**
     * 播放视频，并撤掉「在文件夹中显示」的定位描边。
     *
     * 只有播的正是被定位的那一条才清：播别的视频时，描边指向的目标还在，仍然有用。
     * 宫格与列表两个分支共用，避免两处各写一遍又把语义写歪。
     */
    fun playFromFocus(item: VideoItem, play: () -> Unit) {
        if (FileLocation.matches(item, activeFocus)) activeFocus = ""
        play()
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
    val historyKeys = remember(history) {
        history.mapTo(HashSet()) { it.videoId to it.source }
    }
    val hasHistory = remember(selectedVideos, historyKeys) {
        selectedVideos.any { (it.id to it.source) in historyKeys }
    }

    // 系统返回键交给 NavHost 默认弹栈处理：目录层级就是返回栈，逐级 pop
    // 天然沿目录树退回，不需要页面自己接管。选择态与搜索框的返回在下面优先处理。

    // 添加模式下选择态是常驻的：返回键应当直接退出页面，而不是"退选择"
    BackHandler(enabled = selection.active && !addMode) { selection.exit() }

    // 搜索框打开时，返回键先关搜索框；写在上面之后才能优先于"退选择"。
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
    }

    val title = if (path.trimEnd('/') == activeServer.rootPath.trimEnd('/')) {
        activeServer.name
    } else {
        Formats.baseName(path).ifBlank { activeServer.name }
    }
    // 下钻进子文件夹 = 压一个新的 smb 目的地。防连点挡两件事：同一目录自己压自己；
    // 转场期间旧层还活着，它上面的卡片再点一次会叠第二次转场。
    var navigationPending by remember { mutableStateOf(false) }
    LaunchedEffect(navigationPending) {
        if (navigationPending) {
            kotlinx.coroutines.delay(600) // 大于转场时长，短于用户再次点击的间隔
            navigationPending = false
        }
    }
    fun openFolder(target: String) {
        if (target == path || navigationPending) return
        navigationPending = true
        // 不能加 launchSingleTop：参数化路由只看 destination id，会把上一层目录顶掉，
        // 从子文件夹返回就跳过父目录直达服务器列表。防连点交给上面的 navigationPending。
        navController.navigate(Routes.smb(serverId, target, addMode, targetPlaylist))
    }
    PageTopBarHost(
        title = title,
        subtitle = if (addMode) {
            // 根目录常常只有文件夹没有视频，写"0 个视频"会像出错了。
            // 带"可选"二字，避免用户以为全部数量都要加入播放列表。
            "${visibleVideos.size} 个视频可选"
        } else path,
        onBack = { stepBack() },
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
                        com.yaoahos.mypureplayer.ui.components.GlassButton(
                            text = "加入列表",
                            onClick = {
                                // 选中的视频 + 选中文件夹的直属视频（不递归），按 id 去重。
                                // 这里先把条目取出来，目标列表由随后弹出的选择器决定。
                                scope.launch {
                                    val fromFolders = selectedFolders.flatMap { folder ->
                                        folderVideosOrNull(activeServer, folder.url).orEmpty()
                                    }
                                    val seen = HashSet<String>()
                                    val items = (selectedVideos + fromFolders).filter { seen.add(it.id) }
                                    if (items.isEmpty()) Notice.error("所选文件夹里没有视频")
                                    else pendingAdd = items
                                }
                            },
                            enabled = selectedCount > 0,
                        )
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
                                soloFolder != null -> openFolder(soloFolder.url)
                            }
                            selection.exit()
                        },
                        SelectionAction(Icons.Rounded.Info, "详情", enabled = canAct && selectedCount == 1) {
                            if (soloVideo != null) infoItem = soloVideo
                            if (soloFolder != null) {
                                scope.launch {
                                    val list = folderVideosOrNull(activeServer, soloFolder.url).orEmpty()
                                    infoFolder = soloFolder to list
                                }
                            }
                            selection.exit()
                        },
                        SelectionAction(Icons.AutoMirrored.Rounded.FormatListBulleted, "加入列表", enabled = canAct) {
                            scope.launch {
                                // 多选：选中的视频 + 每个选中文件夹的直属视频（不递归子目录），按 id 去重
                                val fromFolders = selectedFolders.flatMap { folder ->
                                    folderVideosOrNull(activeServer, folder.url).orEmpty()
                                }
                                val seen = HashSet<String>()
                                val list = (selectedVideos + fromFolders).filter { seen.add(it.id) }
                                if (list.isNotEmpty()) pendingAdd = list else Notice.error("这里没有视频")
                            }
                            selection.exit()
                        },
                    ) + listOfNotNull(
                        // 网络封面是按帧猜的：不满意就换下一档取帧策略重来，连着点会在
                        // 几种画面之间循环。放进「更多」，不占主操作位。
                        soloVideo?.let { target ->
                            SelectionAction(
                                Icons.Rounded.Refresh,
                                "重置缩略图",
                                enabled = canAct,
                            ) {
                                selection.exit()
                                scope.launch {
                                    val ok = runCatching {
                                        Thumbnails.resetThumbnail(context, target, activeServer)
                                    }.getOrDefault(false)
                                    if (ok) {
                                        Notice.success("已换一帧画面")
                                    } else {
                                        Notice.error("这个视频取不到其它画面")
                                    }
                                }
                            }
                        },
                        // 只清观看记录，不动 NAS 上的文件；选中项没有历史时置灰。
                        // 用 forceMore 收进「更多」，不挤占播放/详情/加入列表三个主操作。
                        // 文件夹没有观看记录：只选中文件夹时这一项不出现。
                        if (selectedVideos.isEmpty()) {
                            null
                        } else {
                            SelectionAction(
                                Icons.Rounded.DeleteSweep,
                                "删除历史",
                                enabled = canAct && hasHistory,
                            ) {
                                // 只清观看记录，不动 NAS 上的文件；先确认再执行
                                val targets = selectedVideos
                                if (targets.isNotEmpty()) pendingHistoryDelete = targets
                            }
                        },
                    ),
                    forceMore = true,
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
    ) { padding ->
        PullRefreshBox(
            isRefreshing = refreshing,
            // 上一次还没回来就忽略这次下拉，别把多次网络请求叠在一起
            onRefresh = { if (!reloadRunning) { pullReload = true; reloadKey++ } },
            // 刷新失败时页面已经写明「无法打开这个位置」，再弹一句「已刷新」是自相矛盾；
            // 成功才提示，失败让错误态自己说话。
            doneText = { if (error != null) null else "已刷新" },
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
                    error != null -> ScrollableFill {
                        EmptyState(
                            icon = Icons.Rounded.Folder,
                            title = "无法打开这个位置",
                            // 不给「重试」按钮：下拉刷新做的是同一件事，右下角/中间再摆一颗
                            // 按钮只是把同一个动作写两遍。代价是必须把「可以下拉」说出来，
                            // 否则错误页看起来像一条死路。
                            message = "${error.orEmpty().trimEnd()}\n\n下拉可以重新尝试",
                        )
                    }

                    // 无缓存的首开/进目录：内容区给一个居中转圈。
                    // 下拉刷新的转圈在顶部弧线，这里用 refreshing 把它让开，
                    // 保证任何时刻屏幕上只有一个加载指示。
                    loading && !refreshing && visibleVideos.isEmpty() && filteredFolders.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }

                    visibleVideos.isEmpty() && filteredFolders.isEmpty() -> ScrollableFill {
                        // 文案承诺了「下拉可以重新加载」，那就必须真的能下拉：
                        // 纯 Column 不是滚动节点，nestedScroll 收不到手势（这里原来就是断的）
                        EmptyState(
                            icon = Icons.Rounded.Movie,
                            title = "这里没有视频",
                            message = "下拉可以重新加载；只显示视频文件与文件夹。",
                        )
                    }

                    // 加入播放列表模式：固定用列表形式（不受浏览页的宫格设置影响）。
                    // 文件夹左侧=进入目录、右侧复选框=勾选整个目录的直属视频；
                    // 视频整行=勾选/取消选择，右侧复选框同步显示状态。
                    addMode -> LazyColumn(
                        state = listState,
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
                                    flashClick {
                                        openFolder(folder.url)
                                    }
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
                        state = gridState,
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
                                        openFolder(folder.url)
                                    }
                                },
                                onLongClick = {
                                    if (addMode) {
                                        openFolder(folder.url)
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
                                        playFromFocus(item) {
                                            playFromFolder(navController, activeServer, entries, path, item)
                                        }
                                    }
                                },
                                onLongClick = { selection.enter("v:${item.id}") },
                                selected = selection.contains("v:${item.id}"),
                                selecting = selection.active,
                                focused = FileLocation.matches(item, activeFocus),
                            )
                        }
                    }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = listRowMinSize()),
                        state = gridState,
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
                                            openFolder(folder.url)
                                        }
                                    },
                                    onLongClick = {
                                        if (addMode) {
                                            openFolder(folder.url)
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
                                            playFromFocus(item) {
                                                playFromFolder(navController, activeServer, entries, path, item)
                                            }
                                        }
                                    },
                                    onLongClick = { selection.enter("v:${item.id}") },
                                    selected = selection.contains("v:${item.id}"),
                                    selecting = selection.active,
                                    focused = FileLocation.matches(item, activeFocus),
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
                // 取消（含点遮罩/返回键）要把 onPreview 的实时改动回滚到 opening 快照；
                // 确定时 onApply 已先清空 sortDraft，这里不会覆盖已确定的选择。
                sortDraft?.let { draft ->
                    gridMode = draft.grid
                    sortName = draft.key
                    ascending = draft.ascending
                }
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
            location = folder.url,
            videoCount = list.size,
            totalSize = list.sumOf { it.sizeBytes },
            source = "网络共享",
            onDismiss = { infoFolder = null },
        )
    }

    infoItem?.let { MediaInfoDialog(item = it, onDismiss = { infoItem = null }) }

    pendingHistoryDelete?.let { targets ->
        ConfirmDialog(
            title = "删除观看记录",
            message = "已选中的 ${targets.size} 个视频的观看记录将被删除，不会删除 NAS 上的文件。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                pendingHistoryDelete = null
                com.yaoahos.mypureplayer.playback.Playback.suppressProgressRewrite(targets.map { it.id })
                scope.launch {
                    val n = Graph.history.removeAll(targets.map { it.id })
                    Notice.success(if (n > 0) "已删除 $n 条观看记录" else "没有可删除的观看记录")
                }
                selection.exit()
            },
            onDismiss = { pendingHistoryDelete = null },
        )
    }

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
 * 取某个子文件夹的直属视频（不递归子目录）。
 *
 * 用 [runCatching] 包 SMB 调用时必须把协程取消原样抛出：用户点了「加入列表 / 详情」
 * 立刻返回上页，协程被取消，若把它当成失败，回调里会接着提示「这里没有视频」——
 * 明明只是没等到结果。失败返回 null，由调用方决定提示什么。
 */
private suspend fun folderVideosOrNull(server: SmbServer, folder: String): List<VideoItem>? =
    runCatching { SmbClient.list(server, folder) }
        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        .getOrNull()
        ?.let { SmbClient.videoOnly(it) }
        ?.let { SmbClient.toVideoItems(server, it, folder) }

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
    Playback.playChecked(queue, if (index >= 0) index else 0) {
        navController.navigate(Routes.PLAYER)
    }
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
                .glassPressable(
                    role = Role.Button,
                    radius = 12.dp,
                    onClickLabel = "打开文件夹",
                    onClick = onEnterFolder,
                )
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
    // 勾选态走 selected，按下态才算 pressed：早先借用 pressed 表示勾选，
    // 一进页面被勾中的行就是"按下"色，真正按下去反而没有变化。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedWithTap()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mediaCardOutline(pressed = pressed, selected = checked)
            .toggleable(
                interactionSource = interaction,
                indication = null,
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
