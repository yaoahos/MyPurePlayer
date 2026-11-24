package com.yaoahos.mypureplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.yaoahos.mypureplayer.ui.components.collectPressedWithTap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.yaoahos.mypureplayer.ui.components.glassPressLevel
import com.yaoahos.mypureplayer.ui.components.GlassIconButton
import com.yaoahos.mypureplayer.ui.components.glassPressable
import androidx.compose.ui.unit.dp
import com.yaoahos.mypureplayer.ui.components.accentColor
import com.yaoahos.mypureplayer.ui.components.glassTint

/**
 * 播放页统一的「玻璃」外观。
 *
 * 播放页的背景永远是视频画面，所以面板必须和画面拉开层次，否则小字会被画面吃掉。
 * 但"拉开层次"不等于"一律做深色"：日间看片时一块深靛蓝压上去，画面被切掉一大块，
 * 透明感也没了（用户原话："日间模式下播放界面的玻璃面板应该是浅色系淡色系，
 * 才更有透明感"）。
 *
 * 所以这里和全站玻璃一样按明暗分两套：
 *   日间 = 淡色玻璃（白里掺主题色，alpha 0.72，深色文字），画面从下面透上来；
 *   夜间 = 深色玻璃（深底掺主题色，浅色文字），避免夜里一块亮板刺眼。
 *
 * 颜色全部由当前主题色推导，所以浅色主题、深色主题、Android 12 动态取色下
 * 都会自动带上当前主题的主色调，而不是写死一套蓝。
 */
@Immutable
data class PlayerGlassColors(
    /** 侧栏底色：半透明，能透出画面 */
    val panel: Color,
    /**
     * 面板实际铺底：竖向渐变（上浅下深）。
     *
     * 单一色值 + 固定不透明度，压在视频上就是一块"黑板"—— 画面透不出来，
     * 片体自己也没有明暗变化，看起来完全不像玻璃。改成渐变后，
     * 上半部分更透（能看到画面在动），下半部分略沉（压住文字），
     * 片体自身就有了"一层材料"的体积感。
     */
    val panelBrush: Brush,
    /** 斜向高光：从左上扫过来的一道光 */
    val panelSheen: Brush,
    /** 小卡片底色：更实一点，短菜单里文字更稳 */
    val panelSolid: Color,
    /** 强调色：在面板底上足够醒目、又不刺眼的那一档主题色 */
    val accent: Color,
    val onGlass: Color,
    val onGlassSecondary: Color,
    /** 分隔线 / 描边 */
    val divider: Color,
    /** 选中行底色 */
    val selectedFill: Color,
    val scrim: Color,
    /** 面板外缘描边：上亮下暗，表现玻璃断面 */
    val edgeTop: Color,
    val edgeBottom: Color,
    /** 是否深色面板（决定个别图标/高光的取色方向） */
    val isDark: Boolean,
)

val LocalPlayerGlass = compositionLocalOf<PlayerGlassColors?> { null }

@Composable
fun rememberPlayerGlassColors(): PlayerGlassColors {
    val cs = MaterialTheme.colorScheme
    // 与全站玻璃同一判断：看真正生效的背景亮度，而不是系统设置。
    // 应用内强制深色、或动态取色给了偏暗/偏亮的底色，都能判对。
    val dark = cs.background.luminance() < 0.5f
    return remember(cs, dark) {
        if (dark) {
            // 夜间：深底掺主题色。蓝主题是深靛蓝，橙主题是深棕橙，
            // 面板和画面、和主题色都能呼应上，而不是一块突兀的黑板。
            val base = lerp(cs.primary, cs.surfaceContainerLowest, 0.78f)
            PlayerGlassColors(
                panel = base.copy(alpha = 0.84f),
                panelBrush = Brush.verticalGradient(
                    listOf(base.copy(alpha = 0.72f), base.copy(alpha = 0.90f)),
                ),
                panelSheen = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.06f),
                    0.35f to Color.Transparent,
                    1f to Color.Transparent,
                ),
                panelSolid = base.copy(alpha = 0.94f),
                // 强调色从主题色提纯：深底上要亮、要饱和，否则和面板同一片暗色
                accent = cs.accentColor(true),
                onGlass = Color.White.copy(alpha = 0.96f),
                onGlassSecondary = Color.White.copy(alpha = 0.62f),
                divider = Color.White.copy(alpha = 0.10f),
                selectedFill = lerp(cs.primary, Color.White, 0.38f).copy(alpha = 0.16f),
                scrim = Color.Black.copy(alpha = 0.40f),
                edgeTop = Color.White.copy(alpha = 0.20f),
                edgeBottom = Color.White.copy(alpha = 0.05f),
                isDark = true,
            )
        } else {
            // 日间也走**深色玻璃**。
            //
            // 这里改过三次，结论是：播放页的玻璃不该是亮色。
            //   0.72 白片 → 画面亮处把面板顶白，小字读不出；
            //   0.88 淡蓝片 → 字看清了，但一块亮片压在看片上，用户反馈"浅色系亮色确实很突兀"。
            // 视频画面是暗的、有内容的，控制层必须比画面**暗**才像"浮在上面的一层"，
            // 亮片无论多透都更像贴了张纸。
            //
            // 所以日间 = 深色半透明玻璃（比夜间更透、更偏主题色）：画面从下面透上来，
            // 白字永远可读，也和播放页控制条的白图标是同一套语言。
            //
            // alpha 从 0.82 收到 0.52~0.70 之间：0.82 时画面上只透出不到两成，
            // 视频基本被糊掉，"透"这件事完全看不出来。玻璃要能透，就必须让
            // 画面真的透上来 —— 文字可读性靠"下半部分压沉 + 白字投影"来兜。
            val base = lerp(Color.Black, cs.glassTint(), 0.30f)
            PlayerGlassColors(
                panel = base.copy(alpha = 0.62f),
                panelBrush = Brush.verticalGradient(
                    listOf(base.copy(alpha = 0.52f), base.copy(alpha = 0.72f)),
                ),
                panelSheen = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.10f),
                    0.38f to Color.Transparent,
                    1f to Color.Transparent,
                ),
                panelSolid = base.copy(alpha = 0.86f),
                // 深底上的强调色：提纯 + 提亮，否则和面板糊成一片暗色
                accent = cs.accentColor(true),
                onGlass = Color.White.copy(alpha = 0.96f),
                onGlassSecondary = Color.White.copy(alpha = 0.66f),
                divider = Color.White.copy(alpha = 0.12f),
                selectedFill = cs.accentColor(true).copy(alpha = 0.20f),
                scrim = Color.Black.copy(alpha = 0.26f),
                edgeTop = Color.White.copy(alpha = 0.22f),
                edgeBottom = Color.White.copy(alpha = 0.04f),
                isDark = true,
            )
        }
    }
}

/** 在非面板作用域里取玻璃色（例如 HUD、快进条） */
@Composable
fun playerGlass(): PlayerGlassColors = LocalPlayerGlass.current ?: rememberPlayerGlassColors()

/** 播放页浮层里所有「按下去」的颜色：白 12%，与 [PlayerGlassPress] 的波纹同值 */
val PlayerPressFill: Color = Color.White.copy(alpha = 0.12f)

/**
 * 播放页浮层的波纹配色。
 *
 * 播放页的浮层永远是深色玻璃，不跟应用的明暗模式走；而全站波纹在浅色主题下是
 * 「主题色 20%」—— 压在深色面板上会变成一块发灰的蓝，既不像"按下去"，
 * 也和同一块面板里的 [GlassRow] 按压淡片（白 12%）对不上。
 *
 * 所以播放页整块换成 白 12%：与 [GlassRow] 同值，图标按钮、文字按钮、选择行
 * 按下去的亮度一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerGlassPress(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalRippleConfiguration provides RippleConfiguration(
            color = Color.White,
            rippleAlpha = RippleAlpha(
                pressedAlpha = 0.12f,
                focusedAlpha = 0.12f,
                draggedAlpha = 0.12f,
                hoveredAlpha = 0.06f,
            ),
        ),
        content = content,
    )
}

/**
 * 播放页右侧滑入面板：播放列表、字幕音轨、视频信息、投屏、字幕目录共用。
 *
 * 为什么统一到右侧：
 * 横屏时画面是宽幅的，右侧三分之一是视线的余光区，面板从这边出来不遮挡画面主体；
 * 竖屏沿用同一个方向与同一套外观，切换横竖屏时弹窗不会「跳位置」。
 */
@Composable
fun PlayerSidePanel(
    visible: Boolean,
    landscape: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = rememberPlayerGlassColors()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideWidth = if (landscape) {
            // 34% 时播放列表里的长文件名会被挤成两行碎片，放宽到 39%
            // （约 356dp），仍只占右侧不到四成，画面主体不受影响
            (maxWidth * 0.39f).coerceIn(330.dp, 480.dp)
        } else {
            // 竖屏本来就只有一屏宽，留出一条画面缝隙，让人知道这是浮层而不是新页面
            (maxWidth * 0.90f).coerceIn(280.dp, 560.dp)
        }
        val shape = RoundedCornerShape(26.dp)

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(150)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(glass.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismiss() },
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                initialOffsetX = { it },
            ) + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                animationSpec = tween(210, easing = FastOutLinearInEasing),
                targetOffsetX = { it },
            ) + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            // 贴右、贴上、贴下三条边：面板是「从侧边推出来的一层」，不是浮在中间的卡片。
            // 只保留左侧两个圆角，右上/右下直角才能与屏幕边缘无缝贴合。
            val panelShape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)
            Column(
                Modifier
                    .width(sideWidth)
                    .fillMaxHeight()
                    .clip(panelShape)
                    .background(glass.panelBrush)
                    .background(glass.panelSheen)
                    // 顶亮底暗的一圈高光边：玻璃边缘反光的观感，比单色描边更像一层材质
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(glass.edgeTop, glass.edgeBottom)),
                        panelShape,
                    ),
            ) {
                CompositionLocalProvider(LocalPlayerGlass provides glass) {
                    // 面板内所有图标按钮 / 文字按钮 / 选择行共用播放页那一套按压色
                    PlayerGlassPress {
                        Column(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
    }
}

/**
 * 播放页右侧弹出卡片：解码方式、播放速度、顶部「更多」这类只有几行的短菜单。
 *
 * 和侧栏同一个方向、同一套玻璃底与强调色，只是高度跟着内容走，
 * 不为三项选择铺一整屏侧栏。
 */
@Composable
fun PlayerPopupCard(
    visible: Boolean,
    onDismiss: () -> Unit,
    width: Dp = 214.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = rememberPlayerGlassColors()
    val panelShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(120)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismiss() },
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                initialOffsetX = { it / 2 },
            ) + fadeIn(tween(160)),
            exit = slideOutHorizontally(
                animationSpec = tween(180, easing = FastOutLinearInEasing),
                targetOffsetX = { it / 2 },
            ) + fadeOut(tween(120)),
            // 与侧栏同样的贴边方式：贴右、贴上、贴下，内容从顶部往下排。
            // 之前是「内容少就缩成中间一小块」，在播放画面上像一块悬空的浮岛；
            // 现在改成整条贴边的面板，菜单项不论多少条，位置都一样、都在同一处出现。
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .clip(panelShape)
                    .background(glass.panelBrush)
                    .background(glass.panelSheen)
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(glass.edgeTop, glass.edgeBottom)),
                        panelShape,
                    ),
            ) {
                CompositionLocalProvider(LocalPlayerGlass provides glass) {
                    PlayerGlassPress {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(vertical = 10.dp),
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 玻璃面板里的选中行。
 *
 * 选中标识统一放在**右侧**（勾选图标）并用主题强调色，和列表、字幕、音轨、
 * 播放速度的选择风格完全一致——不再有「菜单靠左打勾、列表靠右打勾」两套。
 */
@Composable
fun GlassRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    /**
     * 说明文字：与标题同处一行结构，行距固定 2dp。
     *
     * 早先「播放顺序」把说明单独画在行下方，行与说明之间要跨过行自身的内边距，
     * 视觉上是一段空档；选中时标题变强调色、说明却还是灰的，读起来像两个不相干的东西。
     * 收进行内之后，两者同属一块玻璃，选中态一起变色，行距也不再受行内边距影响。
     */
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val glass = rememberPlayerGlassColors()
    val contentColor = if (selected) glass.accent else glass.onGlass
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectPressedWithTap()
    // 与全站同一个节奏（60ms 亮起 / 200ms 淡出）：白 7% 是即时切换的，
    // 手指抬起来是一下子消失，读起来像"闪了一下故障"而不是"按了一下"。
    val pressLevel = glassPressLevel(pressed)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    selected -> glass.selectedFill
                    // 按压反馈自己画：默认 ripple 会在圆角行上铺一块直角矩形
                    else -> PlayerPressFill.copy(alpha = PlayerPressFill.alpha * pressLevel)
                },
                RoundedCornerShape(14.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = if (selected) "取消选择" else "选择",
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = if (subtitle == null) 13.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) glass.accent else glass.onGlassSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    // 选中时说明一起转强调色（略降透明度保持层次），
                    // 不再出现「标题蓝、说明灰」的割裂感
                    color = if (selected) glass.accent.copy(alpha = 0.78f) else glass.onGlassSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "当前选中",
                tint = glass.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 播放页所有浮层共用的标题栏。
 *
 * 为什么必须抽出来：早先六个浮层各写各的 —— 播放列表是 titleLarge + 副标题 + 关闭，
 * 字幕音轨是裸 titleLarge，视频信息也是裸 titleLarge，投屏是大标题 + 三行长说明，
 * 解码/倍速这种短菜单则只是 12sp 的小标签。同一个播放页里点开不同弹窗，
 * 标题的字号、字重、留白、有没有分隔线全不一样，读起来像六个不同应用的弹窗。
 *
 * 这里定死一套：**titleMedium + SemiBold + 一行副标题 + 底部细分隔线**，
 * 左右留白固定，关闭按钮恒定在右侧同一位置。短菜单也用同一套（[compact] 只收窄上下留白，
 * 不改字号），所以「解码方式」和「播放列表」的标题看起来是同一个东西。
 */
@Composable
fun GlassPanelHeader(
    title: String,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    compact: Boolean = false,
    /** 水平留白：由调用方决定，保证标题与下方内容的左边缘在同一条竖线上 */
    inset: Dp = 0.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val glass = playerGlass()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = if (onBack != null) (inset - 12.dp).coerceAtLeast(0.dp) else inset, end = inset)
                .padding(
                    top = if (compact) 6.dp else 14.dp,
                    bottom = if (compact) 6.dp else 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                GlassIconButton(onClick = onBack, fillOverride = PlayerPressFill) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回上一层",
                        tint = glass.onGlass,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = glass.onGlass,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.onGlassSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
            if (onClose != null) {
                GlassIconButton(onClick = onClose, fillOverride = PlayerPressFill) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = glass.onGlassSecondary,
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = inset)
                .height(1.dp)
                .background(glass.divider),
        )
    }
}

/**
 * 目录选择器面包屑的一级。[path] 是点击后要跳到的路径；[clickable] 为 false 表示当前目录。
 */
data class PickerCrumb(val label: String, val path: String, val clickable: Boolean = true)

/**
 * 本地 / 网络字幕选择器共用的路径面包屑。
 *
 * 最上层目录用调用方给的友好名（本地 = 「根目录」，网络 = 「共享根目录」），
 * 面板不再另设路径副标题 —— 面包屑本身就是完整路径的可交互展示，两者并存只会重复。
 * 层级深时横向滚动，并在路径变化时自动滚到末尾（当前目录永远是用户最关心的）。
 */
@Composable
fun PickerBreadcrumb(
    crumbs: List<PickerCrumb>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (crumbs.isEmpty()) return
    val glass = playerGlass()
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    "›",
                    style = MaterialTheme.typography.labelMedium,
                    color = glass.onGlassSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            val last = index == crumbs.lastIndex
            Text(
                crumb.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (last) glass.onGlass else glass.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(
                        if (crumb.clickable && !last) {
                            Modifier.glassPressable(
                                radius = 6.dp,
                                role = Role.Button,
                                fillOverride = PlayerPressFill,
                            ) { onNavigate(crumb.path) }
                        } else Modifier.clip(RoundedCornerShape(6.dp)),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 「打开目录」类选择器（本地字幕 / 网络字幕）的面板外壳。
 *
 * 这两个浮层原来是居中大白卡片，从横屏播放页点开会在画面正中铺一块白，
 * 和播放页其它弹窗完全不是一套外观。现在改成和侧栏一致的右侧玻璃面板：
 * 同一个方向、同一套配色，翻目录时画面主体还在左边没被整块盖死。
 */
@Composable
fun PlayerPickerPanel(
    onDismiss: () -> Unit,
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    breadcrumb: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = rememberPlayerGlassColors()
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val shape = RoundedCornerShape(26.dp)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideWidth = if (landscape) {
            (maxWidth * 0.40f).coerceIn(320.dp, 520.dp)
        } else {
            (maxWidth * 0.92f).coerceIn(280.dp, 560.dp)
        }
        // Dialog 自带遮罩，这里只画一层很淡的，点空白处关闭
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )
        AnimatedVisibility(
            visible = shown,
            enter = slideInHorizontally(
                animationSpec = tween(260, easing = FastOutSlowInEasing),
                initialOffsetX = { it },
            ) + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(180)) { it } + fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            // 同样贴右/上/下三边，只留左侧圆角
            val panelShape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)
            Column(
                Modifier
                    .width(sideWidth)
                    .fillMaxHeight()
                    .clip(panelShape)
                    .background(glass.panelBrush)
                    .background(glass.panelSheen)
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(glass.edgeTop, glass.edgeBottom)),
                        panelShape,
                    ),
            ) {
                CompositionLocalProvider(LocalPlayerGlass provides glass) {
                  PlayerGlassPress {
                    // 目录选择器与其余浮层共用同一个标题栏：返回箭头、标题、副标题、
                    // 右侧操作、分隔线全部对齐，翻目录时不会突然换一套标题样式
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 4.dp),
                    ) {
                        GlassPanelHeader(
                            title = title,
                            onBack = onBack,
                            inset = 12.dp,
                            actions = actions,
                        )
                    }
                    if (breadcrumb != null) {
                        Column(Modifier.fillMaxWidth()) {
                            breadcrumb()
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(glass.divider),
                            )
                        }
                    }
                    Column(Modifier.fillMaxSize()) { content() }
                  }
                }
            }
        }
    }
}
