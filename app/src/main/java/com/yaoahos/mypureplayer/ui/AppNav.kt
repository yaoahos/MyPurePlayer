package com.yaoahos.mypureplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.yaoahos.mypureplayer.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.appGlassBar
import com.yaoahos.mypureplayer.ui.components.AppOverlayHost
import com.yaoahos.mypureplayer.ui.components.AppOverlayHostView
import com.yaoahos.mypureplayer.ui.components.LocalAppOverlayHost
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlin.math.abs
import com.yaoahos.mypureplayer.ui.local.LocalScreen
import com.yaoahos.mypureplayer.ui.local.LocalPickerScreen
import com.yaoahos.mypureplayer.ui.more.AboutScreen
import com.yaoahos.mypureplayer.ui.more.BackupScreen
import com.yaoahos.mypureplayer.ui.more.HistoryScreen
import com.yaoahos.mypureplayer.ui.more.MoreScreen
import com.yaoahos.mypureplayer.ui.more.SettingsScreen
import com.yaoahos.mypureplayer.ui.more.StreamsScreen
import com.yaoahos.mypureplayer.ui.more.TrashScreen
import com.yaoahos.mypureplayer.ui.more.VaultScreen
import com.yaoahos.mypureplayer.ui.network.NetworkScreen
import com.yaoahos.mypureplayer.ui.network.SmbBrowserScreen
import com.yaoahos.mypureplayer.ui.play.PlaylistScreen
import com.yaoahos.mypureplayer.ui.play.PlaylistDetailScreen
import com.yaoahos.mypureplayer.ui.player.PlayerScreen

/**
 * 当前 NavHost 控制器：用 CompositionLocal 下发，而不是一路当参数传。
 *
 * 为什么必须这么绕：`NavHostController` 是一个没有稳定性标注的接口，Compose 编译期
 * 无法证明它"不会变"，于是**凡是把它当参数的 Composable 都不可跳过重组**。
 *
 * 而 AppRoot 在每次导航（进/出内层页面）时都会重组一次 —— 底栏页的一切都挂在
 * `AppShell(tab, navController)` 下面，参数不可跳过 ⇒ 整个壳层连同底下那张
 * "更多"页每进一次设置就要全量重跑一遍组合。实测代价很具体：七张玻璃卡片的取色、
 * 渐变 Brush、投影全部重建，绘制层当场作废，返回时头几帧 GPU 从 9ms 飙到 21ms，
 * 掉一帧 —— 用户说的"从设置、历史返回更多卡顿"就是这个。
 *
 * 改成 CompositionLocal 之后 AppShell 只剩 `tab` 一个参数（枚举，稳定），
 * 路由怎么变都不会再碰壳层，底下那张页面一次也不用重录。
 * 这里用 static 版本：值在整棵树的生命周期内不变，static 比 dynamic 读得更便宜。
 */
val LocalNavController = androidx.compose.runtime.staticCompositionLocalOf<NavHostController?> { null }

/**
 * 壳层当前是否应该"烘"成一张纹理（[androidx.compose.ui.graphics.CompositingStrategy.Offscreen]）。
 *
 * 为什么需要：Compose 每一帧都把**整窗**的绘制指令重放一遍，只有当某个节点是真正的离屏纹理时，
 * 它才会退化成"一次贴图"。转场时新旧两页同时在画，壳层那张"更多"页有 7 张玻璃卡片，
 * 逐帧重放光重录就要 6~8ms，直接顶穿 16.6ms 的预算。
 *
 * 为什么不能一直开着：离屏纹理意味着页面每次内容变化（滚动）都要整屏重烘一遍，
 * 底栏页的列表滚动会因此变重。所以只在**内层页面打开期间**和**转场期间**开 ——
 * 这两种状态下壳层本来就是静止的，烘一次可以用到底；转场结束回到标签页后保持不解烘，
 * 直到用户下一次按下（见 AppShell 里的 pointerInput）：静止时不重绘，留着不花钱，
 * 而那一刻的重建正好藏在"内容还没开始动"的帧里。
 *
 * 用 `MutableState` 下发而不是直接下发 Boolean：读值的地方在 `graphicsLayer {}` 里
 * （非组合作用域），读的是快照值，变化只会让这一个图层失效，不会牵连整棵子树重组 ——
 * 否则每次进/出设置都要把壳层连同页面重组一遍，又回到了当初那个坑。
 */
val LocalShellCached = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<Boolean>> {
    androidx.compose.runtime.mutableStateOf(false)
}

/**
 * 壳层当前是否被"盖住"（转场进行中，或内层页面正开着）。
 *
 * 这个值只服务一件事：决定 [LocalShellCached] 那张离屏纹理**什么时候可以拆**。
 * 盖住期间壳层整块被上层页面遮住，拆纹理只会白白多烘一次全屏；而且覆盖层正在
 * 用这张纹理做合成，中途拆掉会让转场后半段退回逐帧重放。所以只有"确实回到
 * 标签页、且没有转场"时才允许释放。
 *
 * 下发方式仍是 static + MutableState：读它的地方在 `pointerInput` 的 await 循环里，
 * 写它的是 AppRoot 的协程，两者都不该牵连组合。
 */
val LocalShellCovered = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<Boolean>> {
    androidx.compose.runtime.mutableStateOf(false)
}


/**
 * 需要隐藏壳层常驻底栏的页面集合（按标签页 route 记名）。
 *
 * 两个场景：本地页进入文件夹层级、任意标签页进入多选态。此刻页面自己会显示
 * 底部四宫格操作栏，壳层那条常驻底栏必须让位，否则两条底栏叠在一起。
 * 用 Set 而不是单个 Boolean：标签切换的 180ms 里新旧两页会同时存在，
 * 单个 Boolean 会被两页的 onDispose 互相覆盖成 false（底栏闪一下）。
 */
private val LocalShellBottomBarHidden = androidx.compose.runtime.staticCompositionLocalOf<
    androidx.compose.runtime.MutableState<Set<String>>,
> {
    androidx.compose.runtime.mutableStateOf(emptySet())
}

/** 页面声明「我这段时间要隐藏壳层底栏」；离开或状态恢复时自动撤销。 */
@Composable
fun HideShellBottomBar(hidden: Boolean) {
    val hiddenSet = LocalShellBottomBarHidden.current
    val key = androidx.compose.runtime.currentCompositeKeyHashCode.toString()
    DisposableEffect(key, hidden) {
        if (hidden) hiddenSet.value = hiddenSet.value + key
        else hiddenSet.value = hiddenSet.value - key
        onDispose { hiddenSet.value = hiddenSet.value - key }
    }
}

/**
 * 内层页面（走 NavHost 的那些）的统一根：铺一层不透明底色兜底，避免转场期透底。
 *
 * 为什么需要：转场那 300ms 里两层同时在画，有的页面根节点自带背景（玻璃渐变），
 * 有的没有（例如设置页主体就是个 LazyColumn）。透明部分会透出另一层的内容，
 * 看起来就是「残影」。这里每页统一垫一层不透明底，宿主层就不用猜谁需要兜底。
 *
 * 播放页**不要**用它：它的画面宿主是 SurfaceView，整屏盖住，垫底没有意义，
 * 仍走淡入淡出（见 AppNavHost 里的注释）。
 */
@Composable
private fun PageRoot(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) { content() }
}

object Routes {
    const val LOCAL = "local"
    const val NETWORK = "network"
    const val PLAY = "play"
    const val MORE = "more"

    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val BACKUP = "backup"
    const val HISTORY = "history"
    const val ABOUT = "about"
    const val STREAMS = "streams"
    const val TRASH = "trash"
    const val VAULT = "vault"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"

    // targetPlaylist：加入的目标。空串 = 加入「正在播放」队列；否则为该自建列表 id。
    const val SMB_PATTERN = "smb/{serverId}/{path}?addMode={addMode}&targetPlaylist={targetPlaylist}"
    const val PICKER_PATTERN = "picker/{folder}?targetPlaylist={targetPlaylist}"

    // addMode=true：从播放列表进入，点视频只加入目标不跳转，用于批量添加
    fun smb(serverId: Long, path: String, addMode: Boolean = false, targetPlaylist: String = ""): String =
        "smb/$serverId/${encode(path)}?addMode=$addMode&targetPlaylist=${encode(targetPlaylist)}"

    fun picker(folderKey: String, targetPlaylist: String = ""): String =
        "picker/${encode(folderKey)}?targetPlaylist=${encode(targetPlaylist)}"

    fun playlistDetail(playlistId: String): String = "playlist/${encode(playlistId)}"

    fun encode(raw: String): String =
        Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun decode(encoded: String): String = try {
        String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    } catch (t: Throwable) {
        ""
    }
}

enum class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** 选中态用的实心图标 */
    val iconSelected: ImageVector,
    val labelRes: Int,
) {
    // 未选中统一用空心线条、选中换成实心同款：**形状**本身就在说明"当前在哪一栏"，
    // 不必靠把颜色堆得更重（第 19 轮："底栏选中再明显一点，一眼就能看出来"）。
    LOCAL(Routes.LOCAL, "本地", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary, R.string.tab_local),
    NETWORK(Routes.NETWORK, "网络", Icons.Outlined.Cloud, Icons.Filled.Cloud, R.string.tab_network),
    // 播放列表统一用「条目列表」图标，与「添加到播放列表」操作完全一致，
    // 不再用带播放箭头的变体，避免同一个功能出现两种图标
    PLAY(
        Routes.PLAY, "播放",
        Icons.AutoMirrored.Outlined.FormatListBulleted,
        Icons.AutoMirrored.Filled.FormatListBulleted,
        R.string.tab_play,
    ),
    MORE(Routes.MORE, "更多", Icons.Outlined.Apps, Icons.Filled.Apps, R.string.tab_more),
}

/**
 * 底栏页的骨架：顶栏 + 内容。**不含底栏**。
 *
 * 底栏归壳层（[AppShell]）所有，这样切标签时它一步不动（"常驻"），
 * 而进内层页面时内层页面是整屏覆盖层，会连底栏一起盖住（"一体"）。
 * 之前底栏放在每一页的 Scaffold 里，切标签时两条底栏对穿，
 * 进内层页面时底栏又要靠自己的淡入淡出去追页面的位移 —— 怎么调都有先后。
 */
@Composable
fun MainScaffold(
    topBar: @Composable () -> Unit,
    // 多选态的四宫格操作栏。放在 Scaffold 的 bottomBar 槽里，
    // 这样内容与壳层底栏都不会被它盖住；默认空 = 普通状态。
    bottomBar: @Composable () -> Unit = {},
    // 整页级的位移 / 裁剪等修饰符（页内转场用）
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        // 每个页面自己铺一张不透明背景：转场时进入的那页遮住离开的那页，
        // 不会出现两页内容互相透出的"残影"
        modifier = modifier.fillMaxSize().glassPageBackground(),
        topBar = { topBar() },
        bottomBar = bottomBar,
        // 顶栏自己处理状态栏，内容区由壳层扣掉底栏，这里不再补 inset
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { padding ->
        Box(Modifier.fillMaxSize()) { content(padding) }
    }
}

/**
 * 常驻壳层：内容区 + 底栏。
 *
 * 结构完全对齐 MySafePass 的 MainTabShell：
 *  - 顶栏由各页自己画（这个软件每页的顶栏标题/按钮都不同）；
 *  - **内容区**是唯一参与标签转场的部分，横向对穿；
 *  - **底栏**在内容区之外，任何切换都不动，只有选中态跟着变。
 *
 * 底栏既然"常驻"，就不再有任何自己的进场/退场动画，也就不存在
 * 「底栏先出来一截、页面还在路上」这种对不齐的可能——它压根不动。
 *
 * **参数只有 `tab` 一个**（枚举，稳定）：路由变化时 AppShell 能被整块跳过。
 * 控制器从 [LocalNavController] 读，不占参数位置 —— 详见该 Local 的注释。
 */
@Composable
private fun AppShell(tab: MainTab) {
    val navController = LocalNavController.current ?: return
    // 每个标签页各自保存 rememberSaveable 状态：切走再切回来，
    // 滚动位置、搜索词、宫格/列表选择都还在（与 NavHost 的 saveState 等价）。
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val cs = MaterialTheme.colorScheme
    // 底栏背后那层纯色 = 页面渐变最底端的颜色。页面内容区到这就结束了，
    // 两者同色，接缝看不见——不用为此再画一张全屏渐变。
    val barBackdrop = com.yaoahos.mypureplayer.ui.components.pageGradientBottom(cs)
    val cached = LocalShellCached.current
    val covered = LocalShellCovered.current
    val hiddenBottomSet by LocalShellBottomBarHidden.current
    val bottomBarHidden = hiddenBottomSet.isNotEmpty()

    Column(
        Modifier
            .fillMaxSize()
            // 转场 / 内层页面打开期间把整层烘成纹理：之后每帧只是复用这张贴图，
            // 卡片、文字、投影都不再重放。详见 LocalShellCached 的注释。
            //
            // 壳层**不做任何位移**（2026-11-22 用户定稿）：它常驻渲染标签页，
            // 返回时只由覆盖在上面的内层页面侧滑退出，露出原本就静止的本地视频页。
            // 之前给壳层加过 -W/3 视差位移，结果是"进入首页时整个本地页跟着滑一下"，
            // 且冷启动时 activeTab 首帧为 null 会把壳层先推走再滑回 —— 全是多余的
            // 花哨动作。返回只保留干净的侧滑。
            .graphicsLayer {
                compositingStrategy =
                    if (cached.value) androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    else androidx.compose.ui.graphics.CompositingStrategy.Auto
            }
            // 离屏合成什么时候关：**回到页面后的第一下点击 / 滑动时**，不是定时器。
            //
            // 之前是转场结束 600ms 后自动关，代价是静止画面里凭空多出一帧 16ms 的全屏重建
            // （逐帧数据里那次 16.4ms / GPU 11.1ms 就是它）——用户刚好在这个时刻看着这一页，
            // 任何多余的动作都容易被读成"又卡一下"。
            //
            // 改成用户按下时关：那一下本来就要重新组合、重新绘制，重建图层藏在
            // "内容还没开始动"的那一帧里；而静止时保持离屏烘焙没有任何代价
            // （画面不变就不需要重绘）。反过来说，如果一直不关，那么之后任何一次
            // 点击引发的重绘都要重烘一整屏 1080×2340 的纹理，点一下卡一下，更糟。
            // 只在"确实回到标签页、且没有转场/内层页面压着"时动手：
            // 覆盖层期间拆纹理既要多重烘一次全屏，又会把转场要用的那张拆掉。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (covered.value || !cached.value) continue
                        if (event.type != androidx.compose.ui.input.pointer.PointerEventType.Press) continue
                        // 底栏上那一条（约底部 15%）不在此列：切标签本来就要用这张纹理，
                        // 拆掉反而让切换变成逐帧重录。
                        val y = event.changes.firstOrNull()?.position?.y ?: size.height.toFloat()
                        if (y < size.height * 0.85f) cached.value = false
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                // 内容区必须裁剪：对穿时两页各占满宽，不裁剪会滑到顶栏/底栏上方
                .clipToBounds(),
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    // 底栏转场：满宽对穿、方向按栏位左右顺序、
                    // 180ms tween（见 PageMotion.tabEnter）。不做淡入 —— 新页从
                    // 半透明淡入会让整页文字先虚后实，读起来就是"糊一下"。
                    val toRight = tabDirection(initialState.route, targetState.route) ?: true
                    tabEnter(toRight) togetherWith tabExit(toRight)
                },
                label = "tab-content",
            ) { target ->
                // 控制器在这个 lambda **内部**取，不让它成为闭包捕获值：
                // 捕获了不稳定对象，内容 lambda 本身就不可跳过，上面省下来的重组又白费了。
                val nav = LocalNavController.current ?: return@AnimatedContent
                tabStates.SaveableStateProvider(target.route) {
                    when (target) {
                        MainTab.LOCAL -> LocalScreen(nav)
                        MainTab.NETWORK -> NetworkScreen(nav)
                        MainTab.PLAY -> PlaylistScreen(nav)
                        MainTab.MORE -> MoreScreen(nav)
                    }
                }
            }
        }

        // 底栏：常驻。玻璃条下面垫一层与页面底部同色的底，
        // 内容从它下面滚过时才有"半透明一条"的层次。
        // 进文件夹 / 选择态时整条移除，不保留占位高度 —— 内容区直接铺到底，
        // 由页面自己的四宫格操作栏接管底部。
        if (!bottomBarHidden) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(barBackdrop)
                    .shadow(
                        elevation = 8.dp,
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        clip = false,
                        ambientColor = cs.primary.copy(alpha = 0.10f),
                        spotColor = cs.primary.copy(alpha = 0.14f),
                    )
                    .appGlassBar(com.yaoahos.mypureplayer.ui.components.GlassEdge.TOP),
            ) {
                MainBottomBar(tab, navController)
            }
        }
    }
}

/** 点击底栏与左右滑动共用同一条导航路径，避免两处行为日后走偏。 */
private fun navigateToTab(navController: NavHostController, tab: MainTab) {
    navController.navigate(tab.route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 四个标签页的左右滑动切换。
 *
 * 必须挂在 AppRoot 的最外层容器：标签页在 NavHost 里是四个空的 destination，
 * 那层 NavHost 全屏盖在常驻壳层之上，手势放进 AppShell 的内容区根本收不到事件。
 *
 * 只观察不消费：不跟手平移，松手后走 AnimatedContent 原有的 180ms 对穿，
 * 这样壳层常驻渲染与 SaveableStateProvider 的状态保存都不用动。
 */
private fun Modifier.mainTabSwipe(
    enabled: Boolean,
    tab: MainTab,
    bottomHidden: androidx.compose.runtime.MutableState<Set<String>>,
    navController: NavHostController,
): Modifier = pointerInput(enabled, tab) {
    // enabled 直接由 activeTab != null 决定：进任何内层页面（含播放页）都会
    // 变成 false 并重启本节点，不需要再读那个会在启动时卡住的 shellCovered。
    if (!enabled) return@pointerInput
    val distanceThreshold = 56.dp.toPx()
    val velocityThreshold = 0.35f // px/ms，快速轻扫也算一次切换
    var startX = 0f
    var startY = 0f
    var lastX = 0f
    var lastAt = 0L
    var velocityX = 0f
    var tracking = false
    var lastSwitchAt = 0L
    val fromTab = tab

    awaitPointerEventScope {
        while (true) {
            // Main pass：作为最外层父节点先于页面组件看到事件，并且这里的
            // consume() 能被同 pass 的 clickable 看见。Initial pass 的消费
            // 挡不住卡片点击 —— 实测在「更多」页横滑会误开历史记录。
            val event = awaitPointerEvent(PointerEventPass.Main)
            // 只认单指：第二根手指落下就放弃，不与缩放等手势抢
            if (event.changes.size > 1) {
                tracking = false
                continue
            }
            val change = event.changes.firstOrNull()
            when (event.type) {
                PointerEventType.Press -> {
                    if (bottomHidden.value.isNotEmpty() || change == null) {
                        tracking = false
                        continue
                    }
                    startX = change.position.x
                    startY = change.position.y
                    lastX = startX
                    lastAt = System.nanoTime()
                    velocityX = 0f
                    tracking = true
                }

                PointerEventType.Move -> {
                    if (!tracking || change == null) continue
                    val dx = change.position.x - startX
                    val dy = change.position.y - startY
                    if (abs(dy) > abs(dx) && abs(dy) > 10f) {
                        tracking = false // 纵向滚动优先，不是换页手势
                        continue
                    }
                    // 一旦确认横向，就持续消费这些移动事件：卡片上的 clickable
                    // 因此取消 tap，横滑不会顺手点开某一行。
                    if (abs(dx) > 12f && abs(dx) > abs(dy)) change.consume()
                    val now = System.nanoTime()
                    val dt = (now - lastAt) / 1_000_000f
                    if (dt > 0f) velocityX = (change.position.x - lastX) / dt
                    lastX = change.position.x
                    lastAt = now
                }

                PointerEventType.Release -> {
                    if (!tracking || change == null) continue
                    tracking = false
                    if (bottomHidden.value.isNotEmpty()) continue
                    val dx = change.position.x - startX
                    val far = abs(dx) >= distanceThreshold
                    val fast = abs(velocityX) >= velocityThreshold && abs(dx) >= 12f
                    if (!far && !fast) continue
                    // 转场中忽略新一次滑动，避免连滑直接跳过一页
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastSwitchAt < 300L) continue
                    val index = MainTab.entries.indexOf(fromTab)
                    val target = index + if (dx < 0f) 1 else -1
                    if (target in MainTab.entries.indices) {
                        // 这次抬手已经判定为换页，就别再让它落到页面卡片上变成点击
                        // （在「更多」页横滑会误开历史记录，实测过）。
                        change.consume()
                        lastSwitchAt = nowMs
                        navigateToTab(navController, MainTab.entries[target])
                    }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun MainBottomBar(
    current: MainTab,
    navController: NavHostController,
) {
    // 底栏做成一条玻璃：半透明 + 内容侧高光边，滚动内容从它下面透过去。
    // 材质由外层 Box 统一绘制（见 AppRoot），这里保持全透明，
    // 两处都画会让 alpha 叠两层，变成一条死白色的实心带。
    NavigationBar(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        MainTab.entries.forEach { tab ->
            val selected = current == tab
            // 选中的一处强调不够：光换颜色，在低饱和主题下只是"深一点的同一支蓝"。
            // 现在三层叠加一起用 —— 强调色图标、强调色文字、图标后面一层 18% 的强调色胶囊；
            // 文字再加一档字重。四者中任意一条看不清，剩下三条也能把"当前在哪一栏"说清楚。
            val accent = com.yaoahos.mypureplayer.ui.components.appGlass().accent
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (current != tab) navigateToTab(navController, tab)
                },
                icon = {
                    Icon(
                        if (selected) tab.iconSelected else tab.icon,
                        contentDescription = stringResource(tab.labelRes),
                    )
                },
                label = {
                    Text(
                        stringResource(tab.labelRes),
                        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold
                        else androidx.compose.ui.text.font.FontWeight.Normal,
                    )
                },
                // 选中态**不再三层同色**。
                //
                // 之前是：胶囊 18% 强调色 + 图标强调色 + 文字强调色。三者同一个色相、
                // 同一个饱和度，叠在一起的结果是"一整块同色的东西"，边界反而消失了；
                // 而标签只有 12sp，彩色小字压在彩色胶囊上，对比度从 4.4:1 掉到 3.8:1，
                // 比未选中的灰字更难读 —— 用户问"会不会反而更不清晰"，答案是会。
                //
                // 现在把"颜色"只留给图标一处，文字回到高对比的 onSurface：
                //   图标 = 强调色（唯一饱和色，视线第一落点）
                //   胶囊 = 强调色 14%（很淡的一层"底"，只负责标出位置，不抢色）
                //   文字 = onSurface + SemiBold（近黑/近白，12sp 也清楚）
                // 三种信号各管一件事，不再互相抵消。
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = accent,
                    selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    indicatorColor = accent.copy(alpha = 0.20f),
                    unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * 两个页面是否都属于底栏页：是则返回切换方向（true = 往右切），否则返回 null。
 * 只有底栏之间才按左右顺序取方向，里层页面继续走前进 / 返回。
 */
private fun tabDirection(from: String?, to: String?): Boolean? {
    if (from == null || to == null || from == to) return null
    val fromIndex = MainTab.entries.indexOfFirst { it.route == from }
    val toIndex = MainTab.entries.indexOfFirst { it.route == to }
    if (fromIndex < 0 || toIndex < 0) return null
    return toIndex > fromIndex
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val context = LocalContext.current

    // 保险箱门禁：VAULT 从返回栈消失即上锁。放在常驻的 AppRoot 而非 VaultScreen 内部——
    // 页面里曾用 DisposableEffect 监听 NavBackStackEntry 的 ON_DESTROY，但组合 dispose 时
    // 的 removeObserver 会先于 ON_DESTROY 事件执行，返回时 observer 已被摘掉、永远收不到
    // 销毁回调，表现就是「退出保险箱没锁」。这里改成看返回栈里 VAULT 是否还在：
    // 进播放页 / 从播放页返回 VAULT 时它都还在栈里 → 不锁，看完视频返回免重输；
    // 真正返回退出或切底栏把它弹栈 → 锁。MainActivity.onStop 另负责切后台立即锁。
    val vaultInStack = remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(navController) {
        navController.currentBackStack.collect { stack ->
            val present = stack.any { it.destination.route == Routes.VAULT }
            if (vaultInStack.value && !present) com.yaoahos.mypureplayer.data.Graph.vault.lock()
            vaultInStack.value = present
        }
    }
    // 整个应用只有一个浮层宿主：同一时刻只会有一个面板/确认框，复用同一个槽位即可
    val overlayHost = remember { AppOverlayHost() }
    val openPlayerSignal by com.yaoahos.mypureplayer.playback.Playback.openPlayerSignal
        .collectAsStateWithLifecycle()

    // 当前标签：进内层页面时 route 已经换成内层页面的路由，这里保留最后一个标签，
    // 让壳层在底下继续显示原来那一页。
    //
    // 壳层只吃 [tab] 这一个参数（不直接读 route）：打开/关闭内层页面时 tab 不变，
    // 壳层就能整块跳过重组 —— 否则每进一次设置，整页（列表、缩略图、排序）都要重跑一遍 body。
    var lastTab by remember { mutableStateOf(MainTab.LOCAL) }
    val activeTab = MainTab.entries.firstOrNull { it.route == route }
    androidx.compose.runtime.SideEffect {
        if (activeTab != null) lastTab = activeTab
    }
    val tab = activeTab ?: lastTab

    // 壳层什么时候该烘成纹理：
    //  - NavHost 正在做转场（visibleEntries 里同时有两页）；
    //  - 或者内层页面开着且不是播放页。
    // 转场结束后立刻关掉，恢复成直接绘制，底栏页的列表滚动不受影响。
    //
    // 播放页单独排除：那张纹理是整屏 10MB，播放时显存要留给解码器和渲染，
    // 而播放页本来就整屏盖住壳层，没有任何可见收益。
    val shellCached = remember { mutableStateOf(false) }
    // 与 shellCached 同步维护的"壳层被盖住"标记：给 AppShell 判断何时才允许拆纹理。
    // 单独一个值而不是直接复用 shellCached —— 后者只会被置 true（见下面的注释），
    // 需要"盖住了"和"已经烘过"两种语义分开才判断得出来。
    val shellCovered = remember { mutableStateOf(false) }
    // 特别注意：**不要**用 `collectAsState` 把 visibleEntries 变成 AppRoot 的组合依赖。
    // 返回时它至少变两次，而 AppRoot 一重组就要重跑 NavHost 的 builder（重建导航图），
    // 再叠加转场结束的重新布局，正好构成落位那一帧的 20ms。放在协程里读，
    // 只写 shellCached 这个快照值 —— 读它的地方在 graphicsLayer 中，不会回头重组。
    val activeTabNow = androidx.compose.runtime.rememberUpdatedState(activeTab)
    val routeNow = androidx.compose.runtime.rememberUpdatedState(route)
    androidx.compose.runtime.LaunchedEffect(navController) {
        navController.visibleEntries.collect { entries ->
            // 转场中，或内层页面（播放页除外）正开着
            val innerOpen = activeTabNow.value == null && routeNow.value != Routes.PLAYER
            val covering = entries.size > 1 || innerOpen
            shellCovered.value = covering
            if (covering) shellCached.value = true
        }
    }
    // visibleEntries 只在条目数变化时发出：返回标签页时 route 变了、条目数却可能不变，
    // 上面的 collect 就不会重算，covered 卡在 true —— 壳层一直留在离屏合成，
    // 列表每滚一帧都要重烘整屏。这里在 activeTab / route 变化时补算一次。
    androidx.compose.runtime.LaunchedEffect(activeTab, route) {
        val entries = navController.visibleEntries.value
        val innerOpen = activeTab == null && route != Routes.PLAYER
        val covering = entries.size > 1 || innerOpen
        shellCovered.value = covering
        if (covering) shellCached.value = true
    }
    /**
     * 转场结束后**不主动**把壳层从 Offscreen 切回 Auto。
     *
     * 早先是"返回后 600ms 关掉"，理由是底栏页滚动会更重。但那一下在静止画面里
     * 凭空多出一帧 16ms 的全屏重建（逐帧数据实测 16.4ms / GPU 11.1ms），而且恰好
     * 落在用户刚回到这一页、还盯着画面的时候 —— 这是"转场结束还卡一下"的候选之一。
     *
     * 现在改由 AppShell 的 pointerInput 决定：用户一开始滑动（内容还没移动的那一帧）
     * 就关，把重建成本藏在没有位移的时刻；静止时烘焙不产生任何重绘，留着不花钱。
     */

    // 只有播放页按视频方向自动切换；其它页面一律跟随系统横竖屏设置。
    // 值没变就别写：setRequestedOrientation 要走一趟 ActivityTaskManager，
    // 部分 ROM 还会顺带做一次窗口检查 —— 每返回一次都白付这个成本。
    androidx.compose.runtime.LaunchedEffect(route) {
        if (route != Routes.PLAYER && !com.yaoahos.mypureplayer.playback.Playback.pictureInPicture) {
            val activity = context as? Activity
            if (activity != null &&
                activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // “用其它应用打开 / 投屏恢复”进来的视频：直接进播放页。
    // 触发后立即消费信号，避免退出软件再进入时 StateFlow 重放旧值导致自动续播。
    androidx.compose.runtime.LaunchedEffect(openPlayerSignal) {
        if (openPlayerSignal <= 0) return@LaunchedEffect
        val shouldNavigate = route != Routes.PLAYER
        // 无论是否真的跳转，都先消费掉：信号是“一次性事件”，不是可重放的状态
        com.yaoahos.mypureplayer.playback.Playback.consumeOpenPlayer()
        if (shouldNavigate) navController.navigate(Routes.PLAYER) { launchSingleTop = true }
    }

    // 两层层级（与 MySafePass 同构）：
    //   ① 壳层 = 标签页内容 + 常驻底栏，**始终合成**，切标签只换内容区；
    //   ② 覆盖层 = 内层页面（设置 / 保险箱 / 播放 / SMB 浏览…），整屏滑入，
    //      滑进来的过程中把壳层连同底栏一起盖住 —— 底栏不需要任何自己的动画，
    //      "一体进出"是几何关系本身给的，不是两支动画去对齐。
    //
    // NavHost 仍然负责全部路由与返回栈，但标签页那 4 个目的地图层是**空的**：
    // 它们只用来切标签（以及给返回栈一个落点），真正的界面由壳层常驻渲染。
    // 这样一来，进内层页面不会销毁标签页的组合状态，返回时也不用重建整页 ——
    // 这正是之前"从 6 个页面返回底栏卡顿"的来源：每返回一次都要重新组合一页。
    val bottomHiddenState = remember { mutableStateOf(emptySet<String>()) }
    Box(
        Modifier
            .fillMaxSize()
            // 只铺一层纯色兜底
            .background(MaterialTheme.colorScheme.background)
            // 滑动切换挂在最外层：NavHost 那层全屏容器会盖住常驻壳层，
            // 手势放壳层里收不到事件。enabled 只在四个底栏页为真。
            .mainTabSwipe(activeTab != null, tab, bottomHiddenState, navController),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalNavController provides navController,
            LocalShellCached provides shellCached,
            LocalShellCovered provides shellCovered,
            LocalShellBottomBarHidden provides bottomHiddenState,
            // 浮层宿主：动作面板 / 确认框都挂到这里，画在同一个窗口里，
            // 不再各自去开一个 Dialog 窗口（开窗那一帧实测 50~65ms）
            LocalAppOverlayHost provides overlayHost,
        ) {
            AppShell(tab = tab)

            AppNavHost()

            // 必须是最后一个子节点：遮罩要盖住壳层、内层页面与底栏
            AppOverlayHostView(overlayHost)
        }
    }
}


/**
 * 覆盖层的 NavHost。**故意不接收任何参数**。
 *
 * 这里是"按下返回后要等半拍才动"的直接来源。`AppRoot` 每次路由变化都会重组
 * （它要维护 lastTab、横竖屏、外部打开视频这些副作用），而 NavHost 一旦作为它的
 * 子节点直接写在 body 里，`navController` 这个不稳定接口就被闭包捕获 —— 上层一重组，
 * NavHost 就跟着重组，`remember(route, startDestination, builder)` 的 key 里
 * builder 是新的 lambda，于是**整张导航图被重建**：12 个 destination 连同参数表全部
 * 重新实例化。逐帧数据里返回第一帧 `AnimationStart → PerformTraversalsStart`（这一阶段
 * 实际就是重组）要 9–10ms，就是这笔开销。
 *
 * 把 NavHost 收进一个无参 composable、控制器改从 [LocalNavController] 读：
 * 参数表为空 ⇒ 编译期可跳过 ⇒ 上层重组时整块跳过，导航图只在首次组合时建一次。
 * 返回/前进的第一帧因此少掉一次建图，动画起步更跟手。
 */
@Composable
private fun AppNavHost() {
    val navController = LocalNavController.current ?: return
    NavHost(
        navController = navController,
        startDestination = Routes.LOCAL,
        modifier = Modifier.fillMaxSize(),
        // 全软件一套转场：两层视差模型（详见 PageMotion）——
        // 前进 = 新页 `+W→0`、旧页 `0→-W/3`；返回 = 当前页 `0→+W`、目标页 `-W/3→0`，
        // 严格镜像。navigation-compose 2.10.2 的 AnimatedContent 以**单个 entry**
        // 为目标（contentKey = entry.id），popEnter 正常播放，返回时 z 把目标页压到
        // 下层 —— 不需要任何手动补满。
        //
        // 返回就是干净的侧滑，没有第二层位移：当前页 popExit 推出右侧，
        // 露出底下原本就静止的标签页（界面由 AppShell 常驻渲染，目的地图层是空的）。
        // 不再给壳层加"从 -W/3 补满"的视差代偿——那是"进入首页时本地页滑一下"的来源，
        // 用户 2026-11-22 定稿：返回只保留水平侧滑，不要花哨动作。
        //
        // MIUI 的窗口缩小卡片预览已通过清单 `enableOnBackInvokedCallback=false` 关掉，
        // 应用侧只播这一次水平位移即可，不要再叠任何缩放 / 第二个位移 / 淡化。
        enterTransition = { forwardEnter },
        exitTransition = { forwardExit },
        popEnterTransition = { popEnter },
        popExitTransition = { popExit },
        // 页面尺寸恒定，关掉尺寸动画，避免内容拉伸 / 文字模糊
        sizeTransform = null,
    ) {
        // 标签页：空图层。界面由 AppShell 常驻渲染，这里只占一个返回栈位置。
        composable(Routes.LOCAL) {}
        composable(Routes.NETWORK) {}
        composable(Routes.PLAY) {}
        composable(Routes.MORE) {}

        // 播放页单独用淡入淡出，其余覆盖层仍是水平滑动。
        //
        // 原因：播放画面宿主是 SurfaceView（HDR 唯一可行路径，见 view_player.xml）。
        // SurfaceView 是独立硬件图层，不参与 Compose 图层的 translationX —— 继续用
        // 水平滑动会出现「背景滑走、画面钉在原地」的错位。alpha 走 SurfaceControl，
        // SurfaceView 能正常淡入淡出。
        //
        // 用弹簧而不是 tween：本机 `MotionDurationScale ≈ 0.25`，`tween(220)` 实际只有
        // 55ms，画面几乎是硬切。弹簧按物理时间走，不受这个系数影响，也是全软件其它
        // 转场共用的那条曲线。
        composable(
            route = Routes.PLAYER,
            enterTransition = { fadeIn(animationSpec = PageMotion.PageAlphaSpring) },
            exitTransition = { fadeOut(animationSpec = PageMotion.PageAlphaSpring) },
            popEnterTransition = { fadeIn(animationSpec = PageMotion.PageAlphaSpring) },
            popExitTransition = { fadeOut(animationSpec = PageMotion.PageAlphaSpring) },
        ) { PlayerScreen(navController) }
        composable(Routes.SETTINGS) { PageRoot { SettingsScreen(navController) } }
        composable(Routes.BACKUP) { PageRoot { BackupScreen(navController) } }
        composable(Routes.HISTORY) { PageRoot { HistoryScreen(navController) } }
        composable(Routes.ABOUT) { PageRoot { AboutScreen(navController) } }
        composable(Routes.STREAMS) { PageRoot { StreamsScreen(navController) } }
        composable(Routes.TRASH) { PageRoot { TrashScreen(navController) } }
        composable(Routes.VAULT) { PageRoot { VaultScreen(navController) } }

        composable(
            route = Routes.SMB_PATTERN,
            arguments = listOf(
                navArgument("serverId") { type = NavType.LongType },
                navArgument("path") { type = NavType.StringType },
                navArgument("addMode") { type = NavType.BoolType; defaultValue = false },
                navArgument("targetPlaylist") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val serverId = entry.arguments?.getLong("serverId") ?: 0L
            val path = Routes.decode(entry.arguments?.getString("path").orEmpty())
            val addMode = entry.arguments?.getBoolean("addMode") ?: false
            val targetPlaylist = Routes.decode(entry.arguments?.getString("targetPlaylist").orEmpty())
            PageRoot { SmbBrowserScreen(navController, serverId, path, addMode, targetPlaylist) }
        }

        composable(
            route = Routes.PICKER_PATTERN,
            arguments = listOf(
                navArgument("folder") { type = NavType.StringType },
                navArgument("targetPlaylist") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val folder = Routes.decode(entry.arguments?.getString("folder").orEmpty())
            val targetPlaylist = Routes.decode(entry.arguments?.getString("targetPlaylist").orEmpty())
            PageRoot { LocalPickerScreen(navController, folder, targetPlaylist) }
        }

        composable(
            route = Routes.PLAYLIST_DETAIL,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) { entry ->
            val playlistId = Routes.decode(entry.arguments?.getString("playlistId").orEmpty())
            PageRoot { PlaylistDetailScreen(navController, playlistId) }
        }
    }
}
