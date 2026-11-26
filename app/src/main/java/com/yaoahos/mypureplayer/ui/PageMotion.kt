package com.yaoahos.mypureplayer.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

/**
 * 全软件唯一的页面转场参数。
 *
 * 规格：
 *
 * | 方向 | enter（目标页）              | exit（来源页）               |
 * |------|------------------------------|------------------------------|
 * | 前进 | slideInHorizontally { it }   | slideOutHorizontally { -it / 3 } |
 * | 返回 | slideInHorizontally { -it / 3 } | slideOutHorizontally { it }  |
 *
 * 两层同时动、1/3 视差，前进与返回严格互为逆过程。只做水平位移：
 * 不缩放、不淡入淡出、不模糊、不用共享元素（文字会发虚，观感会被读成「卡」）。
 *
 * 唯一例外是底栏互切：平级入口之间满宽对穿、时长更短（[TAB_DURATION_MS]），
 * 方向只跟底栏的左右顺序走，与返回栈历史无关。
 *
 * ## 2026-11-22 重写：把此前三版全部推翻
 *
 * 此前先后试过「交叉淡化」「单层硬切（来源页 fadeOut(0) + 手动补满）」，都不对。
 * 根因是当时按 navigation-compose 2.8 的行为推断出「NavHost 返回时目标页不出场动画、
 * popEnter 是死参数」——**这个前提在 2.10.2 上是错的**：它内部的 AnimatedContent
 * 以**单个** NavBackStackEntry 为目标（contentKey = entry.id），返回时目标页换 key，
 * popEnter 正常播放，z 也会把目标页压到下层。于是上一版的手动补满（LocalPageBackFill）
 * 与真实 enter 叠在一起，几何和时序全乱了，怎么调都不对。
 *
 * 转场统一用 AnimatedContent 的 ContentTransform 落进 NavHost 的四个参数，
 * 模型、视差比例、曲线全软件一致。
 *
 * 曲线用 `tween(300, FastOutSlowIn)` 而不是弹簧。
 * 此前「本机 MotionDurationScale ≈ 0.25、tween 会被压到 1/4」的结论已被证伪：
 * 2026-11-22 用 DIAG_SLOW_MS=1500 的慢放包在真机连拍，1500ms 线性 tween 的
 * 逐帧进度与墙钟完全吻合（~750ms 处 15%、~1350ms 处 50%），缩放就是 1.0，
 * tween(300) 就是真 300ms。
 */
object PageMotion {
    /** 前进与返回都是 300ms。 */
    const val DURATION_MS = 300

    /**
     * 底栏切换单独用更短的时长。
     *
     * 底栏是平级入口，用户常连续快速点击：300ms 期间状态已切到目标栏、画面还在滑动，
     * 重复点击会被「已是当前栏」吞掉，主观上就像卡在上一栏。180ms 压缩这段不同步窗口。
     */
    const val TAB_DURATION_MS = 180

    /** 主曲线：Material standard easing。 */
    val Easing = FastOutSlowInEasing

    /**
     * 播放页进出改用明确时长的 tween。
     *
     * 之前用弹簧尾巴偏长，画面像"黏"在屏幕上；而且 2026-11-22 慢放包已经证伪了
     * "系统动画缩放会让 tween 变短" 的旧结论。进入稍慢一点、退出干脆一点，
     * 与面板的 180~280ms 也同一量级。
     */
    val PlayerFadeIn = tween<Float>(durationMillis = 220, easing = Easing)
    val PlayerFadeOut = tween<Float>(durationMillis = 200, easing = Easing)

    /**
     * 播放页内部控制条（顶栏 / 底栏 / 中央播放键 / 加载圈）的淡入淡出。
     *
     * 不能直接用 `fadeIn()` / `fadeOut()` 的默认 spring：它的退出尾巴没有明确终点，
     * 半透明的控制条会 lingering 在画面上，慢放尤其明显，看起来像"图标残影 / 卡住"。
     * 用与整页同一条曲线的短 tween，进出都跟手，和整页淡入也不会各走一套节奏。
     */
    val PlayerControlFadeIn = tween<Float>(durationMillis = 140, easing = Easing)
    val PlayerControlFadeOut = tween<Float>(durationMillis = 120, easing = Easing)
}

/**
 * 诊断用放大镜：> 0 时把页面 / 底栏转场换成同值毫秒的线性 tween，**仅供抓帧排查**。
 *
 * 生产值必须是 0。300ms 的转场在 `screencap`（每帧约 400ms）下只能抓到两个端点；
 * 放大到 1.5s 以上才看得清两层各占多少、谁盖着谁。
 */
internal const val DIAG_SLOW_MS = 0

private val pageSpec: FiniteAnimationSpec<IntOffset> =
    if (DIAG_SLOW_MS > 0) {
        tween(durationMillis = DIAG_SLOW_MS, easing = androidx.compose.animation.core.LinearEasing)
    } else {
        tween(durationMillis = PageMotion.DURATION_MS, easing = PageMotion.Easing)
    }

private val tabSpec: FiniteAnimationSpec<IntOffset> =
    if (DIAG_SLOW_MS > 0) {
        tween(durationMillis = DIAG_SLOW_MS, easing = androidx.compose.animation.core.LinearEasing)
    } else {
        tween(durationMillis = PageMotion.TAB_DURATION_MS, easing = PageMotion.Easing)
    }

/** 视差比例：旧页 / 目标页只走 1/3 屏，暗示它是「下一层」而不是被替换掉。 */
private const val PARALLAX = 3

/** 前进：新页从右侧整屏推入，旧页只退 1/3 形成视差。 */
val forwardEnter: EnterTransition
    get() = slideInHorizontally(animationSpec = pageSpec) { it }
val forwardExit: ExitTransition
    get() = slideOutHorizontally(animationSpec = pageSpec) { -it / PARALLAX }

/** 返回：3-A 的严格镜像 —— 当前页整屏推出右侧，目标页从 -W/3 补满。 */
val popEnter: EnterTransition
    get() = slideInHorizontally(animationSpec = pageSpec) { -it / PARALLAX }
val popExit: ExitTransition
    get() = slideOutHorizontally(animationSpec = pageSpec) { it }

/**
 * 底栏之间的转场：满宽对穿、只做位移。
 *
 * 方向只由栏位序号决定（目标序号 > 当前序号 = 往右切），与返回栈历史无关 ——
 * 旧实现方向取自返回栈，导致「本地→更多」和「更多→本地」都往左，来回切像在抽搐。
 *
 * 不做淡入：新页从半透明淡入会让整页文字先虚后实，读起来就是"糊一下"。
 * 两页此刻本就是满宽对穿、边界始终重合，没有需要磨的硬边。
 */
fun tabEnter(toRight: Boolean): EnterTransition =
    slideInHorizontally(animationSpec = tabSpec) { if (toRight) it else -it }

fun tabExit(toRight: Boolean): ExitTransition =
    slideOutHorizontally(animationSpec = tabSpec) { if (toRight) -it else it }
