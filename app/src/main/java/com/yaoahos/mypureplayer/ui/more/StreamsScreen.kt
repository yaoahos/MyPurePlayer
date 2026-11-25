package com.yaoahos.mypureplayer.ui.more

import com.yaoahos.mypureplayer.ui.components.GlassDialogButton
import com.yaoahos.mypureplayer.ui.components.GlassTextField
import com.yaoahos.mypureplayer.ui.components.GlassButtonTone
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.yaoahos.mypureplayer.ui.components.collectPressedWithTap
import com.yaoahos.mypureplayer.ui.components.flashClick
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.MediaSource
import com.yaoahos.mypureplayer.data.StreamItem
import com.yaoahos.mypureplayer.data.VideoItem
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.ConfirmDialog
import com.yaoahos.mypureplayer.ui.components.EmptyState
import com.yaoahos.mypureplayer.ui.components.FabInsets
import com.yaoahos.mypureplayer.ui.components.GlassFab
import com.yaoahos.mypureplayer.ui.components.InfoRow
import com.yaoahos.mypureplayer.ui.components.ListSelection
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.SelectionAction
import com.yaoahos.mypureplayer.ui.components.SelectionActionBar
import com.yaoahos.mypureplayer.ui.components.SelectionBadge
import com.yaoahos.mypureplayer.ui.components.alertDialogGlassSurface
import com.yaoahos.mypureplayer.ui.components.appGlass
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.mediaCardOutline
import com.yaoahos.mypureplayer.ui.components.selectionCardOutline
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 串流地址列表。
 *
 * 交互与「本地视频」对齐：**整行点击 = 播放，长按 = 动作面板**。
 * 早先每行右侧挂着「播放」「删除」两颗按钮，卡片被挤得只剩一小段标题，
 * 而且同一件事在本地页是长按、在这里是点小按钮，两个页面像两个应用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamsScreen(navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val streams by Graph.streams.streams.collectAsStateWithLifecycle()
    // 编辑器：编辑时带目标，新建时为 null
    var editorOpen by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<StreamItem?>(null) }
    var infoTarget by remember { mutableStateOf<StreamItem?>(null) }
    var pendingDelete by remember { mutableStateOf<List<StreamItem>?>(null) }
    // 长按进入选择态，不再弹动作面板
    val selection = remember { ListSelection() }
    val selectedStreams = remember(streams, selection.ids) {
        streams.filter { selection.contains(it.id.toString()) }
    }
    val soloStream = selectedStreams.singleOrNull()

    LaunchedEffect(Unit) { Graph.streams.load() }

    BackHandler(enabled = selection.active) { selection.exit() }

    fun play(stream: StreamItem) {
        Playback.playSingle(stream.toVideoItem())
        navController.navigate(Routes.PLAYER)
    }

    Scaffold(
        // 透明容器：背景由根部的常驻玻璃层统一绘制，内部页面与底栏页共用同一张底
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // 页面级不透明背景：转场时遮住下面那一页，避免残影
        modifier = Modifier.fillMaxSize().glassPageBackground(),
        topBar = {
            if (selection.active) {
                PageTopBar(
                    title = "已选择 ${selectedStreams.size} 项",
                    subtitle = "轻点勾选或取消 · 右上角可全选",
                    onBack = { selection.exit() },
                    backIcon = Icons.Rounded.Close,
                    backLabel = "退出选择",
                    actions = {
                        val allSelected = streams.isNotEmpty() && selectedStreams.size == streams.size
                        IconButton(onClick = {
                            if (allSelected) selection.clear()
                            else selection.replaceAll(streams.map { it.id.toString() })
                        }) {
                            Icon(
                                Icons.Rounded.SelectAll,
                                contentDescription = if (allSelected) "取消全选" else "全选",
                            )
                        }
                    },
                )
            } else {
                PageTopBar(
                    title = "串流",
                    subtitle = "http / https / rtsp / rtsps 网络地址",
                    onBack = { navController.popBackStack() },
                )
            }
        },
        bottomBar = {
            if (selection.active) {
                val canAct = selectedStreams.isNotEmpty()
                SelectionActionBar(
                    actions = listOf(
                        SelectionAction(Icons.Rounded.PlayArrow, "播放", enabled = canAct && soloStream != null) {
                            soloStream?.let { play(it) }
                            selection.exit()
                        },
                        SelectionAction(Icons.Rounded.Info, "详情", enabled = canAct && soloStream != null) {
                            soloStream?.let { infoTarget = it }
                        },
                        // 编辑是高频操作，放在第三格；删除等危险操作仍在「更多」里
                        SelectionAction(Icons.Rounded.Edit, "编辑", enabled = canAct && soloStream != null) {
                            if (soloStream != null) { editorTarget = soloStream; editorOpen = true }
                            selection.exit()
                        },
                        // 复制「整条地址条目」：名称加「-副本」，地址原样带走。
                        // 用于同一地址配多份备注/多套参数（原来那个「复制链接」只把 URL 放剪贴板，
                        // 与条目本身无关，已随本次调整去掉）。
                        SelectionAction(Icons.Rounded.FileCopy, "创建副本", enabled = canAct) {
                            val targets = selectedStreams.toList()
                            scope.launch {
                                targets.forEach { Graph.streams.add(name = "${it.name}-副本", url = it.url) }
                                Notice.success(
                                    if (targets.size == 1) "已创建副本" else "已创建 ${targets.size} 个副本",
                                )
                            }
                            selection.exit()
                        },
                        // 删除：确认框负责二次确认
                        SelectionAction(Icons.Rounded.DeleteOutline, "删除", enabled = canAct, danger = true) {
                            // 只弹确认框；确认删除后才退出选择态，取消时保留选中项
                            pendingDelete = selectedStreams.toList()
                        },
                    ),
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
    ) { padding ->
        // 悬浮按钮放在内容层（不是 Scaffold 的 FAB 槽）：
        // 槽位贴着屏幕底边，而底栏页的按钮在底栏之上，来回切页会看到它在同一屏里跳 80dp。
        // 这里补上底栏那段高度，四页的「+」落在同一条水平线上。
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (streams.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        icon = Icons.Rounded.Podcasts,
                        title = "还没有串流地址",
                        message = "支持 http / https / rtsp / rtsps 地址。点右下角 + 新建串流。",
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PageMetrics.HPad,
                        end = PageMetrics.HPad,
                        top = 8.dp,
                        bottom = 100.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(streams, key = { it.id }) { stream ->
                        StreamRow(
                            stream = stream,
                            onClick = {
                                if (selection.active) selection.toggle(stream.id.toString())
                                else flashClick { play(stream) }
                            },
                            onLongClick = { selection.enter(stream.id.toString()) },
                            selected = selection.contains(stream.id.toString()),
                            selecting = selection.active,
                        )
                    }
                }
            }
            GlassFab(
                onClick = { editorTarget = null; editorOpen = true },
                contentDescription = "新建串流",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // 串流是内层页面（整屏覆盖层），底部不再有底栏，不用再让出那一截
                    .padding(end = FabInsets.margin, bottom = FabInsets.margin),
            )
        }
    }

    infoTarget?.let { stream ->
        StreamInfoDialog(stream = stream, onDismiss = { infoTarget = null })
    }

    if (editorOpen) {
        StreamEditorDialog(
            initial = editorTarget,
            onDismiss = { editorOpen = false; editorTarget = null },
            onSave = { name, url ->
                val target = editorTarget
                scope.launch {
                    if (target != null) {
                        Graph.streams.update(target.id, name, url)
                        editorOpen = false
                        editorTarget = null
                    } else {
                        Graph.streams.add(name, url)
                        editorOpen = false
                        Playback.playSingle(
                            VideoItem(
                                id = "stream:$url",
                                title = name,
                                fileName = name,
                                uri = url,
                                source = MediaSource.STREAM,
                                folderName = "串流",
                            ),
                        )
                        navController.navigate(Routes.PLAYER)
                    }
                }
            },
        )
    }

    pendingDelete?.let { targets ->
        ConfirmDialog(
            title = "删除串流",
            message = if (targets.size == 1) {
                "确定删除「${targets.first().name}」吗？删除后需要重新添加这条地址。"
            } else "选中的 ${targets.size} 条串流地址将被删除。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                scope.launch { targets.forEach { Graph.streams.delete(it.id) } }
                pendingDelete = null
                selection.exit()
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

private fun StreamItem.toVideoItem(): VideoItem = VideoItem(
    id = "stream:$id",
    title = name,
    fileName = name,
    uri = url,
    source = MediaSource.STREAM,
    folderName = "串流",
)

/**
 * 一行串流地址。
 *
 * 只留「图标 + 名称 + 地址」，右侧不再有任何按钮：播放交给整行点击，
 * 其余动作都在长按面板里。与本地视频的行卡同一套材质与按压反馈。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamRow(
    stream: StreamItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean = false,
    selecting: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedWithTap()
    Row(
        Modifier
            .fillMaxWidth()
            .let { base ->
                if (selected) base.selectionCardOutline(pressed = pressed, selected = true, selecting = true)
                else base.mediaCardOutline(pressed)
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onClickLabel = if (selecting) "选中/取消选择" else "播放",
                onLongClick = onLongClick,
                onLongClickLabel = "选择",
                role = Role.Button,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(PageMetrics.IconTileRadius))
                .background(appGlass().tintSoft),
            contentAlignment = Alignment.Center,
        ) {
            SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(4.dp))
            Icon(
                Icons.Rounded.Podcasts,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                stream.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stream.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 串流详情：名称、地址（可复制）、协议、添加时间 */
@Composable
private fun StreamInfoDialog(stream: StreamItem, onDismiss: () -> Unit) {
    val scheme = stream.url.substringBefore("://", "").ifBlank { "未知" }.uppercase(Locale.ROOT)
    val added = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(stream.id))
    AlertDialog(
        shape = RoundedCornerShape(26.dp),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.alertDialogGlassSurface(),
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text("串流详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("名称", stream.name)
                InfoRow("地址", stream.url, copyable = true)
                InfoRow("协议", scheme)
                InfoRow("添加时间", added)
            }
        },
        confirmButton = { GlassDialogButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 新建 / 编辑串流。
 *
 * [initial] 为空即新建：保存后直接进播放页（这是新建入口一直以来的行为）。
 * 编辑时保存只改回列表，不自动播放 —— 用户点的是"改个地址"，不是"现在就看"。
 */
@Composable
private fun StreamEditorDialog(
    initial: StreamItem?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val editing = initial != null

    // 校验与保存抽成一个动作：确认按钮与地址栏键盘 Done 共用，避免两处规则漂移
    val submit = {
        val clean = url.trim()
        val scheme = clean.substringBefore("://", "").lowercase()
        when {
            clean.isBlank() -> error = "请填写串流地址"
            scheme !in SUPPORTED_SCHEMES -> error = "暂不支持该协议（支持 http/https/rtsp/rtsps）"
            else -> onSave(name.trim().ifBlank { clean.substringAfterLast('/') }, clean)
        }
        Unit
    }

    AlertDialog(
        shape = RoundedCornerShape(26.dp),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.alertDialogGlassSurface(),
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "编辑串流" else "新建串流") },
        text = {
            Column {
                GlassTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                GlassTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "地址",
                    placeholder = "http:// 或 rtsp://",
                    // 地址栏用 URI 键盘且键盘 Done 直接保存，不用手动收起键盘再点按钮
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            GlassDialogButton(onClick = { submit() }, tone = GlassButtonTone.Accent) {
                Text(if (editing) "保存" else "保存并播放")
            }
        },
        dismissButton = {
            GlassDialogButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// 只收 Media3 真有 DataSource 的 scheme：http/https 走 DefaultHttpDataSource，
// rtsp/rtsps 走 media3-exoplayer-rtsp。udp/rtp 没有对应数据源（SmbRoutingDataSource
// 会落到 DefaultDataSource 并抛 "Unsupported URI scheme"），放行只会「存下来却打不开」。
// HLS/DASH 仍走 http(s)，由 media3-exoplayer-hls/dash 按 URL 自动识别，无需单列 scheme。
private val SUPPORTED_SCHEMES = setOf(
    "http", "https", "rtsp", "rtsps",
)
