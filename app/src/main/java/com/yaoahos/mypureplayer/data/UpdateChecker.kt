package com.yaoahos.mypureplayer.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** 一次检查更新的结果 */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val publishedAt: String,
    /** 安装包字节数，0 表示接口没给（旧版更新不带大小） */
    val size: Long = 0L,
)

data class UpdateResult(
    val current: String,
    val latest: UpdateInfo,
) {
    val hasUpdate: Boolean get() = UpdateChecker.compareVersion(latest.version, current) > 0
}

/**
 * 检查更新：读 GitHub Releases 的最新一版，和本地 versionName 比大小。
 *
 * 安全检查：
 * 1. 所有 URL 必须是 HTTPS，host 要么精确命中白名单、要么落在 GitHub CDN 域
 *    （`*.githubusercontent.com`）下；不用字符串 contains，避免
 *    `https://evil.com/?github.com` 这类绕过；
 * 2. 下载完成的 APK 会校验包名、版本，且签名证书必须命中硬编码的官方证书指纹
 *    pin（release 私有签名），防止下载链路被替换成第三方/冒名包。
 */
object UpdateChecker {

    /** 项目主页；关于页图标、标题、检查更新一律指向这里 */
    const val PROJECT_URL = "https://github.com/yaoahos/MyPurePlayer"
    const val DEFAULT_REPO = "yaoahos/MyPurePlayer"

    private const val USER_AGENT = "MyPurePlayer-Android"
    private const val MIN_APK_BYTES = 512L * 1024L
    private const val MAX_REDIRECTS = 5

    /**
     * 官方 release 证书的 SHA-256 指纹（pin）。更新包必须由此证书签发，
     * 不再采用「与当前包签名一致」的弱策略——后者在 debug 签名公开时可被冒名安装。
     * 指纹随应用内置，由发布证书提取，不依赖运行时签名比对。
     */
    private const val OFFICIAL_CERT_SHA256 =
        "B13E014387B073F1934216F0B59A3245E0865290B104E94B19409250AAE6091D"
    /** 官方 release 包名（debug 构建带 .debug 后缀，不作为更新目标） */
    private const val OFFICIAL_PACKAGE = "com.yaoahos.mypureplayer"

    private val allowedHosts = setOf("github.com", "api.github.com", "codeload.github.com")

    /**
     * GitHub 的 release 资源 CDN 域。Downloads 会先 302 到
     * `release-assets.githubusercontent.com`（早期是 objects. / github-releases.，
     * 由 GitHub 分配、会随 CDN 调整），所以按 `*.githubusercontent.com` 后缀放行，
     * 而不是逐个写死——写死会在 CDN 改版后整条更新链路失效。
     * 前导点必须保留：避免 `evilgithubusercontent.com` 这类伪装域命中。
     */
    private const val CDN_HOST_SUFFIX = ".githubusercontent.com"

    /** 项目主页地址：始终指向固定项目，不受设置里遗留的仓库名影响 */
    fun projectUrl(): String = PROJECT_URL

    suspend fun check(repo: String, currentVersion: String): Result<UpdateResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 更新源固定为项目仓库，避免历史版本写入的其它仓库名导致检查失败
                val api = "https://api.github.com/repos/$DEFAULT_REPO/releases/latest"
                if (!isAllowedUrl(api)) throw IllegalStateException("更新地址不在允许范围内")
                val json = httpGet(api)
                val obj = JSONObject(json)
                val tag = obj.optString("tag_name").ifBlank { obj.optString("name") }
                val version = normalizeVersion(tag)

                val assets = obj.optJSONArray("assets")
                val apkAsset = (0 until (assets?.length() ?: 0))
                    .mapNotNull { assets?.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                val apk = apkAsset
                    ?.optString("browser_download_url")
                    ?.takeIf { url -> isAllowedUrl(url) }

                val page = obj.optString("html_url")
                    .takeIf { it.isNotBlank() && isAllowedUrl(it) }
                    ?: PROJECT_URL

                UpdateResult(
                    current = currentVersion,
                    latest = UpdateInfo(
                        version = version,
                        notes = obj.optString("body").trim(),
                        pageUrl = page,
                        apkUrl = apk,
                        publishedAt = localDate(obj.optString("published_at")),
                        // 弹窗里显示"安装包大小"，让用户在点下载前知道要下多少流量
                        size = apkAsset?.optLong("size", 0L) ?: 0L,
                    ),
                )
            }
        }

    /** 下载安装包到应用缓存目录，返回本地文件 */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = info.apkUrl ?: throw IllegalStateException("这个版本没有提供安装包，请打开发布页手动下载")
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "MyPurePlayer-${info.version}.apk")

                // GitHub 下载地址固定会 302 到 CDN。不能用 instanceFollowRedirects=true：
                // 那样只校验首尾，中间跳到白名单外的域名会被静默放行。这里逐跳校验，最多 5 跳。
                var current = url
                var active: HttpURLConnection? = null
                try {
                    for (hop in 0 until MAX_REDIRECTS) {
                        if (!isAllowedUrl(current)) throw IllegalStateException("下载地址不在允许范围内")
                        val conn = (URI.create(current).toURL().openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15_000
                            readTimeout = 30_000
                            instanceFollowRedirects = false
                            setRequestProperty("User-Agent", USER_AGENT)
                            setRequestProperty("Accept", "application/octet-stream")
                        }
                        val code = try {
                            conn.responseCode
                        } catch (t: Throwable) {
                            conn.disconnect()
                            throw t
                        }
                        if (code in 300..399) {
                            val location = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (location.isNullOrBlank()) throw IllegalStateException("下载地址跳转无效")
                            if (hop == MAX_REDIRECTS - 1) throw IllegalStateException("下载地址跳转次数过多")
                            // 相对跳转按当前地址解析成绝对地址后再校验
                            current = URI.create(current).resolve(location).toString()
                            continue
                        }
                        if (code !in 200..299) {
                            conn.disconnect()
                            throw IllegalStateException("下载失败（HTTP $code）")
                        }
                        active = conn
                        break
                    }
                    val conn = active ?: throw IllegalStateException("下载地址跳转次数过多")
                    try {
                        val total = conn.contentLengthLong
                        if (total in 1 until MIN_APK_BYTES) throw IllegalStateException("下载到的文件不完整")

                        conn.inputStream.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var written = 0L
                                var lastPercent = -1
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    written += read
                                    if (total > 0) {
                                        val percent = (written * 100 / total).toInt().coerceIn(0, 100)
                                        if (percent != lastPercent) {
                                            lastPercent = percent
                                            onProgress(percent)
                                        }
                                    }
                                }
                                output.flush()
                            }
                        }
                        if (target.length() < MIN_APK_BYTES) throw IllegalStateException("下载到的安装包不完整")
                        // APK 本质就是 ZIP，先查头两字节是不是 PK——避免把 CDN 错误页当包存下来
                        if (!looksLikeZip(target)) throw IllegalStateException("下载到的文件内容异常，已取消")
                        verifyApk(context, target)
                        onProgress(100)
                        target
                    } finally {
                        conn.disconnect()
                    }
                } catch (t: Throwable) {
                    runCatching { target.delete() }
                    throw t
                }
            }
        }

    private fun looksLikeZip(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    /** 交给系统安装器安装；返回 false 表示没能唤起安装界面 */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun openPage(context: Context, url: String): Boolean = runCatching {
        val target = url.takeIf { isAllowedUrl(it) } ?: PROJECT_URL
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    /** 校验 APK：合法安装包 + 官方包名 + 官方证书指纹 pin，杜绝冒名更新 */
    private fun verifyApk(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val downloaded = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IllegalStateException("下载的文件不是有效的安装包")

        // 包名必须是官方 release 包（debug 包不会被当作更新目标安装）
        if (downloaded.packageName != OFFICIAL_PACKAGE) {
            throw IllegalStateException("安装包包名不匹配，已拒绝安装")
        }
        // versionCode 必须为正数且不低于当前版本，防止被替换成旧包
        @Suppress("DEPRECATION")
        val currentCode = runCatching { pm.getPackageInfo(context.packageName, 0).versionCode }.getOrDefault(0)
        @Suppress("DEPRECATION")
        val downloadedCode = downloaded.versionCode
        if (downloadedCode <= 0 || downloadedCode < currentCode) {
            throw IllegalStateException("安装包版本异常，已拒绝安装")
        }

        val downloadedSigs = signaturesOf(downloaded)
        if (downloadedSigs.isNullOrEmpty()) {
            throw IllegalStateException("安装包没有签名，已拒绝安装")
        }
        val md = MessageDigest.getInstance("SHA-256")
        val signedByOfficial = downloadedSigs.any {
            hex(md.digest(it.toByteArray())).equals(OFFICIAL_CERT_SHA256, ignoreCase = true)
        }
        if (!signedByOfficial) {
            throw IllegalStateException("安装包证书与官方签名不一致，已拒绝安装")
        }
    }

    @Suppress("DEPRECATION")
    private fun signaturesOf(info: PackageInfo): Array<Signature>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val history = info.signingInfo?.signingCertificateHistory
            if (!history.isNullOrEmpty()) return history
        }
        return info.signatures
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** HTTPS + host 精确匹配或 GitHub CDN 后缀，杜绝字符串包含式白名单的绕过 */
    private fun isAllowedUrl(raw: String): Boolean = runCatching {
        val uri = URI.create(raw)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "https") return false
        if (allowedHosts.any { host == it || host.endsWith(".$it") }) return true
        // CDN 域必须带子域且带前导点：host 本身要比后缀长，githubusercontent.com 不算。
        host.length > CDN_HOST_SUFFIX.length && host.endsWith(CDN_HOST_SUFFIX)
    }.getOrDefault(false)

    private fun httpGet(url: String): String {
        if (!isAllowedUrl(url)) throw IllegalStateException("检查更新的地址不在允许范围内")
        val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code == 404) throw IllegalStateException("这个项目还没有发布版本，请稍后再来")
            if (code == 403 || code == 429) throw IllegalStateException("请求被 GitHub 限流，请稍后再试")
            if (code !in 200..299) throw IllegalStateException("检查更新失败（HTTP $code）")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * `published_at` 是 UTC 的 ISO 时间（如 `2026-11-19T16:24:30Z`），直接取前 10 位
     * 会显示成 UTC 日期——东八区凌晨发布时比本地日期早一天，与更新日志里写的对不上。
     * 按本机时区换算后再格式化；解析失败退回原始截取。
     */
    internal fun localDate(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            java.time.Instant.parse(iso)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        }.getOrElse { iso.take(10).replace('-', '/') }
    }

    /** v1.2.3 / 1.2.3-beta -> 1.2.3 */
    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    /** 逐段比较版本号；a > b 返回正数 */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.', '-', '+')
        val pb = b.split('.', '-', '+')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull()
            val y = pb.getOrNull(i)?.toIntOrNull()
            when {
                x == null && y == null -> {
                    val c = (pa.getOrNull(i) ?: "").compareTo(pb.getOrNull(i) ?: "")
                    if (c != 0) return c
                }
                x == null -> return -1
                y == null -> return 1
                x != y -> return x - y
            }
        }
        return 0
    }
}
