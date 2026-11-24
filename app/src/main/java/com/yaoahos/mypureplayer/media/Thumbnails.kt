package com.yaoahos.mypureplayer.media

import android.content.ComponentCallbacks2
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.MediaSource
import com.yaoahos.mypureplayer.data.SmbMediaDataSource
import com.yaoahos.mypureplayer.data.SmbServer
import com.yaoahos.mypureplayer.data.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import com.yaoahos.mypureplayer.data.SmbClient
import java.util.concurrent.ConcurrentHashMap

/**
 * 视频缩略图：内存 LRU + 磁盘缓存 + MediaMetadataRetriever 抽帧。
 *
 * 参考 NextPlayer 的思路：抽帧结果落盘缓存，滚动列表时直接读缓存；
 * 额外支持 SMB —— 用 MediaDataSource 包一层 SMB 随机读，网络视频同样有缩略图。
 */
object Thumbnails {

    private const val CACHE_DIR = "thumbs"
    private const val TAG = "Thumbnails"

    /** 缩略图磁盘缓存总上限，超出后按最近使用时间淘汰 */
    private const val DISK_CACHE_LIMIT = 200L * 1024 * 1024

    /**
     * 缩略图缓存格式版本。
     *
     * v1 时期解码器有 CSD/stride 缺陷，落盘的图可能是黑屏或花屏；文件名带版本号后，
     * 升级到新算法会自动绕过旧图，并顺手清掉它们（见 [ensureCacheVersion]）。
     *
     * v6：抽帧判据（黑场/白闪/发亮过曝/纯色）与续读时间点都改过。文件名不带版本的话，
     * 以前存下的「发亮封面」会一直被命中，用户看到的仍是旧图 —— 改算法不升版本等于没改。
     */
    private const val CACHE_VERSION = "v6"

    /** 缩略图「跑完但没图」时的自动重试次数上限 */
    private const val MAX_QUIET_RETRIES = 3

    /** 「取不出画面」落盘标记的有效期：过期后自动重试，避免偶发网络故障让视频永久没图 */
    private const val NO_THUMB_TTL_MS = 7L * 24 * 60 * 60 * 1000
    // 卡片上的图会被放大到 100~200dp 显示，256px 的源在上屏后明显发糊，
    // 尤其手机 3 列宫格与平板 1~2 列列表。这里按「两倍于最大显示宽度」取：
    // 本地 720px、网络 640px（网络再大只是多耗 SMB 流量，视觉提升有限）。
    // 手机宫格一行 4 格、单格约 84dp（约 250px），留足余量后 640/512 已经足够清晰；
    // 再往上加只会让网络抽帧多读数据、磁盘缓存更占空间。
    private const val MAX_WIDTH_LOCAL = 640
    private const val MAX_WIDTH_REMOTE = 512
    private const val JPEG_QUALITY_LOCAL = 88
    private const val JPEG_QUALITY_REMOTE = 82

    /** Matroska 顶层元素 ID：Segment / Cluster */
    private const val EBML_SEGMENT = 0x18538067L
    private const val EBML_CLUSTER = 0x1F43B675L
    private const val EBML_ATTACHMENTS = 0x1941A469L

    /**
     * 位图内存缓存上限。
     *
     * 缩略图分辨率提到 640~720px 后单张约 0.5~0.9MB（RGB_565），16MB 只放得下
     * 二十来张，滚动时会频繁回读磁盘；这里放宽到 24MB，够一屏半的卡片常驻，
     * 也不至于在低内存机型上把堆顶满（不再申请 largeHeap）。
     * 尺寸仍按 byteCount 计，缓存条目按 LRU 淘汰。
     */
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 通知/锁屏封面用的 JPEG 字节缓存。
     *
     * 过去通知封面在主线程同步做「磁盘 JPEG 解码 + 再压缩」，点开视频那一瞬间
     * 主线程要花几十 ms 在这件事上。改成：每当一张位图进入内存缓存，就顺手在
     * IO 线程压好字节；通知侧只读这里的现成字节，零磁盘、零解码。
     */
    private val artwork = LruCache<String, ByteArray>(12)

    /** 在 IO 线程把位图压成通知封面字节；失败静默，通知侧自会退回应用图标 */
    private fun primeArtwork(context: Context, item: VideoItem, bitmap: Bitmap) {
        val key = keyOf(item)
        if (artwork.get(key) != null) return
        workScope.launch {
            runCatching {
                val out = java.io.ByteArrayOutputStream()
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                    artwork.put(key, out.toByteArray())
                }
            }
        }
    }

    /** 通知封面字节：只走内存，绝不为封面读盘或读网络 */
    fun cachedArtwork(item: VideoItem): ByteArray? {
        val key = keyOf(item)
        artwork.get(key)?.let { return it }
        // 位图刚生成、字节还没压完的窗口期：内存里有就直接压（一次性十几 ms，可接受）
        val bmp = memory.get(key) ?: return null
        return runCatching {
            val out = java.io.ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)) return null
            out.toByteArray().also { artwork.put(key, it) }
        }.getOrNull()
    }

    /** 系统低内存回调：按级别裁剪缩略图缓存（应用层从未挂过 onTrimMemory） */
    fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                memory.evictAll()
                artwork.evictAll()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> memory.trimToSize(memory.maxSize() / 4)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> memory.trimToSize(memory.maxSize() / 2)
        }
    }

    private val localSemaphore = Semaphore(4)
    // 网络抽帧并发：2 路足够（一张封面只要几百 KB），再多只会把 SMB 会话挤满，
    // 连列目录和首帧都排在后面，用户感受就是「进文件夹慢、打开视频慢」。
    // 网络抽帧一次只跑一张。
    // 2.4G Wi-Fi 实测吞吐只有 ~1.8MB/s，两张并发不是「快一倍」，而是各自把对方的
    // 往返拖长：实测同时抽 4 张时会冒出 `Timed out waiting for 12000 ms`，
    // 那一批卡片就真的再也没有图了。串行虽然晚几秒，但每一张都能跑完。
    // 网络抽帧并发：smbj 是单连接多路复用，两条并行读能把「一屏十几张排队」的时间
    // 压掉近一半；只在浏览态生效，播放时由 remotePaused 整体挡住，不会和首帧抢带宽。
    private val remoteSemaphore = Semaphore(2)
    private val knownDurations = ConcurrentHashMap<String, Long>()

    /**
     * 已知分辨率。
     *
     * 网络视频列目录只拿得到文件名/大小/时间，宽高永远是 0，信息页就显示「未知」。
     * 播放器的 onVideoSizeChanged 与抽帧时的容器元数据都能拿到真实宽高，统一记在这里
     * 并落盘 `.wh`：杀进程、重装后信息页仍能显示，而不是每次都靠再播一遍。
     */
    private val knownSizes = ConcurrentHashMap<String, IntArray>()

    /**
     * 正在播放网络视频时暂停抽帧。
     *
     * 播放和抽帧原本共用 SMB 通道，一屏二十多张封面同时开工会让首帧和快进
     * 排在它们后面 —— 这是「打开慢、快进要等」最直接的来源。
     * 播放期间只读已有缓存，返回列表页时再继续补图。
     */
    @Volatile
    private var remotePaused = false

    fun pauseRemoteThumbs() {
        remotePaused = true
    }

    fun resumeRemoteThumbs() {
        remotePaused = false
    }

    /**
     * 缩略图版本号：后台抽帧落盘后自增，列表订阅它重取。
     *
     * 有的卡片第一次等超时只显示了占位图标，抽帧其实还在后台跑；没有这个信号，
     * 它就要等到下次滚动重新组合才会显示，用户看到的就是「图一直不出来」。
     */
    private val _thumbVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val thumbVersion: kotlinx.coroutines.flow.StateFlow<Int> = _thumbVersion

    /**
     * 已知时长的版本号：网络视频的时长要等抽帧完成后才有，
     * 列表页订阅它来让「按时长排序」在时长补齐后自动重排，而不是一直按 0 排。
     */
    private val _durationVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val durationVersion: kotlinx.coroutines.flow.StateFlow<Int> = _durationVersion

    /** 已播放集合的版本号：网络视频播放后登记，列表订阅它来触发缩略图重取。 */
    private val _playedVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val playedVersion: kotlinx.coroutines.flow.StateFlow<Int> = _playedVersion

    /**
     * 抽帧放在独立作用域里：列表滚动导致 Compose 协程取消时，SMB 抽帧不会跟着中断，
     * 同一个视频也不会被反复请求；结果出来后统一写磁盘缓存。
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    /**
     * 本次运行里确认抽不出帧的视频：只显示图标，不再反复尝试拖慢列表。
     * （失败两次才落盘标记，见 [recordFailure]）
     */
    private val unsupported = ConcurrentHashMap.newKeySet<String>()

    /** 每个视频的失败次数：>1 才落盘，避免一次网络抖动就把缩略图永久判死 */
    private val failCounts = ConcurrentHashMap<String, Int>()

    /**
     * 已经走过一次「深读」的视频。
     *
     * 深读 = 跳过片头探针，把整个文件交给 MediaMetadataRetriever 按索引定位，
     * 单次最多读 24MB。它对「首个关键帧离片头很远」的文件有效，但代价是一个视频
     * 十几秒的带宽；因此只在探针失败后试一次，试过就记下来，不再重复烧流量。
     */
    private val deepFallbackUsed = ConcurrentHashMap.newKeySet<String>()

    /**
     * 自动重试计数。
     *
     * 抽帧任务「跑完但没图」（SMB 读超时、容器异常）时，卡片会停在占位图标上；
     * 而卡片自己不会再次发起请求 —— 之前的表现就是「有的缩略图一直没有」，
     * 除非用户退出目录再进来。这里记次数并安排退避重试，最多 [MAX_QUIET_RETRIES] 次。
     */
    private val retryCounts = ConcurrentHashMap<String, Int>()

    /**
     * 已播放过的网络视频（保留 played.txt 只用于返回列表后触发一次刷新）。
     *
     * 现在的策略与主流播放器一致：网络视频和本地视频一样**按需生成**缩略图，
     * 只对真正滚到可见区域的卡片抽帧，并发上限 2，不再只照顾「已播放」的条目 ——
     * 之前那样做会让没播过的卡片永远是图标，用户看到的就是「大部分是空白」。
     */
    private val played = ConcurrentHashMap.newKeySet<String>()
    private var playedLoaded = false

    /** 正在补时长的视频：同一时刻只跑一个，避免列表滚动时重复打 NAS */
    private val durationInflight = ConcurrentHashMap.newKeySet<String>()

    /** 本次运行里探测时长失败过的视频：别每次进目录都重跑一遍慢探测 */
    private val durationFailed = ConcurrentHashMap.newKeySet<String>()

    fun knownDuration(id: String): Long = knownDurations[id] ?: 0L

    /**
     * 记下解析出的视频宽高。
     *
     * 两个来源都会调：播放器 [Playback] 的 onVideoSizeChanged（最准），
     * 以及抽帧时 MediaMetadataRetriever 的 METADATA_KEY_VIDEO_WIDTH/HEIGHT。
     * 值没变化就直接返回，避免播放中每次尺寸回调都写一次盘。
     */
    fun rememberSize(context: Context, item: VideoItem, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val old = knownSizes[item.id]
        if (old != null && old[0] == width && old[1] == height) return
        knownSizes[item.id] = intArrayOf(width, height)
        workScope.launch {
            runCatching {
                val f = File(cacheDir(context), "${keyOf(item)}.wh")
                f.parentFile?.mkdirs()
                f.writeText("$width $height")
            }
        }
    }

    /** 取已知分辨率：条目自带 > 内存 > 磁盘；都没有返回 null（信息页显示「未知」） */
    fun cachedSize(context: Context, item: VideoItem): Pair<Int, Int>? {
        if (item.width > 0 && item.height > 0) return item.width to item.height
        knownSizes[item.id]?.let { return it[0] to it[1] }
        val f = File(cacheDir(context), "${keyOf(item)}.wh")
        if (!f.exists()) return null
        val nums = runCatching { f.readText().trim().split(' ').map { it.toInt() } }.getOrNull() ?: return null
        if (nums.size != 2 || nums[0] <= 0 || nums[1] <= 0) return null
        knownSizes[item.id] = intArrayOf(nums[0], nums[1])
        return nums[0] to nums[1]
    }

    /**
     * 重新进入目录时给这些视频一次重试机会。
     *
     * 旧逻辑对「连续两次取不出画面」落盘 `.nothumb3` 并压进 [unsupported]，有效期一周。
     * 用户第一次进目录时网络抖动/超时被判死，之后再进同一个文件夹也不会重试 ——
     * 表现就是「第一次没生成，以后永远没有缩略图」。进目录时把这一批的判定清掉，
     * 每进一次最多重跑一次，失败也不会滚雪球（并发与超时仍受原策略约束）。
     */
    fun allowRetry(context: Context, items: Collection<VideoItem>) {
        var cleared = false
        items.forEach { item ->
            if (item.source != MediaSource.SMB) return@forEach
            val key = keyOf(item)
            var had = false
            if (unsupported.remove(item.id)) had = true
            if (failCounts.remove(key) != null) had = true
            if (retryCounts.remove(key) != null) had = true
            if (deepFallbackUsed.remove(item.id)) had = true
            if (durationFailed.remove(item.id)) had = true
            if (runCatching { noThumbMark(context, item).delete() }.getOrDefault(false)) had = true
            if (had) cleared = true
        }
        // 卡片一旦因为「已判死」停在图标上，就不会再自己请求；这里主动发一次版本号，
        // 让还空着的卡片重新取图（只在本目录确实有失败标记时触发，避免每次进目录都重排整屏）。
        if (cleared) _thumbVersion.value = _thumbVersion.value + 1
    }

    fun displayDuration(item: VideoItem): Long =
        if (item.durationMs > 0L) item.durationMs else knownDuration(item.id)

    /**
     * 只从缓存里取时长（内存 → 磁盘），不抽帧、不联网。
     *
     * 本地视频的时长由 MediaStore 扫描时带出；网络视频第一次播放时可能还没有，
     * 但只要列表里生成过缩略图（或之前播放/抽帧过），磁盘上就有 `.dur` 记录。
     * 播放页在起播前调一次，长度立刻可见，不必等播放内核解析完容器再跳出来。
     */
    fun cachedDuration(context: Context, item: VideoItem): Long {
        if (item.durationMs > 0L) return item.durationMs
        hydrateDuration(context, item)
        return knownDuration(item.id)
    }

    private fun playedFile(context: Context): File = File(cacheDir(context), "played.txt")

    private fun ensurePlayedLoaded(context: Context) {
        if (playedLoaded) return
        playedLoaded = true
        runCatching {
            playedFile(context).readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { played.add(it) }
        }
    }

    /** 视频播放成功后由播放内核登记，之后才会为它生成网络缩略图 */
    fun markPlayed(context: Context, item: VideoItem, server: SmbServer? = null) {
        if (item.source != MediaSource.SMB) return
        ensurePlayedLoaded(context)
        val key = keyOf(item)
        if (!played.add(key)) return
        workScope.launch {
            runCatching {
                val f = playedFile(context)
                f.parentFile?.mkdirs()
                f.appendText(key + "\n")
            }
            // 通知列表重取：播放成功登记后，返回目录就能看到该视频的缩略图。
            // 注意：不在这里主动抽帧——播放中 SMB 大文件正被 HTTP Range 读取，
            // 并发抽帧会抢同一条 SMB 通道，导致播放器首帧失败（connection failed）。
            // 返回列表后由 VideoThumb 按需加载，已验证能正常生成。
            _playedVersion.value += 1
        }
    }

    /**
     * 把播放时截下来的画面存成缩略图。
     *
     * 网络视频靠「下载片头再解码」出图，代价取决于 NAS 带宽：实测这台服务器只有
     * 约 1MB/s，而字幕组/WEB-DL 的 MKV 把字体附件堆在文件头，片头 6MB 里连一个
     * 视频块都没有 —— 一张缩略图要挖十几 MB、十几秒，几十张卡片根本抽不完。
     *
     * 播放时这一帧本来就已经解码上屏了，直接拷过来是零流量、零等待，
     * 而且是用户真正看到的那一帧，比片头黑场/台标更合适。
     */
    fun saveCaptured(context: Context, item: VideoItem, bitmap: Bitmap): Int {
        val key = keyOf(item)
        val maxWidth = if (item.source == MediaSource.SMB) MAX_WIDTH_REMOTE else MAX_WIDTH_LOCAL
        var luma = 0
        runCatching {
            val scaled = if (bitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bitmap.width
                Bitmap.createScaledBitmap(
                    bitmap,
                    maxWidth,
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val cropped = centerCrop16x9(scaled)
            luma = averageLuma(cropped)
            memory.put(key, cropped)
            primeArtwork(context, item, cropped)
            runCatching {
                val file = File(cacheDir(context), "$key.$CACHE_VERSION.jpg")
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { out ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY_REMOTE, out)
                }
            }
            unsupported.remove(item.id)
            runCatching { noThumbMark(context, item).delete() }
            _thumbVersion.value = _thumbVersion.value + 1
            Log.d(TAG, "播放截帧存为缩略图 亮度=$luma ${item.title}")
        }.onFailure { Log.d(TAG, "播放截帧保存失败 ${it.javaClass.simpleName}") }
        if (bitmap !== memory.get(key)) runCatching { bitmap.recycle() }
        return luma
    }

    /**
     * 只补时长，不管缩略图。
     *
     * 时长和缩略图是两件独立的事：宫格取不出帧（moov 位置刁钻）时，
     * 列表仍然应该能显示时长、按住时长排序，所以这里单独开一条轻量通道，
     * 只读容器元数据区，不取画面。
     */
    fun ensureDuration(context: Context, item: VideoItem, server: SmbServer? = null) {
        if (item.source == MediaSource.STREAM) return
        if (displayDuration(item) > 0L) return
        if (durationFailed.contains(item.id)) return
        if (!durationInflight.add(item.id)) return
        val app = context.applicationContext
        workScope.launch {
            try {
                if (!hydrateDuration(app, item)) backfillDuration(app, item, server)
            } finally {
                durationInflight.remove(item.id)
            }
        }
    }

    /**
     * 只读缓存：内存 → 磁盘，不抽帧、不联网。
     *
     * 媒体通知栏的封面要得急，而且绝不能为了封面去读一遍网络视频（会和正在播放的
     * 数据流抢带宽）。列表滚过一遍后缓存里基本都有图，取不到就让通知栏退回应用图标。
     */
    fun cached(context: Context, item: VideoItem): Bitmap? {
        if (item.source == MediaSource.STREAM) return null
        val key = keyOf(item)
        memory.get(key)?.let { return it }
        ensureCacheVersion(context)
        val file = File(cacheDir(context), "$key.$CACHE_VERSION.jpg")
        if (!file.exists() || file.length() == 0L) return null
        val width = if (item.source == MediaSource.SMB) MAX_WIDTH_REMOTE else MAX_WIDTH_LOCAL
        return runCatching { decodeSampled(file, width) }.getOrNull()?.also { memory.put(key, it) }
    }

    suspend fun load(context: Context, item: VideoItem, server: SmbServer? = null): Bitmap? {
        if (item.source == MediaSource.STREAM) return null
        val key = keyOf(item)
        val remote = item.source == MediaSource.SMB
        val maxWidth = if (remote) MAX_WIDTH_REMOTE else MAX_WIDTH_LOCAL
        memory.get(key)?.let { return it }

        // 内存未命中后的所有路径（读时长、缓存目录清理、JPEG 解码、落盘）都是
        // 磁盘/CPU 工作，几十 ms 量级；调用方的 produceState 协程跑在主线程上，
        // 不下沉的话列表每滚一张卡就卡一下主线程。
        return withContext(Dispatchers.IO) { loadSlow(context, item, server, key, remote, maxWidth) }
    }

    private suspend fun loadSlow(
        context: Context,
        item: VideoItem,
        server: SmbServer?,
        key: String,
        remote: Boolean,
        maxWidth: Int,
    ): Bitmap? {
        val appContext = context.applicationContext

        // 先把上次落盘的时长读回来：卡片副标题、按时长排序都靠它，
        // 等抽帧才显示时长的话，网络视频往往要几十秒后才出现，看着就像「没有时长」
        hydrateDuration(context, item)

        ensureCacheVersion(context)
        val file = File(cacheDir(context), "$key.$CACHE_VERSION.jpg")
        if (file.exists()) {
            decodeSampled(file, maxWidth)?.let {
                memory.put(key, it)
                primeArtwork(appContext, item, it)
                // 旧缓存可能只有图没有时长：立即返回画面，后台补时长，列表随后自动重排
                if (!hydrateDuration(context, item) && item.durationMs <= 0L) {
                    workScope.launch { backfillDuration(appContext, item, server) }
                }
                return it
            }
        }

        // 本地 MediaStore 视频：系统早就生成了缩略图，直接取比自己解容器快得多。
        // 必须放到 IO 线程：取系统缩略图在慢设备上要几百毫秒到几秒，放在主线程会直接卡住列表
        if (!remote) {
            mediaStoreThumbnail(context, item, maxWidth)?.let {
                memory.put(key, it)
                primeArtwork(context, item, it)
                runCatching {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { out ->
                        it.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY_LOCAL, out)
                    }
                }
                return it
            }
        }

        // 网络视频现在也走同一条「按需生成」通道：只有真正滚到可见的卡片才会请求，
        // 每个视频并发上限由 remoteSemaphore 控制，不会一次打满 NAS。

        if (remote && remotePaused) {
            // 正在播网络视频：不去抢同一条 SMB 通道，先让首帧/快进拿到带宽。
            // 返回列表页会 resumeRemoteThumbs()，那时再按需补图。
            Log.d(TAG, "播放中，暂缓网络抽帧 ${item.title}")
            return null
        }

        // 已播放过的网络视频优先用 NAS 上现成的同名封面图。
        // 只对播过的条目做：否则每张卡片都要试 2~4 个文件名，一屏就是上百次 SMB 探测，
        // 既拖慢列表，也把连接占满（这是过去「缩略图基本没有」的主因之一）。
        if (remote && server != null) {
            ensurePlayedLoaded(context)
            if (played.contains(key)) {
                loadRemoteSidecar(context.applicationContext, item, server, key, file, maxWidth)?.let { return it }
            }
        }

        // 失败两次才落盘标记；命中标记就不再重试（本次安装内已确认取不出画面）
        if (unsupported.contains(item.id) || thumbMarkedDead(context, item)) {
            Log.d(TAG, "跳过抽帧（此前已判定取不出画面）${item.title}")
            // 缩略图放弃重试，但时长还得补：否则「按时长排序」对这些大文件永远失效
            ensureDuration(context, item, server)
            return null
        }

        Log.d(TAG, "开始抽帧 ${item.title} src=${item.source}")

        val task = inflight.computeIfAbsent(key) {
            workScope.async {
                val outcome = extractOutcome(context.applicationContext, item, server)
                val frame = outcome?.frame
                if (frame != null) {
                    failCounts.remove(key)
                    retryCounts.remove(key)
                    unsupported.remove(item.id)
                    memory.put(key, frame)
                    primeArtwork(context.applicationContext, item, frame)
                    runCatching {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { out ->
                            frame.compress(
                                Bitmap.CompressFormat.JPEG,
                                if (remote) JPEG_QUALITY_REMOTE else JPEG_QUALITY_LOCAL,
                                out,
                            )
                        }
                    }
                    runCatching { noThumbMark(context.applicationContext, item).delete() }
                    // 通知列表：之前只拿到占位图标的卡片可以来重取了
                    _thumbVersion.value = _thumbVersion.value + 1
                } else if (outcome != null) {
                    // 真的跑完却没画面：连续两次才判「取不出画面」
                    recordFailure(context.applicationContext, item)
                } else {
                    // 超时：这一轮不算数，下次滚到再试
                    Log.d(TAG, "抽帧超时，暂不记录失败 ${item.title}")
                }
                frame
            }
        }
        // 再加一层等待上限：个别容器（或 ROM 的 native 播放器实现）会让抽帧长时间不返回，
        // 连 withTimeout 都打断不了。这里到点就先按「没有缩略图」处理，绝不让列表被它拖住。
        // 网络视频每张只读 2.5MB 左右，单张约 1s；但一屏几十张排队，不能让任何一张
        // 把界面按住。这里只等 4s，超时先显示占位图标，后台任务继续跑完并落盘，
        // 完成后 thumbVersion 自增、卡片自动补图。
        val budget = if (remote) 4_000L else 15_000L
        val result = runCatching { withTimeoutOrNull(budget) { task.await() } }.getOrNull()
        val finished = task.isCompleted
        if (finished) inflight.remove(key, task)
        if (result == null) {
            // 「取不出画面」只由后台任务里的 recordFailure 判定（连续两次才落盘）。
            // 这里绝不能补一刀：排队超时的卡片本来只是没轮到，之前那样会把整屏永久判死。
            if (finished) {
                ensureDuration(context, item, server)
                // 跑完了却没图（多数是 SMB 读超时）：隔一会儿自动再试一次。
                // 没有这一步，卡片会一直空到用户手动退出目录再进来。
                scheduleQuietRetry(context, item)
            }
        }
        return result
    }

    /**
     * 抽帧结果包装：用来区分「抽帧跑完但确实取不到画面」和「抽帧被内部超时打断」。
     * 后者只是这一轮没等到，绝不能落成「该视频永远没有缩略图」的标记。
     */
    private class Outcome(val frame: Bitmap?)

    /**
     * 带结果包装的抽帧入口，供 [load] 的后台任务调用。
     * @return null 表示本轮被超时打断（不计入失败次数）；否则为抽帧结果
     */
    private suspend fun extractOutcome(
        context: Context,
        item: VideoItem,
        server: SmbServer?,
    ): Outcome? {
        val remote = item.source == MediaSource.SMB
        // 关键：先排队拿到并发许可，再开始计时。
        //
        // 之前许可是在 extract 内部拿的，12 秒超时从「进函数」就开始算，
        // 一屏十二张串行时，排在后面的任务光排队就超过 12 秒，还没开始读就被掐断 ——
        // 表现就是「越往后越没有缩略图」，而且一次能空掉六七张。
        val permit = if (remote) remoteSemaphore else localSemaphore
        return permit.withPermit {
            withTimeoutOrNull(if (remote) 15_000L else 10_000L) {
                try {
                    Outcome(extract(context, item, server))
                } catch (io: java.io.IOException) {
                    // 网络抖动 / 会话超时：这一轮不算数，交给自动重试
                    Log.d(TAG, "网络异常，本轮不计失败：${io.message}")
                    null
                }
            }
        }
    }

    /** 真正抽帧：SMB 只读片头一小段，其余交给 MediaMetadataRetriever */
    private suspend fun extract(context: Context, item: VideoItem, server: SmbServer?): Bitmap? {
        val remote = item.source == MediaSource.SMB
        val maxWidth = if (remote) MAX_WIDTH_REMOTE else MAX_WIDTH_LOCAL
        // 并发许可由 extractOutcome 统一持有（那里还负责计时），这里只负责干活。
        return run {
            run {
                // MediaMetadataRetriever 在部分 ROM 上「构造即卡死」，绝不能在入口就 new。
                // 改成按需创建：SMB 走纯 MediaCodec 解码，根本不会构造它。
                var retriever: MediaMetadataRetriever? = null
                var source: MediaDataSource? = null
                var headFile: File? = null
                var pfd: android.os.ParcelFileDescriptor? = null
                try {
                    when (item.source) {
                        MediaSource.SMB -> {
                            val srv = server ?: return@run null
                            val tDs = System.currentTimeMillis()
                            // 按容器分策略，两条路各打各的强项：
                            //
                            // 1) Matroska/WebM（EBML）：元素长度显式写在前面，能按偏移跳过
                            //    字体附件，只取「头部 + 首个 Cluster」。实测一集 1GB 的番剧
                            //    只读 768KB~2MB、约 1.5~2.4s 就能出图。
                            // 2) MP4/MOV 这类：moov 索引常在文件**尾部**，只读片头永远取不到
                            //    画面（实测 3MB 片头仍是「所有时间点取帧均失败」）。交给
                            //    MediaDataSource 让系统解码器自己去尾部定位，容器无关。
                            val container = Formats.extensionOf(item.fileName).lowercase()
                            val ebml = container == "mkv" || container == "webm" || container == "mka"
                            if (ebml) {
                                val probe = buildProbeFile(context, item, srv)
                                if (probe != null) {
                                    try {
                                        var frame = retrieveFromLocalFile(
                                            context,
                                            probe.file,
                                            item,
                                            maxWidth,
                                            nearStart = true,
                                            timeoutMs = 3_500L,
                                        )
                                        // 两种「没结果」都要继续往后补数据：
                                        //  1) 取不到帧（首个 Cluster 前几 MB 没有关键帧）；
                                        //  2) 取到了但几乎全黑 —— 片头黑场被当成画面，
                                        //     这正是「有缩略图但全黑」的来源。
                                        // 每次补 2.5MB，最多补三次（合计约 11MB）。
                                        var extra = 0
                                        while ((frame == null || isUnusableFrame(frame)) && extra < 3) {
                                            extra++
                                            if (!probe.extend(2_500_000L)) break
                                            runCatching { frame?.recycle() }
                                            frame = retrieveFromLocalFile(
                                                context,
                                                probe.file,
                                                item,
                                                maxWidth,
                                                nearStart = true,
                                                timeoutMs = 3_500L,
                                                // 每补一次数据，取帧时间点整体往后推一档
                                                extraDepth = extra,
                                            )
                                        }
                                        val usable = frame?.takeIf { !isUnusableFrame(it) }
                                        if (usable != null) {
                                            Log.d(
                                                TAG,
                                                "SMB 结构探针抽帧成功 ${System.currentTimeMillis() - tDs}ms " +
                                                    "读≈${probe.bytes / 1024}KB ${item.title}",
                                            )
                                            return@run usable
                                        }
                                        if (frame != null) runCatching { frame.recycle() }
                                        // 探针这条捷径走完仍没画面（首个关键帧离得更远、
                                        // 或者扩展名与真实容器不符）：不要直接判死。
                                        // 旧实现到这里就返回 null，连续两次失败后落盘 .nothumb2，
                                        // 这一集从此永远只剩图标 —— 用户看到的就是「总有些图生成不了」。
                                        // 这里改成交给解码器按索引自己定位，深读一次再下结论。
                                        Log.d(
                                            TAG,
                                            "结构探针无画面，转深读 ${System.currentTimeMillis() - tDs}ms ${item.title}",
                                        )
                                    } finally {
                                        runCatching { probe.file.delete() }
                                    }
                                    // 探针走完仍没画面时，**允许深读一次**：交给解码器按
                                    // 索引自己定位（最多读 24MB）。这类文件多是首个关键帧
                                    // 离片头很远，深读能出图；但成本高，同一个视频只试一次，
                                    // 避免每次滚动都重读几十 MB。
                                    if (!deepFallbackUsed.add(item.id)) {
                                        Log.d(
                                            TAG,
                                            "结构探针无画面且已深读过一次，放弃 " +
                                                "${System.currentTimeMillis() - tDs}ms ${item.title}",
                                        )
                                        return@run null
                                    }
                                }
                            }
                            // 非 EBML（MP4/MOV），或结构没解析出来（扩展名不实）：
                            // 交给 MediaDataSource 让解码器自己定位 moov，容器无关。
                            val viaSource = retrieveViaSource(context, item, srv, maxWidth)
                            if (viaSource != null) {
                                Log.d(
                                    TAG,
                                    "SMB 数据源抽帧成功 ${System.currentTimeMillis() - tDs}ms " +
                                        "($container) ${item.title}",
                                )
                                return@run viaSource
                            }
                            Log.d(
                                TAG,
                                "SMB 抽帧失败 ${System.currentTimeMillis() - tDs}ms " +
                                    "($container) ${item.title}",
                            )
                        }

                        // 本地：content:// 交给 Retriever 常常卡在「拿不到可 seek 的句柄」上，
                        // 先解析出真实路径给它，最稳；拿不到再退到文件描述符
                        MediaSource.LOCAL -> {
                            val r = MediaMetadataRetriever().also { retriever = it }
                            val parsed = Uri.parse(item.uri)
                            val tl = System.currentTimeMillis()
                            // 条目自带真实路径时直接用：content:// 行号变化后文件本身仍可读，
                            // 旧实现只认 content URI，行号一换就再也抽不出帧。
                            val path = item.localPath?.takeIf { File(it).canRead() }
                                ?: localFilePath(context, parsed)
                            if (path != null) {
                                r.setDataSource(path)
                            } else {
                                val opened = runCatching {
                                    if (parsed.scheme == "content") {
                                        context.contentResolver.openFileDescriptor(parsed, "r")
                                    } else {
                                        null
                                    }
                                }.getOrNull()
                                pfd = opened
                                if (opened != null) {
                                    r.setDataSource(opened.fileDescriptor)
                                } else {
                                    r.setDataSource(context, parsed)
                                }
                            }
                            Log.d(TAG, "本地 setDataSource ${System.currentTimeMillis() - tl}ms path=$path")
                            return@run frameFrom(context, r, item, maxWidth)
                        }
                        MediaSource.STREAM -> return@run null
                    }

                    frameFrom(context, retriever ?: return@run null, item, maxWidth)
                } catch (t: Throwable) {
                    Log.w(TAG, "抽帧异常 ${item.uri}: ${t.javaClass.simpleName}: ${t.message}")
                    // 网络类异常（SMB 会话超时、连接被回收）与「这个文件取不出画面」
                    // 是两件事：前者过一会儿重试通常就好了，不能算进失败次数，
                    // 否则两次网络抖动就把这集的缩略图永久判死。
                    if (t is java.io.IOException) throw t
                    null
                } finally {
                    runCatching { retriever?.release() }
                    runCatching { source?.close() }
                    runCatching { pfd?.close() }
                    headFile?.let { runCatching { it.delete() } }
                }
            }
        }.also { frame ->
            if (frame == null) {
                Log.d(TAG, "没有取到画面，退回图标：${item.uri.substringAfterLast('/')}")
            }
        }
    }

    /** 从磁盘恢复此前记录的时长（抽帧成功时写下的小文件） */
    private fun hydrateDuration(context: Context, item: VideoItem): Boolean {
        if (knownDurations.containsKey(item.id)) return true
        val dur = File(cacheDir(context), "${keyOf(item)}.dur")
        if (!dur.exists()) return false
        val value = runCatching { dur.readText().trim().toLong() }.getOrNull() ?: return false
        if (value > 0L) {
            if (knownDurations.put(item.id, value) == null) _durationVersion.value += 1
            return true
        }
        return false
    }

    /**
     * 旧版本缓存里只有缩略图、没有时长文件。
     * 命中缩略图时不阻塞界面，后台补一次时长（只解元数据，不抽帧）。
     */
    private suspend fun backfillDuration(context: Context, item: VideoItem, server: SmbServer?) {
        if (knownDurations.containsKey(item.id)) return
        runCatching {
            // SMB 打开数据源就可能要 20~30s（要定位 moov/cues），给足预算
            withTimeoutOrNull(60_000L) {
                val retriever = MediaMetadataRetriever()
                var source: MediaDataSource? = null
                try {
                    when (item.source) {
                        MediaSource.SMB -> {
                            val srv = server ?: return@withTimeoutOrNull
                            val ds = SmbMediaDataSource(srv, item.uri)
                            source = ds
                            retriever.setDataSource(ds)
                        }
                        MediaSource.LOCAL -> retriever.setDataSource(context, Uri.parse(item.uri))
                        MediaSource.STREAM -> return@withTimeoutOrNull
                    }
                    val ms = runCatching {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    }.getOrNull() ?: 0L
                    if (ms > 0L) {
                        if (knownDurations.put(item.id, ms) == null) {
                            val dur = File(cacheDir(context), "${keyOf(item)}.dur")
                            dur.parentFile?.mkdirs()
                            dur.writeText(ms.toString())
                            _durationVersion.value += 1
                        }
                    } else {
                        // 探测不出来就先记着，别再让目录页每开一次就跑一遍慢探测
                        durationFailed.add(item.id)
                    }
                } finally {
                    runCatching { retriever.release() }
                    runCatching { source?.close() }
                }
            }
        }.onFailure { Log.w(TAG, "补时长失败 ${item.uri}: ${it.javaClass.simpleName}") }
    }

    /** 从已设置数据源的 Retriever 里取一帧并记录时长 */
    private fun frameFrom(
        context: Context,
        retriever: MediaMetadataRetriever,
        item: VideoItem,
        maxWidth: Int,
        nearStart: Boolean = false,
        fewProbes: Boolean = false,
        extraDepth: Int = 0,
    ): Bitmap? {
        val durationMs = runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }.getOrNull() ?: 0L
        // 抽帧时顺手把容器里的真实宽高记下来：网络视频的信息页就靠这份数据，
        // 不必等用户先播一遍（播放页的 onVideoSizeChanged 也会写同一份缓存）。
        val px = runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        }.getOrDefault(0)
        val py = runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        }.getOrDefault(0)
        // 部分手机把旋转信息单独给出，宽高按显示方向交换后再记
        val rotation = runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        }.getOrDefault(0)
        if (px > 0 && py > 0) {
            if (rotation == 90 || rotation == 270) rememberSize(context, item, py, px) else rememberSize(context, item, px, py)
        }
        if (durationMs > 0L && knownDurations.put(item.id, durationMs) != durationMs) {
            _durationVersion.value += 1
            // 时长同时落盘：杀进程/重装后列表不必等抽帧完成就能显示时长、按时长排序
            runCatching {
                val dur = File(cacheDir(context), "${keyOf(item)}.dur")
                dur.parentFile?.mkdirs()
                dur.writeText(durationMs.toString())
            }
        }

        return bitmapFromRetriever(retriever, maxWidth, nearStart, fewProbes, extraDepth)
    }

    /**
     * 本地探针文件：头部 + 第一个 Cluster 的紧凑副本。
     *
     * 一开始试过「按原偏移留空洞、用 Void 填缺口」，理论上更完美（元素偏移一个都不动），
     * 但实测某集 843KB 缺口填 Void 后本地解码器直接取不到画面 —— 而同样的文件
     * 顺序读前 3MB 却能出图。说明取帧这条老链路对「有洞的 MKV」不友好。
     * 所以改成把两段数据**紧挨着**写下来：MKV 的元素都是自描述长度，
     * 块内的时间码是相对 Cluster 的、不是文件偏移，拼接不会破坏解码；
     * 唯一失效的是 SeekHead/Cues 里的绝对偏移 —— 而我们本来就只取片头帧，
     * 顺序解析足够。
     *
     * @param bytes 已落盘的总字节数
     * @param extend 取不到画面时继续往后追加视频块，返回是否成功追加
     */
    private class ProbeFile(
        val file: File,
        var bytes: Long,
        val extend: suspend (Long) -> Boolean,
    )

    /** 容器顶层元素：只记位置，用来决定「哪些块可以不读」 */
    private class EbmlElement(val id: Long, val start: Long, val dataStart: Long, val size: Long)

    /**
     * 拼一个「只含必要数据」的本地探针文件。
     *
     * 目标是让一集 1GB 的番剧只读几 MB 就能出图：
     *  - 头部（EBML/SeekHead/Info/Tracks）必须原样保留，它在文件最前面几十 KB；
     *  - 字体/封面这类附件必须跳过 —— 它们能有几十 MB，但解码画面完全不需要；
     *  - 第一个 Cluster 必须保留，画面就在这里。
     *
     * 附件在 Cluster 之前时，头部只读到附件起点为止，剩下的字节用「视频块」顶上来，
     * 两段紧挨着写成连续文件。
     */
    private suspend fun buildProbeFile(
        context: Context,
        item: VideoItem,
        server: SmbServer,
    ): ProbeFile? = withContext(Dispatchers.IO) {
        runCatching {
            val total = item.sizeBytes.takeIf { it > 0L }
            if (total == null) {
                Log.d(TAG, "探针跳过：没有文件大小 ${item.fileName}")
                return@runCatching null
            }
            if (total > 32L * 1024 * 1024 * 1024) return@runCatching null

            val elements = ArrayList<EbmlElement>()
            var clusterStart = -1L
            var pos = 0L
            var guard = 0
            val limit = minOf(total, 96L * 1024 * 1024)
            while (pos in 0L until limit && guard++ < 96) {
                val head = readEbmlHead(server, item.uri, pos) ?: break
                when {
                    head.id == EBML_CLUSTER -> {
                        clusterStart = pos
                        break
                    }
                    head.id == EBML_SEGMENT -> pos = head.dataStart
                    head.size < 0L -> break
                    else -> {
                        elements += EbmlElement(head.id, pos, head.dataStart, head.size)
                        pos = head.dataStart + head.size
                    }
                }
            }
            if (clusterStart <= 0L) {
                Log.d(TAG, "探针跳过：定位不到第一个视频块 ${item.fileName}")
                return@runCatching null
            }

            // 附件（字体/封面）在 Cluster 之前时跳过，头部只读到附件起点；
            // 附件不大（<512KB）就不折腾，整段连着读，省一次往返也少一种失败可能。
            val skip = elements.firstOrNull { it.id == EBML_ATTACHMENTS && it.start < clusterStart }
            val gap = if (skip != null) clusterStart - skip.start else 0L
            val cut = if (skip != null && gap > 512L * 1024) skip.start else clusterStart
            val headLen = cut.toInt()
            // 首个 Cluster 未必一开头就是关键帧，更重要的是**片头通常是黑场/台标**：
            // 只读 768KB（约 1 秒）时，唯一能取到的时间点就是 0 秒，抽出来必然是一张纯黑图
            // —— 这也是「缩略图一直有，但全黑」的根因。
            //
            // 这里取 2MB（约 4~6 秒）作为折中：够越过片头黑场，又把单张流量压到一半。
            // 实测一屏 24 集、每张 4MB 时总量接近 100MB，2.4G 下要排一分多钟，
            // 观感就是「缩略图半天出不来」；取不到画面时仍由 extend 续读兜底。
            var bodyLen = minOf(2L * 1024 * 1024, total - clusterStart)
            if (headLen <= 0 || bodyLen <= 0) return@runCatching null

            val t0 = System.currentTimeMillis()
            val head = SmbClient.readRange(server, item.uri, 0L, headLen)
            if (head == null) {
                Log.d(TAG, "探针失败：读头部 ${headLen / 1024}KB ${item.fileName}")
                return@runCatching null
            }
            val body = SmbClient.readRange(server, item.uri, clusterStart, bodyLen.toInt())
            if (body == null) {
                Log.d(TAG, "探针失败：读视频块 ${bodyLen / 1024}KB ${item.fileName}")
                return@runCatching null
            }
            val readMs = System.currentTimeMillis() - t0

            val ext = Formats.extensionOf(item.fileName).ifBlank { "mkv" }
            val file = File(cacheDir(context), "probe-${keyOf(item)}.$ext")
            file.parentFile?.mkdirs()
            // 两段紧凑拼接：头部在前，第一个 Cluster 紧接着。
            // 不按原偏移留洞（留洞要用 Void 填缺口，实测取不到画面），见 ProbeFile 注释。
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0L)
                raf.seek(0L)
                raf.write(head)
                raf.write(body)
            }
            var bytes = (head.size + body.size).toLong()
            Log.d(
                TAG,
                "探针 ${bytes / 1024}KB（头${head.size / 1024}KB" +
                    (if (gap > 512L * 1024) "+跳过${gap / 1024}KB附件" else "") +
                    "+块${body.size / 1024}KB）读 ${readMs}ms ${item.fileName}",
            )
            ProbeFile(file, bytes) { want ->
                // 取不到画面：顺着文件往后补一段视频块再试（追加到探针文件尾部）
                val more = minOf(want, total - clusterStart - bodyLen)
                if (more <= 0L) {
                    false
                } else {
                    val t1 = System.currentTimeMillis()
                    val chunk = SmbClient.readRange(
                        server,
                        item.uri,
                        clusterStart + bodyLen,
                        more.toInt(),
                    )
                    if (chunk == null || chunk.isEmpty()) {
                        false
                    } else {
                        java.io.FileOutputStream(file, true).use { it.write(chunk) }
                        bodyLen += chunk.size
                        bytes += chunk.size
                        Log.d(
                            TAG,
                            "探针续读 +${chunk.size / 1024}KB " +
                                "${System.currentTimeMillis() - t1}ms ${item.fileName}",
                        )
                        true
                    }
                }
            }
        }.onFailure { Log.w(TAG, "探针异常 ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
    }

    private class EbmlHead(
        val id: Long,
        val size: Long,
        val dataStart: Long,
    )

    private suspend fun readEbmlHead(server: SmbServer, url: String, pos: Long): EbmlHead? {
        val buf = SmbClient.readRange(server, url, pos, 16) ?: return null
        if (buf.size < 2) return null

        fun vint(off: Int, keepMarker: Boolean): Pair<Long, Int>? {
            if (off >= buf.size) return null
            val first = buf[off].toInt() and 0xFF
            if (first == 0) return null
            var len = 1
            var mask = 0x80
            while (first and mask == 0) {
                mask = mask shr 1
                len++
            }
            if (off + len > buf.size) return null
            var value = if (keepMarker) first.toLong() else (first and (mask - 1)).toLong()
            for (k in 1 until len) value = (value shl 8) or (buf[off + k].toLong() and 0xFF)
            return value to len
        }

        val id = vint(0, true) ?: return null
        val sizeField = vint(id.second, false) ?: return null
        val unknown = sizeField.first == (1L shl (7 * sizeField.second)) - 1L
        return EbmlHead(
            id = id.first,
            size = if (unknown) -1L else sizeField.first,
            dataStart = pos + id.second + sizeField.second,
        )
    }

    /**
     * 用本地临时文件抽帧：网络视频「先拉片头再解」的主路径。
     *
     * 同样放守护线程 + 超时：Retriever 的 native 实现偶尔会卡住，协程取消打断不了它。
     * 正常情况一个片头文件几十毫秒就能出图，比走 HTTP 中转快一个量级。
     */
    private suspend fun retrieveFromLocalFile(
        context: Context,
        file: File,
        item: VideoItem,
        maxWidth: Int,
        nearStart: Boolean = false,
        timeoutMs: Long = 6_000L,
        extraDepth: Int = 0,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val holder = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
        val worker = Thread({
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                holder.set(frameFrom(context, retriever, item, maxWidth, nearStart, extraDepth = extraDepth))
            } catch (t: Throwable) {
                Log.d(TAG, "片头抽帧异常：${t.javaClass.simpleName} ${t.message}")
            } finally {
                runCatching { retriever.release() }
            }
        }, "thumb-head")
        worker.isDaemon = true
        worker.start()
        runCatching { worker.join(timeoutMs) }
        if (worker.isAlive) Log.w(TAG, "片头抽帧超时，放弃：${item.fileName}")
        holder.get()
    }

    /**
     * 用 SMB 随机读数据源直接喂给系统解码器取帧（容器无关）。
     *
     * 为什么需要它：MP4/MOV 的 moov 索引经常写在文件尾部，只读片头永远拿不到画面。
     * 交给 MediaDataSource 后，解码器自己会跳到尾部读 moov、再跳回首个关键帧，
     * 我们只负责把每次随机读做快（1MB 预读窗口 + 句柄复用）。
     *
     * 预算 24MB 是硬上限：解码器偶发抽风时不能让它把整集拖下来。
     */
    private suspend fun retrieveViaSource(
        context: Context,
        item: VideoItem,
        server: SmbServer,
        maxWidth: Int,
        timeoutMs: Long = 7_000L,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val holder = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val ds = SmbMediaDataSource(server, item.uri)
        val worker = Thread({
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ds)
                holder.set(
                    frameFrom(
                        context,
                        retriever,
                        item,
                        maxWidth,
                        nearStart = false,
                        fewProbes = true,
                    ),
                )
            } catch (t: Throwable) {
                Log.d(TAG, "数据源抽帧异常：${t.javaClass.simpleName} ${t.message}")
                failure.set(t)
            } finally {
                runCatching { retriever.release() }
                runCatching { ds.close() }
            }
        }, "thumb-ds")
        worker.isDaemon = true
        worker.start()
        runCatching { worker.join(timeoutMs) }
        if (worker.isAlive) {
            Log.w(TAG, "数据源抽帧超时 ${timeoutMs}ms，放弃：${item.fileName}")
            // close 后 readAt 直接返回 EOF，把还卡在网络读上的线程放出来
            runCatching { ds.close() }
        }
        Log.d(TAG, "数据源读取 ${ds.bytesRead / 1024}KB ${item.fileName}")
        holder.get() ?: (failure.get() as? java.io.IOException)?.let { throw it }
    }

    /** 从已设置数据源的 Retriever 里挑一个合适的时间点取帧，并统一裁剪成 16:9 */
    private fun bitmapFromRetriever(
        retriever: MediaMetadataRetriever,
        maxWidth: Int,
        nearStart: Boolean = false,
        // 网络源每多试一个时间点就要多一次 seek + 续读（实测每集 3MB 左右），
        // 只留「片长 10%」和「第一帧」两次，把失败尝试的流量省下来。
        fewProbes: Boolean = false,
        extraDepth: Int = 0,
    ): Bitmap? {
        val durationMs = runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }.getOrNull() ?: 0L

        val primaryUs = if (durationMs > 8_000L) {
            (durationMs * 1_000L / 10L).coerceIn(3_000_000L, 30_000_000L)
        } else {
            0L
        }
        val times = when {
            // 片头文件是被截断的前若干 MB：只能取片头几秒的画面，
            // 按「片长 10%」去取必然落在文件之外，所有时间点都会失败。
            // 顺序很关键：先试 3 秒、5 秒（越过黑场与台标），最后才退回第一帧，
            // 避免片头黑场被当成「取到了画面」而直接返回一张黑图。
            //
            // extraDepth > 0 表示这是「前面都不合格、已往后补过数据」的轮次：
            // 补进去的是后续视频块，时间点也要跟着往后走，否则每轮都在重试同一批
            // 片头帧（旧实现就是这样：白闪/发亮的片头永远换不掉）。
            nearStart -> if (extraDepth <= 0) {
                longArrayOf(9_000_000L, 5_000_000L, 2_000_000L, 0L)
            } else {
                longArrayOf(15_000_000L, 25_000_000L, 40_000_000L, 60_000_000L, 9_000_000L, 5_000_000L)
            }
            // 完整数据源：先取片长 10%（3~30 秒），片头黑场时再往后跳，最后才退回第一帧
            fewProbes -> longArrayOf(primaryUs, primaryUs + 30_000_000L, primaryUs + 90_000_000L, 6_000_000L, 0L)
            // 多给两个「往后跳」的点：10% 处可能正好是转场/淡入淡出的纯色帧，
            // 这时与它相距 20 秒、40 秒的位置大概率已经进入正片。
            else -> longArrayOf(primaryUs, primaryUs + 20_000_000L, primaryUs + 40_000_000L, 1_500_000L, -1L)
        }
        var raw: Bitmap? = null
        // 全都不合格时的兜底：以前留「第一张不合格帧」（常是黑场），
        // 现在留「信息量最大」的那张 —— 转场中间的低对比帧也比纯黑能看。
        var fallback: Bitmap? = null
        var fallbackScore = Int.MIN_VALUE
        for (timeUs in times) {
            val frame = runCatching {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull() ?: continue
            val score = frameScore(frame)
            if (score >= 0) {
                raw = frame
                break
            }
            if (score > fallbackScore) {
                fallback?.recycle()
                fallback = frame
                fallbackScore = score
            } else {
                frame.recycle()
            }
        }
        if (raw == null) raw = fallback ?: runCatching { retriever.frameAtTime }.getOrNull()
        if (raw == null) {
            Log.w(TAG, "所有时间点取帧均失败")
            return null
        }

        val ratio = maxWidth.toFloat() / raw.width
        val bitmap = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                raw,
                maxWidth,
                (raw.height * ratio).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== raw) raw.recycle() }
        } else {
            raw
        }
        return centerCrop16x9(bitmap)
    }

    /**
     * 给一帧打分：负数表示「不适合当封面」，非负数是信息量（越大越有内容）。
     *
     * 三类要在抽帧阶段就排除的画面：
     *  1) 纯黑场 / 片头黑场 —— 不能用「平均亮度」判（黑底白字的标题卡平均亮度也低），
     *     改看「亮点占比」：亮度 > 40 的采样点不足 1% 才算黑场；
     *  2) 白闪 / 淡入淡出的白场 —— 转场最典型的「白色遮罩」，以前只判黑不判白，
     *     于是被当成正常画面存成封面。这里要求 92% 以上采样点亮度 > 240 才算白场；
     *  3) 近乎纯色的低信息帧（转场中段、渐变过渡）—— 用亮度方差判断，
     *     方差低于 12 基本就是一块单色。
     * 黑底白字 / 白底黑字的标题卡因为有大量反差像素，方差很大，能正常通过。
     */
    private fun frameScore(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return -1
        val stepX = (w / 32).coerceAtLeast(1)
        val stepY = (h / 32).coerceAtLeast(1)
        var bright = 0
        var white = 0
        var total = 0
        var sum = 0L
        var sumSq = 0L
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bitmap.getPixel(x, y)
                val luma = (
                    ((p shr 16) and 0xFF) * 299 +
                        ((p shr 8) and 0xFF) * 587 +
                        (p and 0xFF) * 114
                    ) / 1000
                if (luma > 40) bright++
                if (luma > 240) white++
                sum += luma
                sumSq += luma.toLong() * luma
                total++
                x += stepX
            }
            y += stepY
        }
        if (total == 0) return -1
        if (bright * 100 < total) return -1
        if (white * 100 >= total * 78) return -1
        val mean = (sum / total).toInt()
        val variance = (sumSq / total - mean.toLong() * mean).toInt()
        if (variance < 120) return -1
        // 发亮的过曝帧：整体亮度很高、几乎没有细节（白闪前后、淡入淡出的白场）。
        // 只判「纯白」不够 —— 淡出到一半的画面只有七成像素是纯白，依然刺眼。
        // 有细节的亮场景（雪景、白墙+字幕）方差远大于 2000，不会被误杀。
        if (mean >= 200 && variance < 2_000) return -1
        return variance
    }

    /** 这一帧是否不能当封面（黑场 / 白闪 / 纯色） */
    private fun isUnusableFrame(bitmap: Bitmap): Boolean = frameScore(bitmap) < 0

    /** 抽样平均亮度（0~255），16×16 个采样点，够用且几乎不耗时 */
    private fun averageLuma(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0
        var sum = 0L
        var count = 0
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 16).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return 0
        return (sum / (count * 3L)).toInt()
    }

    /**
     * 把 content:// 解析成磁盘上的真实路径。
     * 有真实路径时抽帧几乎不会出错，也免得 Retriever 在 provider 上等句柄。
     */
    private fun localFilePath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() && File(it).canRead() }
    }

    /**
     * 本地 MediaStore 视频的系统缩略图。
     *
     * 媒体库在扫描视频时就已经生成好了一张图，取它比自己解析容器快一个数量级；
     * 更重要的是，content:// 交给 MediaMetadataRetriever 有时会一直卡在索引上，
     * 这里先拿现成的，拿不到再退回抽帧。
     */
    private fun mediaStoreThumbnail(context: Context, item: VideoItem, maxWidth: Int): Bitmap? {
        val uri = runCatching { Uri.parse(item.uri) }.getOrNull() ?: return null
        if (uri.scheme != "content") return null
        val resolver = context.contentResolver
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(uri, android.util.Size(maxWidth, maxWidth * 9 / 16), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    resolver,
                    ContentUris.parseId(uri),
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                )
            }
        }.getOrNull() ?: return null
        val scaled = if (bitmap.width > maxWidth) {
            runCatching {
                Bitmap.createScaledBitmap(
                    bitmap,
                    maxWidth,
                    (bitmap.height * (maxWidth.toFloat() / bitmap.width)).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== bitmap) bitmap.recycle() }
            }.getOrDefault(bitmap)
        } else {
            bitmap
        }
        return centerCrop16x9(scaled)
    }

    /** NAS 上常见的「视频名.jpg / .png / .webp」封面，命中后直接缓存，不再抽帧 */    private suspend fun loadRemoteSidecar(
        context: Context,
        item: VideoItem,
        server: SmbServer,
        key: String,
        cacheFile: File,
        maxWidth: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val base = item.fileName.substringBeforeLast('.').ifBlank { return@withContext null }
        val folder = item.uri.substringBeforeLast('/')
        // 行业主流命名（Infuse / Kodi / nPlayer / 群晖 DSM 缩略图索引）：
        // 同名封面优先，其次 -poster/-cover/.thumb，再到目录级 poster/folder/cover，
        // 最后是群晖 @eaDir 里预生成的视频缩略图。找到即用，不再走卡死的抽帧。
        val candidates = buildList {
            for (ext in listOf("jpg", "png")) add("$folder/$base.$ext")
            // 群晖为视频预生成的缩略图（命中率高，值得一次往返）
            add("$folder/@eaDir/$base/SYNOPHOTO_THUMB_VIDEO_M.jpg")
        }
        for (url in candidates) {
            val bytes = withTimeoutOrNull(2_500L) {
                runCatching { SmbClient.readHead(server, url, 2 * 1024 * 1024) }.getOrNull()
            } ?: continue
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            val scaled = runCatching {
                Bitmap.createScaledBitmap(
                    bitmap,
                    minOf(maxWidth, bitmap.width),
                    (bitmap.height * (minOf(maxWidth, bitmap.width).toFloat() / bitmap.width)).toInt().coerceAtLeast(1),
                    true,
                )
            }.getOrDefault(bitmap)
            val cropped = centerCrop16x9(scaled)
            memory.put(key, cropped)
            runCatching {
                cacheFile.parentFile?.mkdirs()
                FileOutputStream(cacheFile).use { it.write(bytes) }
            }
            return@withContext cropped
        }
        null
    }

    private fun centerCrop16x9(src: Bitmap): Bitmap {
        val targetRatio = 16f / 9f
        val ratio = src.width.toFloat() / src.height
        if (ratio > targetRatio) {
            val w = (src.height * targetRatio).toInt()
            val x = (src.width - w) / 2
            val out = Bitmap.createBitmap(src, x, 0, w, src.height)
            if (out !== src) src.recycle()
            return out
        } else if (ratio < targetRatio) {
            val h = (src.width / targetRatio).toInt()
            if (h <= 0) return src
            val y = (src.height - h) / 2
            val out = Bitmap.createBitmap(src, 0, y, src.width, h)
            if (out !== src) src.recycle()
            return out
        }
        return src
    }

    private fun decodeSampled(file: File, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, CACHE_DIR)

    /** 缓存版本迁移只跑一次：清掉旧算法留下的黑屏/花屏缩略图 */
    @Volatile
    private var cacheVersionChecked = false

    /**
     * 缩略图磁盘缓存的 LRU 兜底。
     *
     * 每个视频会留下一个 .jpg（约 30~120KiB）和一个 .dur 文本，没有上限时长期使用
     * 会把 cacheDir 养成几百 MB：系统只在存储吃紧时才清理 cache，日常并不会，
     * 于是「设置-缓存占用」越滚越大。这里在进程启动第一次碰缩略图时按总大小淘汰，
     * 保留最近的图，删掉的只是可再生的数据，不影响任何用户内容。
     */
    private fun trimDiskCache(dir: File) {
        val files = dir.listFiles()
            ?.filter {
                it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".dur") ||
                    it.name.endsWith(".wh") || it.name.startsWith("probe-"))
            }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CACHE_LIMIT) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CACHE_LIMIT) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) total -= len
        }
    }

    private fun ensureCacheVersion(context: Context) {
        if (cacheVersionChecked) return
        cacheVersionChecked = true
        runCatching {
            val dir = cacheDir(context)
            trimDiskCache(dir)
            val marker = File(dir, ".$CACHE_VERSION")
            if (marker.exists()) return
            dir.listFiles()?.forEach { f ->
                val name = f.name
                if (name.endsWith(".jpg") || name.startsWith(".nothumb") ||
                    name.endsWith(".nothumb") || name.endsWith(".nothumb2") ||
                    name.endsWith(".nothumb3")
                ) {
                    f.delete()
                }
            }
            dir.mkdirs()
            marker.writeText("1")
        }
    }

    /**
     * 「这个视频取不出帧」的落盘标记。
     *
     * 生成一次要花几秒到几十秒，失败结果必须记住；但网络抖动、解码器忙都可能造成
     * 一次失败，所以改成**连续失败两次**才落盘（见 [recordFailure]）。
     * 文件名带 v3：抽帧策略（深读兜底、更长片头续读）改过之后，旧版本留下的
     * .nothumb2 不再生效，之前被判死的视频会自动重试一次。
     */
    private fun noThumbMark(context: Context, item: VideoItem): File =
        File(cacheDir(context), "${keyOf(item)}.nothumb3")

    /**
     * 标记是否仍然有效。
     *
     * 标记本身不带原因，「连续两次没取到」既可能是容器真的没有可用帧，也可能只是
     * 那两次网络抖动 / NAS 休眠。所以给它一周有效期：过期的标记自动作废并重新抽一次，
     * 避免一次偶发故障让某个视频**永久**只剩图标。
     */
    private fun thumbMarkedDead(context: Context, item: VideoItem): Boolean {
        val f = noThumbMark(context, item)
        if (!f.exists()) return false
        if (System.currentTimeMillis() - f.lastModified() < NO_THUMB_TTL_MS) return true
        runCatching { f.delete() }
        unsupported.remove(item.id)
        failCounts.remove(keyOf(item))
        retryCounts.remove(keyOf(item))
        Log.d(TAG, "缩略图失败标记已过期，重新尝试 ${item.title}")
        return false
    }

    /**
     * 悄悄排一次重试：延迟 [delayMs] 后让列表重新请求这张缩略图。
     *
     * 只在「任务真的跑完但没拿到图」时调用；排队被预算掐断的不算，
     * 那种情况下任务还在后台跑，跑完自己会自增 thumbVersion。
     */
    private fun scheduleQuietRetry(context: Context, item: VideoItem) {
        if (item.source != MediaSource.SMB) return
        if (unsupported.contains(item.id) || thumbMarkedDead(context, item)) return
        val key = keyOf(item)
        val n = (retryCounts[key] ?: 0) + 1
        if (n > MAX_QUIET_RETRIES) {
            Log.d(TAG, "缩略图重试已达上限，保持占位 ${item.title}")
            return
        }
        retryCounts[key] = n
        workScope.launch {
            // 退避：一屏好几张同时失败时别一起顶上来，把带宽让给正在跑的那张
            kotlinx.coroutines.delay(1_500L * n)
            if (unsupported.contains(item.id) || thumbMarkedDead(context, item)) return@launch
            _thumbVersion.value = _thumbVersion.value + 1
        }
    }

    private fun recordFailure(context: Context, item: VideoItem) {
        val key = keyOf(item)
        val count = (failCounts[key] ?: 0) + 1
        failCounts[key] = count
        if (count < 2) return
        unsupported.add(item.id)
        runCatching {
            val f = noThumbMark(context, item)
            f.parentFile?.mkdirs()
            if (!f.exists()) f.writeText("1")
            // 顺手清掉探测用的临时文件，别让它们长期占着缓存目录
            File(cacheDir(context), "probe-${key}.bin").delete()
            File(cacheDir(context), "sparse-${key}.bin").delete()
        }
    }

    fun diskUsage(context: Context): Long =
        cacheDir(context).listFiles()
            ?.filter { it.name.endsWith(".jpg") || it.name.endsWith(".dur") || it.name.endsWith(".wh") }
            ?.sumOf { it.length() } ?: 0L

    fun clear(context: Context) {
        memory.evictAll()
        // 内存里的「已知时长 / 取不出帧」也要一起清掉，否则点了清除缓存，
        // 这些视频仍然不会再尝试一次，看着就像清除没生效
        knownDurations.clear()
        knownSizes.clear()
        unsupported.clear()
        failCounts.clear()
        retryCounts.clear()
        deepFallbackUsed.clear()
        durationFailed.clear()
        durationInflight.clear()
        played.clear()
        cacheDir(context).listFiles()?.forEach { runCatching { it.delete() } }
        _durationVersion.value += 1
    }

    private fun keyOf(item: VideoItem): String {
        // SMB 用 smb:// 路径（本身稳定）；本地优先用真实文件路径。
        // 本地 id 是 MediaStore 的 content:// 行号，重新扫描、移动文件或清理媒体库后
        // 会变化 —— 行号一变，缩略图缓存与历史播放同时失效，用户看到的就是
        // 「过段时间记录既不能播、也没有图」。路径不随行号变化。
        val stable = item.localPath?.takeIf { it.isNotBlank() } ?: item.id
        val digest = MessageDigest.getInstance("MD5").digest(stable.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
