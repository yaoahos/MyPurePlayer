package com.yaoahos.mypureplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
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
 * 玻璃弹窗面板：直接复用首页那张不透明渐变（顶色 → 底色 + 顶部薄白 + 横向主题色薄纱），
 * 让弹窗看起来就是「首页背景的一块」，而不是浮在页面上的另一套灰蓝板。
 *
 * 与 [Modifier.glassPageBackground] 同源同色，只多一层圆角、描边与投影把它从页面里勾出来。
 * 弹窗里的选项条再叠文件夹卡片同款玻璃 [appGlassSurface]，层次关系与首页「淡背景 + 淡蓝玻璃卡」完全一致。
 */
@Composable
fun Modifier.appDialogSurface(
    shape: Shape = RoundedCornerShape(26.dp),
    elevation: Dp = 8.dp,
): Modifier {
    val cs = MaterialTheme.colorScheme
    val dark = cs.isDarkScheme()
    val top = if (dark) cs.surface else cs.background
    val bottom = pageGradientBottom(cs)
    val white = if (dark) 0.03f else 0.34f
    val sideAlpha = if (dark) 0.09f else 0.050f
    val glass = appGlass()
    val tile = androidx.compose.runtime.remember(top, bottom, white, sideAlpha, cs.primary) {
        renderPageBackdrop(top, bottom, white, sideAlpha, cs.primary)
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

/** 玻璃卡片的修饰符：半透明底 + 片体光感 + 高光描边 + 柔和投影 */
@Composable
fun Modifier.appGlassSurface(
    shape: Shape = RoundedCornerShape(20.dp),
    strong: Boolean = false,
    elevation: Dp = 2.dp,
    tinted: Boolean = false,
): Modifier {
    val glass = appGlass()
    val fill = when {
        tinted -> glass.tintSoft
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
    val tile = androidx.compose.runtime.remember(fill, glass.isDark, topLight, bottomLight) {
        renderSurfaceTile(fill, topLight, bottomLight, if (glass.isDark) 0.07f else 0.13f)
    }

    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
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
            brush = Brush.verticalGradient(
                listOf(glass.borderTop, glass.borderBottom),
            ),
            shape = shape,
        )
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
