package com.yaoahos.mypureplayer.ui.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.BuildConfig
import com.yaoahos.mypureplayer.R
import com.yaoahos.mypureplayer.data.AppSettings
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.UpdateChecker
import com.yaoahos.mypureplayer.data.UpdateLog
import com.yaoahos.mypureplayer.data.UpdateResult
import com.yaoahos.mypureplayer.ui.components.isDarkScheme
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.glassPressable
import com.yaoahos.mypureplayer.ui.components.glassToggleable
import com.yaoahos.mypureplayer.ui.components.GlassButton
import com.yaoahos.mypureplayer.ui.components.GlassPanelDialog
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.SectionLabel
import com.yaoahos.mypureplayer.ui.components.appGlassSurface
import com.yaoahos.mypureplayer.ui.components.appGlass
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.UpdateResultDialog
import com.yaoahos.mypureplayer.ui.components.UpdateReadyDialog
import com.yaoahos.mypureplayer.data.UpdatePrompt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    // 项目地址固定指向项目主页，图标与标题都可点开
    val projectUrl = UpdateChecker.projectUrl()

    // 「更新」属于关于页：版本号在这里，检查更新与更新日志自然跟着版本走。
    // （曾短暂挪到更多页，那是把「操作」和「说明」拆开了，反而找不到；
    //  更多页现在是纯入口列表，不再承担更新。）
    val releaseNotes = remember(context) { UpdateLog.load(context) }
    var showLog by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateResult?>(null) }
    // 下载状态与通知栏统一走 UpdatePrompt：与启动检查那条路径共用同一套下载、
    // 进度通知和"已下载"确认，不再各写一遍（之前两个入口的行为并不一致）
    val download by UpdatePrompt.download.collectAsStateWithLifecycle()
    val downloading = download?.running == true
    val progress = download?.progress ?: 0

    // collectAsState 而非 WithLifecycle：后者在本页停驻时不会把新值推下来，
    // 「启动时检查更新」改完返回会显示旧值。
    // 徽章不依赖这条 state：深浅直接读 MaterialTheme，改外观由主题重组带过去。
    val settings by Graph.settings.settings.collectAsState(
        initial = Graph.settings.currentOrNull() ?: AppSettings()
    )

    Scaffold(
        // 透明容器：背景由根部的常驻玻璃层统一绘制，内部页面与底栏页共用同一张底
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // 页面级不透明背景：转场时遮住下面那一页，避免残影
        modifier = Modifier.fillMaxSize().glassPageBackground(),
        topBar = {
            PageTopBar(
                title = "关于",
                subtitle = "版本、更新与开源协议",
                onBack = { navController.popBackStack() },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageMetrics.HPad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            // 图标 / 标题整块可点，直接跳项目主页
            Column(
                // 整块铺满宽度：原来宽度跟着文字走，按下去只有文字那一小条亮起来，
                // 和设置页「整行都能按」的手感不是一回事。现在是一整条横贯屏幕的淡片。
                Modifier
                    .fillMaxWidth()
                    .glassPressable(
                        onClick = {
                            runCatching { uriHandler.openUri(projectUrl) }
                                .onFailure { Notice.error("这台设备上没有可打开网页的应用") }
                        },
                        radius = PageMetrics.CardRadius,
                    )
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIconBadge(size = 78.dp)
                Text(
                    "MyPurePlayer",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "专注视频播放的安卓客户端",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "本地 · 网络 · 字幕 · 投屏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text(
                    BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(22.dp))

            // 框架与版本号统一在「更多」页顶部列出，这里不再重复罗列组件清单；
            // 关于页只保留「版本 / 协议 / 项目地址」这类与发布相关的事实。
            InfoCard(
                listOf(
                    "版本" to "${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）",
                    "运行环境" to "Android 7.0 及以上 · arm64",
                    "开源协议" to "MIT License",
                    // 项目地址显示完整的 https:// 前缀，让用户看到的是可识别的链接协议
                    "项目地址" to projectUrl,
                ),
            )

            Spacer(Modifier.height(20.dp))
            Section(
                "功能范围",
                "支持本地视频扫描、SMB 共享浏览、网络串流、播放列表、外挂字幕与历史记录；" +
                    "并可通过回收站恢复删除、用保险箱加密隐藏视频，以及投屏到局域网设备。" +
                    "网络共享为只读方式，仅用于播放和加入播放列表，不修改远程文件。",
            )
            Section(
                "权限说明",
                "「所有文件访问」用于重命名、移动、删除视频与字幕，并把删除的文件放进回收站；" +
                    "该权限不经过媒体库索引，系统无法只对单个目录授权，因此需要整体授权。" +
                    "应用只处理你主动浏览到的视频文件，不上传、不收集文件列表。" +
                    "「安装未知应用」仅用于安装本应用自己下载的更新包。",
            )
            Section(
                "手势",
                "单击显示或隐藏控件；双击暂停或继续；左侧上下滑调节亮度，右侧上下滑调节音量；" +
                    "横向滑动快进或快退；长按屏幕临时以 2 倍速播放，松开恢复。",
            )
            Section(
                "字幕",
                "自动加载视频同目录下的同名字幕（支持 影片.chs.srt、影片.1.srt 等命名）；" +
                    "也可从网络目录或设备文件中手动选择其它字幕。ASS/SSA 由 libass 渲染，" +
                    "完整支持字体样式、动画、卡拉OK与内嵌图形特效。",
            )
            Section(
                "播放列表",
                "打开视频时，自动将其所在目录的全部视频加入播放列表；" +
                    "也可在「播放」页手动添加某个文件夹中的全部或部分视频。",
            )
            Section(
                "开源协议",
                "本项目以 MIT 协议开源，可自由使用、修改与再分发，请保留版权与许可声明。",
            )
            Section(
                "第三方组件",
                "Jetpack Media3 / ExoPlayer、ass-media（libass）、smbj（Apache License 2.0）、" +
                    "AndroidX 与 Jetpack Compose（Apache License 2.0）；" +
                    "DTS / TrueHD 音轨的软件解码使用 FFmpeg（LGPL-2.1+，未启用 GPL 组件）。" +
                    "上述组件遵循各自的许可条款。",
            )
            Spacer(Modifier.height(11.dp))
            // 更新相关设置与操作统一放在关于页底部，与版本号在同一页。
            // 版式与上方「功能范围 / 权限说明」等区块完全一致：同一个标题层级、
            // 同一张 22dp 圆角卡、同样的 16dp 内边距，只是行内多了一个开关或箭头。
            UpdateSection(
                autoCheck = settings.autoCheckUpdate,
                onAutoCheck = { scope.launch { Graph.settings.setAutoCheckUpdate(it) } },
                checking = checking,
                onCheck = {
                    if (!checking) {
                        checking = true
                        scope.launch {
                            val result = UpdateChecker.check(UpdateChecker.DEFAULT_REPO, BuildConfig.VERSION_NAME)
                            checking = false
                            result.fold(
                                onSuccess = { info ->
                                    if (info.hasUpdate) update = info
                                    else Notice.success("已是最新版本 ${info.current}")
                                },
                                onFailure = { Notice.error(it.message ?: "检查更新失败") },
                            )
                        }
                    }
                },
                hasLog = releaseNotes.isNotEmpty(),
                onOpenLog = { showLog = true },
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showLog) {
        // 用全站统一的玻璃弹窗外壳，不再用 M3 AlertDialog：
        // AlertDialog 自带「标题—正文—按钮」三段各 24dp 的留白，按钮本身还有 48dp
        // 最小触控高度，叠起来就是关闭键上下两大块空白。这里内边距由弹窗外壳统一给
        // （左右 22dp / 上下 20dp），底部按钮只隔 16dp，按钮换成与「测试 / 保存」
        // 同一种玻璃按钮。
        GlassPanelDialog(title = "更新日志", onDismiss = { showLog = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (releaseNotes.isEmpty()) {
                    Text(
                        "暂无更新日志。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                releaseNotes.forEachIndexed { index, note ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    Text(
                        buildString {
                            append(note.date)
                            if (note.version.isNotBlank()) append("    v${note.version}")
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    // 逐条平铺，保留 md 里的「新增：」「优化：」前缀，不再按分类切组。
                    // 分类标题会把同一句话拆成标题+正文两行，一版 22 条时反而多占一大截高度；
                    // 原文自带类别前缀，平铺照样看得出是新增还是修复。
                    note.notes.forEachIndexed { lineIndex, line ->
                        Text(
                            "· $line",
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = if (lineIndex == 0) 8.dp else 4.dp),
                        )
                    }
                }
                // 「查看更早的版本」属于**内容**，跟在最后一条日志后面，随日志一起滚动。
                // 固定在底部按钮栏时，它和「关闭」并排、和正文脱节，读起来像第二个关闭键；
                // 而它要跳转的是"这份日志之外的更早版本"，语义上就该在正文末尾。
                if (releaseNotes.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "查看更早的版本",
                        style = MaterialTheme.typography.labelLarge,
                        color = appGlass().accent,
                        modifier = Modifier
                            .glassPressable(
                                radius = 10.dp,
                                onClick = {
                                    runCatching { uriHandler.openUri("$projectUrl/releases") }
                                        .onFailure { Notice.error("这台设备上没有可打开网页的应用") }
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GlassButton(text = "关闭", onClick = { showLog = false })
            }
        }
    }

    // 下载完成后优先弹「立即安装」，不再叠加「发现新版本」——与全局 UpdatePromptHost
    // 的互斥逻辑一致，否则两个 AlertDialog 会一起出现、点哪个都被另一个盖住。
    val readyApk = download?.takeIf { !it.running && it.apkPath != null }
    update?.let { info ->
        if (readyApk != null) return@let
        UpdateResultDialog(
            result = info,
            downloading = downloading,
            progress = progress,
            errorText = download?.error,
            onDismiss = { update = null },
            onDownload = {
                if (downloading) return@UpdateResultDialog
                if (info.latest.apkUrl == null) {
                    if (UpdateChecker.openPage(context, info.latest.pageUrl)) update = null
                    else Notice.error("没有可以打开浏览器的应用")
                } else {
                    UpdatePrompt.startDownload(context, info.latest)
                }
            },
        )
    }

    // 下载完成：与全局提示一致，先问一句再装
    readyApk?.let {
        UpdateReadyDialog(
            version = update?.latest?.version.orEmpty(),
            onInstall = { UpdatePrompt.installDownloaded(context) },
            onDismiss = {
                UpdatePrompt.dismissDownload()
                update = null
            },
        )
    }
}

/** 「更新」区块：标题加粗在上、状态小字在下，右侧只保留必要开关，不再塞说明文字 */
@Composable
private fun UpdateSection(
    autoCheck: Boolean,
    onAutoCheck: (Boolean) -> Unit,
    checking: Boolean,
    onCheck: () -> Unit,
    hasLog: Boolean,
    onOpenLog: () -> Unit,
) {
    // 内边距交给每一行自己给（contentPadding = Zero）：行才能贴到卡片左右边界，
    // 与设置页的开关行按下时是同一个范围。
    SectionFrame("更新", contentPadding = PaddingValues(vertical = 0.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                // 整行可切换：与设置页开关行保持同一套交互，玻璃条任意位置都能开关；
                // Switch 只作视觉指示，onCheckedChange 置空，避免子控件与整行各切换一次。
                //
                // 用 glassToggleable 而不是原生 toggleable：后者的按压波纹是一块**直角**
                // 矩形，压在圆角玻璃条里就是用户反复提到的「白色细框 / 矩形阴影」。
                // 这里关掉波纹，改成同圆角的主题色淡片，按压反馈仍保留。
                Modifier
                    .fillMaxWidth()
                    .glassToggleable(value = autoCheck, onValueChange = onAutoCheck)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "启动时检查更新",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Switch(checked = autoCheck, onCheckedChange = null)
            }
            UpdateActionRow(
                title = "检查更新",
                // 下方只留版本号：原来那句「点击检查是否有新版本」既是废话，
                // 也把一行状态文字挤成两行，反而不像"状态"了
                value = if (checking) "正在检查…" else "当前版本 ${BuildConfig.VERSION_NAME}",
                onClick = onCheck,
            )
            UpdateActionRow(
                title = "更新日志",
                value = if (hasLog) "查看各版本更新内容" else "暂无更新日志",
                onClick = if (hasLog) onOpenLog else null,
            )
        }
    }
}

/** 整行可点：粗体标题在上，小字状态在下；没有右侧文字和箭头 */
@Composable
private fun UpdateActionRow(
    title: String,
    value: String,
    onClick: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    // 与开关行同一套：关掉矩形波纹，改成同圆角的按压淡片
                    Modifier.glassPressable(onClick = onClick)
                } else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 应用图标徽章：与桌面自适应图标完全同源——同一份矢量前景 + 同一套深色渐变底。
 *
 * 不能用 painterResource(R.mipmap.ic_launcher)：那是 adaptive-icon XML，
 * Compose 的 painterResource 只支持矢量与 PNG/WebP，加载 XML 会在进入
 * 「更多 / 关于」页时抛 IllegalArgumentException 直接闪退。
 */
@Composable
fun AppIconBadge(
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    // 桌面自适应图标只显示 108 视口中心的 72×72（1/1.5 缩放），
    // 这里把两层矢量按 1.5 倍画进徽章并裁切，可见图案与桌面图标完全一致。
    //
    // 必须用 requiredSize 而不是 size：size 会 constrain 到父级传入的
    // max 约束（Box 给子项的 max 就是自身边长），1.5 倍会被夹回 1.0 倍，
    // 实际画的是完整 108 视口 —— 盘径只占徽章 51%，比桌面小一大截。
    // requiredSize 不受父约束限制，超出部分交给外层 clip 裁掉。
    //
    // 底板同样读 ic_launcher_background.xml，不再手抄渐变：
    // 之前这里是一份硬编码的深蓝底 + 靛蓝环境光，图标改版后没有同步，
    // 徽章一直停在旧配色，与桌面上那枚不是同一个底色。
    // background/foreground 都是普通 <vector>，painterResource 能直接加载
    // （只有 mipmap-anydpi-v26 的 adaptive-icon XML 不行）。
    // 徽章底色与关于页背景同属「白 / 淡蓝白」，不描边时整块板会消失在页面里，
    // 只剩一个蓝球浮着（第 23 版真机截图）。
    //
    // 第 27 版把轮廓做进了图标自己的底板：可见区内缘 1.2u / 22% 的收口。
    // 徽章按 1.5 倍画、裁到 72×72 显示，收口正好落在徽章边缘，于是这里原有的
    // 0.75dp 描边会变成第二道边 —— 撤掉，只留投影负责「浮起来」。
    // 第 26 版「图标画不出跟随遮罩的边」的结论仍然成立，但那针对的是描在板沿
    // 上的线；板内侧的收口不跟随遮罩，三种遮罩下都连续，所以能两边同源。
    //
    // 深色图标必须由代码显式选资源，不能靠资源限定符：
    // 限定符跟随的是「系统」深色模式，而本应用允许在设置里强制深色/浅色。
    // 系统浅色 + 应用强制深色时，按限定符取图仍会拿到浅色版，
    // 结果就是深色页面上摆了一块白板（真机复现过）。
    // 所以按 isDarkScheme()（读实际生效配色，已兼容强制开关与动态取色）选图。
    // 桌面图标没有代码参与，固定用浅色板：MIUI 桌面把图标位图缓存在桌面进程里，
    // 既不认系统深色变化，也不认覆盖安装，只有重启桌面或重启手机才重建
    // （实测：切深色不变 / 覆盖安装不变 / force-stop 桌面立即变）。
    // 与其给一枚切了也不动的夜间图标，不如统一浅色，桌面始终是设计基准那版。
    // 桌面既然固定浅色，徽章也就不再提供「固定浅色」的开关 —— 少一个只管两处
    // 图标、又和桌面不一致的选项，用户不必理解边界。
    //
    // 深浅由 isDarkScheme() 直接读实际生效配色（已兼容强制外观与动态取色），
    // 父页面不需要再传值：这里不 collect flow，改完设置返回时随重组刷新。
    val dark = MaterialTheme.colorScheme.isDarkScheme()
    // 浅色直接取原件：drawable-night 已撤掉，这两个 id 不再有夜间变体，
    // 系统深色下由 dark 分支接管，所以浅色分支取到的必定是浅色板。
    // 深色走 _night 副本，两份的结构唯一差别是底板色与收口方向。
    val (bgRes, fgRes) = if (dark) {
        R.drawable.ic_launcher_background_night to R.drawable.ic_launcher_foreground_night
    } else {
        R.drawable.ic_launcher_background to R.drawable.ic_launcher_foreground
    }
    val shape = RoundedCornerShape(size * 0.26f)
    Box(
        modifier
            .size(size)
            .shadow(
                elevation = 3.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0x30254A8C),
                spotColor = Color(0x30254A8C),
            )
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(bgRes),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.5f),
        )
        Image(
            painter = painterResource(fgRes),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}


@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    Column(
        Modifier
            .fillMaxWidth()
            .appGlassSurface(RoundedCornerShape(PageMetrics.CardRadius))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(76.dp),
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    SectionFrame(title) {
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
        )
    }
}

/** 关于页所有区块的统一外壳：小标题 + 22dp 圆角卡，标题与卡片的间距只在这里定义 */
@Composable
private fun SectionFrame(
    title: String,
    /**
     * 卡片内容的内边距。
     *
     * 纯文字区块需要左右 16dp；而「更新」区是整行可点的（与设置页同构），
     * 行必须能贴到卡片边界，否则按下去只有中间一段亮 —— 就是"关于的点击反馈
     * 比设置小一圈"的来源。这类区块传 [PaddingValues.Zero]，内边距由行自己给。
     */
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        // 分区标题与设置页、本地页共用 SectionLabel：蓝竖条 + titleSmall
        SectionLabel(title)
        Spacer(Modifier.height(3.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .appGlassSurface(RoundedCornerShape(PageMetrics.CardRadius))
                .padding(contentPadding),
            content = content,
        )
    }
}
