package com.yaoahos.mypureplayer.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch

/**
 * 全站统一的底部面板。
 *
 * 为什么不用 Material3 的 `ModalBottomSheet`：
 *
 *  1. **它用布局偏移挪面板**。M3 是靠 `Modifier.offset { IntOffset(0, sheetOffset) }` 逐帧挪的，
 *     每挪一帧整块面板（标题、分段按钮、滚动区、半透明底）都要重新测量 + 重新录制绘制指令。
 *     横屏时面板几乎占满屏高、位移量近千像素，这条路上每一帧都在做整页布局，掉帧非常明显。
 *     这里改成 `graphicsLayer.translationY`：面板只被录一次，之后每帧只是换一个位移值，
 *     不重排也不重录。
 *  2. **它的进场是一条很软的空间弹簧**（Material3 1.4 的 MotionScheme，尾段拖得很长）。
 *     横屏位移大，弹簧尾段那段"几乎不动的慢速滑行"会被读成"慢慢往上爬"。这里用固定 220ms
 *     的 tween：起步快、落位干脆，与前后的页面转场同一个节奏。
 *  3. **它依赖 anchoredDraggable 的锚点**：锚点由内容高度算出，内容高度一旦在进场途中
 *     变化一次（横竖屏切换后重新测量、滚动区上限生效），锚点就变、动画会从头再来一遍，
 *     表现正是"先卡一下，然后再慢慢滑上去"。这里面板高度只由内容决定，动画与测量无关。
 *
 * 交互保持不变：点遮罩 / 返回键 / 向下拖拽都能关闭；向下拖不过阈值就弹回原位。
 * 拖拽只在顶部的把手条上生效 —— 面板里可能有可滚动的选项区，整块可拖会和滚动抢手势。
 */
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 有宿主就走应用内浮层（不新建窗口），没有才退回 Dialog。
    // 开窗本身的代价（新 Window + ViewRootImpl + Surface + 一次全屏 layout）实测
    // 就是 50~60ms，比面板内容还贵；放进应用窗口后只剩浮层自己的组合与绘制。
    val host = LocalAppOverlayHost.current
    if (host != null) {
        AppOverlaySlot {
            SheetBody(onDismissRequest, modifier, inOverlay = true, content = content)
        }
    } else {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                // 让对话框窗口铺满全屏：遮罩要盖住顶栏和底栏，
                // 默认的 usePlatformDefaultWidth=true 会把它压成一条窄条。
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            SheetBody(onDismissRequest, modifier, inOverlay = false, content = content)
        }
    }
}

/**
 * 面板本体（遮罩 + 面板）。窗口形态由调用方决定，内容与动画两条路径完全一致。
 */
@Composable
private fun SheetBody(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    inOverlay: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 只关一次：动画途中再点遮罩/返回键不能重复触发 onDismissRequest，
    // 否则上层会把状态清掉、面板在动画半途被直接移除，看起来就是"闪一下消失"。
    var closing by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // 外面每次重组都会传一个新的 onDismissRequest 闭包，而拖拽手势块只初始化一次；
    // 用 rememberUpdatedState 持有最新那份，避免手势里调用的是早就过期的那一个。
    val dismiss = rememberUpdatedState(onDismissRequest)

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(0f, tween(EXIT_MS, easing = FastOutSlowInEasing))
            dismiss.value()
        }
    }

    // 应用内浮层没有对话框窗口帮着拦返回键，自己注册一个
    if (inOverlay) {
        androidx.activity.compose.BackHandler(enabled = !closing) { close() }
    }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(ENTER_MS, easing = FastOutSlowInEasing))
    }
    // 横屏/折叠屏可用高度有限，面板不许超过窗口高度，避免内容被顶出屏幕上沿。
    val windowHeight = with(LocalDensity.current) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // 遮罩：直接用 drawBehind 画，读的是动画值。
        // 不用 graphicsLayer{alpha}、也不用 background(color) —— 前者要给整屏开一张
        // 离屏纹理，后者会在每一帧重组一次 Modifier 链，两者都比"画一个矩形"贵。
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(SCRIM.copy(alpha = SCRIM.alpha * progress.value))
                }
                .pointerInput(Unit) {
                    detectTapGestures { close() }
                },
        )

        Column(
            modifier = modifier
                .align(Alignment.BottomCenter)
                // 先限宽再 fillMaxWidth：顺序反了的话 fillMaxWidth 已把 min 撑到满屏，
                // widthIn(max) 就再也收不回来，面板在横屏/平板上仍会横贯整屏。
                // 手机上宽度不足上限，仍是满宽；宽屏限到一档宽度并居中。
                .widthIn(max = PageMetrics.SheetMaxWidth)
                .fillMaxWidth()
                .heightIn(max = (windowHeight - 24.dp).coerceAtLeast(200.dp))
                .graphicsLayer {
                    // 位移只写在这一层：面板内容录一次绘制指令，之后每帧只更新这个值。
                    // size.height 是面板自身高度，1f - progress 从"整块藏在屏幕下沿"
                    // 平滑过渡到 0 —— 不需要知道屏幕高度，横竖屏都自动正确。
                    translationY = size.height * (1f - progress.value) + dragY
                    // 圆角裁剪合并进同一个图层：单独再加 Modifier.clip 会多开一层
                    // （两层 graphicsLayer = 两次 RenderNode + 两次裁剪），打开那一帧白花时间。
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    clip = true
                }
                // 与全站弹窗统一为首页渐变材质；底部面板只露顶部圆角、贴屏不放投影。
                .appDialogSurface(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    elevation = 0.dp,
                )
                .navigationBarsPadding(),
        ) {
            // 把手：既是视觉提示，也是唯一的拖拽热区（见文件头注释）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .pointerInput(Unit) {
                        // 热区要高过视觉上的 4dp 横条：26~34dp 是"看着细、按得住"的折中，
                        // 再矮就要靠盲猜位置了。
                        val closeThreshold = 96.dp.toPx()
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragY > closeThreshold) {
                                    close()
                                } else {
                                    scope.launch {
                                        animate(
                                            initialValue = dragY,
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = 0.9f,
                                                stiffness = 700f,
                                            ),
                                        ) { value, _ -> dragY = value }
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    animate(
                                        initialValue = dragY,
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = 1f,
                                            stiffness = 700f,
                                        ),
                                    ) { value, _ -> dragY = value }
                                }
                            },
                        ) { _, delta ->
                            // 只允许往下拖：往上拖没有对应状态，会露出面板背后的间隙
                            dragY = (dragY + delta).coerceAtLeast(0f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
                        ),
                )
            }
            content()
        }
    }
}

/** 进场时长：与页面转场同一个节奏（约 220ms），起步快、落位干脆 */
private const val ENTER_MS = 220

/** 退场比进场更快：关闭是"撤回"，慢收会显得拖沓 */
private const val EXIT_MS = 150

/** 遮罩：比 M3 默认略浅，面板本身是半透明玻璃，压太黑就看不出透出来的内容了 */
private val SCRIM = Color.Black.copy(alpha = 0.40f)
