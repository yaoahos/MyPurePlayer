package com.yaoahos.mypureplayer.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 启动阶段需要给用户看的更新提示。
 *
 * 分两类：
 * - [Kind.NEW_VERSION]：检查到服务器上有更新，带下载入口；
 * - [Kind.WHATS_NEW]：本地版本刚升级，展示「本次更新内容」，纯说明。
 *
 * 放在 StateFlow 里而不是直接弹对话框：Application 没有窗口，
 * 只能把结果交给界面根部（[com.yaoahos.mypureplayer.ui.components.UpdatePromptHost]）统一展示。
 */
data class UpdatePromptData(
    val kind: Kind,
    val version: String,
    val current: String,
    val publishedAt: String = "",
    /** 纯文本更新日志，可能很长，弹窗侧会截断并给出「查看更多」 */
    val notes: String,
    val pageUrl: String = "",
    val apkUrl: String? = null,
    /** 安装包大小（字节），0 表示未知 */
    val size: Long = 0L,
) {
    enum class Kind { NEW_VERSION, WHATS_NEW }
}

object UpdatePrompt {

    private const val PREFS = "update_prompt"
    private const val KEY_LAST_VERSION = "last_installed_version"

    private val _state = MutableStateFlow<UpdatePromptData?>(null)
    val state: StateFlow<UpdatePromptData?> = _state.asStateFlow()

    fun dismiss() {
        _state.value = null
    }

    // ---------------------------------------------------------------- 下载

    /**
     * 下载状态。
     *
     * 放在 object 里而不是弹窗的 remember 里，是因为下载必须能**脱离弹窗存活**：
     * 之前进度只画在对话框内、下载中还不允许关闭，用户切到别的应用就什么都看不到，
     * 回来时对话框又因为作用域销毁而中断。现在下载挂在进程级作用域上，关掉弹窗照常下，
     * 进度同步到通知栏（对照 SafePassword 的做法）。
     */
    data class Download(
        val progress: Int = 0,
        val running: Boolean = true,
        val apkPath: String? = null,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _download = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _download.asStateFlow()

    /**
     * 开始下载。[info] 为空时用当前全局提示里那一版（启动检查的路径）；
     * 关于页主动检查时直接把自己的 [UpdateResult.latest] 传进来，
     * 两处共用同一套下载、通知与"已下载"流程。
     */
    fun startDownload(context: Context, info: UpdateInfo? = null) {
        val data = _state.value
        val target = info ?: data?.let {
            UpdateInfo(
                version = it.version,
                notes = it.notes,
                pageUrl = it.pageUrl,
                apkUrl = it.apkUrl,
                publishedAt = it.publishedAt,
                size = it.size,
            )
        } ?: return
        val apkUrl = target.apkUrl ?: return
        if (_download.value?.running == true) return
        // 启动检查这条路径：用户可能在下到一半时把弹窗关了。下载完成后要能把
        // "已下载，是否安装"重新弹回来，否则只剩通知栏一个入口。
        val restoreOnDone = info == null
        val snapshot = data
        _download.value = Download(progress = 0, running = true)
        val app = context.applicationContext
        UpdateNotifications.progress(app, 0)
        scope.launch {
            UpdateChecker.download(app, target) { percent ->
                _download.value = Download(progress = percent, running = true)
                UpdateNotifications.progress(app, percent)
            }.fold(
                onSuccess = { apk ->
                    _download.value = Download(progress = 100, running = false, apkPath = apk.absolutePath)
                    UpdateNotifications.completed(app, target.version, apk.absolutePath)
                    // 关过弹窗就把提示恢复回来，Host 会据此弹"是否安装"
                    if (restoreOnDone && _state.value == null && snapshot != null) {
                        _state.value = snapshot
                    }
                },
                onFailure = { error ->
                    _download.value = Download(running = false, error = error.message ?: "下载安装包失败")
                    UpdateNotifications.clear(app)
                },
            )
        }
    }

    /** 用户确认安装（或点了通知回到 App） */
    fun installDownloaded(context: Context): Boolean {
        val path = _download.value?.apkPath ?: return false
        val ok = UpdateChecker.install(context, java.io.File(path))
        if (ok) {
            _download.value = null
            _state.value = null
        }
        return ok
    }

    /** 关掉"已下载"提示但保留文件与通知：用户随时可以从通知栏安装 */
    fun dismissDownload() {
        _download.value = null
    }

    /** 检查到新版本：弹窗里放发布说明，允许直接下载 */
    fun showNewVersion(result: UpdateResult, fallbackNotes: List<ReleaseNote> = emptyList()) {
        val notes = result.latest.notes.ifBlank {
            fallbackNotes.firstOrNull { it.version == result.latest.version }
                ?.notes?.joinToString("\n") { "· $it" }
                .orEmpty()
        }
        // 已有弹窗在展示时不覆盖，避免两次检查同时回来互相打断
        if (_state.value != null) return
        _state.value = UpdatePromptData(
            kind = UpdatePromptData.Kind.NEW_VERSION,
            version = result.latest.version,
            current = result.current,
            publishedAt = result.latest.publishedAt,
            notes = notes,
            pageUrl = result.latest.pageUrl,
            apkUrl = result.latest.apkUrl,
            size = result.latest.size,
        )
    }

    /**
     * 升级后首次启动展示「本次更新内容」，每个版本只弹一次。
     *
     * 首次安装不弹：只记录版本号，等下次真正升级时才有「更新内容」的意义。
     */
    fun maybeShowWhatsNew(context: Context, currentVersion: String, notes: List<ReleaseNote>) {
        if (_state.value != null) return
        // debug 包的 versionName 带 "-debug" 后缀，更新日志里只写 1.1.0，比较前先去掉后缀
        val current = currentVersion.substringBefore('-')
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastRaw = prefs.getString(KEY_LAST_VERSION, null)
        prefs.edit().putString(KEY_LAST_VERSION, current).apply()
        val last = lastRaw?.substringBefore('-')
        if (last.isNullOrBlank() || last == current) return

        val entry = notes.firstOrNull { it.version == current } ?: return
        _state.value = UpdatePromptData(
            kind = UpdatePromptData.Kind.WHATS_NEW,
            version = entry.version,
            current = current,
            notes = entry.notes.joinToString("\n") { "· $it" },
        )
    }
}
