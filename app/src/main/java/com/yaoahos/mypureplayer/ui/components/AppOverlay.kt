package com.yaoahos.mypureplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 应用内浮层宿主。
 *
 * 动作面板 / 确认框以前各开一个 `Dialog` 窗口。开窗这一下要新建一个 `Window`、
 * 一套 `ViewRootImpl` + `Surface`，再做一次全屏 measure/layout —— 真机实测打开动作面板
 * 首帧 55.7ms、打开一个只有两行字的确认框 65.5ms（越简单的框越贵，说明代价在窗口本身，
 * 不在内容）。表现为"点了之后要愣一下才滑出来"。
 *
 * 这里把浮层放回应用自己的窗口：宿主挂在根布局最上层，浮层只是同一棵树里的一个子节点，
 * 打开时没有窗口创建、没有第二次 Surface 分配，只剩浮层自己的组合与绘制。
 *
 * 用法（浮层组件内部自动完成，界面代码不用改）：
 * ```
 * val host = LocalAppOverlayHost.current
 * if (host != null) AppOverlaySlot { ...浮层内容... } else Dialog { ...同内容... }
 * ```
 * 保留 Dialog 分支是为了在宿主缺席时（例如将来单独预览某个组件）仍然能弹出来。
 */
@Stable
class AppOverlayHost {
    internal var content: (@Composable () -> Unit)? by mutableStateOf(null)

    /**
     * 当前内容的归属令牌。
     *
     * 面板与确认框共用这一个槽位，而"面板退场"与"确认框入场"是挨着发生的：
     * 老面板的 `onDispose` 可能晚于确认框的注册执行，那时如果无条件把槽位清空，
     * 就会把刚弹出来的确认框一起抹掉（现象：点"删除"没反应，确认框根本不出现）。
     * 清空只在令牌仍是自己那一份时才做。
     */
    internal var token: Any? by mutableStateOf(null)

    /** 当前是否有浮层占据最上层（给返回键 / 命中测试用） */
    val active: Boolean get() = content != null
}

val LocalAppOverlayHost = staticCompositionLocalOf<AppOverlayHost?> { null }

/**
 * 把 [content] 挂到宿主最上层。
 *
 * 内容 lambda 用 `rememberUpdatedState` 托住：外层每帧都可能传新闭包，
 * 而宿主只注册一次，不这样处理浮层会一直显示第一次组合时的旧参数。
 */
@Composable
fun AppOverlaySlot(content: @Composable () -> Unit) {
    val host = LocalAppOverlayHost.current ?: return
    val latest = rememberUpdatedState(content)
    DisposableEffect(host) {
        val mine = Any()
        host.content = { latest.value() }
        host.token = mine
        onDispose {
            if (host.token === mine) {
                host.content = null
                host.token = null
            }
        }
    }
}

/** 宿主本体：放在根布局最后一个子节点，天然盖住壳层、导航与底栏 */
@Composable
fun AppOverlayHostView(host: AppOverlayHost) {
    val content = host.content ?: return
    Box(Modifier.fillMaxSize()) { content() }
}
