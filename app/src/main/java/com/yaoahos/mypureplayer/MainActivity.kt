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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 用 FragmentActivity 而不是 ComponentActivity：保险箱的指纹解锁依赖
 * androidx.biometric 的 BiometricPrompt，它要求宿主是 FragmentActivity。
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(applicationContext)
        // 首帧的主题必须是对的：`initialValue` 只是兜底，实际值要等 DataStore
        // 第一次读盘回来（几十毫秒），那期间界面已按默认配色画过一帧 ——
        // 用户选的是绿主题，会看到冷启动时先闪一下蓝。
        // 这里等一次，但给上限：读不出来（极慢盘/异常）就按默认配色走，绝不拖住启动。
        runBlocking { withTimeoutOrNull(150L) { Graph.settings.warmUp() } }
        enableEdgeToEdge()

        setContent {
            val settings by Graph.settings.settings
                .collectAsStateWithLifecycle(initialValue = Graph.settings.currentOrNull() ?: AppSettings())
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

    override fun onStart() {
        super.onStart()
        // 只靠 Application.onCreate 检查的话，常驻后台的应用永远等不到第二次；
        // 这里回到前台时补一次（按 6 小时节流，不会每次切回来都请求）
        (application as? App)?.checkUpdateOnForeground()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        super.onDestroy()
    }

    /** 小窗里的按钮用系统 RemoteAction 实现：系统控件区可点击，应用自绘按钮在小窗里点不到 */
    private fun registerPipReceiver() {
        val filter = IntentFilter().apply {
            addAction(PIP_TOGGLE)
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
            }
        }
    }

    /**
     * 构造画中画参数。调用方需自行保证已在 API 26+。
     *
     * [autoEnter] 只给 `syncAutoEnterPip` 用：手动点「小窗播放」进的也传 false，
     * 否则本次进小窗后参数会一直留着自动进入的许可，之后从任意页面按 Home
     * 系统都会把小窗拉出来。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    fun buildPipParams(ratio: Rational, autoEnter: Boolean = false): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(pipActions(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }
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
        /** 保护 PIP 广播的自定义签名权限（见 AndroidManifest.xml） */
        private const val PIP_PERMISSION = "${BuildConfig.APPLICATION_ID}.permission.PIP_CONTROL"
        private var permissionCallback: ((Boolean) -> Unit)? = null

        /**
         * 小窗控制条：只保留播放/暂停。
         * 进入小窗与状态刷新共用这一份，避免两处按钮数量或图标不一致。
         * 用户要求小窗只保留最基本操作：上一个、下一个、放大都回到全屏播放页使用。
         */
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
        private fun pipActions(activity: Activity): List<RemoteAction> {
            val state = Playback.state.value
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun pi(action: String, requestCode: Int) =
                PendingIntent.getBroadcast(activity, requestCode, Intent(action).setPackage(activity.packageName), flags)

            val playing = state.playing
            val toggle = RemoteAction(
                Icon.createWithResource(activity, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
                if (playing) "暂停" else "播放",
                if (playing) "暂停播放" else "继续播放",
                pi(PIP_TOGGLE, 1),
            )
            return listOf(toggle)
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
            val params = (activity as? MainActivity)?.buildPipParams(ratio)
                ?: PictureInPictureParams.Builder().setAspectRatio(ratio).build()
            runCatching { activity.setPictureInPictureParams(params) }
        }

        /**
         * 按当前真实状态写入「是否允许系统自动进小窗」。
         *
         * 必须四个条件同时成立：设置里允许、播放页正显示在前台、视频真的在播、
         * 既不是后台音频也不已经在小窗里。其余情况一律写 false。
         *
         * 参数是持久生效的：之前只要起播过一次就把 autoEnter 写成 true 再没人改，
         * 于是退回列表、暂停、甚至换到设置页之后按 Home，系统照样进小窗，
         * 小窗里显示的甚至是列表页 —— 就是用户说的「软件内也会画中画」。
         */
        fun syncAutoEnterPip(activity: Activity?) {
            val main = activity as? MainActivity ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val state = Playback.state.value
            val armed = Playback.settings.pipOnHome &&
                Playback.playerScreenVisible &&
                state.item != null &&
                // 保险箱视频不进小窗：内容会直接挂在桌面之上，且小窗里的 FLAG_SECURE
                // 表现因机型而异，不能指望它挡住画面
                state.item?.id?.startsWith("vault:") != true &&
                state.playing &&
                !state.backgroundAudio &&
                !Playback.pictureInPicture
            val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) {
                Rational(state.videoWidth, state.videoHeight)
            } else {
                Rational(16, 9)
            }
            runCatching { main.setPictureInPictureParams(main.buildPipParams(ratio, armed)) }
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

    /**
     * Android 12+ 用系统 auto-enter：只有真正切到桌面/其它应用才会进小窗，
     * 播放页上叠其它软件的弹窗、权限框、悬浮层不会误触发（入口在 buildPipParams）。
     * 旧系统没有 auto-enter，才在这里按 Home/切任务进小窗。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) return
        val state = Playback.state.value
        if (!Playback.settings.pipOnHome) return
        if (state.item?.id?.startsWith("vault:") == true) return
        if (state.item == null || !state.playing || state.backgroundAudio || Playback.pictureInPicture) return
        val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) {
            Rational(state.videoWidth, state.videoHeight)
        } else {
            Rational(16, 9)
        }
        runCatching { enterPictureInPictureMode(buildPipParams(ratio)) }
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
            Playback.playChecked(listOf(item), 0)
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}
