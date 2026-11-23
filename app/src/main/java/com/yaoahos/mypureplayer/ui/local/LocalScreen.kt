package com.yaoahos.mypureplayer.ui.local

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
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
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.appGlass
import com.yaoahos.mypureplayer.ui.components.appGlassSurface
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
fun LocalScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val videos by Graph.local.videos.collectAsStateWithLifecycle()
    val scanning by Graph.local.scanning.collectAsStateWithLifecycle()
    val vaultConfig by Graph.vault.config.collectAsStateWithLifecycle()
    // 保险箱是否已解锁（数据密钥在内存里）。锁定状态下直接隐藏必然失败，
    // 必须先让他输一次密码 —— 见下方 onHide 分支。
    val vaultUnlocked by Graph.vault.unlocked.collectAsStateWithLifecycle()

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var gridMode by rememberSaveable { mutableStateOf(true) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(SortKey.DATE.name) }
    var ascending by rememberSaveable { mutableStateOf(false) }
    // null = 全部视频；非 null = 已进入某个文件夹
    var currentFolderKey by rememberSaveable { mutableStateOf<String?>(null) }
    // 1 文件夹 / 2 视频：本地视频的「文件夹 / 视频」浏览方式切换（2026-11-22 按用户要求恢复）
    var browseMode by rememberSaveable { mutableIntStateOf(1) }

    // 本地进文件夹用页内状态切换（不走 NavHost），为了让转场与网络页进入 SMB 目录的
    // 水平滑动一致，这里用位移动画模拟同样的 300ms 整屏滑动。
    //
    // 关键：起始位移必须在**组合期**就定好（remember 的初值），不能放到 LaunchedEffect 里 snap。
    // LaunchedEffect 要等首帧画完才跑，于是旧写法会「先画一帧已经到位的新页面，
    // 再瞬移到屏外滑回来」—— 用户看到的就是返回首页时整页左右晃一下。
    // 用 remember(currentFolderKey) 重建 Animatable：key 一变，第一帧就渲染在屏外，
    // 之后 animateTo(0) 只是单向滑入，不再有回弹。
    var lastFolderKey by remember { mutableStateOf(currentFolderKey) }
    val slideDistance = remember { context.resources.displayMetrics.widthPixels.toFloat() }
    val folderSlide = remember(currentFolderKey) {
        folderSlideFor(lastFolderKey, currentFolderKey, slideDistance)
    }
    LaunchedEffect(currentFolderKey) {
        lastFolderKey = currentFolderKey
        // 页内文件夹滑动刻意保留弹簧（PageMotion.PageSpringF）——这是用户从头到尾
        // 认可的手感，参数不要动。注意它与 NavHost 页面转场已不是同一条曲线：
        // 页面转场 2026-11-22 起定稿为 tween(300, FastOutSlowIn)
        // 两层视差（见 PageMotion.kt），本条是仅存的页内例外。
        folderSlide.animateTo(0f, com.yaoahos.mypureplayer.ui.PageMotion.PageSpringF)
    }

    // 在文件夹内按系统返回键先退回上一层文件夹，而不是直接退出应用
    BackHandler(enabled = currentFolderKey != null) { currentFolderKey = null }

    // 搜索框的返回同样优先于"退出应用"：先收搜索框并清空关键词。
    // 必须写在上面那条 BackHandler **之后**——同一时刻有多个启用时，
    // 后组合的那个先拿到事件，否则会先去退文件夹（或直接退到桌面）。
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
    }

    // 下钻进文件夹、或进入多选态时，壳层常驻底栏必须让位给页面自己的操作栏。
    // 「文件夹 / 视频」浏览方式放在「排列与排序」面板里切换（browseMode）。
    var sortDraft by remember { mutableStateOf<MediaSortDraft?>(null) }

    // 长按只进入选择态；动作从顶栏 / 底部四宫格操作栏发起。
    val selection = remember { ListSelection() }

    HideShellBottomBar(selection.active || currentFolderKey != null)

    BackHandler(enabled = selection.active) { selection.exit() }
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
    var pendingDelete by remember { mutableStateOf<VideoItem?>(null) }
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
        // 首次进入静默扫描，列表为空时由 EmptyState 兜底，不需要闪一下转圈
        if (granted) autoScan() else askPermission()
        Graph.vault.load()
        Graph.trash.load()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (FileOps.hasAllFiles(context)) resumePendingPermissionOp()
        // 静默扫描：切回本页时若内容没变就不闪转圈、不重组整页
        if (!granted) return@LifecycleEventEffect
        scope.launch {
            // 让转场先跑完（页面弹簧约 320ms 落位）
            delay(TRANSITION_SETTLE_MS)
            autoScan()
        }
    }

    val durationVersion by Thumbnails.durationVersion.collectAsStateWithLifecycle()
    val durationTick = if (sortKey == SortKey.DURATION) durationVersion else 0
    val filtered = remember(videos, query, sortKey, ascending, durationTick) {
        val base = videos.filter { item ->
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

    val groups = remember(videos) { Graph.local.folders() }
    // v:/d: 前缀区分视频与文件夹，避免两者 id 恰好相同
    val selectedVideos = remember(videos, selection.ids) {
        videos.filter { selection.contains("v:${it.id}") }
    }
    val selectedGroups = remember(groups, selection.ids) {
        groups.filter { selection.contains("d:${it.key}") }
    }
    val selectedCount = selectedVideos.size + selectedGroups.size
    val soloVideo = selectedVideos.singleOrNull()
    val soloGroup = selectedGroups.singleOrNull()
    val scopedVideos = remember(filtered, currentFolderKey) {
        if (currentFolderKey == null) filtered
        else filtered.filter { it.folderKey == currentFolderKey }
    }
    val currentFolderName = groups.firstOrNull { it.key == currentFolderKey }?.name.orEmpty()
    // browseMode=1（文件夹）：只列文件夹；进入某个文件夹后只列该文件夹的视频。
    // browseMode=2（视频）：铺平列出全部视频，不显示文件夹、退出文件夹层级。
    val visibleFolders = remember(groups, query, browseMode, currentFolderKey) {
        if (currentFolderKey != null || browseMode == 2) emptyList()
        else groups.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }
    val visibleVideos = if (browseMode == 1 && currentFolderKey == null) emptyList() else scopedVideos

    fun runBusy(text: String, block: suspend () -> String) {
        scope.launch {
            busyText = text
            val result = runCatching { block() }
            busyText = null
            // 显式语气，不再靠中文关键词猜；异常即失败，成功结果统一 success
            result.fold(
                onSuccess = { Notice.success(it) },
                onFailure = { Notice.error(it.message ?: "操作失败") },
            )
            Graph.local.scan()
        }
    }

    MainScaffold(
        // 页内下钻只移动**内容区**：底栏是壳层的常驻元素，不参与这次位移。
        // 一个永远不动的底栏，配上一条自己滑的页面，任何时刻都不会出现
        // "底栏先到、页面后到"的错位。
        modifier = Modifier.graphicsLayer { translationX = folderSlide.value },
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
                    onBack = if (currentFolderKey == null) null else ({ currentFolderKey = null }),
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
                                    currentFolderKey = soloGroup.key
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
                                        else -> hideToVault(request) { text, block -> runBusy(text, block) }
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
                                    // 多选直接删除，已真正执行，随即退出选择态
                                    val targets = selectedVideos + selectedGroups.flatMap { it.videos }
                                    if (targets.isNotEmpty()) {
                                        guarded {
                                            scope.launch {
                                                targets.forEach { runCatching { Graph.trash.moveToTrash(it) } }
                                                Graph.forgetGone(targets)
                                                Notice.success("已将 ${targets.size} 个视频移入回收站")
                                            }
                                        }
                                    }
                                    selection.exit()
                                }
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
                            message = "把视频放进手机的影片/相册目录后，下拉即可重新扫描。",
                            actionLabel = "重新扫描",
                            onAction = { scope.launch { Graph.local.scan() } },
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
                                    else currentFolderKey = group.key
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
                                    else startPlayback(navController, item)
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
                                        else currentFolderKey = group.key
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
                                        else startPlayback(navController, item)
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

            busyText?.let { text ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .appGlassSurface(
                                androidx.compose.foundation.shape.RoundedCornerShape(PageMetrics.PanelRadius),
                                strong = true,
                                elevation = 8.dp,
                            )
                            .padding(horizontal = 28.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.padding(2.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
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
                hideToVault(request) { text, block -> runBusy(text, block) }
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
                    hideToVault(request) { text, block -> runBusy(text, block) }
                }
            },
        )
    }

    if (sortSheetOpen) {
        SortLayoutSheet(
            onDismiss = {
                // onPreview 已实时应用选择；不能再用打开面板时的旧快照覆盖，
                // 否则点遮罩关闭会撤销刚选的排序/布局，看起来像「排序没生效」。
                sortDraft = null
                sortSheetOpen = false
            },
            initialGrid = gridMode,
            initialSort = sortKey,
            initialAscending = ascending,
            entries = listOf(SortKey.NAME, SortKey.DATE, SortKey.DURATION, SortKey.SIZE),
            browseMode = browseMode,
            onPreviewBrowseMode = { mode -> browseMode = mode },
            onApplyBrowseMode = { mode ->
                browseMode = mode
                // 只有切到「视频」（铺平全部视频）才需要退出文件夹；
                // 在文件夹里只改列表/网格、排序时继续留在当前文件夹。
                if (mode == 2) currentFolderKey = null
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

    pendingFolderDelete?.let { group ->
        ConfirmDialog(
            title = "删除文件夹里的视频",
            message = "「${group.name}」里的 ${group.videos.size} 个视频会移入回收站，可以在「更多 → 回收站」整体恢复。" +
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
                            // 整体搬走等于把它们一起删了。回收站里会建一个同名文件夹装这些视频，
                            // 全部搬完之后原目录若已空，才把它删掉。
                            Graph.trash.moveFolderToTrash(
                                videos = fresh.videos,
                                sizeBytes = fresh.videos.sumOf { it.sizeBytes },
                            ).fold(
                                onSuccess = {
                                    // 只清真正搬走的那些（部分失败时留在原处的仍可继续看）
                                    Graph.forgetGone(fresh.videos)
                                    "已把「${fresh.name}」里的 ${fresh.videos.size} 个视频移入回收站"
                                },
                                onFailure = { it.message ?: "删除失败" },
                            )
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
            containerColor = appGlass().panelStrong,
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
                TextButton(onClick = {
                    permissionDialog = false
                    runCatching { context.startActivity(FileOps.allFilesSettingsIntent(context)) }
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { permissionDialog = false; pendingPermissionOp = null }) { Text("取消") }
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
    runBusy: (String, suspend () -> String) -> Unit,
) {
    if (request.items.isEmpty()) return
    runBusy("正在加密移入保险箱…") {
        try {
            Graph.vault.add(request.items, group = request.group).fold(
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
    val index = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
    Playback.play(list, index)
    navController.navigate(Routes.PLAYER)
}

/**
 * 文件夹切换动画的起点。
 *
 * 只有在**组合期**就把起点定好，第一帧才不会被画出「新内容已经到位」的样子。
 *  - key 没变（首次组合）：0，不做动画；
 *  - 进入文件夹：从右侧屏外整屏滑入（与 NavHost 的 forwardEnter 同参）；
 *  - 返回首页：从左侧 **1/3 屏**处补满（与 NavHost 的 popEnter 同参）。
 *
 * 这条位移由本页自己的 `Animatable` 驱动，与 NavHost 那条路径互不相干 —— 也正因为如此，
 * 它是 2026-11-21 那轮错误改造里唯一没被波及、始终正确的返回路径
 * （当时 NavHost 的返回被改成"不做转场"，用户一眼看出只有本地视频还是原样）。
 *
 * 返回为什么不是整屏：网络页从 SMB 目录退回时，上一层只退了 1/3、这里从 -1/3 补满，
 * 读起来是"上面那一层还在，我只是盖回来"。整屏滑入会让同一件事在两处有两种速度感。
 */
private fun <T> folderSlideFor(
    previous: T?,
    next: T?,
    distance: Float,
): Animatable<Float, AnimationVector1D> = when {
    previous == next -> Animatable(0f)
    next != null -> Animatable(distance)
    else -> Animatable(-distance / 3f)
}

/** 自动扫描的最小间隔：来回切页时不必每次都查一遍 MediaStore */
private const val AUTO_SCAN_MIN_GAP_MS = 1_500L
/** 等页面转场落位后再做重活（扫描 / 列表重建） */
private const val TRANSITION_SETTLE_MS = 320L
private var lastAutoScanAt = 0L
