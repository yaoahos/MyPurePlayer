package com.yaoahos.mypureplayer.ui.local

import com.yaoahos.mypureplayer.ui.components.GlassDialogButton
import com.yaoahos.mypureplayer.ui.components.GlassButtonTone
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.data.FileOps
import com.yaoahos.mypureplayer.data.FolderGroup
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.MediaSource
import com.yaoahos.mypureplayer.data.SortKey
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.media.Thumbnails
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.ui.HideShellBottomBar
import java.io.File
import com.yaoahos.mypureplayer.ui.MainScaffold
import com.yaoahos.mypureplayer.ui.MainTab
import com.yaoahos.mypureplayer.ui.FileLocation
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.alertDialogGlassSurface
import com.yaoahos.mypureplayer.ui.components.appDialogSurface
import com.yaoahos.mypureplayer.ui.components.DirPickerDialog
import com.yaoahos.mypureplayer.ui.components.ConfirmDialog
import com.yaoahos.mypureplayer.ui.components.EmptyState
import com.yaoahos.mypureplayer.ui.components.FolderInfoDialog
import com.yaoahos.mypureplayer.ui.components.MediaFolderGridCard
import com.yaoahos.mypureplayer.ui.components.ListRowContainer
import com.yaoahos.mypureplayer.ui.components.ListSelection
import com.yaoahos.mypureplayer.ui.components.MediaFolderRow
import com.yaoahos.mypureplayer.ui.components.MediaInfoDialog
import com.yaoahos.mypureplayer.ui.components.MediaSortDraft
import com.yaoahos.mypureplayer.ui.components.mediaDuration
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.PlaylistTargetSheet
import com.yaoahos.mypureplayer.ui.components.RenameDialog
import com.yaoahos.mypureplayer.ui.components.SearchField
import com.yaoahos.mypureplayer.ui.components.ScrollableFill
import com.yaoahos.mypureplayer.ui.components.SelectionAction
import com.yaoahos.mypureplayer.ui.components.SelectionActionBar
import com.yaoahos.mypureplayer.ui.components.SelectionMoreSheet
import com.yaoahos.mypureplayer.ui.components.SortLayoutSheet
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.adaptiveMediaGridMinSize
import com.yaoahos.mypureplayer.ui.components.listRowMinSize
import com.yaoahos.mypureplayer.ui.components.VideoGridCard
import com.yaoahos.mypureplayer.ui.components.VideoListRow
import com.yaoahos.mypureplayer.ui.more.VaultSetupDialog
import com.yaoahos.mypureplayer.ui.more.VaultUnlockDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalScreen(
    navController: NavHostController,
    /**
     * 文件夹目的地：非空表示本页是 NavHost 里的一层目录，只显示该目录视频；
     * 首页（壳层 tab）恒为 null。目录层级完全由 NavHost 承载 —— 进文件夹 push 一个
     * 目的地、返回 pop，与其它内层页走同一套转场，页内不再有第二套弹簧动画。
     */
    initialFolderKey: String? = null,
    /** 「在文件夹中显示」带来的目标视频标识；命中该卡片则高亮并滚动到它。 */
    focus: String = "",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val videos by Graph.local.videos.collectAsStateWithLifecycle()
    // 长按操作里的「删除历史」要按当前选中的视频判断有没有记录，没有就置灰
    val history by Graph.history.history.collectAsStateWithLifecycle()
    val scanning by Graph.local.scanning.collectAsStateWithLifecycle()
    val vaultConfig by Graph.vault.config.collectAsStateWithLifecycle()
    // 保险箱是否已解锁（数据密钥在内存里）。锁定状态下直接隐藏必然失败，
    // 必须先让他输一次密码 —— 见下方 onHide 分支。
    val vaultUnlocked by Graph.vault.unlocked.collectAsStateWithLifecycle()
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = Graph.settings.currentOrNull() ?: com.yaoahos.mypureplayer.data.AppSettings(),
    )

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var gridMode by rememberSaveable { mutableStateOf(settings.localGrid) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(settings.localSort) }
    var ascending by rememberSaveable { mutableStateOf(settings.localAscending) }
    // null = 全部视频（首页）；非 null = 本页所在的文件夹目的地（固定不变）。
    // 目录层级改由 NavHost 目的地承载：进文件夹 push、返回 pop，不再在页内改这个值。
    val currentFolderKey: String? = initialFolderKey

    // 进文件夹防连点：flashClick 只是延后动作、并不拦重复点击，快速连点同一张
    // 文件夹卡片会连 push 两层相同目的地，返回时要按两次才退出来。转场期间旧页
    // 还活着，它上面的卡片再点一次同样会叠第二次转场。与 SMB 目录页同一套做法：
    // 一次放行后锁 600ms（大于 300ms 转场、短于用户再次点击的间隔）。
    var folderNavPending by remember { mutableStateOf(false) }
    LaunchedEffect(folderNavPending) {
        if (folderNavPending) {
            kotlinx.coroutines.delay(600)
            folderNavPending = false
        }
    }
    fun openFolder(key: String) {
        if (folderNavPending) return
        folderNavPending = true
        // 不能加 launchSingleTop：参数化路由只看 destination id，会把上一层目录顶掉，
        // 从子文件夹返回就跳过父目录。防连点交给 folderNavPending。
        navController.navigate(Routes.localFolder(key))
    }
    // 1 文件夹 / 2 视频：本地视频的「文件夹 / 视频」浏览方式切换（2026-11-22 按用户要求恢复）
    var browseMode by rememberSaveable { mutableIntStateOf(settings.localBrowseMode) }
    // 设置在别处被改（配置恢复）时同步；只盯这几个字段，改主题等不会覆盖页内草稿
    LaunchedEffect(settings.localGrid, settings.localSort, settings.localAscending, settings.localBrowseMode) {
        gridMode = settings.localGrid
        sortName = settings.localSort
        ascending = settings.localAscending
        browseMode = settings.localBrowseMode
    }

    // 返回统一交给 NavHost：文件夹目的地 pop 掉自己就露出下面的首页（带常驻底栏）。
    // 本地目录只有一层（进入某个文件夹后只列视频、不再往下钻），所以一次 pop 到位，
    // 不存在「先退回首页文件夹、再返回一次才见底栏」的中间态。
    // 系统返回键与顶栏返回按钮都走这条路，动画与全站页面转场完全一致（PageMotion）。
    BackHandler(enabled = currentFolderKey != null) { navController.popBackStack() }

    // 搜索框的返回同样优先于"退出应用"：先收搜索框并清空关键词。
    // 必须写在上面那条 BackHandler **之后**——同一时刻有多个启用时，
    // 后组合的那个先拿到事件，否则会先去退文件夹（或直接退到桌面）。
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
    }

    // 只有多选态才让壳层常驻底栏让位给页面自己的四宫格操作栏。
    // 文件夹目的地与其它内层页一致：靠 PageRoot 的不透明背景把底栏盖住，
    // 进/出时由页面侧滑"一体"遮住或露出底栏。之前这里额外把底栏从组合里
    // 直接移除，导致底栏先闪消失、页面才滑入，返回时反过来（底栏最后才闪出来）。
    var sortDraft by remember { mutableStateOf<MediaSortDraft?>(null) }

    // 长按只进入选择态；动作从顶栏 / 底部四宫格操作栏发起。
    val selection = remember { ListSelection() }

    HideShellBottomBar(selection.active)

    BackHandler(enabled = selection.active && !searchOpen) { selection.exit() }
    var infoFolder by remember { mutableStateOf<FolderGroup?>(null) }
    var pendingFolderDelete by remember { mutableStateOf<FolderGroup?>(null) }
    var renameItem by remember { mutableStateOf<VideoItem?>(null) }
    var infoItem by remember { mutableStateOf<VideoItem?>(null) }
    var dirOp by remember { mutableStateOf<Pair<VideoItem, Boolean>?>(null) } // item to copyFlag
    var vaultSetupOpen by remember { mutableStateOf(false) }
    // 待隐藏内容：group 非空表示整目录隐藏，保险箱里才会还原成一个文件夹；
    // 单个视频留空，加入后就直接躺在保险箱首页（不凭空长出一个上层目录）
    var pendingHide by remember { mutableStateOf<VaultHide?>(null) }
    // 隐藏前需要先解锁保险箱：把这些视频暂存，解锁成功后接着隐藏
    var unlockThenHide by remember { mutableStateOf<VaultHide?>(null) }
    var permissionDialog by remember { mutableStateOf(false) }
    // 权限对话框不能只是"告诉用户去开启"：确认框已经收起了，用户授权回来后
    // 必须接着执行刚才那一步，否则只能重新长按、重新确认，还容易拿到过期条目。
    var pendingPermissionOp by remember { mutableStateOf<(() -> Unit)?>(null) }
    var busyText by remember { mutableStateOf<String?>(null) }
    // 可量化的长任务（加密进保险箱）额外带百分比与副标题；普通忙碌只转圈。
    var busyProgress by remember { mutableStateOf<Float?>(null) }
    var busyDetail by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<VideoItem?>(null) }
    // 多选删除（含多选里的文件夹）：与单条一样先确认，不能因为「选了好几个」就跳过
    var pendingMultiDelete by remember { mutableStateOf<List<VideoItem>?>(null) }
    // 删除历史（批量）：只清观看记录，不动文件，同样要二次确认
    var pendingHistoryDelete by remember { mutableStateOf<List<VideoItem>?>(null) }
    // 「加入列表」的目标由用户自己选
    var pendingAdd by remember { mutableStateOf<List<VideoItem>?>(null) }

    /** 需要完整文件权限的操作统一走这里；缺权限时把动作存住，授权回来自动续做 */
    fun guarded(action: () -> Unit) {
        if (FileOps.hasAllFiles(context)) {
            action()
        } else {
            pendingPermissionOp = action
            permissionDialog = true
        }
    }

    /** 授权返回后重新扫描媒体库，再用最新条目续做刚才被拦下的操作 */
    fun resumePendingPermissionOp() {
        val op = pendingPermissionOp ?: return
        pendingPermissionOp = null
        permissionDialog = false
        scope.launch {
            Graph.local.scan(silent = true)
            op()
        }
    }

    val sortKey = remember(sortName) { runCatching { SortKey.valueOf(sortName) }.getOrDefault(SortKey.DATE) }

    val permission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    fun askPermission() {
        com.yaoahos.mypureplayer.MainActivity.requestPermission(context, permission) { ok ->
            granted = ok
            if (ok) scope.launch { Graph.local.scan(silent = true) }
        }
    }

    /**
     * 回到本页时补一次静默扫描。
     *
     * 必须**等转场结束**再扫：MediaStore 查询回来会换掉整个 videos 列表，
     * 列表重建 + 缩略图解码如果正好压在页面滑入的那 300ms 上，表现就是
     * 「从里层页面返回底栏时卡一下」—— 用户这次的第二条反馈。
     * 页面内进文件夹之所以流畅，就是因为那条路径不重建整个列表。
     *
     * 另外加一个最小间隔：连续来回切页时不要每次都扫（内容没变，白扫）。
     */
    fun autoScan() {
        if (!granted) return
        val now = System.currentTimeMillis()
        if (now - lastAutoScanAt < AUTO_SCAN_MIN_GAP_MS) return
        lastAutoScanAt = now
        scope.launch { Graph.local.scan(silent = true) }
    }

    LaunchedEffect(Unit) {
        // 文件夹目的地只读首页已经加载好的内存数据：进 Camera 这类视频很多目录时，
        // 一次 MediaStore 全量扫描 + 保险箱/回收站读盘正好压在 300ms 转场落位那一帧，
        // 就是"进文件夹卡一下"的来源。扫描只在首页做，文件夹里需要刷新时走下拉刷新。
        if (currentFolderKey == null) {
            if (granted) autoScan() else askPermission()
            Graph.vault.load()
            Graph.trash.load()
        }
    }

    // 首次进视频多的文件夹会掉帧：首屏十几张卡同时生成缩略图（分配位图+JPEG落盘+
    // 重组）压在 300ms 滑入动画上。转场期间先暂停本地缩略图生成，落位后再统一
    // 恢复、渐次出图；磁盘缓存命中的（滑到底后再进）不受影响，照常秒开。
    LaunchedEffect(currentFolderKey) {
        if (currentFolderKey == null) return@LaunchedEffect
        Thumbnails.pauseLocalThumbs()
        try {
            delay(TRANSITION_SETTLE_MS)
        } finally {
            Thumbnails.resumeLocalThumbs()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (FileOps.hasAllFiles(context)) resumePendingPermissionOp()
        // 文件夹页不在 ON_RESUME 里全量扫描，否则每次从视频详情/播放页返回都会重建整张列表
        if (!granted || currentFolderKey != null) return@LifecycleEventEffect
        scope.launch {
            // 让转场先跑完（页面切换约 300ms 落位）
            delay(TRANSITION_SETTLE_MS)
            autoScan()
        }
    }

    val durationVersion by Thumbnails.durationVersion.collectAsStateWithLifecycle()
    val durationTick = if (sortKey == SortKey.DURATION) durationVersion else 0
    // 先按目录收窄，再过滤/排序。
    //
    // 旧实现对着整个本地库做一次 filter + sortedWith，再在结果里挑出本目录的视频。
    // 首页（几千条）需要这样；但进了文件夹页只要本目录那几十上百条，却也把全库
    // 重新排一遍 —— 每次进入都是一次纯属白干的主线程开销，视频越多越明显。
    val baseVideos = remember(videos, currentFolderKey) {
        if (currentFolderKey == null) videos else videos.filter { it.folderKey == currentFolderKey }
    }
    val filtered = remember(baseVideos, query, sortKey, ascending, durationTick) {
        val base = baseVideos.filter { item ->
            query.isBlank() ||
                item.title.contains(query, ignoreCase = true) ||
                item.fileName.contains(query, ignoreCase = true) ||
                item.folderName.contains(query, ignoreCase = true)
        }
        val comparator: Comparator<VideoItem> = when (sortKey) {
            SortKey.NAME -> compareBy { it.title.lowercase() }
            SortKey.DATE -> compareBy { it.dateModified }
            SortKey.DURATION -> compareBy { mediaDuration(it) }
            SortKey.SIZE -> compareBy { it.sizeBytes }
        }
        base.sortedWith(if (ascending) comparator else comparator.reversed())
    }

    // 文件夹分组只有首页用得到：目录页不分文件夹，也不显示文件夹卡片。
    // 目录页跳过这次 groupBy + 排序，同样是「进入时少做一件与当前页无关的事」。
    val groups = if (currentFolderKey == null) {
        remember(videos) { Graph.local.folders() }
    } else {
        emptyList()
    }
    // v:/d: 前缀区分视频与文件夹，避免两者 id 恰好相同
    val selectedVideos = remember(videos, selection.ids) {
        videos.filter { selection.contains("v:${it.id}") }
    }
    val historyKeys = remember(history) {
        history.mapTo(HashSet()) { it.videoId to it.source }
    }
    val hasHistory = remember(selectedVideos, historyKeys) {
        selectedVideos.any { (it.id to it.source) in historyKeys }
    }
    val selectedGroups = remember(groups, selection.ids) {
        groups.filter { selection.contains("d:${it.key}") }
    }
    val selectedCount = selectedVideos.size + selectedGroups.size
    val soloVideo = selectedVideos.singleOrNull()
    val soloGroup = selectedGroups.singleOrNull()
    // filtered 已经在目录页按 folderKey 收窄过，这里直接就是本页要显示的视频
    val scopedVideos = filtered
    val currentFolderName = remember(currentFolderKey, scopedVideos) {
        if (currentFolderKey == null) ""
        else scopedVideos.firstOrNull()?.folderName?.takeIf { it.isNotBlank() } ?: "文件夹"
    }
    // browseMode=1（文件夹）：只列文件夹；进入某个文件夹后只列该文件夹的视频。
    // browseMode=2（视频）：铺平列出全部视频，不显示文件夹、退出文件夹层级。
    val visibleFolders = remember(groups, query, browseMode, currentFolderKey) {
        if (currentFolderKey != null || browseMode == 2) emptyList()
        else groups.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }
    val visibleVideos = if (browseMode == 1 && currentFolderKey == null) emptyList() else scopedVideos

    // 「在文件夹中显示」的定位描边只在刚跳进来时有意义：它标的是"你要找的就是这一条"。
    // 用户播完这条视频再返回，描边还亮着就会被误认成多选选中态（用户 2026-11-27 反馈）。
    // 因此这里不直接用路由参数，而是留一份可清空的状态：匹配到的那条一开始播放就置空。
    // 用 rememberSaveable 记住 focus 作为输入：换一个目标重新跳转时能重新生效。
    var activeFocus by rememberSaveable(focus) { mutableStateOf(focus) }

    // 「在文件夹中显示」的目标定位：瞬时滚动到目标卡片，不引入新动画。
    // 列表前面可能有文件夹行（首页场景），算上偏移；文件夹目的地里 visibleFolders 为空。
    val gridState = rememberLazyGridState()
    val focusIndex = remember(visibleVideos, activeFocus) {
        if (activeFocus.isBlank()) -1
        else visibleVideos.indexOfFirst { FileLocation.matches(it, activeFocus) }
    }
    LaunchedEffect(activeFocus, focusIndex, visibleVideos.size, visibleFolders.size, browseMode, gridMode) {
        if (focusIndex < 0) return@LaunchedEffect
        gridState.scrollToItem((visibleFolders.size + focusIndex).coerceAtLeast(0))
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

    /**
     * 忙碌遮罩的统一实现。
     *
     * [showProgress] 为 false 时只有转圈（重命名、复制、移入回收站这些瞬时的或
     * 无法估量的操作）；为 true 时显示条形进度。加密一个大文件要几秒到几十秒，
     * 只转圈会让人以为卡死；百分比按 1% 节流（加密回调是 256KB 一次的，
     * 不节流会把重组打爆）。
     */
    fun runBusyInternal(
        text: String,
        showProgress: Boolean,
        block: suspend ((Float, String?) -> Unit) -> String,
    ) {
        scope.launch {
            busyText = text
            busyProgress = if (showProgress) 0f else null
            busyDetail = null
            var lastPercent = -1
            val report: (Float, String?) -> Unit = { fraction, detail ->
                val value = fraction.coerceIn(0f, 1f)
                val percent = (value * 100).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    busyProgress = value
                    if (detail != null) busyDetail = detail
                }
            }
            val result = runCatching { block(report) }
            busyText = null
            busyProgress = null
            busyDetail = null
            // 显式语气，不再靠中文关键词猜；异常即失败，成功结果统一 success
            result.fold(
                onSuccess = { Notice.success(it) },
                onFailure = { Notice.error(it.message ?: "操作失败") },
            )
            Graph.local.scan()
        }
    }

    fun runBusyProgress(text: String, block: suspend ((Float, String?) -> Unit) -> String) {
        runBusyInternal(text, showProgress = true, block)
    }

    fun runBusy(text: String, block: suspend () -> String) {
        runBusyInternal(text, showProgress = false) { block() }
    }

    // 目录层级改由 NavHost 目的地承载后，这一层不再是「页内位移」，而是整页转场：
    // 进入时新页从右滑入、返回时旧页右滑出，全部由 AppNavHost 的 page 套动画接管。
    // 因此不再需要页内 Animatable / graphicsLayer，也不再需要额外垫背景补空白。
    Box(Modifier.fillMaxSize().glassPageBackground()) {
    MainScaffold(
        topBar = {
            if (selection.active) {
                PageTopBar(
                    title = "已选择 $selectedCount 项",
                    subtitle = "轻点勾选或取消 · 右上角可全选",
                    onBack = { selection.exit() },
                    backIcon = Icons.Rounded.Close,
                    backLabel = "退出选择",
                    actions = {
                        val allKeys = visibleFolders.map { "d:${it.key}" } + visibleVideos.map { "v:${it.id}" }
                        val allSelected = allKeys.isNotEmpty() && selectedCount == allKeys.size
                        IconButton(onClick = {
                            // 取消全选只清勾选项，不退出选择态
                            if (allSelected) selection.clear()
                            else selection.replaceAll(allKeys)
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
                    title = if (currentFolderKey == null) "本地视频" else currentFolderName,
                    subtitle = when {
                        videos.isEmpty() -> "自动扫描本机视频"
                        currentFolderKey != null -> "${scopedVideos.size} 个视频"
                        browseMode == 2 -> "共 ${videos.size} 个视频"
                        else -> "共 ${videos.size} 个视频 · ${groups.size} 个文件夹"
                    },
                    onBack = if (currentFolderKey != null) ({ navController.popBackStack() }) else null,
                    actions = {
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = {
                            sortDraft = MediaSortDraft(gridMode, sortName, ascending, browseMode)
                            sortSheetOpen = true
                        }) {
                            Icon(Icons.Rounded.Tune, contentDescription = "排列与排序")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (selection.active) {
                val canAct = selectedCount > 0
                val sheetVideos = selectedVideos + selectedGroups.flatMap { it.videos }
                SelectionActionBar(
                    actions = listOf(
                        SelectionAction(
                            icon = if (soloGroup != null) Icons.Rounded.Folder else Icons.Rounded.PlayArrow,
                            label = if (soloGroup != null) "打开" else "播放",
                            enabled = canAct && selectedCount == 1,
                        ) {
                            when {
                                soloVideo != null -> {
                                    selection.exit()
                                    startPlayback(navController, soloVideo)
                                }
                                soloGroup != null -> {
                                    selection.exit()
                                    navController.navigate(Routes.localFolder(soloGroup.key))
                                }
                            }
                        },
                        SelectionAction(Icons.Rounded.Info, "详情", enabled = canAct && selectedCount == 1) {
                            if (soloVideo != null) infoItem = soloVideo else if (soloGroup != null) infoFolder = soloGroup
                        },
                        SelectionAction(Icons.Rounded.Lock, "隐藏", enabled = canAct && selectedCount == 1) {
                            val item = soloVideo
                            val group = soloGroup
                            guarded {
                                val request = when {
                                    item != null -> VaultHide(listOf(item))
                                    group != null -> VaultHide(group.videos, group.name)
                                    else -> null
                                }
                                if (request != null) {
                                    when {
                                        !vaultConfig.enabled -> { pendingHide = request; vaultSetupOpen = true }
                                        !vaultUnlocked -> unlockThenHide = request
                                        else -> hideToVault(request) { text, block -> runBusyProgress(text, block) }
                                    }
                                }
                            }
                            selection.exit()
                        },
                        // 以下都在「更多」里：加入列表与分享是只读，其余会改动文件或保险箱
                        SelectionAction(
                            Icons.AutoMirrored.Rounded.FormatListBulleted,
                            "加入列表",
                            enabled = canAct && sheetVideos.isNotEmpty(),
                        ) {
                            // 目标由用户选，不默认进正在播放
                            pendingAdd = sheetVideos
                            selection.exit()
                        },
                        SelectionAction(Icons.Rounded.Share, "分享", enabled = canAct && soloVideo != null) {
                            soloVideo?.let { FileOps.share(context, it) }
                        },
                        SelectionAction(Icons.Rounded.DriveFileRenameOutline, "重命名", enabled = canAct && soloVideo != null) {
                            soloVideo?.let { guarded { renameItem = it } }
                        },
                        SelectionAction(Icons.Rounded.ContentCopy, "复制到…", enabled = canAct && soloVideo != null) {
                            soloVideo?.let { guarded { dirOp = it to true } }
                        },
                        SelectionAction(Icons.AutoMirrored.Rounded.DriveFileMove, "移动到…", enabled = canAct && soloVideo != null) {
                            soloVideo?.let { guarded { dirOp = it to false } }
                        },
                        SelectionAction(Icons.Rounded.DeleteOutline, "删除", enabled = canAct, danger = true) {
                            when {
                                // 单条/单文件夹走确认框：确认后才退出选择态，取消时保留选中项
                                soloVideo != null -> pendingDelete = soloVideo
                                soloGroup != null -> pendingFolderDelete = soloGroup
                                else -> {
                                    // 多选：同样先弹确认框，确认后才真正移入回收站
                                    val targets = selectedVideos + selectedGroups.flatMap { it.videos }
                                    if (targets.isNotEmpty()) pendingMultiDelete = targets
                                }
                            }
                        },
                    ) + listOfNotNull(
                        // 只清观看记录，不动视频文件；选中项里没有任何历史时置灰。
                        // 文件夹本身没有观看记录，只选中文件夹时这一项直接不出现，
                        // 而不是摆一个永远点不动的灰按钮。
                        if (selectedVideos.isEmpty()) {
                            null
                        } else {
                            SelectionAction(
                                Icons.Rounded.DeleteSweep,
                                "删除历史",
                                enabled = canAct && hasHistory,
                            ) {
                                val targets = selectedVideos
                                if (targets.isNotEmpty()) pendingHistoryDelete = targets
                            }
                        },
                    ),
                    moreSheetTitle = if (selectedCount == 1) {
                        soloVideo?.title ?: soloGroup?.name ?: "更多操作"
                    } else "已选择 $selectedCount 项",
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
        com.yaoahos.mypureplayer.ui.components.PullRefreshBox(
            isRefreshing = scanning,
            onRefresh = { scope.launch { Graph.local.scan() } },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (searchOpen) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClose = { searchOpen = false; query = "" },
                    )
                }

                when {
                    // 三种空态都套 ScrollableFill：空页面也要能下拉刷新。
                    // 之前它们是不可滚动的 Column，nestedScroll 收不到任何手势 ——
                    // 列表为空时下拉毫无反应，只能去点中间的按钮。
                    !granted -> ScrollableFill {
                        EmptyState(
                            icon = Icons.Rounded.Lock,
                            title = "需要视频读取权限",
                            message = "MyPurePlayer 只读取设备上的视频文件，不会上传任何数据。",
                            actionLabel = "授予权限",
                            onAction = { askPermission() },
                        )
                    }

                    // 首扫与下拉刷新统一交给 PullRefreshBox 顶部的转圈，
                    // 不再在这里另画一个居中转圈，避免「上面一个、中间一个」两个刷新提示。
                    videos.isEmpty() -> ScrollableFill {
                        EmptyState(
                            icon = Icons.Rounded.Movie,
                            title = "没有找到视频",
                            // 不给按钮：这一页的空态本身就能下拉刷新（外层 PullRefreshBox +
                            // ScrollableFill），按钮只是把同一个动作抄了一遍，还占掉空态的视觉重心。
                            message = "把视频放进手机的影片/相册目录后，下拉即可重新扫描。",
                        )
                    }

                    visibleFolders.isEmpty() && visibleVideos.isEmpty() -> ScrollableFill {
                        // 分两种：搜索没命中 / 这个文件夹本来就是空的。
                        // 统一写"换个关键词试试"会让空文件夹看起来像"东西丢了"。
                        val searching = query.isNotBlank()
                        EmptyState(
                            icon = if (searching) Icons.Rounded.Search else Icons.Rounded.CreateNewFolder,
                            title = if (searching) "没有匹配的内容" else "这个文件夹是空的",
                            message = if (searching) "换个关键词试试。" else "这里的视频可能已被移动或删除。",
                        )
                    }

                    gridMode -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = adaptiveMediaGridMinSize()),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = PageMetrics.HPad, end = PageMetrics.HPad, top = 8.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 「文件夹 / 视频」由 browseMode 决定，同一时刻只铺一种，不加分组标题。
                        items(visibleFolders, key = { "d${it.key}" }) { group ->
                            MediaFolderGridCard(
                                name = group.name,
                                subtitle = "${group.videos.size} 个视频",
                                onClick = {
                                    if (selection.active) selection.toggle("d:${group.key}")
                                    else openFolder(group.key)
                                },
                                onLongClick = { selection.enter("d:${group.key}") },
                                selected = selection.contains("d:${group.key}"),
                                selecting = selection.active,
                            )
                        }
                        items(visibleVideos, key = { it.id }) { item ->
                            VideoGridCard(
                                item = item,
                                onClick = {
                                    if (selection.active) selection.toggle("v:${item.id}")
                                    else {
                                        playFromFocus(item) { startPlayback(navController, item) }
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
                        // 同宫格视图：文件夹 / 视频由 browseMode 决定，无需分组标题。
                        items(visibleFolders, key = { "d${it.key}" }) { group ->
                            ListRowContainer {
                                MediaFolderRow(
                                    name = group.name,
                                    subtitle = "${group.videos.size} 个视频",
                                    // 与网格卡片一致显示视频数量；副标题与标题作为一组相对图标整体居中。
                                    showSubtitle = true,
                                    onClick = {
                                        if (selection.active) selection.toggle("d:${group.key}")
                                        else openFolder(group.key)
                                    },
                                    onLongClick = { selection.enter("d:${group.key}") },
                                    selected = selection.contains("d:${group.key}"),
                                    selecting = selection.active,
                                )
                            }
                        }
                        items(visibleVideos, key = { it.id }) { item ->
                            ListRowContainer {
                                VideoListRow(
                                    item = item,
                                    onClick = {
                                        if (selection.active) selection.toggle("v:${item.id}")
                                        else {
                                            playFromFocus(item) { startPlayback(navController, item) }
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

            busyText?.let { text ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            // 与全站弹窗同一块玻璃面板。早先这里用 panelStrong（近实色白片），
                            // 扫描/导入时这块浮层比其它弹窗白一大截，像另一套 UI。
                            .appDialogSurface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(PageMetrics.PanelRadius),
                                elevation = 8.dp,
                            )
                            .padding(horizontal = 28.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val progress = busyProgress
                        if (progress == null) {
                            CircularProgressIndicator(Modifier.padding(2.dp))
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            // 有条形进度时不再叠一个转圈：标题 → 正在处理的文件 → 进度条 → 百分比，
                            // 与保险箱页解密、移出的进度弹窗保持同一套信息顺序。
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                            busyDetail?.let { detail ->
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                            com.yaoahos.mypureplayer.ui.components.WatchProgressBar(
                                progress = { progress },
                                modifier = Modifier.width(200.dp),
                            )
                            Text(
                                "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    }

    // ------------------------------------------------------------ 面板与对话框

    infoFolder?.let { group ->
        FolderInfoDialog(
            name = group.name,
            location = group.videos.firstOrNull()?.localPath
                ?.substringBeforeLast('/').orEmpty(),
            videoCount = group.videos.size,
            totalSize = group.videos.sumOf { it.sizeBytes },
            source = "本地",
            onDismiss = { infoFolder = null },
        )
    }

    renameItem?.let { item ->
        RenameDialog(
            item = item,
            onDismiss = { renameItem = null },
            onConfirm = { newName ->
                renameItem = null
                runBusy("正在重命名…") {
                    val fresh = Graph.local.freshById(item.id) ?: item
                    FileOps.rename(context, fresh, newName).fold(
                        onSuccess = { "已重命名为 $newName" },
                        onFailure = { it.message ?: "重命名失败" },
                    )
                }
            },
        )
    }

    infoItem?.let { item ->
        MediaInfoDialog(item = item, onDismiss = { infoItem = null })
    }

    dirOp?.let { (item, isCopy) ->
        DirPickerDialog(
            title = if (isCopy) "复制到" else "移动到",
            candidates = FileOps.knownVideoDirs(videos),
            onDismiss = { dirOp = null },
            onPick = { dir ->
                dirOp = null
                runBusy(if (isCopy) "正在复制…" else "正在移动…") {
                    val fresh = Graph.local.freshById(item.id) ?: item
                    val result = if (isCopy) FileOps.copyTo(context, fresh, dir)
                    else FileOps.moveTo(context, fresh, dir)
                    result.fold(
                        onSuccess = { if (isCopy) "复制完成" else "移动完成" },
                        onFailure = { it.message ?: "操作失败" },
                    )
                }
            },
        )
    }

    unlockThenHide?.let { request ->
        VaultUnlockDialog(
            title = "解锁后隐藏",
            onDismiss = {
                unlockThenHide = null
                // 解锁后又不隐藏了：这次认证没有产生任何动作，不该留下解锁会话
                Graph.vault.lock()
            },
            onUnlocked = {
                unlockThenHide = null
                hideToVault(request) { text, block -> runBusyProgress(text, block) }
            },
        )
    }

    if (vaultSetupOpen) {
        VaultSetupDialog(
            onDismiss = { vaultSetupOpen = false; pendingHide = null },
            onDone = {
                vaultSetupOpen = false
                val request = pendingHide
                pendingHide = null
                if (request != null && request.items.isNotEmpty()) {
                    hideToVault(request) { text, block -> runBusyProgress(text, block) }
                }
            },
        )
    }

    if (sortSheetOpen) {
        // 「文件夹 / 视频」浏览方式只在首页层级有意义：进到某个文件夹后列表里全是该目录
        // 的视频，没有文件夹可切，面板里再摆一个浏览方式是无效选项。
        val inFolder = currentFolderKey != null
        SortLayoutSheet(
            onDismiss = {
                // 取消（含点遮罩/按返回键）要把 onPreview 的实时改动全部回滚到 opening 快照；
                // 只对"没点确定"生效——确定时 onApply 已先清空 sortDraft，这里不会覆盖。
                sortDraft?.let { draft ->
                    gridMode = draft.grid
                    sortName = draft.key
                    ascending = draft.ascending
                    browseMode = draft.browseMode
                }
                sortDraft = null
                sortSheetOpen = false
            },
            initialGrid = gridMode,
            initialSort = sortKey,
            initialAscending = ascending,
            entries = listOf(SortKey.NAME, SortKey.DATE, SortKey.DURATION, SortKey.SIZE),
            browseMode = if (inFolder) null else browseMode,
            onPreviewBrowseMode = { mode -> browseMode = mode },
            onApplyBrowseMode = { mode ->
                browseMode = mode
                // 「媒体浏览方式」只在首页出现；若确实在文件夹目的地里被切到「视频」，
                // 用 pop 退回首页，而不是改页内状态（目录层级已交给 NavHost）。
                if (mode == 2 && currentFolderKey != null) navController.popBackStack()
            },
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
                scope.launch {
                    Graph.settings.setLocalLayout(grid, key.name, asc, browseMode)
                }
            },
        )
    }

    pendingDelete?.let { item ->
        ConfirmDialog(
            title = "删除视频",
            message = "确定把「${item.fileName}」移入回收站吗？可以在「更多 → 回收站」恢复。",
            confirmText = "移入回收站",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                selection.exit()
                guarded {
                    runBusy("正在移入回收站…") {
                        val fresh = Graph.local.freshById(item.id) ?: item
                        val existing = fresh.localPath?.let { File(it).exists() } == true
                        if (!existing) {
                            // 路径已失效：把陈旧条目从列表摘掉，并说明列表已刷新
                            Graph.forgetGone(listOf(fresh))
                            "文件已不在原位置，列表已刷新"
                        } else {
                            Graph.trash.moveToTrash(fresh).fold(
                                onSuccess = {
                                    // 文件已经不在原处：历史和播放列表里的这一条同时摘掉
                                    Graph.forgetGone(listOf(fresh))
                                    "已移入回收站"
                                },
                                onFailure = { it.message ?: "删除失败" },
                            )
                        }
                    }
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingMultiDelete?.let { targets ->
        ConfirmDialog(
            title = "删除视频",
            message = "已选中的 ${targets.size} 个视频将移入回收站，可以在「更多 → 回收站」恢复。",
            confirmText = "移入回收站",
            destructive = true,
            onConfirm = {
                pendingMultiDelete = null
                selection.exit()
                guarded {
                    scope.launch {
                        // 一次批量搬运、只落盘一次：逐个调 moveToTrash 时每搬一个都要
                        // 全量重写 trash.json 并推一次列表，50 个视频就是 50 次写盘。
                        val result = Graph.trash.moveManyToTrash(targets)
                        val movedPaths = result.moved.mapTo(HashSet()) { it.originalPath }
                        val movedItems = targets.filter { it.localPath in movedPaths }
                        // 只摘成功的：失败的还躺在原处，历史和播放列表也该继续留着
                        Graph.forgetGone(movedItems)
                        val failed = targets.size - result.moved.size
                        when {
                            result.moved.isEmpty() -> Notice.error(result.firstError ?: "删除失败")
                            failed > 0 -> Notice.error(
                                "已移入回收站 ${result.moved.size} 个，" +
                                    "$failed 个失败：${result.firstError ?: "请检查存储权限"}",
                            )
                            else -> Notice.success("已将 ${result.moved.size} 个视频移入回收站")
                        }
                    }
                }
            },
            onDismiss = { pendingMultiDelete = null },
        )
    }

    pendingHistoryDelete?.let { targets ->
        ConfirmDialog(
            title = "删除观看记录",
            message = "已选中的 ${targets.size} 个视频的观看记录将被删除，不会删除视频文件。",
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

    pendingFolderDelete?.let { group ->
        ConfirmDialog(
            title = "删除文件夹里的视频",
            message = "「${group.name}」里的 ${group.videos.size} 个视频会移入回收站，可以在「更多 → 回收站」恢复（支持多选）。" +
                "文件夹里的其它文件（字幕、图片等）留在原处不动。",
            confirmText = "移入回收站",
            destructive = true,
            onConfirm = {
                pendingFolderDelete = null
                selection.exit()
                guarded {
                    runBusy("正在移入回收站…") {
                        // 授权往返期间列表可能已经变了，按 key 重新取这一组，
                        // 而不是拿对话框里记住的旧 FolderGroup 去删。
                        val fresh = Graph.local.freshFolder(group.key) ?: group
                        val existing = fresh.videos.any { it.localPath?.let { p -> File(p).exists() } == true }
                        if (!existing) {
                            Graph.forgetGone(fresh.videos)
                            "文件夹里的视频已不在原位置，列表已刷新"
                        } else {
                            // 只搬视频、不搬目录：目录里往往还混着字幕/封面等非视频文件，
                            // 整体搬走等于把它们一起删了。搬空之后原目录若已空，才把它删掉。
                            // 回收站里是**一条条视频**（不是一条文件夹记录），恢复时各自回原位。
                            val result = Graph.trash.moveFolderToTrash(fresh.videos)
                            // 只清真正搬走的那些（部分失败时留在原处的仍可继续看）
                            val movedPaths = result.moved.mapTo(HashSet()) { it.originalPath }
                            Graph.forgetGone(fresh.videos.filter { it.localPath in movedPaths })
                            val failed = fresh.videos.size - result.moved.size
                            when {
                                result.moved.isEmpty() -> result.firstError ?: "删除失败"
                                failed > 0 -> "已移入回收站 ${result.moved.size} 个，" +
                                    "$failed 个失败：${result.firstError ?: "请检查存储权限"}"
                                else -> "已把「${fresh.name}」里的 ${result.moved.size} 个视频移入回收站"
                            }
                        }
                    }
                }
            },
            onDismiss = { pendingFolderDelete = null },
        )
    }

    if (permissionDialog) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.alertDialogGlassSurface(),
            tonalElevation = 0.dp,
            onDismissRequest = { permissionDialog = false; pendingPermissionOp = null },
            title = { Text("需要「所有文件访问权限」") },
            text = {
                Text(
                    "重命名、移动、删除本地视频需要直接读写文件。安卓 11 起，应用必须获得该权限才能操作其它应用创建的媒体文件。" +
                        "开启后仅用于本机视频文件管理，不会上传任何数据。",
                )
            },
            confirmButton = {
                GlassDialogButton(onClick = {
                    permissionDialog = false
                    runCatching { context.startActivity(FileOps.allFilesSettingsIntent(context)) }
                }, tone = GlassButtonTone.Accent) { Text("去开启") }
            },
            dismissButton = {
                GlassDialogButton(onClick = { permissionDialog = false; pendingPermissionOp = null }) { Text("取消") }
            },
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

/** 一次「隐藏到保险箱」请求：[group] 非空表示整目录隐藏，用来在保险箱里还原出文件夹 */
private data class VaultHide(val items: List<VideoItem>, val group: String = "")

/**
 * 把视频加密移入保险箱。抽成扩展函数是为了让长按面板与后续的文件夹批量隐藏共用同一套逻辑。
 */
private fun hideToVault(
    request: VaultHide,
    runBusy: (String, suspend ((Float, String?) -> Unit) -> String) -> Unit,
) {
    if (request.items.isEmpty()) return
    runBusy("正在加密移入保险箱…") { report ->
        try {
            Graph.vault.add(request.items, group = request.group) { index, total, p ->
                // 多文件时总进度按「第几个 + 这个文件的百分比」折算，副标题给出文件名，
                // 否则一串大文件连着加密时，界面只有百分比没有「在动哪一个」。
                val count = total.coerceAtLeast(1)
                val overall = ((index + p) / count).coerceIn(0f, 1f)
                val name = request.items.getOrNull(index)?.fileName
                report(overall, if (total > 1 && name != null) "第 ${index + 1}/$total 个 · $name" else name)
            }.fold(
                onSuccess = { count ->
                    // 加密成功后明文已删除，历史和播放列表里的对应条目一并摘掉
                    Graph.forgetGone(request.items)
                    "已将 $count 个视频移入保险箱"
                },
                onFailure = { it.message ?: "移入失败" },
            )
        } finally {
            // 在本地页解锁只服务于这一次加密动作，做完就把数据密钥从内存里收掉。
            // 否则「刚隐藏过视频」会变成之后进入保险箱免认证的通行证。
            Graph.vault.lock()
        }
    }
}

/**
 * 播放本地视频时，自动把同目录下的全部视频排进播放列表，并从点击的这一个开始播。
 */
fun startPlayback(navController: NavHostController, item: VideoItem) {
    val folderVideos = when (item.source) {
        MediaSource.LOCAL -> Graph.local.videosInFolder(item.folderKey)
        else -> emptyList()
    }
    val list = folderVideos.ifEmpty { listOf(item) }
    val index = list.indexOfFirst { it.id == item.id }
    // 目录列表里已经没有这一条（扫描之后又被删掉）时只播它自己：
    // 按 0 起播会默默换成同目录的第一条，用户看到的是「点 A 播了 B」。
    Playback.playChecked(if (index >= 0) list else listOf(item), index.coerceAtLeast(0)) {
        navController.navigate(Routes.PLAYER)
    }
}

/** 自动扫描的最小间隔：来回切页时不必每次都查一遍 MediaStore */
private const val AUTO_SCAN_MIN_GAP_MS = 1_500L
/** 等页面转场落位后再做重活（扫描 / 列表重建） */
private const val TRANSITION_SETTLE_MS = 320L
private var lastAutoScanAt = 0L
