package com.yaoahos.mypureplayer.ui.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 页面里的数据操作统一从这里起：异常只变成一条提示，不会把应用带走。
 *
 * 为什么必须这么做：`rememberCoroutineScope()` 的作用域没有异常兜底，未捕获异常
 * 会一路冒到 Recomposer，表现就是闪退。而数据层的落盘函数（`JsonStore.write`）
 * 在写失败时是**主动 throw** 的 —— 磁盘满、目录被占用、备份/清理工具锁文件时
 * 一定会撞上。用户看到的是「点了删除，应用直接没了」，而实际上什么都没提示。
 *
 * 取消信号必须原样抛出：那是页面退出时的正常流程，吞掉会让协程继续跑在已销毁的页面上。
 *
 * 用法：把 `scope.launch { ... }` 换成 `scope.launchUi { ... }`，其余不变。
 * 默认把失败原因用 [Notice] 弹出来；需要自定义文案时传 `onError`。
 */
fun CoroutineScope.launchUi(
    onError: (Throwable) -> Unit = { Notice.error(it.message?.takeIf { m -> m.isNotBlank() } ?: "操作失败，请重试") },
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        android.util.Log.w("launchUi", "页面操作失败", t)
        onError(t)
    }
}
