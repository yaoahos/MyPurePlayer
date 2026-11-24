package com.yaoahos.mypureplayer.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.AppSettings
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.PlayModes
import com.yaoahos.mypureplayer.media.Thumbnails
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.glassPressable
import com.yaoahos.mypureplayer.ui.components.glassToggleable
import com.yaoahos.mypureplayer.ui.components.GlassChoiceDialog
import com.yaoahos.mypureplayer.ui.components.GlassChoiceRow
import com.yaoahos.mypureplayer.ui.theme.ThemePalette
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.SectionLabel
import com.yaoahos.mypureplayer.ui.components.appGlassSurface
import com.yaoahos.mypureplayer.ui.components.appGlass
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.theme.DynamicColorStatus
import com.yaoahos.mypureplayer.ui.theme.ThemeRuntime
import com.yaoahos.mypureplayer.ui.components.ConfirmDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var cacheSize by remember { mutableStateOf(Thumbnails.diskUsage(context)) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var playModeDialog by remember { mutableStateOf(false) }

    Scaffold(
        // 透明容器：背景由根部的常驻玻璃层统一绘制，内部页面与底栏页共用同一张底
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // 页面级不透明背景：转场时遮住下面那一页，避免残影
        modifier = Modifier.fillMaxSize().glassPageBackground(),
        topBar = {
            // 与底栏页共用同一个标题栏：字号、副标题、返回键位置全部一致，
            // 否则从「更多」点进来时标题会从 16dp 跳到 64dp、高度也矮一截
            PageTopBar(
                title = "设置",
                subtitle = "外观、播放、缓存",
                onBack = { navController.popBackStack() },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsGroup("外观") {
                Text(
                    "深色模式",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (value, label) ->
                        FilterChip(
                            selected = settings.darkMode == value,
                            onClick = { scope.launch { Graph.settings.setDarkMode(value) } },
                            label = { Text(label) },
                        )
                    }
                }
                // 固定配色：手动挑的色相，玻璃层次可以按套校准。
                // 动态取色开着时色点变淡（表示当前不生效），但点一下就切回固定配色 ——
                // 比"先去下面关开关、再回来挑颜色"少一步
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "主题色",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = when {
                            !settings.dynamicColor -> ThemePalette.fromKey(settings.themePalette).label
                            // 动态取色开了但没生效时，这里必须说实话：
                            // 否则用户看到"跟随壁纸"而界面还是靛蓝，会以为开关坏了
                            ThemeRuntime.dynamicStatus == DynamicColorStatus.ACTIVE -> "跟随壁纸"
                            else -> "未生效 · ${ThemePalette.fromKey(settings.themePalette).label}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (
                            settings.dynamicColor &&
                            ThemeRuntime.dynamicStatus != DynamicColorStatus.ACTIVE &&
                            ThemeRuntime.dynamicStatus != DynamicColorStatus.OFF
                        ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PalettePicker(
                    selected = ThemePalette.fromKey(settings.themePalette),
                    dimmed = settings.dynamicColor,
                    onSelect = {
                        scope.launch {
                            Graph.settings.setThemePalette(it.key)
                            // 选了固定配色就关掉动态取色，否则选了没反应
                            if (settings.dynamicColor) Graph.settings.setDynamicColor(false)
                        }
                    },
                )
                SettingsSwitchRow(
                    title = "动态取色",
                    subtitle = "跟随壁纸取色（Android 12 及以上）。开启后会覆盖上面的主题色",
                    checked = settings.dynamicColor,
                    onCheckedChange = { scope.launch { Graph.settings.setDynamicColor(it) } },
                )
                // 开了但没生效时说清原因，并且明确"现在用的是哪套配色" ——
                // 静默回落到自选色会让人以为开关坏了
                if (settings.dynamicColor) {
                    val status = ThemeRuntime.dynamicStatus
                    if (status == DynamicColorStatus.UNSUPPORTED || status == DynamicColorStatus.LOW_CHROMA) {
                        val paletteLabel = ThemeRuntime.fallbackPalette.label
                        DynamicColorHint(
                            text = when (status) {
                                DynamicColorStatus.UNSUPPORTED ->
                                    "当前系统不支持动态取色（需要 Android 12 及以上），已沿用「$paletteLabel」主题色。"
                                else ->
                                    "这张壁纸的颜色太少，取出的主色接近灰色，界面会变得寡淡。已沿用「$paletteLabel」主题色 —— 换一张有颜色的壁纸即可生效。"
                            },
                        )
                    }
                }
            }

            SettingsGroup("播放") {
                // 播放顺序放在「自动加载同名字幕」上方：它是整组播放行为的入口，
                // 先决定怎么播，再决定字幕/解码这些播的时候怎么呈现。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glassPressable(onClick = { playModeDialog = true })
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "播放顺序",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "${PlayModes.label(settings.playMode)}：${PlayModes.description(settings.playMode)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SettingsSwitchRow(
                    title = "自动加载同名字幕",
                    subtitle = "播放时自动加载同目录下的同名字幕",
                    checked = settings.autoLoadSubtitle,
                    onCheckedChange = { scope.launch { Graph.settings.setAutoLoadSubtitle(it) } },
                )
                SettingsSwitchRow(
                    title = "硬件解码",
                    subtitle = "关闭后使用软件解码，兼容性更好但更耗电",
                    checked = settings.hardwareDecode,
                    onCheckedChange = { scope.launch { Graph.settings.setHardwareDecode(it) } },
                )
                SettingsSwitchRow(
                    title = "记忆播放进度",
                    subtitle = "从历史记录中的上次位置继续播放",
                    checked = settings.resumePlayback,
                    onCheckedChange = { scope.launch { Graph.settings.setResumePlayback(it) } },
                )
                SettingsSwitchRow(
                    title = "回到桌面时开启小窗",
                    subtitle = "播放中回到桌面时自动进入画中画",
                    checked = settings.pipOnHome,
                    onCheckedChange = { scope.launch { Graph.settings.setPipOnHome(it) } },
                )
            }

            SettingsGroup("存储") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glassPressable(onClick = { confirmClearCache = true })
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "视频缩略图缓存",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "点击清除；当前 ${if (cacheSize > 0) Formats.formatSize(cacheSize) else "暂无缓存"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SettingsGroup("备份与恢复") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glassPressable(onClick = { navController.navigate(Routes.BACKUP) })
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "配置备份",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "主题、选项、SMB 与串流、历史与播放列表；本地文件或 WebDAV，可按设备自动备份",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (playModeDialog) {
        GlassChoiceDialog(title = "播放顺序", onDismiss = { playModeDialog = false }) {
            listOf(
                PlayModes.NO_AUTO to Icons.Rounded.Pause,
                PlayModes.SEQUENCE to Icons.Rounded.SkipNext,
                PlayModes.LOOP_ALL to Icons.Rounded.Repeat,
                PlayModes.LOOP_ONE to Icons.Rounded.RepeatOne,
                PlayModes.SHUFFLE to Icons.Rounded.Shuffle,
            ).forEach { (mode, icon) ->
                GlassChoiceRow(
                    icon = icon,
                    title = PlayModes.label(mode),
                    subtitle = PlayModes.description(mode),
                    selected = settings.playMode == mode,
                    onClick = {
                        playModeDialog = false
                        if (settings.playMode != mode) {
                            scope.launch { Graph.settings.setPlayMode(mode) }
                            Notice.success("已设为${PlayModes.label(mode)}")
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    // 清除缓存同样属于「清除」类操作，统一二次确认，避免误点后缩略图全部重建
    if (confirmClearCache) {
        ConfirmDialog(
            title = "清除缩略图缓存",
            message = buildString {
                append("将删除全部已缓存的缩略图")
                if (cacheSize > 0) append("（${Formats.formatSize(cacheSize)}）")
                append("，下次浏览列表时会重新生成。")
            },
            confirmText = "清除",
            destructive = true,
            onConfirm = {
                confirmClearCache = false
                Thumbnails.clear(context)
                cacheSize = 0
                Notice.success("已清除缩略图缓存")
            },
            onDismiss = { confirmClearCache = false },
        )
    }

}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        // 分区标题与本地页「文件夹 / 视频」同一套写法（蓝竖条 + titleSmall），
        // 不再是"彩色小字"这种第二套样式
        SectionLabel(title)
        Column(
            Modifier
                .fillMaxWidth()
                .appGlassSurface(RoundedCornerShape(PageMetrics.CardRadius)),
        ) { content() }
    }
}

/**
 * 动态取色没有生效时的原因说明。
 *
 * 放在开关正下方、用同一块卡片的 tintSoft 底色，读起来是"这一项的状态"而不是全局警告；
 * 文案必须同时回答两件事：为什么没生效、现在实际用的是哪套颜色。
 */
@Composable
private fun DynamicColorHint(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(PageMetrics.IconTileRadius))
            .background(appGlass().tintSoft)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        // 整行 toggleable：点文字也能切换，Switch 的 onCheckedChange 置空避免双重焦点。
        // 走 glassToggleable：原生 toggleable 的按压波纹是直角矩形，在圆角玻璃卡里会
        // 露出一块方形高亮/白框，改为同圆角的淡色按压片。
        Modifier
            .fillMaxWidth()
            .glassToggleable(value = checked, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * 主题色选择器：一排色点 + 当前配色名。
 *
 * 用色点而不是文字 Chip：颜色本身就是信息，六个中文名排一行反而要读。
 * 选中项外面加一圈同色描边（不是白圈），在浅色卡片上也看得出选的是哪个。
 */
@Composable
private fun PalettePicker(
    selected: ThemePalette,
    dimmed: Boolean,
    onSelect: (ThemePalette) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ThemePalette.entries.forEach { palette ->
            val isSelected = palette == selected && !dimmed
            // 外圈（选中环）+ 内点：圈与点同一个色相，但圈更淡 —— 选中时读作"被圈住的色点"，
            // 不像加了一个白底胶囊那么抢眼
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .then(
                        if (isSelected) {
                            Modifier
                                .background(palette.seed.copy(alpha = 0.22f))
                                .border(1.5.dp, palette.seed, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .glassPressable(
                        role = Role.RadioButton,
                        // 色点是 34dp 的正圆：圆角取一半，按压淡片才与色点同形
                        radius = 17.dp,
                        onClickLabel = palette.label,
                    ) { onSelect(palette) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (isSelected) 20.dp else 26.dp)
                        .clip(CircleShape)
                        .background(
                            if (dimmed) palette.seed.copy(alpha = 0.30f) else palette.seed,
                        ),
                )
            }
        }
    }
}
