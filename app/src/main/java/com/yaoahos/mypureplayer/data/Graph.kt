package com.yaoahos.mypureplayer.data

import android.content.Context
import com.yaoahos.mypureplayer.playback.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 极简依赖容器（手写单例，避免为一个小应用引入 DI 框架）。
 */
object Graph {

    lateinit var local: LocalVideoRepo
        private set
    lateinit var servers: ServerRepo
        private set
    lateinit var streams: StreamRepo
        private set
    lateinit var history: HistoryRepo
        private set
    lateinit var queue: QueueRepo
        private set
    lateinit var playlists: PlaylistRepo
        private set
    lateinit var settings: SettingsRepo
        private set
    lateinit var trash: TrashRepo
        private set
    lateinit var vault: VaultRepo
        private set

    /** 保险箱进度 + 回收站/保险箱期间的进度暂存（两者都不进历史记录） */
    lateinit var progress: PlaybackProgressRepo
        private set

    private val bootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context

    @Volatile
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val app = context.applicationContext
            appContext = app
            local = LocalVideoRepo(app)
            servers = ServerRepo(app)
            streams = StreamRepo(app)
            history = HistoryRepo(app)
            progress = PlaybackProgressRepo(app)
            queue = QueueRepo(app)
            playlists = PlaylistRepo(app)
            settings = SettingsRepo(app)
            // 恢复动作本身在数据层完成，但"恢复后这条视频应该重新出现在历史里"是跨仓库的编排：
            // 交给两个仓库各自的回调，界面不必记得在每个恢复入口补一次。
            trash = TrashRepo(app) { paths -> afterRestore(paths) }
            vault = VaultRepo(app) { restored -> afterVaultRestore(restored) }
            Playback.init(app, servers, history, queue, settings, progress, local)
            ready = true
            // 启动即把历史和进度载入内存：播放器是同步读续播点的，
            // 等历史页打开才 load 会让冷启动后的第一次播放从 0 开始。
            // 服务器与串流也要一起载入：播放列表/历史里的 SMB 条目要靠 serverId（或 host）
            // 找回服务器配置，没载入就会「地址没变却一直提示打不开」。
            bootScope.launch {
                // 保险箱的播放缓存是解密出来的明文副本：正常退出播放页会清，
                // 但进程被杀、崩溃、低内存回收时走不到 onDispose，明文会留在 cacheDir 里。
                // 冷启动统一清一次，保证新会话开始时没有上一次的残留。
                vault.clearPlayCache()
                history.load()
                progress.load()
                playlists.load()
                servers.load()
                streams.load()
                // 设置和上面几份 JSON 不同：它几乎每个页面都要用，而且要用于**首帧**
                // （开关位置、深色模式、网格排列）。这里先读一次填进快照，
                // 之后的页面用 currentOrNull() 就能直接画出正确的状态，不会先画默认值再跳。
                settings.warmUp()
            }
        }
    }

    /**
     * 视频的源文件已经不在原处（移入回收站 / 加密进保险箱 / 被别的应用删掉）后，
     * 把观看历史和播放列表里的对应条目一并摘掉 —— 否则用户点进去就是一条播不了的死记录。
     *
     * 用「文件还在不在」判断，而不是让调用方逐个报成功的 id：
     * 批量操作常常部分失败（加密到一半空间不足、权限缺失），成功的那些文件一定已经不在原处，
     * 失败的还在原地、历史也该留着。
     *
     * 摘历史之前先把进度按**原路径**存进 [PlaybackProgressRepo]：用户要求
     * 「移入回收站 / 保险箱时移除历史，恢复时重新记录」，而恢复后文件会拿到新的
     * MediaStore id，唯一还能对上的是路径。进度存在这里，恢复时由 [afterRestore] 取回。
     */
    suspend fun forgetGone(items: Collection<VideoItem>) {
        if (items.isEmpty()) return
        val gone = withContext(Dispatchers.IO) {
            items.filter { it.localPath?.let { p -> !File(p).exists() } == true }
        }
        if (gone.isEmpty()) return
        // 先摘队列、再清历史：摘队列如果正好停在当前播放的那一条，会触发一次
        // stopInternal → recordHistory（异步落盘）。顺序反过来的话，那次记录会在
        // 清理之后把条目又写回来，表现就是「删了视频，历史里还留着」。
        Playback.dropDeletedFromQueue(gone)
        // 自建播放列表里的同一批条目也要摘掉，否则点进去是一条播不了的死记录
        playlists.dropGone(gone)
        // 本地列表同样要立刻收紧。MediaStore 的删除通知是异步的，等下一次 scan 太晚；
        // 用户在文件管理器里删的文件更是只会留下一条永远刷不掉的陈旧行。
        local.dropGone(gone)
        gone.forEach { item ->
            val path = item.localPath ?: return@forEach
            val position = history.resumePosition(item.id, item.source)
            val duration = history.historyDuration(item.id, item.source)
            // 进了保险箱的视频，播放器那边的 id 是 `vault:<id>`，
            // 进度必须挂这个键 —— 挂原路径的话保险箱里读不到，永远从 0 开始。
            // 回收站不换 id 语义（恢复后仍按路径认回来），继续用路径键。
            val vaultId = vault.entryIdForOriginalPath(path)
            if (vaultId != null) {
                // 连同「隐藏前暂存」的旧路径进度一起搬（上一版只写了路径键），
                // 取两者较大值：用户可能刚在保险箱里看过，也可能只是隐藏前看到一半。
                val stashed = progress.find(path)
                val best = maxOf(position, stashed?.positionMs ?: 0L)
                val dur = if (duration > 0L) duration else stashed?.durationMs ?: 0L
                progress.drop(path)
                if (best >= PlaybackProgressRepo.MIN_POSITION_MS) {
                    progress.record(
                        key = PlaybackProgressRepo.vaultKey(vaultId),
                        positionMs = best,
                        durationMs = dur,
                        force = true,
                    )
                }
            } else {
                if (position <= 0L) return@forEach
                progress.record(
                    key = path,
                    positionMs = position,
                    durationMs = duration,
                    force = true,
                )
            }
        }
        history.removeAll(gone.map { it.id })
    }

    /**
     * 文件从回收站 / 保险箱放回原位之后，把历史记录（连同上次看到的位置）写回来。
     *
     * 恢复出来的文件在 MediaStore 里是**新条目**（新 id、新 uri），所以不能沿用旧记录：
     * 先等媒体库把它重新收录（scanAndAwait 要等系统回调，不是发个广播就走），
     * 再按路径认回来重写。没有暂存进度的（移入前就没看过）不凭空造记录 ——
     * 那只会让历史页多出一条"看到 00:00"的噪音。
     */
    suspend fun afterRestore(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val pending = paths.mapNotNull { path -> progress.find(path)?.let { path to it } }
        if (pending.isEmpty()) {
            progress.dropAll(paths)
            return
        }
        val uriOf = FileOps.scanAndAwait(appContext, pending.map { it.first })
        local.scan(silent = true)
        val byPath = local.videos.value
            .mapNotNull { item -> item.localPath?.let { it to item } }
            .toMap()

        pending.forEach { (path, mark) ->
            val item = byPath[path] ?: uriOf[path]?.let { uri ->
                // 媒体库索引可能还没跟上（或这台机器扫描慢），但回调已经给了真实 content uri，
                // 用它拼一条与本地页 id 完全一致的条目即可；只是 folderKey 拿不到，
                // 历史里点开会单条播放，不影响续播。
                runCatching {
                    val file = File(path)
                    VideoItem(
                        id = uri,
                        title = com.yaoahos.mypureplayer.core.Formats.stripExtension(file.name),
                        fileName = file.name,
                        uri = uri,
                        source = MediaSource.LOCAL,
                        durationMs = mark.durationMs,
                        sizeBytes = file.length(),
                        folderName = file.parentFile?.name.orEmpty(),
                        localPath = path,
                    )
                }.getOrNull()
            } ?: return@forEach
            history.record(item, mark.positionMs, mark.durationMs, force = true)
            progress.drop(path)
        }
    }

    /** 保险箱移出：先把 `vault:<id>` 的进度迁到原路径，再走统一的恢复流程 */
    private suspend fun afterVaultRestore(restored: List<Pair<VaultEntry, String>>) {
        if (restored.isEmpty()) return
        restored.forEach { (entry, path) ->
            progress.rename(PlaybackProgressRepo.vaultKey(entry.id), path)
        }
        afterRestore(restored.map { it.second })
    }
}
