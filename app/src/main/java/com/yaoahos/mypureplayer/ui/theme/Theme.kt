package com.yaoahos.mypureplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.yaoahos.mypureplayer.ui.components.isDarkScheme
import kotlin.math.pow

/**
 * 固定主题色。
 *
 * 为什么不再默认跟随壁纸（动态取色）：
 * 动态取色给的 primary 是从壁纸里"量化"出来的，饱和度常常只有 0.3~0.5、明度也不定
 * （本机实测 #4C5E8B / #51638D，暗板岩蓝）。这套界面里玻璃面的颜色是由 primary 的
 * **色相**推出来的，于是同一个 App 换个壁纸，玻璃的浓淡、栏位与页面的层次就全变了：
 * 遇到低饱和壁纸，玻璃直接塌成灰片 —— 这就是反复出现"没有玻璃质感""底栏不搭"的根。
 *
 * 改成固定几套人工调过的配色后，每一套的色相、饱和度、明度都是确定的，
 * 玻璃层次可以按套校准，不会随壁纸漂移。动态取色仍然保留为开关（默认关闭）。
 */
enum class ThemePalette(
    val key: String,
    /** 设置页里显示的名字 */
    val label: String,
    /** 种子色：只用来定色相与基本气质，具体槽位由 [schemeFrom] 推导 */
    val seed: Color,
) {
    BLUE("blue", "靛蓝", Color(0xFF2F6BFF)),
    TEAL("teal", "青碧", Color(0xFF0E9E9E)),
    // 原来的 #7C5BD9 色相 256°，与靛蓝只差 33°；深色 primary 的色差 ΔE 只有 14.5，
    // 两个色点在同一排里几乎分不清。改到 274° 后与靛蓝、绯樱都拉开到 ΔE≈23（深色）
    VIOLET("violet", "紫罗兰", Color(0xFF9745D4)),
    GREEN("green", "松绿", Color(0xFF2E9E5B)),
    // 青柠补在琥珀与松绿之间的空档；schemeFrom 会把黄绿系偏亮的明度压回统一亮度，
    // 不会在玻璃面板上糊成一片。
    LIME("lime", "青柠", Color(0xFF9ACD32)),
    AMBER("amber", "琥珀", Color(0xFFDE7A22)),
    ROSE("rose", "绯樱", Color(0xFFD24A79)),
    ;

    companion object {
        fun fromKey(key: String?): ThemePalette =
            entries.firstOrNull { it.key == key } ?: BLUE
    }
}

/** 按色相/饱和度/明度取一个颜色 */
private fun hsv(hue: Float, saturation: Float, value: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))

/** 色相环上偏移（用于 tertiary：不换主色，但给一个互补的辅色） */
private fun shiftHue(hue: Float, delta: Float): Float {
    val h = hue + delta
    return if (h >= 360f) h - 360f else if (h < 0f) h + 360f else h
}

/** 相对亮度（WCAG 公式），用来判断一个颜色压在白底/深底上能不能看清 */
private fun Color.relativeLuminance(): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

/**
 * 求一个明度，使该色相的相对亮度正好落在 [target]。
 *
 * 为什么不能给所有色相都写 v = 0.94：HSV 的「明度」与「看起来有多亮」不是一回事。
 * 青、绿、黄三个色相天生亮度就接近满值 —— hsv(180, .90, .94) 出来是 #18F0F0，
 * 相对亮度 0.69，压在白底上对比度只有 1.43:1，几乎看不见；同一档位下靛蓝是
 * #2B66F0，亮度 0.16、对比度 4.9:1，清清楚楚。
 *
 * 用户反馈「青碧和松绿主题看不清晰」就是这一条：一套 UI 里所有以 primary 着色的
 * 文字、图标、描边，在青/绿/琥珀/绯樱四套主题下全部掉到 3.3:1 以下。
 *
 * 改成先定目标亮度再反解 v：六套主题的 primary 落在同一个亮度上（浅色 0.185，
 * 白底 4.5:1；深色 0.40，深底 8.7:1），色相各不相同但"看得清"这件事完全一致。
 */
private fun hsvForLuminance(hue: Float, saturation: Float, target: Float): Color {
    var low = 0f
    var high = 1f
    repeat(18) {
        val mid = (low + high) / 2f
        if (hsv(hue, saturation, mid).relativeLuminance() < target) low = mid else high = mid
    }
    return hsv(hue, saturation, (low + high) / 2f)
}

/**
 * 由种子色生成一整套 M3 配色。
 *
 * 不引用 material-color-utilities（多一个依赖、且它生成的容器色偏"设计师口味"，
 * 在自绘玻璃上没有更好）。这里用 HSV 直接排色阶：同一支色相，靠明度分档、
 * 靠饱和度控制浓淡，出来的是一套**严格同族**的颜色 —— 玻璃面掺色时不会串色。
 *
 * 每个槽位的取值目标：
 *   - primary 在浅色下要够深（压在白底上有对比），在深色下要够亮；
 *   - background 带极淡的同色相（不是纯白/纯黑），页面才不会死板；
 *   - 各级 surfaceContainer 之间只差一小档明度，卡片叠上去才有层次。
 */
private fun schemeFrom(palette: ThemePalette, dark: Boolean): ColorScheme {
    val hsvSeed = FloatArray(3)
    android.graphics.Color.colorToHSV(palette.seed.toArgb(), hsvSeed)
    val h = hsvSeed[0]
    // 种子的色相固定，饱和度取一个"够鲜艳但不刺眼"的区间
    val sSeed = hsvSeed[1].coerceIn(0.55f, 0.90f)
    val hAlt = shiftHue(h, 152f)

    val error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)
    val onError = if (dark) Color(0xFF690005) else Color(0xFFFFFFFF)
    val errorContainer = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6)
    val onErrorContainer = if (dark) Color(0xFFFFDAD6) else Color(0xFF410002)
    val scrim = Color(0xFF000000)

    return if (!dark) {
        // 浅色下 primary 用来写字、画图标、描边，必须压在近白页面上看得清。
        // 目标亮度 0.185 对应白底 4.5:1（WCAG AA 正文标准）。
        val primary = hsvForLuminance(h, sSeed, 0.185f)
        val onPrimary = Color.White
        val primaryContainer = hsv(h, (sSeed * 0.34f).coerceAtLeast(0.18f), 0.95f)
        val onPrimaryContainer = hsv(h, (sSeed * 0.92f).coerceAtMost(0.85f), 0.20f)
        // secondary 是"弱化一档的主色"，同样按亮度归一，否则青主题下它比 primary 还亮
        val secondary = hsvForLuminance(h, 0.30f, 0.26f)
        val secondaryContainer = hsv(h, 0.20f, 0.90f)
        val onSecondaryContainer = hsv(h, 0.42f, 0.18f)
        val tertiary = hsvForLuminance(hAlt, 0.62f, 0.24f)
        val tertiaryContainer = hsv(hAlt, 0.42f, 0.92f)
        val onTertiaryContainer = hsv(hAlt, 0.70f, 0.22f)
        val background = hsv(h, 0.045f, 0.998f)
        val onSurface = hsv(h, 0.20f, 0.11f)
        val surfaceVariant = hsv(h, 0.13f, 0.90f)
        val onSurfaceVariant = hsv(h, 0.18f, 0.33f)
        val outline = hsv(h, 0.12f, 0.52f)
        val outlineVariant = hsv(h, 0.11f, 0.80f)

        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = hsv(h, (sSeed * 0.55f), 0.80f),
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = Color.White,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onSurface,
            surface = background,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = hsv(h, 0.18f, 0.20f),
            inverseOnSurface = hsv(h, 0.08f, 0.95f),
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrim,
            surfaceBright = hsv(h, 0.030f, 1.0f),
            surfaceDim = hsv(h, 0.08f, 0.88f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsv(h, 0.055f, 0.982f),
            surfaceContainer = hsv(h, 0.065f, 0.966f),
            surfaceContainerHigh = hsv(h, 0.075f, 0.946f),
            surfaceContainerHighest = hsv(h, 0.085f, 0.925f),
        )
    } else {
        // 深色下 primary 要够亮：目标亮度 0.40，压在近黑页面上 8.7:1。
        // 饱和度取 sSeed 的 0.72（不再压到 0.52）：深底会"吃掉"一部分饱和度，
        // 压得太低青碧会变成灰青、松绿会变成灰绿。
        val primary = hsvForLuminance(h, (sSeed * 0.72f), 0.40f)
        val onPrimary = hsv(h, 0.92f, 0.18f)
        val primaryContainer = hsv(h, (sSeed * 0.85f), 0.38f)
        val onPrimaryContainer = hsv(h, 0.26f, 0.95f)
        val secondary = hsvForLuminance(h, 0.26f, 0.34f)
        val secondaryContainer = hsv(h, 0.22f, 0.29f)
        val onSecondaryContainer = hsv(h, 0.14f, 0.92f)
        val tertiary = hsvForLuminance(hAlt, 0.55f, 0.34f)
        val tertiaryContainer = hsv(hAlt, 0.55f, 0.34f)
        val onTertiaryContainer = hsv(hAlt, 0.35f, 0.93f)
        // 背景保留明确的主题色相，但亮度固定在 0.006 附近：
        // 青/绿主题的 v=0.075 出来比蓝主题亮一档，同一套玻璃在它们上面会显得发灰
        val background = hsvForLuminance(h, 0.42f, 0.006f)
        val onSurface = hsv(h, 0.12f, 0.90f)
        val surfaceVariant = hsv(h, 0.18f, 0.28f)
        val onSurfaceVariant = hsv(h, 0.13f, 0.78f)
        val outline = hsv(h, 0.14f, 0.58f)
        val outlineVariant = hsv(h, 0.18f, 0.30f)

        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = hsv(h, (sSeed * 0.85f), 0.92f),
            secondary = secondary,
            onSecondary = hsv(h, 0.30f, 0.16f),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = hsv(hAlt, 0.80f, 0.18f),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onSurface,
            surface = background,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = hsv(h, 0.08f, 0.90f),
            inverseOnSurface = hsv(h, 0.18f, 0.20f),
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrim,
            surfaceBright = hsvForLuminance(h, 0.26f, 0.030f),
            surfaceDim = background,
            // 深色容器同样按亮度分档（不用 v 值）：v 值下青/绿的容器会明显比蓝更亮，
            // 卡片叠在页面上要么糊成一片、要么突兀
            surfaceContainerLowest = hsvForLuminance(h, 0.44f, 0.003f),
            surfaceContainerLow = hsvForLuminance(h, 0.40f, 0.008f),
            surfaceContainer = hsvForLuminance(h, 0.38f, 0.011f),
            surfaceContainerHigh = hsvForLuminance(h, 0.36f, 0.016f),
            surfaceContainerHighest = hsvForLuminance(h, 0.34f, 0.022f),
        )
    }
}

/**
 * 动态取色的实际运行结果。
 *
 * 「开启动态取色」不等于「界面真的按壁纸变了」：系统版本不够、ROM 没实现 Material You、
 * 或者壁纸本身是纯黑/纯白/灰阶，取出来的 primary 会是一个接近中性色的灰
 * （实测这类壁纸给的是 #4C5E8B 甚至 #6B6B6B），饱和度只剩 0.1 上下 ——
 * 而整套玻璃的颜色是由 primary 的色相推出来的，色相一旦塌成灰，页面、卡片、底栏
 * 全部一起变灰，用户看到的就是「动态取色失效了」。
 *
 * 所以这里做两件事：**先判定能不能用，不能用就明确回落固定配色**（而不是让界面变灰），
 * 再把原因暴露给设置页 —— 用户需要知道为什么开了没反应。
 */
enum class DynamicColorStatus {
    /** 开关是关的 */
    OFF,

    /** 正常生效，界面跟随壁纸 */
    ACTIVE,

    /** 系统版本低于 Android 12，没有取色能力 */
    UNSUPPORTED,

    /** 壁纸色彩太少，取出来的主色接近灰色，已回落固定配色 */
    LOW_CHROMA,
}

object ThemeRuntime {
    var dynamicStatus: DynamicColorStatus by mutableStateOf(DynamicColorStatus.OFF)
        internal set

    /** 动态取色不可用时实际沿用的配色，设置页用它拼提示文案 */
    var fallbackPalette: ThemePalette by mutableStateOf(ThemePalette.BLUE)
        internal set
}

/** 动态取色结果的可用性：主色饱和度太低就视为"取不出颜色" */
private fun Color.isUsableDynamicPrimary(): Boolean {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    return hsv[1] >= 0.20f
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPurePlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: ThemePalette = ThemePalette.BLUE,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val dynamicScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }.getOrNull()
    } else {
        null
    }
    val dynamicUsable = dynamicScheme != null && dynamicScheme.primary.isUsableDynamicPrimary()

    val status = when {
        !dynamicColor -> DynamicColorStatus.OFF
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> DynamicColorStatus.UNSUPPORTED
        !dynamicUsable -> DynamicColorStatus.LOW_CHROMA
        else -> DynamicColorStatus.ACTIVE
    }
    androidx.compose.runtime.SideEffect {
        ThemeRuntime.dynamicStatus = status
        ThemeRuntime.fallbackPalette = palette
    }

    val colorScheme = if (dynamicUsable) dynamicScheme!! else schemeFrom(palette, darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
    ) {
        // 全站波纹配色。
        //
        // M3 控件的默认波纹取 `onSurface` 的 10%：浅色主题下就是一层几乎看不见的灰，
        // 压在同样是近白的玻璃卡上，实测只差 2~3/255 —— 用户反馈"图标按钮、文字按钮
        // 点了没有任何反馈"。深色下是白 10%，同样偏淡。
        //
        // 这里把波纹的颜色与强度对齐到 [glassPressFill]：亮色 = 主题色 20%，
        // 深色 = 白 12%。于是图标按钮、文字按钮、按钮、筛选片按下去的亮度，
        // 和设置行、卡片上的按压淡片是同一个数值 —— 全站只有一种"按下去了"的颜色。
        val pressColor = if (colorScheme.isDarkScheme()) Color.White else colorScheme.primary
        val pressAlpha = if (colorScheme.isDarkScheme()) 0.12f else 0.20f
        CompositionLocalProvider(
            LocalRippleConfiguration provides RippleConfiguration(
                color = pressColor,
                rippleAlpha = RippleAlpha(
                    pressedAlpha = pressAlpha,
                    focusedAlpha = pressAlpha,
                    draggedAlpha = pressAlpha,
                    // 悬停（触控板/鼠标）刻意淡一档：它不等于"按下去"
                    hoveredAlpha = 0.06f,
                ),
            ),
        ) {
            content()
        }
    }
}
