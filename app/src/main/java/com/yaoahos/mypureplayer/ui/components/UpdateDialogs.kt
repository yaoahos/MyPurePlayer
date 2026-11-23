package com.yaoahos.mypureplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yaoahos.mypureplayer.data.UpdateChecker
import com.yaoahos.mypureplayer.data.UpdatePrompt
import com.yaoahos.mypureplayer.data.UpdatePromptData
import com.yaoahos.mypureplayer.data.UpdateResult
import java.util.Locale

/**
 * 把 GitHub 发布说明压成「一条一件事」的短句。
 *
 * 去掉 Markdown 记号、丢掉标题/图片/表格/安装说明/校验值等噪音。
 * 不再限制条目数与单行字数——全部显示，超出弹窗高度的部分交给滚动区。
 */
internal fun conciseNotes(raw: String): List<String> =
    raw.lineSequence()
        .map { it.trim() }
        .map { line ->
            line.removePrefix("- ")
                .removePrefix("* ")
                .removePrefix("+ ")
                .removePrefix("• ")
                .removePrefix("· ")
                .replace("**", "")
                .replace("`", "")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .filterNot { line -> NOISE_PATTERNS.any { line.contains(it, ignoreCase = true) } }
        .filterNot { line ->
            line.startsWith("#") ||
                line.startsWith(">") ||
                line.startsWith("|") ||
                line.startsWith("![") ||
                line.startsWith("---") ||
                line.startsWith("http://") ||
                line.startsWith("https://") ||
                line.startsWith("大小：") ||
                line.startsWith("大小:") ||
                line.startsWith("支持系统") ||
                line.startsWith("certutil")
        }
        .filterNot { DATE_ONLY.matches(it) || HEADING_LINE.matches(it) }
        .toList()

/** 发布信息类关键词：这些内容不属于功能介绍或问题修复 */
private val NOISE_PATTERNS = listOf(
    "包名", "versionCode", "versionName", "版本号", "系统要求", "适用系统", "适用版本",
    "安装说明", "安装包", "下载地址", "文件大小", "SHA", "MD5", "签名", "校验值", "更新于",
    "Full Changelog", "What's Changed", "Contributors",
)

private val DATE_ONLY = Regex("""\d{4}[/-]\d{1,2}[/-]\d{1,2}""")
private val HEADING_LINE = Regex("""^\d{4}[/-]\d{1,2}[/-]\d{1,2}\s*[·\-—]?\s*v?\d+(?:\.\d+)*$""")

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "未知"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) String.format(Locale.CHINA, "%.1f MB", mb)
    else String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
}

/**
 * 更新提示对话框：发现新版本 / 本次更新内容共用一套版式。
 *
 * 与 SafePassword 对齐的三处：
 *  1) 更新日志先清洗再列表化（见 [conciseNotes]），不再原样贴 Markdown；
 *  2) 标题下显示安装包大小，点下载前就知道要下多少流量；
 *  3) 下载过程有进度条 + 百分比，且弹窗可以关掉（下载在进程级进行，通知栏同步显示）。
 */
@Composable
fun UpdateAvailableDialog(
    title: String,
    versionLine: String,
    notes: String,
    sizeBytes: Long,
    downloading: Boolean,
    progress: Int,
    onDismiss: () -> Unit,
    confirmLabel: String?,
    onConfirm: (() -> Unit)?,
    dismissLabel: String = "稍后",
    /** 下载 / 安装失败时就地显示在弹窗内；不往页面顶部发提示，那层会被弹窗盖住 */
    errorText: String? = null,
) {
    val bullets = remember(notes) { conciseNotes(notes) }
    val logScroll = rememberScrollState()
    // 底部渐隐：内容溢出且尚未滚到底时显示，暗示下方还有内容；滚到底自动消失
    val showFade by remember {
        derivedStateOf { logScroll.maxValue > 0 && logScroll.value < logScroll.maxValue - 8 }
    }
    UpdateGlassDialog(title = title, onDismiss = onDismiss) {
        Text(
            versionLine +
                if (sizeBytes > 0) " · 安装包 ${formatBytes(sizeBytes)}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Box {
            Column(
                Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(logScroll),
            ) {
                if (bullets.isEmpty()) {
                    Text("本次更新主要是问题修复与稳定性优化。", style = MaterialTheme.typography.bodySmall)
                } else {
                    bullets.forEach { line ->
                        Text("· $line", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }
            if (showFade) {
                // 底部渐隐遮罩：从透明过渡到面板底色，比滚动条柔和；
                // 盖在日志区下沿、match 面板背景，暗示"下方还有内容，继续滑"。
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                            ),
                        ),
                )
            }
        }
        if (downloading) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(
                    if (progress <= 0) "正在准备下载…" else "正在下载 $progress%",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(6.dp))
            // 进度条比纯文字百分比更好读：下载 30MB 的包，光看数字没有"还剩多少"的量感
            LinearProgressIndicator(
                progress = { (progress.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "可以关掉这个窗口，下载会在后台继续，进度在通知栏查看。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!errorText.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                errorText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
            if (confirmLabel != null && onConfirm != null) {
                TextButton(onClick = onConfirm, enabled = !downloading) { Text(confirmLabel) }
            }
        }
    }
}

/**
 * 下载完成后的安装确认。
 *
 * 与 SafePassword 一致：下载完不直接拉系统安装器，先问一句。
 * 直接拉起的话，用户在别的界面、或者此刻不方便安装，就会看到一个莫名其妙的系统弹窗；
 * 而他点"稍后"之后，通知栏那条"已下载"仍然在，随时能回来装。
 */
@Composable
fun UpdateReadyDialog(
    version: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    UpdateGlassDialog(
        title = if (version.isBlank()) "更新已下载" else "$version 已下载完成",
        onDismiss = onDismiss,
    ) {
        Text(
            "是否立即安装新版本？安装前请允许本应用安装未知来源应用。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("稍后") }
            TextButton(onClick = onInstall) { Text("立即安装") }
        }
    }
}

/**
 * 更新弹窗的统一玻璃外壳。
 *
 * 之前更新提示一直是 M3 AlertDialog + panelStrong（近乎实色），与全站玻璃材质脱节。
 * 这里和 ConfirmDialog 走同一条路：有应用内浮层宿主就画在宿主里（不开新窗口，
 * 省掉真机实测 50~65ms 的开窗首帧），否则退回独立 Dialog 窗口；面板统一用
 * [appDialogSurface] 首页渐变玻璃，标题与正文由调用方填。
 */
@Composable
private fun UpdateGlassDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val host = LocalAppOverlayHost.current
    if (host != null) {
        AppOverlaySlot { UpdateDialogBody(title, onDismiss, content) }
        return
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        UpdateDialogBody(title, onDismiss, content)
    }
}

/** 玻璃面板本体：遮罩 + 居中渐变卡片 + 140ms 淡入微缩放，与确认框同一套版式 */
@Composable
private fun UpdateDialogBody(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val enter = remember { Animatable(0f) }
    val dismiss = rememberUpdatedState(onDismiss)
    BackHandler { dismiss.value() }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(140, easing = FastOutSlowInEasing))
    }
    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(UPDATE_SCRIM.copy(alpha = UPDATE_SCRIM.alpha * enter.value)) }
                .pointerInput(Unit) { detectTapGestures { dismiss.value() } },
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
                .widthIn(min = 300.dp, max = PageMetrics.DialogMaxWidth)
                .fillMaxWidth()
                .heightIn(max = (windowHeight - 48.dp).coerceAtLeast(240.dp))
                .verticalScroll(rememberScrollState())
                .graphicsLayer {
                    alpha = enter.value
                    scaleX = 0.94f + 0.06f * enter.value
                    scaleY = 0.94f + 0.06f * enter.value
                }
                .appDialogSurface(RoundedCornerShape(26.dp), elevation = 8.dp)
                .pointerInput(Unit) { detectTapGestures { } } // 吞掉点击，避免穿透到遮罩
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

private val UPDATE_SCRIM = Color.Black.copy(alpha = 0.32f)

/**
 * 放在界面根部的宿主：消费 [UpdatePrompt] 里的状态。
 *
 * 因为 Application 里没有窗口，检查更新的结果只能由这里统一弹窗，
 * 这样无论用户停在哪个页面（本地 / 网络 / 播放 / 更多）都能看到。
 */
@Composable
fun UpdatePromptHost() {
    val context = LocalContext.current
    val pending by UpdatePrompt.state.collectAsStateWithLifecycle()
    val download by UpdatePrompt.download.collectAsStateWithLifecycle()
    val data = pending ?: return

    // 已下载完：优先弹"立即安装"，不再弹"发现新版本"
    val apkPath = download?.takeIf { !it.running }?.apkPath
    if (apkPath != null) {
        UpdateReadyDialog(
            version = data.version,
            onInstall = { UpdatePrompt.installDownloaded(context) },
            onDismiss = {
                UpdatePrompt.dismissDownload()
                UpdatePrompt.dismiss()
            },
        )
        return
    }

    val downloading = download?.running == true
    val progress = download?.progress ?: 0

    fun openUrl(url: String) {
        if (url.isBlank() || !UpdateChecker.openPage(context, url)) {
            Notice.error("没有可以打开浏览器的应用")
        }
    }

    when (data.kind) {
        UpdatePromptData.Kind.NEW_VERSION -> UpdateAvailableDialog(
            title = "发现新版本 ${data.version}",
            versionLine = "当前版本 ${data.current}" +
                if (data.publishedAt.isBlank()) "" else " · 发布于 ${data.publishedAt}",
            notes = data.notes,
            sizeBytes = data.size,
            downloading = downloading,
            progress = progress,
            // 下载中也能关：任务挂在进程级作用域上，关掉后进度去通知栏
            onDismiss = { UpdatePrompt.dismiss() },
            confirmLabel = "更新",
            onConfirm = {
                if (data.apkUrl == null) {
                    openUrl(data.pageUrl)
                    UpdatePrompt.dismiss()
                } else if (!downloading) {
                    UpdatePrompt.startDownload(context)
                }
            },
            errorText = download?.error,
        )

        UpdatePromptData.Kind.WHATS_NEW -> UpdateAvailableDialog(
            title = "已更新到 ${data.version}",
            versionLine = "以下是本次更新的主要内容",
            notes = data.notes,
            sizeBytes = 0L,
            downloading = false,
            progress = 0,
            onDismiss = { UpdatePrompt.dismiss() },
            confirmLabel = "知道了",
            onConfirm = { UpdatePrompt.dismiss() },
            dismissLabel = "关闭",
            errorText = null,
        )
    }
}

/** 供关于页复用的「发现新版本」弹窗，保持全局与页面内提示一致 */
@Composable
fun UpdateResultDialog(
    result: UpdateResult,
    downloading: Boolean,
    progress: Int,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    /** 下载失败就地显示；与全局 UpdatePromptHost 的弹窗保持一致 */
    errorText: String? = null,
) {
    UpdateAvailableDialog(
        title = "发现新版本 ${result.latest.version}",
        versionLine = "当前版本 ${result.current}" +
            if (result.latest.publishedAt.isBlank()) "" else " · 发布于 ${result.latest.publishedAt}",
        notes = result.latest.notes,
        sizeBytes = result.latest.size,
        downloading = downloading,
        progress = progress,
        onDismiss = onDismiss,
        confirmLabel = "更新",
        onConfirm = onDownload,
        errorText = errorText,
    )
}
