package com.yaoahos.mypureplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 提示语义。只影响配色与停留时长，不改变任何业务逻辑。
 * 判定顺序「失败优先」，避免「已删除 0 项，操作失败」被误判为成功。
 */
enum class NoticeTone { INFO, SUCCESS, WARN, ERROR }

data class NoticeMessage(
    val id: Long,
    val text: String,
    val tone: NoticeTone,
)

/**
 * 全局提示出口。原先散落各处的 Toast 统一走这里：
 * 顶部浮层、不黑底、不占位、不打断操作，点一下也能立刻关掉。
 */
object Notice {

    private val counter = AtomicLong(0L)

    private val _message = MutableStateFlow<NoticeMessage?>(null)
    val message: StateFlow<NoticeMessage?> = _message.asStateFlow()

    /**
     * 显式语气，不再靠中文文案关键词猜测（换语言/改文案都会导致语气错乱）。
     * 调用方用 info/success/warn/error 语义方法，或显式传入 tone。
     */
    fun show(text: String, tone: NoticeTone) {
        if (text.isBlank()) return
        _message.value = NoticeMessage(counter.incrementAndGet(), text, tone)
    }

    fun info(text: String) = show(text, NoticeTone.INFO)
    fun success(text: String) = show(text, NoticeTone.SUCCESS)
    fun warn(text: String) = show(text, NoticeTone.WARN)
    fun error(text: String) = show(text, NoticeTone.ERROR)

    fun dismiss(id: Long) {
        // 只关掉「还在显示的那一条」，避免旧提示的超时把新提示顶掉
        _message.value?.takeIf { it.id == id }?.let { _message.value = null }
    }
}

private fun NoticeTone.durationFor(length: Int): Long {
    val base = if (this == NoticeTone.ERROR) 3_500L else 2_000L
    return base + (length * 60L).coerceAtMost(4_000L)
}

/**
 * 语义色分深浅两套：只给单色背景硬配一套色值在另一侧对比度不达标。
 *
 * INFO 不再写死蓝色，直接用当前主题的强调色 —— 换了青碧/松绿主题，
 * 普通提示却还是一条蓝边，那块提示就成了画面上唯一不属于这套配色的东西。
 */
@Composable
private fun NoticeTone.accent(): Color {
    val cs = MaterialTheme.colorScheme
    val dark = cs.isDarkScheme()
    return when (this) {
        NoticeTone.ERROR -> if (dark) Color(0xFFFF8A8A) else Color(0xFFD32F2F)
        NoticeTone.SUCCESS -> if (dark) Color(0xFF5FDC9E) else Color(0xFF12855B)
        NoticeTone.WARN -> if (dark) Color(0xFFFFC46B) else Color(0xFFB26A00)
        NoticeTone.INFO -> cs.accentColor(dark)
    }
}

/**
 * 顶部提示浮层：圆角卡片 + 语义色图标，宽度受限、自动消失、点一下也能关。
 * 它只是叠在内容上方的一小块，不整屏遮罩，所以不影响继续操作。
 *
 * 配色是三层叠出来的，缺一层都会"只是贴了张色纸"：
 *   1) 玻璃底（panelStrong）——与全站卡片同一种材质，浮在列表上是同一层东西；
 *   2) 语义色薄纱（上浓下淡 10% → 3%）——让卡片**本身**带上该提示类别的颜色，
 *      而不是只有一条边框在喊"我是错误"；
 *   3) 顶部一层白高光 + 上亮下暗的描边——这才是"玻璃片"的边缘，纯色边框没有厚度。
 * 阴影也用语义色而不是主题色：卡片底下的光晕和它的内容是一个颜色。
 */
@Composable
fun NoticeHost(modifier: Modifier = Modifier) {
    val incoming by Notice.message.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf<NoticeMessage?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(incoming?.id) {
        if (incoming != null) {
            shown = incoming
            visible = true
        } else {
            visible = false
        }
    }

    LaunchedEffect(shown?.id) {
        val current = shown ?: return@LaunchedEffect
        delay(current.tone.durationFor(current.text.length))
        Notice.dismiss(current.id)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { -it / 2 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 2 },
        modifier = modifier,
    ) {
        val message = shown ?: return@AnimatedVisibility
        val accent = message.tone.accent()
        val glass = appGlass()
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .widthIn(max = 560.dp)
                .shadow(
                    elevation = if (glass.isDark) 8.dp else 10.dp,
                    shape = shape,
                    ambientColor = accent.copy(alpha = 0.22f),
                    spotColor = accent.copy(alpha = 0.30f),
                )
                .clip(shape)
                .background(glass.panelStrong)
                // 语义色薄纱：卡片自己是"带一点该类别颜色"的玻璃，不是一个白底贴上彩边
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = if (glass.isDark) 0.16f else 0.10f),
                            accent.copy(alpha = if (glass.isDark) 0.05f else 0.03f),
                        ),
                    ),
                )
                // 顶部高光：与 appGlassSurface 同方向，卡片才有"片"的厚度
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (glass.isDark) 0.10f else 0.16f),
                            Color.Transparent,
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = 0.46f),
                            accent.copy(alpha = 0.12f),
                        ),
                    ),
                    shape = shape,
                )
                .glassPressable(radius = 16.dp) { Notice.dismiss(message.id) }
                .padding(horizontal = 13.dp, vertical = 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f))
                        .border(1.dp, accent.copy(alpha = 0.28f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when (message.tone) {
                            NoticeTone.ERROR -> Icons.Rounded.ErrorOutline
                            NoticeTone.SUCCESS -> Icons.Rounded.CheckCircle
                            NoticeTone.WARN -> Icons.Rounded.Info
                            NoticeTone.INFO -> Icons.Rounded.Info
                        },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = message.text,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontWeight = FontWeight.Medium,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}

/** 把提示浮层固定在屏幕顶部中央（避开状态栏）。 */
@Composable
fun NoticeOverlay() {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        NoticeHost()
    }
}
