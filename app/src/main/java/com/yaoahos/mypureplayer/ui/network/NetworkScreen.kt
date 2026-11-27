package com.yaoahos.mypureplayer.ui.network

import com.yaoahos.mypureplayer.ui.components.GlassDialogButton
import com.yaoahos.mypureplayer.ui.components.GlassTextField
import com.yaoahos.mypureplayer.ui.components.GlassButtonTone
import com.yaoahos.mypureplayer.ui.HideShellBottomBar

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.yaoahos.mypureplayer.ui.components.collectPressedWithTap
import com.yaoahos.mypureplayer.ui.components.flashClick
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.SmbClient
import com.yaoahos.mypureplayer.data.SmbInput
import com.yaoahos.mypureplayer.data.SmbErrors
import com.yaoahos.mypureplayer.data.SmbServer
import com.yaoahos.mypureplayer.data.SortKey
import com.yaoahos.mypureplayer.ui.MainScaffold
import com.yaoahos.mypureplayer.ui.MainTab
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.GlassFab
import com.yaoahos.mypureplayer.ui.components.FabInsets
import com.yaoahos.mypureplayer.ui.components.ConfirmDialog
import com.yaoahos.mypureplayer.ui.components.appGlass
import com.yaoahos.mypureplayer.ui.components.EmptyState
import com.yaoahos.mypureplayer.ui.components.ListSelection
import com.yaoahos.mypureplayer.ui.components.MediaSortDraft
import com.yaoahos.mypureplayer.ui.components.SelectionAction
import com.yaoahos.mypureplayer.ui.components.SelectionActionBar
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.launchUi
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.PasswordEye
import com.yaoahos.mypureplayer.ui.components.PullRefreshBox
import com.yaoahos.mypureplayer.ui.components.ScrollableFill
import com.yaoahos.mypureplayer.ui.components.SearchField
import com.yaoahos.mypureplayer.ui.components.SortLayoutSheet
import com.yaoahos.mypureplayer.ui.components.SelectionBadge
import com.yaoahos.mypureplayer.ui.components.adaptiveMediaGridMinSize
import com.yaoahos.mypureplayer.ui.components.mediaCardOutline
import com.yaoahos.mypureplayer.ui.components.selectionCardOutline
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val servers by Graph.servers.servers.collectAsStateWithLifecycle()
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(
        // 首帧就要正确的排列方式：默认值是「网格」，用户若设成列表，
        // 先画网格再切成列表会看到整页重排
        initialValue = Graph.settings.currentOrNull() ?: com.yaoahos.mypureplayer.data.AppSettings(),
    )

    var sheetOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SmbServer?>(null) }
    // 删除先记录目标，弹确认框；单条与批量共用同一条路径
    var pendingDelete by remember { mutableStateOf<List<SmbServer>?>(null) }
    var testing by remember { mutableStateOf<SmbServer?>(null) }
    // 长按进入选择态；动作只在顶栏与底部操作栏出现
    val selection = remember { ListSelection() }

    HideShellBottomBar(selection.active)
    val selectedServers = remember(servers, selection.ids) {
        servers.filter { selection.contains(it.id.toString()) }
    }
    val soloServer = selectedServers.singleOrNull()
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(settings.networkSort) }
    var ascending by rememberSaveable { mutableStateOf(settings.networkAscending) }
    var gridMode by rememberSaveable { mutableStateOf(settings.networkGrid) }
    LaunchedEffect(settings.networkSort, settings.networkAscending, settings.networkGrid) {
        sortName = settings.networkSort
        ascending = settings.networkAscending
        gridMode = settings.networkGrid
    }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var sortDraft by remember { mutableStateOf<MediaSortDraft?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    // 共享卡片防连点：flashClick 的延迟反馈不拦截重复点击，快速连点同一张卡片会
    // 连续 navigate 同一个 smb 目的地，压出一叠相同页面（表现为"重复进入同一个文件夹"）。
    // 这里在导航层加闸门，600ms 大于转场时长、短于用户再次主动点击的间隔。
    var navigationPending by remember { mutableStateOf(false) }
    LaunchedEffect(navigationPending) {
        if (navigationPending) {
            kotlinx.coroutines.delay(600)
            navigationPending = false
        }
    }
    // 正在预检连接的共享：同时充当第二次防连点（预检可能跑十几秒）
    var connecting by remember { mutableStateOf<SmbServer?>(null) }
    // 转圈再延迟一点出现：局域网里预检常常一两百毫秒就完成，圈刚画出来人就跳进去了，
    // 看起来像闪了一下。超过阈值还没进才显示，快到看不见的情况干脆不画。
    var ringVisible by remember { mutableStateOf(false) }
    LaunchedEffect(connecting) {
        ringVisible = false
        if (connecting == null) return@LaunchedEffect
        kotlinx.coroutines.delay(CONNECTING_RING_DELAY_MS)
        ringVisible = true
    }

    /**
     * 打开共享前先确认「它现在真的能打开」。
     *
     * 旧实现直接 navigate：NAS 关机、换了网络、密码被改了的时候，用户会先看到一个
     * 完整的空页面，页面中间再竖一条红色错误——那看起来像应用自己坏了，而且还得手动返回。
     * 现在把这一次失败留在列表页，用顶部提示条说明原因。
     *
     * 预检本身就是列根目录，结果进 SmbClient 的目录缓存，真正进入时能直接出列表，
     * 不会多花一次网络往返。
     */
    fun openServer(server: SmbServer) {
        if (navigationPending || connecting != null) return
        navigationPending = true
        // 有缓存（5 分钟内进过）就直接进：旧列表能看，不该被强制预检挡在门外。
        if (SmbClient.cachedList(server, server.rootPath) != null) {
            navController.navigate(Routes.smb(server.id, server.rootPath))
            return
        }
        connecting = server
        scope.launchUi {
            // 超时按「最坏冷连接」给：SMB 握手 20s + 认证 + 列一次目录。
            // 比共享页面里的 70s 短，是因为这里没有任何内容可看，等太久只会让人以为卡死。
            val probe = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(CONNECT_PROBE_TIMEOUT_MS) {
                    SmbClient.list(server, server.rootPath)
                }
            }
                // 取消不是失败：切标签 / 离开网络页时协程被取消，runCatching 会把它当成
                // 一次异常，接着就在别的页面上弹「连接失败」。这里原样抛出。
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            connecting = null
            when {
                probe.isFailure -> {
                    navigationPending = false
                    Notice.error("「${server.name}」${SmbErrors.headline(server, probe.exceptionOrNull()!!)}")
                }
                probe.getOrNull() == null -> {
                    navigationPending = false
                    Notice.error("「${server.name}」连接超时")
                }
                else -> {
                    // 预检可能跑了几秒，原来的 600ms 闸门早已复位；这里重新举起，
                    // 挡住转场期间的连点（否则会压出两层相同的页面）。
                    navigationPending = true
                    navController.navigate(Routes.smb(server.id, server.rootPath))
                }
            }
        }
    }

    suspend fun reload() {
        refreshing = true
        Graph.servers.load()
        refreshing = false
    }

    val sortKey = remember(sortName) {
        runCatching { SortKey.valueOf(sortName) }.getOrDefault(SortKey.NAME)
    }
    val filtered = remember(servers, query, sortKey, ascending) {
        val base = servers.filter {
            query.isBlank() ||
                it.name.contains(query, ignoreCase = true) ||
                it.host.contains(query, ignoreCase = true) ||
                it.share.contains(query, ignoreCase = true)
        }
        val comparator: Comparator<SmbServer> = when (sortKey) {
            SortKey.DATE -> compareBy { it.id }
            else -> compareBy { it.name.lowercase() }
        }
        base.sortedWith(if (ascending) comparator else comparator.reversed())
    }

    LaunchedEffect(Unit) { reload() }

    BackHandler(enabled = selection.active) { selection.exit() }

    // 搜索框的返回优先于退出页面：先收搜索框并清空关键词。
    // 必须写在上面那条 BackHandler 之后——多条同时启用时，后组合的先拿到事件。
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
    }

    // 网络页要做「目录里有没有视频」的递归探测，同样吃 SMB 往返延迟，
    // 停留期间保持 Wi-Fi 低延迟（离开自动释放）。
    val perfContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.yaoahos.mypureplayer.data.WifiPerf.acquire(perfContext)
        onDispose { com.yaoahos.mypureplayer.data.WifiPerf.release() }
    }

    MainScaffold(
        topBar = {
            if (selection.active) {
                PageTopBar(
                    title = "已选择 ${selectedServers.size} 项",
                    subtitle = "轻点勾选或取消 · 右上角可全选",
                    onBack = { selection.exit() },
                    backIcon = Icons.Rounded.Close,
                    backLabel = "退出选择",
                    actions = {
                        val allSelected = servers.isNotEmpty() && selectedServers.size == servers.size
                        IconButton(onClick = {
                            if (allSelected) selection.clear()
                            else selection.replaceAll(servers.map { it.id.toString() })
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
                    title = "网络视频",
                    subtitle = if (servers.isEmpty()) "添加 SMB 共享后即可直接播放" else "${servers.size} 个共享位置",
                    actions = {
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = {
                            sortDraft = MediaSortDraft(gridMode, sortName, ascending)
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
                val canAct = selectedServers.isNotEmpty()
                SelectionActionBar(
                    actions = listOf(
                        SelectionAction(Icons.Rounded.Cloud, "打开", enabled = canAct && soloServer != null) {
                            soloServer?.let { openServer(it) }
                            selection.exit()
                        },
                        SelectionAction(Icons.Rounded.NetworkCheck, "测试", enabled = canAct && soloServer != null) {
                            soloServer?.let { testing = it }
                            selection.exit()
                        },
                        SelectionAction(Icons.Rounded.Edit, "编辑", enabled = canAct && soloServer != null) {
                            if (soloServer != null) { editing = soloServer; sheetOpen = true }
                            selection.exit()
                        },
                        // 创建副本：地址、账号、共享目录一并带走，名称加「-副本」。
                        // 用于同一台 NAS 换不同目录/账号，不必从头再填一遍。
                        SelectionAction(Icons.Rounded.FileCopy, "创建副本", enabled = canAct) {
                            val targets = selectedServers.toList()
                            scope.launchUi {
                                targets.forEach { s -> Graph.servers.upsert(s.copy(id = 0L, name = "${s.name}-副本")) }
                                Notice.success(
                                    if (targets.size == 1) "已创建副本" else "已创建 ${targets.size} 个副本",
                                )
                            }
                            selection.exit()
                        },
                        // 删除：单条与批量都要先过确认框（此处只记录目标，不直接删）
                        SelectionAction(Icons.Rounded.DeleteOutline, "删除", enabled = canAct, danger = true) {
                            pendingDelete = selectedServers.toList()
                        },
                    ),
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
        PullRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launchUi { reload() } },
            modifier = Modifier.fillMaxSize(),
        ) {
        Column(Modifier.fillMaxSize()) {
            if (searchOpen) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onClose = { searchOpen = false; query = "" },
                    placeholder = "搜索共享名称或地址",
                )
            }
            if (filtered.isEmpty()) {
                // 空态也要能下拉：这一页的下拉是「重新加载共享列表」，
                // 空列表恰恰是最需要重载的时候。纯 Column 收不到 nestedScroll 手势。
                ScrollableFill {
                    EmptyState(
                        icon = Icons.Rounded.Cloud,
                        title = if (servers.isEmpty()) "还没有网络共享" else "没有匹配的共享",
                        message = if (servers.isEmpty()) {
                            "支持 SMB2 / SMB3。点右下角 + 添加 NAS 或电脑共享后，这里只展示视频文件。"
                        } else {
                            "换个关键词试试。"
                        },
                    )
                }
            } else if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = adaptiveMediaGridMinSize()),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = PageMetrics.HPad, end = PageMetrics.HPad, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.id }) { server ->
                        ServerGridCard(
                            server = server,
                            onClick = {
                                if (selection.active) selection.toggle(server.id.toString())
                                else flashClick { openServer(server) }
                            },
                            onLongClick = { selection.enter(server.id.toString()) },
                            selected = selection.contains(server.id.toString()),
                            selecting = selection.active,
                            connecting = ringVisible && connecting?.id == server.id,
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = PageMetrics.HPad, end = PageMetrics.HPad, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            onClick = {
                                if (selection.active) selection.toggle(server.id.toString())
                                else flashClick { openServer(server) }
                            },
                            onLongClick = { selection.enter(server.id.toString()) },
                            selected = selection.contains(server.id.toString()),
                            selecting = selection.active,
                            connecting = ringVisible && connecting?.id == server.id,
                        )
                    }
                }
            }
        }
        }
        // 顶栏第三个图标会被挤出屏幕（所有尺寸都点不到），「添加共享」改放悬浮按钮
        GlassFab(
            onClick = { editing = null; sheetOpen = true },
            contentDescription = "添加共享",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = FabInsets.margin, bottom = FabInsets.margin),
        )
        }
    }

    if (sheetOpen) {
        ServerEditSheet(
            initial = editing,
            onDismiss = { sheetOpen = false },
            onSaved = { sheetOpen = false },
        )
    }

    pendingDelete?.let { targets ->
        ConfirmDialog(
            title = if (targets.size == 1) "删除共享" else "删除共享位置",
            message = if (targets.size == 1) {
                "确定删除「${targets.first().name}」吗？已加入播放列表的条目不受影响。"
            } else {
                "选中的 ${targets.size} 个共享位置将被删除。已加入播放列表的条目不受影响。"
            },
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                scope.launchUi {
                    targets.forEach {
                        SmbClient.invalidate(it.id)
                        Graph.servers.delete(it.id)
                    }
                    Notice.success(
                        if (targets.size == 1) "已删除共享" else "已删除 ${targets.size} 个共享",
                    )
                    pendingDelete = null
                    selection.exit()
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (sortSheetOpen) {
        SortLayoutSheet(
            onDismiss = {
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
            entries = listOf(SortKey.NAME, SortKey.DATE),
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
                scope.launchUi { Graph.settings.setNetworkLayout(grid, key.name, asc) }
            },
        )
    }

    testing?.let { server ->
        // 测试期间不弹进度框，测试结束后只给一条结果提示
        LaunchedEffect(server.id) {
            val result = SmbClient.test(server)
            result.fold(
                onSuccess = { Notice.success("连接成功") },
                // 顶部只放一句结论；要看「为什么」就去编辑面板里点「测试连接」，那里有整句
                onFailure = { Notice.error("「${server.name}」${SmbErrors.headline(server, it)}") },
            )
            testing = null
        }
    }

}

/** 网络位置统一使用云图标，列表与宫格保持同一视觉 */
@Composable
private fun NetworkArtwork(size: androidx.compose.ui.unit.Dp) {
    Icon(
        imageVector = Icons.Rounded.Cloud,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(size),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    server: SmbServer,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean = false,
    selecting: Boolean = false,
    connecting: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedWithTap()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 手机上单列满宽也只有三百多 dp，图位过宽会把「地址 · 账号」挤掉一半。
        // 窄卡片用紧凑图位，宽卡片（平板 2~3 列）维持与视频行一致的图位；
        // 图标尺寸贴满图位高度，与宫格的「铺满」体量一致。
        val compact = maxWidth < 430.dp
        val slotWidth = if (compact) 68.dp else 132.dp
        val slotHeight = if (compact) 48.dp else 76.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (selecting) {
                        Modifier.selectionCardOutline(
                            selected = selected, selecting = true, pressed = pressed,
                        )
                    } else Modifier.mediaCardOutline(pressed)
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onClickLabel = if (selecting) "选中/取消选择" else "打开",
                    onLongClick = onLongClick,
                    onLongClickLabel = "更多操作",
                    role = Role.Button,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 与文件夹 / 视频列表行同一图位，图标裸展示、左侧严格对齐
            Box(
                Modifier.size(width = slotWidth, height = slotHeight),
                contentAlignment = Alignment.Center,
            ) {
                NetworkArtwork(size = slotHeight)
                if (selecting) {
                    SelectionBadge(
                        selected, Modifier.align(Alignment.TopEnd).padding(2.dp),
                    )
                }
                // 预检连接期间在图标右上角转圈：点完立刻有反馈，不用猜「是不是没点上」
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
            ) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // 只报地址与共享目录。账号属于登录信息，摆在列表上等于把它当公告展示，
                    // 而且它不能帮用户区分位置（同一台机器换账号也还是同一个位置）。
                    text = server.displayHost,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 窄屏给两行：地址与共享目录都能看全，不再被一行省略号截断
                    maxLines = if (compact) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ServerGridCard(
    server: SmbServer,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean = false,
    selecting: Boolean = false,
    connecting: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedWithTap()
    // 与视频 / 文件夹卡片完全同构：同一外框、同一 16:9 图形区、同一文字层级
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 100.dp
        // 与文件夹图标同一比例（0.72×卡宽），云图标和缩略图同一个体量
        val iconSize = (maxWidth * 0.72f).coerceIn(42.dp, 130.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (selecting) {
                        Modifier.selectionCardOutline(
                            selected = selected, selecting = true, pressed = pressed,
                        )
                    } else Modifier.mediaCardOutline(pressed)
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onClickLabel = if (selecting) "选中/取消选择" else "打开",
                    onLongClick = onLongClick,
                    onLongClickLabel = "更多操作",
                    role = Role.Button,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 图形区与缩略图同尺寸：图标铺满这一块，卡片边界即内容边界
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center,
            ) {
                NetworkArtwork(size = iconSize)
                if (selecting) {
                    SelectionBadge(
                        selected, Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    server.name,
                    style = if (compact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // 标题已经是主机地址了，副标题再重复一遍只是噪音，这里只留共享目录
                    server.share.ifBlank { server.displayHost },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerEditSheet(
    initial: SmbServer?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    // 底部面板是独立窗口，会盖住顶部的提示条，所以测试结果也在这里显示一份
    var testState by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var share by remember { mutableStateOf(initial?.share.orEmpty()) }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // 玻璃底：与全站面板同一套材质
        containerColor = appGlass().panelStrong,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // 键盘弹出时整块内容让位并可以滚动：五个输入框 + 两个按钮在手机上
                // 撑不满一屏，不给滚动的话内容会被压在键盘下面、输入框被挤成一条
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (initial == null) "添加 SMB 共享" else "编辑 SMB 共享",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "填写 NAS / Windows 共享信息，只浏览视频文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 「测试连接」抽成可复用动作：密码框键盘的 Done 键也走它，
            // 填完五个字段不用手动收起键盘再找按钮
            val runTest: () -> Unit = {
                val draft = buildServer(initial, name, host, share, user, password)
                if (draft == null) {
                    // 校验失败也写在输入框下方：弹窗里已经有一个结果位，
                    // 再往页面顶部发一条提示就是同一件事说两遍
                    testState = false to "请先填写服务器地址"
                } else {
                    testing = true
                    scope.launchUi {
                        val result = SmbClient.test(draft)
                        testing = false
                        // 结果只落在弹窗内的结果位，不再往页面上叠提示
                        result.fold(
                            onSuccess = { testState = true to "连接成功" },
                            onFailure = { testState = false to SmbErrors.describe(it) },
                        )
                    }
                }
            }

            OutlinedInput(
                value = name,
                onValueChange = { name = it },
                label = "名称（如：家里 NAS）",
            )
            OutlinedInput(
                value = host,
                onValueChange = { host = it },
                label = "服务器地址（可粘贴完整路径）",
                placeholder = "192.168.1.10 或 \\\\192.168.1.10\\media",
                // 地址栏用 URI 键盘：. / : / \ 这些符号在第一屏，且不被输入法当普通文本纠错
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedInput(
                value = share,
                onValueChange = { share = it },
                label = "共享目录（地址里已带路径时可不填）",
                placeholder = "media",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedInput(
                value = user,
                onValueChange = { user = it },
                label = "用户名（匿名访问留空）",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedInput(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
                else PasswordVisualTransformation(),
                trailing = {
                    PasswordEye(visible = showPassword) { showPassword = !showPassword }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { runTest() }),
            )

            testState?.let { (ok, msg) ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassDialogButton(
                    onClick = runTest,
                    modifier = Modifier.weight(1f),
                    // 「测试」是次要动作，把强调色留给「保存」
                    tone = GlassButtonTone.Neutral,
                ) { Text(if (testing) "测试中…" else "测试连接") }

                com.yaoahos.mypureplayer.ui.components.GlassButton(
                    text = "保存",
                    onClick = {
                        val draft = buildServer(initial, name, host, share, user, password)
                        if (draft == null) {
                            // 底部面板是独立窗口，会盖住顶部的提示条（NoticeOverlay），
                            // 校验结果必须写在输入框下方才看得见 —— 与「测试连接」同一套路
                            testState = false to "请先填写服务器地址"
                        } else {
                            scope.launchUi {
                                SmbClient.invalidate(draft.id)
                                Graph.servers.upsert(draft)
                                Notice.success(if (initial == null) "已添加共享" else "已保存修改")
                                onSaved()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun buildServer(
    initial: SmbServer?,
    name: String,
    host: String,
    share: String,
    user: String,
    password: String,
): SmbServer? {
    // 地址里可能带着 smb:// 前缀、\\ 反斜杠、端口，甚至整条路径，
    // 统一拆成 host + share，避免「地址填对了却报路径不存在」
    val parsed = SmbInput.parse(host)
    val cleanHost = parsed.host
    if (cleanHost.isBlank()) return null
    val cleanShare = share.trim().trim('/').ifBlank { parsed.share }
    return SmbServer(
        id = initial?.id ?: 0L,
        name = name.trim().ifBlank { cleanHost },
        host = cleanHost,
        username = user.trim(),
        password = password,
        // 域输入框已移除；旧记录里的域在编辑保存时也一并清空
        domain = "",
        share = cleanShare,
    )
}

@Composable
private fun OutlinedInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions =
        androidx.compose.foundation.text.KeyboardActions.Default,
) {
    GlassTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 点击共享卡片时预检连接的等待上限。
 *
 * 冷连接（NAS 刚从休眠醒来）握手 20s + 认证 + 列一次目录，再长就只能归到「进不去」。
 * 共享页里给 70s 是因为那边有缓存列表可看、可以慢慢等；这里点击后什么都还没发生，
 * 空等一分多钟只会让人以为卡死，不如早一点把原因写在顶部。
 */
private const val CONNECT_PROBE_TIMEOUT_MS = 40_000L

/**
 * 预检转圈延迟出现的阈值。
 *
 * 局域网里预检常常一两百毫秒就完成，圈刚画出来人已经进页面了，看起来像闪了一下。
 * 350ms 小于人的「这算不算有反应」等待感（约半秒），又足够盖住快速预检。
 */
private const val CONNECTING_RING_DELAY_MS = 350L
