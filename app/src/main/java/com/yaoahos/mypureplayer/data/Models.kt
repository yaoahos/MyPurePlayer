package com.yaoahos.mypureplayer.data

import kotlinx.serialization.Serializable

/** 媒体来源类型 */
@Serializable
enum class MediaSource { LOCAL, SMB, STREAM }

/**
 * 统一的视频条目：本地文件、SMB 共享文件、串流都归一到这一个结构。
 * [id] 在同一来源内稳定唯一，用于列表 key / 缩略图缓存 / 历史记录。
 */
@Serializable
data class VideoItem(
    val id: String,
    val title: String,
    val fileName: String,
    /** 播放地址：本地为 content:// 或 file://，SMB 为不含口令的 smb://，串流为原始 URL */
    val uri: String,
    val source: MediaSource = MediaSource.LOCAL,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val dateModified: Long = 0L,
    /** 展示用所在文件夹名 */
    val folderName: String = "",
    /** 分组键：本地为 bucketId，SMB 为目录地址 */
    val folderKey: String = "",
    val width: Int = 0,
    val height: Int = 0,
    /** SMB 来源时关联的服务器 id */
    val serverId: Long = 0L,
    /** 本地文件的真实路径（用于找同目录字幕）；content:// 场景下可能为 null */
    val localPath: String? = null,
) {}

/**
 * 用户自建播放列表：与「正在播放」队列相互独立，可长期保存、反复取用。
 * [videos] 只存条目的快照元数据，加入时即固定，不随媒体库变动自动增删。
 */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val videos: List<VideoItem> = emptyList(),
    val createdAt: Long = 0L,
    /** 0 = 未置顶；>0 为置顶时间戳（越大越靠前）。「正在播放」始终固定在其上方。 */
    val pinnedAt: Long = 0L,
) {}

/** SMB 服务器配置 */
@Serializable
data class SmbServer(
    val id: Long,
    val name: String,
    val host: String,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val share: String = "",
) {
    /** 进入这个共享位置时打开哪一层：填了默认共享目录就直接进它，否则先列服务器上的共享 */
    val rootPath: String
        get() {
            // host 里可能残留 smb:// 前缀、反斜杠或端口（老配置 / 手动输入），这里再兜一层
            val parsed = SmbInput.parse(host)
            val cleanShare = (share.ifBlank { parsed.share }).trim().trim('/')
            return if (cleanShare.isNotBlank()) {
                "smb://${parsed.host}/$cleanShare/"
            } else {
                "smb://${parsed.host}/"
            }
        }
    val displayHost: String get() = if (share.isNotBlank()) "$host/$share" else host
}

/** 串流地址 */
@Serializable
data class StreamItem(
    val id: Long,
    val name: String,
    val url: String,
)

/** 观看历史 */
@Serializable
data class HistoryEntry(
    val id: Long,
    val videoId: String,
    val title: String,
    val uri: String,
    val source: MediaSource,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val folderKey: String = "",
    val serverId: Long = 0L,
    val fileName: String = "",
    // 详情弹窗要显示的信息：历史里必须自带，视频后来被删/改名时才不会显示成空白
    val sizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val folderName: String = "",
    val dateModified: Long = 0L,
)

/** 播放器 UI 状态 */
data class PlayerUiState(
    val item: VideoItem? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    /**
     * 起播准备中：从「开始播放某个媒体」到「首帧渲染出来或出错」的整个等待期。
     *
     * 与 [buffering] 的区别：buffering 跟随播放器的 STATE_BUFFERING/IDLE 等状态回调，
     * 而 setMediaItem() 会立刻触发一次 STATE_IDLE，把刚置起的 buffering 又清掉，
     * 出现「转圈闪一下就没了、黑屏等半天」的空窗。preparing 不受这些中间状态影响，
     * 从 startAt 一直保持到首帧，保证任何等待都有加载提示。
     */
    val preparing: Boolean = false,
    /**
     * 播放位置。**只反映最近一次 setPosition() 的值**，播放中每 200ms 的进度轮询
     * 只更新 `Playback.positionMs` 这条独立 flow（避免整页按 200ms 重组），
     * 所以界面上显示实时进度请读 `Playback.positionMs`，不要读这个字段。
     */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val ended: Boolean = false,
    val error: String? = null,
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
    val scaleMode: Int = 0,
    val subtitlesEnabled: Boolean = true,
    val activeSubtitleId: Int = -1,
    val autoSubtitleName: String? = null,
    val hasSubtitles: Boolean = false,
    /** 当前播放倍速（1.0 = 原速） */
    val playbackRate: Float = 1f,
    /** 解码出来的真实视频尺寸，用于按画面比例自动旋转 */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** 当前是否硬件解码（播放器右上角可一键切换，与设置页联动） */
    val hardwareDecode: Boolean = true,
    /** 后台音频播放模式：已卸载视频画面，只保留声音 */
    val backgroundAudio: Boolean = false,
    /** 当前投屏设备名，空串表示本机播放 */
    val rendererName: String = "",
    /**
     * 视频动态范围标签：SDR / HLG / HDR10 / HDR10+ / 杜比视界。
     *
     * 取自 Media3 提取器写进 `Format.colorInfo` 的传递函数，不是从文件名猜的。
     * 空串表示还没解析出来（起播前）。
     */
    val dynamicRange: String = "",
    /**
     * 当前音轨的实际输出方式：`TrueHD 5.1 · 直通` / `AC3 5.1 · 解码`。
     *
     * 「直通」= 位流原样交给系统输出（接 HDMI 功放时才有），对象音频（Atmos）
     * 在直通下才能完整保留；「解码」= Media3/FFmpeg 解成 PCM 再输出。
     */
    val audioOutput: String = "",
    /**
     * 播放内核（libvlc 原生库）不可用。
     * 内核按需下载时，用户尚未安装内核就会进入这个状态；此时不能崩溃，
     * 只能把播放能力关掉并提示，列表/缩略图等不依赖 native 的功能照常可用。
     */
    val engineMissing: Boolean = false,
)

/** 字幕轨道 */
data class SubtitleTrack(
    val id: Int,
    val name: String,
    val selected: Boolean,
    /** 当前设备与解码器能否播放这条轨；false 时列表里仍显示，但标注并禁用点击 */
    val supported: Boolean = true,
)

/** 同目录下的字幕候选文件 */
data class SubtitleFile(
    val name: String,
    val uri: String,
    val sizeBytes: Long = 0L,
)

/** SMB 目录项（仅目录与视频/字幕，其它类型在扫描阶段就被过滤掉） */
data class SmbEntry(
    val name: String,
    val url: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
)

/** 回收站条目：文件已被移动到应用私有的回收站目录，原路径用于恢复 */
@Serializable
data class TrashEntry(
    val id: Long,
    val fileName: String,
    val originalPath: String,
    val originalFolderName: String = "",
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val deletedAt: Long,
    /** 在回收站目录内的实际文件名（可能加了时间前缀避免重名） */
    val storedName: String,
    /** 删除的是一个文件夹（整目录搬走、整目录恢复） */
    val isFolder: Boolean = false,
    /** 文件夹里当时有几个视频，仅用于展示 */
    val fileCount: Int = 0,
)

/** 保险箱条目：文件已加密存放在应用私有目录 */
@Serializable
data class VaultEntry(
    val id: Long,
    val title: String,
    val fileName: String,
    val originalPath: String,
    val originalFolderName: String = "",
    /**
     * 只有「整个文件夹」被加入保险箱时才非空：值是原文件夹的绝对路径，用作保险箱内的分组键。
     *
     * 单独加入的视频必须留空 —— 否则保险箱会按它的**原目录**自动长出一个文件夹，
     * 用户看到的就是「明明只加了一个视频，里面却多出一个上层文件夹」。
     * 默认值让旧数据（没有这个字段）自然落到根目录，不会凭空分组。
     */
    val groupKey: String = "",
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val addedAt: Long,
    /** 加密后的存储文件名 */
    val storedName: String,
)

/** 保险箱门禁配置 */
@Serializable
data class VaultConfig(
    /** PBKDF2 派生的 PIN 校验值（十六进制） */
    val pinHash: String = "",
    /** 随机盐（十六进制） */
    val salt: String = "",
    /** PIN 的明文摘要提示，仅用于判断是否已设置 */
    val enabled: Boolean = false,
    /** 是否允许指纹快捷解锁 */
    val biometric: Boolean = false,
    /** 数据密钥密文：v3 为 "V3:..."（PIN + Keystore 双因子），旧值为纯 Base64（仅 Keystore） */
    val wrappedKey: String = "",
    /** 指纹解锁用的数据密钥密文（"BIO:" 前缀，由生物识别 Keystore 密钥包裹） */
    val bioWrappedKey: String = "",
    /** 连续输错 PIN 的次数，用于指数退避 */
    val failedAttempts: Int = 0,
    /** 锁定截止时间（毫秒时间戳），在此之前拒绝校验 */
    val lockedUntil: Long = 0L,
)

/** 排序字段 */
enum class SortKey { NAME, DATE, DURATION, SIZE }
