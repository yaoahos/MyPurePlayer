package com.yaoahos.mypureplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全站「玻璃」外观。
 *
 * 为什么自绘而不是引入 miuix-glass：
 * 1. miuix-glass 要求 Android 13（API 33）起步，本项目 minSdk 是 24，
 *    直接引用等于把 Android 7~12 的用户全部砍掉；
 * 2. 它是 Miuix（Compose Multiplatform）主题体系的一部分，组件依赖 MiuixTheme，
 *    和本项目现有 Material3 主题并存会出现两套主题、两套尺寸规范。
 *
 * 所以这里用同一套思路自己做。玻璃能被"看见"靠三件事，缺一不可：
 *   a) 片体本身与身后背景**不是同一个亮度**（不然半透明等于没有）；
 *   b) 片体与内容交界处有一条亮线（光在厚度边缘的折射，是玻璃最关键的识别特征）；
 *   c) 片体向背景投一层带主题色的柔和阴影（它浮在内容之上，不是一个色块）。
 *
 * 色相：片体不是白的。日间 = 白里掺主题色（淡蓝玻璃），夜间 = 深底里掺主题色。
 * 早先版本用「白色 84% 叠在近白页面上」，实测顶栏与页面只差 12/255，
 * 肉眼看就是同一种白，于是"感觉没有玻璃面板"——那是白色塑料，不是玻璃。
 */
@Immutable
data class AppGlassColors(
    /** 卡片底：能透出背景光斑 */
    val panel: Color,
    /** 弹窗/面板底：比卡片更实，保证文字稳定 */
    val panelStrong: Color,
    /**
     * 弹窗面板里的选项条。
     *
     * 弹窗面板本身是一块**比页面深一档**的淡色玻璃（见 [appDialogSurface]），
     * 若选项条继续用 [panel]（同样深），两者只差 1~2 个色阶，一屏的选项会糊成一整块。
     * 所以这一档刻意比面板**更亮、更白**，形成「深玻璃板 + 亮玻璃条」的层次 ——
     * 与页面「浅底 + 深卡片」方向相反，但浮层里的层次本来就该这样读。
     */
    val dialogItem: Color,
    /** 栏位（顶栏/底栏）底 */
    val bar: Color,
    /** 栏位内容侧的那条亮线（玻璃厚度的高光） */
    val barEdge: Color,
    /** 栏位内容侧紧外侧的极细分界线（把栏位从页面里勾出来） */
    val barLine: Color,
    /** 描边：上亮下暗 */
    val borderTop: Color,
    val borderBottom: Color,
    /** 悬浮按钮 / 强调块：比卡片更浓的一层主题色玻璃 */
    val fab: Color,
    /**
     * 强调色：选中图标/文字、可点按的强调项用。
     *
     * 与 [tint]（玻璃的面色）刻意拉开：面色永远是低饱和的浅片，
     * 强调色是高饱和、与面色有明显明度差的实色，两者放在一起不会糊成一块。
     */
    val accent: Color,
    val tint: Color,
    val tintSoft: Color,
    val isDark: Boolean,
)

val LocalAppGlass = compositionLocalOf<AppGlassColors?> { null }

/**
 * 玻璃的「着色剂」：只借主题色的**色相**，明度与饱和度重新归一。
 *
 * 为什么不直接拿 `colorScheme.primary` 往白里掺：
 * 动态取色（Android 12+ 从壁纸取色）给出的 primary 往往又暗又闷 —— 本机实测
 * `#4C5E8B`，明度只有 0.55、饱和度 0.45。这种颜色掺进近白页面，结果是**降低亮度、
 * 加一点点灰蓝**，玻璃看起来就是"一块灰片"，而且比页面更灰更脏 —— 用户说的
 * "底栏与页面配色不太和谐"，根子在这里，不在掺的比例。
 *
 * 所以把色相留下，明度拉满、饱和度给一个下限，得到一个"干净的有色光"，
 * 再由"掺多少"去控制浓淡：
 *   - 蓝主题 → 淡蓝玻璃，紫主题 → 淡紫玻璃，绿主题 → 淡绿玻璃（动态取色照样跟随）；
 *   - 无论壁纸给的 primary 多暗，玻璃的色相浓度都不会塌成灰。
 *
 * 注意只用于**面**（页面渐变、卡片、栏位）。文字/图标的强调色仍然用真 primary，
 * 保证强调色在不同主题下与系统其它 App 一致。
 */
internal fun androidx.compose.material3.ColorScheme.glassTint(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(primary.toArgb(), hsv)
    val hue = hsv[0]
    val sat = hsv[1].coerceIn(0.38f, 0.68f)
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, valueForLuminance(hue, sat, 0.40f))))
}

/**
 * 求一个明度，使该色相的亮度正好落在 [target]。
 *
 * 为什么必须归一：亮度是"掺多少"的分母。同样的"页面比白底暗 3%"，
 * 靛蓝的着色剂很暗、只掺 4% 就够；青碧的着色剂很亮（青色的相对亮度天生接近满值），
 * 要掺 25% 才降得下来 —— 结果同样一套玻璃，蓝主题是淡淡一层，
 * 青碧主题整页变成薄荷糖。把着色剂的亮度统一压到 0.40 之后，
 * 任何一个主题"掺多少"都落在同一个量级，玻璃浓淡不再随色相漂移。
 */
private fun valueForLuminance(hue: Float, saturation: Float, target: Float): Float {
    var low = 0f
    var high = 1f
    repeat(14) {
        val mid = (low + high) / 2f
        val lum = Color(
            android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, mid)),
        ).luminance()
        if (lum < target) low = mid else high = mid
    }
    return (low + high) / 2f
}

/**
 * 强调色：从主题色提纯出来的高饱和实色。
 *
 * 为什么不能直接用 `colorScheme.primary` 当选中色：动态取色给的 primary 饱和度常常
 * 只有 0.4 左右（本机 `#51638D`），压在同样带蓝的底栏上，两者是**同一支暗淡的蓝**，
 * 「选中的那一项几乎看不见」—— 用户原话。这里把饱和度抬到 0.72 以上，
 * 浅色下压低明度换对比度、深色下用满明度换亮度，保证它永远比玻璃面色显眼。
 */
internal fun androidx.compose.material3.ColorScheme.accentColor(dark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(primary.toArgb(), hsv)
    hsv[1] = hsv[1].coerceIn(0.72f, 1f)
    // 同样不能写死明度：青/绿/琥珀的 v=0.70 出来仍是很亮的浅色（#18A8A8 白底仅 2.9:1），
    // 用户反馈的「青碧、松绿主题看不清」在底栏选中态上最明显。
    // 改成按目标亮度反解：浅色 0.20（白玻璃上 4.4:1）、深色 0.62（深玻璃上 10:1 以上）。
    val target = if (dark) 0.62f else 0.20f
    var low = 0f
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2f
        val c = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], mid)))
        if (c.luminance() < target) low = mid else high = mid
    }
    hsv[2] = (low + high) / 2f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * 按「目标亮度」掺色，而不是按固定比例。
 *
 * 固定比例在两种主题下会走出两个极端：静态亮蓝主题（primary `#2F6BFF`）掺 40% 就是
 * 一块很响的蓝带，从页面到底栏像被切了一刀（用户反馈"非常不自然"）；
 * 而壁纸取色给的暗蓝掺同样比例却几乎看不见。
 *
 * 所以先定"落地后应该多亮"，再反解掺多少：不管着色剂多鲜艳，玻璃与页面的层次一致。
 */
private fun mixToLuminance(base: Color, tint: Color, target: Float): Color {
    val baseLum = base.luminance()
    val tintLum = tint.luminance()
    val delta = baseLum - tintLum
    // 目标与基准同向才有解；否则（着色剂比底色还亮/还暗）退回极小的固定掺比
    if (kotlin.math.abs(delta) < 0.0001f) return lerp(base, tint, 0.10f)
    val k = ((baseLum - target) / delta).coerceIn(0f, 1f)
    return lerp(base, tint, k)
}

/**
 * 玻璃配色随明暗模式切换：
 *
 *   日间 = 淡色玻璃：白里掺主题色，半透明，靠一条细界线 + 柔和阴影浮起来；
 *   夜间 = 深色玻璃：深底里掺主题色，靠低透明度白色高光提亮边缘。
 *
 * 这不是「同一套色值调透明度」，而是两套独立的取值逻辑：浅色下玻璃要"更亮更透"，
 * 深色下玻璃要"更深"，方向相反。用同一套反而两边都不像玻璃。
 *
 * 关键：**玻璃是有颜色的**。日间那层 `lerp(White, primary, .10f)` 让每块面板都带
 * 当前主题的色相（蓝主题偏淡蓝、紫主题偏淡紫），而不是一片白。
 */
@Composable
fun appGlass(): AppGlassColors {
    LocalAppGlass.current?.let { return it }
    val cs = MaterialTheme.colorScheme
    val dark = cs.isDarkScheme()
    val tint = cs.glassTint()
    val accent = cs.accentColor(dark)
    return androidx.compose.runtime.remember(cs.primary, cs.surface, dark) {
        if (dark) {
            AppGlassColors(
                // 夜间：深底 + 同色相的干净色光。层次同样按"落地亮度"定，
                // 不按比例 —— 壁纸取色给的色光可能很亮，按比例会把面板推成灰白
                panel = mixToLuminance(cs.surface, tint, 0.014f).copy(alpha = 0.56f),
                panelStrong = mixToLuminance(cs.surface, tint, 0.020f).copy(alpha = 0.94f),
                // 深色下「更亮」才是浮起来：比面板提亮两档，叠在深面板上仍保持通透
                dialogItem = mixToLuminance(cs.surface, tint, 0.058f).copy(alpha = 0.62f),
                bar = mixToLuminance(cs.surface, tint, 0.030f).copy(alpha = 0.92f),
                barEdge = Color.White.copy(alpha = 0.13f),
                barLine = Color.White.copy(alpha = 0.05f),
                borderTop = Color.White.copy(alpha = 0.14f),
                borderBottom = Color.Black.copy(alpha = 0.22f),
                // 悬浮按钮：比栏位再亮一点点（深色下要"浮起来"，不是"挖下去"）。
                // 早期在深色里只掺 0.075 的主题色，结果按钮就是一块比页面略深半档的
                // 黑方块，蓝加号孤零零飘着 —— 用户会再次觉得"太深、没质感"。
                // 现在掺 0.19 让它明显是同一族里更亮的一层玻璃，配合描边与投影站住。
                fab = mixToLuminance(cs.surface, tint, 0.125f).copy(alpha = 0.94f),
                accent = accent,
                tint = cs.primary,
                tintSoft = cs.primary.copy(alpha = 0.18f),
                isDark = true,
            )
        } else {
            // 日间：整页只有一个色相族 —— 页面、卡片、弹窗、底栏全部由
            // 「背景色掺玻璃着色剂」推导，层次改由**目标亮度**决定
            // （0.895 → 0.86 → 0.82），不再用固定比例。
            //
            // 固定比例的坑：静态亮蓝主题（primary #2F6BFF）掺 40% 会得到一条很响的蓝带，
            // 从页面切到底栏像被割开；而壁纸取色给的暗蓝掺 40% 又几乎看不见。
            // 按亮度定档之后，两种主题下"页面→底栏"的落差看起来是一样的。
            AppGlassColors(
                // 卡片：比页面深一档的淡蓝玻璃。日间页几乎全白，卡片若再"掺白提亮"
                // 就和页面同色了；半透明 0.65 让页面渐变透上来，"玻"的那部分靠透光
                panel = mixToLuminance(cs.background, tint, 0.852f).copy(alpha = 0.66f),
                // 弹窗 / 抽屉：不透明，保证文字稳定
                panelStrong = mixToLuminance(cs.background, tint, 0.860f).copy(alpha = 0.96f),
                // 日间同理反向：弹窗面板压到 0.89 左右的淡蓝玻璃，选项条必须回到近白
                dialogItem = mixToLuminance(cs.background, tint, 0.955f).copy(alpha = 0.88f),
                // 底栏：族里最深的一档，与页面底部之间是"同一支蓝、深半档"的过渡，
                // 不是一个色块接一个色块
                bar = mixToLuminance(cs.background, tint, 0.820f).copy(alpha = 0.84f),
                barEdge = Color.White.copy(alpha = 0.95f),
                barLine = tint.copy(alpha = 0.24f),
                borderTop = Color.White.copy(alpha = 0.85f),
                borderBottom = tint.copy(alpha = 0.20f),
                // 悬浮按钮：比卡片（0.852）、栏位（0.820）再深半档，
                // 但仍然是同一族的浅玻璃 —— 靠 accent 的图标把它顶成主操作，
                // 而不是把整块压成深色圆片（用户第 19 轮："背景颜色太深、没有玻璃质感"）
                fab = mixToLuminance(cs.background, tint, 0.742f).copy(alpha = 0.95f),
                accent = accent,
                tint = cs.primary,
                tintSoft = cs.primary.copy(alpha = 0.12f),
                isDark = false,
            )
        }
    }
}

/**
 * 当前配色是不是深色。
 *
 * **不能用 `isSystemInDarkTheme()`**：本应用允许在设置里强制深色/浅色，
 * 系统是浅色而应用强制深色时，两者会打架 —— 结果是深色页面配一套"日间浅色玻璃"，
 * 面板糊成一片白。这里直接读主题实际背景色的亮度，跟随的是真正生效的配色，
 * 也顺带兼容动态取色（动态取色给的背景色亮度不同也能正确判断）。
 */
@Composable
fun ColorScheme.isDarkScheme(): Boolean = background.luminance() < 0.5f

/** 页面渐变的下端颜色：整页只有一个色相，底部比顶部深半档（同样按亮度定档） */
@Composable
internal fun pageGradientBottom(cs: androidx.compose.material3.ColorScheme): Color =
    if (cs.isDarkScheme()) mixToLuminance(cs.surface, cs.glassTint(), 0.011f)
    else mixToLuminance(cs.background, cs.glassTint(), 0.925f)

/**
 * 全站页面背景：主题底色 + 一层同色相的主题色薄纱 + 顶部一层薄白。
 *
 * 三条约束，破了任何一条都不好看：
 *
 * 1) **不许出现圆形光斑**。早先用过两团径向渐变（左上蓝、右下暖），在纯色页面上
 *    形成两个明显的圆形边界，用户原话"有一种圆形眩光的感觉"。所以只用线性渐变。
 *
 * 2) **只用主题色一个色相**。旧版斜向是 primary → 透明 → tertiary，页面右下角因此
 *    掺进一层暖色（实测 240,239,248 偏紫，底部偏黄灰），而底栏是冷蓝 —— 冷暖同屏，
 *    这就是"底栏与页面配色不太和谐"的来源。现在页面的每一档都是
 *    `背景色掺主题色`，和卡片、底栏严格同族，只差掺的比例。
 *
 * 3) **顶部压一层薄白**。纯色渐变从上到下容易发闷（越往下越"脏"），
 *    顶部提亮能让页面显清透，也给透明的顶栏留出落字的空间。
 *
 * 由 [Modifier.glassPageBackground] **每个页面自己铺一张**。
 *
 * 为什么页面必须自己铺：页面若透明，转场时新旧两页会互相透出对方的内容，
 * 读起来就是"切换有残影"。每个页面各画一张不透明渐变，进入的那页就能把
 * 离开的那页盖住，转场参数完全不用动。
 */
/**
 * 页面级背景：整块不透明渐变，转场时用于遮住下面那一页。
 *
 * **性能**：这是每帧都要执行的整屏着色。早先它是三层独立的全屏渐变
 * （底色竖向 + 顶部薄白竖向 + 主题色横向），转场时新旧两页同时在场，一帧要叠
 * 多次整屏 shader —— 实测返回底栏时会出现 69ms 的单帧。
 *
 * 现在：
 *  1. 顶部薄白本来就是**竖向**的，直接与底色渐变合并成一条多点渐变（预混好颜色），
 *     少一次整屏透明度混合；
 *  2. Brush 用 remember 缓存，不再每次重组都新建着色器对象；
 *  3. 只剩两层（竖向 + 横向）。
 */
@Composable
fun Modifier.glassPageBackground(): Modifier {
    val cs = MaterialTheme.colorScheme
    val dark = cs.isDarkScheme()
    val top = if (dark) cs.surface else cs.background
    val bottom = pageGradientBottom(cs)
    val white = if (dark) 0.03f else 0.34f
    val sideAlpha = if (dark) 0.09f else 0.050f
    val primary = cs.primary

    // 把「底色竖向渐变 + 顶部薄白 + 横向主题色薄纱」预渲染成一张 64×256 的小图。
    //
    // 为什么不用 Brush 直接画：这是**每帧**都要执行的整屏着色。原来两条 Brush 各自
    // 是一遍整屏渐变（1080×2340 = 250 万像素的逐像素着色 + 一次透明度混合），
    // 转场时新旧两页同时在场，一帧要跑四遍。实测（本机 dumpsys gfxinfo）：
    //    两条渐变 → GPU 90 分位 21ms、整帧 90 分位 32ms（返回时肉眼可见卡一下）
    //    换成单色 → GPU 90 分位 10ms、整帧 90 分位 18ms
    // 也就是说这两遍渐变一个人就吃掉了十几毫秒，是"从设置/历史返回更多卡顿"的真凶。
    //
    // 渐变本身是线性的，用 64×256 采样 + 双线性放大能**逐像素还原**原样（线性插值
    // 还原线性渐变，误差只来自 8bit 量化），而绘制侧从"逐像素算渐变"降级成
    // "一次纹理采样" —— 64KB 的贴图，比整屏渐变便宜一个数量级。
    val backdrop = androidx.compose.runtime.remember(top, bottom, white, sideAlpha, primary) {
        renderPageBackdrop(top, bottom, white, sideAlpha, primary)
    }
    return this
        // layer 放在最前面：让背景也一起进入图层，转场时整页（含背景）只是被移动。
        //
        // 这里保持**空 lambda**，不要为了"预热"给它塞 alpha 之类的属性。
        // 实测（关于页返回首帧）：空 lambda 时 draw 16ms / GPU 11.8ms / 整帧 37ms；
        // 改成 alpha = 0.999f 强制常驻纹理后反而恶化到 draw 27.9ms / 整帧 72.9ms ——
        // 独立的整屏纹理每帧都要重新合成，比"静止时折叠、需要时再录"更贵。
        .graphicsLayer { }
        .drawBehind {
            drawImage(
                image = backdrop,
                dstSize = androidx.compose.ui.unit.IntSize(
                    size.width.toInt().coerceAtLeast(1),
                    size.height.toInt().coerceAtLeast(1),
                ),
                // 双线性：放大的是一张只有 64×256 的线性渐变，插值后与原渐变一致
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
            )
        }
}

/**
 * 玻璃弹窗面板：一块**与页面不同色**的淡色玻璃，上沿受光、下沿下沉，靠上亮下暗的描边勾出厚度。
 *
 * 早先这里直接复用 [Modifier.glassPageBackground] 的同一张渐变（同 top、同 bottom、
 * 同 0.34 的顶部薄白），弹窗与身后页面的亮度都落在 0.93~0.97 的近白区间，只差几个色阶 ——
 * 除了投影和一圈描边，看不出这是一块面板，用户的原话是「底板接近白色，不像玻璃面板」。
 * 这正是文件开头那条约束：玻璃要能被看见，前提是**片体与背景不是同一个亮度**。
 *
 * 现在弹窗单独取一组色：比页面深一档、掺主题色更足的淡蓝玻璃（日间亮度约 0.89~0.94，
 * 页面是 0.93~0.97），并且把「受光」从整片泛白改成一圈有落差的顶光，所以它像一块
 * 有厚度的玻璃片压在页面上，而不是页面里挖出来的一个白框。
 *
 * 弹窗里的选项条仍叠 [appGlassSurface]（更透的 panel），与面板底保持 4% 左右的亮度差，
 * 保证那一根根选项条在面板上依然数得清。
 */
@Composable
fun Modifier.appDialogSurface(
    shape: Shape = RoundedCornerShape(26.dp),
    elevation: Dp = 8.dp,
): Modifier {
    val cs = MaterialTheme.colorScheme
    val dark = cs.isDarkScheme()
    val colorTint = cs.glassTint()
    // 顶 / 底各自按「目标亮度」定档：日间比页面深一档并带明显的主题色相，
    // 夜间比页面亮一档（深色下"浮起来"要更亮，方向与日间相反，见 appGlass 的注释）
    val top = if (dark) mixToLuminance(cs.surface, colorTint, 0.032f)
    else mixToLuminance(cs.background, colorTint, 0.900f)
    val bottom = if (dark) mixToLuminance(cs.surface, colorTint, 0.019f)
    else mixToLuminance(cs.background, colorTint, 0.866f)
    // 受光只压顶部一段（22% 内衰减到 0），不再整片泛白 —— 整片白就是"白纸"的来源
    val topLight = if (dark) 0.11f else 0.20f
    val sideAlpha = if (dark) 0.11f else 0.060f
    val glass = appGlass()
    val tile = androidx.compose.runtime.remember(top, bottom, topLight, sideAlpha, cs.primary) {
        renderDialogBackdrop(top, bottom, topLight, sideAlpha, cs.primary)
    }
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.12f),
            spotColor = Color.Black.copy(alpha = 0.18f),
        )
        .clip(shape)
        .drawBehind {
            drawImage(
                image = tile,
                dstSize = androidx.compose.ui.unit.IntSize(
                    size.width.toInt().coerceAtLeast(1),
                    size.height.toInt().coerceAtLeast(1),
                ),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
            )
        }
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)),
            shape = shape,
        )
}

/**
 * M3 AlertDialog 的玻璃底。
 *
 * AlertDialog 只能通过 modifier 换外观，因此调用方必须同时把 `containerColor`
 * 传成 Transparent —— 否则 M3 会先用 [AppGlassColors.panelStrong]（接近不透明）
 * 铺满整个 Surface，这层渐变就完全看不见了。
 *
 * 圆角要传成与 `shape` 相同的值：描边与裁剪是两条路径，不一致会在四角露出线头。
 *
 * 投影用 0dp：AlertDialog 自带窗口阴影，而且弹窗窗口是按内容尺寸开的，
 * 外部投影会被窗口边界裁掉一半，反而在上下出现直边。
 */
@Composable
fun Modifier.alertDialogGlassSurface(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(26.dp),
): Modifier {
    // 注意：这里**不能**关掉窗口 dim。AlertDialog 没有自绘遮罩，全靠窗口 dim 压暗页面；
    // 关掉它页面会一动不动，弹窗像贴纸一样浮着。只有「窗口 dim + 自绘遮罩」叠两层的
    // 自绘 Dialog（GlassChoiceDialog / 更新框 / 保险箱解锁）才需要关窗口那一层。
    return appDialogSurface(shape, elevation = 0.dp)
}

/**
 * 页面底色回退：把渐变的几档颜色按 64×256 画进一张小图。
 *
 * 采样点与原 [Brush] 版一模一样（0 / 18% / 32% / 45% / 100%），横向薄纱也是
 * 同一条 0 → 48% → 100% 的透明度曲线，所以这里**不是近似**，是同一张图换了个画法。
 */
private fun renderPageBackdrop(
    top: Color,
    bottom: Color,
    white: Float,
    sideAlpha: Float,
    primary: Color,
): androidx.compose.ui.graphics.ImageBitmap {
    val w = 64
    val h = 256
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint()

    fun at(t: Float, a: Float): Int =
        androidx.compose.ui.graphics.lerp(
            androidx.compose.ui.graphics.lerp(top, bottom, t),
            Color.White,
            a,
        ).toArgb()

    paint.shader = android.graphics.LinearGradient(
        0f, 0f, 0f, h.toFloat(),
        intArrayOf(
            at(0f, white),
            at(0.18f, white * 0.60f),
            at(0.32f, white * 0.27f),
            at(0.45f, 0f),
            bottom.toArgb(),
        ),
        floatArrayOf(0f, 0.18f, 0.32f, 0.45f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

    paint.shader = android.graphics.LinearGradient(
        0f, 0f, w.toFloat(), 0f,
        intArrayOf(0, primary.copy(alpha = sideAlpha).toArgb(), 0),
        floatArrayOf(0f, 0.48f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

    return bmp.asImageBitmap()
}

/**
 * 弹窗面板的贴图：上沿受光，22% 内就衰减到零，下半段回到平色，再压一层主题色横向薄纱。
 *
 * 与 [renderPageBackdrop] 唯一的差别是**受光只占顶部一小段**。页面是整屏大面积，
 * 顶部薄白不能有可见边界（否则像贴了一条白带）；弹窗只占屏幕几分之一，受光必须有落差，
 * 才读得成「一块有厚度的玻璃片」。这也是弹窗不再是白纸的关键：明暗差集中在顶部一圈，
 * 配合上白下暗的描边，眼睛看到的是折边，不是填色。
 */
private fun renderDialogBackdrop(
    top: Color,
    bottom: Color,
    topLight: Float,
    sideAlpha: Float,
    primary: Color,
): androidx.compose.ui.graphics.ImageBitmap {
    val w = 64
    val h = 256
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint()

    fun at(t: Float, a: Float): Int =
        androidx.compose.ui.graphics.lerp(
            androidx.compose.ui.graphics.lerp(top, bottom, t),
            Color.White,
            a,
        ).toArgb()

    paint.shader = android.graphics.LinearGradient(
        0f, 0f, 0f, h.toFloat(),
        intArrayOf(
            at(0f, topLight),
            at(0.10f, topLight * 0.52f),
            at(0.22f, topLight * 0.16f),
            at(0.34f, 0f),
            bottom.toArgb(),
        ),
        floatArrayOf(0f, 0.10f, 0.22f, 0.34f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

    paint.shader = android.graphics.LinearGradient(
        0f, 0f, w.toFloat(), 0f,
        intArrayOf(0, primary.copy(alpha = sideAlpha).toArgb(), 0),
        floatArrayOf(0f, 0.48f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

    return bmp.asImageBitmap()
}

/**
 * 把一张位图按绘制区域缩放着铺满形状，用 Shader 填充代替 clip。
 *
 * 用 Android 的 [android.graphics.BitmapShader] + 本地矩阵做缩放：Compose 的
 * ImageShader 不接受矩阵，而这里必须按目标尺寸缩放，否则只会铺满左上角。
 * Shader 由 Compose 按绘制尺寸缓存（尺寸变化才会重调 createShader），
 * 所以每帧只是一次 drawRoundRect 级别的填充，不涉及离屏图层。
 */
internal class BitmapFillBrush(private val bitmap: android.graphics.Bitmap) :
    androidx.compose.ui.graphics.ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader {
        val sx = if (bitmap.width > 0) size.width / bitmap.width else 1f
        val sy = if (bitmap.height > 0) size.height / bitmap.height else 1f
        val shader = android.graphics.BitmapShader(
            bitmap,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP,
        )
        shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(sx, sy) })
        return shader
    }
}

/** 玻璃卡片的修饰符：半透明底 + 片体光感 + 高光描边 + 柔和投影 */
@Composable
fun Modifier.appGlassSurface(
    shape: Shape = RoundedCornerShape(20.dp),
    strong: Boolean = false,
    elevation: Dp = 2.dp,
    tinted: Boolean = false,
    /**
     * 显式指定面色：动作按钮用更深一档的 [AppGlassColors.fab]；
     * 弹窗里的选项条用更亮的 [AppGlassColors.dialogItem]。
     * 与 [tinted] 同时给出时**以 tinted 为准**，选中态不会被面色盖掉。
     */
    fillOverride: Color? = null,
): Modifier {
    val glass = appGlass()
    // 选中态优先：tinted 表示「当前生效的那一项」，不能被 fillOverride（普通面色）盖掉 ——
    // 否则弹窗里指定了亮面的选项条，选中与未选中长得一模一样。
    val fill = when {
        tinted -> glass.tintSoft
        fillOverride != null -> fillOverride
        strong -> glass.panelStrong
        else -> glass.panel
    }
    // 光从上面来：片体顶端受光、向下渐暗。
    // 一块**单一色值**的半透明矩形不管多透明都像塑料片；玻璃之所以像玻璃，
    // 是因为片体自身的亮度不是均匀的 —— 上沿亮、下沿沉，中间还有一道斜光。
    val topLight = if (glass.isDark) 0.11f else 0.16f
    val bottomLight = if (glass.isDark) 0.03f else 0.05f

    // 面色 + 竖向受光 + 斜向高光，合成一张 96×96 的小图再缩放铺满卡片。
    //
    // 原来这里是三层 `background`：一层实色、两遍整卡面积的渐变混合。单张卡片看不出来，
    // 但设置页 / 关于页是"整页都是卡片"，加起来等于每帧多刷两张全屏渐变 ——
    // 页背景改成预渲染贴图之后，这几层就成了剩下的主要开销（返回设置 26ms vs 返回历史 17ms，
    // 差别就是卡片数量）。
    //
    // 卡片上的三种光感都是**相对自身范围**的线性渐变，所以同一张 96×96 的图
    // 拉伸到任意卡片尺寸都成立，双线性插值能还原线性渐变。
    val diagonalLight = if (glass.isDark) 0.07f else 0.13f
    val tile = androidx.compose.runtime.remember(fill, glass.isDark, topLight, bottomLight) {
        cachedSurfaceTile(fill, topLight, bottomLight, diagonalLight)
    }

    return this
        // 卡片不再用 Modifier.shadow。
        //
        // 玻璃片体的底是半透明的（panel alpha 0.66），而 shadow 画在片体**下面**：
        // 投影的轮廓与模糊会被半透明的片体透出来，在卡片内侧形成一圈边界笔直的
        // 暗色带 —— 上薄下厚，正是反复被提到的「玻璃条里多出来的一块直角矩形」。
        // 卡片与页面的层次已经由上下两条亮/暗描边 + 片体自身的受光渐变表达，
        // 去掉投影之后片体内部第一次是干净的。
        //
        // 底色直接用「贴图填充形状」画出来，不再挂 Modifier.clip。
        //
        // clip 的实现是 graphicsLayer(clip = true)：每个卡片节点都会被分配一个
        // 离屏图层。这台设备上每分配一个图层约 7ms，一屏二十张缩略图卡在进入
        // 目录时就要 200ms 一帧（慢放 10 倍能直接看到一次明显停顿）；
        // 文件夹目录三十二张也要 110ms。改成 drawOutline(brush) 后是纯 GPU 填充，
        // 不建图层、不做整卡离屏渲染，视觉完全一致（同样的形状、同样的渐变色）。
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val brush = BitmapFillBrush(tile.asAndroidBitmap())
            val path = Path().apply { addOutline(outline) }
            onDrawBehind { drawPath(path, brush) }
        }
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(glass.borderTop, glass.borderBottom),
            ),
            shape = shape,
        )
}

/**
 * 同配色的贴图只生成一次，全站共享。
 *
 * 原来每张卡片在自己的 `remember` 里各渲染一张 96×96：设置页二十来行、更多页七行、
 * 配置备份四组、播放列表每行一张 —— 进页面那一帧要连着做二十多次 Bitmap 分配
 * 加 Canvas 绘制。贴图是只读的，多个节点画同一张 Bitmap 完全安全，
 * 所以按「面色 + 三档光」共享一份即可。
 *
 * 容量 24：同一屏上真正不同的配色只有几种（页面卡片、弹窗选项条、选中态、深色一套），
 * 动态取色换主题时旧条目被 LRU 自然淘汰。
 */
private val surfaceTiles =
    android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24)

private fun cachedSurfaceTile(
    fill: Color,
    topLight: Float,
    bottomLight: Float,
    diagonalLight: Float,
): androidx.compose.ui.graphics.ImageBitmap {
    val key = "${fill.value}|$topLight|$bottomLight|$diagonalLight"
    surfaceTiles.get(key)?.let { return it }
    return renderSurfaceTile(fill, topLight, bottomLight, diagonalLight)
        .also { surfaceTiles.put(key, it) }
}

/**
 * 卡片面色：三层光感按 96×96 预渲染（竖向受光 + 斜向高光），与原来三支 Brush 同值。
 */
private fun renderSurfaceTile(
    fill: Color,
    topLight: Float,
    bottomLight: Float,
    diagonalLight: Float,
): androidx.compose.ui.graphics.ImageBitmap {
    val n = 96
    val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint()
    val f = n.toFloat()

    // ① 面色
    canvas.drawColor(fill.toArgb())

    // ② 竖向光感：上亮下暗（0 → 30% → 100%）
    fun white(a: Float): Int = Color.White.copy(alpha = a.coerceIn(0f, 1f)).toArgb()
    paint.shader = android.graphics.LinearGradient(
        0f, 0f, 0f, f,
        intArrayOf(white(topLight), white(bottomLight), 0),
        floatArrayOf(0f, 0.30f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, f, f, paint)

    // ③ 斜向高光：从左上一角扫过来的一道（0 → 32% → 100%）
    //    轴取贴图的角到角：贴图被拉伸到卡片尺寸后，等价于原来卡片空间的"左上→右下"
    paint.shader = android.graphics.LinearGradient(
        0f, 0f, f, f,
        intArrayOf(white(diagonalLight), 0, 0),
        floatArrayOf(0f, 0.32f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, f, f, paint)

    return bmp.asImageBitmap()
}

/** 栏位与内容相接的那条边 */
enum class GlassEdge {
    /** 顶栏：内容在下面，亮线画在下沿 */
    BOTTOM,

    /** 底栏：内容在上面，亮线画在上沿 */
    TOP,
    NONE,
}

/**
 * 玻璃栏位（顶栏 / 底栏）。
 *
 * 亮线不画整圈：整圈描边会把栏位框成一张"卡片"，而真实玻璃只在
 * 与内容相接的那一条边上折射。所以只画内容侧那一根。
 *
 * 那一侧画**两条**线，缺一条都会塌：
 *   外侧（贴着页面）一条极淡主题色细线 —— 负责"这条边在哪"；
 *   内侧一条白色高光 —— 负责"玻璃有厚度"，光在玻璃断面上折一下。
 */
@Composable
fun Modifier.appGlassBar(edge: GlassEdge = GlassEdge.TOP): Modifier {
    val glass = appGlass()
    return this
        .background(glass.bar)
        // 片体自身的光照：只留很轻一层。日间栏位本身已有明确的蓝，
        // 顶部再压一层白会在边界内侧形成一条"更亮的横带"，看着像两段拼接
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (glass.isDark) 0.07f else 0.09f),
                    Color.Transparent,
                ),
            ),
        )
        // 栏位也要有一道斜光，否则它就是一条实色带 —— 玻璃条和色带的差别全在这道光
        .background(
            Brush.horizontalGradient(
                0f to Color.White.copy(alpha = if (glass.isDark) 0.05f else 0.06f),
                0.42f to Color.Transparent,
                1f to Color.White.copy(alpha = if (glass.isDark) 0.03f else 0.04f),
            ),
        )
        .drawWithContent {
            drawContent()
            if (edge == GlassEdge.NONE) return@drawWithContent
            val stroke = 1.dp.toPx()
            // 外侧线压在栏位边界上，内侧高光往里挪 1px
            val lineY = if (edge == GlassEdge.TOP) stroke / 2f else size.height - stroke / 2f
            val edgeY = if (edge == GlassEdge.TOP) stroke * 1.5f else size.height - stroke * 1.5f
            // 分界线两端渐隐：整条等宽的线会读成"分割线"，把底栏从页面里割出去；
            // 眼睛看到的应该只是"这里有一层玻璃的边缘"
            drawLine(
                brush = Brush.horizontalGradient(
                    0f to glass.barLine.copy(alpha = glass.barLine.alpha * 0.35f),
                    0.5f to glass.barLine,
                    1f to glass.barLine.copy(alpha = glass.barLine.alpha * 0.35f),
                ),
                start = Offset(0f, lineY),
                end = Offset(size.width, lineY),
                strokeWidth = stroke,
            )
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(
                        glass.barEdge.copy(alpha = glass.barEdge.alpha * 0.45f),
                        glass.barEdge,
                        glass.barEdge.copy(alpha = glass.barEdge.alpha * 0.62f),
                    ),
                ),
                start = Offset(0f, edgeY),
                end = Offset(size.width, edgeY),
                strokeWidth = stroke,
            )
        }
}

/**
 * 玻璃悬浮按钮（「添加视频」「添加共享」「新建串流」这类圆形主操作）。
 *
 * 第 19 轮改了两处：
 *  1) **不再是深色实心块**。之前 fab 直接取 `accent`（浅色下亮度 0.20 的实色），
 *     白图标压上去对比够，但整块是"深色圆片"，与全站的浅玻璃不是一个材质，
 *     用户反馈"背景颜色太深、没有玻璃质感"。现在 fab 是一块**比卡片深半档的主题色玻璃**，
 *     图标改用 accent 实色 —— 有颜色、有厚度，但仍然是玻璃。
 *  2) 三层画法与 [appGlassSurface] 完全一致（面色 → 竖向受光 → 斜向高光 → 上亮下暗的边），
 *     保证它与卡片、底栏是同一种材质。
 */
val FabShape: Shape = RoundedCornerShape(18.dp)

@Composable
fun Modifier.appGlassFab(shape: Shape = FabShape): Modifier {
    val glass = appGlass()
    return this
        .shadow(
            elevation = 10.dp,
            shape = shape,
            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.26f),
            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
        )
        .clip(shape)
        .background(glass.fab)
        // 竖向受光：上沿亮、下沿沉
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (glass.isDark) 0.19f else 0.22f),
                0.55f to Color.White.copy(alpha = if (glass.isDark) 0.04f else 0.06f),
                1f to Color.Transparent,
            ),
        )
        // 斜向高光：玻璃最容易被认出来的细节
        .background(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = if (glass.isDark) 0.10f else 0.16f),
                0.38f to Color.Transparent,
                1f to Color.Transparent,
            ),
        )
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(glass.borderTop, glass.borderBottom),
            ),
            shape = shape,
        )
}

/**
 * 全站唯一的悬浮「+」按钮。
 *
 * 之前三个页面各写一遍 `FloatingActionButton(containerColor = appGlass().fab)`：
 * 各自传了不同的 elevation、有的用默认圆形 shape、有的自己叠 `appGlassFab()`，
 * 结果是同一个按钮在三页上圆角、颜色深浅、离边距都不一样（用户："位置不一致、
 * 背景太深、没有玻璃质感"）。现在只留这一个入口，形状与材质不再分叉。
 */
@Composable
fun GlassFab(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val glass = appGlass()
    androidx.compose.material3.FloatingActionButton(
        onClick = onClick,
        modifier = modifier.appGlassFab(),
        shape = FabShape,
        // 面色由 appGlassFab 画；这里必须透明，否则 M3 会先用自己的 shape 铺一层实色，
        // 与外面 18dp 的圆角错开，边缘会出现"双层轮廓"
        containerColor = Color.Transparent,
        // 深色下面色本身就是掺了主题色的蓝灰，加号再用同族强调色会"蓝压蓝"糊在一起；
        // 这里在深色下换成近白（带一点主题色相），一个按钮只留一处高对比焦点。
        contentColor = if (glass.isDark) Color.White.copy(alpha = 0.92f) else glass.accent,
        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
        ),
    ) {
        androidx.compose.material3.Icon(
            Icons.Rounded.Add,
            contentDescription = contentDescription,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * 玻璃按钮的语义档位。
 *
 * 弹窗底部、表单行尾的动作按钮原来全部是 M3 `TextButton`：没有片体、没有描边，
 * 压在渐变玻璃面板上就是两行裸文字，与页面里「凡可点皆为玻璃片」的规则相反。
 * 统一成玻璃按钮后，一个弹窗里要同时表达三种轻重，所以按语义分档取值，
 * 而不是让调用方各自传颜色 —— 否则「取消」在不同弹窗里会是三种灰。
 */
enum class GlassButtonTone {
    /** 主操作：确认、保存、去开启。accent 描边 + accent 文字 */
    Accent,

    /** 次操作：取消、关闭。中性描边 + 常规文字色，不与主操作抢视线 */
    Neutral,

    /** 破坏性操作：删除、清空、移出保险箱。error 描边 + error 文字 */
    Danger,
}

/**
 * 弹窗底部的玻璃按钮。签名与 `TextButton` 对齐（`onClick` + 尾随 content），
 * 所以各处 `TextButton(...) { Text("取消") }` 可以直接换成它，而不用重排布局。
 *
 * 存在的理由：M3 的 `TextButton` 没有片体、没有描边，压在渐变玻璃面板上就是一行
 * 裸文字；同一个 App 里页面用玻璃片、弹窗用裸文字，切换一次就露馅。
 *
 * 文字颜色由 [tone] 决定，content 里的 `Text` 不要再写死 color —— 否则会盖掉
 * 禁用态与主题色的变化。
 */
@Composable
fun GlassDialogButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // 默认 Neutral：弹窗底部的次要动作（取消、关闭）占多数，主操作由调用方显式标 Accent，
    // 否则所有按钮都染主题色，"取消"和"确定"会长得一模一样。
    tone: GlassButtonTone = GlassButtonTone.Neutral,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val glass = appGlass()
    val shape = RoundedCornerShape(14.dp)
    val buttonFill = when (tone) {
        GlassButtonTone.Accent -> lerp(glass.panelStrong, glass.fab, 0.48f)
        GlassButtonTone.Neutral -> glass.panelStrong
        GlassButtonTone.Danger -> lerp(glass.panelStrong, glass.fab, 0.28f)
    }
    val contentColor = when (tone) {
        GlassButtonTone.Accent -> glass.accent
        GlassButtonTone.Neutral -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
        GlassButtonTone.Danger -> MaterialTheme.colorScheme.error
    }
    val borderColor = when (tone) {
        GlassButtonTone.Accent -> glass.accent.copy(alpha = 0.52f)
        GlassButtonTone.Neutral -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
        GlassButtonTone.Danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.48f)
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .appGlassSurface(
                shape = shape,
                elevation = 1.dp,
                fillOverride = if (enabled) buttonFill else glass.panelStrong,
            )
            .border(
                width = 1.dp,
                color = if (enabled) borderColor else borderColor.copy(alpha = 0.18f),
                shape = shape,
            ),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        elevation = null,
        content = content,
    )
}

/**
 * 带边框的玻璃动作按钮（表单里的「测试 / 上传 / 恢复」、弹窗底部的「取消 / 确定」）。
 *
 * 不用 `OutlinedButton`：它自带的描边会和 [appGlassSurface] 的上下亮暗双色描边重叠成两道边；
 * 也不用 `TextButton`：没有片体、没有描边，在设置页那一片玻璃卡片里像一行裸文字。
 * 这里让 M3 Button 只负责点击/焦点/禁用态，外观全部交给玻璃修饰符。
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 操作进行中：按钮内部显示转圈，替代页面上重复的「处理中…」文字。 */
    loading: Boolean = false,
    tone: GlassButtonTone = GlassButtonTone.Accent,
) {
    GlassDialogButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tone = tone,
    ) {
        // 固定最小内容宽度：转圈与文字宽度不同，切换时按钮不能忽宽忽窄挤动旁边按钮
        Box(Modifier.widthIn(min = 40.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
            } else {
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * 悬浮按钮的落点：右下角、离内容区底边 20dp。
 *
 * 底栏页（本地/网络/播放）的内容区已经让出了底栏高度，所以它就是"底栏上方 20dp"；
 * 非底栏页（串流、回收站这类从更多页进来的内页）没有底栏，如果也只用 20dp，
 * 按钮会贴到屏幕最下方 —— 来回切页时同一个按钮在屏幕上跳了 80dp，看着像两个控件。
 * 非底栏页的调用方自行补上这段高度，使各页按钮落在同一条水平线上。
 */
object FabInsets {
    val margin = 20.dp
}

/**
 * 全站点击反馈的**唯一规格**。
 *
 * 为什么要把圆角和时长收成一个对象：早先按压反馈散在七八处 —— 整行是 20%/12% 的淡片、
 * 播放页的行是白 7%、卡片是切到 `tintSoft`（12%）、M3 控件是 `onSurface` 10% 的波纹，
 * 圆角有 10/12/14/16 四种，亮起时长有的有动画有的没有。同一块玻璃按下去，
 * 在不同页面看到的是四种不同的亮度与边界，看起来像四个不同的人做的。
 *
 * 现在颜色只有一份（[glassPressFill]）、强度动画只有一份（[glassPressLevel]）、
 * 圆角只有一份（[GlassPress.radius]），行 / 卡片 / 弹窗选项 / 按钮全部引用这里。
 */
object GlassPress {
    /** 按压淡片的圆角。与卡片自身 20dp 圆角相差 6dp，贴在卡内也读作同一族 */
    val radius: Dp = 14.dp

    /** 按下多快亮起：够快才像"跟手" */
    const val RISE_MS = 60

    /** 松开多久淡出：比亮起慢一档，收得自然 */
    const val FALL_MS = 200

    /** 松手后再停留，保证「按住—抬起」的交界处不闪断 */
    const val HOLD_MS = 60L

    /** 轻点兜底：亮多久（不含淡出）。110ms 在真机上仍然太短，容易被当成没反馈；180ms 刚好能看清一次闪动。 */
    const val TAP_MS = 180L

    /**
     * 会立刻跳页 / 播放的卡片，点击动作要等一次闪动画出来再执行。
     *
     * 真机录屏确认：手指抬起与页面跳转落在同一帧时，卡片当场被销毁，
     * 按压层一次都没画出来 —— 用户看到的就是"点了完全没反应"。
     * 120ms 足够让淡片亮起，又不至于让列表点击显得迟钝。
     */
    const val ACTION_DELAY_MS = 120L
}

/**
 * 「先闪后跳」：点击会立刻跳页/播放的列表项专用，等一次按压淡片亮起再执行动作。
 *
 * 与 Widgets.kt 里的 rememberFlashClick 同源（同一个 120ms + 主线程 Handler），
 * 但做成无需 remember 的普通函数，供各页面自定义卡片在 onClick 里直接调用：
 *   onClick = { flashClick { navController.navigate(...); play(...) } }
 * 多选勾选这类不跳页、要即时跟手的分支不要包它。
 */
private val flashHandler = android.os.Handler(android.os.Looper.getMainLooper())
fun flashClick(action: () -> Unit) {
    flashHandler.postDelayed(action, GlassPress.ACTION_DELAY_MS)
}

/**
 * 玻璃卡片内「整行可点」的圆角按压反馈。
 *
 * **为什么必须自己画**：M3 的默认 ripple 是一个按**矩形**铺满点击区域的填充。
 * 整行可点的设置行 / 开关行横跨整张卡片，按下去就是一块直角半透明方块 ——
 * 亮色下像"白色细框"，深色下像"矩形阴影"，压在圆角玻璃卡上一眼就是错的。
 * 位置、宽度还随卡片内边距变化，越看越像没擦干净的图层。
 *
 * 所以这里彻底关掉 indication（`indication = null`，波纹一次都不画），
 * 改由 `pressed` 状态画一层**同圆角**的主题色淡片：按下去有反馈，但边界永远跟着圆角走。
 * 关闭 indication 也顺带消掉了部分 ROM 给可点节点补的直角焦点框。
 *
 * 用法：`Modifier.glassPressable(onClick = ...)`，不要再叠 `clickable`。
 */
@Composable
fun Modifier.glassPressable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    radius: Dp = GlassPress.radius,
    /** 读屏用的动作说明（"打开文件夹""清除缓存"），与原生 clickable 的 onClickLabel 等价 */
    onClickLabel: String? = null,
    /**
     * 覆盖按压色。只给**播放页浮层**用（那里永远是深色玻璃，全站按压色压上去会发灰），
     * 其余一律留空，走 [glassPressFill]。
     */
    fillOverride: Color? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    // onClick 放最后：这样 `glassPressable { ... }` 的尾随 lambda 才会绑到它，
    // 而不是绑到前面的 interactionSource（那是编译期才报错的坑）
    onClick: () -> Unit,
): Modifier {
    // 点击计数：每次点击都驱动一次「闪一下」，不依赖按压事件是否完整送达
    var clicks by remember { mutableIntStateOf(0) }
    val level = pressLevel(interactionSource, enabled, clicks)
    val base = glassPressFill(enabled)
    val fill = if (fillOverride != null && enabled) fillOverride else base
    return this
        .clip(RoundedCornerShape(radius))
        .background(fill.copy(alpha = fill.alpha * level))
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = {
                clicks++
                onClick()
            },
        )
}

/**
 * 玻璃上的图标按钮：圆形按压淡片 + 图标，替代 M3 [androidx.compose.material3.IconButton]。
 *
 * 为什么不用 IconButton：它的波纹是一个**按矩形铺满触控区**的状态层。默认尺寸下
 * M3 自己会给它套一个圆形裁切，看起来正常；一旦调用方传了自己的 `size`
 * （播放页那些 44/48dp 的工具钮就是这样），裁切就丢了 —— 按下去是一块**方形**亮斑。
 * 播放页满屏深色，一块方斑比在任何地方都显眼。
 *
 * 这里把形状写死成圆（图标按钮本来就是圆的），按压色走全站同一份 [glassPressFill]，
 * 播放页用 [fillOverride] 换成白。
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillOverride: Color? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var clicks by remember { mutableIntStateOf(0) }
    val level = pressLevel(interaction, enabled, clicks)
    val base = glassPressFill(enabled)
    val fill = if (fillOverride != null && enabled) fillOverride else base
    Box(
        modifier
            // M3 IconButton 自带 48dp 最小触摸区；替换成自绘按钮时把这层丢了，
            // 结果横屏顶栏五个图标挤成一团。这里补回来（调用方显式给 size 时以它为准）。
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(fill.copy(alpha = fill.alpha * level))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    clicks++
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * 与 [glassPressable] 同一套按压反馈，用于整行开关（[Role.Switch]）。
 *
 * Switch 自身仍需 `onCheckedChange = null`：状态由整行驱动，避免点击开关时切换两次。
 */
@Composable
fun Modifier.glassToggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = Role.Switch,
    radius: Dp = GlassPress.radius,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    // 同 glassPressable：状态变化回调放最后，兼容尾随 lambda
    onValueChange: (Boolean) -> Unit,
): Modifier {
    var clicks by remember { mutableIntStateOf(0) }
    val level = pressLevel(interactionSource, enabled, clicks)
    val fill = glassPressFill(enabled)
    return this
        .clip(RoundedCornerShape(radius))
        .background(fill.copy(alpha = fill.alpha * level))
        .toggleable(
            value = value,
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onValueChange = {
                clicks++
                onValueChange(it)
            },
        )
}

/**
 * 按压强度 0~1。
 *
 * 两路驱动，缺一条都会出问题：
 *
 *  1) **按住**：`isPressed` 为真时一直亮着，松手后再留 [PRESS_HOLD_MS] 才淡出
 *     —— 长按能看出「手指还按在这里」。
 *  2) **点击兜底**：轻点时按下与抬起常常落在同一帧里，Compose 会把它合并成一次
 *     值变化，`isPressed` 甚至可能一次都不为真；反过来，若抬起事件因为重组丢了，
 *     `isPressed` 又会一直停在 true —— 实测就是「点一下高亮常亮、再点一下才灭」。
 *     所以这里再挂一路由点击次数驱动的定时闪烁：每次点击必定亮 [PRESS_TAP_MS]
 *     然后熄灭，不管按压事件是否完整送达。
 *
 * 两路都只写 `lit`，最终一定会回到 false，不会留下常亮的高亮块。
 */
@Composable
private fun pressLevel(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    clickTick: Int,
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    var lit by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (pressed) {
            lit = true
        } else if (lit) {
            delay(GlassPress.HOLD_MS)
            lit = false
        }
    }

    LaunchedEffect(clickTick) {
        if (clickTick > 0) {
            lit = true
            delay(GlassPress.TAP_MS)
            lit = false
        }
    }

    val active = enabled && lit
    val level by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = if (active) GlassPress.RISE_MS else GlassPress.FALL_MS),
        label = "glassPressLevel",
    )
    return level
}

/**
 * 已有 `pressed` 布尔时的强度动画（卡片、列表项、播放页选项行走这一支）。
 *
 * 与 [pressLevel] 同一套 [GlassPress.RISE_MS] / [GlassPress.FALL_MS]：
 * 卡片上按下与设置行的按下必须是同一个节奏，否则一屏之内两种手感。
 */
@Composable
fun glassPressLevel(pressed: Boolean, enabled: Boolean = true): Float {
    val active = enabled && pressed
    val level by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = if (active) GlassPress.RISE_MS else GlassPress.FALL_MS),
        label = "glassPressLevelStatic",
    )
    return level
}

/**
 * 「带点击兜底的按压状态」——卡片 / 列表项专用，替代裸的 [collectIsPressedAsState]。
 *
 * 轻点时按下与抬起常落在同一帧，Compose 合并后 `isPressed` 可能一次都不为真，
 * 卡片就完全不亮（用户反馈「文件夹、视频、网络连接、播放列表点了没反应」）。
 * [glassPressable] 正常是因为它在点击回调里另有一路 clickTick 强制闪一下；这里把
 * 同一套兜底下沉到状态本身：按住走 isPressed，收到 [PressInteraction.Release] 就强制
 * 亮 [GlassPress.TAP_MS] 再熄灭。返回布尔可直接喂给 [glassPressLevel]，调用方只换一行
 * 采集函数，按压时长 / 颜色 / 圆角全部不变。
 */
@Composable
fun MutableInteractionSource.collectPressedWithTap(): State<Boolean> {
    val pressed by collectIsPressedAsState()
    var tapped by remember { mutableStateOf(false) }
    LaunchedEffect(this) {
        interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                tapped = true
                delay(GlassPress.TAP_MS)
                tapped = false
            }
        }
    }
    return derivedStateOf { pressed || tapped }
}

/**
 * 给已经自己处理点击（`combinedClickable` / 自带 `interactionSource` 的控件）
 * 的玻璃面补一层同规格的按压淡片。
 *
 * 与 [glassPressable] 的区别：这个**不接管点击**，只负责画。
 * 卡片的点击要带长按（多选），没法交给 [glassPressable]，但按压的观感必须一致。
 */
@Composable
fun Modifier.glassPressOverlay(
    pressed: Boolean,
    shape: Shape = RoundedCornerShape(GlassPress.radius),
    enabled: Boolean = true,
    /**
     * 是否把淡片画在内容上方。
     *
     * 视频卡的图片区是不透明的，按压层画在内容下面会被缩略图完全盖住 —— 用户看到的就是
     * 「视频点了没反应」。这类带不透明内容的卡片必须传 true；纯文字行保持 false，
     * 避免按压时把文字也染上一层色。
     */
    foreground: Boolean = false,
): Modifier {
    val level = glassPressLevel(pressed, enabled)
    if (level <= 0.002f) return this
    // 内容上方的淡片不能用主题色：视频缩略图常常就是同一种蓝，压上去几乎看不出变化。
    // 这里改用中性明暗膜（亮色主题压暗、深色主题提亮），在任何画面上都能读出"按下去了"。
    // 内容下方的普通淡片仍然走全站的主题色规格。
    val fill = if (foreground && enabled) {
        if (appGlass().isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.12f)
    } else {
        glassPressFill(enabled)
    }
    val color = fill.copy(alpha = fill.alpha * level)
    return if (foreground) {
        this.clip(shape).drawWithContent {
            drawContent()
            drawRect(color)
        }
    } else {
        this.background(color, shape)
    }
}

/**
 * 按压淡片：亮色下用主题色、深色下用白。
 *
 * 透明度不能再压得太低：10% 主题色压在同样是淡蓝的玻璃卡上，实测只差 2~3/255，
 * 手指按下去几乎看不出变化（用户反馈「点击时没有按压提示」）。现在亮色 20%、
 * 深色 12%，在卡片自身的蓝底上能读出一块明确的「按下去了」的色片，
 * 又不会盖住文字。
 */
@Composable
fun glassPressFill(enabled: Boolean = true): Color {
    if (!enabled) return Color.Transparent
    val glass = appGlass()
    return if (glass.isDark) Color.White.copy(alpha = 0.12f) else glass.tint.copy(alpha = 0.20f)
}
