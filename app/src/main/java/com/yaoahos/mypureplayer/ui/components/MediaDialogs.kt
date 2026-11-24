package com.yaoahos.mypureplayer.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yaoahos.mypureplayer.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.SortKey
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.media.Thumbnails
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 长按视频弹出的操作面板。
 * 不支持的操作传 null 即可自动隐藏（例如网络共享没有重命名 / 移动 / 保险箱）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaActionSheet(
    item: VideoItem,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
    onInfo: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.folderName.ifBlank { Formats.formatSize(item.sizeBytes) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // 用普通 Column 而不是 LazyColumn：条目数量固定，LazyColumn 在这里会按最大高度撑开，
            // 导致底部出现大片空白、最后一项被屏幕裁掉。
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                ActionRow(Icons.Rounded.PlayArrow, "播放") { onDismiss(); onPlay() }
                ActionRow(Icons.AutoMirrored.Rounded.FormatListBulleted, "加入列表") {
                    onDismiss(); onAddToQueue()
                }
                if (onRename != null || onCopy != null || onMove != null) {
                    ActionDivider()
                    if (onRename != null) {
                        ActionRow(Icons.Rounded.DriveFileRenameOutline, "重命名") {
                            onDismiss(); onRename()
                        }
                    }
                    if (onCopy != null) {
                        ActionRow(Icons.Rounded.ContentCopy, "复制到…") { onDismiss(); onCopy() }
                    }
                    if (onMove != null) {
                        ActionRow(Icons.AutoMirrored.Rounded.DriveFileMove, "移动到…") { onDismiss(); onMove() }
                    }
                }
                ActionDivider()
                if (onShare != null) {
                    ActionRow(Icons.Rounded.Share, "分享") { onDismiss(); onShare() }
                }
                ActionRow(Icons.Rounded.Info, "详情") { onDismiss(); onInfo() }
                if (onHide != null || onDelete != null) {
                    ActionDivider()
                    // 不区分「文件 / 文件夹」：长按的是哪个就隐藏哪个，行为一致，菜单也不堆两行
                    if (onHide != null) {
                        ActionRow(Icons.Rounded.Lock, "隐藏到保险箱") { onDismiss(); onHide() }
                    }
                    if (onDelete != null) {
                        ActionRow(Icons.Rounded.DeleteOutline, "删除", destructive = true) {
                            onDismiss(); onDelete()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 文件夹长按操作面板。
 * 直接平铺：添加到播放列表 / 信息 / 隐藏到保险箱 / 删除，不再套一层「移出文件夹」子页。
 * 不支持的操作传 null 即自动隐藏（网络共享只读，没有隐藏 / 删除）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderActionSheet(
    name: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            if (onAddToQueue != null) {
                ActionRow(Icons.AutoMirrored.Rounded.FormatListBulleted, "加入列表") {
                    onDismiss(); onAddToQueue()
                }
            }
            if (onInfo != null) {
                ActionRow(Icons.Rounded.Info, "详情") { onDismiss(); onInfo() }
            }
            if (onHide != null || onDelete != null) {
                ActionDivider()
                if (onHide != null) {
                    ActionRow(Icons.Rounded.Lock, "隐藏到保险箱") { onDismiss(); onHide() }
                }
                if (onDelete != null) {
                    // 与视频菜单同一个词：两者做的都是"移入回收站"，不是永久删除
                    ActionRow(Icons.Rounded.DeleteOutline, "删除", destructive = true) {
                        onDismiss(); onDelete()
                    }
                }
            }
        }
    }
}

/** 文件夹信息：名称、位置、视频数量、总大小、来源 */
@Composable
fun FolderInfoDialog(
    name: String,
    location: String,
    videoCount: Int,
    totalSize: Long,
    source: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
        containerColor = appGlass().panelStrong,
        tonalElevation = 0.dp,
        // 玻璃底：与全站卡片同一套材质，弹窗压住内容时仍透出一点页面
        onDismissRequest = onDismiss,
        title = { Text("文件夹详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("名称", name)
                InfoRow("位置", location.ifBlank { "—" }, copyable = true)
                InfoRow("视频", "$videoCount 个")
                InfoRow("总大小", Formats.formatSize(totalSize))
                InfoRow("来源", source)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 网络连接长按操作面板（替代原三点菜单）：测试连接 / 编辑 / 删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerActionSheet(
    name: String,
    onDismiss: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth()) {
            ActionRow(Icons.Rounded.NetworkCheck, "测试连接") { onDismiss(); onTest() }
            ActionRow(Icons.Rounded.Edit, "编辑") { onDismiss(); onEdit() }
            ActionDivider()
            ActionRow(Icons.Rounded.DeleteOutline, "删除", destructive = true) { onDismiss(); onDelete() }
        }
    }
}

/**
 * 宽度自适应的底部面板：窄屏占满宽度；宽屏按内容收缩并居中，避免选项被拉散。
 *
 * 底层用自研的 [AppBottomSheet] 而不是 Material3 的 `ModalBottomSheet`：
 * 后者靠布局偏移挪面板、每帧重排整块内容，横屏时位移近千像素会明显掉帧
 * （详细原因见 AppBottomSheet 文件头）。「排列与排序」面板早已切过去，
 * 动作面板继续留在 M3 上就成了全站唯一会卡的那一类弹窗。
 */
@Composable
internal fun AdaptiveSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 20.dp),
            content = content,
        )
    }
}

@Composable
internal fun ActionRow(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    // 多选时「播放 / 详情」这类只能对单条执行的操作置灰而不是隐藏：
    // 用户能看见这个操作确实存在，只是当前选中状态不满足条件。
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val base = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val tint = if (enabled) base else base.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 与设置页/关于页同一套按压反馈：关掉矩形波纹，用同圆角的淡片
            .then(
                if (enabled) Modifier.glassPressable(radius = 12.dp, onClick = onClick)
                else Modifier,
            )
            .padding(horizontal = 24.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
internal fun ActionDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/** 多选态共用的操作项：底部操作栏与「更多操作」面板都读这一份，disabled 只置灰不隐藏。 */
data class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 底部选择操作栏：圆角玻璃底 + 等距图标文字，最多四个槽位。
 *
 * 分配规则（用户 2026-11-23 定稿）：
 *  - ≤3 个动作：原样展示，末尾补一个**置灰**的「更多」占位，保持四宫格宽度不跳；
 *  - 4 个动作：四个都直接展示，不出现「更多」；
 *  - ≥5 个动作：前三个直接展示，第四个是真正可用的「更多」，其余动作进面板。
 * 面板内容直接取 [actions] 的第四项之后，绝不重复前三格里的动作。
 * 零选择时 [actions] 里所有 enabled 都是 false，整条栏可见但点不动。
 *
 * [forceMore] 供「历史 / 回收站 / 保险箱」这类恰好四个动作、但要求第 4 个收进
 * 「更多」的页面使用：置 true 时即使只有 4 项也按「前 3 平铺 + 更多」处理。
 */
@Composable
fun SelectionActionBar(
    actions: List<SelectionAction>,
    modifier: Modifier = Modifier,
    moreSheetTitle: String = "更多操作",
    forceMore: Boolean = false,
) {
    var moreOpen by androidx.compose.runtime.remember { mutableStateOf(false) }
    val anyEnabled = actions.any { it.enabled }
    LaunchedEffect(anyEnabled) { if (!anyEnabled) moreOpen = false }

    val foldFourth = forceMore && actions.size == 4
    val shown = when {
        actions.size <= 3 -> actions + SelectionAction(Icons.Rounded.MoreHoriz, "更多", enabled = false) {}
        actions.size == 4 && !foldFourth -> actions
        else -> actions.take(3) + SelectionAction(Icons.Rounded.MoreHoriz, "更多", enabled = anyEnabled) {
            moreOpen = true
        }
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
    Box(
        modifier
            // 平板/横屏下只把这条玻璃底限宽居中，四个按钮仍在条内均分铺开；
            // 不再去挤按钮间距——用户要收的是玻璃条本身，不是把按钮挪近。
            .widthIn(max = PageMetrics.DialogMaxWidth)
            .fillMaxWidth()
            .appGlassSurface(shape = RoundedCornerShape(28.dp), strong = true, elevation = 4.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            shown.forEach { action ->
                val tint = when {
                    !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    action.danger -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
                Column(
                    modifier = Modifier
                        .then(
                            if (action.enabled) {
                                // 与设置行同一套按压淡片：图标按钮也是玻璃上的一块可点区域，
                                // 原来这里走的是 M3 波纹，方形边界在圆角卡上很明显
                                Modifier.glassPressable(role = Role.Button, onClick = action.onClick)
                            } else Modifier,
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(action.icon, contentDescription = action.label, tint = tint, modifier = Modifier.size(21.dp))
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (action.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
    }

    if (moreOpen && (actions.size > 4 || foldFourth)) {
        SelectionMoreSheet(
            title = moreSheetTitle,
            onDismiss = { moreOpen = false },
            actions = actions.drop(3).map { item ->
                // 面板里的动作点完要收起面板，避免留在选择态上反复操作
                item.copy(onClick = { moreOpen = false; item.onClick() })
            },
        )
    }
}

/** 「更多操作」面板：标题 + 图标行；不可用项只变灰，不隐藏。 */
@Composable
fun SelectionMoreSheet(
    actions: List<SelectionAction>,
    onDismiss: () -> Unit,
    title: String = "更多操作",
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        // 之前这里没有水平内边距：AdaptiveSheet 只管整体宽度与底部留白，
        // 于是标题与图标行贴着面板左边，和全站其它面板的 22dp 内缩明显不一致。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            actions.forEach { action ->
                val tint = when {
                    !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    action.danger -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (action.enabled) {
                                Modifier.glassPressable(role = Role.Button, onClick = action.onClick)
                            } else Modifier,
                        )
                        .padding(vertical = 13.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(
                        action.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (action.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                    )
                }
            }
        }
    }
}

/** 重命名：只让用户改基础名，扩展名保持不变 */
@Composable
fun RenameDialog(
    item: VideoItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val base = item.fileName.substringBeforeLast('.', item.fileName)
    var text by remember { mutableStateOf(base) }
    val ext = item.fileName.substringAfterLast('.', "")
    val suffixSlot: (@Composable () -> Unit)? = if (ext.isBlank()) null else { { Text(".$ext") } }

    AlertDialog(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
        containerColor = appGlass().panelStrong,
        tonalElevation = 0.dp,
        // 玻璃底：与全站卡片同一套材质，弹窗压住内容时仍透出一点页面
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("文件名") },
                    singleLine = true,
                    suffix = suffixSlot,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "扩展名保持不变，只修改前面的名称。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.trim().isNotEmpty() && text.trim() != base,
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 信息面板里的「位置」：本地给真实路径，网络共享给去掉 scheme 的地址（与浏览页标题一致） */
private fun locationOf(item: VideoItem): String = when (item.source) {
    com.yaoahos.mypureplayer.data.MediaSource.LOCAL -> item.localPath ?: item.folderName
    else -> item.uri.removePrefix("smb://")
}

/** 视频信息 */
@Composable
fun MediaInfoDialog(item: VideoItem, onDismiss: () -> Unit) {
    val date = if (item.dateModified > 0) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.dateModified))
    } else "未知"

    AlertDialog(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
        containerColor = appGlass().panelStrong,
        tonalElevation = 0.dp,
        // 玻璃底：与全站卡片同一套材质，弹窗压住内容时仍透出一点页面
        onDismissRequest = onDismiss,
        title = { Text("视频详情") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoRow("名称", item.fileName)
                InfoRow("位置", locationOf(item).ifBlank { "—" }, copyable = true)
                InfoRow("大小", Formats.formatSize(item.sizeBytes))
                // 网络视频的时长是抽帧时补到 Thumbnails 缓存里的，不在 item 上：
                // 只读 item.durationMs 会显示 00:00，而列表卡片明明是 22:51。
                val durationMs = Thumbnails.displayDuration(item).takeIf { it > 0L } ?: item.durationMs
                InfoRow("时长", if (durationMs > 0L) Formats.formatDuration(durationMs) else "未知")
                InfoRow(
                    "分辨率",
                    if (item.width > 0 && item.height > 0) "${item.width} × ${item.height}" else "未知",
                )
                InfoRow("修改时间", date)
                InfoRow(
                    "来源",
                    when (item.source) {
                        com.yaoahos.mypureplayer.data.MediaSource.LOCAL -> "本地"
                        com.yaoahos.mypureplayer.data.MediaSource.SMB -> "网络共享"
                        com.yaoahos.mypureplayer.data.MediaSource.STREAM -> "串流"
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun InfoRow(label: String, value: String, copyable: Boolean = false) {
    val context = LocalContext.current
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_600)
            copied = false
        }
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(68.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = if (copied) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                modifier = if (copyable && value.isNotBlank() && value != "—") {
                    Modifier.glassPressable(role = Role.Button, radius = 8.dp) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText(label, value))
                        copied = true
                    }
                } else Modifier,
            )
            // 长按面板本身会盖住页面，提示只能就地显示
            if (copied) {
                Text(
                    "已复制到剪贴板",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 复制 / 移动的目标目录选择器：真的一层层浏览目录，不再让用户手打路径。
 * 从「已知视频目录」的父目录开始，点文件夹进入，点「选择此目录」确认。
 */
@Composable
fun DirPickerDialog(
    title: String,
    candidates: List<File>,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit,
) {
    var current by remember {
        mutableStateOf(candidates.firstOrNull()?.parentFile ?: Environment.getExternalStorageDirectory())
    }
    var dirs by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(current) {
        dirs = withContext(Dispatchers.IO) {
            runCatching {
                current.listFiles { f ->
                    f.isDirectory && !f.name.startsWith(".") && f.name != "Android"
                }?.sortedBy { it.name.lowercase() }?.toList()
            }.getOrDefault(emptyList()) ?: emptyList()
        }
    }

    AlertDialog(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
        containerColor = appGlass().panelStrong,
        tonalElevation = 0.dp,
        // 玻璃底：与全站卡片同一套材质，弹窗压住内容时仍透出一点页面
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    current.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .glassPressable(radius = 12.dp, role = Role.Button) {
                                    current.parentFile?.let { current = it }
                                }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.ArrowUpward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(10.dp))
                            Text("上级目录", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(dirs, key = { it.absolutePath }) { dir ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .glassPressable(radius = 12.dp, role = Role.Button) { current = dir }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(10.dp))
                            Text(
                                dir.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "点文件夹进入子目录，选好后点「选择此目录」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text("选择此目录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 「加入播放列表」目标选择器：只列出用户自建的播放列表，交由用户决定加到哪一个。
 *
 * 「正在播放」是自动维护的播放队列、不允许手动添加，所以这里不再提供该入口（targetId 永远非空）。
 * 列表项间留出足够呼吸空间，避免条目贴在一起显得拥挤。
 */
@Composable
fun PlaylistTargetSheet(
    onDismiss: () -> Unit,
    onPick: (targetId: String, name: String) -> Unit,
) {
    val playlists by Graph.playlists.playlists.collectAsStateWithLifecycle()
    // 与「选择文件夹 / 添加视频 / 选择共享位置」同一套居中玻璃弹窗：
    // 之前用贴底的 AdaptiveSheet，内容没有水平内边距，列表一路贴到屏幕左边；
    // GlassChoiceDialog 自带 0.86 宽 + 20dp 内边距，既不贴边也与其他弹窗材质一致。
    GlassChoiceDialog(title = "加入播放列表", onDismiss = onDismiss) {
        Text("加入播放列表", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "选择要把视频加入哪个列表",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (playlists.isEmpty()) {
                item {
                    Text(
                        "还没有自建播放列表，请先在「播放列表」页点右下角 + 创建。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(playlists, key = { it.id }) { pl ->
                GlassChoiceRow(
                    icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
                    title = pl.name,
                    subtitle = "${pl.videos.size} 个视频",
                ) { onPick(pl.id, pl.name) }
            }
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    }
}

/**
 * 玻璃选择弹窗内部的圆条选项：直接用本地文件夹卡片同款玻璃材质 [appGlassSurface]。
 * 面板已改为首页那张浅色渐变（[appDialogSurface]），选项再叠一张淡蓝玻璃卡，
 * 正是首页「淡背景 + 淡蓝玻璃卡」的层次，不会像过去"白条压灰板"那样突兀。
 *
 * [selected] 用于「当前生效的那一项」：底色染主题色 + 标题改强调色 + 右侧打勾，
 * 三处同时出现，避免只换底色在深色主题下看不出选中。
 */
@Composable
fun GlassChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.5f
    val accent = appGlass().accent
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectPressedWithTap()
    val shape = RoundedCornerShape(GlassPress.radius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier
                        .appGlassSurface(
                            shape,
                            elevation = 1.dp,
                            tinted = selected,
                        )
                        // 选项条原先**没有任何按压反馈**：只有选中态变色。
                        // 点一下没反应会让人以为没点上，于是连点两下（第二下才看到选中）。
                        .glassPressOverlay(pressed, shape)
                } else {
                    Modifier
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                }
            )
            // 双保险：先按圆角裁切，再关闭 M3 默认 ripple。此前只在 clickable 上关
            // indication，某些系统/焦点态仍会在选中条内部画出直角半透明矩形；
            // 这里让任何后续绘制都被 16dp 圆角限制，选中反馈仍由变色 + 打勾承担。
            .clip(shape)
            // 部分系统会给带 Role.Button 的焦点节点画一个直角白色高亮框，
            // 即使 indication=null 也照画。选项只靠点按使用，禁掉焦点即可彻底消失。
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = (if (selected) accent else MaterialTheme.colorScheme.primary)
                .copy(alpha = contentAlpha),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = (if (selected) accent else MaterialTheme.colorScheme.onSurface)
                    .copy(alpha = contentAlpha),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }
        if (selected) {
            Spacer(Modifier.size(8.dp))
            Icon(
                Icons.Rounded.Check,
                contentDescription = "当前设置",
                tint = accent.copy(alpha = contentAlpha),
            )
        }
    }
}

/**
 * 玻璃材质的选择对话框（加入播放列表 / 添加视频 / 选择文件夹 / 选择共享位置）。
 *
 * 面板走 [appDialogSurface]（首页同款淡蓝渐变），内部选项走 [GlassChoiceRow]
 * （文件夹卡片同款玻璃）——与首页「淡背景 + 淡蓝玻璃卡」完全同一套层次。
 * 全站底部面板（AppBottomSheet）与确认框（ConfirmDialog）也已统一到 appDialogSurface。
 */
@Composable
fun GlassChoiceDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 用真实窗口尺寸算高度上限：横屏/折叠屏可用高度很矮，弹窗必须能整体滚动，
        // 否则选项列表会把标题和底部按钮顶出屏幕。
        val windowHeight = with(LocalDensity.current) {
            androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.34f))
                .clickable(role = Role.Button, onClickLabel = "关闭", onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    // 手机上按 86% 宽，平板/横屏不超过 DialogMaxWidth，避免铺满整屏。
                    .widthIn(max = PageMetrics.DialogMaxWidth)
                    .heightIn(max = (windowHeight - 48.dp).coerceAtLeast(240.dp))
                    .verticalScroll(rememberScrollState())
                    // 面板直接用首页那张不透明渐变（淡蓝白），像从页面里裁出来的一块；
                    // 选项条再叠文件夹卡片同款玻璃，层次与首页「淡背景 + 淡蓝玻璃卡」一致。
                    .appDialogSurface(RoundedCornerShape(26.dp))
                    .clickable(enabled = false, onClick = {}) // 吞掉点击，避免穿透到遮罩
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                // title 之前只是形参、从未画出来：所有选择弹窗都少了标题。
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                content()
            }
        }
    }
}

/** 排序字段与显示名 */
val SortKey.label: String
    get() = when (this) {
        SortKey.NAME -> "名称"
        SortKey.DATE -> "日期"
        SortKey.DURATION -> "时长"
        SortKey.SIZE -> "大小"
    }

