package com.yaoahos.mypureplayer.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.view.WindowManager
import android.Manifest
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.MediaSource
import com.yaoahos.mypureplayer.data.SubtitleFile
import com.yaoahos.mypureplayer.data.SubtitleTrack
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.media.Thumbnails
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.ui.components.DurationBadge
import java.io.File
import com.yaoahos.mypureplayer.ui.components.VideoThumb
import kotlinx.coroutines.delay
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/** 手势过程中的居中 / 侧边提示 */
/** 横向滑满一整屏对应的快进/快退时长，决定拖动时的手感 */
private const val SEEK_SPAN_MS = 120_000f

/** 手动旋转方向；null 表示按视频比例自动决定，按钮只在横屏 / 竖屏之间切换 */
private enum class RotationMode { LANDSCAPE, PORTRAIT }

private sealed interface GestureHud {
    data class Brightness(val percent: Int) : GestureHud
    data class Volume(val percent: Int) : GestureHud
    data class Seek(val targetMs: Long, val deltaMs: Long, val durationMs: Long) : GestureHud
    data object Speed : GestureHud
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(navController: NavHostController) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val state by Playback.state.collectAsStateWithLifecycle()
    // 进度独立订阅：每 300ms 的进度更新只重组进度条/时间文本，不带动整页
    val playbackPosition by Playback.positionMs.collectAsStateWithLifecycle()
    val pipActive by Playback.pictureInPictureState.collectAsStateWithLifecycle()
    val queue by Playback.queue.collectAsStateWithLifecycle()
    val tracks by Playback.subtitleTracks.collectAsStateWithLifecycle()
    val subtitleFiles by Playback.subtitleFiles.collectAsStateWithLifecycle()
    val audioTracks by Playback.audioTracks.collectAsStateWithLifecycle()

    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val activity = remember(context) { context.findActivity() }

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    var trackTab by remember { mutableStateOf(0) } // 0 字幕 / 1 音轨
    var showQueue by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var showDecode by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showTopMore by remember { mutableStateOf(false) }
    var showNetSubs by remember { mutableStateOf(false) }
    var showLocalSubs by remember { mutableStateOf(false) }
    // 任一浮层（底部面板 / 侧栏 / 对话框）打开时为 true。
    // 页面中央的提示在这种情况下会被整个盖住，没有显示意义，统一据此判断。
    val modalOpen = showTracks || showQueue || showSpeed || showCast ||
        showDecode || showInfo || showTopMore || showNetSubs || showLocalSubs
    var rotationMode by remember { mutableStateOf<RotationMode?>(null) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    // 用一个独立的 State 承载手势提示：写入不触发整页重组，
    // 读取只发生在 GestureHudView 内部，拖动时才不会掉帧
    val hudState = remember { mutableStateOf<GestureHud?>(null) }
    var hud by hudState

    // 长按（2 倍速）状态：按下时由独立协程计时，拖动会取消
    var pressing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var longHeld by remember { mutableStateOf(false) }
    val rateBeforeHold = remember { mutableFloatStateOf(1f) }

    // 单击 / 双击判定：第一次抬手先挂起 300ms，期间来了第二次就按双击处理
    val lastTapAt = remember { longArrayOf(0L) }
    var tapToken by remember { mutableIntStateOf(0) }
    var doubleTapFired by remember { mutableStateOf(false) }

    // 本地字幕改在播放页内的浮层里挑：系统 SAF 目录选择器是独立 Activity，
    // 会被系统按竖屏拉起，横屏播放页 → 竖屏文件页 → 横屏播放页，屏幕来回转两次。
    val dlnaDevices by Playback.dlnaDevices.collectAsStateWithLifecycle()

    fun toggleBackgroundAudio() {
        if (state.backgroundAudio) {
            Playback.setBackgroundAudio(false)
            // 关闭后台音频时把播放界面带回来，不用用户再从桌面点图标
            Playback.requestOpenPlayer()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            com.yaoahos.mypureplayer.MainActivity.requestPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) { granted ->
                if (granted) Playback.setBackgroundAudio(true)
                else seekHint = "需要通知权限才能在后台继续播放"
            }
        } else {
            Playback.setBackgroundAudio(true)
            seekHint = "后台音频已开启，点通知恢复画面"
        }
    }

    fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            seekHint = "当前系统版本不支持小窗播放"
            return
        }
        if (state.backgroundAudio) {
            seekHint = "请先关闭后台音频"
            return
        }
        // 进入小窗前先把任务停在播放页，退出小窗时系统会回到这里而不是桌面
        Playback.requestOpenPlayer()
        val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) {
            Rational(state.videoWidth, state.videoHeight)
        } else {
            Rational(16, 9)
        }
        // 用系统 RemoteAction 提供可点击的播放/暂停与全屏按钮；小窗里自绘按钮会被系统拦截为展开。
        // 参数必须在版本判断**之内**构造：RemoteAction / PictureInPictureParams 是 API 26 的类，
        // 在 24/25 上提前构造会直接 NoClassDefFoundError，用户点一下就闪退。
        val entered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val params = (activity as? com.yaoahos.mypureplayer.MainActivity)?.buildPipParams(ratio)
                    ?: PictureInPictureParams.Builder().setAspectRatio(ratio).build()
                activity?.enterPictureInPictureMode(params) == true
            }.getOrDefault(false)
        } else {
            false
        }
        if (!entered) seekHint = "无法进入小窗播放"
    }

    fun openCast() {
        Playback.startDlnaDiscovery()
        showCast = true
    }

    fun switchDecode(hardware: Boolean) {
        if (state.hardwareDecode != hardware) Playback.toggleHardwareDecode()
        seekHint = if (hardware) "已切换为硬件解码" else "已切换为软件解码"
    }

    fun cycleScale() {
        Playback.cycleScale()
        seekHint = "画面比例：${Playback.scaleLabel((state.scaleMode + 1) % 3)}"
    }

    fun rotateScreen() {
        // 按当前屏幕方向切到另一档，不依赖上一次按钮状态
        rotationMode = if (landscape) RotationMode.PORTRAIT else RotationMode.LANDSCAPE
        seekHint = if (landscape) "旋转：竖屏" else "旋转：横屏"
    }

    // 进入播放页就预热 DLNA 发现，打开投屏面板时通常已经有设备了
    LaunchedEffect(Unit) {
        Playback.startDlnaDiscovery()
    }

    // 小窗里播放状态/队列位置变化后刷新系统控制条：图标（播放/暂停）与
    // 「上一个/下一个」的置灰都要跟着变，所以把 queueIndex/queueSize 一起作为 key。
    LaunchedEffect(pipActive, state.playing, state.queueIndex, state.queueSize) {
        activity?.let { com.yaoahos.mypureplayer.MainActivity.refreshPipActions(it) }
    }

    ImmersiveFullScreen()
    KeepScreenOn()

    // 按视频真实宽高比自动决定横屏 / 竖屏；手动按钮可以覆盖自动结果。
    //
    // 尺寸优先级：解码器报的真实尺寸 > 列表项带的尺寸（本地扫描时就有了）> 默认横屏。
    // 最后这一档很关键：网络视频要等首帧解码才知道尺寸（SMB 常常 1~3 秒），
    // 之前尺寸未知时给 UNSPECIFIED，于是从竖屏列表点进来会先在竖屏转圈，
    // 解码出尺寸后再转成横屏 —— 用户看到的就是「竖屏加载一会才进入横屏」。
    val orientedWidth = if (state.videoWidth > 0) state.videoWidth else state.item?.width ?: 0
    val orientedHeight = if (state.videoHeight > 0) state.videoHeight else state.item?.height ?: 0
    LaunchedEffect(state.item?.id, orientedWidth, orientedHeight, rotationMode, pipActive) {
        // 小窗由系统决定尺寸，不再改 Activity 方向，否则退出小窗时很容易停在错误朝向
        if (pipActive) return@LaunchedEffect
        val requested = when (rotationMode) {
            null -> when {
                orientedWidth > orientedHeight -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                orientedHeight > orientedWidth -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                // 尺寸未知：先按横屏锁住。绝大多数视频是横屏，竖屏视频解码后会自动纠正，
                // 代价只是转一次；反过来（先竖屏再转横屏）每次都必然多等几秒。
                else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            RotationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            RotationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        activity?.requestedOrientation = requested
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Playback.stopDlnaDiscovery()
        }
    }

    // 返回键优先级：只要有任何弹层展开，
    // 返回就先关最上层弹层，绝不能直接退出播放页。
    //
    // 这里必须把所有浮层都列进来：右侧面板、短菜单（showTopMore / showDecode /
    // showSpeed）都是自绘的，没有系统返回处理；网络/本地字幕选择器（showNetSubs /
    // showLocalSubs）虽是 Dialog，但把它们一并计入 anyOverlay 后，退出用的
    // BackHandler 才会被禁用，避免出现「弹窗还开着就退出视频」。
    // 竖屏时顶栏「更多」弹出的短菜单尤其容易被漏掉，之前就会直接退出播放。
    val anyOverlay = showInfo || showCast || showDecode || showSpeed || showQueue ||
        showTracks || showTopMore || showNetSubs || showLocalSubs
    BackHandler(enabled = anyOverlay) {
        when {
            showNetSubs -> showNetSubs = false
            showLocalSubs -> showLocalSubs = false
            showTopMore -> showTopMore = false
            showInfo -> showInfo = false
            showCast -> {
                showCast = false
                Playback.stopDlnaDiscovery()
            }
            showDecode -> showDecode = false
            showSpeed -> showSpeed = false
            showQueue -> showQueue = false
            showTracks -> showTracks = false
        }
    }
    BackHandler(enabled = !anyOverlay && !locked) {
        Playback.onScreenLeave()
        navController.popBackStack()
    }
    // 锁定态不能让返回键直接退出 —— 锁定本来就是为了防误触。
    // 第一次按返回只给提示，3 秒内再次按返回才真正退出。
    // 加 !anyOverlay：它注册在最后，若不排除弹层态，锁定 + 弹层同时存在时
    // 它会抢在关闭弹层之前响应，违背「返回先关弹层」的优先级。
    var lastLockedBackAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = locked && !anyOverlay) {
        val now = System.currentTimeMillis()
        if (now - lastLockedBackAt < 3_000L) {
            Playback.onScreenLeave()
            navController.popBackStack()
        } else {
            lastLockedBackAt = now
            seekHint = "已锁定：再按一次返回退出播放"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Playback.onScreenLeave()
            // 保险箱视频是解密到缓存目录的临时文件，离开播放页就清掉，避免明文残留；
            // 同时把这一条从队列里摘掉，否则再点播放会指向已经删除的临时文件。
            val current = Playback.state.value.item
            if (current?.id?.startsWith("vault:") == true) {
                com.yaoahos.mypureplayer.data.Graph.vault.clearPlayCache()
                current.localPath?.let { Playback.dropDeletedFromQueue(it) }
            }
        }
    }

    LaunchedEffect(controlsVisible, state.playing, locked) {
        if (controlsVisible && state.playing && !locked) {
            delay(4200)
            controlsVisible = false
        }
    }

    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(900)
            seekHint = null
        }
    }

    // 单击判定：300ms 内没有第二次抬手才切换控件显隐
    LaunchedEffect(tapToken) {
        if (tapToken == 0) return@LaunchedEffect
        delay(300)
        if (!doubleTapFired) controlsVisible = !controlsVisible
        doubleTapFired = false
    }

    // 长按 2 倍速：静置达到系统长按时长就加速，松手恢复原倍速
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    LaunchedEffect(pressing, dragging) {
        if (pressing && !dragging && !locked) {
            delay(longPressMs + 12L)
            if (pressing && !dragging) {
                rateBeforeHold.floatValue = state.playbackRate
                longHeld = true
                Playback.setRate(2f)
                hud = GestureHud.Speed
            }
        } else if (!pressing && longHeld) {
            Playback.setRate(rateBeforeHold.floatValue)
            longHeld = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).also { Playback.attach(it) }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { Playback.detach() },
        )

        // ---------------------------------------------------------------- 手势层
        // 与系统侧滑返回分区共存：左右屏幕边缘各 48dp 是系统返回手势的起手区，
        // 横滑从这里起手一律让给系统（下面 pointerInput 里主动放弃）；
        // 中间区域起手的横滑才是快进快退。两者生效区不重叠，因此不需要
        // systemGestureExclusion 去排除系统手势——那等于把侧滑返回砍掉。
        // 参照 MX Player：左半屏上下滑调亮度、右半屏上下滑调音量、横向滑动快进快退、
        // 长按临时 2 倍速、双击左右两侧快退/快进 10 秒、双击中间暂停/继续。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 长按判定交给独立的 LaunchedEffect：手指完全不动时不会有后续指针事件，
                        // 若把超时判断写在事件循环里，静置长按永远触发不了。
                        pressing = true
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        // 系统侧滑返回的起手区：左右边缘各 64dp（比常见判定带略宽，
                        // 确保 MIUI 等 ROM 的边缘横滑不会既返回又 seek）。
                        // 从这里起手的横滑让给系统；竖滑（亮度/音量）系统不接管，照常生效。
                        val backEdgePx = 64.dp.toPx()
                        val startX = down.position.x
                        val startY = down.position.y
                        // 本次手势已让位给系统返回：抬手时不能再判单击/双击。
                        var handedToSystem = false

                        // 0 未定性 / 1 亮度 / 2 音量 / 3 快进快退
                        var mode = 0
                        var baseDy = 0f
                        var baseBrightness = 0f
                        var baseVolume = 0
                        var maxVolume = 1
                        var basePosition = 0L
                        var seekTarget = 0L
                        var seekValid = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                break
                            }
                            val dx = change.position.x - startX
                            val dy = change.position.y - startY

                            if (mode == 0) {
                                val slop = viewConfiguration.touchSlop
                                if (locked) {
                                    // 锁屏后只有单击有效果，避免误触改亮度/进度
                                    if (abs(dx) > slop * 2 || abs(dy) > slop * 2) {
                                        change.consume()
                                    }
                                } else if (abs(dx) > slop || abs(dy) > slop) {
                                    // 边缘起手的横滑让给系统侧滑返回：本层不消费、不判定，
                                    // 系统收到完整的手势序列才能完成返回。中间区域才判快进快退。
                                    if (abs(dx) >= abs(dy) &&
                                        (startX < backEdgePx || startX > width - backEdgePx)
                                    ) {
                                        handedToSystem = true
                                        break
                                    }
                                    dragging = true
                                    mode = when {
                                        abs(dx) >= abs(dy) -> 3
                                        startX < width * 0.42f -> 1
                                        startX > width * 0.58f -> 2
                                        else -> 0
                                    }
                                    baseDy = dy
                                    when (mode) {
                                        1 -> baseBrightness = activity.currentBrightness()
                                        2 -> {
                                            maxVolume = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15)
                                                .coerceAtLeast(1)
                                            baseVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                                        }

                                        3 -> basePosition = Playback.positionMs.value
                                    }
                                    change.consume()
                                }
                            }

                            if (mode != 0) {
                                change.consume()
                                when (mode) {
                                    1 -> {
                                        val delta = (baseDy - dy) / height
                                        val value = (baseBrightness + delta).coerceIn(0.02f, 1f)
                                        activity.applyBrightness(value)
                                        hud = GestureHud.Brightness((value * 100).roundToInt())
                                    }

                                    2 -> {
                                        val delta = ((baseDy - dy) / height * maxVolume).roundToInt()
                                        val value = (baseVolume + delta).coerceIn(0, maxVolume)
                                        runCatching {
                                            audioManager?.setStreamVolume(
                                                AudioManager.STREAM_MUSIC, value, 0,
                                            )
                                        }
                                        hud = GestureHud.Volume((value * 100f / maxVolume).roundToInt())
                                    }

                                    3 -> {
                                        val duration = Playback.state.value.durationMs
                                        if (duration > 0L) {
                                            // 手感固定：横向滑满整屏 = 120 秒。
                                            // 若按「整屏 = 全片时长」换算，两小时的片子滑一点就跳十几分钟，
                                            // 数字乱跳、跟手感很差。
                                            val deltaMs = (dx / width * SEEK_SPAN_MS).toLong()
                                            seekTarget = (basePosition + deltaMs).coerceIn(0L, duration)
                                            seekValid = true
                                            hud = GestureHud.Seek(seekTarget, deltaMs, duration)
                                        }
                                    }
                                }
                            }
                        }

                        // 抬手
                        pressing = false
                        dragging = false
                        if (mode == 3 && seekValid) {
                            Playback.seekTo(seekTarget)
                        }

                        if (!handedToSystem && mode == 0 && !longHeld && !locked) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastTapAt[0] in 1..300) {
                                lastTapAt[0] = 0
                                doubleTapFired = true
                                // 画面任意位置双击都只做暂停 / 继续，不再按区域快进快退
                                Playback.togglePlay()
                            } else {
                                lastTapAt[0] = now
                                tapToken++
                            }
                        }
                    }
                },
        )

        // 起播/缓冲等待层：任何需要用户等待的时刻都要有明确提示。
        // preparing 覆盖「点击 → setMediaItem 的 IDLE 空档 → prepare → 首帧」整个等待期，
        // buffering 覆盖播放中的重新缓冲（seek、网络抖动），两者任一为真都显示。
        // 只保留一个加载圈：不再叠封面遮罩、圆底和「正在加载…」文字——
        // 黑色蒙版在画面上很突兀，文字与图标表达的是同一件事，重复且抢画面。
        // 快进/快退提示条本身就代表「正在等这个位置出画面」，此时再叠一个加载圈
        // 等于同一个等待说了两遍，而且两个浮层都在画面中央偏下，看起来像叠在一起。
        // derivedStateOf：只在「是不是快进浮层」翻转时重组，拖动过程中 hud 每帧变化不会拖着整页重画
        val seekingHud by remember { derivedStateOf { hudState.value is GestureHud.Seek } }
        AnimatedVisibility(
            visible = (state.preparing || state.buffering) && state.error == null && !seekingHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // 只留加载圈本身：白色弧线 + 一圈很淡的轨道。
                // 有轨道才一眼看出这是「转圈」而不是画面上的残影；
                // 背景保持完全透明，不再盖黑底蒙版。
                CircularProgressIndicator(
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.16f),
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(56.dp),
                )
            }
        }

        // 面板/对话框打开时，页面中央被整个盖住，画在这儿的提示用户根本看不见。
        // 这种情况下提示交给浮层内部显示（例如信息面板里「已复制到剪贴板」），这里不再画。
        if (!modalOpen) {
            seekHint?.let { hint ->
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                ) {
                    Text(hint, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        GestureHudView(hudState)

        state.error?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { Playback.playAt(state.queueIndex) }) {
                    Text("重试", color = Color.White)
                }
            }
        }

        // 小窗的控制按钮由系统 RemoteAction 提供（播放/暂停 + 全屏），
        // 这里不再自绘 —— PiP 窗口会拦截触摸当作「展开」，自绘按钮点不到。

        // 锁定按钮固定在右侧中间；锁定后同一个位置变成解锁，其余控件全部隐藏
        // 面板打开时锁定按钮也收起：它是控制条的一部分，留着会浮在玻璃面板里
        if (!locked && controlsVisible && !pipActive && !modalOpen) {
            IconButton(
                onClick = {
                    locked = true
                    controlsVisible = false
                    seekHint = "控制已锁定，点右侧按钮解锁"
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .size(48.dp),
            ) {
                Icon(Icons.Rounded.LockOpen, contentDescription = "锁定屏幕", tint = Color.White)
            }
        }
        if (locked) {
            IconButton(
                onClick = { locked = false; controlsVisible = true },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .size(48.dp),
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = "解锁控制", tint = Color.White)
            }
        }

        // ---------------------------------------------------------------- 顶部栏
        // 面板打开时连同控制条一起淡出：面板贴右上左下三边，控制条的图标会透过玻璃
        // 隐约浮在面板里，看起来像脏点。面板关掉后控制条自动回来。
        AnimatedVisibility(
            visible = controlsVisible && !locked && !pipActive && !modalOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    Playback.onScreenLeave()
                    navController.popBackStack()
                }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                // 标题下方原本还有一行「文件夹 · 1/3 · 字幕：xxx」，信息密度过高且
                // 与右上角按钮抢空间，这里只保留文件名本身。
                Column(Modifier.weight(1f)) {
                    Text(
                        state.item?.title ?: "未在播放",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 右上角按钮：横屏全部平铺；竖屏只留后台/字幕/信息，投屏与解码收进溢出菜单，
                // 避免窄屏把标题挤成一条缝、图标互相挤压。
                // 选中态一律用当前主题的强调色，不再写死 #80DEEA。
                // 写死青色时：换到紫罗兰 / 绯樱主题，这两个按钮亮起来是画面里
                // 唯一不属于该配色的颜色；而在青碧主题下又几乎和背景同色（用户反馈
                // "后台播放的按钮选中状态颜色是固定的，不跟随主题色变化"）。
                val activeTint = playerGlass().accent
                val castButton: @Composable () -> Unit = {
                    IconButton(onClick = { openCast() }) {
                        Icon(
                            Icons.Rounded.Cast,
                            contentDescription = "投屏到局域网设备",
                            tint = if (state.rendererName.isNotBlank()) activeTint else Color.White,
                        )
                    }
                }
                val audioButton: @Composable () -> Unit = {
                    IconButton(onClick = { toggleBackgroundAudio() }) {
                        Icon(
                            Icons.Rounded.Headphones,
                            contentDescription = if (state.backgroundAudio) "恢复画面" else "后台播放音频",
                            tint = if (state.backgroundAudio) activeTint else Color.White,
                        )
                    }
                }
                val decodeButton: @Composable () -> Unit = {
                    IconButton(onClick = { showDecode = true }) {
                        Icon(
                            Icons.Rounded.Memory,
                            contentDescription = if (state.hardwareDecode) "当前硬件解码" else "当前软件解码",
                            tint = Color.White,
                        )
                    }
                }
                val subtitleButton: @Composable () -> Unit = {
                    IconButton(onClick = { trackTab = 0; showTracks = true }) {
                        Icon(
                            Icons.Rounded.Subtitles,
                            contentDescription = "字幕与音轨",
                            tint = if (
                                state.hasSubtitles ||
                                audioTracks.isNotEmpty() ||
                                subtitleFiles.isNotEmpty()
                            ) Color.White else Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
                val infoButton: @Composable () -> Unit = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Rounded.Info, contentDescription = "详情", tint = Color.White)
                    }
                }
                if (landscape) {
                    castButton()
                    audioButton()
                    decodeButton()
                    subtitleButton()
                    infoButton()
                } else {
                    audioButton()
                    subtitleButton()
                    infoButton()
                    IconButton(onClick = { showTopMore = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = Color.White)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- 底部控制条
        AnimatedVisibility(
            visible = controlsVisible && !locked && !pipActive && !modalOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // 时长三路兜底：内核已解析 → 列表项自带 → 抽帧时落盘的缓存。
            // 网络视频起播时内核还没解析出容器长度（SMB 首包要等），后面两路能先把
            // 总时长填上，用户就不会在"正在加载"的那几秒里一直看到 --:--。
            val duration = state.durationMs.takeIf { it > 0L }
                ?: state.item?.let { com.yaoahos.mypureplayer.media.Thumbnails.displayDuration(it) }
                ?: 0L
            val durationReady = duration > 0L
            val fraction = scrubbing ?: if (durationReady) (playbackPosition.toFloat() / duration) else 0f
            val shownPosition = scrubbing?.let { (it * duration).toLong() } ?: playbackPosition

            val glass = playerGlass()
            // 横屏控制条贴底，用渐变溶进画面；竖屏给一块带圆角的玻璃底，
            // 让按钮区和画面分层，手指操作时不会觉得按钮浮在视频上
            val bottomBackground = if (landscape) {
                Modifier.background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.80f))),
                )
            } else {
                Modifier
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                glass.panel.copy(alpha = 0.55f),
                                glass.panelSolid.copy(alpha = 0.94f),
                            ),
                        ),
                    )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(bottomBackground)
                    .navigationBarsPadding()
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = if (landscape) 2.dp else 8.dp,
                        bottom = if (landscape) 4.dp else 6.dp,
                    ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // 播放位置任何时刻都是确定的（起播即 00:00），不需要「加载中…」这种占位
                        Formats.formatDuration(shownPosition),
                        color = Color.White,
                        // 固定最小宽度：1:02:15 这样的时长比 0:00 宽，不占位就会把
                        // 进度条挤窄再弹回，读起来像"时长显示不全"
                        modifier = Modifier.widthIn(min = 52.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                        style = if (landscape) {
                            MaterialTheme.typography.labelSmall
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                    )
                    BoxWithConstraints(
                        // 高度收到 32dp：44dp 时手指区域比可视线条粗太多，
                        // 观感上"刻度很高、线很细"，比例不协调
                        Modifier.weight(1f).height(32.dp).padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 轨道与进度自己画，不再用 LinearProgressIndicator。
                        //
                        // 它的圆头端帽在进度为 0（加载中、刚起播）时会画成一枚实心圆点：
                        // 「零长度线段 + Round cap」在 Skia 里就是一个直径等于线宽的圆。
                        // 用户看到的「进度条上莫名其妙有个圆点」就是它 —— 位置在左端时像
                        // 起点多一颗点，进度到 100% 时又像终点多一颗点。
                        // 自绘之后进度为 0 就真的什么都不画。
                        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                            val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.22f),
                                cornerRadius = radius,
                                size = size,
                            )
                            val w = size.width * fraction.coerceIn(0f, 1f)
                            if (w > 0.6f) {
                                drawRoundRect(
                                    color = glass.accent,
                                    cornerRadius = radius,
                                    size = androidx.compose.ui.geometry.Size(w, size.height),
                                )
                            }
                        }
                        // 滑块头改成自己摆位，不再交给 Slider 的 thumb 槽。
                        //
                        // 之前把 12dp 的圆放在 thumb 槽里：M3 按自己那套 20dp 拇指尺寸
                        // 计算摆放偏移（槽位高度、轨道内缩都按 20dp 算），12dp 的圆被
                        // 顶到轨道上面，于是"圆点和进度条不居中"。用户连续两轮都能看出来。
                        // 现在圆点的位置由 fraction 直接算：轨道两端各让半个圆点，
                        // 100% 时圆心正好落在轨道右端，0% 落在左端，垂直方向与 6dp 轨道同心。
                        //
                        // 只在知道总时长后才画：长度未知时这一枚点既拖不动也说明不了位置，
                        // 只会被当成"进度条上多余的圆点"（用户："进度条终点为什么有个圆点"）。
                        if (durationReady) {
                            val travel = (maxWidth - 12.dp).coerceAtLeast(0.dp)
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = travel * fraction.coerceIn(0f, 1f))
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                            )
                        }
                        Slider(
                            value = fraction.coerceIn(0f, 1f),
                            onValueChange = { scrubbing = it },
                            onValueChangeFinished = {
                                scrubbing?.let { if (durationReady) Playback.seekTo((it * duration).toLong()) }
                                scrubbing = null
                            },
                            // 长度未知时不让拖：拖了也只能定位到 0
                            enabled = durationReady,
                            // 拇指只负责手势，视觉由上面那枚自绘圆点承担
                            thumb = {},
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Transparent,
                                activeTrackColor = Color.Transparent,
                                inactiveTrackColor = Color.Transparent,
                            ),
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    Text(
                        if (durationReady) Formats.formatDuration(duration) else "--:--",
                        color = if (durationReady) Color.White else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.widthIn(min = 52.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        style = if (landscape) {
                            MaterialTheme.typography.labelSmall
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                    )
                }

                // 播放控制与右下角工具放在同一行；窄屏时收起 ±10s，避免把播放键顶到上一行
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val narrow = maxWidth < 500.dp
                    // 竖屏右侧只留「播放列表 + 小窗」两个工具（播放速度收进顶部更多菜单），
                    // 播放控制就能真正居中；否则三组按钮会把播放键挤到屏幕左侧 1/4 处
                    val centered = maxWidth >= 340.dp
                    Box(Modifier.fillMaxWidth().height(56.dp)) {
                        Row(
                            Modifier.align(if (centered) Alignment.Center else Alignment.CenterStart),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { Playback.previous() },
                                enabled = state.queueIndex > 0,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.SkipPrevious,
                                    contentDescription = "上一个",
                                    tint = if (state.queueIndex > 0) Color.White else Color.White.copy(alpha = 0.32f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            if (!narrow) {
                                TextButton(onClick = { Playback.seekBy(-10_000) }) {
                                    Text("−10s", color = Color.White, fontWeight = FontWeight.Medium)
                                }
                            }
                            IconButton(
                                onClick = { Playback.togglePlay() },
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .background(Color.White.copy(alpha = 0.14f), CircleShape)
                                    .size(if (narrow) 48.dp else 52.dp),
                            ) {
                                Icon(
                                    imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (state.playing) "暂停" else "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(if (narrow) 28.dp else 30.dp),
                                )
                            }
                            if (!narrow) {
                                TextButton(onClick = { Playback.seekBy(10_000) }) {
                                    Text("+10s", color = Color.White, fontWeight = FontWeight.Medium)
                                }
                            }
                            val hasNext = state.queueIndex >= 0 && state.queueIndex < state.queueSize - 1
                            IconButton(
                                onClick = { Playback.next() },
                                enabled = hasNext,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.SkipNext,
                                    contentDescription = "下一个",
                                    tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.32f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        // 右下角顺序：播放列表 → 字幕音轨 → 播放速度 → 画面比例 → 小窗 → 旋转
                        // 竖屏把触摸区收到 44dp、图标放大 1dp：按钮更紧凑但不难点，
                        // 给左侧播放控制腾出呼吸空间
                        val toolSize = if (narrow) 44.dp else 48.dp
                        val toolIcon = if (narrow) 20.dp else 19.dp
                        Row(
                            Modifier.align(Alignment.CenterEnd),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { showQueue = true }, modifier = Modifier.size(toolSize)) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.FormatListBulleted,
                                    contentDescription = "播放列表",
                                    tint = Color.White,
                                    modifier = Modifier.size(toolIcon),
                                )
                            }
                            // 竖屏右下角空间有限：速度入口移到顶部「更多」菜单，
                            // 这里只留播放列表与小窗，播放控制才能居中
                            if (!narrow) {
                                IconButton(onClick = { showSpeed = true }, modifier = Modifier.size(toolSize)) {
                                    Icon(
                                        Icons.Rounded.Speed,
                                        contentDescription = "播放速度",
                                        tint = Color.White,
                                        modifier = Modifier.size(toolIcon),
                                    )
                                }
                            }
                            // 画面比例 / 旋转在窄竖屏时收进顶部溢出菜单，避免和左侧播放键挤在一起
                            if (!narrow) {
                                IconButton(onClick = { cycleScale() }, modifier = Modifier.size(toolSize)) {
                                    Icon(
                                        Icons.Rounded.AspectRatio,
                                        contentDescription = "画面比例：${Playback.scaleLabel(state.scaleMode)}",
                                        tint = Color.White,
                                        modifier = Modifier.size(toolIcon),
                                    )
                                }
                            }
                            IconButton(onClick = { enterPictureInPicture() }, modifier = Modifier.size(toolSize)) {
                                Icon(
                                    Icons.Rounded.PictureInPictureAlt,
                                    contentDescription = "小窗播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(toolIcon),
                                )
                            }
                            if (!narrow) {
                                IconButton(onClick = { rotateScreen() }, modifier = Modifier.size(toolSize)) {
                                    Icon(
                                        Icons.Rounded.ScreenRotation,
                                        contentDescription = "旋转屏幕",
                                        tint = Color.White,
                                        modifier = Modifier.size(toolIcon),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 右侧短菜单
    // 解码 / 播放速度 / 竖屏「更多」都改成右侧弹出的小卡片：与侧栏同一个滑入方向、
    // 同一套玻璃底与强调色，只是高度跟着内容走，几张选择不值得铺一整屏侧栏。
    PlayerPopupCard(visible = showDecode, onDismiss = { showDecode = false }) {
        // 短菜单与长面板共用同一个标题组件（compact 只收窄留白，字号不变），
        // 这样「解码方式」和「播放列表」的标题读起来是同一个东西
        GlassPanelHeader("解码方式", onClose = { showDecode = false }, compact = true, inset = 10.dp)
        Spacer(Modifier.height(4.dp))
        listOf(true to "硬件解码", false to "软件解码").forEach { (hardware, label) ->
            GlassRow(
                title = label,
                selected = state.hardwareDecode == hardware,
                onClick = {
                    showDecode = false
                    switchDecode(hardware)
                },
            )
        }
    }

    // 竖屏窄屏放不下横屏那排按钮，收进「更多」，内容与横屏能力一一对应
    PlayerPopupCard(visible = showTopMore, onDismiss = { showTopMore = false }) {
        GlassPanelHeader("更多", onClose = { showTopMore = false }, compact = true, inset = 10.dp)
        Spacer(Modifier.height(4.dp))
        GlassRow(
            title = "投屏到电视",
            selected = false,
            onClick = { showTopMore = false; openCast() },
        )
        GlassRow(
            title = if (state.hardwareDecode) "解码：硬件" else "解码：软件",
            selected = false,
            onClick = { showTopMore = false; showDecode = true },
        )
        GlassRow(
            title = "播放速度：${if (state.playbackRate == 1f) "1.0×" else "${state.playbackRate}×"}",
            selected = false,
            onClick = { showTopMore = false; showSpeed = true },
        )
        GlassRow(
            title = "画面比例：${Playback.scaleLabel(state.scaleMode)}",
            selected = false,
            onClick = { showTopMore = false; cycleScale() },
        )
        GlassRow(
            title = if (landscape) "旋转为竖屏" else "旋转为横屏",
            selected = false,
            onClick = { showTopMore = false; rotateScreen() },
        )
    }

    PlayerPopupCard(visible = showSpeed, onDismiss = { showSpeed = false }) {
        GlassPanelHeader("播放速度", onClose = { showSpeed = false }, compact = true, inset = 10.dp)
        Spacer(Modifier.height(4.dp))
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
            GlassRow(
                title = if (rate == 1f) "1.0×（原速）" else "${rate}×",
                selected = state.playbackRate == rate,
                onClick = {
                    showSpeed = false
                    Playback.setRate(rate)
                },
            )
        }
    }

    // 播放列表：横竖屏统一从右侧滑入，同一个动效、同一套玻璃外观
    PlayerSidePanel(visible = showQueue, landscape = landscape, onDismiss = { showQueue = false }) {
        QueueSheet(
            queueItems = queue,
            currentIndex = state.queueIndex,
            fillHeight = true,
            onSelect = { index ->
                Playback.playAt(index)
                showQueue = false
            },
            onDismiss = { showQueue = false },
        )
    }

    // 字幕音轨：竖屏用底部面板；横屏与播放列表一致，放在右侧三分之一区域。
    // TrackSheet 内含 LazyColumn，不能放进 DropdownMenu（同向嵌套滚动会崩溃）。
    val isSmbSource = state.item?.source == MediaSource.SMB
    // 去重：外挂字幕被合并成一条文本轨后，轨道名就是文件名，而同目录扫描又把它列进
    // subtitleFiles，于是同一字幕展示两条（一条轨道、一条文件）。以 subtitleFiles 为
    // 真值源，凡轨道名与候选文件名归一后相同，就从轨道列表剔除，只留可点击的文件项。
    val subtitleFileNames = remember(subtitleFiles) {
        subtitleFiles.map { it.name.substringBeforeLast('.').lowercase() }.toSet()
    }
    // 远程字幕会先下载到 cache/subs/remote_<sha1>.<ext> 再挂载，轨道名就是这份缓存副本的
    // 文件名。它与 subtitleFiles 中同内容的真实字幕是同一条，必须单独识别并剔除。
    val cachedSubtitleCopy = remember {
        Regex("""(?:^|[\\/])remote_[0-9a-f]{40}\.[a-z0-9]{1,8}$""")
    }
    val trackSheetContent: @Composable () -> Unit = {
        TrackSheet(
            tab = trackTab,
            onTabChange = { trackTab = it },
            subtitleTracks = tracks
                .filterNot { track ->
                    // 内嵌轨道的名字由 Playback 拼成「字幕 N · 语言」，正常不会出现无名的
                    // 占位行；这里只去掉明确的关闭项，别再把真实轨道连同名字一起滤掉。
                    val n = track.name.trim()
                    track.id < 0 ||
                        n.equals("Disable", ignoreCase = true) || n == "禁用" ||
                        Regex("""^Track\s*\d+$""", RegexOption.IGNORE_CASE).matches(n)
                }
                .filterNot { track ->
                    // 轨道名可能是完整文件名、也可能是 Playback 加的「字幕 N · 文件名」。
                    // 先剥前缀，再取 basename 与候选字幕文件比对；远程字幕的缓存副本
                    // （cache/subs/remote_<sha1>.<ext>）也一并剔除。
                    val bare = track.name.trim().lowercase()
                        .replaceFirst(Regex("""^字幕\s*\d+\s*·\s*"""), "")
                    val fileName = bare.substringAfterLast('/').substringAfterLast('\\')
                    val stem = fileName.substringBeforeLast('.')
                    val cacheCopy = cachedSubtitleCopy.matches(bare) ||
                        bare.contains("cache/subs/") || bare.contains("cache\\subs\\")
                    cacheCopy || (stem.isNotBlank() && stem in subtitleFileNames)
                },

            activeSubtitleId = state.activeSubtitleId,
            subtitleFiles = subtitleFiles,
            autoLoaded = state.autoSubtitleName,
            isSmb = isSmbSource,
            audioTracksList = audioTracks,
            onSelectSubtitleTrack = { Playback.selectSubtitleTrack(it) },
            onDisableSubtitles = { Playback.disableSubtitles() },
            onSelectSubtitleFile = { Playback.selectSubtitleFile(it) },
            onBrowseLocalFolder = {
                showTracks = false
                // 就在播放页之上翻目录，不跳系统文件页（那个页面一定是竖屏）
                showLocalSubs = true
            },
            onBrowseNetwork = {
                showTracks = false
                val serverId = state.item?.serverId ?: 0L
                if (serverId > 0L) {
                    // 就在播放页之上翻开目录：不再跳独立路由，
                    // 否则横屏播放页 → 竖屏选择页 → 横屏播放页，屏幕要来回转两次。
                    showNetSubs = true
                } else {
                    seekHint = "当前视频不是网络视频，请从设备选择字幕"
                }
            },
            onSelectAudioTrack = { Playback.selectAudioTrack(it) },
            onDisableAudio = { Playback.disableAudio() },
        )
    }
    PlayerSidePanel(visible = showTracks, landscape = landscape, onDismiss = { showTracks = false }) {
        trackSheetContent()
    }

    PlayerSidePanel(
        visible = showCast,
        landscape = landscape,
        onDismiss = {
            showCast = false
            Playback.stopDlnaDiscovery()
        },
    ) {
        val glass = playerGlass()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 18.dp),
        ) {
            GlassPanelHeader(
                title = "投屏到局域网设备",
                // 原来这段说明有三行，标题栏统一后说明只保留一行关键信息，
                // 详细条件放到列表下方的空状态提示里，避免标题区被拉得过高
                subtitle = "同一 Wi-Fi 下的 DLNA / UPnP 电视与盒子",
                onClose = {
                    showCast = false
                    Playback.stopDlnaDiscovery()
                },
            )
            Spacer(Modifier.height(10.dp))
            GlassRow(
                title = if (state.rendererName.isBlank()) "本机播放" else "本机播放（当前：${state.rendererName}）",
                selected = state.rendererName.isBlank(),
                onClick = {
                    Playback.stopCasting()
                    showCast = false
                    Playback.stopDlnaDiscovery()
                },
            )
            if (dlnaDevices.isEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "还没有发现设备，确保电视 / 机顶盒与手机在同一局域网并保持发现开关开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.onGlassSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "重新搜索",
                    style = MaterialTheme.typography.labelLarge,
                    color = glass.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            Playback.stopDlnaDiscovery()
                            Playback.startDlnaDiscovery()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(dlnaDevices, key = { "dlna-${it.controlUrl}" }) { device ->
                        GlassRow(
                            title = device.name,
                            selected = device.name == state.rendererName,
                            onClick = {
                                Playback.selectDlna(device)
                                showCast = false
                                Playback.stopDlnaDiscovery()
                                seekHint = "已投屏到 ${device.name}"
                            },
                        )
                    }
                }
            }
        }
    }

    if (showInfo) {
        state.item?.let { infoItem ->
            PlayerInfoSheet(
                item = infoItem,
                landscape = landscape,
                dynamicRange = state.dynamicRange,
                audioOutput = state.audioOutput,
                onDismiss = { showInfo = false },
            )
        }
    }

    // 网络字幕目录：作为播放页之上的浮层，不换路由、不改屏幕方向
    if (showNetSubs) {
        val serverId = state.item?.serverId ?: 0L
        val startPath = state.item?.folderKey.orEmpty()
        NetworkSubtitlePickerDialog(
            serverId = serverId,
            initialPath = startPath,
            onDismiss = { showNetSubs = false },
        )
    }

    // 本地字幕目录：同样用浮层，从当前视频所在目录开始翻
    if (showLocalSubs) {
        val startDir = remember(state.item?.id) {
            state.item?.localPath?.let { File(it).parentFile }
                ?: android.os.Environment.getExternalStorageDirectory()
        }
        LocalSubtitlePickerDialog(
            startDir = startDir,
            onDismiss = { showLocalSubs = false },
            onPick = { file ->
                showLocalSubs = false
                Playback.selectSubtitleFile(
                    SubtitleFile(
                        name = file.name,
                        uri = Uri.fromFile(file).toString(),
                        sizeBytes = runCatching { file.length() }.getOrDefault(0L),
                    ),
                )
                seekHint = "已加载字幕：${file.name}"
            },
        )
    }
}

/** 串流地址：去掉 smb://user:pass@ 里的账号密码，避免信息面板泄露凭证 */
private fun safeStreamUrl(item: VideoItem): String {
    val raw = when (item.source) {
        MediaSource.SMB -> item.uri
        MediaSource.STREAM -> item.uri
        MediaSource.LOCAL -> item.localPath ?: item.uri
    }
    return raw.replace(Regex("^(smb://)[^/@]+@"), "$1")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerInfoSheet(
    item: VideoItem,
    landscape: Boolean,
    dynamicRange: String,
    audioOutput: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 复制反馈就地显示：这个面板会盖住播放页中央，往页面发提示等于没发
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }
    val duration = Thumbnails.displayDuration(item).let {
        if (it > 0L) it else item.durationMs
    }
    val streamUrl = safeStreamUrl(item)
    // 本地视频展示的是设备上的文件路径，不该叫「串流地址」
    val addressLabel = if (item.source == MediaSource.LOCAL) "文件地址" else "串流地址"
    val sourceText = when (item.source) {
        MediaSource.LOCAL -> "本地文件"
        MediaSource.SMB -> "网络共享 (SMB)"
        MediaSource.STREAM -> "网络串流"
    }
    val infoContent: @Composable () -> Unit = {
        val glass = playerGlass()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            GlassPanelHeader(title = "视频详情", onClose = onDismiss)
            Spacer(Modifier.height(12.dp))

            // 标题本来就由文件名推导而来，两行都写会显得重复，信息面板只保留文件名
            InfoItem("文件名", item.fileName.ifBlank { item.title })
            InfoItem("来源", sourceText)
            if (item.folderName.isNotBlank() && item.source == MediaSource.LOCAL) {
                InfoItem("所在目录", item.folderName)
            }
            if (item.sizeBytes > 0L) InfoItem("大小", Formats.formatSize(item.sizeBytes))
            if (duration > 0L) InfoItem("时长", Formats.formatDuration(duration))
            if (item.width > 0 && item.height > 0) {
                InfoItem("分辨率", "${item.width} × ${item.height}")
            }
            // 动态范围与声音输出方式来自实时解析出的 Format，不是列表项缓存：
            // 起播后才会有值，解析不出来时整行不显示，不猜 SDR。
            if (dynamicRange.isNotBlank()) InfoItem("画面", dynamicRange)
            if (audioOutput.isNotBlank()) InfoItem("声音", audioOutput)

            Spacer(Modifier.height(18.dp))
            Text(
                addressLabel,
                style = MaterialTheme.typography.labelMedium,
                color = glass.onGlassSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, glass.divider, RoundedCornerShape(12.dp))
                    .pointerInput(streamUrl) {
                        detectTapGestures {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText(addressLabel, streamUrl))
                            copied = true
                        }
                    }
                    .padding(12.dp),
            ) {
                Text(
                    streamUrl.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.onGlass,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (copied) "已复制到剪贴板" else "点击地址可复制",
                style = MaterialTheme.typography.labelSmall,
                color = if (copied) glass.accent else glass.onGlassSecondary,
            )
        }
    }
    // 横竖屏统一右侧滑入，不再一处底部面板、一处右侧侧栏
    PlayerSidePanel(visible = true, landscape = landscape, onDismiss = onDismiss) {
        infoContent()
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    if (value.isBlank()) return
    val glass = playerGlass()
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = glass.onGlassSecondary,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = glass.onGlass,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GestureHudView(state: MutableState<GestureHud?>) {
    // 只在这个浮层里读 hud：拖动时重组范围被限制在这里，
    // 视频画面与控制栏不跟着重画，滑动才不掉帧
    val hud by state
    val current = hud ?: return

    LaunchedEffect(current) {
        delay(900)
        state.value = null
    }

    val alignment = when (current) {
        is GestureHud.Brightness -> Alignment.CenterStart
        is GestureHud.Volume -> Alignment.CenterEnd
        // 快进/快退提示条固定在底部上方：屏幕正中留给加载圈，
        // 两者同时出现时不再叠在一起（拖动后松手必然伴随一次缓冲）
        is GestureHud.Seek -> Alignment.BottomCenter
        else -> Alignment.Center
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp)
            .padding(bottom = if (current is GestureHud.Seek) 108.dp else 0.dp),
        contentAlignment = alignment,
    ) {
        when (current) {
            is GestureHud.Brightness -> LevelHud(
                icon = Icons.Rounded.Brightness6,
                fraction = current.percent / 100f,
            )

            is GestureHud.Volume -> LevelHud(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                fraction = current.percent / 100f,
            )

            is GestureHud.Seek -> SeekHud(current)
            GestureHud.Speed -> SpeedHud()
        }
    }
}

/**
 * 浮层底：一层没有边界的径向暗晕。
 *
 * 拿掉玻璃卡片之后，白字落在明亮画面上会糊掉（实测 69% 那个数字压在浅色背景上几乎读不出）。
 * 但再塞回一张卡片又回到"塑料牌"的观感。折中做法是画一层**看不到边界**的暗晕：
 * 中心最深、向外渐隐到透明，读起来只是"这块画面暗了一点"。
 */
private fun Modifier.hudGlow(): Modifier = drawBehind {
    val radius = size.maxDimension * 0.72f
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.Black.copy(alpha = 0.46f),
            0.55f to Color.Black.copy(alpha = 0.30f),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * 亮度 / 音量浮层：上方百分比、中间竖向条、下方图标。
 *
 * **不套玻璃面板**。早先这三条浮层（含快进、倍速）都包了一块 panelSolid 卡片，
 * 在画面上就是一张压上去的塑料牌，与播放页"轻"的控制层不是一套东西 —— 用户原话
 * "不应该使用玻璃面板，感觉很突兀"。现在用 [hudGlow] 的暗晕代替卡片底。
 */
@Composable
private fun LevelHud(icon: ImageVector, fraction: Float) {
    val glass = playerGlass()
    val value = fraction.coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .hudGlow()
            .padding(horizontal = 26.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${(value * 100).roundToInt()}%",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(shadow = HudTextShadow),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(160.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White.copy(alpha = 0.26f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(value)
                    .clip(RoundedCornerShape(5.dp))
                    .background(glass.accent),
            )
        }
        Spacer(Modifier.height(10.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 浮层文字的统一投影：在亮画面上给白字一个底，替代不透明的卡片底 */
private val HudTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.72f),
    offset = androidx.compose.ui.geometry.Offset(0f, 1.5f),
    blurRadius = 10f,
)

/** 快进快退浮层：仍然是横向条，因为这里必须能读出目标时间。
 * 不加投影、不加暗晕：用户明确要求快进提示条保持干净，不要阴影。 */
@Composable
private fun SeekHud(seek: GestureHud.Seek) {
    val sign = if (seek.deltaMs >= 0) "+" else "\u2212"
    Column(
        modifier = Modifier
            .padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Formats.formatDuration(seek.targetMs),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "$sign${Formats.formatDuration(abs(seek.deltaMs))}",
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(9.dp))
        val fraction = if (seek.durationMs > 0L) {
            seek.targetMs.toFloat() / seek.durationMs.toFloat()
        } else {
            0f
        }
        HudBar(fraction.coerceIn(0f, 1f), barWidth = 180.dp)
    }
}

@Composable
private fun SpeedHud() {
    Text(
        "2\u00d7 倍速",
        color = Color.White,
        style = MaterialTheme.typography.titleMedium.copy(shadow = HudTextShadow),
        modifier = Modifier
            .hudGlow()
            .padding(horizontal = 26.dp, vertical = 18.dp),
    )
}

/** 快进浮层里的进度条：加厚的轨道 + 主题色渐变填充 + 末端高光 */
@Composable
private fun HudBar(fraction: Float, barWidth: Dp = 148.dp) {
    val glass = playerGlass()
    val value = fraction.coerceIn(0f, 1f)
    val trackShape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .width(barWidth)
            .height(9.dp)
            .clip(trackShape)
            .background(Color.White.copy(alpha = 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), trackShape),
    ) {
        if (value > 0.005f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width((barWidth - 2.dp) * value)
                    .clip(trackShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(glass.accent.copy(alpha = 0.62f), glass.accent),
                        ),
                    ),
            ) {
                // 末端一小段白色高光，拖动时能一眼看到进度头停在哪
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(3.dp)
                        .background(Color.White.copy(alpha = 0.9f)),
                )
            }
        }
    }
}

/** 字幕 + 音轨共用一个面板，用顶部两个标签切换 */
@Composable
private fun TrackSheet(
    tab: Int,
    onTabChange: (Int) -> Unit,
    subtitleTracks: List<SubtitleTrack>,
    activeSubtitleId: Int,
    subtitleFiles: List<SubtitleFile>,
    autoLoaded: String?,
    isSmb: Boolean,
    audioTracksList: List<SubtitleTrack>,
    onSelectSubtitleTrack: (Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    onSelectSubtitleFile: (SubtitleFile) -> Unit,
    onBrowseLocalFolder: () -> Unit,
    onBrowseNetwork: () -> Unit,
    onSelectAudioTrack: (Int) -> Unit,
    onDisableAudio: () -> Unit,
) {
    val glass = playerGlass()
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 22.dp),
    ) {
        GlassPanelHeader(title = "字幕与音轨", subtitle = "选择字幕轨道或配音音轨")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 选中态只用一个淡主题色底 + 主题色文字：同一时刻只强调一次。
            // 之前「底色 + 主题色边框 + 图标」三处同时强调，看起来像被框住的按钮
            val chipColors = FilterChipDefaults.filterChipColors(
                containerColor = Color.White.copy(alpha = 0.07f),
                labelColor = glass.onGlassSecondary,
                selectedContainerColor = glass.accent.copy(alpha = 0.22f),
                selectedLabelColor = glass.accent,
            )
            listOf("字幕", "音轨").forEachIndexed { index, label ->
                FilterChip(
                    selected = tab == index,
                    onClick = { onTabChange(index) },
                    label = { Text(label) },
                    colors = chipColors,
                    // M3 的 FilterChip 默认给未选中项画一圈描边，在深色玻璃上
                    // 会变成一个抢眼的白色圆圈，让人误以为它是选中项。全部关掉，
                    // 选中与否只靠「淡主题色底 + 主题色文字」表达。
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = tab == index,
                        borderColor = Color.Transparent,
                        selectedBorderColor = Color.Transparent,
                    ),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (tab == 0) {
            // 已加载的字幕在下面列表里就是勾选态，不用再单独提示"已自动加载"
            // 关闭字幕与关闭音轨使用同一行样式：纯文字行，不带图标
            SheetRow(
                title = "关闭字幕",
                selected = activeSubtitleId < 0,
                onClick = onDisableSubtitles,
            )
            subtitleTracks.forEach { track ->
                SheetRow(
                    title = if (track.supported) track.name else "${track.name}（本机不支持）",
                    selected = track.id == activeSubtitleId,
                    enabled = track.supported,
                    onClick = { onSelectSubtitleTrack(track.id) },
                )
            }
            if (subtitleFiles.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (isSmb) "视频所在目录的同名字幕" else "同目录同名字幕",
                    style = MaterialTheme.typography.labelMedium,
                    color = glass.onGlassSecondary,
                )
                Spacer(Modifier.height(4.dp))
                // 用普通 Column 而不是 LazyColumn：外层已经有 verticalScroll，
                // 同向嵌套滚动会导致IllegalArgumentException 崩溃；同时让字幕多时
                // 下方的目录按钮也跟着整体滚动，而不是字幕单独滚、按钮固定。
                subtitleFiles.forEach { file ->
                    SheetRow(
                        title = file.name,
                        selected = autoLoaded == file.name ||
                            (autoLoaded == null && activeSubtitleId >= 0 && subtitleFiles.size == 1),
                        icon = Icons.Rounded.FolderOpen,
                        onClick = { onSelectSubtitleFile(file) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // 「打开目录」是**次级导航动作**，不是主操作：这里原本用强调色，
                // 在同一屏里和"选中的字幕轨道"抢视线 —— 一个只是翻目录，一个才是在选，
                // 两者都用高饱和强调色，用户反而分不清哪个是当前状态。
                // 改成普通前景色（图标再降一档），选中态继续独占强调色。
                TextButton(onClick = onBrowseLocalFolder) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = glass.onGlassSecondary)
                    Spacer(Modifier.size(6.dp))
                    Text("打开本地字幕目录", color = glass.onGlass)
                }
                // 本地视频没有网络目录，不显示这一项，避免点了只得到一句「当前不是网络视频」
                if (isSmb) {
                    TextButton(onClick = onBrowseNetwork) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = glass.onGlassSecondary)
                        Spacer(Modifier.size(6.dp))
                        Text("打开网络字幕目录", color = glass.onGlass)
                    }
                }
            }
        } else {
            if (audioTracksList.isEmpty()) {
                Text(
                    "这个视频没有可切换的音轨。",
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.onGlassSecondary,
                )
            } else {
                // 与「关闭字幕」对称：关掉音轨后画面照常、只留静音
                SheetRow(
                    title = "关闭音轨",
                    selected = audioTracksList.none { it.selected },
                    onClick = onDisableAudio,
                )
                audioTracksList.forEach { track ->
                    SheetRow(
                        title = if (track.supported) track.name else "${track.name}（本机不支持）",
                        selected = track.selected,
                        enabled = track.supported,
                        onClick = { onSelectAudioTrack(track.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
) {
    // 面板统一是深色玻璃底。选中态同样不再"底色 + 文字 + 勾"三处同色：
    // 底色给一层很淡的强调色，文字保持高对比的白，只有图标与勾用强调色。
    // 三处同色时整行会糊成一块，反而看不出"这一行被选中了"的边界。
    val glass = playerGlass()
    // 本机不支持的轨道仍然列出（否则用户会以为片源没有这条音轨），但降一档灰且不可点。
    val contentColor = if (enabled) glass.onGlass else glass.onGlassSecondary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) glass.selectedFill else Color.Transparent,
                RoundedCornerShape(14.dp),
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = if (selected) "取消选择" else "选择",
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) glass.accent else glass.onGlassSecondary,
            )
            Spacer(Modifier.size(10.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "当前选中",
                tint = glass.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 播放中的队列面板：参考 PiliPlus 的「稍后再看 / 分P」面板，
 * 顶栏固定、下方是带缩略图的紧凑列表，当前项用主色强调。
 */
@Composable
private fun QueueSheet(
    queueItems: List<VideoItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    fillHeight: Boolean = false,
) {
    val glass = playerGlass()
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.heightIn(max = 560.dp))
            .statusBarsPadding()
            .padding(horizontal = 8.dp)
            .padding(top = 8.dp, bottom = 18.dp),
    ) {
        // 标题样式与其余浮层完全一致（GlassPanelHeader），不再各写各的
        GlassPanelHeader(
            title = "播放列表",
            subtitle = "共 ${queueItems.size} 个视频 · 点击切换",
            onClose = onDismiss,
            inset = 8.dp,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(queueItems.size, key = { queueItems[it].id }) { index ->
                val item = queueItems[index]
                val selected = index == currentIndex
                val tint = if (selected) glass.accent else glass.onGlass
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selected) glass.selectedFill
                            else Color.Transparent,
                        )
                        .clickable(role = Role.Button, onClickLabel = "播放") { onSelect(index) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 缩略图从 104×60 收到 88×50：面板只有 310dp 上下，
                    // 原来一张图就吃掉三分之一，右边的文件名被挤成
                    // "VID_20220814_145 / 152" 这种从中间断开的碎片，
                    // 序号也贴着文字没有呼吸。缩小预览图把宽度还给标题。
                    Box(Modifier.size(width = 84.dp, height = 48.dp)) {
                        VideoThumb(item, Modifier.fillMaxSize(), cornerRadius = 9.dp)
                        val duration = Thumbnails.displayDuration(item)
                        if (duration > 0L) {
                            DurationBadge(
                                Formats.formatDuration(duration),
                                Modifier.align(Alignment.BottomEnd).padding(2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.size(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = tint,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 不再显示文件夹名：队列条目只保留标题，选中项用「正在播放」作状态标识
                        if (selected) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "正在播放",
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.accent.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.size(6.dp))
                    // 序号右对齐并给一个最小宽度：三位数（105）和一位数（3）
                    // 都在同一条竖线上收尾，不会随条目不同左右跳
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = tint.copy(alpha = 0.75f),
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(min = 18.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 亮度与窗口

private fun Activity?.currentBrightness(): Float {
    val value = this?.window?.attributes?.screenBrightness ?: -1f
    // -1 表示跟随系统，这里用一个中间值作为滑动起点
    return if (value < 0f) 0.5f else value
}

private fun Activity?.applyBrightness(value: Float) {
    val window = this?.window ?: return
    val params = window.attributes
    params.screenBrightness = value.coerceIn(0.02f, 1f)
    window.attributes = params
}

@Composable
private fun ImmersiveFullScreen() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = view.context.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
