package com.yaoahos.mypureplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 全项目统一的确认对话框。
 *
 * 过去每个页面手写一份 title/text/confirm/dismiss 四件套，样式与文案口径各不相同；
 * 抽出来后顺手统一了破坏性操作的确认键颜色（红色），用户对「这个按钮有后果」
 * 的识别不再依赖文字本身。
 *
 * 有 [LocalAppOverlayHost] 时画在应用自己的窗口里（不新建 Dialog 窗口）：
 * 一个只有两行字的确认框，开窗实测要 65.5ms，比动作面板还贵 —— 代价全在窗口本身。
 * 放进应用窗口后只剩这一个卡片的组合与绘制，同时补了一段 140ms 的淡入+微缩放，
 * 让"出现"这件事有起手，而不是硬切。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "取消",
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val host = LocalAppOverlayHost.current
    if (host != null) {
        AppOverlaySlot {
            ConfirmBody(
                title = title,
                message = message,
                confirmText = confirmText,
                dismissText = dismissText,
                destructive = destructive,
                modifier = modifier,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        }
        return
    }
    AlertDialog(
        shape = RoundedCornerShape(26.dp),
        // 玻璃底：与全站弹窗同一套渐变面板；容器色必须透明，否则会盖住这层渐变
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
        modifier = modifier.alertDialogGlassSurface(),
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(message) },
        confirmButton = {
            GlassButton(
                text = confirmText,
                onClick = onConfirm,
                tone = if (destructive) GlassButtonTone.Danger else GlassButtonTone.Accent,
            )
        },
        dismissButton = {
            GlassButton(text = dismissText, onClick = onDismiss, tone = GlassButtonTone.Neutral)
        },
    )
}

/** 应用内浮层形态的确认框：遮罩 + 居中卡片，与 M3 AlertDialog 的排版口径一致 */
@Composable
private fun ConfirmBody(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    destructive: Boolean,
    modifier: Modifier,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val enter = remember { Animatable(0f) }
    // 关闭是即时收起：确认/取消都带明确结果，再加退场动画反而挡住下一步操作
    val dismiss = rememberUpdatedState(onDismiss)
    androidx.activity.compose.BackHandler { dismiss.value() }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(140, easing = FastOutSlowInEasing))
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(SCRIM_DIALOG.copy(alpha = SCRIM_DIALOG.alpha * enter.value)) }
                .pointerInput(Unit) {
                    detectTapGestures { dismiss.value() }
                },
        )
        Box(
            modifier = modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
                .widthIn(min = 280.dp, max = 420.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    // 只写两个值：透明度与缩放。卡片内容仍然只录一次绘制指令。
                    alpha = enter.value
                    scaleX = 0.94f + 0.06f * enter.value
                    scaleY = 0.94f + 0.06f * enter.value
                }
                // 与其它弹窗统一为首页渐变面板：淡底色 + 上亮下暗描边 + 轻投影，
                // 不再用平涂的 panelStrong。elevation 保持轻，避免首帧大范围模糊。
                .appDialogSurface(RoundedCornerShape(26.dp), elevation = 6.dp),
        ) {
            Column(Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(14.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    GlassButton(
                        text = dismissText,
                        onClick = { dismiss.value() },
                        tone = GlassButtonTone.Neutral,
                    )
                    GlassButton(
                        text = confirmText,
                        onClick = onConfirm,
                        tone = if (destructive) GlassButtonTone.Danger else GlassButtonTone.Accent,
                    )
                }
            }
        }
    }
}

/** 确认框遮罩：比动作面板略浅，对话框不该把整页压黑 */
private val SCRIM_DIALOG = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)
