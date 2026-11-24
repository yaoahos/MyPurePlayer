package com.yaoahos.mypureplayer.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** SMB 服务器列表 */
class ServerRepo(context: Context) {

    /**
     * 落盘的是 StoredServer（密码为密文），内存里始终是明文 SmbServer。
     * 这样即使 filesDir 被备份或读出，也不会直接暴露 NAS 密码。
     */
    @kotlinx.serialization.Serializable
    private data class StoredServer(
        val id: Long,
        val name: String,
        val host: String,
        val username: String = "",
        val password: String = "",
        val domain: String = "",
        val share: String = "",
    )

    private val cipher = CredentialCipher()
    private val store = JsonStore(
        File(context.filesDir, "smb_servers.json"),
        ListSerializer(StoredServer.serializer()),
    ) { emptyList() }

    /** 读-改-写串行化：内存快照与落盘必须成对完成，否则并发修改会互相覆盖 */
    private val rw = Mutex()

    private val _servers = MutableStateFlow<List<SmbServer>>(emptyList())
    val servers: StateFlow<List<SmbServer>> = _servers.asStateFlow()

    private fun decryptAll(stored: List<StoredServer>): List<SmbServer> = stored.map { s ->
        // 老版本把地址与共享目录拼成一个字段（如 192.168.1.5Backup），这里在加载时拆回去，
        // 否则这条记录永远连不上，只能靠用户自己发现并手动改
        val fixed = SmbInput.repair(s.host, s.share)
        SmbServer(
            id = s.id, name = s.name,
            host = fixed?.host ?: s.host,
            username = s.username,
            password = cipher.decrypt(s.password),
            domain = s.domain,
            share = fixed?.share ?: s.share,
        )
    }

    private fun encryptAll(items: List<SmbServer>): List<StoredServer> = items.map { s ->
        StoredServer(
            id = s.id, name = s.name, host = s.host,
            username = s.username,
            password = cipher.encrypt(s.password),
            domain = s.domain, share = s.share,
        )
    }

    suspend fun load() {
        _servers.value = decryptAll(store.read())
    }

    suspend fun upsert(server: SmbServer): SmbServer = rw.withLock {
        val target = if (server.id <= 0L) server.copy(id = System.currentTimeMillis()) else server
        val next = _servers.value.toMutableList()
        val index = next.indexOfFirst { it.id == target.id }
        if (index >= 0) next[index] = target else next.add(target)
        val stamped = next.toList()
        store.write(encryptAll(stamped))
        _servers.value = stamped
        target
    }

    /** 配置恢复：整体替换服务器列表（备份里就是用户当时的完整列表） */
    suspend fun replaceAll(items: List<SmbServer>) = rw.withLock {
        val stamped = items.toList()
        store.write(encryptAll(stamped))
        _servers.value = stamped
    }

    suspend fun delete(id: Long) = rw.withLock {
        val stamped = _servers.value.filterNot { it.id == id }
        store.write(encryptAll(stamped))
        _servers.value = stamped
    }

    fun byId(id: Long): SmbServer? = _servers.value.firstOrNull { it.id == id }

    /**
     * 给历史记录 / 播放列表里的 SMB 条目找「现在还挂着」的服务器。
     *
     * 只认两种情况：serverId 精确命中且这台服务器仍覆盖该位置；或者当前配置里有
     * host 与共享名都对得上的服务器（服务器删掉重建、配置从另一台设备恢复之后）。
     *
     * 不按 host 单独兜底。同一个 IP 可能因为 DHCP 换了机器，用户也可能把默认共享
     * 改成了另一个目录 —— 照历史地址继续取，会打开到别处的东西：同名文件照样能播，
     * 其它报「打不开」，用户看到的就是「目录已经换了，却还能播之前的视频」。
     * 匹配不上返回 null，由调用方按「文件不存在」处理，不发这次注定错误的连接。
     */
    fun forSmbItem(serverId: Long, uri: String): SmbServer? {
        val parsed = SmbInput.parse(uri)
        val host = parsed.host.trim().lowercase()
        // 只取共享名：后面那段是共享内的目录与文件名，与服务器配置无关
        val share = parsed.share.substringBefore('/').trim().lowercase()
        if (host.isBlank() || share.isBlank()) return null
        val exact = serverId.takeIf { it > 0L }?.let { byId(it) }
        if (exact != null && exact.covers(host, share)) return exact
        return _servers.value.firstOrNull { it.covers(host, share) }
    }

    /**
     * 这台服务器的共享根是否覆盖 [host] / [share] 指向的位置。
     *
     * 服务器填了默认共享目录时要求共享名一致；没填的（先连服务器、再逐层浏览的那种
     * 配置）只看主机 —— 那种条目本来就散落在同一台机器的不同共享里。
     */
    private fun SmbServer.covers(host: String, share: String): Boolean {
        val own = SmbInput.parse(this.host)
        if (own.host.trim().lowercase() != host) return false
        // 只比共享名的**第一段**：服务器配置里常把入口写到子目录
        // （Backup/PT/临时），而浏览出来的条目地址里只有共享名（Backup）。
        // 旧实现拿整串 "backup/pt/临时" 去比 "backup"，永远不等 ——
        // 表现就是从网络位置刚进去点视频，就提示「网络位置已移除」。
        val ownShare = own.share.substringBefore('/').trim().lowercase()
            .ifBlank { this.share.trim().trim('/').substringBefore('/').lowercase() }
        return ownShare.isBlank() || ownShare == share
    }
}

/** 串流地址 */
class StreamRepo(context: Context) {

    private val store = JsonStore(
        File(context.filesDir, "streams.json"),
        ListSerializer(StreamItem.serializer()),
    ) { emptyList() }

    /** 读-改-写串行化（同 ServerRepo） */
    private val rw = Mutex()

    private val _streams = MutableStateFlow<List<StreamItem>>(emptyList())
    val streams: StateFlow<List<StreamItem>> = _streams.asStateFlow()

    suspend fun load() {
        _streams.value = store.read()
    }

    suspend fun add(name: String, url: String): StreamItem = rw.withLock {
        // id 用毫秒时间戳：批量创建副本时同一毫秒内会连加几条，直接取 now 会撞号，
        // 列表 key 重复会让 Compose 崩。这里保证严格大于现有最大值。
        val nextId = maxOf(
            System.currentTimeMillis(),
            (_streams.value.maxOfOrNull { it.id } ?: 0L) + 1L,
        )
        val item = StreamItem(id = nextId, name = name, url = url)
        val stamped = _streams.value + item
        store.write(stamped)
        _streams.value = stamped
        item
    }

    /**
     * 编辑已有地址。
     *
     * 早先只有增删：地址写错了只能删掉重建，重建后 id 变化，
     * 历史记录、播放列表里引用它 id 的条目就与地址断了关系。
     * 这里只改名字与地址，id 保持不变。
     */
    suspend fun update(id: Long, name: String, url: String): StreamItem? = rw.withLock {
        val index = _streams.value.indexOfFirst { it.id == id }
        if (index < 0) return@withLock null
        val updated = _streams.value[index].copy(name = name, url = url)
        val stamped = _streams.value.toMutableList().also { it[index] = updated }
        store.write(stamped)
        _streams.value = stamped
        updated
    }

    /** 配置恢复：整体替换串流列表 */
    suspend fun replaceAll(items: List<StreamItem>) = rw.withLock {
        val stamped = items.toList()
        store.write(stamped)
        _streams.value = stamped
    }

    suspend fun delete(id: Long) = rw.withLock {
        val stamped = _streams.value.filterNot { it.id == id }
        store.write(stamped)
        _streams.value = stamped
    }
}

/** 观看历史（最多保留 300 条，同一条目滚动更新） */
class HistoryRepo(private val context: Context) {

    private val store = JsonStore(
        File(context.filesDir, "history.json"),
        ListSerializer(HistoryEntry.serializer()),
    ) { emptyList() }

    /** 读-改-写串行化：30 秒轮询的 record 与暂停/换片的 force record 并发时，旧快照覆盖会丢历史 */
    private val rw = Mutex()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    /** 是否已从磁盘载入。所有写操作前都必须为 true，见 [ensureLoadedLocked] */
    @Volatile
    private var loaded = false

    /**
     * 写之前先把磁盘上的旧历史读进来。
     *
     * 这之前只有历史页会调 [load]，而播放器冷启动就能直接记进度：那时 `_history` 还是空表，
     * 一次 record 就会把「原有 300 条」整个覆盖成「只有刚才这一条」—— 表现为
     * **打开 App 直接播放一个视频，历史记录全没了**。写路径自己兜底，不再依赖界面先打开过。
     */
    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        _history.value = store.read()
            .filterNot { it.videoId.startsWith(PlaybackProgressRepo.VAULT_PREFIX) }
            .sortedByDescending { it.updatedAt }
        loaded = true
    }

    suspend fun load() {
        rw.withLock {
            loaded = false
            ensureLoadedLocked()
        }
        migrateLocalPaths()
    }

    /** 历史路径补全的后台任务只跑一次（进程内） */
    private val migrateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var pathMigrated = false

    /**
     * 老版本历史没有 [HistoryEntry.localPath]：按 content:// 反查 MediaStore 的真实路径补上。
     *
     * 为什么要迁移：缩略图缓存与播放现在都以「真实路径」为稳定键，本地 content://
     * 行号重新扫描后会变；不补路径，老记录就既没有缩略图、也没法按路径兜底播放。
     * 放在后台做（300 条最多几百毫秒），绝不挡历史页首帧。
     */
    private fun migrateLocalPaths() {
        if (pathMigrated) return
        pathMigrated = true
        migrateScope.launch {
            // 整个迁移在锁内基于**当前**历史做：否则迁移期间刚写入的一条播放进度，
            // 会被这里用旧快照覆盖掉（表现为「刚看完的那条历史消失」）。
            rw.withLock {
                val current = _history.value
                if (current.none { it.source == MediaSource.LOCAL && it.localPath == null }) return@withLock
                var changed = false
                val next = current.map { entry ->
                    if (entry.source != MediaSource.LOCAL || entry.localPath != null) return@map entry
                    val path = runCatching {
                        context.contentResolver.query(
                            Uri.parse(entry.uri),
                            arrayOf(MediaStore.Video.Media.DATA),
                            null, null, null,
                        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }.getOrNull()
                    if (!path.isNullOrBlank()) {
                        changed = true
                        entry.copy(localPath = path)
                    } else {
                        entry
                    }
                }
                if (!changed) return@withLock
                store.write(next)
                _history.value = next
            }
        }
    }

    /** 配置恢复：整体替换历史。保留原始 id / updatedAt，不走 record 的节流与 300 条上限 */
    suspend fun replaceAll(items: List<HistoryEntry>) = rw.withLock {
        val ordered = items
            .filterNot { it.videoId.startsWith(PlaybackProgressRepo.VAULT_PREFIX) }
            .sortedByDescending { it.updatedAt }
        store.write(ordered)
        _history.value = ordered
        lastRecorded.clear()
        loaded = true
    }

    /** 上次落盘的位置，用于节流：进度变化不足 5 秒就不写盘 */
    private var lastRecorded = mutableMapOf<String, Long>()

    suspend fun record(item: VideoItem, positionMs: Long, durationMs: Long, force: Boolean = false) {
        // 原来「时长未知且位置 <5s 就不记」会把短视频、刚起播就退出的串流全丢掉。
        // 真正不该记的是 0 位置（打开但未出画面）；时长未知也照记，续播时有位置就够了。
        if (positionMs <= 0L) return
        rw.withLock {
            ensureLoadedLocked()
            val previous = lastRecorded[item.id]
            if (!force && previous != null && kotlin.math.abs(positionMs - previous) < 5_000L) return
            lastRecorded[item.id] = positionMs
            // 防泄漏：映射只增不减，超过上限后只保留当前历史里还存在的条目
            if (lastRecorded.size > 400) {
                val live = _history.value.map { it.videoId }.toSet()
                lastRecorded.keys.retainAll(live)
            }
            val entry = HistoryEntry(
                id = item.id.hashCode().toLong() * 31 + item.source.ordinal,
                videoId = item.id,
                title = item.title,
                uri = item.uri,
                source = item.source,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
                folderKey = item.folderKey,
                serverId = item.serverId,
                fileName = item.fileName,
                sizeBytes = item.sizeBytes,
                width = item.width,
                height = item.height,
                folderName = item.folderName,
                dateModified = item.dateModified,
                localPath = item.localPath,
            )
            val next = (_history.value.filterNot { it.videoId == item.id && it.source == item.source } + entry)
                .sortedByDescending { it.updatedAt }
                .take(300)
            store.write(next)
            _history.value = next
        }
        // 冷启动直接播放时历史由写路径自己载入，这里补一次老记录的路径迁移
        migrateLocalPaths()
    }

    suspend fun remove(videoId: String) = rw.withLock {
        ensureLoadedLocked()
        val next = _history.value.filterNot { it.videoId == videoId }
        store.write(next)
        _history.value = next
    }

    /** 多选删除：按记录 id 精确匹配，一次落盘，避免与 30 秒进度写入打成交错覆盖 */
    suspend fun removeByIds(ids: Collection<Long>) = rw.withLock {
        ensureLoadedLocked()
        if (ids.isEmpty()) return@withLock
        val set = ids.toHashSet()
        val snapshot = _history.value
        val next = snapshot.filterNot { it.id in set }
        if (next.size == snapshot.size) return@withLock
        store.write(next)
        _history.value = next
        lastRecorded.keys.retainAll(next.mapTo(HashSet()) { it.videoId })
    }

    /**
     * 视频被删除 / 加密移入保险箱后，把这些记录一并摘掉。
     *
     * 按 [HistoryEntry.videoId]（本地即 MediaStore 的 content:// URI）匹配，
     * 而不是按文件名：同一部影片可能有好几条不同目录下的副本，删错等于删了别人的记录。
     * 返回真正清掉的条数，供调用方决定要不要提示。
     */
    suspend fun removeAll(videoIds: Collection<String>): Int = rw.withLock {
        ensureLoadedLocked()
        if (videoIds.isEmpty()) return@withLock 0
        val ids = videoIds.toHashSet()
        val snapshot = _history.value
        val next = snapshot.filterNot { it.videoId in ids }
        if (next.size == snapshot.size) return@withLock 0
        store.write(next)
        _history.value = next
        lastRecorded.keys.retainAll(next.mapTo(HashSet()) { it.videoId })
        snapshot.size - next.size
    }

    /**
     * 清掉「文件已经不在」的本地记录。
     *
     * 删除动作本身会即时清理（见 [removeAll]），但用户也可能在系统相册、
     * 文件管理器或别的应用里把文件删掉 —— 那些路径不经过本应用，历史里就会留下
     * 一点就报错的死条目。打开历史页时做一次兜底，比等用户碰壁再解释好。
     *
     * 只处理 [MediaSource.LOCAL]：SMB / 串流的存在性要联网才知道，
     * 每次开页都去连一遍服务器不现实，也容易误删（服务器只是暂时离线）。
     */
    suspend fun pruneMissing(): Int = withContext(Dispatchers.IO) {
        rw.withLock {
            ensureLoadedLocked()
            val snapshot = _history.value
            if (snapshot.isEmpty()) return@withLock 0
            val alive = snapshot.filterNot { isGone(it) }
            if (alive.size == snapshot.size) return@withLock 0
            store.write(alive)
            _history.value = alive
            lastRecorded.keys.retainAll(alive.mapTo(HashSet()) { it.videoId })
            snapshot.size - alive.size
        }
    }

    /** 查不到 / 读失败一律当作「还在」：宁可留一条死记录，也不能把用户的历史删掉 */
    private fun isGone(entry: HistoryEntry): Boolean {
        if (entry.source != MediaSource.LOCAL) return false
        val uri = entry.uri
        return when {
            uri.startsWith("content://") -> runCatching {
                // 媒体库索引本身不可靠：文件被 rm 掉、扫描还没跟上时，这一行仍然在。
                // 索引说"有"不算数，还要看它指向的真实路径还在不在。
                context.contentResolver.query(
                    Uri.parse(uri),
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA),
                    null, null, null,
                )?.use { c ->
                    if (c.count == 0) return true
                    val dataIdx = c.getColumnIndex(MediaStore.Video.Media.DATA)
                    if (dataIdx >= 0 && c.moveToFirst()) {
                        val path = c.getString(dataIdx)
                        if (!path.isNullOrBlank() && !File(path).exists()) return true
                    }
                    false
                } ?: false
            }.getOrDefault(false)

            uri.startsWith("file://") -> runCatching {
                val path = Uri.parse(uri).path ?: return false
                !File(path).exists()
            }.getOrDefault(false)

            else -> false
        }
    }

    suspend fun clear() {
        rw.withLock {
            loaded = true
            store.write(emptyList())
            _history.value = emptyList()
            lastRecorded.clear()
        }
    }

    fun resumePosition(videoId: String, source: MediaSource): Long =
        _history.value.firstOrNull { it.videoId == videoId && it.source == source }?.positionMs ?: 0L

    /** 视频条目没有时长时，从观看历史里补一个，用于列表展示总时长 */
    fun historyDuration(videoId: String, source: MediaSource): Long =
        _history.value.firstOrNull { it.videoId == videoId && it.source == source }?.durationMs ?: 0L
}

/** 播放列表（退出应用后仍保留） */
class QueueRepo(context: Context) {

    private val store = JsonStore(
        File(context.filesDir, "queue.json"),
        ListSerializer(VideoItem.serializer()),
    ) { emptyList() }

    suspend fun load(): List<VideoItem> = store.read()

    suspend fun save(items: List<VideoItem>) = store.write(items)
}

/**
 * 用户自建播放列表（独立于「正在播放」队列）。
 * 与 StreamRepo 同构：StateFlow 作为内存镜像，读-改-写用 Mutex 串行化，
 * 所有增删都在锁内基于最新快照，避免并发把刚加的列表覆盖掉。
 */
class PlaylistRepo(context: Context) {

    private val store = JsonStore(
        File(context.filesDir, "playlists.json"),
        ListSerializer(Playlist.serializer()),
    ) { emptyList() }

    private val rw = Mutex()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    suspend fun load() {
        _playlists.value = store.read().sortedWith(playlistOrder())
    }

    /** 置顶者优先（后置顶的排最前），其余按创建时间正序；「正在播放」不入此列表，UI 固定置顶。 */
    private fun playlistOrder(): Comparator<Playlist> =
        compareByDescending<Playlist> { it.pinnedAt > 0 }
            .thenByDescending { it.pinnedAt }
            .thenBy { it.createdAt }

    /** 新建（重名允许，靠 id 区分）；返回新列表供界面直接进入 */
    suspend fun create(name: String): Playlist = rw.withLock {
        val item = Playlist(
            id = "pl-${System.currentTimeMillis()}-${(1000..9999).random()}",
            name = name,
            createdAt = System.currentTimeMillis(),
        )
        val next = (_playlists.value + item).sortedWith(playlistOrder())
        store.write(next)
        _playlists.value = next
        item
    }

    suspend fun rename(id: String, name: String) = rw.withLock {
        val next = _playlists.value.map { if (it.id == id) it.copy(name = name) else it }
        store.write(next)
        _playlists.value = next
    }

    /**
     * 置顶 / 取消置顶。每次置顶都写入当前时间，因此「后置顶的排在最上方」；
     * 排序只在自建列表内部生效，UI 中「正在播放」始终在所有自建列表之上。
     */
    suspend fun setPinned(id: String, pinned: Boolean) = rw.withLock {
        val now = System.currentTimeMillis()
        val next = _playlists.value.map {
            when {
                it.id != id -> it
                pinned -> it.copy(pinnedAt = now)
                else -> it.copy(pinnedAt = 0L)
            }
        }.sortedWith(playlistOrder())
        store.write(next)
        _playlists.value = next
    }

    /** 详情页排序后整体写回成员顺序 */
    suspend fun reorder(id: String, items: List<VideoItem>) = rw.withLock {
        val next = _playlists.value.map { if (it.id == id) it.copy(videos = items) else it }
        store.write(next)
        _playlists.value = next
    }

    suspend fun delete(id: String) = rw.withLock {
        val next = _playlists.value.filterNot { it.id == id }
        store.write(next)
        _playlists.value = next
    }

    /** 批量加入：按 id 去重，已存在的跳过 */
    suspend fun addVideos(id: String, items: List<VideoItem>): Int = rw.withLock {
        val target = _playlists.value.firstOrNull { it.id == id } ?: return@withLock 0
        val existing = target.videos.map { it.id }.toHashSet()
        val added = items.filterNot { it.id in existing }
        if (added.isEmpty()) return@withLock 0
        val next = _playlists.value.map {
            if (it.id == id) it.copy(videos = it.videos + added) else it
        }
        store.write(next)
        _playlists.value = next
        added.size
    }

    /** 从列表移除视频；不删除本地文件 */
    suspend fun removeVideos(id: String, videoIds: Set<String>) = rw.withLock {
        val next = _playlists.value.map {
            if (it.id == id) it.copy(videos = it.videos.filterNot { v -> v.id in videoIds }) else it
        }
        store.write(next)
        _playlists.value = next
    }

    /** 配置恢复：整体替换自建列表 */
    suspend fun replaceAll(items: List<Playlist>) = rw.withLock {
        val stamped = items.sortedWith(playlistOrder())
        store.write(stamped)
        _playlists.value = stamped
    }

    /** 源文件被删/移入回收站或保险箱后，摘掉各列表里对应条目 */
    suspend fun dropGone(gone: Collection<VideoItem>) = rw.withLock {
        if (gone.isEmpty()) return@withLock
        val ids = gone.map { it.id }.toHashSet()
        val paths = gone.mapNotNull { it.localPath }.toHashSet()
        var changed = false
        val next = _playlists.value.map { pl ->
            val kept = pl.videos.filterNot { v -> v.id in ids || (v.localPath != null && v.localPath in paths) }
            if (kept.size != pl.videos.size) changed = true
            if (kept.size != pl.videos.size) pl.copy(videos = kept) else pl
        }
        if (changed) {
            store.write(next)
            _playlists.value = next
        }
    }
}
