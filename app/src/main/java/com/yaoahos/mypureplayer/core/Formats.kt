package com.yaoahos.mypureplayer.core

import java.util.Locale

/**
 * 文件类型白名单与格式化工具。
 *
 * 设计原则：只认「视频」与「字幕」两类。网络共享目录里其它任何文件（图片、文档、压缩包、
 * 音频……）都不会被识别、不会出现在界面上，也无法被加入播放列表。
 */
object Formats {

    /**
     * 视频扩展名白名单。
     *
     * 沿用 libVLC 时代的全集（VLC 能播 wmv / rmvb / mxf 等），而现在的播放内核是 Media3，
     * 其中 wmv / rmvb / mxf / dv / amv 与裸流（h264 / hevc / vp9 / av1）没有对应提取器，
     * 会以「封装格式不受支持」失败。保留它们是为了让文件在界面上可见、可管理，
     * 并不表示一定能播。
     */
    val VIDEO_EXTS: Set<String> = hashSetOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "f4v",
        "ts", "m2ts", "mts", "m2t", "mpg", "mpeg", "mpe", "m2v", "m1v",
        "vob", "ogv", "ogm", "3gp", "3g2", "asf", "rm", "rmvb", "divx",
        "xvid", "dat", "mxf", "dv", "tp", "trp", "amv", "mj2", "mjpeg",
        "h264", "h265", "hevc", "vp9", "av1", "f4p", "wtv", "dvr-ms",
    )

    /**
     * 字幕扩展名白名单（用于发现同目录字幕文件、SMB 目录里的字幕）。
     *
     * 只列播放内核真正能渲染的四种：SRT / ASS / SSA / VTT。
     * 此前把 sub / smi / idx / ttml / sbv / sup / pgs 也列进来，点击后会以 TEXT_SSA
     * 交给解析器，多数解析不出内容——用户看到的是「选了字幕但屏幕上一片空白」，
     * 却不知道是格式不支持。宁可不出现在列表里，也不要给一个注定失败的入口。
     */
    val SUBTITLE_EXTS: Set<String> = hashSetOf("srt", "ass", "ssa", "vtt")

    fun extensionOf(name: String): String {
        val idx = name.lastIndexOf('.')
        if (idx <= 0 || idx == name.length - 1) return ""
        return name.substring(idx + 1).lowercase(Locale.ROOT)
    }

    fun isVideo(name: String): Boolean = extensionOf(name) in VIDEO_EXTS

    fun isSubtitle(name: String): Boolean = extensionOf(name) in SUBTITLE_EXTS

    /** 去掉扩展名，作为展示标题 */
    fun stripExtension(name: String): String {
        val idx = name.lastIndexOf('.')
        return if (idx > 0) name.substring(0, idx) else name
    }

    fun baseName(path: String): String {
        val clean = path.trimEnd('/', '\\')
        val idx = maxOf(clean.lastIndexOf('/'), clean.lastIndexOf('\\'))
        return if (idx >= 0) clean.substring(idx + 1) else clean
    }

    /**
     * 是否是同一部影片的字幕。
     *
     * 判定放宽为「从第一个字符开始的前缀匹配」：视频 `abcd.mp4` 与字幕
     * `abcd123.srt` / `abcd.chs.srt` / `abcd_2.srt` 都算同名，而 `aabcd123.srt`
     * 因为首字符就对不上，不算。以前要求「片名 + 分隔符」才算，导致
     * `abcd123.srt` 这类「片名直接接序号」的字幕在列表里根本不出现。
     */
    fun matchesVideo(videoName: String, subtitleName: String): Boolean {
        val v = stripExtension(videoName).lowercase(Locale.ROOT)
        val s = stripExtension(subtitleName).lowercase(Locale.ROOT)
        if (v.isEmpty()) return false
        return s.startsWith(v)
    }

    /**
     * 自动加载用的判定：与 [matchesVideo] 同一套前缀规则，避免出现
     * 「字幕列表里看得到、却不会自动挂上」的不一致。
     */
    fun matchesVideoStrict(videoName: String, subtitleName: String): Boolean =
        matchesVideo(videoName, subtitleName)

    /**
     * 候选字幕的优先级：完全同名 > 中文（chs / chi / zh） > 数字后缀（1 / 2 / 3） > 其它。
     * 数字越小越优先，用来在同目录多个「同名」字幕里挑一个自动挂上。
     */
    fun subtitlePriority(videoName: String, subtitleName: String): Int {
        val base = stripExtension(subtitleName).lowercase(Locale.ROOT)
        val video = stripExtension(videoName).lowercase(Locale.ROOT)
        if (base == video) return 0
        if (listOf("chs", "chi", "zh", "zhcn", "sc", "simplified").any { base.endsWith(".$it") }) return 1
        val tail = base.removePrefix(video).trim('.' , '_', '-')
        return if (tail.toIntOrNull() != null) 2 else 3
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024
            i++
        }
        return if (i == 0) "${bytes} B" else String.format(Locale.ROOT, "%.1f %s", value, units[i])
    }

    /** 相对时间，用于历史列表 */
    fun formatAgo(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - timestamp).coerceAtLeast(0L)
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff < minute -> "刚刚"
            diff < hour -> "${diff / minute} 分钟前"
            diff < day -> "${diff / hour} 小时前"
            diff < 30 * day -> "${diff / day} 天前"
            else -> {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                fmt.format(java.util.Date(timestamp))
            }
        }
    }

    /**
     * 视频文件的 MIME 类型（全项目唯一映射表：本地分享与本地流服务器共用）。
     *
     * fallback：HTTP 服务给未知封装回 application/octet-stream（非法 Content-Type
     * 会让部分播放器拒绝）；系统分享面板回 video 通配（让接收方按视频处理）。
     */
    fun videoMimeOf(fileName: String, fallback: String = "application/octet-stream"): String =
        when (extensionOf(fileName)) {
            "mp4", "m4v", "mj2", "mjpeg", "h264", "h265", "hevc" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi", "divx", "xvid" -> "video/x-msvideo"
            "ts", "m2ts", "mts", "m2t", "tp", "trp" -> "video/mp2t"
            "flv", "f4v", "f4p" -> "video/x-flv"
            "3gp", "3g2" -> "video/3gpp"
            "wmv", "asf", "wtv", "dvr-ms" -> "video/x-ms-wmv"
            "rm", "rmvb" -> "video/x-pn-realvideo"
            "mpg", "mpeg", "mpe", "m2v", "m1v", "vob", "dat" -> "video/mpeg"
            "ogv", "ogm" -> "video/ogg"
            "mxf" -> "application/mxf"
            else -> fallback
        }
}
