package com.yaoahos.mypureplayer.data

import com.yaoahos.mypureplayer.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置备份（本地文件 / WebDAV 共用同一份配置快照）。
 *
 * 快照本体是 JSON，但**写入任何目标之前都先用 [BackupCrypto] 整体加密**：
 * 备份里虽然没有密码，却有主机名、共享名、账号、串流地址——这些足够拼出一张内网地图，
 * 明文躺在网盘或聊天记录里并不合适。
 *
 * 范围：偏好与连接配置（外观与主题、播放选项、网络页与 SMB 浏览页的排序、SMB 共享、
 * 串流地址）+ 观看历史 + 正在播放队列 + 自建播放列表。按用户要求，后三者一并纳入备份：
 * 换设备/重装后能接着看。它们的 VideoItem 存的是元数据快照（uri/localPath 等），
 * 换设备后本地路径可能已失效，界面打开时按「文件还在不在」自行过滤。
 *
 * 仍不进备份：回收站、保险箱（加密内容绑本机、且体积大，走独立的本地存储）；
 * 本地视频页的排序与浏览方式（页内状态，未持久化）；WebDAV 连接本身（它是备份的去处，
 * 恢复前本来就要先能连上它）。
 *
 * 关于密码：**任何密码都不写进备份文件**。
 * SMB 密码由 [CredentialCipher] 绑本机 Keystore 加密，换台设备根本解不开，写进去也解不了。
 * 所以备份里只留地址、账号、共享名等非敏感字段。恢复时优先沿用本机已有的同账号密码，
 * 没有的留空并在提示里明确告诉用户去补填，而不是让他点开一个连不上的条目自己猜。
 */
object ConfigBackup {

    const val SCHEMA = 2

    /**
     * 1.0.0 时代云端只有这一个固定文件（上传即覆盖）。1.0.1 起改为「一设备一份」，
     * 这个常量只用于两件事：识别旧备份以便迁移恢复、目录地址拼装时的兜底。
     */
    const val REMOTE_FILE = "MyPurePlayer-config.mpp"

    /**
     * 扩展名 = 应用名首字母缩写 MPP（MyPurePlayer），不带任何附加字母。
     * 文件身份由首行魔数决定（见 [BackupCrypto]），扩展名只用于人眼识别与文件管理器分类。
     */
    const val DEFAULT_FOLDER = "MyPurePlayer"
    const val EXTENSION = ".mpp"

    @Serializable
    data class Payload(
        val app: String = "MyPurePlayer",
        val schema: Int = SCHEMA,
        val appVersion: String = "",
        val exportedAt: Long = 0L,
        val settings: BackupSettings? = null,
        val smbServers: List<BackupSmbServer> = emptyList(),
        val streams: List<StreamItem> = emptyList(),
        /** 观看历史（最多 300 条） */
        val history: List<HistoryEntry> = emptyList(),
        /** 正在播放队列 */
        val queue: List<VideoItem> = emptyList(),
        /** 用户自建播放列表 */
        val playlists: List<Playlist> = emptyList(),
    )

    @Serializable
    data class BackupSettings(
        val darkMode: Int = 0,
        val dynamicColor: Boolean = false,
        val themePalette: String = "blue",
        val autoLoadSubtitle: Boolean = true,
        val hardwareDecode: Boolean = true,
        val autoPlayNext: Boolean = true,
        val playMode: Int = 1,
        val resumePlayback: Boolean = true,
        val autoCheckUpdate: Boolean = true,
        val pipOnHome: Boolean = true,
        val networkGrid: Boolean = true,
        val networkSort: String = "NAME",
        val networkAscending: Boolean = true,
        val smbBrowserGrid: Boolean = true,
        val smbBrowserSort: String = "NAME",
        val smbBrowserAscending: Boolean = true,
    )

    /** 刻意没有 password 字段：序列化时连密文都不出现，避免"看着像加密"的误解 */
    @Serializable
    data class BackupSmbServer(
        val id: Long,
        val name: String,
        val host: String,
        val username: String = "",
        val domain: String = "",
        val share: String = "",
    )

    data class RestoreResult(
        val sectionCount: Int,
        /** 本机此前也没有密码的 SMB 条目数，需要用户补填 */
        val smbNeedPassword: Int,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 本机备份文件名：精确到秒，避免同一分钟连备两次撞名。 */
    fun suggestedFileName(): String =
        "MyPurePlayer-config-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date()) + EXTENSION

    /** 协议默认端口。WebDAV 没有专属端口，走的就是 HTTP/HTTPS 的 80/443。 */
    fun defaultPort(scheme: String): Int = if (scheme.equals("http", ignoreCase = true)) 80 else 443

    /**
     * 地址 + 端口 → 规范基地址。地址可省略协议（按 https 处理），可带路径；
     * 端口等于协议默认值时不写进 URL，避免拼出 `:443` 这种冗余写法。
     */
    fun baseUrl(rawUrl: String, rawPort: String): String {
        val raw = rawUrl.trim().trimEnd('/')
        if (raw.isEmpty()) return ""
        val withScheme = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            raw
        } else {
            "https://$raw"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return withScheme
        val scheme = uri.scheme?.lowercase() ?: return withScheme
        val host = uri.host ?: return withScheme
        val port = rawPort.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: uri.port.takeIf { it > 0 }
            ?: defaultPort(scheme)
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val portPart = if (port == defaultPort(scheme)) "" else ":$port"
        return "$scheme://$host$portPart$path"
    }

    /**
     * 基地址 + 目录 → 远端文件地址。目录可多级，逐段做百分号编码：
     * 中文目录名直接塞进 URL 会抛异常，这是最容易在真机上出错的一处。
     */
    /**
     * 基地址 + 目录 → 远端目录地址。目录可多级，逐段做百分号编码：
     * 中文目录名直接塞进 URL 会抛异常，这是最容易在真机上出错的一处。
     * 列目录时用这个地址（末尾不带文件名）。
     */
    fun remoteDir(baseUrl: String, folder: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val segments = folder.trim().trim('/').split('/')
            .filter { it.isNotBlank() }
            .map { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return (listOf(base) + segments).joinToString("/")
    }

    /** 基地址 + 目录 + 文件名 → 远端文件地址。默认是旧版单文件名，按设备备份时传入设备文件名。 */
    fun remoteUrl(baseUrl: String, folder: String, fileName: String = REMOTE_FILE): String =
        remoteDir(baseUrl, folder) + "/" + fileName

    suspend fun export(): String {
        // 仓库可能还没被页面打开过（用户从设置页直接备份），先载入再取快照。
        // 历史/自建列表由 Graph.init 启动即载入；这里再 load 一次确保拿到最新落盘状态。
        Graph.servers.load()
        Graph.streams.load()
        Graph.history.load()
        Graph.playlists.load()
        val s = Graph.settings.settings.first()
        val payload = Payload(
            schema = SCHEMA,
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = System.currentTimeMillis(),
            settings = BackupSettings(
                darkMode = s.darkMode,
                dynamicColor = s.dynamicColor,
                themePalette = s.themePalette,
                autoLoadSubtitle = s.autoLoadSubtitle,
                hardwareDecode = s.hardwareDecode,
                autoPlayNext = s.autoPlayNext,
                playMode = s.playMode,
                resumePlayback = s.resumePlayback,
                autoCheckUpdate = s.autoCheckUpdate,
                pipOnHome = s.pipOnHome,
                networkGrid = s.networkGrid,
                networkSort = s.networkSort,
                networkAscending = s.networkAscending,
                smbBrowserGrid = s.smbBrowserGrid,
                smbBrowserSort = s.smbBrowserSort,
                smbBrowserAscending = s.smbBrowserAscending,
            ),
            smbServers = Graph.servers.servers.value.map {
                BackupSmbServer(it.id, it.name, it.host, it.username, it.domain, it.share)
            },
            streams = Graph.streams.streams.value,
            history = Graph.history.history.value,
            queue = Graph.queue.load(),
            playlists = Graph.playlists.playlists.value,
        )
        return json.encodeToString(Payload.serializer(), payload)
    }

    /**
     * 解析备份。加密文件先解密再解析；不带魔数的旧明文 JSON 仍然接受，
     * 免得之前已经导出的文件直接作废（新导出一律加密）。
     */
    fun parse(text: String, password: String): Payload {
        val body = if (BackupCrypto.isEncrypted(text)) BackupCrypto.decrypt(text, password) else text
        val payload = runCatching { json.decodeFromString<Payload>(body) }
            .getOrElse { throw IllegalArgumentException("备份文件已损坏或不是本应用的配置备份") }
        if (payload.app != "MyPurePlayer") throw IllegalArgumentException("这不是 MyPurePlayer 的配置备份")
        if (payload.schema > SCHEMA) throw IllegalArgumentException("备份文件版本较新，请升级应用后再恢复")
        return payload
    }

    suspend fun restore(payload: Payload): RestoreResult {
        var sections = 0
        var missingPwd = 0
        // 备份文件是外部数据（旧版本导出的、手工改过的、跨设备合并过的），
        // 不能假设条目 key 唯一。历史/队列/播放列表在界面里都用 id 当 Lazy 的 key，
        // 重复值会抛 "Key ... was already used" 把整页带走 —— 而队列是落盘的，
        // 重启后照样崩。入库前统一去重，比在每个列表页各兜一层更可靠。
        val queue = payload.queue.distinctBy { it.id }
        val history = payload.history.distinctBy { it.id }
        val playlists = payload.playlists
            .distinctBy { it.id }
            .map { pl -> pl.copy(videos = pl.videos.distinctBy { v -> v.id }) }
        payload.settings?.let {
            Graph.settings.restore(it)
            sections++
        }
        if (payload.smbServers.isNotEmpty()) {
            // 先 load 再按 id / 地址+账号认回本机已有密码：
            // 新设备上没有对应记录，就留空并在结果里报数，UI 再明确提示重填。
            Graph.servers.load()
            val known = Graph.servers.servers.value
            val restored = payload.smbServers.map { b ->
                val local = known.firstOrNull { it.id == b.id }
                    ?: known.firstOrNull {
                        it.host.equals(b.host, ignoreCase = true) && it.username == b.username
                    }
                if (local?.password.isNullOrEmpty()) missingPwd++
                SmbServer(
                    id = b.id, name = b.name, host = b.host,
                    username = b.username,
                    password = local?.password.orEmpty(),
                    domain = b.domain, share = b.share,
                )
            }
            Graph.servers.replaceAll(restored)
            sections++
        }
        if (payload.streams.isNotEmpty()) {
            Graph.streams.replaceAll(payload.streams)
            sections++
        }
        // 内容状态：空列表也照样覆盖——用户可能在备份时就是空的，恢复后理应还原成空，
        // 否则旧设备的数据会残留在恢复后的机器上，与备份快照不一致。
        Graph.history.replaceAll(history)
        sections++
        Graph.queue.save(queue)
        // 只写磁盘不够：Playback 的内存队列才是界面与播放器实际读的那份，必须同步替换
        com.yaoahos.mypureplayer.playback.Playback.setQueue(queue)
        sections++
        Graph.playlists.replaceAll(playlists)
        sections++
        return RestoreResult(sections, missingPwd)
    }
}
