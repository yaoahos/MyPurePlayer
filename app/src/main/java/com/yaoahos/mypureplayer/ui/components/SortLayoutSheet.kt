package com.yaoahos.mypureplayer.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yaoahos.mypureplayer.data.SortKey

/** 打开面板时保存的正式设置，取消时用它回滚预览 */
data class MediaSortDraft(
    val grid: Boolean,
    val key: String,
    val ascending: Boolean,
    val browseMode: Int = 1,
)

/** 与补充图一致的短标签 */
val SortKey.chipLabel: String
    get() = when (this) {
        SortKey.NAME -> "标题"
        SortKey.DATE -> "日期"
        SortKey.DURATION -> "时长"
        SortKey.SIZE -> "大小"
    }

private fun SortKey.chipIcon(): ImageVector = when (this) {
    SortKey.NAME -> Icons.Rounded.TextFields
    SortKey.DATE -> Icons.Rounded.CalendarMonth
    SortKey.DURATION -> Icons.Rounded.Schedule
    SortKey.SIZE -> Icons.Rounded.SwapVert
}

/** 排序方向的短标签，参考 nextplayer 按排序字段给出语义 */
private fun SortKey.orderLabels(): Pair<String, String> = when (this) {
    SortKey.NAME -> "A-Z" to "Z-A"
    SortKey.DATE -> "最早" to "最新"
    SortKey.DURATION -> "最短" to "最长"
    SortKey.SIZE -> "最小" to "最大"
}

/**
 * 合并后的「媒体布局 + 排序」面板，样式参考 nextplayer 的 QuickSettingsDialog。
 *
 * 所有点击只改面板内的草稿，并通过 [onPreview] 让页面实时预览；
 * 只有确定才调用 [onApply] 写回，取消则把预览回滚。
 * [browseMode] 非 null 时显示「媒体浏览方式」（文件夹 / 视频）一节，仅供本地视频使用；
 * 其它页面传 null，面板不会出现这一节。
 *
 * [showLayout] 为 false 时隐藏「媒体布局」（列表/网格）一节 —— 播放列表没有布局概念，
 * 只需要排序；它复用同一个面板，好处是两处排序的用词、分组、确定/取消行为完全一致
 * （用户原话："播放列表的排序参考排列与排序重做一下"）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortLayoutSheet(
    onDismiss: () -> Unit,
    initialGrid: Boolean,
    initialSort: SortKey,
    initialAscending: Boolean,
    entries: List<SortKey>,
    onApply: (grid: Boolean, key: SortKey, ascending: Boolean) -> Unit,
    onPreview: ((grid: Boolean, key: SortKey, ascending: Boolean) -> Unit)? = null,
    showLayout: Boolean = true,
    title: String = "排列与排序",
    // 1 文件夹 / 2 视频；传 null 的页面不显示这一节（网络、播放列表、回收站、保险箱）
    browseMode: Int? = null,
    onApplyBrowseMode: ((Int) -> Unit)? = null,
    onPreviewBrowseMode: ((Int) -> Unit)? = null,
) {
    var grid by remember { mutableStateOf(initialGrid) }
    var key by remember { mutableStateOf(initialSort) }
    var ascending by remember { mutableStateOf(initialAscending) }
    var mode by remember { mutableIntStateOf(browseMode ?: 1) }

    fun preview() {
        onPreview?.invoke(grid, key, ascending)
        if (browseMode != null) onPreviewBrowseMode?.invoke(mode)
    }

    // 横屏时屏幕只有 393dp 高，而这个面板竖着排下来要 400dp 以上：
    // 标题 + 浏览方式 + 布局 + 排序 + 方向 + 按钮，超出的部分被 bottom sheet 裁掉，
    // 「取消 / 确定」正好是最后一行 —— 用户看到的就是"按钮不见了，退不出去"。
    //
    // 竖屏内容放得下，保持原样（不加高度约束，面板贴合内容，不会空一大截）；
    // 横屏只给**选项区**一个高度上限、让它自己滚，底部按钮固定。
    //
    // 为什么不把整个面板高度钉死（上一版是 height(screenHeight - 76) + weight）：
    // 面板高度一旦由 weight 撑开，就要等测量完成才知道真实高度，M3 会先按一个
    // 估算高度弹上去、下一帧拿到真实高度再补一次位移 —— 肉眼看到的就是
    // "横屏展开菜单会弹一下"（第 19 轮 10）。改成 heightIn 之后高度完全由内容决定，
    // 首帧就是最终高度，不再有第二次位移。
    // 窗口尺寸不能读 LocalConfiguration.screenHeightDp：强制旋转（设置里锁横屏、
    // 或系统刚转过来那一帧）时它可能还是竖屏的 851dp，横屏判断就失效了 ——
    // 面板按"竖屏放得下"排布，结果整块被顶出屏幕上沿、按钮看不见。
    // LocalWindowInfo 给的是窗口当前真实像素尺寸，旋转后立刻是对的。
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val windowHeight = with(density) { windowInfo.containerSize.height.toDp() }
    val compact = windowHeight < 560.dp
    val scrollState = rememberScrollState()
    // 标题 + 分割线 + 按钮 + 上下留白约 190dp，扣掉后的部分给选项区滚动
    val optionsMaxHeight = (windowHeight - 210.dp).coerceAtLeast(140.dp)

    // M3 的 ModalBottomSheet 已换成 AppBottomSheet，原因与动画参数见 AppBottomSheet.kt 文件头：
    // 它靠"布局偏移"逐帧挪面板、靠一条很软的空间弹簧收尾，横屏位移大时就是
    // "先卡一下、再慢慢往上爬"。换成 graphicsLayer 位移 + 固定时长之后，
    // 面板高度仍完全由内容决定（下面这套 heightIn 不改），动画与测量彻底解耦。
    AppBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                // 保险带：内容无论如何不许超过窗口高度（否则整块被顶出屏幕上沿）
                .heightIn(max = (windowHeight - 16.dp).coerceAtLeast(200.dp))
                // 导航栏内边距由 AppBottomSheet 统一加（它知道自己是贴底的面板），
                // 这里再加一次就成了双倍，横屏时底部会空出一整条
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // 选项区：横屏可滚，竖屏不滚（weight 只在高度确定时才有效，见上）
            Column(
                Modifier
                    .then(
                        if (compact) Modifier.heightIn(max = optionsMaxHeight).verticalScroll(scrollState)
                        else Modifier,
                    ),
            ) {
            if (browseMode != null) {
                SectionTitle("媒体浏览方式")
                val modes = listOf(1 to "文件夹", 2 to "视频")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = mode == value,
                            onClick = {
                                mode = value
                                preview()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContentColor = MaterialTheme.colorScheme.primary,
                                activeBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
            }

            if (showLayout) {
                SectionTitle("媒体布局")
                val layouts = listOf(false to "列表", true to "网格")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    layouts.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = grid == value,
                            onClick = {
                                grid = value
                                preview()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, layouts.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContentColor = MaterialTheme.colorScheme.primary,
                                activeBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                            icon = {
                                Icon(
                                    if (value) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        ) { Text(label) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
            }
            SectionTitle("排序方式")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEach { entry ->
                    TextIconToggle(
                        text = entry.chipLabel,
                        icon = entry.chipIcon(),
                        selected = key == entry,
                        onClick = {
                            key = entry
                            preview()
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            val (ascendingLabel, descendingLabel) = key.orderLabels()
            val orders = listOf(true to ascendingLabel, false to descendingLabel)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                orders.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = ascending == value,
                        onClick = {
                            ascending = value
                            preview()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, orders.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContentColor = MaterialTheme.colorScheme.primary,
                            activeBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                        icon = {
                            Icon(
                                if (value) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    ) { Text(label) }
                }
            }
            }  // ← 选项区 Column 结束（横屏可滚范围到此为止）

            // 按钮固定在面板底部：横屏滚动选项时它始终可见
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = {
                    onApply(grid, key, ascending)
                    if (browseMode != null) onApplyBrowseMode?.invoke(mode)
                    onDismiss()
                }) { Text("确定") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
    )
}

/** nextplayer 风格的「图标按钮 + 文字」切换项 */
@Composable
private fun TextIconToggle(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        FilledIconToggleButton(checked = selected, onCheckedChange = { onClick() }) {
            Icon(
                imageVector = if (selected) Icons.Rounded.Check else icon,
                contentDescription = text,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
