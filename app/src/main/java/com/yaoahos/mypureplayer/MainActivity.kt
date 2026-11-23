package com.yaoahos.mypureplayer

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import androidx.core.app.ActivityCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.AppSettings
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.MediaSource
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.R
import com.yaoahos.mypureplayer.ui.AppRoot
import com.yaoahos.mypureplayer.ui.components.NoticeOverlay
import com.yaoahos.mypureplayer.ui.components.UpdatePromptHost
import com.yaoahos.mypureplayer.ui.theme.MyPurePlayerTheme
import com.yaoahos.mypureplayer.ui.theme.ThemePalette
import kotlinx.coroutines.launch

/**
 * 用 FragmentActivity 而不是 ComponentActivity：保险箱的指纹解锁依赖
 * androidx.biometric 的 BiometricPrompt，它要求宿主是 FragmentActivity。
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(applicationContext)
        enableEdgeToEdge()

        setContent {
            val settings by Graph.settings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val dark = when (settings.darkMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            MyPurePlayerTheme(
                darkTheme = dark,
                palette = ThemePalette.fromKey(settings.themePalette),
                dynamicColor = settings.dynamicColor,
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        AppRoot()
                        // 更新提示放在根部：冷启动检查完更新时，无论停在哪一页都能弹出来
                        UpdatePromptHost()
                        // 全局提示浮层：叠在所有页面之上，不占位、不打断操作
                        NoticeOverlay()
                    }
                }
            }
        }

        registerPipReceiver()
        handleOpenPlayer(intent)
        handleViewIntent(intent)
        handleInstallIntent(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        super.onDestroy()
    }

    /** 小窗里的按钮用系统 RemoteAction 实现：系统控件区可点击，应用自绘按钮在小窗里点不到 */
    private fun registerPipReceiver() {
        val filter = IntentFilter().apply {
            addAction(PIP_TOGGLE)
            addAction(PIP_EXPAND)
            addAction(PIP_PREV)
            addAction(PIP_NEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // 老系统动态注册默认导出：挂上自定义签名权限后，只有本应用（签名一致）
            // 能给这个 receiver 发广播，第三方应用伪造 PIP_TOGGLE 播放/暂停被拦下
            registerReceiver(pipReceiver, filter, PIP_PERMISSION, null)
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PIP_TOGGLE -> Playback.togglePlay()
                PIP_PREV -> Playback.previous()
                PIP_NEXT -> Playback.next()
                PIP_EXPAND -> {
                    // 回到全屏播放页：把任务切回前台并发出打开播放页信号
                    Playback.requestOpenPlayer()
                    runCatching {
                        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                        manager?.moveTaskToFront(taskId, 0)
                    }
                }
            }
        }
    }

    /** 构造带上一个 / 播放暂停 / 下一个的画中画参数。调用方需自行保证已在 API 26+ */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    fun buildPipParams(ratio: Rational): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(pipActions(this))
        // 系统小窗自带「展开」入口，不再添加自定义全屏按钮，避免两个放大按钮重叠冲突
        return builder.build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenPlayer(intent)
        handleViewIntent(intent)
        handleInstallIntent(intent)
    }

    /**
     * 点「更新已下载」通知进来：直接进安装确认。
     *
     * 通知里的路径只由本应用写出（UpdateChecker 下载到自己的 cacheDir），
     * 这里仍做一次存在性检查；安装前 UpdateChecker.install 会走 FileProvider，
     * 不会把裸 file:// 暴露给安装器。
     */
    private fun handleInstallIntent(intent: Intent?) {
        val path = intent?.getStringExtra(com.yaoahos.mypureplayer.data.UpdateNotifications.EXTRA_INSTALL_APK)
            ?: return
        intent.removeExtra(com.yaoahos.mypureplayer.data.UpdateNotifications.EXTRA_INSTALL_APK)
        val apk = java.io.File(path)
        if (!apk.exists()) return
        if (!com.yaoahos.mypureplayer.data.UpdateChecker.install(this, apk)) {
            com.yaoahos.mypureplayer.ui.components.Notice.error("无法唤起安装界面，请到发布页手动下载")
        }
    }

    /** 从系统媒体栏 / 通知点进来：直接回到播放页 */
    private fun handleOpenPlayer(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) != true) return
        intent.removeExtra(EXTRA_OPEN_PLAYER)
        Playback.requestOpenPlayer()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        permissionCallback?.invoke(granted)
        permissionCallback = null
    }

    companion object {
        /** 通知 / 媒体栏点开时带回的标记：进入后自动跳到播放页 */
        const val EXTRA_OPEN_PLAYER = "com.yaoahos.mypureplayer.extra.OPEN_PLAYER"
        private const val REQ_PERMISSION = 1001
        private const val PIP_TOGGLE = "com.yaoahos.mypureplayer.pip.TOGGLE"
        private const val PIP_EXPAND = "com.yaoahos.mypureplayer.pip.EXPAND"
        private const val PIP_PREV = "com.yaoahos.mypureplayer.pip.PREV"
        private const val PIP_NEXT = "com.yaoahos.mypureplayer.pip.NEXT"
        /** 保护 PIP 广播的自定义签名权限（见 AndroidManifest.xml） */
        private const val PIP_PERMISSION = "${BuildConfig.APPLICATION_ID}.permission.PIP_CONTROL"
        private var permissionCallback: ((Boolean) -> Unit)? = null

        /**
         * 小窗控制条：上一个 / 播放暂停 / 下一个。
         * 进入小窗与状态刷新共用这一份，避免两处按钮数量或图标不一致。
         * 队列边界（第一集没有上一个、最后一集没有下一个）用 enabled=false 置灰，
         * 而不是隐藏——系统控制槽位固定，隐藏会让按钮在切歌时跳位。
         */
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
        private fun pipActions(activity: Activity): List<RemoteAction> {
            val state = Playback.state.value
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun pi(action: String, requestCode: Int) =
                PendingIntent.getBroadcast(activity, requestCode, Intent(action).setPackage(activity.packageName), flags)

            val hasPrev = state.queueIndex > 0
            val hasNext = state.queueIndex >= 0 && state.queueIndex < state.queueSize - 1

            val prev = RemoteAction(
                Icon.createWithResource(activity, R.drawable.ic_pip_prev),
                "上一个", "播放上一个",
                pi(PIP_PREV, 2),
            ).apply { isEnabled = hasPrev }
            val playing = state.playing
            val toggle = RemoteAction(
                Icon.createWithResource(activity, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
                if (playing) "暂停" else "播放",
                if (playing) "暂停播放" else "继续播放",
                pi(PIP_TOGGLE, 1),
            )
            val next = RemoteAction(
                Icon.createWithResource(activity, R.drawable.ic_pip_next),
                "下一个", "播放下一个",
                pi(PIP_NEXT, 3),
            ).apply { isEnabled = hasNext }
            return listOf(prev, toggle, next)
        }

        /** 让小窗里的图标与置灰状态随播放状态刷新（播放状态变化时在 PlayerScreen 调用） */
        fun refreshPipActions(activity: Activity) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val state = Playback.state.value
            if (!Playback.pictureInPicture || state.item == null) return
            val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) {
                Rational(state.videoWidth, state.videoHeight)
            } else {
                Rational(16, 9)
            }
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .setActions(pipActions(activity))
                .build()
            runCatching { activity.setPictureInPictureParams(params) }
        }

        /**
         * 不用 Activity Result API 申请权限：activity-compose 生成的是 16 位以上的请求码，
         * FragmentActivity 的 RequestPermissionsRequestCodeValidator 会直接抛
         * "Can only use lower 16 bits for requestCode"，新装应用第一次申请权限必崩。
         */
        fun requestPermission(context: Context, permission: String, callback: (Boolean) -> Unit) {
            val activity = context as? Activity ?: run { callback(false); return }
            permissionCallback = callback
            ActivityCompat.requestPermissions(activity, arrayOf(permission), REQ_PERMISSION)
        }
    }

    override fun onStop() {
        super.onStop()
        // 切到后台就把保险箱收回：从最近任务切回来不该直接看到隐藏内容。
        // 旋转屏幕（isChangingConfigurations）不算离场，否则转一下屏就要重输密码。
        if (!isChangingConfigurations) Graph.vault.lock()
        // 进入小窗时部分机型会先回调 onStop，再回调 onPictureInPictureModeChanged；
        // 若此刻立刻按「离开播放页」暂停，小窗里就是一张定格画面。延后一小段，
        // 等 PiP 状态稳定后再判断（onScreenLeave 内部已对 PiP/后台音频做了保护）。
        window.decorView.postDelayed({ Playback.onScreenLeave() }, 150L)
    }

    /** 播放中按 Home / 切任务时自动进入画中画 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val state = Playback.state.value
        if (!Playback.settings.pipOnHome) return
        if (state.item == null || !state.playing || state.backgroundAudio || Playback.pictureInPicture) return
        val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) {
            Rational(state.videoWidth, state.videoHeight)
        } else {
            Rational(16, 9)
        }
        val params = buildPipParams(ratio)
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // 退出小窗的时序因系统而异：onStop 可能先于本回调到达。先把状态置好，
        // 避免 onScreenLeave 在小窗里误判为「离开播放页」而暂停视频。
        Playback.setPictureInPicture(isInPictureInPictureMode)
    }

    /** 从文件管理器「用其它应用打开」进来的视频 */
    private fun handleViewIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_VIEW) return
        val data: Uri = intent.data ?: return
        val name = queryName(data) ?: data.lastPathSegment ?: "视频"
        lifecycleScope.launch {
            val item = VideoItem(
                id = data.toString(),
                title = Formats.stripExtension(name),
                fileName = name,
                uri = data.toString(),
                source = MediaSource.LOCAL,
                // file:// 入口必须带真实路径，否则同目录外挂字幕探测会落空
                localPath = if (data.scheme == "file") data.path else null,
            )
            Playback.playSingle(item)
            Playback.requestOpenPlayer()
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}
