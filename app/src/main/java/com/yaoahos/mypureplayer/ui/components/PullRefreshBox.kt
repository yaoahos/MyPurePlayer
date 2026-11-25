package com.yaoahos.mypureplayer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 下拉刷新容器，手势与触发时机沿用 Material3 官方 [PullToRefreshBox]。
 *
 * 之前是手写的 nestedScroll 版本，问题出在触发时机：它把「列表滚到顶后剩余的下拉量」
 * 一律累计，松手（onPostFling）时只要越过阈值就刷新。于是两种误触不可避免：
 *  - 快速下滑浏览时，惯性把余量带过阈值，手指只是划了一下列表就刷新了；
 *  - 从列表中部往上推、再往回带一下，也会被算成一次下拉。
 * M3 的实现自己管手势仲裁：只有「列表已经在顶部、并且手指持续向下拖动」才计数，
 * 松手后才回调 onRefresh，滚动过程本身不会触发。
 *
 * **指示器是自绘的**，不再用 [androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator]。
 * 官方那枚是「浅色圆盘 + 细弧」：圆盘色取自 surfaceContainerHigh（我们换成了玻璃的
 * near-white 面板色），弧线只有 2.5dp 且颜色被我们的强调色规则压暗过一档 ——
 * 在浅色玻璃页面上，用户看到的就是一个**什么也没有的白圆圈**（实测截图里就是一坨白点）。
 * 现在自己画：玻璃圆盘 + 4dp 满饱和强调色圆弧 + 一层很淡的同色轨道，
 * 拉多少画多少，松手后转圈。位置也自己按下拉比例往下带，不再依赖官方内部偏移。
 *
 * 这里额外补三件事（M3 不管这些）：
 *  - 最短展示 [MIN_VISIBLE_MS]：本地目录几十毫秒就扫完，不补时长转圈会闪一下；
 *  - 收圈与「已刷新」同帧提交，不出现「提示已出、图标还在转」；
 *  - 看门狗：外部状态卡住不回落时到点强制收圈，并降级为「刷新超时」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 完成提示文案，**在刷新结束时求值**：返回 null 表示这一轮不提示。
     *
     * 为什么要延迟求值：刷新有可能是失败的（SMB 目录重新列失败时页面已经写明
     * 「无法打开这个位置」），固定写「已刷新」会与错误态自相矛盾。
     */
    doneText: () -> String? = { "已刷新" },
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()

    // 本轮刷新（由本组件发起）还在跑：负责最短展示时长与收圈提示，也用来顶住转圈
    var roundRunning by remember { mutableStateOf(false) }
    val spinning = isRefreshing || roundRunning

    val currentRefreshing by rememberUpdatedState(isRefreshing)
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val currentDoneText by rememberUpdatedState(doneText)

    // 外部驱动的刷新（例如进页面自动扫描）结束时，M3 不会自己把指示器收回去：
    // 它只在「调用方走 onRefresh → state 进入 refreshing → 再结束」这条路上负责收起。
    // 结果是一个空圆环停在列表上方。这里补一条：只要不在转圈，就把指示器收干净。
    androidx.compose.runtime.LaunchedEffect(spinning) {
        if (!spinning) settleIndicator(state)
    }

    val startRound: () -> Unit = {
        if (!roundRunning) {
            roundRunning = true
            val started = System.currentTimeMillis()
            scope.launch {
                currentOnRefresh()
                // 1) 补足最短展示时长（本地扫描常常几十毫秒就结束）
                val remain = MIN_VISIBLE_MS - (System.currentTimeMillis() - started)
                if (remain > 0L) delay(remain)
                // 2) 等外部状态真正落下，最多等 WATCHDOG_MS
                var timedOut = false
                if (currentRefreshing) {
                    val done = withTimeoutOrNull(WATCHDOG_MS) {
                        snapshotFlow { currentRefreshing }.first { !it }
                        true
                    }
                    timedOut = done == null
                }
                // 3) 收圈与提示同一帧提交
                roundRunning = false
                val text = currentDoneText()
                if (text != null) {
                    if (timedOut) Notice.warn("刷新超时，请稍后再试") else Notice.success(text)
                }
                settleIndicator(state)
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = spinning,
        onRefresh = startRound,
        state = state,
        modifier = modifier,
        indicator = {
            RefreshIndicator(
                state = state,
                spinning = spinning,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        content = content,
    )
}

/**
 * 自绘的刷新指示器：一枚玻璃圆盘 + 强调色圆弧。
 *
 * 为什么不直接给官方的 `color` 传强调色了事：官方那枚的圆盘是**不透明**色块，
 * 弧线 2.5dp，两者之间的对比只有「深一点的蓝压在近白圆上」，本机实测缩到 40dp
 * 后弧线几乎糊掉（用户："一个白色圆圈，什么也看不到"）。
 * 自己画之后线条粗细、颜色、轨道对比度都在这一个文件里定死，换主题也不会走样。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshIndicator(
    state: PullToRefreshState,
    spinning: Boolean,
    modifier: Modifier = Modifier,
) {
    val glass = appGlass()
    val fraction = state.distanceFraction.coerceIn(0f, 1f)
    // 不在拉也不在转的时候连盒子都不画：M3 的内容会缩到 0，但外层盒子还在，
    // 之前每个页面顶部就常驻一个空圆环（用户："页面上方怎么始终有个圆环"）。
    if (!spinning && fraction <= 0.01f) return

    // 转圈用无限旋转；下拉时角度固定，只按比例画弧长
    val spin by rememberInfiniteTransition(label = "refreshSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "refreshSpinValue",
    )

    val accent = glass.accent
    // 拉下时跟着手指往下走（0 → 40dp），松手转圈时停在固定高度，不再随手指回弹
    val travel = if (spinning) 8.dp else (46.dp * fraction)
    val scale = if (spinning) 1f else (0.72f + 0.28f * fraction)

    androidx.compose.foundation.layout.Box(
        modifier
            .padding(top = 2.dp)
            .offset(y = travel)
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (spinning) 6.dp else 2.dp,
                shape = CircleShape,
                ambientColor = accent.copy(alpha = 0.30f),
                spotColor = accent.copy(alpha = 0.34f),
            )
            .background(glass.panelStrong, CircleShape)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(9.dp)) {
            val stroke = 3.4.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            // 轨道：同色 16%，只用来交代"这里还有一段没走完"
            drawArc(
                color = accent.copy(alpha = 0.16f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val sweep = if (spinning) 96f else (24f + 300f * fraction)
            val start = if (spinning) spin - 90f else -90f
            drawArc(
                color = accent,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * 把「占满一屏但没有内容」的空态变成可下拉的容器。
 *
 * 下拉刷新是走 nestedScroll 的：M3 的 [PullToRefreshBox] 只有在**子节点是滚动容器**、
 * 并且把手势余量 dispatch 上来时才会看到下拉量。空态原本是一个纯 Column，
 * 手指按下去没有任何滚动事件，于是「本地视频为空时下拉没反应」——
 * 用户只能去点中间那颗「重新扫描」按钮。
 *
 * 这里用只放一项的 LazyColumn 兜住：它自身是可滚动节点，即使内容不超屏也会把手势
 * 交给父级，下拉刷新于是和列表页完全一致；同时用 fillParentMaxSize 保住空态居中。
 */
@Composable
fun ScrollableFill(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        item {
            androidx.compose.foundation.layout.Box(
                Modifier.fillParentMaxSize(),
            ) { content() }
        }
    }
}

/**
 * 把指示器彻底收回起点。
 *
 * M3 的「松手 → 动画到阈值」和「刷新结束 → 收起」共用同一个 Animatable，
 * 两者互相取消时位置会停在半路。所以先跑官方收起动画，再盯一小段时间；
 * 只要还有残余位移且没有动画在跑，就直接 snapTo(0) 落位。
 * snapTo 是唯一不依赖另一条协程配合的动作。
 */
@OptIn(ExperimentalMaterial3Api::class)
private suspend fun settleIndicator(state: PullToRefreshState) {
    if (state.distanceFraction > 0f) runCatching { state.animateToHidden() }
    repeat(3) {
        if (state.distanceFraction <= 0f) return
        delay(60L)
        if (state.distanceFraction > 0f) runCatching { state.snapTo(0f) }
    }
}

private const val MIN_VISIBLE_MS = 450L
private const val WATCHDOG_MS = 8_000L
