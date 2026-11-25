package com.yaoahos.mypureplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.yaoahos.mypureplayer.R
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.AppSettings
import com.yaoahos.mypureplayer.data.HistoryRepo
import com.yaoahos.mypureplayer.data.LocalVideoRepo
import com.yaoahos.mypureplayer.data.MediaSource
import com.yaoahos.mypureplayer.data.PlaybackProgressRepo
import com.yaoahos.mypureplayer.data.QueueRepo
import com.yaoahos.mypureplayer.data.ServerRepo
import com.yaoahos.mypureplayer.data.SettingsRepo
import com.yaoahos.mypureplayer.data.SmbClient
import com.yaoahos.mypureplayer.data.SmbErrors
import com.yaoahos.mypureplayer.data.SmbServer
import com.yaoahos.mypureplayer.data.SubtitleFile
import com.yaoahos.mypureplayer.data.SubtitleTrack
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.data.PlayerUiState
import com.yaoahos.mypureplayer.media.Thumbnails
import com.yaoahos.mypureplayer.ui.components.Notice
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * 全局播放控制器：一个 Media3 ExoPlayer 实例，贯穿整个应用生命周期。
 *
 * 播放列表 = [queue]，播放位置 = [state].queueIndex。
 * 打开任意视频时由界面层决定「同目录全部视频一起入队」，控制器本身只负责按队列播放。
 *
 * 投屏走独立的 DLNA / UPnP 链路（见 [DlnaRenderer]），不依赖 Media3 Cast 或 Google Play
 * 服务；播放内核就是 Jetpack Media3，UI 层（PlayerScreen 等）只消费这里的 StateFlow。
 */
object Playback {

    private const val TAG = "Playback"
    private const val HISTORY_TICK_MS = 30_000L
    /** 换片时应用的起点位置容差；偏差内不再补 seek */
    private const val START_TIME_TOLERANCE_MS = 2_000L
    /** 字幕外挂轨道的最小自定义 id，避开媒体内嵌轨道 id */
    private const val EXTERNAL_SUBTITLE_ID_BASE = 128

    /**
     * STATE_READY 后首帧仍未渲染时的撤圈兜底时长。
     *
     * 只对**没有视频轨**的情况生效（纯音频、投屏接收端）：这类片源永远不会回调
     * onRenderedFirstFrame，不撤圈就会一直转。
     */
    private const val FIRST_FRAME_FALLBACK_MS = 1_200L

    /**
     * 有视频轨但首帧迟迟不来时，最多再等这么久。
     *
     * 早先这里没有区分有没有视频轨，一律 1.2s 撤圈 —— 网络视频（SMB / HTTP）
     * 在 READY 之后解码出首帧常常要好几秒，于是出现用户描述的那一幕：
     * 「转圈只转一下，然后黑屏，等几秒画面才出来」。
     * 转圈时长必须覆盖到真正出画面为止，只有到这条硬上限才放弃等待。
     */
    private const val FIRST_FRAME_MAX_WAIT_MS = 20_000L

    /**
     * 播放中重新缓冲时，延迟这么久才把转圈画出来。
     *
     * seek 到未缓冲位置时 Media3 一定会走一次 STATE_BUFFERING，但网络/本地缓存
     * 往往两三百毫秒就恢复。立刻显示转圈就是「每次快进都闪一下圈」——
     * 延迟到这个阈值，短暂重缓冲根本不会上屏，真卡住了才提示。
     */
    private const val BUFFERING_UI_DELAY_MS = 400L

    /** 首帧上屏后等这么久再截缩略图：跳过片头黑场、台标与开场字幕 */
    private const val THUMB_CAPTURE_DELAY_MS = 2_500L
    /** 截到黑场（夜景/淡入）时补截一次的间隔 */
    private const val THUMB_RETRY_DELAY_MS = 6_000L

    /** 起播前等服务器回音的时长；超过就当「不知道」，让播放页照常去连 */
    private const val SMB_PROBE_WAIT_MS = 2_000L

    private lateinit var app: Context
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var attachedContainer: android.view.ViewGroup? = null
    private var attachedLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var lastAttachedWidth = 0
    private var lastAttachedHeight = 0

    /**
     * 设备当前的音频直通能力，与 [AppRenderersFactory] 交给 AudioSink 的是同一份判定。
     *
     * 用来在「视频详情」里说明当前音轨是位流直通还是解码成 PCM 输出 ——
     * 接没接功放、Atmos 能不能完整传出去，看这一行就知道。
     */
    private var audioCapabilities: androidx.media3.exoplayer.audio.AudioCapabilities? = null

    private var lastHistoryTick = 0L
    private var retriedNetwork = false
    private var localStrategy = 0

    /**
     * 本次起播的首帧是否已经渲染。转圈的关闭时机必须以「首帧真的画出来了」为准，
     * 不能用 STATE_READY：READY 只表示缓冲够了，和首帧渲染的先后在不同视频/网络下会翻转。
     * READY 先到、首帧后到时，若用 READY 关转圈，就会出现「转圈没了但画面还没出」的空窗。
     */
    private var firstFrameShown = false
    /** 本次起播的代号：首帧/READY 回调回来时核对，避免上一集的迟到回调误关本次转圈 */
    private var startSeq = 0

    /** 进入画中画后 Activity 仍在前台，不能按「离开播放页」处理 */
    @Volatile
    var pictureInPicture = false
        private set

    /**
     * 播放页是否正显示在屏幕上（由 PlayerScreen 的 DisposableEffect 维护）。
     *
     * 系统自动进小窗只应在「看着播放页」时生效。原来只要起播过一次就把
     * autoEnter 写死为 true，用户退回列表/暂停后按 Home 仍会被拉成小窗，
     * 小窗里显示的甚至是列表页 —— 看起来就像在软件内进了画中画。
     */
    @Volatile
    var playerScreenVisible = false

    /** 画中画状态流：界面需要据此收起控制条，避免小窗里挤满按钮 */
    private val _pictureInPicture = MutableStateFlow(false)
    val pictureInPictureState: StateFlow<Boolean> = _pictureInPicture.asStateFlow()

    fun setPictureInPicture(active: Boolean) {
        pictureInPicture = active
        _pictureInPicture.value = active
        // 进出小窗窗口尺寸都会变，比例框跟着重测，避免退回全屏后画面停在旧尺寸
        requestVideoRelayout()
        if (!active) return
        // Media3 的 TextureView 在 PiP 尺寸变化时会自行重建 Surface，无需像 VLC 那样
        // 手动 updateVideoSurfaces；但暂停态小窗 Surface 重建后可能没有可显示的帧，
        // 短 seek 到当前位置补一帧。只在「进入」小窗时做，退出时不能动播放进度。
        scope.launch {
            delay(120L)
            val p = player ?: return@launch
            if (!p.isPlaying && p.mediaItemCount > 0) {
                val target = p.currentPosition.coerceAtLeast(0L)
                runCatching { p.seekTo(target + 1L) }
            }
        }
    }

    // 兜底异常处理器：persistQueue 这类写盘任务抛异常（磁盘满等）不能直接崩进程
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "播放器后台任务异常", t) },
    )

    private var serverRepo: ServerRepo? = null
    private var historyRepo: HistoryRepo? = null
    private var localRepo: LocalVideoRepo? = null
    private var progressRepo: PlaybackProgressRepo? = null
    private var queueRepo: QueueRepo? = null
    private var settingsRepo: SettingsRepo? = null

    @Volatile
    var settings: AppSettings = AppSettings()
        private set

    private val _queue = MutableStateFlow<List<VideoItem>>(emptyList())
    val queue: StateFlow<List<VideoItem>> = _queue.asStateFlow()

    /** 递增计数：界面观察到变化就跳到播放页（用于「用其它应用打开」） */
    private val _openPlayerSignal = MutableStateFlow(0)
    val openPlayerSignal: StateFlow<Int> = _openPlayerSignal.asStateFlow()

    fun requestOpenPlayer() {
        _openPlayerSignal.value += 1
    }

    /**
     * 打开播放页后立刻消费掉信号。否则这个 StateFlow 的旧值会在
     * AppRoot 重组（退出软件再进入、切任务返回）时重放，导致冷启动自动跳回播放页。
     */
    fun consumeOpenPlayer() {
        if (_openPlayerSignal.value != 0) _openPlayerSignal.value = 0
    }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /**
     * 播放页里临时改的播放顺序，只服务当前这个队列。
     * 设置页改的是全局默认值（[AppSettings.playMode]），换一批视频重新 setQueue 时这里自动清空，
     * 不会把「这次想单集循环」带到下一个文件夹。
     */
    private var sessionPlayMode: Int? = null

    private fun effectivePlayMode(): Int = sessionPlayMode ?: settings.playMode

    /** 播放页 / 播放列表面板里的「仅当前列表」播放顺序。 */
    fun setSessionPlayMode(mode: Int) {
        sessionPlayMode = mode
        _state.update { it.copy(playMode = mode) }
        Notice.success("已设为${com.yaoahos.mypureplayer.data.PlayModes.label(mode)}（仅当前列表）")
    }

    /**
     * 播放进度独立成流：Media3 的 position 每 200ms 刷新一次，若和 _state 放一起会让
     * 整个播放页（标题、按钮、字幕列表……）每秒重组 3~4 次。现在只有进度条与
     * 时间文本订阅这个流，其余 UI 只在真正状态变化时重组。
     */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private fun setPosition(ms: Long) {
        if (_positionMs.value != ms) _positionMs.value = ms
        // 注意不要把进度镜像进 _state：_state 的 positionMs 只在换片/seek 完成等
        // 离散事件写入。这里每 200ms 刷一次，镜像过去会让整个播放页每秒重组 5 次，
        // 独立进度流就白拆了（进度条与时间文本只订阅 _positionMs）。
    }

    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrack>> = _subtitleTracks.asStateFlow()

    private val _subtitleFiles = MutableStateFlow<List<SubtitleFile>>(emptyList())
    val subtitleFiles: StateFlow<List<SubtitleFile>> = _subtitleFiles.asStateFlow()

    /** DLNA/UPnP 渲染器（独立 SSDP 发现，与播放内核无关） */
    private val _dlnaDevices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val dlnaDevices: StateFlow<List<DlnaDevice>> = _dlnaDevices.asStateFlow()
    private var dlnaDiscoveryJob: Job? = null
    private var activeDlnaDevice: DlnaDevice? = null
    /** 投屏期间是否持有低延迟 Wi-Fi 锁（避免重复 acquire/release 打乱引用计数） */
    private var castWifiHeld = false
    /** 投屏期间的 CPU 唤醒锁：保证本地流服务一直在供数据 */
    private var castWakeLock: android.os.PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val scaleModes = intArrayOf(
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )
    private val scaleLabels = arrayOf("适应屏幕", "铺满裁切", "强制 16:9")

    fun scaleLabel(index: Int): String = scaleLabels.getOrElse(index) { scaleLabels[0] }

    fun init(
        context: Context,
        servers: ServerRepo,
        history: HistoryRepo,
        queueStore: QueueRepo,
        settingsRepo: SettingsRepo,
        progress: PlaybackProgressRepo,
        local: LocalVideoRepo,
    ) {
        if (::app.isInitialized) return
        app = context.applicationContext
        serverRepo = servers
        historyRepo = history
        localRepo = local
        progressRepo = progress
        queueRepo = queueStore
        this.settingsRepo = settingsRepo

        // 媒体会话服务在进入播放页时启动（见 attach），这里不做：应用冷启动就拉起
        // 前台服务既无必要，也会让「还没播放就出现媒体通知」。

        scope.launch {
            settingsRepo.settings.collect {
                settings = it
                if (sessionPlayMode == null) _state.update { s -> s.copy(playMode = it.playMode) }
            }
        }
        scope.launch {
            val restored = queueStore.load()
            if (_queue.value.isEmpty()) {
                // 上次退出前删掉的本地视频可能还留在队列里（旧版本删除不清队列、或用户在
                // 系统相册里删了文件）：恢复时按「文件还在不在」过滤一次，别留给用户
                // 一个播到就报错的播放列表。content:// 条目没有 localPath 的保持原样。
                val alive = withContext(Dispatchers.IO) {
                    restored.filterNot { it.localPath?.let { p -> !File(p).exists() } == true }
                }
                _queue.value = alive
                if (alive.size != restored.size) queueStore.save(alive)
            }
        }
    }

    // ---------------------------------------------------------------- 播放器构建

    /**
     * 懒加载 Media3 ExoPlayer。用 ass-media 的 buildWithAssSupport 构建，
     * 使 ASS/SSA 复杂图形字幕（样式/动画/卡拉OK/内嵌字体）由 libass 完整渲染。
     *
     * Media3 是纯 Java/Kotlin 实现，不存在 libVLC 那种「加载 .so 失败即 System.exit」的坑，
     * 因此这里不需要 native 探测；唯一可能失败的是 libass 原生库缺失，用 runCatching 兜住。
     */
    /**
     * 供媒体会话服务取播放器实例。
     *
     * 播放器归本单例所有（跨页面复用，投屏与字幕都挂在上面），会话只借用、不持有所有权。
     * 应用尚未初始化时返回 null，让服务自行退出，而不是拿空 context 去建播放器。
     */
    fun playerForSession(): ExoPlayer? {
        if (!::app.isInitialized) return null
        return ensurePlayer()
    }

    @Synchronized
    private fun ensurePlayer(): ExoPlayer? {
        player?.let { return it }
        // 与 AppRenderersFactory 用同一份判定，保证信息面板里的「直通/解码」与真实输出一致
        audioCapabilities = runCatching {
            androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(app)
        }.getOrNull()
        val created = runCatching {
            // 先建 PlayerView，把它的 SubtitleView 交给 libass 作为 OVERLAY 的绘制宿主。
            // 从 view_player.xml 充气：surface_type=surface_view（HDR 必需，原因见该布局注释）。
            // 配套改动：播放页转场已从水平滑动改成淡入淡出，否则 SurfaceView 不跟随位移。
            if (playerView == null) {
                playerView = (android.view.LayoutInflater.from(app)
                    .inflate(R.layout.view_player, null) as PlayerView).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    resizeMode = scaleModes[_state.value.scaleMode.coerceIn(0, scaleModes.lastIndex)]
                }
            }
            val subtitleView = playerView?.getSubtitleView()
            // 字幕默认样式是「白字 + 不透明黑底」，在画面上就是一条突兀的黑带，
            // 而且会盖住字幕里自带的描边/阴影效果。这里改成透明底 + 细描边：
            // 亮画面靠描边保证可读，暗画面不会被黑块割断。
            subtitleView?.setStyle(
                androidx.media3.ui.CaptionStyleCompat(
                    /* foregroundColor = */ android.graphics.Color.WHITE,
                    /* backgroundColor = */ android.graphics.Color.TRANSPARENT,
                    /* windowColor = */ android.graphics.Color.TRANSPARENT,
                    /* edgeType = */ androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    /* edgeColor = */ android.graphics.Color.BLACK,
                    /* typeface = */ null,
                )
            )
            subtitleView?.setApplyEmbeddedStyles(true)
            subtitleView?.setApplyEmbeddedFontSizes(true)
            // smb:// 走直连数据源（复用 SMB 句柄、seek 不重新握手），其余走默认源。
            // 注意：buildWithAssSupport 内部会新建 DefaultMediaSourceFactory 并覆盖
            // Builder 上设置的 MediaSourceFactory，所以自定义工厂必须作为参数传入，
            // 不能只 setMediaSourceFactory —— 否则直连不生效。
            ExoPlayer.Builder(app)
                .setLoadControl(
                    // 内网高带宽场景的缓冲策略：起播、seek 恢复都要快，不需要弱网那套大缓冲。
                    // - bufferForPlayback=500ms / AfterRebuffer=750ms：seek 后只要 0.75 秒数据就恢复播，
                    //   配合 1MB 预读与 SMB 吞吐（实测 17-19MB/s），快进/快退不再转几秒圈；
                    // - minBuffer=5s：50Mbps 视频约 31MB，够抗抖动又不会过度预读抢带宽；
                    // - maxBuffer=30s：上限兜底，避免长时间挂着吃内存。
                    // 若以后要兼顾弱网，可按网络探测结果动态切一组更大阈值。
                    androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            /* minBufferMs = */ 5_000,
                            /* maxBufferMs = */ 30_000,
                            /* bufferForPlaybackMs = */ 500,
                            /* bufferForPlaybackAfterRebufferMs = */ 750,
                        )
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build(),
                )
                .buildWithAssSupport(
                    context = app,
                    renderType = AssRenderType.OVERLAY_OPEN_GL,
                    // 系统解码器不够用时才走 FFmpeg（DTS / DTS-HD / TrueHD）：
                    // EXTENSION_RENDERER_MODE_ON 把扩展排在所有系统渲染器之后，
                    // AAC/AC3 这些设备自带的编码仍然走硬解，功耗与延迟不变。
                    renderersFactory = AppRenderersFactory(app)
                        .setExtensionRendererMode(
                            androidx.media3.exoplayer.DefaultRenderersFactory
                                .EXTENSION_RENDERER_MODE_ON,
                        ),
                    subtitleView = subtitleView,
                    dataSourceFactory = SmbRoutingDataSource.Factory(app),
                )
                .apply {
                    playWhenReady = true
                    addListener(playerListener)
                }
        }.getOrElse { e ->
            Log.e(TAG, "创建 Media3 播放器失败", e)
            _state.update {
                it.copy(engineMissing = true, error = "播放内核初始化失败，请重启应用后重试。")
            }
            return null
        }
        player = created
        playerView?.setPlayer(created)
        startPositionPoller()
        return created
    }

    // ---------------------------------------------------------------- 视图挂载

    /**
     * Media3 的比例框在窗口尺寸变化后偶发只按旧尺寸测量：画面缩在左上，
     * 右侧和底部留黑。切一次比例能强制重测，但过一会又被宽高比回调拉回去。
     * 这里在容器尺寸变化与视频尺寸变化时主动重测，保证内容框始终居中铺满可用区域。
     */
    private fun requestVideoRelayout() {
        val view = playerView ?: return
        view.post {
            val frame = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
            (frame?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.gravity == Gravity.NO_GRAVITY) {
                    lp.gravity = Gravity.CENTER
                    frame.layoutParams = lp
                }
            }
            frame?.requestLayout()
            view.requestLayout()
            view.videoSurfaceView?.requestLayout()
        }
    }

    /**
     * 把播放器视图挂到 Compose AndroidView 提供的容器上。
     * 签名从旧版 VLCVideoLayout 改为通用 ViewGroup；PlayerScreen 已同步适配。
     * 画面宿主是 SurfaceView（HDR 需要独立硬件层）：它在画中画窗口里是系统推荐做法，
     * 但页面级的位移动画不会带动它 —— 所以播放页的转场是淡入淡出，不是水平滑动。
     */
    fun attach(container: android.view.ViewGroup) {
        if (ensurePlayer() == null) return
        // 有了播放器就拉起媒体会话服务：通知、锁屏控制、蓝牙耳机与车机按钮都由它托管。
        // Media3 的会话在开始播放时才把这个服务提到前台，这里只是把会话建好。
        PlaybackSessionService.start(app)
        if (attachedContainer === container && playerView?.parent === container &&
            playerView?.visibility == android.view.View.VISIBLE
        ) {
            // 容器还是同一个，但窗口尺寸可能转过：主动重测一次，防止画面缩在左上。
            requestVideoRelayout()
            return
        }
        // 从旧容器摘下（同样走清屏路径：横屏 → 竖屏时旧容器可能正随窗口转场）
        unmountPlayerView()
        val view = playerView ?: return
        view.visibility = android.view.View.VISIBLE
        runCatching { container.addView(view, android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT) }
        attachedContainer = container
        lastAttachedWidth = container.width
        lastAttachedHeight = container.height
        attachedLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val width = container.width
            val height = container.height
            if (width != lastAttachedWidth || height != lastAttachedHeight) {
                lastAttachedWidth = width
                lastAttachedHeight = height
                requestVideoRelayout()
            }
        }
        runCatching { container.viewTreeObserver.addOnGlobalLayoutListener(attachedLayoutListener) }
        requestVideoRelayout()
        applyScale()
    }

    /**
     * 卸载播放画面。
     *
     * SurfaceView 是独立硬件图层：只把 PlayerView 从容器里 removeView，系统合成器
     * 可能还留着最后一帧（横屏关闭 → 转竖屏 → 回桌面时尤其明显）。先把 surface
     * 涂黑、再把整张 PlayerView 隐藏，摘除后这一层就不再贡献任何像素。
     *
     * 不用 TextureView 替代：那会丢掉 HDR 传递与色彩元数据。
     */
    private fun unmountPlayerView() {
        attachedLayoutListener?.let { listener ->
            runCatching { attachedContainer?.viewTreeObserver?.removeOnGlobalLayoutListener(listener) }
        }
        attachedLayoutListener = null
        lastAttachedWidth = 0
        lastAttachedHeight = 0
        blankVideoSurface()
        playerView?.let { view ->
            view.visibility = android.view.View.INVISIBLE
            runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view) }
        }
        attachedContainer = null
    }

    private fun blankVideoSurface() {
        val surfaceView = playerView?.videoSurfaceView as? SurfaceView ?: return
        runCatching {
            val holder = surfaceView.holder
            if (holder.surface.isValid) {
                holder.lockCanvas()?.let { canvas ->
                    try {
                        canvas.drawColor(android.graphics.Color.BLACK)
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            }
        }
    }

    fun detach() {
        // 只从当前 UI 容器摘下视图，不释放播放器（它是跨页面复用的全局单例）。
        unmountPlayerView()
        // 通知不在这里收：Media3 的会话随播放状态走 —— 还在播就保留（可点回画面），
        // 停止播放后自动收起。旧实现在这里手动 cancel，反而会在播放中把媒体栏撤掉。
    }

    private fun applyScale() {
        val mode = scaleModes[_state.value.scaleMode.coerceIn(0, scaleModes.lastIndex)]
        playerView?.resizeMode = mode
        requestVideoRelayout()
    }

    /** 延迟显示的缓冲提示：短促的 seek/重缓冲不画转圈，避免每次快进都闪一下 */
    private var bufferingHintJob: Job? = null

    private fun scheduleBufferingHint() {
        bufferingHintJob?.cancel()
        bufferingHintJob = scope.launch {
            delay(BUFFERING_UI_DELAY_MS)
            _state.update { if (it.error == null && !it.preparing) it.copy(buffering = true) else it }
        }
    }

    private fun clearBufferingHint() {
        bufferingHintJob?.cancel()
        bufferingHintJob = null
    }

    private var positionPoller: Job? = null

    /** 每 200ms 把播放器进度同步到独立 flow；比整秒细腻，进度条不会一格一格跳 */
    private fun startPositionPoller() {
        positionPoller?.cancel()
        positionPoller = scope.launch {
            while (true) {
                val p = player
                if (p == null || p.mediaItemCount == 0) {
                    // 空闲时降到 1s 一跳：单例存活期间这个协程不会退出，
                    // 没有媒体可同步时没必要每 200ms 唤醒一次主线程
                    delay(1000L)
                    continue
                }
                delay(200L)
                val pos = p.currentPosition
                if (pos >= 0L && _positionMs.value != pos) _positionMs.value = pos
                val now = System.currentTimeMillis()
                if (now - lastHistoryTick > HISTORY_TICK_MS) {
                    lastHistoryTick = now
                    recordHistory(force = false)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 队列操作

    /**
     * 播放队列中的视频。
     *
     * [startPositionMs] 为空时自动从观看历史续播——本地页、网络页、主界面等入口都只传
     * 队列与下标，若在这里不补读进度，每次点开同一视频都会从第 0 秒开始，等于「记忆播放
     * 进度失效」。历史页点「从头播放」会显式传 0L，必须原样尊重，不能被自动续播覆盖。
     */
    fun play(items: List<VideoItem>, startIndex: Int, startPositionMs: Long? = null) {
        if (items.isEmpty()) return
        setQueue(items)
        val index = startIndex.coerceIn(0, items.lastIndex)
        val target = items[index]
        val start = startPositionMs ?: resumePositionOf(target)
        requestStart(index, start)
    }

    fun playSingle(item: VideoItem, startPositionMs: Long? = null) {
        play(listOf(item), 0, startPositionMs)
    }

    /**
     * 用户主动点开一条视频的入口：先确认文件还在，再起播并打开播放页。
     *
     * 历史记录与播放列表存的是「加入那一刻的快照」。文件被删除、改名，或所在的共享
     * 目录已从网络位置里移除之后，直接进播放页只会得到一屏「播放失败」——
     * 用户还得先退出来才能换一条。这里就地拦下，顶部提示一句，正在播的内容不受影响。
     *
     * 只拦「确定不存在」：服务器没响应、网络超时这类不确定的失败照常进播放页，
     * 那里有重试与更完整的说明，拦下来反而会让人以为视频坏了。
     *
     * [onStart] 默认走 [requestOpenPlayer]（界面层统一监听并跳转），需要自己控制跳转时传进来。
     */
    fun playChecked(
        items: List<VideoItem>,
        startIndex: Int,
        startPositionMs: Long? = null,
        onStart: () -> Unit = { requestOpenPlayer() },
    ) {
        if (items.isEmpty()) return
        val index = startIndex.coerceIn(0, items.lastIndex)
        val target = items[index]
        scope.launch {
            val missing = missingReason(target)
            if (missing != null) {
                Notice.error(missing)
                return@launch
            }
            setQueue(items)
            requestStart(index, startPositionMs ?: resumePositionOf(target))
            onStart()
        }
    }

    /**
     * 这条视频现在还能不能播；能播返回 null，否则返回一句给用户看的短提示。
     *
     * 本地文件直接看能不能读；网络条目先看当前配置是否仍覆盖这个位置（不需要联网），
     * 再问一次服务器文件还在不在。
     */
    private suspend fun missingReason(item: VideoItem): String? = when (item.source) {
        MediaSource.LOCAL -> withContext(Dispatchers.IO) {
            if (localItemExists(item)) null else "视频文件不存在"
        }

        MediaSource.SMB -> smbMissingReason(item)

        MediaSource.STREAM -> null
    }

    private fun localItemExists(item: VideoItem): Boolean {
        val live = localEntryFor(item)
        live.localPath?.takeIf { it.isNotBlank() }?.let { return File(it).canRead() }
        val uri = runCatching { Uri.parse(live.uri) }.getOrNull() ?: return false
        return when (uri.scheme) {
            "content" -> runCatching {
                app.contentResolver.openFileDescriptor(uri, "r")?.use { }
            }.getOrNull() != null

            "file" -> File(uri.path.orEmpty()).canRead()
            else -> false
        }
    }

    private suspend fun smbMissingReason(item: VideoItem): String? {
        // 共享目录已经从网络位置里删掉 / 改成别的目录：不必联网，直接判定不存在
        val server = serverRepo?.forSmbItem(item.serverId, item.uri) ?: return "网络位置已移除"
        // 探测放到后台并只等一小会儿：NAS 休眠时连 TCP 都要 20 秒，
        // 不能让用户点完以后界面毫无反应，超时就当「不知道」，交给播放页去试。
        val probe = scope.async(Dispatchers.IO) { runCatching { SmbClient.probe(server, item.uri) } }
        val result = withTimeoutOrNull(SMB_PROBE_WAIT_MS) { probe.await() } ?: return null
        val cause = result.exceptionOrNull() ?: return null
        return if (SmbErrors.isNotFound(cause)) "视频文件不存在" else null
    }

    /** 读取观看历史里的续播点；看到结尾（剩不到 5 秒）就当已看完，下次从头播。 */
    private fun resumePositionOf(item: VideoItem): Long {
        // 设置里关掉「记忆播放进度」时，历史照记（历史页要显示看过），但下次必须从头播
        if (!settings.resumePlayback) return 0L
        // 保险箱里的视频有自己的进度表：历史记录里根本没有它，去那边查必然是 0
        if (item.id.startsWith(PlaybackProgressRepo.VAULT_PREFIX)) {
            val repo = progressRepo ?: return 0L
            val position = repo.positionOf(item.id)
            if (position <= 0L) return 0L
            val total = item.durationMs.takeIf { it > 0L } ?: repo.durationOf(item.id)
            return if (total > 0L && position >= total - 5_000L) 0L else position
        }
        val repo = historyRepo ?: return 0L
        val position = repo.resumePosition(item.id, item.source)
        if (position <= 0L) return 0L
        val total = item.durationMs.takeIf { it > 0L }
            ?: repo.historyDuration(item.id, item.source)
        return if (total > 0L && position >= total - 5_000L) 0L else position
    }

    fun setQueue(items: List<VideoItem>) {
        // 换新列表（例如从另一个文件夹打开视频）时，播放页里临时选的顺序不再适用
        sessionPlayMode = null
        _state.update { it.copy(playMode = effectivePlayMode()) }
        // 去重兜底：队列 id 直接当播放列表 Lazy 的 key，重复值会抛
        // "Key ... was already used" 把播放页带走，而且队列会落盘、重启后照样崩。
        // 正常入口（addToQueue）已经保证唯一，这里防的是外部数据（备份恢复、旧 json）。
        _queue.value = if (items.size <= 1) items else items.distinctBy { it.id }
        persistQueue()
    }

    fun addToQueue(items: List<VideoItem>) {
        if (items.isEmpty()) return
        val existing = _queue.value.map { it.id }.toHashSet()
        val merged = _queue.value + items.filterNot { it.id in existing }
        _queue.value = merged
        _state.update { it.copy(queueSize = merged.size) }
        persistQueue()
    }

    fun removeAt(index: Int) {
        val current = _queue.value
        if (index !in current.indices) return
        val next = current.toMutableList().apply { removeAt(index) }
        val playingIndex = _state.value.queueIndex
        if (next.isEmpty()) {
            stopInternal()
            _queue.value = emptyList()
            persistQueue()
            return
        }
        _queue.value = next
        persistQueue()
        when {
            index < playingIndex -> _state.update { it.copy(queueIndex = playingIndex - 1, queueSize = next.size) }
            index == playingIndex -> requestStart(index.coerceAtMost(next.lastIndex))
            else -> _state.update { it.copy(queueSize = next.size) }
        }
    }

    fun clearQueue() {
        stopInternal()
        _queue.value = emptyList()
        sessionPlayMode = null
        _state.value = PlayerUiState(playMode = effectivePlayMode())
        persistQueue()
    }

    /** 文件被删除 / 移入回收站后，把对应条目从队列里摘掉，避免播到坏地址 */
    fun dropDeletedFromQueue(path: String) {
        val index = _queue.value.indexOfFirst { it.localPath == path }
        if (index >= 0) removeAt(index)
    }

    /**
     * 一批视频离开本地库（移入回收站 / 加密进保险箱 / 被别的应用删掉）后的批量摘除。
     *
     * 按 id 与真实路径双条件匹配：`id` 是 content:// URI，删除后这一条最可靠；
     * `localPath` 兜住从历史/播放列表里拼出来的旧条目（那些条目的 id 可能来自旧版本）。
     * 逐条 [removeAt] 不行 —— 每删一条都会重排一次索引并重新起播，删 20 个会跳 20 次。
     */
    fun dropDeletedFromQueue(items: Collection<VideoItem>) {
        if (items.isEmpty()) return
        val ids = items.mapTo(HashSet()) { it.id }
        val paths = items.mapNotNullTo(HashSet()) { it.localPath }
        dropQueueWhere { it.id in ids || (it.localPath != null && it.localPath in paths) }
    }

    private fun dropQueueWhere(predicate: (VideoItem) -> Boolean) {
        val current = _queue.value
        if (current.isEmpty() || current.none(predicate)) return
        val playingIndex = _state.value.queueIndex
        val playing = current.getOrNull(playingIndex)
        // 当前项之前被删掉几个，决定播放位置要往前挪多少
        val removedBefore = if (playingIndex > 0) current.take(playingIndex).count(predicate) else 0
        val kept = current.filterNot(predicate)
        if (kept.isEmpty()) {
            // 先清队列再停：stopInternal 里的 recordHistory 会检查"当前项还在不在队列里"，
            // 顺序反了的话，停下这一刻会把刚删掉的那条进度又写回历史。
            _queue.value = emptyList()
            stopInternal()
            // 当前项也一起摘掉：它指向的文件已经没了，留着会让媒体通知/播放页
            // 显示一条播不了的条目
            _state.update { it.copy(item = null, queueIndex = -1, queueSize = 0) }
            persistQueue()
            return
        }
        _queue.value = kept
        persistQueue()
        when {
            playingIndex < 0 -> _state.update { it.copy(queueSize = kept.size) }
            // 正在播的这一集被删了：只有**此刻真的在播**（含后台音频）才让下一集补上来，
            // 手感与"删掉正在看的那一集"一致。
            //
            // 这里以前是无条件 requestStart：从文件页把一个**以前看过**的视频移入回收站/保险箱时，
            // 队列里恰好还记着它当"当前项"（退出播放页不会清 item），于是队列里的下一集
            // 会在后台凭空开始播放，而且播完还写进历史 —— 用户看到的现象是
            // "我删了个视频，历史里多出一条不相干的新记录，扬声器还自己响了"。
            playing != null && predicate(playing) ->
                if (_state.value.playing) {
                    requestStart((playingIndex - removedBefore).coerceIn(0, kept.lastIndex))
                } else {
                    stopInternal()
                    // 当前项的文件已经没了，界面不该再挂着一个播不了的条目
                    _state.update { it.copy(item = null, queueIndex = -1, queueSize = kept.size) }
                }

            else -> _state.update {
                it.copy(queueIndex = playingIndex - removedBefore, queueSize = kept.size)
            }
        }
    }

    /**
     * 从播放列表 / 队列点选某一集。
     *
     * 与 [next]/[previous] 的语义不同：那是顺序换集（从头播），而用户主动点某一集，
     * 期望和从文件页点开一样接着上次的进度看。所以这里也走历史续播。
     * 错误页「重试」不经过这里（它走 onPlayerError 里带当前 position 的 startAt）。
     */
    fun playAt(index: Int) {
        val items = _queue.value
        if (index !in items.indices) return
        requestStart(index, resumePositionOf(items[index]))
    }

    /**
     * 按给定顺序重排队列（播放列表页的「排列方式」）。
     * 当前正在播的条目跟着它的新位置走，播放本身不中断。
     */
    fun reorderQueue(items: List<VideoItem>) {
        if (items.isEmpty()) return
        val currentId = _state.value.item?.id
        _queue.value = items
        val index = if (currentId != null) items.indexOfFirst { it.id == currentId } else -1
        _state.update {
            it.copy(queueIndex = if (index >= 0) index else it.queueIndex, queueSize = items.size)
        }
        persistQueue()
    }

    fun next() {
        val index = _state.value.queueIndex
        if (index >= 0 && index < _queue.value.lastIndex) requestStart(index + 1)
        else stopInternal()
    }

    fun previous() {
        val index = _state.value.queueIndex
        if (index > 0) requestStart(index - 1) else seekTo(0)
    }

    private fun persistQueue() {
        val repo = queueRepo ?: return
        val items = _queue.value
        // 写盘失败不能完全静默：内存队列已经变了、磁盘没变，杀进程回来就是旧内容。
        // 不弹提示（队列变动很频繁，浮层会盖住画面），但必须留日志，
        // 否则「加了 20 个视频，第二天变回旧的」根本无从查起。
        scope.launch {
            runCatching { repo.save(items) }.onFailure {
                Log.w(TAG, "播放队列保存失败（${items.size} 项）", it)
            }
        }
    }

    // ---------------------------------------------------------------- 播放内核

    /** 请求播放队列中的第 [index] 个视频。换片第一时间清掉上一集的字幕状态。 */
    private fun requestStart(index: Int, startPositionMs: Long = 0L) {
        val items = _queue.value
        if (index !in items.indices) return
        val target = items[index]
        if (_state.value.item?.id != target.id) {
            // 投屏时电视还在播上一集；换片若不先断开，就会「电视播旧的、手机播新的」。
            // 这里先退回本机播放，新的一集要再投需要用户主动选设备。
            if (activeDlnaDevice != null) stopCasting()
            forcedSubtitle = null
            forcedSubtitleName = null
            _subtitleFiles.value = emptyList()
            _subtitleTracks.value = emptyList()
            _audioTracks.value = emptyList()
            lastAudioTracks = emptyList()
        }
        startAt(index, startPositionMs)
    }

    private fun startAt(
        index: Int,
        startMs: Long = 0L,
        keepSubtitle: Boolean = false,
        soft: Boolean = false,
        /** 失败重试时传 false：否则这里一清计数，onPlayerError 的「只重试一次」永远生效不了 */
        resetRetry: Boolean = true,
    ) {
        val items = _queue.value
        if (index !in items.indices) return
        val item = items[index]
        val p = ensurePlayer() ?: return

        val sameItem = _state.value.item?.id == item.id
        if (!keepSubtitle && !sameItem) {
            forcedSubtitle = null
            forcedSubtitleName = null
        }

        recordHistory(force = true)

        // 播放网络视频时先让封面抽帧停手：两者共用一条 SMB 通道，
        // 边播边抽会让首帧、快进都排在几十张封面后面（就是「打开慢、快进要等」）。
        if (item.source == MediaSource.SMB) Thumbnails.pauseRemoteThumbs()
        else Thumbnails.resumeRemoteThumbs()
        // 低延迟 Wi-Fi：SMB 的打开/查询都是往返，读吞吐也受往返延迟约束，
        // 2.4G 省电带来的 20ms 往返会把码率打到 1MB/s 上下（就是「快进要等」的原因）。
        holdWifi(item.source == MediaSource.SMB)

        // SMB：只认「当前配置里仍然覆盖这个位置」的服务器（host + 共享名都对得上）。
        // 历史 / 播放列表存的是加入时的快照，共享被移除、默认目录被改、或 IP 分给了
        // 另一台机器之后，照旧地址去连只会打开到别处，必须按文件不存在处理。
        val server = serverFor(item)
        val mediaItem = buildMediaItem(item, server)
        if (mediaItem == null) {
            // 走到这里说明预检放行了、构造却失败（例如探测之后文件才被删掉）。
            // 提示只说要紧的一句：播放页上就是一块黑底，字数越多越难读。
            _state.update {
                it.copy(
                    item = item,
                    queueIndex = index,
                    queueSize = items.size,
                    playing = false,
                    buffering = false,
                    preparing = false,
                    error = when {
                        item.source == MediaSource.SMB && server == null -> "网络位置已移除"
                        item.source == MediaSource.SMB -> "无法打开网络文件"
                        item.source == MediaSource.LOCAL -> "视频文件不存在"
                        else -> "无法打开该链接"
                    },
                )
            }
            return
        }

        // soft = 只为换字幕重开：不显示缓冲圈、不清空播放状态，避免画面“刷新”一下。
        val prev = _state.value
        _state.value = PlayerUiState(
            item = item,
            queueIndex = index,
            queueSize = items.size,
            playMode = effectivePlayMode(),
            durationMs = prev.durationMs.takeIf { soft && sameItem } ?: seededDuration(item),
            buffering = !soft,
            // 起播准备中：覆盖「置起状态 → setMediaItem 的 IDLE 空档 → prepare → 首帧」整个等待期，
            // 不被 STATE_IDLE 清掉，保证任何等待都有加载提示。
            preparing = !soft,
            scaleMode = prev.scaleMode,
            playbackRate = if (sameItem) prev.playbackRate else 1f,
            hardwareDecode = settings.hardwareDecode,
            backgroundAudio = if (sameItem) prev.backgroundAudio else false,
            videoWidth = if (sameItem) prev.videoWidth else 0,
            videoHeight = if (sameItem) prev.videoHeight else 0,
            rendererName = prev.rendererName,
            playing = soft && sameItem && prev.playing,
            positionMs = if (soft && sameItem) prev.positionMs else 0L,
            autoSubtitleName = if (soft && sameItem) prev.autoSubtitleName else null,
            activeSubtitleId = if (soft && sameItem) prev.activeSubtitleId else -1,
            hasSubtitles = if (soft && sameItem) prev.hasSubtitles else false,
            subtitlesEnabled = if (soft && sameItem) prev.subtitlesEnabled else false,
        )
        _subtitleTracks.value = emptyList()
        if (!soft) {
            _subtitleFiles.value = emptyList()
            _audioTracks.value = emptyList()
            lastAudioTracks = emptyList()
        }

        if (resetRetry) {
            retriedNetwork = false
            localStrategy = 0
        }
        thumbCaptured = false
        pendingSeekMs = startMs
        // 新一轮起播：首帧尚未渲染，递增代号让上一集的迟到回调失效
        startSeq++
        firstFrameShown = false

        // 设置媒体并定位到起点
        if (startMs > 0L) p.setMediaItem(mediaItem, startMs) else p.setMediaItem(mediaItem)
        p.playWhenReady = true
        p.playbackParameters = androidx.media3.common.PlaybackParameters(
            _state.value.playbackRate.coerceIn(0.25f, 4f)
        )
        p.prepare()
        applyScale()
        playerView?.setPlayer(p)

        scope.launch { loadSubtitleCandidates(item, server) }
    }

    private var pendingSeekMs: Long = 0L

    /**
     * 构造 Media3 MediaItem。
     * - 本地：优先 file:// 真实路径，其次 content://，最后回环 HTTP。
     * - SMB：走 LocalStreamServer 的本地 HTTP Range 中转（Media3 无 SMB 数据源）。
     * - 串流：原 URL（http/hls/dash/rtsp 均由对应模块支持）。
     * 外挂字幕以 SubtitleConfiguration 随媒体一起交给 libass 渲染。
     */
    /**
     * 把一条「可能是旧快照」的本地条目，换成当前本地库里真实存在的那一条。
     *
     * 自身路径能读就不用换（绝大多数情况，零开销）；读不到才去库里按
     * 路径 / id / 文件名重新匹配。匹配不到就原样返回，由上层给出明确错误。
     */
    private fun localEntryFor(item: VideoItem): VideoItem {
        val selfReadable = item.localPath?.let { File(it).canRead() } == true
        if (selfReadable) return item
        return localRepo?.relocate(item) ?: item
    }

    /**
     * 这条条目现在还能用哪台服务器。
     *
     * 判断规则统一放在 [com.yaoahos.mypureplayer.data.ServerRepo.forSmbItem]：
     * 只有当前配置仍然覆盖这个位置（host 与共享名）才返回，否则一律当作文件不存在。
     */
    private fun serverFor(item: VideoItem): SmbServer? =
        if (item.source == MediaSource.SMB) serverRepo?.forSmbItem(item.serverId, item.uri) else null

    private fun buildMediaItem(item: VideoItem, server: SmbServer?): MediaItem? {
        return try {
            val contentUri: Uri
            val isNetwork: Boolean
            when (item.source) {
                MediaSource.SMB -> {
                    val srv = server ?: return null
                    // 直连：smb:// 交给 SmbRoutingDataSource，复用 SMB 句柄、seek 不重新握手。
                    // 不再绕 http://127.0.0.1 中转 —— 那里每层 Range 都新建 SMB 会话，
                    // 正是「打开慢、快进卡、偶发卡住」的根因。
                    val fullUrl = SmbClient.playableUrl(srv, item)
                    // MediaItem / MediaSession / 错误日志里都不该出现明文口令；
                    // 凭据留在进程内的注册表，DataSource 按无凭据 URL 取回即可。
                    val url = SmbClient.playableUrlWithoutCredentials(fullUrl)
                    SmbPlaybackRegistry.register(fullUrl, srv)
                    SmbPlaybackRegistry.register(url, srv)
                    contentUri = Uri.parse(url)
                    isNetwork = true
                }
                MediaSource.LOCAL -> {
                    // 保险箱视频：localPath 指向的是密文（`.bin`），绝对不能当普通文件
                    // 交给解码器。用 vault:// 交给 VaultDataSource 按块解密，播放器就能
                    // 边读边解、拖动秒响应，也不必先解密一整份到缓存。
                    // 这条分支必须在投屏判断之前：投屏是把 URL 推给电视，
                    // 电视那头没有密钥，推出去的只会是密文。
                    val vaultPath = item.localPath
                    if (item.id.startsWith("vault:") && vaultPath != null) {
                        contentUri = Uri.Builder().scheme("vault").path(vaultPath).build()
                        isNetwork = false
                        return buildMediaItemWithSubtitles(contentUri, item, false)
                    }
                    // 投屏时电视机访问不到本机回环/content，改走局域网 HTTP
                    if (_state.value.rendererName.isNotBlank()) {
                        LocalStreamServer.lanUrlFor(app, item)?.let { lanUrl ->
                            return buildMediaItemWithSubtitles(Uri.parse(lanUrl), item, true)
                        }
                    }
                    // 播放列表 / 历史里的记录是加入时的快照：content:// 行号在重新扫描、
                    // 文件移动、或从其它设备恢复备份后会失效（表现就是「地址没变却打不开」）。
                    // 这里先换成当前本地库里真实存在的条目，再决定用哪种地址去播。
                    val live = localEntryFor(item)
                    val livePath = live.localPath
                    contentUri = when {
                        livePath != null && File(livePath).canRead() -> Uri.fromFile(File(livePath))
                        live.uri.startsWith("file://") || live.uri.startsWith("content://") ->
                            Uri.parse(live.uri)
                        localStrategy == 0 -> Uri.parse(LocalStreamServer.urlFor(app, live))
                        else -> Uri.parse(live.uri)
                    }
                    isNetwork = contentUri.scheme == "http" || contentUri.scheme == "https"
                }
                MediaSource.STREAM -> {
                    contentUri = Uri.parse(item.uri)
                    isNetwork = true
                }
            }
            buildMediaItemWithSubtitles(contentUri, item, isNetwork)
        } catch (t: Throwable) {
            Log.w(TAG, "buildMediaItem failed: ${item.uri}", t)
            null
        }
    }

    private fun buildMediaItemWithSubtitles(uri: Uri, item: VideoItem, isNetwork: Boolean): MediaItem {
        val builder = MediaItem.Builder().setUri(uri).setMediaMetadata(mediaMetadataFor(item))
        val subs = mutableListOf<MediaItem.SubtitleConfiguration>()

        // 1) 本次会话强制外挂的字幕（用户在面板里选过）
        forcedSubtitle?.let { target ->
            subs += subtitleConfiguration(target, forcedSubtitleName, EXTERNAL_SUBTITLE_ID_BASE)
        }
        // 2) 自动加载的同名本地字幕
        if (forcedSubtitle == null && item.source == MediaSource.LOCAL && settings.autoLoadSubtitle) {
            subtitleChoiceFor(item)?.let { choice ->
                subs += subtitleConfiguration(choice.target, choice.name, EXTERNAL_SUBTITLE_ID_BASE)
            }
        }
        if (subs.isNotEmpty()) builder.setSubtitleConfigurations(subs)
        return builder.build()
    }

    /**
     * 通知栏 / 锁屏 / 系统媒体卡片用的元数据。
     *
     * Media3 的会话从 MediaItem 的 metadata 取标题与封面：缺了就只有一行文件名，
     * 看起来像没有内容在播。封面只读缩略图缓存 —— 通知要得急，不能为了封面
     * 去读一遍正在播放的网络视频（会和播放流抢带宽）。
     */
    private fun mediaMetadataFor(item: VideoItem): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(item.title.ifBlank { item.fileName })
            .setArtist(
                item.folderName.ifBlank {
                    if (item.source == MediaSource.SMB) "网络共享" else "本地视频"
                },
            )
            .setAlbumTitle("MyPurePlayer")
            .setIsPlayable(true)
            .setIsBrowsable(false)
        if (item.durationMs > 0L) builder.setDurationMs(item.durationMs)
        // 封面走预压好的字节缓存（Thumbnails 在 IO 线程顺手压好），主线程零磁盘零解码；
        // 拿不到就退回应用图标，绝不为封面去读盘或读网络
        Thumbnails.cachedArtwork(item)?.let { bytes ->
            runCatching { builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        }
        return builder.build()
    }

    /**
     * 中文字幕大量是 GBK / GB18030 编码，Media3 的 SubRip / ASS 解析器默认按 UTF-8 读，
     * 结果是整屏乱码。这里把本地字幕（含从 SMB 缓存下来的）转成 UTF-8 副本再交给播放器。
     * UTF-8 原文件直接复用，不额外拷贝；BOM 交给解析器自己处理。
     */
    private fun utf8SubtitlePath(path: String): String {
        val file = File(path)
        if (!file.isFile) return path
        val ext = Formats.extensionOf(file.name)
        if (ext !in setOf("srt", "ass", "ssa", "vtt")) return path
        // 先按「路径 + 修改时间 + 大小」算出缓存名，命中就直接返回 ——
        // 原来不管命中与否都先把整个字幕读进内存再算摘要，重看同一部片子
        // 每次起播都要白读几百 KB（SMB 缓存下来的字幕更慢）。
        val key = "$path|${file.lastModified()}|${file.length()}"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val cached = File(app.cacheDir, "subs/utf8_$digest.$ext")
        if (cached.exists() && cached.length() > 0L) return cached.absolutePath

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return path
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return path
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16BE)
            else -> runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrElse {
                // 严格 UTF-8 解不开才按 GB18030 试；它覆盖 GBK / GB2312
                runCatching { String(bytes, Charset.forName("GB18030")) }
                    .getOrElse { return path }
            }
        }
        return runCatching {
            cached.parentFile?.mkdirs()
            cached.writeBytes(text.toByteArray(Charsets.UTF_8))
            cached.absolutePath
        }.getOrDefault(path)
    }

    private fun subtitleConfiguration(path: String, name: String?, id: Int): MediaItem.SubtitleConfiguration {
        val remote = path.contains("://")
        val uri = if (remote) Uri.parse(path) else Uri.fromFile(File(utf8SubtitlePath(path)))
        val mime = when (Formats.extensionOf(name ?: path).lowercase()) {
            "ass" -> MimeTypes.TEXT_SSA
            "ssa" -> MimeTypes.TEXT_SSA
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            else -> MimeTypes.TEXT_SSA
        }
        return MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("zh")
            .setLabel(name ?: File(path).name)
            .setId(id.toString())
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    private data class SubtitleChoice(val target: String, val name: String)

    /**
     * 目录内字幕文件的缓存（目录路径 → 扫描时刻与文件名列表）。
     *
     * [subtitleChoiceFor] 每次起播都要对整个目录 `listFiles()` 再逐个 `canRead()`，
     * 全是系统调用；片库目录动辄几千个文件，点下视频到起播之间就有一段说不清的空档。
     * 同一目录连播（下一集、重看）完全没必要重扫，缓存 60 秒即可 ——
     * 代价是新丢进目录的字幕最多晚一分钟被认出来。命中判断只做字符串匹配，仍然是即时的。
     */
    private val subtitleFileCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()

    private fun subtitleFilesIn(dir: File): List<String> {
        val key = dir.absolutePath
        val now = System.currentTimeMillis()
        subtitleFileCache[key]?.let { (at, names) ->
            if (now - at < 60_000L) return names
        }
        val names = dir.listFiles().orEmpty()
            .filter { it.isFile && Formats.isSubtitle(it.name) && it.canRead() }
            .map { it.name }
        // 浏览过的目录不设上限会一直长；64 个目录足够覆盖「同一文件夹连播」的场景
        if (subtitleFileCache.size > 64) subtitleFileCache.clear()
        subtitleFileCache[key] = now to names
        return names
    }

    private fun subtitleChoiceFor(item: VideoItem): SubtitleChoice? {
        val path = item.localPath ?: return null
        val dir = File(path).parentFile ?: return null
        val match = subtitleFilesIn(dir)
            .filter { Formats.matchesVideoStrict(item.fileName, it) }
            .minWithOrNull(
                compareBy<String> { Formats.subtitlePriority(item.fileName, it) }
                    .thenBy { it.lowercase() },
            ) ?: return null
        return SubtitleChoice(File(dir, match).absolutePath, match)
    }

    private fun releaseMedia() {
        runCatching { player?.stop() }
        // 播放结束后放开抽帧：返回列表页时还能按需补图
        Thumbnails.resumeRemoteThumbs()
        holdWifi(false)
    }

    /**
     * 低延迟 Wi-Fi 的持有状态。用显式标记而不是每次 acquire/release，
     * 是因为换字幕（soft 重开）会重复走 startAt，引用计数会一路涨上去。
     */
    private var wifiHeld = false

    private fun holdWifi(hold: Boolean) {
        if (hold == wifiHeld) return
        wifiHeld = hold
        runCatching {
            if (hold) com.yaoahos.mypureplayer.data.WifiPerf.acquire(app)
            else com.yaoahos.mypureplayer.data.WifiPerf.release()
        }
    }

    private fun stopInternal() {
        recordHistory(force = true)
        releaseMedia()
        setPosition(0L)
        _state.update { it.copy(playing = false, buffering = false, preparing = false) }
    }

    /**
     * 播完后按播放键要从头再来。
     *
     * Media3 在 STATE_ENDED 时只是把 playWhenReady 设回 true，播放位置仍停在结尾，
     * 表面上"按了没反应"。所有播放入口（页面按钮、通知栏、媒体键）都先过这里。
     */
    private fun restartIfEnded(p: Player): Boolean {
        if (p.playbackState != Player.STATE_ENDED && !_state.value.ended) return false
        // 不能在结束态上 seekTo(0)+play()：结束回调会和新一轮起播抢状态，
        // 结果是位置回到 0 但立刻又被置回 ended，表现为"按了没反应"。
        // 直接走完整的起播链路（重新 setMediaItem + prepare），和点开视频一致。
        val index = _state.value.queueIndex
        if (index >= 0) requestStart(index, 0L)
        else {
            p.seekTo(0L)
            p.play()
        }
        return true
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else if (!restartIfEnded(p)) {
            p.play()
        }
    }

    /** 明确的「继续播放」：系统的播放按钮/耳机线控按下时用，避免和暂停混淆 */
    fun resume() {
        val p = player ?: return
        if (!p.isPlaying && !restartIfEnded(p)) p.play()
    }

    fun pause() {
        player?.let { if (it.isPlaying) it.pause() }
        recordHistory(force = true)
    }

    /** 媒体栏「退出播放」：停止播放并释放媒体，通知由会话随播放状态自动收起。 */
    fun stopAndDismiss() {
        recordHistory(force = true)
        releaseMedia()
        // 通知栏「退出播放」不一定经过播放页的 onDispose（可能正在后台音频 / 画中画），
        // 这条路径也要把最后一帧擦掉，否则退回桌面时会看到定格的视频。
        blankVideoSurface()
        if (_state.value.backgroundAudio) {
            _state.update { it.copy(backgroundAudio = false) }
        }
        _state.update { it.copy(playing = false, buffering = false, preparing = false, ended = false) }
        setPosition(0L)
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val duration = if (p.duration == C.TIME_UNSET) _state.value.durationMs else p.duration
        val target = ms.coerceAtLeast(0L).let { if (duration > 0) it.coerceAtMost(duration) else it }
        p.seekTo(target)
        setPosition(target)
    }

    fun seekBy(deltaMs: Long) = seekTo(positionMs.value + deltaMs)

    fun cycleScale() {
        val next = (_state.value.scaleMode + 1) % scaleModes.size
        _state.update { it.copy(scaleMode = next) }
        applyScale()
    }

    /** 播放器右上角按钮：与设置页共用同一个开关，并立即重建当前媒体生效 */
    fun toggleHardwareDecode() {
        val next = !settings.hardwareDecode
        settings = settings.copy(hardwareDecode = next)
        _state.update { it.copy(hardwareDecode = next) }
        scope.launch { settingsRepo?.setHardwareDecode(next) }
        restartCurrentMedia()
        Log.i(TAG, "解码方式切换为 ${if (next) "硬件" else "软件"}解码")
    }

    private fun restartCurrentMedia() {
        val index = _state.value.queueIndex
        val position = positionMs.value
        if (index in _queue.value.indices) startAt(index, position, keepSubtitle = true)
    }

    // ---------------------------------------------------------------- 投屏

    private fun acquireMulticastLock() {
        // DLNA SSDP 发现需要组播锁（默认丢弃局域网组播包，不拿锁几乎必然扫不到设备）
        if (multicastLock != null) return
        val manager = runCatching {
            app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return
        val lock = runCatching { manager.createMulticastLock("mypureplayer_dlna") }.getOrNull() ?: return
        runCatching {
            lock.setReferenceCounted(false)
            lock.acquire()
        }
        multicastLock = lock
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    fun startDlnaDiscovery() {
        if (dlnaDiscoveryJob?.isActive == true) return
        acquireMulticastLock()
        dlnaDiscoveryJob = scope.launch(Dispatchers.IO) {
            val devices = DlnaRenderer.discover()
            withContext(Dispatchers.Main.immediate) { _dlnaDevices.value = devices }
            Log.i(TAG, "DLNA 发现设备: ${devices.map { it.name }}")
        }
    }

    fun stopDlnaDiscovery() {
        dlnaDiscoveryJob?.cancel()
        dlnaDiscoveryJob = null
        releaseMulticastLock()
    }

    /** 把当前视频推送到 DLNA 电视；本机暂停，画面仍留在播放页 */
    fun selectDlna(device: DlnaDevice) {
        val url = castUrl()
        if (url == null) {
            _state.update { it.copy(error = "当前视频没有可投屏的地址") }
            return
        }
        scope.launch {
            val item = _state.value.item
            val title = item?.fileName ?: "视频"
            // MIME 必须和真实封装一致：电视按 protocolInfo 决定能不能拉，
            // mkv 谎报成 video/mp4 时部分机型直接拒绝播放（放不出来）
            val mime = item?.fileName?.let { com.yaoahos.mypureplayer.core.Formats.videoMimeOf(it) }
                ?: "video/mp4"
            val ok = DlnaRenderer.play(device, url, title, mime)
            if (!ok) {
                _state.update { it.copy(error = "投屏失败，请确认电视支持 DLNA 且在同一局域网") }
                return@launch
            }
            activeDlnaDevice = device
            _state.update { it.copy(rendererName = device.name) }
            // 投屏期间本机不再解码，但仍要替电视供流：Wi-Fi 一旦进省电，
            // 每个 1MB 分片都会被 DTIM 攒包拖慢，电视端就是「一顿一顿、波动很大」。
            // 锁在整个投屏会话期间持有，断开投屏时释放。
            com.yaoahos.mypureplayer.data.WifiPerf.acquire(app)
            castWifiHeld = true
            // 投屏时本机画面已经暂停，系统会很快让 CPU 睡下去；本地流服务要靠
            // CPU 持续从 SMB 拉数据喂电视，不持有唤醒锁就会「放着放着停住」
            runCatching {
                val pm = app.getSystemService(android.content.Context.POWER_SERVICE)
                    as? android.os.PowerManager
                castWakeLock = pm?.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "mypureplayer:cast",
                )?.apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
            }
            pause()
            Log.i(TAG, "已投屏到 DLNA 设备 ${device.name}")
        }
    }

    /** 断开所有投屏回到本机播放 */
    fun stopCasting() {
        activeDlnaDevice?.let { device ->
            scope.launch { DlnaRenderer.stop(device) }
            activeDlnaDevice = null
        }
        if (castWifiHeld) {
            com.yaoahos.mypureplayer.data.WifiPerf.release()
            castWifiHeld = false
        }
        runCatching { castWakeLock?.takeIf { it.isHeld }?.release() }
        castWakeLock = null
        // 投屏结束就把本地流服务收回回环：不再对局域网开放，局域网 token 也随 TTL 失效
        runCatching { LocalStreamServer.stopLan() }
        _state.update { it.copy(rendererName = "") }
    }

    /** 投屏地址：本机和 SMB 视频都转成局域网 HTTP，DLNA 电视能直接拉流 */
    fun castUrl(): String? {
        val s = _state.value
        val item = s.item ?: return null
        return when (item.source) {
            MediaSource.LOCAL -> LocalStreamServer.lanUrlFor(app, item)
            MediaSource.SMB -> {
                // 与播放走同一套匹配：共享已经不在当前配置里就不投，别把电视引到一个空地址
                val server = serverFor(item)
                if (server != null) LocalStreamServer.lanUrlForSmb(app, item, server) else null
            }
            MediaSource.STREAM -> item.uri
        }
    }

    // ---------------------------------------------------------------- 后台音频

    /** 卸载视频画面只保留声音，再点一次恢复画面 */
    fun setBackgroundAudio(enabled: Boolean) {
        if (enabled) {
            if (_state.value.backgroundAudio) return
            backgroundContainer = attachedContainer
            // 只把 PlayerView 从 UI 容器摘下，播放器继续解码音频。
            // 绝不能 clearMediaItems()——那会把当前媒体连同播放一起清空，
            // 表现为「一开后台音频就停播 / 没声音」，恢复画面时也无片可播。
            // 走统一卸载路径：纯 removeView 会在切后台音频时留下最后一帧残影。
            unmountPlayerView()
            _state.update { it.copy(backgroundAudio = true) }
            // 会话服务在播放中已经是前台服务，进程不会被回收；这里只做幂等启动兜底
            PlaybackSessionService.start(app)
        } else {
            if (!_state.value.backgroundAudio) return
            _state.update { it.copy(backgroundAudio = false) }
            val container = backgroundContainer
            backgroundContainer = null
            if (container != null) attach(container)
            // 播放没停，会话与通知都保持；恢复画面只是把视图挂回去
        }
    }

    private var backgroundContainer: android.view.ViewGroup? = null

    // ---------------------------------------------------------------- 倍速与音轨

    private val _audioTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val audioTracks: StateFlow<List<SubtitleTrack>> = _audioTracks.asStateFlow()

    /** 设置播放倍速（长按临时 2 倍速、菜单里选固定倍速都走这里） */
    fun setRate(value: Float) {
        val v = value.coerceIn(0.25f, 4f)
        runCatching {
            player?.playbackParameters = androidx.media3.common.PlaybackParameters(v)
        }
        _state.update { it.copy(playbackRate = v) }
    }

    private val TRACK_NAME_PATTERN =
        Regex("""^(?:Track|音轨)\s*(\d+)\s*(?:[-–—]\s*)?(?:\[(.+?)]|\((.+?)\))?$""")

    private fun prettyLanguage(raw: String): String {
        val tag = raw.trim().trim('[', ']', '(', ')').trim()
        if (tag.isBlank()) return ""
        val key = tag.lowercase(java.util.Locale.ROOT)
        LANGUAGE_NAMES[key]?.let { return it }
        return if (Regex("""^[A-Za-z][A-Za-z\-]{1,9}$""").matches(tag)) tag else ""
    }

    private val LANGUAGE_NAMES = mapOf(
        "english" to "英语", "eng" to "英语", "en" to "英语", "en-us" to "英语", "en-gb" to "英语",
        "chinese" to "中文", "chi" to "中文", "zho" to "中文", "zh" to "中文", "cmn" to "中文",
        "zh-cn" to "中文", "zh-hans" to "中文", "zh-sg" to "中文",
        "zh-tw" to "繁体中文", "zh-hant" to "繁体中文", "zh-hk" to "繁体中文",
        "cantonese" to "粤语", "yue" to "粤语",
        "japanese" to "日语", "jpn" to "日语", "ja" to "日语",
        "korean" to "韩语", "kor" to "韩语", "ko" to "韩语",
        "french" to "法语", "fra" to "法语", "fre" to "法语", "fr" to "法语",
        "german" to "德语", "deu" to "德语", "ger" to "德语", "de" to "德语",
        "spanish" to "西班牙语", "spa" to "西班牙语", "es" to "西班牙语",
        "russian" to "俄语", "rus" to "俄语", "ru" to "俄语",
        "italian" to "意大利语", "ita" to "意大利语", "it" to "意大利语",
        "portuguese" to "葡萄牙语", "por" to "葡萄牙语", "pt" to "葡萄牙语",
        "thai" to "泰语", "tha" to "泰语", "th" to "泰语",
        "vietnamese" to "越南语", "vie" to "越南语", "vi" to "越南语",
        "arabic" to "阿拉伯语", "ara" to "阿拉伯语", "ar" to "阿拉伯语",
        "hindi" to "印地语", "hin" to "印地语", "hi" to "印地语",
        "und" to "未标注", "undetermined" to "未标注",
    )

    private var lastAudioTracks: List<SubtitleTrack> = emptyList()

    /**
     * 轨道名的语言取自 Media3 提取器解析出的 [Format.language]。
     *
     * 这里刻意不用系统 MediaExtractor 预扫容器：那是 media.extractor 进程里的另一套
     * 实现，对 mkv 里的 AC3 / TrueHD / DTS 轨道一律判「not supported」并丢弃（实测同一个
     * 文件 AC3 与 TrueHD 都被丢掉），扫出来是空列表 —— 语言全丢；万一它只跳过其中一条，
     * 按下标套用的语言还会整体错位。播放本身走的是 Media3 提取器，两者同源才不会打架。
     */
    private fun audioTrackLabel(index: Int, format: Format): String {
        val codec = audioCodecLabel(format)
        val name = trackDisplayName(format)
        val parts = mutableListOf("音轨 ${index + 1}")
        if (name.isNotBlank()) parts += name
        // 名称里已经写着编码或声道（「英语 AC3 5.1」）就不要重复
        if (codec.isNotBlank() && !(name.isNotBlank() && AUDIO_CODEC_HINT.containsMatchIn(name))) parts += codec
        return parts.joinToString(" · ")
    }

    /**
     * 字幕轨只标语言，不标编码。
     *
     * Media3 的 TextRenderer 会把所有文本轨的 mime 统一改写成
     * [MimeTypes.APPLICATION_MEDIA3_CUES]（实测真机三条 SRT/ASS/SRT 轨道拿到的都是
     * 这个值），SRT / ASS / PGS 的区别在 Tracks 这一层已经不可见。猜出来的编码标错
     * 比不标更糟，所以这里只给语言。
     */
    private fun subtitleTrackLabel(index: Int, format: Format): String {
        val name = trackDisplayName(format)
        return if (name.isBlank()) "字幕 ${index + 1}" else "字幕 ${index + 1} · $name"
    }

    /** 轨道显示名：容器里的名称优先，没有就用翻译后的语言。 */
    private fun trackDisplayName(format: Format): String =
        labelText(format.label).ifBlank {
            prettyLanguage(format.language.orEmpty()).takeUnless { it == "未标注" }.orEmpty()
        }

    /**
     * 容器里的轨道名：可能是一句人话（「评论音轨」），也可能只是个语言 tag（chi / eng），
     * 还可能被写成 VLC 风格的「Track 2 [chi]」。能翻译的就翻译，别把原始 tag 直接摆给用户。
     */
    private fun labelText(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isBlank()) return ""
        TRACK_NAME_PATTERN.matchEntire(name)?.let { m ->
            return prettyLanguage(m.groupValues[2].ifBlank { m.groupValues[3] })
        }
        return prettyLanguage(name).ifBlank { name }
    }

    private val AUDIO_CODEC_HINT = Regex(
        """(?i)\b(?:ac-?3|e-?ac-?3|dts(?:-?hd)?|true-?hd|flac|aac|mp[23]|l?pcm|opus|vorbis|alac|ac-?4)\b|\b\d\.\d\b""",
    )

    /**
     * 音轨编码与声道的短标签，例如「TrueHD 5.1」「DTS-HD 7.1」「AC3 2.0」。
     *
     * 家庭影院片源里同一条语言常同时有 AC3 与 TrueHD 两版，只按语言区分不出来；
     * 编码名直接来自 Media3 提取器写进 [Format] 的 mime，认不出来的容器返回空串。
     */
    private fun audioCodecLabel(format: Format): String {
        val name = when (format.sampleMimeType) {
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
            MimeTypes.AUDIO_AC3 -> "AC3"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            // 蓝光原盘里的 LPCM 走系统直出，不需要解码器，但列表里要能看出是无损 PCM
            MimeTypes.AUDIO_RAW -> "PCM"
            MimeTypes.AUDIO_AC4 -> "AC-4"
            else -> return ""
        }
        val layout = when (format.channelCount) {
            1 -> "单声道"
            2 -> "2.0"
            3 -> "3.0"
            6 -> "5.1"
            7 -> "6.1"
            8 -> "7.1"
            in 1..Int.MAX_VALUE -> "${format.channelCount} 声道"
            else -> ""
        }
        return if (layout.isBlank()) name else "$name $layout"
    }

    private var audioTrackIdMap = mutableMapOf<Int, Pair<TrackGroup, Int>>()

    fun refreshAudioTracks() {
        val p = player ?: return
        val tracks = p.currentTracks
        val (list, map) = collectTracks(tracks, C.TRACK_TYPE_AUDIO)
        audioTrackIdMap = map
        val live = list
        if (live.isNotEmpty()) lastAudioTracks = live
        val source = if (live.isNotEmpty()) live else lastAudioTracks
        val selectedId = list.firstOrNull { it.selected }?.id ?: -1
        _audioTracks.value = source.map {
            it.copy(selected = live.isNotEmpty() && it.id == selectedId)
        }
        if (live.isNotEmpty()) {
            Log.d(TAG, "音轨列表: " + live.joinToString(" | ") { it.name + if (it.supported) "" else "(不支持)" })
        }
    }

    fun selectAudioTrack(id: Int) {
        applyTrackOverride(C.TRACK_TYPE_AUDIO, id, audioTrackIdMap)
        refreshAudioTracks()
    }

    /** 关闭音轨 */
    fun disableAudio() {
        val p = player ?: return
        val params = p.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        p.trackSelectionParameters = params
        _audioTracks.value = _audioTracks.value.map { it.copy(selected = false) }
    }

    // ---------------------------------------------------------------- 字幕

    /** 当前会话强制使用的外挂字幕：文件路径或可直接打开的 URL */
    private var forcedSubtitle: String? = null
    private var forcedSubtitleName: String? = null

    /** 只列举候选字幕，不在这里挂载 */
    private fun loadSubtitleCandidates(item: VideoItem, server: SmbServer?) {
        scope.launch {
            // 列目录/列 SMB 共享是网络或磁盘操作，不能留在主线程调度器上跑
            val files = withContext(Dispatchers.IO) {
                try {
                    when {
                        item.source == MediaSource.SMB && server != null ->
                            SmbClient.subtitlesIn(server, item.folderKey)
                                .map { SubtitleFile(it.name, it.url, it.sizeBytes) }
                        item.source == MediaSource.LOCAL -> localSiblingSubtitles(item)
                        else -> emptyList()
                    }
                } catch (t: Throwable) {
                    // 静默吞掉会让「目录里没字幕」和「连接断/权限拒」无法区分，留条日志
                    Log.w(TAG, "列举字幕候选失败 ${item.title}: ${t.javaClass.simpleName}: ${t.message}")
                    emptyList()
                }
            }

            if (!isCurrent(item.id)) return@launch
            val matched = files.filter { Formats.matchesVideo(item.fileName, it.name) }
            _subtitleFiles.value = matched
            if (matched.isEmpty()) return@launch

            val autoMatched = matched.filter { Formats.matchesVideoStrict(item.fileName, it.name) }
            val exact = autoMatched.minWithOrNull(
                compareBy<SubtitleFile> { Formats.subtitlePriority(item.fileName, it.name) }
                    .thenBy { it.name.lowercase() },
            )
            if (item.source == MediaSource.LOCAL) {
                if (exact != null && settings.autoLoadSubtitle && isCurrent(item.id)) {
                    _state.update { it.copy(autoSubtitleName = forcedSubtitleName ?: exact.name) }
                }
                return@launch
            }
            if (settings.autoLoadSubtitle && exact != null && forcedSubtitle == null && server != null) {
                val target = cacheRemoteSubtitle(server, exact.uri, exact.name)
                    ?: smbSubtitleUrl(server, exact.uri)
                if (!isCurrent(item.id)) return@launch
                restartWithSubtitle(target, exact.name, expectedItemId = item.id)
            }
        }
    }

    private fun isCurrent(itemId: String): Boolean = _state.value.item?.id == itemId

    private suspend fun cacheRemoteSubtitle(server: SmbServer, url: String, name: String): String? {
        val file = remoteSubtitleFile(url, name)
        if (file.exists() && file.length() > 0L) return file.absolutePath
        return if (SmbClient.cacheSubtitle(server, url, file).isSuccess) file.absolutePath else null
    }

    /**
     * 字幕缓存失败时的兜底直链：同样去掉 userinfo，凭据只留在进程内注册表。
     * 之前外挂字幕 URL 带明文口令，和视频主 URL 的处理不一致。
     */
    private fun smbSubtitleUrl(server: SmbServer, rawUrl: String): String {
        val full = SmbClient.playableUrl(server, rawUrl)
        val plain = SmbClient.playableUrlWithoutCredentials(full)
        SmbPlaybackRegistry.register(full, server)
        SmbPlaybackRegistry.register(plain, server)
        return plain
    }

    private fun remoteSubtitleFile(url: String, name: String): File {
        val ext = name.substringAfterLast('.', "srt")
            .filter { it.isLetterOrDigit() }.take(8).ifBlank { "srt" }
        val key = MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(app.cacheDir, "subs/remote_$key.$ext")
    }

    private fun localSiblingSubtitles(item: VideoItem): List<SubtitleFile> {
        val path = item.localPath ?: return emptyList()
        val dir = File(path).parentFile ?: return emptyList()
        return (dir.listFiles() ?: return emptyList())
            .filter { it.isFile && Formats.isSubtitle(it.name) && it.canRead() }
            .map { SubtitleFile(it.name, Uri.fromFile(it).toString(), it.length()) }
    }

    /** 带指定外挂字幕重新打开当前媒体，并回到原播放位置 */
    private fun restartWithSubtitle(
        target: String,
        name: String,
        expectedItemId: String? = null,
        soft: Boolean = true,
    ) {
        val s = _state.value
        if (expectedItemId != null && s.item?.id != expectedItemId) {
            Log.d(TAG, "字幕目标已切换，放弃挂载：$name")
            return
        }
        val index = s.queueIndex
        if (index !in _queue.value.indices) return
        forcedSubtitle = target
        forcedSubtitleName = name
        _state.update { it.copy(autoSubtitleName = name, error = null) }
        Log.i(TAG, "挂载外挂字幕：$name")
        startAt(index, positionMs.value, keepSubtitle = true, soft = soft)
    }

    /** 从网络目录手动挑中任意字幕文件（URL 可来自别的目录） */
    fun selectSubtitleUrl(url: String, name: String) {
        val current = _state.value.item ?: return
        val server = serverFor(current)
        if (server == null || !url.startsWith("smb://")) {
            restartWithSubtitle(url, name, expectedItemId = current.id)
            return
        }
        scope.launch {
            val cached = remoteSubtitleFile(url, name)
            if (!cached.exists() || cached.length() <= 0L) {
                withContext(Dispatchers.Main.immediate) { Notice.info("正在准备字幕…") }
            }
            val target = cacheRemoteSubtitle(server, url, name) ?: smbSubtitleUrl(server, url)
            withContext(Dispatchers.Main.immediate) {
                restartWithSubtitle(target, name, expectedItemId = current.id)
            }
        }
    }

    /** 手动选择同目录下的其它字幕 */
    fun selectSubtitleFile(sub: SubtitleFile) {
        val current = _state.value.item ?: return
        if (forcedSubtitleName?.equals(sub.name, ignoreCase = true) == true) {
            _state.update { it.copy(autoSubtitleName = forcedSubtitleName) }
            refreshSubtitleTracks()
            return
        }
        val normalized = sub.name.substringBeforeLast('.').trim().lowercase()
        val existing = _subtitleTracks.value.firstOrNull {
            it.name.substringBeforeLast('.').trim().lowercase() == normalized
        }
        if (existing != null) {
            selectSubtitleTrack(existing.id)
            _state.update { it.copy(autoSubtitleName = sub.name) }
            return
        }
        val server = serverFor(current)
        when {
            sub.uri.startsWith("smb://") && server != null -> selectSubtitleUrl(sub.uri, sub.name)
            sub.uri.startsWith("file://") -> restartWithSubtitle(
                Uri.parse(sub.uri).path ?: sub.uri, sub.name, expectedItemId = current.id,
            )
            else -> restartWithSubtitle(sub.uri, sub.name, expectedItemId = current.id)
        }
    }


    private var subtitleTrackIdMap = mutableMapOf<Int, Pair<TrackGroup, Int>>()

    fun selectSubtitleTrack(id: Int) {
        applyTrackOverride(C.TRACK_TYPE_TEXT, id, subtitleTrackIdMap)
        _state.update { it.copy(activeSubtitleId = id, subtitlesEnabled = id >= 0) }
        refreshSubtitleTracks()
    }

    fun disableSubtitles() {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        p.trackSelectionParameters = params
        forcedSubtitle = null
        forcedSubtitleName = null
        _state.update {
            it.copy(activeSubtitleId = -1, subtitlesEnabled = false, autoSubtitleName = null)
        }
        refreshSubtitleTracks()
    }

    fun refreshSubtitleTracks() {
        val p = player ?: return
        val tracks = p.currentTracks
        val (list, map) = collectTracks(tracks, C.TRACK_TYPE_TEXT)
        subtitleTrackIdMap = map
        _subtitleTracks.value = list
        val activeId = list.firstOrNull { it.selected }?.id ?: -1
        _state.update {
            it.copy(
                activeSubtitleId = activeId,
                // 只有「本机能用」的字幕才算有字幕，否则按钮亮着却没有可选项
                hasSubtitles = list.any { it.supported },
                subtitlesEnabled = activeId >= 0,
            )
        }
        if (list.isNotEmpty()) {
            Log.d(TAG, "字幕列表: " + list.joinToString(" | ") { it.name + if (it.supported) "" else "(不支持)" })
        }
    }

    /** 从 ExoPlayer 的 Tracks 中收集指定类型的轨道，并建立 UI id -> (TrackGroup, index) 映射 */
    private fun collectTracks(
        tracks: Tracks,
        type: Int,
    ): Pair<List<SubtitleTrack>, MutableMap<Int, Pair<TrackGroup, Int>>> {
        val result = mutableListOf<SubtitleTrack>()
        val map = mutableMapOf<Int, Pair<TrackGroup, Int>>()
        var id = 0
        tracks.groups.forEach { group ->
            if (group.type != type) return@forEach
            for (i in 0 until group.length) {
                val format: Format = group.getTrackFormat(i)
                val supported = group.isTrackSupported(i)
                val name = when (type) {
                    C.TRACK_TYPE_AUDIO -> audioTrackLabel(id, format)
                    C.TRACK_TYPE_TEXT -> subtitleTrackLabel(id, format)
                    else -> "${format.label ?: format.language ?: "轨道"} ${id + 1}"
                }
                val selected = group.isTrackSelected(i)
                // 不支持的轨也列出来：直接从列表里消失，用户只会以为片源没这条音轨，
                // 反而查不出是设备缺解码器。UI 会给它加标注并禁用点击。
                result += SubtitleTrack(id, name, selected, supported)
                map[id] = group.mediaTrackGroup to i
                id++
            }
        }
        return result to map
    }

    private fun applyTrackOverride(type: Int, id: Int, map: Map<Int, Pair<TrackGroup, Int>>) {
        val p = player ?: return
        if (id < 0) {
            val params = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(type, true).build()
            p.trackSelectionParameters = params
            return
        }
        val (group, index) = map[id] ?: return
        val override = TrackSelectionOverride(group, index)
        val params = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(override)
            .build()
        p.trackSelectionParameters = params
    }

    // ---------------------------------------------------------------- 事件

    private var thumbCaptured = false

    /**
     * 播放出第一帧后，顺手把这一帧截下来当列表缩略图。
     *
     * 网络视频抽帧要下载片头若干 MB，而这台 NAS 实测只有 ~1MB/s，加上字幕组 MKV
     * 把字体附件堆在文件头（片头 6MB 内一个视频块都没有），一张图能挖掉十几 MB、
     * 十几秒。播放时画面本就已解码上屏，这里直接拷一份 —— 零流量、零等待，
     * 而且是用户真正看到的那一帧。
     */
    private fun captureThumbnail(retry: Boolean = false) {
        val item = _state.value.item ?: return
        val itemId = item.id
        if (item.source == MediaSource.LOCAL) return // 本地有系统缩略图，不需要截
        if (thumbCaptured && !retry) return
        // 画面宿主既可能是 TextureView（默认，转场不留残影）也可能是 SurfaceView，
        // 两条截帧路径不同：TextureView 直接 getBitmap，SurfaceView 必须走 PixelCopy。
        val host = playerView?.videoSurfaceView ?: return
        thumbCaptured = true
        // 稍等一下再截：刚上屏往往还是片头黑场/台标/字幕条
        host.postDelayed({
            // 延迟期间用户可能已经切到下一集：Surface 上那时是新画面，
            // 旧 item 拿着它落盘会把 A 的缩略图挂到 B 上，这里直接丢弃这一帧。
            if (_state.value.item?.id != itemId) return@postDelayed
            runCatching {
                val w = host.width
                val h = host.height
                if (w <= 0 || h <= 0) return@postDelayed
                val bmp = when (val v = playerView?.videoSurfaceView) {
                    is android.view.TextureView -> v.bitmap
                    is SurfaceView -> {
                        val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        PixelCopy.request(
                            v,
                            target,
                            { result ->
                                // PixelCopy 是异步的：回来时可能又换了一集，再核对一次
                                if (result == PixelCopy.SUCCESS &&
                                    _state.value.item?.id == itemId
                                ) {
                                    onFrameCaptured(item, target, retry)
                                } else {
                                    runCatching { target.recycle() }
                                }
                            },
                            Handler(Looper.getMainLooper()),
                        )
                        null
                    }
                    else -> null
                }
                if (bmp != null) onFrameCaptured(item, bmp, retry)
            }.onFailure { Log.d(TAG, "截帧失败 ${it.javaClass.simpleName}") }
        }, if (retry) THUMB_RETRY_DELAY_MS else THUMB_CAPTURE_DELAY_MS)
    }

    /** 把截到的一帧落成列表缩略图；过黑（夜景/黑场）时安排重截一次。 */
    private fun onFrameCaptured(item: VideoItem, bmp: Bitmap, retry: Boolean) {
        // 缩放 + JPEG 压缩 + 写盘是纯 CPU/磁盘工作（几十 ms 量级），留在主线程会跟
        // 控制条动画、手势抢帧；截帧的 bitmap 尚未上屏，跨线程使用安全。
        scope.launch(Dispatchers.IO) {
            val luma = runCatching { Thumbnails.saveCaptured(app, item, bmp) }
                .onFailure { Log.d(TAG, "截帧落盘失败 ${it.javaClass.simpleName}") }
                .getOrDefault(255)
            withContext(Dispatchers.Main.immediate) {
                if (luma < 40 && !retry && _state.value.item?.id == item.id) {
                    captureThumbnail(retry = true)
                }
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                // 延迟 400ms 再显示：seek 造成的瞬时重缓冲不应该每次都在屏幕上闪一个圈
                Player.STATE_BUFFERING -> scheduleBufferingHint()
                Player.STATE_READY -> {
                    clearBufferingHint()
                    val p = player ?: return
                    _state.update {
                        it.copy(
                            // 用 playWhenReady 兜底：seek 期间 isPlaying 会短暂为 false，
                            // 只看 isPlaying 会让暂停/播放图标在快进时来回跳。
                            playing = p.isPlaying || p.playWhenReady,
                            // 关键：READY 只说明「缓冲够了」，不等于画面已渲染。首帧先到就撤圈；
                            // 首帧还没到就继续转，等 onRenderedFirstFrame 撤，避免出现
                            // 「转圈消失但画面还没出来」的空窗（网络视频慢时尤其明显）。
                            // 只有「本轮确实在转圈、且首帧未出」才保持；soft 重开本就没转圈
                            buffering = it.buffering && !firstFrameShown,
                            ended = false,
                            error = null,
                            durationMs = if (p.duration != C.TIME_UNSET) p.duration else it.durationMs,
                        )
                    }
                    // 兜底：READY 后若首帧迟迟不来（纯音频、解码异常、投屏等），
                    // 到点强制撤圈，绝不能永久转下去。
                    //
                    // 但**有视频轨**的片源要区别对待：READY 只代表缓冲够，解码出第一帧
                    // 还要时间（网络片源尤其明显）。这里早先一律 1.2s 撤圈，结果就是
                    // 「转圈闪一下 → 黑屏 → 几秒后画面才出来」，用户以为卡住了。
                    // 有视频轨时改成等首帧，最长到 FIRST_FRAME_MAX_WAIT_MS 才放弃。
                    if (!firstFrameShown) {
                        val seq = startSeq
                        val hasVideoTrack = runCatching {
                            p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
                        }.getOrDefault(false)
                        val wait = if (hasVideoTrack) FIRST_FRAME_MAX_WAIT_MS else FIRST_FRAME_FALLBACK_MS
                        scope.launch {
                            delay(wait)
                            if (seq == startSeq && !firstFrameShown) {
                                firstFrameShown = true
                                _state.update { it.copy(buffering = false, preparing = false) }
                            }
                        }
                    }
                    refreshSubtitleTracks()
                    refreshAudioTracks()
                    refreshVideoSize()
                    if (pendingSeekMs > 0L) {
                        val target = pendingSeekMs
                        pendingSeekMs = 0L
                        if (kotlin.math.abs(p.currentPosition - target) > START_TIME_TOLERANCE_MS) {
                            p.seekTo(target)
                            setPosition(target)
                        }
                    }
                    _state.value.item?.let { playingItem ->
                        val server = serverFor(playingItem)
                        Thumbnails.markPlayed(app, playingItem, server)
                    }
                }
                Player.STATE_ENDED -> {
                    clearBufferingHint()
                    onPlaybackEnded()
                }
                // IDLE 只表示「当前没有媒体在准备」。setMediaItem() 后必经一次 IDLE，
                // 如果这里把 buffering 清掉，起播的加载提示会闪一下消失、然后黑屏等首帧。
                // 真正停止（stopInternal/stopAndDismiss）各自会清 preparing/buffering。
                Player.STATE_IDLE -> {
                    clearBufferingHint()
                    _state.update { it.copy(playing = false) }
                }
            }
        }

        /**
         * 媒体会话（通知栏 / 锁屏 / 耳机线控）直接改 playWhenReady，不经过 [togglePlay]。
         * 播完后它同样只把 playWhenReady 置 true，所以在这里补一次归零。
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val p = player ?: return
            if (playWhenReady && p.playbackState == Player.STATE_ENDED) restartIfEnded(p)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 只同步 playing。isPlaying=true 只表示「playWhenReady 且已开始播放」，
            // 不代表首帧已渲染，不能据此撤转圈。转圈统一由首帧/READY-兜底/错误来收。
            //
            // seek 期间 isPlaying 会短暂 false（目标位置的数据还没回来），直接照抄会让
            // 暂停/播放图标在快进时来回跳。用 playWhenReady 兜底：只要用户没有主动暂停、
            // 也没播完，就保持「正在播放」这一态。
            val p = player
            val held = p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED
            _state.update { it.copy(playing = isPlaying || held) }
        }

        override fun onRenderedFirstFrame() {
            // 画面真的画出来了——这才是撤掉转圈的正确时机。
            // 之前用 STATE_READY 撤圈，而 READY 与首帧的先后在不同视频/网络下会翻转；
            // READY 先到时就会出现「转圈没了、画面还没出」的空窗。
            firstFrameShown = true
            clearBufferingHint()
            _state.update { it.copy(buffering = false, preparing = false) }
            captureThumbnail()
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            _state.update {
                it.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height,
                )
            }
            // 把真实分辨率记进缩略图缓存：网络视频列目录拿不到宽高，
            // 信息页/详情弹窗此前一直显示「未知」。这份缓存落盘，杀进程后仍有效。
            _state.value.item?.let { item ->
                Thumbnails.rememberSize(app, item, videoSize.width, videoSize.height)
            }
            // 宽高比更新会改变比例框的测量结果，跟容器尺寸变化同样需要重测一次
            requestVideoRelayout()
        }

        override fun onPlayerError(error: PlaybackException) {
            clearBufferingHint()
            Log.w(TAG, "onPlayerError: code=${error.errorCode} ${error.message}")
            val item = _state.value.item
            // 本地文件：换下一种数据源策略再试
            if (item != null && item.source == MediaSource.LOCAL && localStrategy < 2) {
                localStrategy++
                startAt(
                    _state.value.queueIndex,
                    positionMs.value,
                    keepSubtitle = true,
                    resetRetry = false,
                )
                return
            }
            // 网络视频（SMB / http 串流）：重建媒体并回到原位置再试一次。
            // HTTP 短链跳转、Wi-Fi 瞬断经常只失败一次；直接弹错误页会让人以为链接失效。
            // 文件确实不在共享里时不重试：再连一次也只会拿到同一个 STATUS_OBJECT_NAME_NOT_FOUND。
            val cause = error.cause ?: error
            val notFound = item?.source == MediaSource.SMB && SmbErrors.isNotFound(cause)
            if (item != null &&
                (item.source == MediaSource.SMB || item.source == MediaSource.STREAM) &&
                !retriedNetwork &&
                !notFound
            ) {
                retriedNetwork = true
                startAt(
                    _state.value.queueIndex,
                    positionMs.value,
                    keepSubtitle = true,
                    resetRetry = false,
                )
                return
            }
            val message = if (item?.source == MediaSource.SMB) {
                if (notFound) "视频文件不存在" else SmbErrors.brief(cause)
            } else {
                playbackErrorMessage(error)
            }
            _state.update {
                it.copy(playing = false, buffering = false, preparing = false, error = message)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshSubtitleTracks()
            refreshAudioTracks()
            refreshVideoSize()
            refreshMediaProfile(tracks)
        }
    }

    /**
     * 播放页的错误文案：只说要紧的一句。
     *
     * 播放页是黑底中央的一行字，写给"现在要不要换一条"看的，不是给排查问题看的；
     * 详细原因仍会写进 logcat，网络页的测试与诊断也照旧给出完整说明。
     */
    private fun playbackErrorMessage(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
            "不支持的格式"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "文件已损坏"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "没有可用的解码器"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "视频文件不存在"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "没有读取权限"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "网络连接失败"
        else -> "无法播放"
    }

    private fun onPlaybackEnded() {
        recordHistory(force = true)
        val index = _state.value.queueIndex
        val size = _queue.value.size
        val hasNext = index >= 0 && index < size - 1
        // 播放顺序：0 不连播 / 1 顺序 / 2 列表循环 / 3 单集循环 / 4 随机。
        // 旧版本只有「自动播放下一个」开关，已在 Prefs 里迁移到 0/1，老用户行为不变。
        when (effectivePlayMode()) {
            3 -> requestStart(index, 0L) // 单集循环：从头重播当前这一集
            2 -> if (size > 0) requestStart(if (hasNext) index + 1 else 0) else finishQueue()
            4 -> {
                if (size <= 1) {
                    requestStart(index, 0L)
                } else {
                    // 随机不连着抽到同一集；队列较短时排除当前项即可
                    val candidates = _queue.value.indices.filter { it != index }
                    requestStart(candidates[kotlin.random.Random.nextInt(candidates.size)], 0L)
                }
            }
            1 -> if (hasNext) requestStart(index + 1) else finishQueue()
            else -> finishQueue()
        }
    }

    /** 播完停在结尾：停掉 playWhenReady，媒体键的「播放」会走重启路径而不是重试结束的时间线。 */
    private fun finishQueue() {
        runCatching { player?.playWhenReady = false }
        _state.update { it.copy(playing = false, ended = true, buffering = false, preparing = false) }
    }

    /** Media3 解码出真实画面尺寸后上报给界面，用于按视频比例自动决定横竖屏 */
    private fun refreshVideoSize() {
        // 实际尺寸由 onVideoSizeChanged 写入，这里预留给后续重建场景的兜底
    }

    /**
     * 记录当前视频的动态范围与音轨输出方式，展示在「视频详情」里。
     *
     * 两者都取 Media3 提取器解析出的 [Format]，不从文件名或列表项猜：
     *  - 动态范围看 `colorInfo.colorTransfer`：ST2084 是 PQ（HDR10 用的就是它），
     *    HLG 是混合对数伽马。杜比视界单看 mime 就能认出，profile 写在 codecs
     *    （`dvhe.05.06` → 05；05 没有 HDR10 兼容基座，接不支持 DV 的屏幕会偏色）。
     *  - 音频输出方式取决于设备有没有对应的直通能力：能直通就是位流原样交给功放，
     *    否则解成 PCM 再输出。Atmos 的对象音频只有在直通链路上才完整保留。
     */
    private fun refreshMediaProfile(tracks: Tracks) {
        var range = ""
        var audio = ""
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> range = dynamicRangeOf(format)
                    C.TRACK_TYPE_AUDIO -> audio = audioOutputOf(format)
                }
            }
        }
        val current = _state.value
        if (current.dynamicRange == range && current.audioOutput == audio) return
        _state.update { it.copy(dynamicRange = range, audioOutput = audio) }
        if (range.isNotBlank() || audio.isNotBlank()) {
            Log.d(TAG, "媒体档案: 画面=$range 声音=$audio")
        }
    }

    private fun dynamicRangeOf(format: Format): String {
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            val profile = format.codecs?.split('.')?.getOrNull(1)
            return if (profile.isNullOrBlank()) "杜比视界" else "杜比视界 $profile"
        }
        return when (format.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            null -> ""
            else -> "SDR"
        }
    }

    private fun audioOutputOf(format: Format): String {
        val label = audioCodecLabel(format)
        if (label.isBlank()) return ""
        val encoding = when (format.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> C.ENCODING_AC3
            MimeTypes.AUDIO_E_AC3 -> C.ENCODING_E_AC3
            MimeTypes.AUDIO_E_AC3_JOC -> C.ENCODING_E_AC3_JOC
            MimeTypes.AUDIO_DTS -> C.ENCODING_DTS
            MimeTypes.AUDIO_DTS_HD -> C.ENCODING_DTS_HD
            MimeTypes.AUDIO_TRUEHD -> C.ENCODING_DOLBY_TRUEHD
            MimeTypes.AUDIO_AC4 -> C.ENCODING_AC4
            else -> -1
        }
        val mode = if (encoding >= 0 && audioCapabilities?.supportsEncoding(encoding) == true) "直通" else "解码"
        return "$label · $mode"
    }

    /**
     * 起播前就先确定一个时长，避免控制条左右两端一段时间内都是 00:00 / --:--。
     *
     * 依次取：列表项自带（本地由 MediaStore 扫描带出）→ 抽帧时落盘的 `.dur` 缓存 →
     * 历史记录里的总时长。三者都是本地读取，不做网络探测，不会和首帧抢带宽。
     * 播放内核真正解析出长度后会覆盖它。
     */
    private fun seededDuration(item: VideoItem): Long {
        if (item.durationMs > 0L) return item.durationMs
        if (::app.isInitialized) {
            val cached = runCatching { Thumbnails.cachedDuration(app, item) }.getOrDefault(0L)
            if (cached > 0L) return cached
        }
        if (item.id.startsWith(PlaybackProgressRepo.VAULT_PREFIX)) {
            return progressRepo?.durationOf(item.id) ?: 0L
        }
        return historyRepo?.historyDuration(item.id, item.source) ?: 0L
    }

    private fun recordHistory(force: Boolean) {
        val s = _state.value
        val item = s.item ?: return
        // 无痕播放：只停止新增记录，已有历史一条都不动。
        // 闸门放在最前面，暂停 / 切后台 / 退出播放页 / 换片所有入口都从这里过，不会漏。
        if (settings.incognito) return
        // 已经被从队列里摘掉的条目不要再回写历史。
        // 删除视频时顺序是「摘队列 → 清历史」：摘队列会触发 stopInternal，那里的
        // recordHistory(force) 是异步的 —— 少了这道闸，它会在清理之后把刚删掉的
        // 那一条重新写回去，表现就是「删了视频，历史里还在」。
        if (_queue.value.none { it.id == item.id }) return
        // 直接读播放器：进度 flow 每 200ms 才更新一次，短视频或刚起播就退出时
        // 它可能还停在 0，明明播了几秒却因为「position < 门槛」被丢掉。
        val livePosition = (runCatching { player?.currentPosition }.getOrNull() ?: 0L).coerceAtLeast(0L)
        val position = maxOf(positionMs.value, livePosition)
        // 原来要求播满 3 秒才写，几秒的短片永远进不了历史。只要真的出过画面
        // （≥300ms）就算看过；想避免误触的话，真正该挡的是「一点就退」而不是短片。
        if (position < 300L) return
        val duration = if (s.durationMs > 0L) s.durationMs else item.durationMs
        if (!force && position <= 0L) return

        // 保险箱：进度照记（否则每次都要从头看），但只写进独立的进度表。
        // 历史记录页读的是 history.json，这里根本不往那个文件写 ——
        // 「保险箱视频出现在历史里」在数据层就不可能发生，不靠界面记得过滤。
        if (item.id.startsWith(PlaybackProgressRepo.VAULT_PREFIX)) {
            val repo = progressRepo ?: return
            scope.launch {
                runCatching { repo.record(item.id, position, duration, force) }
                    .onFailure { Log.w(TAG, "保险箱播放进度保存失败", it) }
            }
            return
        }
        val repo = historyRepo ?: return
        scope.launch {
            // 本地文件已经不在原处（删进回收站、加密进保险箱、在系统相册里删掉）就不要再记。
            // 播放器在文件消失后仍持有这一条，30 秒一次的进度轮询会把它重新写回历史 ——
            // 表现就是"历史页刚清掉，过一会儿它又冒出来了"。
            val gone = withContext(Dispatchers.IO) {
                item.source == MediaSource.LOCAL &&
                    item.localPath?.let { !File(it).exists() } == true
            }
            if (gone) return@launch
            // 进度写不进去就记不上，用户看到的是「看完一集，回来还是从头开始」。
            // 播放中每 30 秒轮询一次，失败了会持续静默，所以至少留一条日志。
            runCatching { repo.record(item, position, duration, force) }
                .onFailure { Log.w(TAG, "历史记录保存失败：${item.title}", it) }
        }
    }

    /**
     * @param pause 是否立刻暂停。退出播放页做淡出动画时传 false：让最后一帧继续播放，
     *   避免"冻结画面淡出"和中央播放键突然闪现；页面真正 dispose 时再按默认值暂停。
     */
    fun onScreenLeave(pause: Boolean = true) {
        recordHistory(force = true)
        if (!pause) return
        // 后台音频 / 画中画模式下离开播放页 / 退到后台都不能暂停
        if (_state.value.backgroundAudio || pictureInPicture) return
        // 不判 isPlaying：缓冲、seek 之后、刚起播这几瞬间 isPlaying 都是 false，
        // 用 if (isPlaying) 会漏掉暂停 —— 日志里就是"退出播放页回到列表，声音还在播"。
        // 直接清掉 playWhenReady 再 pause，任何状态下都能停下来。
        runCatching {
            player?.let {
                it.playWhenReady = false
                it.pause()
            }
        }
        _state.update { it.copy(playing = false) }
    }
}
