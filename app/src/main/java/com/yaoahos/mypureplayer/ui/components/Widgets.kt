package com.yaoahos.mypureplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yaoahos.mypureplayer.R
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.MediaSource
import com.yaoahos.mypureplayer.data.VideoItem
import kotlinx.coroutines.flow.first
import com.yaoahos.mypureplayer.media.Thumbnails
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Handler
import android.os.Looper

/**
 * 会立刻跳页/播放的卡片专用点击包装。
 *
 * 直接把 onClick 交给 combinedClickable 时，抬起与页面跳转落在同一帧，
 * 卡片当场被销毁，按压层一次都没画出来（真机 30fps 录屏确认）。
 * 这里用主线程 Handler 延后一小段再执行动作，让 [GlassPress.TAP_MS] 的淡片
 * 至少亮一次；Handler 不随 Compose 组合销毁，所以跳页后动作仍会照常执行。
 */
@Composable
fun rememberFlashClick(onClick: () -> Unit): () -> Unit {
    val current by rememberUpdatedState(onClick)
    val handler = remember { Handler(Looper.getMainLooper()) }
    return remember(handler) {
        {
            handler.postDelayed({ current() }, GlassPress.ACTION_DELAY_MS)
        }
    }
}

/**
 * 视频缩略图：内存/磁盘缓存命中时秒开，未命中时先铺一层柔和渐变占位再替换，
 * 避免列表滚动时出现大片空白。
 */
@Composable
fun VideoThumb(
    item: VideoItem,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    showIcon: Boolean = true,
) {
    val context = LocalContext.current
    // 订阅服务器列表而非 remember 缓存：首帧组合时 servers 可能还没加载完，
    // 缓存一个 null 会导致该缩略图永远走不到 HTTP 抽帧分支。
    val servers by Graph.servers.servers.collectAsStateWithLifecycle()
    // 匹配规则与播放一致：共享已经不在当前配置里的旧条目不去连，直接没有封面
    val server = remember(item.id, item.uri, servers) {
        if (item.source == MediaSource.SMB) {
            Graph.servers.forSmbItem(item.serverId, item.uri)
        } else null
    }
    // 播放登记「已播放」后 playedVersion 变化，触发重新取缩略图（低频事件，可作 key）
    val playedVersion by Thumbnails.playedVersion.collectAsStateWithLifecycle()
    // 「重置缩略图」后 epoch 变化：没有这个 key，卡片会一直显示手里那张旧图
    val thumbEpoch by Thumbnails.epoch.collectAsStateWithLifecycle()
    val bitmap by produceState<BitmapHolder?>(
        initialValue = null,
        item.id,
        item.dateModified,
        item.sizeBytes,
        server?.id,
        playedVersion,
        thumbEpoch,
    ) {
        value = Thumbnails.load(context, item, server)?.let { BitmapHolder(it) }
        // 全局 thumbVersion（每次后台抽帧落盘 +1）不能放进 key：那会让「一张图完成」
        // 重启当前屏幕上所有卡片的 produceState，几十张卡片集体重取。改成只有
        // 还没拿到图的卡片才等待版本号变化后自行重试。
        //
        // 重试必须错开、但不能设次数上限：版本号是全局的，完成一张会把这一屏所有没图的
        // 卡片同时叫醒，不打散就成了「每完成一张、其余全体重新排一遍抽帧队列」的雪崩；
        // 而设了上限更糟 —— 一屏二十几张时，排在后半段的卡片前几轮都还没轮到，
        // 次数用完就永久停在占位图标上（实测 24 张全部抽帧成功，界面却只更新了 10 张）。
        var tries = 0
        while (value == null) {
            val seen = Thumbnails.thumbVersion.value
            Thumbnails.thumbVersion.first { it != seen }
            tries++
            // 越靠后等得越久，把重试均匀摊开，别和别人的新请求撞在一起
            kotlinx.coroutines.delay(80L * tries.coerceAtMost(8) + (0..400).random())
            if (value != null) break
            val bmp = Thumbnails.load(context, item, server)
            if (bmp != null) value = BitmapHolder(bmp)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        val holder = bitmap
        if (holder != null) {
            // 有缩略图就铺满展示，不叠加任何背景区域
            Image(
                bitmap = holder.bitmap.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (showIcon) {
            // 没有缩略图时直接展示影片图标，不再加灰白色背景块去"扩充"成一张卡片的形状。
            // 图标按容器尺寸缩放，本身留白由图标的矢量 padding 提供，视觉上就是一枚裸图标。
            val iconSize = (maxWidth * 0.68f).coerceIn(28.dp, 80.dp)
            Icon(
                imageVector = Icons.Rounded.Movie,
                contentDescription = item.title,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** 用引用包一层，避免 Bitmap 参与 Compose 相等性比较带来的额外开销 */
class BitmapHolder(val bitmap: android.graphics.Bitmap)

/**
 * 全站统一的尺寸刻度。
 *
 * 这些数字以前散落在各页面里各写一遍，结果是同一层级的元素在不同页上差 2~6dp：
 * 行卡片圆角 18 / 20 / 24 三种、页面左右边距 12 / 16 / 20 三种、
 * 行内边距 6 / 10 / 14 / 16 四种。单看每页都过得去，来回切页就能看到内容在跳。
 *
 * 现在只在这里定一次，页面引用常量：
 *   [HPad]           页面内容左右边距（标题与之对齐，返回键也落在同一条线上）
 *   [CardRadius]     任何"一整块卡/一整行卡"的圆角
 *   [ThumbRadius]    卡片内缩略图的圆角（比卡片小一档，读起来是"嵌在里面"）
 *   [IconTileRadius] 行内方形图标底片（42dp）的圆角
 *   [PanelRadius]    弹窗 / 抽屉 / 底部面板的圆角
 */
object PageMetrics {
    val HPad = 16.dp
    val CardRadius = 20.dp
    val ThumbRadius = 12.dp
    val IconTileRadius = 14.dp
    val PanelRadius = 26.dp

    // 浮层宽度上限：手机上不生效（屏幕本就没这么宽），平板/横屏防止弹窗与底栏被拉满整屏。
    // 底部面板、排列排序弹窗、四格底栏玻璃条与 Dialog 统一到同一档宽度并居中，
    // 否则横屏下一个内容很少的排序弹窗也会横贯整屏，显得又宽又空。
    val DialogMaxWidth = 520.dp
    val SheetMaxWidth = 520.dp
}

/**
 * 列表多选状态。key 由各页面自己定义（保险箱用 v:/d: 前缀区分视频和文件夹）。
 * 用 Set 而不是状态列表：数量变化时只重组订阅了 [ids] 的地方。
 */
class ListSelection {
    var ids by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * 是否处于多选模式。必须和 [ids] 分开：
     * 「取消全选」只是把勾选项清空，用户仍处于选择态（顶栏仍是「已选择 0 项」+ X），
     * 只有点 X / 返回键才是真正退出。早期直接从 ids 推导 active，
     * 一点「取消全选」就退出选择态，用户还要重新长按，属于明显的操作回退。
     */
    var active by mutableStateOf(false)
        private set

    val count: Int get() = ids.size

    fun contains(id: String): Boolean = id in ids

    /** 长按进入选择态并选中这一条 */
    fun enter(id: String) {
        active = true
        if (id !in ids) ids = ids + id
    }

    /**
     * 进入选择态但一条都不选。
     *
     * 「添加视频」这类页面要求一进来就是空选择状态（复选框常驻、可以做全选），
     * 与长按进入的语义不同：这里没有"被长按的那一条"，不能借用 [enter]。
     */
    fun begin() {
        active = true
        ids = emptySet()
    }

    fun toggle(id: String) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun select(id: String) {
        if (id !in ids) ids = ids + id
    }

    fun replaceAll(ids: Collection<String>) {
        this.ids = ids.toSet()
    }

    /** 取消全选：只清勾选项，保留选择态 */
    fun clear() {
        ids = emptySet()
    }

    /** 退出选择态 */
    fun exit() {
        active = false
        ids = emptySet()
    }
}

/**
 * 多选选中角标：用主题色实心对勾放在缩略图右上角。
 * 默认不绘制，所以其它页面的卡片不受影响。
 */
@Composable
fun SelectionBadge(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!selected) return
    Icon(
        Icons.Rounded.CheckCircle,
        contentDescription = "已选择",
        tint = com.yaoahos.mypureplayer.ui.components.appGlass().accent,
        modifier = modifier.size(20.dp),
    )
}

/**
 * 多选态的卡片边界：选中 = 2dp 主题色描边 + 主题色薄纱；
 * 未选中但处于多选态 = 1dp 次级描边。底材沿用玻璃卡，不退化成一块纯色板。
 */
@Composable
fun Modifier.selectionCardOutline(
    selected: Boolean,
    selecting: Boolean,
    pressed: Boolean = false,
): Modifier {
    if (!selected && !selecting) return mediaCardOutline(pressed = pressed)
    val shape = RoundedCornerShape(PageMetrics.CardRadius)
    // 用用户当前主题色（与玻璃卡薄纱同源），而不是系统 primary，避免换主题后描边与底色串色。
    val accent = com.yaoahos.mypureplayer.ui.components.appGlass().accent
    // 按压走全站统一的 [glassPressOverlay]：早先按下是把整张卡切成 tinted
    // （主题色 12%），与卡片自己的淡蓝底只差 2~3/255，按下去几乎没有反馈。
    val base = appGlassSurface(
        shape = shape,
        tinted = selected,
        elevation = if (selected) 1.dp else 3.dp,
    ).glassPressOverlay(pressed, shape, foreground = true)
    val outlined = base.border(
        width = if (selected) 2.dp else 1.dp,
        color = if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        shape = shape,
    )
    if (!selected) return outlined
    return outlined.drawBehind { drawRect(accent.copy(alpha = 0.10f)) }
}

/**
 * 文件夹 / 视频 / 网络位置共用的卡片边界。
 *
 * **全站唯一卡片外观入口**：视频卡、文件夹卡、连接卡、更多页入口行、设置分组都用它。
 * 它现在直接把工作交给 [appGlassSurface]，不再自己"平色 + 描边"画一遍 ——
 * 那套画法没有片体渐变和斜向高光，与设置页的玻璃卡放在一屏里就是两种材质：
 * 一个像玻璃片，一个像磨砂色块。
 *
 * 按下时叠一层全站统一的按压淡片（[glassPressOverlay]），强度与节奏和设置页的行完全一致。
 * 早先按下是把整卡切成 [appGlassSurface] 的 `tinted`（主题色 12%）：卡片本身已经带
 * 同色相的底，两者只差 2~3/255，用户反馈"文件夹、视频点了没反应"。
 *
 * [selected] 用于「正在播放的那一行」。再加两条**形状**信号：整片 10% 强调色薄纱 +
 * 左边一根 3.5dp 圆头竖条。形状比颜色更容易在余光里被认出来，也不必把底色压得很重。
 */
@Composable
fun Modifier.mediaCardOutline(
    pressed: Boolean = false,
    selected: Boolean = false,
): Modifier {
    val accent = com.yaoahos.mypureplayer.ui.components.appGlass().accent
    val shape = RoundedCornerShape(PageMetrics.CardRadius)
    val base = appGlassSurface(
        shape = shape,
        tinted = selected,
        elevation = if (selected) 1.dp else 3.dp,
    ).glassPressOverlay(pressed, shape, foreground = true)
    if (!selected) return base
    return base.drawBehind {
        drawRect(accent.copy(alpha = 0.10f))
        val w = 3.5.dp.toPx()
        val inset = 9.dp.toPx()
        drawRoundRect(
            color = accent,
            topLeft = androidx.compose.ui.geometry.Offset(0f, inset),
            size = androidx.compose.ui.geometry.Size(w, (size.height - inset * 2f).coerceAtLeast(0f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
        )
    }
}

/**
 * 分区标题：一条短主题色竖线 + 文字。
 *
 * 本地页的「文件夹 / 视频」、设置页的「外观 / 播放 / 存储」、关于页的「功能范围 / 权限说明」
 * 原来有三种写法（带竖条 / 纯彩色小字 / 纯灰字），同一 App 里像三个不同的设计。
 * 现在统一走这一个组件：竖条高度、文字字号、左侧缩进都唯一。
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = if (compact) 13.dp else 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 旧名保留：本地页仍在用，行为与 [SectionLabel] 完全一致 */
@Composable
fun MediaSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) = SectionLabel(text = text, modifier = modifier)

/**
 * 按屏幕宽度自适应宫格的最小列宽：不写死列数，由 [androidx.compose.foundation.lazy.grid.GridCells.Adaptive]
 * 自己算能放几列。
 *
 * 每一档的值是**按目标列数反推**的，不是"屏幕越大格子越大"：
 * 列数 = floor((可用宽 + 间距) / (minSize + 间距))，间距 10dp、页面左右各 16dp。
 *
 * 手机竖屏原来给 84dp —— 393dp 屏可用 361dp，正好差 1.25dp 放不下第 4 列，
 * 于是掉成 3 列、每格宽 113dp，一行只有三张、每张巨大（用户反馈"一行三个有点太大了"）。
 * 收到 76dp 之后同样宽度稳稳落在 4 列、每格 82.75dp，数量与大小同时改善。
 *
 * 手机横屏（约 851dp）给 124dp → 6 列，每格约 128dp；平板竖屏 5 列、大平板逐步加列。
 */
@Composable
fun adaptiveMediaGridMinSize(): Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    // 大屏不再沿用手机的「最小列宽」：平板竖屏约 813dp 时若是 104dp，
    // 会排成 7 列、单卡只有 90dp 左右，缩略图小到看不清。按屏幕档位放大单卡，
    // 平板竖屏稳定在 5 列、横屏 6~7 列，视觉密度与手机竖屏（4 列）保持接近。
    return when {
        widthDp >= 1280 -> 176.dp  // 大平板横屏：约 6~7 列
        widthDp >= 1000 -> 156.dp  // 平板横屏：约 6 列
        widthDp >= 700 -> 148.dp   // 平板竖屏：约 5 列
        widthDp >= 480 -> 112.dp   // 小平板、折叠屏展开、分屏：约 4 列
        else -> 76.dp              // 手机竖屏：4 列
    }
}

/**
 * 列表模式下的多列最小列宽。
 *
 * 手机竖屏给一个很大的值 => 强制单列，行为与旧版一致；
 * 平板/宽屏按此值自动分 2~3 列，避免单列被拉到一千多 dp、右侧留一大片空白。
 * 每列至少 480dp，保证 132dp 缩略图 + 标题 + 时长仍有充足空间。
 */
@Composable
fun listRowMinSize(): Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp >= 1600 -> 560.dp  // 大平板横屏：约 3 列
        widthDp >= 1100 -> 520.dp  // 平板横屏：约 2 列
        // 平板竖屏约 813dp：480dp 只能排 1 列，行会被拉得很宽；降到 380dp 得到 2 列。
        widthDp >= 700 -> 380.dp   // 平板竖屏：约 2 列
        else -> 1_000.dp           // 手机：强制单列
    }
}

/**
 * 密码框右侧的眼睛图标：点一下在明文与掩码之间切换。
 * 统一用它代替「显示 / 隐藏」文字按钮，输入框右侧宽度固定、不跳动。
 */
@Composable
fun PasswordEye(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            contentDescription = if (visible) "隐藏密码" else "显示密码",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun DurationBadge(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.62f),
        contentColor = androidx.compose.ui.graphics.Color.White,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** 宫格卡片 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VideoGridCard(
    item: VideoItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    progress: Float? = null,
    selected: Boolean = false,
    selecting: Boolean = false,
    /** 回收站等页面需要显示原位置等信息时传入；不传则沿用全站统一的「时长 · 大小」。 */
    metaOverride: String? = null,
    metaMaxLines: Int = 1,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedWithTap()
    val flashClick = rememberFlashClick(onClick)
    // 卡片宽度随屏幕变化很大（手机窄列 / 平板宽列），字号跟着分档，避免窄卡片标题更挤、宽卡片字太小
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 100.dp
        // 图片区独立成块，文字信息脱离背景直接排在下方并居中，不再有整卡的浅色底
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectionCardOutline(pressed = pressed, selected = selected, selecting = selecting)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = flashClick,
                    onClickLabel = if (selecting) "选中/取消选择" else "播放",
                    onLongClick = onLongClick,
                    onLongClickLabel = if (onLongClick != null) "更多操作" else null,
                    role = Role.Button,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 缩略图左右不再内缩：直接铺满卡片宽度，由卡片自身的圆角裁切，
            // 一格里能看到的画面更大，一行也就能多放一格。
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            ) {
                VideoThumb(item, Modifier.fillMaxSize(), cornerRadius = 0.dp)
                SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 标题固定单行：不同长度的片名不会因为占 1 行还是 2 行把卡片撑成不同高度。
                Text(
                    text = item.title,
                    style = if (compact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().height(18.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaOverride ?: videoMeta(item, withSize = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = metaMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                // 外部传入的固定进度优先；否则订阅历史，删记录后进度条自己会消失
                val ratio = if (progress != null) progress else watchProgress(item)
                if (ratio > 0f) {
                    Spacer(Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { ratio.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(0.86f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

/** 列表行 */
/**
 * 列表行容器：铺满可用宽度，不再限宽居中。
 *
 * 旧版把行限到 720dp 居中，在平板上会在两侧留下大片空白；现在列表布局本身
 * 在宽屏会切换为多列网格，每行自然铺满自己那一列，因此这里只需要铺满即可。
 */
@Composable
fun ListRowContainer(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) { content() }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VideoListRow(
    item: VideoItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    selecting: Boolean = false,
    metaOverride: String? = null,
    metaMaxLines: Int = 1,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedWithTap()
    val flashClick = rememberFlashClick(onClick)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectionCardOutline(pressed = pressed, selected = selected, selecting = selecting)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = flashClick,
                onClickLabel = if (selecting) "选中/取消选择" else "播放",
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "更多操作" else null,
                role = Role.Button,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 132.dp, height = 76.dp)) {
            VideoThumb(item, Modifier.fillMaxSize(), cornerRadius = 14.dp)
            SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = metaOverride ?: videoMeta(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = metaMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            val ratio = watchProgress(item)
            if (ratio > 0f) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { ratio.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/**
 * 视频的有效时长：先看列表项自带的 durationMs，再退到抽帧时记下的缓存，
 * 最后用历史记录里的总时长。网络视频的时长只能靠抽帧补齐，所以排序要用它而不是直接读 durationMs。
 */
fun mediaDuration(item: VideoItem): Long {
    val known = Thumbnails.displayDuration(item)
    return if (known > 0L) known else Graph.history.historyDuration(item.id, item.source)
}

/**
 * 续播位置（毫秒）：订阅历史表，而不是读一次快照。
 *
 * 原来这里直接调 [Graph.history.resumePosition]，拿到的是「组合那一刻」的值。
 * 删掉历史后历史表变了，但卡片没有订阅它，于是进度条一直留在原位，
 * 必须手动下拉刷新触发重组才消失。
 */
@Composable
fun rememberResumePosition(item: VideoItem): Long {
    val entries by Graph.history.history.collectAsStateWithLifecycle()
    return remember(entries, item.id, item.source) {
        entries.firstOrNull { it.videoId == item.id && it.source == item.source }?.positionMs ?: 0L
    }
}

/** 卡片进度条比例：位置与总时长都走可订阅的来源，历史增删后立即跟着变。 */
@Composable
private fun watchProgress(item: VideoItem): Float {
    val position = rememberResumePosition(item)
    val total = rememberMediaDuration(item)
    return if (total > 0L && position > 0L) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * 卡片副标题只保留「时长 · 大小」两项：
 * 观看进度交给卡片底部的进度条表达，不再堆「已看 / 百分比 / 总时长」三段文字。
 */
@Composable
private fun videoMeta(item: VideoItem, withSize: Boolean = true): String {
    val total = rememberMediaDuration(item)
    val parts = mutableListOf<String>()
    if (total > 0L) parts.add(Formats.formatDuration(total))
    // 手机端窄卡片放不下两段信息，只留时长，避免一行被省略号切得零碎
    if (withSize && item.sizeBytes > 0L) parts.add(Formats.formatSize(item.sizeBytes))
    return parts.joinToString(" · ")
}

/**
 * 卡片上要显示的时长。
 *
 * 网络视频的时长要等后台探测完容器才有，所以这里订阅时长版本号：
 * 每补上一个就精确重绘对应的卡片，而不是整页刷新或一直显示 0。
 */
@Composable
fun rememberMediaDuration(item: VideoItem): Long {
    val version by Thumbnails.durationVersion.collectAsStateWithLifecycle()
    return remember(item.id, item.durationMs, version) { mediaDuration(item) }
}


/**
 * 文件夹图形：只画图形本身，底色与圆角由外面的卡片统一决定。
 *
 * 颜色用主色的柔和版本（primary 的 82% 不透明度），和视频缩略图的照片放在一起时
 * 不会因为一块高饱和实心色跳到眼前。
 */
@Composable
private fun FolderArtwork(size: Dp) {
    Icon(
        painter = painterResource(R.drawable.folder_card),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
        modifier = Modifier.size(size),
    )
}

/** 文件夹宫格卡片：本地与网络共用，保持同一套视觉语言 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaFolderGridCard(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    selecting: Boolean = false,
    subtitleMaxLines: Int = 1,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedWithTap()
    val flashClick = rememberFlashClick(onClick)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 100.dp
        // 图标放大到图形区的六成多，视觉上和铺满的缩略图同一个体量，
        // 不再出现「小图标浮在一大块空白中间」的空旷感
        val iconSize = (maxWidth * 0.72f).coerceIn(42.dp, 130.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectionCardOutline(pressed = pressed, selected = selected, selecting = selecting)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = flashClick,
                    onClickLabel = if (selecting) "选中/取消选择" else "打开",
                    onLongClick = onLongClick,
                    onLongClickLabel = if (onLongClick != null) "更多操作" else null,
                    role = Role.Button,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 图形区与缩略图同尺寸：图标铺满这一块，卡片边界即内容边界
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center,
            ) {
                // 直接展示文件夹图标，不加灰白色背景块去扩充成卡片形状
                FolderArtwork(size = iconSize)
                SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 标题固定单行并占固定高度，保证长短名称的卡片高度完全一致。
                Text(
                    name,
                    style = if (compact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().height(18.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 文件夹列表行 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaFolderRow(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    selecting: Boolean = false,
    showSubtitle: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedWithTap()
    val flashClick = rememberFlashClick(onClick)
    Row(
        Modifier
            .fillMaxWidth()
            .selectionCardOutline(pressed = pressed, selected = selected, selecting = selecting)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = flashClick,
                onClickLabel = if (selecting) "选中/取消选择" else "打开",
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "更多操作" else null,
                role = Role.Button,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 与视频行同尺寸的图位、左边缘对齐；图标放大到与缩略图同一视觉重量
        Box(
            Modifier
                .size(width = 132.dp, height = 76.dp),
            contentAlignment = Alignment.Center,
        ) {
            FolderArtwork(size = 64.dp)
            SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        // 列表行只保留单行标题：与左侧图标严格垂直居中齐平。
        // 副标题（视频数量）会增加一行，把标题顶离图标中心，正是「标题与图标不齐平」的成因。
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSubtitle && subtitle.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
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
}

/** 内联搜索框 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    placeholder: String = "搜索视频名称",
) {
    // 搜索框一出现就自动聚焦并弹出软键盘，省去用户再点一次输入框。
    // 必须 remember：直接 new 出来的实例每次重组都会换新，LaunchedEffect 持有的
    // 还是旧实例，requestFocus 就永远落空了。
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    GlassTextField(
        value = query,
        onValueChange = onQueryChange,
        // 搜索框自带语义，不再画一行「搜索」标签；用胶囊形与表单里的方框区分开
        placeholder = placeholder,
        leadingIcon = {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭搜索")
            }
        },
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
    )
}

/** 空状态 */
@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 空状态图标块也走玻璃片体：之前是一块几乎不透明的 panelStrong 矩形，
        // 在满屏玻璃卡片里像贴了个实心色块；圆角同时从 28 收回面板档（26）
        Box(
            Modifier.appGlassSurface(
                shape = RoundedCornerShape(PageMetrics.PanelRadius),
                strong = true,
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(22.dp).size(34.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            // 全站按钮 2026-11-25 已统一为玻璃；这里原来是 M3 的 FilledTonalButton
            // （实色胶囊、自带 M3 水波纹），是空态里唯一没跟上的一处。
            GlassButton(text = actionLabel, onClick = onAction)
        }
    }
}
