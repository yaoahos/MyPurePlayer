package com.yaoahos.mypureplayer.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.core.Formats
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.SortKey
import com.yaoahos.mypureplayer.data.VaultEntry
import com.yaoahos.mypureplayer.data.VaultRepo
import com.yaoahos.mypureplayer.playback.Playback
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.ActionDivider
import com.yaoahos.mypureplayer.ui.components.ActionRow
import com.yaoahos.mypureplayer.ui.components.AdaptiveSheet
import com.yaoahos.mypureplayer.ui.components.EmptyState
import com.yaoahos.mypureplayer.ui.components.FolderInfoDialog
import com.yaoahos.mypureplayer.ui.components.InfoRow
import com.yaoahos.mypureplayer.ui.components.ListSelection
import com.yaoahos.mypureplayer.ui.components.MediaFolderGridCard
import com.yaoahos.mypureplayer.ui.components.MediaFolderRow
import com.yaoahos.mypureplayer.ui.components.MediaSectionLabel
import com.yaoahos.mypureplayer.ui.components.MediaSortDraft
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.SearchField
import com.yaoahos.mypureplayer.ui.components.SelectionAction
import com.yaoahos.mypureplayer.ui.components.SelectionActionBar
import com.yaoahos.mypureplayer.ui.components.SelectionBadge
import com.yaoahos.mypureplayer.ui.components.SelectionMoreSheet
import com.yaoahos.mypureplayer.ui.components.SortLayoutSheet
import com.yaoahos.mypureplayer.ui.components.adaptiveMediaGridMinSize
import com.yaoahos.mypureplayer.ui.components.appGlass
import com.yaoahos.mypureplayer.ui.components.appGlassSurface
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import com.yaoahos.mypureplayer.ui.components.listRowMinSize
import com.yaoahos.mypureplayer.ui.components.mediaCardOutline
import com.yaoahos.mypureplayer.ui.components.selectionCardOutline
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保险箱。
 *
 * 交互与「本地视频」完全一致 —— 搜索、排列与排序、点击播放、长按菜单、文件夹下钻，
 * 唯一的区别是这里的内容是加密的（列表里没有可截的帧，用锁牌代替缩略图）。
 *
 * 之前这里只有一行「锁图标 + 文件名 + 三个小按钮」：点整行没反应（只能点右侧那颗
 * 小小的播放键）、不能搜索、不能排序、不能长按，用户原话是
 * 「保险箱内的样式应该像本地视频一样，区别只是加密了」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val entries by Graph.vault.entries.collectAsStateWithLifecycle()
    val config by Graph.vault.config.collectAsStateWithLifecycle()
    val unlocked by Graph.vault.unlocked.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }

    var setupOpen by remember { mutableStateOf(false) }
    // 打开对话框那一刻是否已经启用过：首次设置完要说"保险箱已启用"，
    // 说"密码已更新"会让第一次用的用户以为点了什么危险操作
    var setupWasEnabled by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var gridMode by rememberSaveable { mutableStateOf(true) }
    var sortName by rememberSaveable { mutableStateOf(SortKey.DATE.name) }
    var ascending by rememberSaveable { mutableStateOf(false) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var sortDraft by remember { mutableStateOf<MediaSortDraft?>(null) }
    var currentFolder by rememberSaveable { mutableStateOf<String?>(null) }

    // 长按 = 选中 + 弹面板；视频 / 文件夹各有一张面板，关掉面板后点按其它条目继续加选。
    val selection = remember { ListSelection() }
    var infoEntry by remember { mutableStateOf<VaultEntry?>(null) }
    var infoFolder by remember { mutableStateOf<VaultFolder?>(null) }
    var restoreTarget by remember { mutableStateOf<List<VaultEntry>?>(null) }
    var deleteTarget by remember { mutableStateOf<List<VaultEntry>?>(null) }
    var folderRestoreTarget by remember { mutableStateOf<List<VaultFolder>?>(null) }
    var folderDeleteTarget by remember { mutableStateOf<List<VaultFolder>?>(null) }

    var disableConfirm by remember { mutableStateOf(false) }
    // 关闭保险箱 = 清掉门禁 + 把内容全部解密搬回原目录，属于不可逆操作。
    // 必须先在同一个对话框里验明身份（密码或指纹），不能只点一下「关闭」就执行。
    var disablePin by remember { mutableStateOf("") }
    var disableError by remember { mutableStateOf<String?>(null) }
    var disableBusy by remember { mutableStateOf(false) }
    // 修改密码前校验旧密码
    var oldPinCheck by remember { mutableStateOf(false) }
    var oldPinText by remember { mutableStateOf("") }
    var oldPinError by remember { mutableStateOf<String?>(null) }
    var oldPinBusy by remember { mutableStateOf(false) }
    // 「修改密码」刚验证过的原密码：改密码时要靠它解出原数据密钥再重包，
    // 否则新密码会绑到一把新密钥上，旧文件全部解不开。
    var verifiedOldPin by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var decrypting by remember { mutableStateOf<VaultEntry?>(null) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) { Graph.vault.load() }

    // 离开保险箱页的自动上锁已上移到常驻的 AppRoot（监听返回栈里 VAULT 是否消失）。
    // 这里曾监听本页 NavBackStackEntry 的 ON_DESTROY，但组合 dispose 时先执行的
    // removeObserver 会把 observer 摘掉，返回时收不到销毁事件 —— 实测「退出未锁」。
    // 切后台由 MainActivity.onStop 锁；工具栏的锁图标仍可手动立即上锁。

    val sortKey = remember(sortName) {
        runCatching { SortKey.valueOf(sortName) }.getOrDefault(SortKey.DATE)
    }

    // ------------------------------------------------------------ 数据整理
    // 文件夹按加密前所在的目录分组：隐藏整目录后，保险箱里仍然是一个文件夹，
    // 而不是一堆散文件 —— 这也是「怎么删的就怎么恢复」的前提。
    val folders = remember(entries) { vaultFolders(entries) }
    val filtered = remember(entries, query, sortKey, ascending) {
        val base = entries.filter { e ->
            query.isBlank() ||
                e.title.contains(query, ignoreCase = true) ||
                e.fileName.contains(query, ignoreCase = true) ||
                e.originalFolderName.contains(query, ignoreCase = true)
        }
        val comparator: Comparator<VaultEntry> = when (sortKey) {
            SortKey.NAME -> compareBy { it.title.lowercase() }
            SortKey.DATE -> compareBy { it.addedAt }
            SortKey.DURATION -> compareBy { it.durationMs }
            SortKey.SIZE -> compareBy { it.sizeBytes }
        }
        base.sortedWith(if (ascending) comparator else comparator.reversed())
    }
    val visibleFolders = remember(folders, query, currentFolder) {
        if (currentFolder != null) emptyList()
        else folders.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }
    val visibleVideos = remember(filtered, currentFolder) {
        if (currentFolder == null) filtered.filter { it.vaultFolderKey().isEmpty() }
        else filtered.filter { it.vaultFolderKey() == currentFolder }
    }
    val currentFolderName = folders.firstOrNull { it.key == currentFolder }?.name.orEmpty()

    // v:/d: 前缀区分视频和文件夹，避免一个视频的 id 恰好等于某个文件夹的 key
    val selectedEntries = remember(entries, selection.ids) {
        entries.filter { selection.contains("v:${it.id}") }
    }
    val selectedFolders = remember(folders, selection.ids) {
        folders.filter { selection.contains("d:${it.key}") }
    }
    val selectedCount = selectedEntries.size + selectedFolders.size

    // 注意：这里不能再根据「选中数归零」自动退出选择态。
    // 取消全选后 selectedCount == 0 是合法状态，顶栏应保持「已选择 0 项」+ X。

    // 系统返回键：多选态先退出多选；否则在文件夹里逐层退回，只有站在保险箱首页才退出。
    // 之前没有拦文件夹返回，用户只是想从文件夹退出来，却被迫重新输密码。
    BackHandler(enabled = selection.active || currentFolder != null) {
        if (selection.active) {
            selection.exit()
        } else {
            currentFolder = currentFolder?.let { parentFolderKey(it, folders) }
        }
    }

    fun play(entry: VaultEntry) {
        scope.launch {
            decrypting = entry
            progress = 0f
            val file = Graph.vault.preparePlayback(entry) { p -> progress = p }
            decrypting = null
            if (file != null) {
                val item = Graph.vault.entryToVideoItem(entry, file)
                Playback.play(listOf(item), 0)
                navController.navigate(Routes.PLAYER)
            } else {
                // 分两种：文件没了 / 密钥对不上。以前统一写"文件可能已损坏"，
                // 用户既不知道能不能救，也不知道该找谁 —— 现在按真实原因说话。
                message = Graph.vault.lastPlaybackError ?: "解密失败，文件可能已损坏"
            }
        }
    }

    fun restoreEntries(targets: List<VaultEntry>) {
        scope.launch {
            var ok = 0
            val failed = mutableListOf<String>()
            targets.forEach { entry ->
                Graph.vault.restore(entry).fold(
                    onSuccess = { ok++ },
                    onFailure = { failed += it.message ?: entry.fileName },
                )
            }
            Graph.local.scan(silent = true)
            message = when {
                failed.isEmpty() && targets.size == 1 -> "已移出：${targets.first().fileName}"
                failed.isEmpty() -> "已移出 $ok 个视频"
                else -> "已移出 $ok 个，${failed.size} 个失败：${failed.first()}"
            }
            selection.exit()
        }
    }

    fun restoreFolders(targets: List<VaultFolder>) {
        scope.launch {
            var ok = 0
            val failed = mutableListOf<String>()
            targets.forEach { folder ->
                folder.entries.forEach { entry ->
                    Graph.vault.restore(entry).fold(
                        onSuccess = { ok++ },
                        onFailure = { failed += it.message ?: entry.fileName },
                    )
                }
            }
            Graph.local.scan(silent = true)
            message = if (failed.isEmpty()) {
                if (targets.size == 1) {
                    "已把「${targets.first().name}」的 $ok 个视频移出保险箱"
                } else {
                    "已把 ${targets.size} 个文件夹的 $ok 个视频移出保险箱"
                }
            } else {
                "已移出 $ok 个，${failed.size} 个失败：${failed.first()}"
            }
            selection.exit()
        }
    }

    fun deleteFolders(targets: List<VaultFolder>) {
        scope.launch {
            val entries = targets.flatMap { it.entries }
            Graph.vault.deletePermanently(entries)
            message = if (targets.size == 1) {
                "已彻底删除「${targets.first().name}」（${entries.size} 个视频）"
            } else {
                "已彻底删除 ${targets.size} 个文件夹（${entries.size} 个视频）"
            }
            selection.exit()
        }
    }

    // 视频与文件夹混选时，两套确认框各管各的类型（视频走单条 API，文件夹按整夹处理）。
    fun requestRestore() {
        if (selectedEntries.isNotEmpty()) restoreTarget = selectedEntries.toList()
        if (selectedFolders.isNotEmpty()) folderRestoreTarget = selectedFolders.toList()
    }

    fun requestDelete() {
        if (selectedEntries.isNotEmpty()) deleteTarget = selectedEntries.toList()
        if (selectedFolders.isNotEmpty()) folderDeleteTarget = selectedFolders.toList()
    }

    Scaffold(
        // 透明容器：背景由根部的常驻玻璃层统一绘制，内部页面与底栏页共用同一张底
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // 页面级不透明背景：转场时遮住下面那一页，避免残影
        modifier = Modifier.fillMaxSize().glassPageBackground(),
        topBar = {
            PageTopBar(
                title = if (selection.active) {
                    "已选择 $selectedCount 项"
                } else if (currentFolder == null) "保险箱" else currentFolderName,
                subtitle = when {
                    selection.active -> "轻点勾选或取消 · 右上角可全选"
                    !config.enabled -> "尚未设置密码"
                    !unlocked -> "已锁定"
                    entries.isEmpty() -> "已解锁 · 暂无内容"
                    currentFolder != null -> "${visibleVideos.size} 个视频"
                    else -> "共 ${entries.size} 个视频 · ${folders.size} 个文件夹 · " +
                        Formats.formatSize(Graph.vault.totalSize())
                },
                onBack = {
                    if (selection.active) {
                        selection.exit()
                    } else if (currentFolder != null) {
                        // 与系统返回键同一套规则：先逐层退文件夹，到首页才退出保险箱
                        currentFolder = currentFolder?.let { parentFolderKey(it, folders) }
                    } else {
                        navController.popBackStack()
                    }
                },
                backIcon = if (selection.active) Icons.Rounded.Close else null,
                backLabel = if (selection.active) "退出选择" else "返回",
                actions = {
                    if (selection.active) {
                        val allKeys = visibleFolders.map { "d:${it.key}" } + visibleVideos.map { "v:${it.id}" }
                        val allSelected = allKeys.isNotEmpty() && selectedCount == allKeys.size
                        IconButton(onClick = {
                            if (allSelected) {
                                // 取消全选只清勾选项，保留选择态
                                selection.clear()
                            } else {
                                selection.replaceAll(allKeys)
                            }
                        }) {
                            Icon(
                                Icons.Rounded.SelectAll,
                                contentDescription = if (allSelected) "取消全选" else "全选",
                            )
                        }
                    } else if (config.enabled && unlocked) {
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = {
                            sortDraft = MediaSortDraft(gridMode, sortName, ascending)
                            sortSheetOpen = true
                        }) {
                            Icon(Icons.Rounded.Tune, contentDescription = "排列与排序")
                        }
                        // 不再提供「立即锁定」按钮：退出保险箱页（或切后台）已由 AppNav / MainActivity 自动锁定
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("修改密码") },
                                    onClick = {
                                        menuOpen = false
                                        oldPinText = ""
                                        oldPinError = null
                                        oldPinCheck = true
                                    },
                                )
                                DropdownMenuItem(
                                    // 文案必须写「点下去会发生什么」，并且一行能读完
                                    text = { Text(if (config.biometric) "关闭指纹" else "开启指纹") },
                                    onClick = {
                                        menuOpen = false
                                        if (config.biometric) {
                                            // 关闭指纹直接生效，无需验证
                                            scope.launch {
                                                val ok = Graph.vault.setBiometric(false)
                                                message = if (ok) "已关闭指纹" else "关闭失败，请重试"
                                            }
                                        } else {
                                            val act = activity
                                            if (act == null || !VaultBiometric.available(context)) {
                                                message = "当前设备未录入指纹，无法开启指纹"
                                            } else {
                                                // 用生物密钥的加密 Cipher 走一次认证：
                                                // 认证成功后系统才放行该密钥，才能封装数据密钥。
                                                VaultBiometric.enroll(
                                                    act,
                                                    onAuthed = { cipher ->
                                                        scope.launch {
                                                            val ok = Graph.vault.setBiometricWithCipher(cipher)
                                                            message = if (ok) "已开启指纹" else "开启失败，请重试"
                                                        }
                                                    },
                                                    // 取消返回空串：不要把它当成一条提示显示出来
                                                    onError = { if (it.isNotBlank()) message = it },
                                                )
                                            }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("关闭保险箱") },
                                    onClick = { menuOpen = false; disableConfirm = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selection.active) {
                val single = selectedCount == 1
                val canAct = selectedCount > 0
                val soloVideo = selectedEntries.singleOrNull()
                val soloFolder = selectedFolders.singleOrNull()
                SelectionActionBar(
                    actions = listOf(
                        SelectionAction(
                            if (soloFolder != null) Icons.Rounded.Folder else Icons.Rounded.PlayArrow,
                            if (soloFolder != null) "打开" else "播放",
                            enabled = canAct && single,
                        ) {
                            when {
                                soloVideo != null -> {
                                    play(soloVideo)
                                    selection.exit()
                                }
                                soloFolder != null -> {
                                    selection.exit()
                                    currentFolder = soloFolder.key
                                }
                            }
                        },
                        SelectionAction(Icons.Rounded.Info, "详情", enabled = canAct && single) {
                            if (soloVideo != null) infoEntry = soloVideo
                            if (soloFolder != null) infoFolder = soloFolder
                        },
                        // 「移出」在第三格；彻底删除不可撤销，收进「更多」
                        SelectionAction(Icons.Rounded.Restore, "移出", enabled = canAct) { requestRestore() },
                        SelectionAction(Icons.Rounded.DeleteForever, "彻底删除", enabled = canAct, danger = true) {
                            requestDelete()
                        },
                    ),
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    forceMore = true,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = PageMetrics.HPad, vertical = 6.dp),
                )
            }

            when {
                !config.enabled -> EmptyState(
                    icon = Icons.Rounded.Shield,
                    title = "保险箱未启用",
                    message = "设置密码后，可以把视频隐藏到这里：文件会被加密存放在应用私有目录，" +
                        "媒体库和文件管理器都看不到。",
                    actionLabel = "设置密码",
                    onAction = {
                        setupWasEnabled = config.enabled
                        verifiedOldPin = null
                        setupOpen = true
                    },
                )

                !unlocked -> VaultUnlockPanel(
                    config = config,
                    onUnlocked = { message = null },
                )

                entries.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.LockOpen,
                    title = "保险箱是空的",
                    message = "在「本地」长按视频或文件夹，选择「隐藏到保险箱」即可加入。",
                )

                visibleFolders.isEmpty() && visibleVideos.isEmpty() -> {
                    val searching = query.isNotBlank()
                    EmptyState(
                        icon = if (searching) Icons.Rounded.Search else Icons.Rounded.LockOpen,
                        title = if (searching) "没有匹配的内容" else "这个文件夹是空的",
                        message = if (searching) "换个关键词试试。" else "这里的视频可能已被移出或删除。",
                    )
                }

                else -> Column(Modifier.fillMaxSize()) {
                    if (searchOpen) {
                        SearchField(
                            query = query,
                            onQueryChange = { query = it },
                            onClose = { searchOpen = false; query = "" },
                            placeholder = "搜索保险箱内的视频",
                        )
                    }
                    if (gridMode) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = adaptiveMediaGridMinSize()),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = PageMetrics.HPad,
                                end = PageMetrics.HPad,
                                top = 8.dp,
                                bottom = 28.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (visibleFolders.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) { MediaSectionLabel("文件夹") }
                            }
                            items(visibleFolders, key = { "d${it.key}" }) { folder ->
                                val folderKey = "d:${folder.key}"
                                MediaFolderGridCard(
                                    name = folder.name,
                                    subtitle = "${folder.entries.size} 个视频",
                                    onClick = {
                                        if (selection.active) selection.toggle(folderKey)
                                        else currentFolder = folder.key
                                    },
                                    onLongClick = {
                                        // 长按只进入选择态，不弹更多面板
                                        selection.enter(folderKey)
                                    },
                                    selected = selection.contains(folderKey),
                                    selecting = selection.active,
                                )
                            }
                            if (visibleVideos.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) { MediaSectionLabel("视频") }
                            }
                            items(visibleVideos, key = { it.id }) { entry ->
                                val videoKey = "v:${entry.id}"
                                VaultGridCard(
                                    entry = entry,
                                    onClick = {
                                        if (selection.active) selection.toggle(videoKey)
                                        else play(entry)
                                    },
                                    onLongClick = {
                                        // 长按只进入选择态，不弹更多面板
                                        selection.enter(videoKey)
                                    },
                                    selected = selection.contains(videoKey),
                                    selecting = selection.active,
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = listRowMinSize()),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = PageMetrics.HPad,
                                end = PageMetrics.HPad,
                                top = 4.dp,
                                bottom = 28.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (visibleFolders.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) { MediaSectionLabel("文件夹") }
                            }
                            items(visibleFolders, key = { "d${it.key}" }) { folder ->
                                val folderKey = "d:${folder.key}"
                                MediaFolderRow(
                                    name = folder.name,
                                    subtitle = "${folder.entries.size} 个视频",
                                    onClick = {
                                        if (selection.active) selection.toggle(folderKey)
                                        else currentFolder = folder.key
                                    },
                                    onLongClick = {
                                        // 长按只进入选择态，不弹更多面板
                                        selection.enter(folderKey)
                                    },
                                    selected = selection.contains(folderKey),
                                    selecting = selection.active,
                                )
                            }
                            if (visibleVideos.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) { MediaSectionLabel("视频") }
                            }
                            items(visibleVideos, key = { it.id }) { entry ->
                                val videoKey = "v:${entry.id}"
                                VaultListRow(
                                    entry = entry,
                                    onClick = {
                                        if (selection.active) selection.toggle(videoKey)
                                        else play(entry)
                                    },
                                    onLongClick = {
                                        // 长按只进入选择态，不弹更多面板
                                        selection.enter(videoKey)
                                    },
                                    selected = selection.contains(videoKey),
                                    selecting = selection.active,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    infoEntry?.let { entry ->
        VaultInfoDialog(entry = entry, onDismiss = { infoEntry = null })
    }

    infoFolder?.let { folder ->
        FolderInfoDialog(
            name = folder.name,
            location = folder.entries.firstOrNull()?.originalPath?.substringBeforeLast('/')
                ?: folder.key,
            videoCount = folder.entries.size,
            totalSize = folder.sizeBytes,
            source = "保险箱",
            onDismiss = { infoFolder = null },
        )
    }

    // ------------------------------------------------------------ 对话框
    if (oldPinCheck) {
        AlertDialog(
            shape = RoundedCornerShape(26.dp),
            containerColor = appGlass().panelStrong,
            tonalElevation = 0.dp,
            onDismissRequest = {
                oldPinCheck = false
                oldPinText = ""
                oldPinError = null
            },
            title = { Text("验证当前密码") },
            text = {
                Column {
                    Text(
                        "修改密码前，请先输入当前密码确认身份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = oldPinText,
                        onValueChange = { oldPinText = Graph.vault.normalizePin(it); oldPinError = null },
                        label = { Text("当前密码") },
                        singleLine = true,
                        isError = oldPinError != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    oldPinError?.let { err ->
                        Text(
                            err,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = oldPinText.isNotBlank() && !oldPinBusy,
                    onClick = {
                        oldPinBusy = true
                        scope.launch {
                            val ok = runCatching { Graph.vault.verifyPin(oldPinText) }.getOrDefault(false)
                            oldPinBusy = false
                            if (ok) {
                                verifiedOldPin = oldPinText
                                oldPinCheck = false
                                oldPinText = ""
                                oldPinError = null
                                setupWasEnabled = config.enabled
                                setupOpen = true
                            } else {
                                oldPinError = "密码不正确"
                                Graph.vault.vibrateWrongPin(context)
                            }
                        }
                    },
                ) { Text(if (oldPinBusy) "验证中…" else "确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    oldPinCheck = false
                    oldPinText = ""
                    oldPinError = null
                }) { Text("取消") }
            },
        )
    }

    if (setupOpen) {
        VaultSetupDialog(
            onDismiss = { setupOpen = false; verifiedOldPin = null },
            onDone = {
                setupOpen = false
                verifiedOldPin = null
                message = if (setupWasEnabled) "密码已更新" else "保险箱已启用"
            },
            currentPin = verifiedOldPin,
        )
    }

    restoreTarget?.let { targets ->
        AlertDialog(
            shape = RoundedCornerShape(26.dp),
            containerColor = appGlass().panelStrong,
            tonalElevation = 0.dp,
            onDismissRequest = { restoreTarget = null },
            title = { Text("移出保险箱") },
            text = {
                Text(
                    if (targets.size == 1) {
                        "「${targets.first().fileName}」会被解密并放回原目录，之后会重新出现在本地视频列表中。"
                    } else {
                        "选中的 ${targets.size} 个视频会被解密并放回各自原来的目录。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreTarget = null
                    restoreEntries(targets)
                }) { Text("移出") }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("取消") } },
        )
    }

    folderRestoreTarget?.let { targets ->
        AlertDialog(
            shape = RoundedCornerShape(26.dp),
            containerColor = appGlass().panelStrong,
            tonalElevation = 0.dp,
            onDismissRequest = { folderRestoreTarget = null },
            title = { Text(if (targets.size == 1) "移出整个文件夹" else "移出选中的文件夹") },
            text = {
                Text(
                    if (targets.size == 1) {
                        "「${targets.first().name}」里的 ${targets.first().entries.size} 个视频会解密回原目录，" +
                            "文件夹结构保持不变。"
                    } else {
                        "选中的 ${targets.size} 个文件夹会解密回原目录，文件夹结构保持不变。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    folderRestoreTarget = null
                    restoreFolders(targets)
                }) { Text("移出") }
            },
            dismissButton = { TextButton(onClick = { folderRestoreTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { targets ->
        AlertDialog(
            shape = RoundedCornerShape(26.dp),
            containerColor = appGlass().panelStrong,
            tonalElevation = 0.dp,
            onDismissRequest = { deleteTarget = null },
            title = { Text("彻底删除") },
            text = {
                Text(
                    if (targets.size == 1) {
                        "「${targets.first().fileName}」将被永久删除，无法恢复。"
                    } else {
                        "选中的 ${targets.size} 个视频将被永久删除，无法恢复。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        Graph.vault.deletePermanently(targets)
                        message = if (targets.size == 1) "已删除" else "已删除 ${targets.size} 个视频"
                        selection.exit()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    folderDeleteTarget?.let { targets ->
        AlertDialog(
            shape = RoundedCornerShape(26.dp),
            containerColor = appGlass().panelStrong,
            tonalElevation = 0.dp,
            onDismissRequest = { folderDeleteTarget = null },
            title = { Text(if (targets.size == 1) "彻底删除文件夹" else "彻底删除选中的文件夹") },
            text = {
                Text(
                    if (targets.size == 1) {
                        "「${targets.first().name}」里的 ${targets.first().entries.size} 个视频将被永久删除，无法恢复。"
                    } else {
                        "选中的 ${targets.size} 个文件夹里的视频将被永久删除，无法恢复。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    folderDeleteTarget = null
                    deleteFolders(targets)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { folderDeleteTarget = null }) { Text("取消") } },
        )
    }

    if (disableConfirm) {
        fun closeVault() {
            if (disableBusy) return
            disableBusy = true
            scope.launch {
                val result = Graph.vault.disable()
                Graph.local.scan(silent = true)
                disableBusy = false
                disableConfirm = false
                disablePin = ""
                disableError = null
                // 失败时 message 里会写清原因（并且门禁仍然保留），成功时是"已移出 N 个"
                message = result.message
            }
        }
        fun dismissDialog() {
            if (disableBusy) return
            disableConfirm = false
            disablePin = ""
            disableError = null
        }

        // 开启了指纹就先用指纹：用户提的是「关闭保险箱时默认不是使用指纹」。
        // 与解锁页同一套规则：只自动弹一次，取消后仍可手动点「用指纹确认」。
        val canBio = config.biometric && activity != null && VaultBiometric.available(context)
        LaunchedEffect(disableConfirm, canBio) {
            if (!canBio || activity == null) return@LaunchedEffect
            VaultBiometric.authenticate(
                activity,
                onSuccess = { closeVault() },
                onError = { if (it.isNotBlank()) disableError = it },
            )
        }

        AlertDialog(
            shape = RoundedCornerShape(26.dp),
            containerColor = appGlass().panelStrong,
            tonalElevation = 0.dp,
            onDismissRequest = { dismissDialog() },
            title = { Text("关闭保险箱") },
            text = {
                Column {
                    Text(
                        "保险箱里的 ${entries.size} 个视频会被解密并放回原目录，" +
                            "密码与指纹设置一并清除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (config.biometric) "验证指纹确认身份，也可以输入密码：" else "输入密码确认身份：",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = disablePin,
                        // 与设置/解锁同一套归一：全角数字转半角
                        onValueChange = { disablePin = Graph.vault.normalizePin(it); disableError = null },
                        label = { Text("密码") },
                        singleLine = true,
                        isError = disableError != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    disableError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (config.biometric) {
                        val act = activity
                        TextButton(
                            enabled = act != null && !disableBusy,
                            onClick = {
                                if (act == null) return@TextButton
                                if (!VaultBiometric.available(context)) {
                                    disableError = "当前设备未录入指纹，请改用密码"
                                    return@TextButton
                                }
                                // 纯身份验证：不碰数据密钥，认证通过即视为机主确认
                                VaultBiometric.authenticate(
                                    act,
                                    onSuccess = { closeVault() },
                                    onError = { if (it.isNotBlank()) disableError = it },
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("用指纹确认")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    // 下限与设置页一致（4 位）。之前写死 6，设了 4 位密码的用户
                    // 永远点不亮「关闭」，看起来就是「保险箱关不掉」。
                    enabled = disablePin.length in VaultRepo.MIN_PIN..VaultRepo.MAX_PIN && !disableBusy,
                    onClick = {
                        disableBusy = true
                        scope.launch {
                            val ok = runCatching { Graph.vault.verifyPin(disablePin) }
                                .getOrDefault(false)
                            disableBusy = false
                            if (ok) {
                                closeVault()
                            } else {
                                disablePin = ""
                                disableError = "密码不正确"
                                Graph.vault.vibrateWrongPin(context)
                            }
                        }
                    },
                ) { Text(if (disableBusy) "处理中…" else "关闭") }
            },
            dismissButton = {
                TextButton(onClick = { dismissDialog() }, enabled = !disableBusy) { Text("取消") }
            },
        )
    }

    decrypting?.let { entry ->
        AlertDialog(
            shape = RoundedCornerShape(26.dp),
            containerColor = appGlass().panelStrong,
            tonalElevation = 0.dp,
            onDismissRequest = {},
            title = { Text("正在解密") },
            text = {
                Column {
                    Text(entry.fileName, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )
    }

    if (sortSheetOpen) {
        SortLayoutSheet(
            onDismiss = {
                // onPreview 已实时应用选择；不能再用打开面板时的旧快照覆盖
                sortDraft = null
                sortSheetOpen = false
            },
            initialGrid = gridMode,
            initialSort = sortKey,
            initialAscending = ascending,
            entries = listOf(SortKey.NAME, SortKey.DATE, SortKey.DURATION, SortKey.SIZE),
            onPreview = { grid, key, asc ->
                gridMode = grid
                sortName = key.name
                ascending = asc
            },
            onApply = { grid, key, asc ->
                gridMode = grid
                sortName = key.name
                ascending = asc
                sortDraft = null
            },
        )
    }
}

// ---------------------------------------------------------------- 数据模型

private data class VaultFolder(
    val key: String,
    val name: String,
    val entries: List<VaultEntry>,
) {
    val sizeBytes: Long get() = entries.sumOf { it.sizeBytes }
}

/** 保险箱内的分组键：只有「整个文件夹」加入时才有值，单独加入的视频一律在根目录 */
private fun VaultEntry.vaultFolderKey(): String = groupKey.trimEnd('/')

private fun vaultFolders(entries: List<VaultEntry>): List<VaultFolder> =
    entries
        .filter { it.vaultFolderKey().isNotEmpty() }
        .groupBy { it.vaultFolderKey() }
        .map { (key, list) ->
            // 优先用加入时记下的文件夹名；缺失时退回路径末段
            val name = list.firstOrNull { it.originalFolderName.isNotBlank() }?.originalFolderName
                ?: key.substringAfterLast('/')
            VaultFolder(key, name, list.sortedByDescending { it.addedAt })
        }
        .sortedBy { it.name.lowercase() }

/**
 * 当前文件夹的上一层。
 *
 * 返回键必须「逐层」退：在文件夹里按返回只能回到上一层文件夹，只有已经在保险箱
 * 首页时才能退出保险箱 —— 因为退出保险箱 = 重新认证，退错了代价很高。
 */
private fun parentFolderKey(current: String, folders: List<VaultFolder>): String? =
    folders.asSequence()
        .map { it.key }
        .filter { it != current && current.startsWith("$it/") }
        .maxByOrNull { it.length }

// ---------------------------------------------------------------- 列表卡片

/**
 * 保险箱视频卡（网格）。
 *
 * 尺寸、间距、标题行高都与 [com.yaoahos.mypureplayer.ui.components.VideoGridCard] 一致，
 * 只是缩略图位置换成"加密锁牌"：这里没有可截的帧，也不能为了预览去解密。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VaultGridCard(
    entry: VaultEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean = false,
    selecting: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 100.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectionCardOutline(pressed = pressed, selected = selected, selecting = selecting)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onClickLabel = if (selecting) "选中/取消选择" else "播放",
                    onLongClick = onLongClick,
                    onLongClickLabel = "更多操作",
                    role = Role.Button,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                VaultArtwork(iconSize = 34.dp)
                SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = entry.fileName,
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
                    text = vaultMeta(entry, compact = true),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 保险箱视频行（列表） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VaultListRow(
    entry: VaultEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean = false,
    selecting: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectionCardOutline(pressed = pressed, selected = selected, selecting = selecting)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onClickLabel = if (selecting) "选中/取消选择" else "播放",
                onLongClick = onLongClick,
                onLongClickLabel = "更多操作",
                role = Role.Button,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 132.dp, height = 76.dp)) {
            VaultArtwork(iconSize = 26.dp, radius = 14.dp)
            SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = vaultMeta(entry),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 加密内容的占位牌：一层同族薄纱 + 锁图标，随卡片尺寸缩放 */
@Composable
private fun VaultArtwork(iconSize: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp = 0.dp) {
    val glass = appGlass()
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(radius))
            .background(
                Brush.verticalGradient(
                    listOf(glass.tintSoft.copy(alpha = 0.20f), glass.tintSoft.copy(alpha = 0.06f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = "已加密",
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.70f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 元信息文案。
 *
 * 宫格宽度只有 150dp 上下，塞「大小 · 时长 · 日期」三段会被省略号切成
 * "30.2 KB · …"，等于什么都没说 —— 与本地页宫格保持一致：只留时长
 * （没有时长才退回大小），列表行空间足够，才把三段都写全。
 */
private fun vaultMeta(entry: VaultEntry, compact: Boolean = false): String {
    val duration = if (entry.durationMs > 0L) Formats.formatDuration(entry.durationMs) else ""
    if (compact) {
        return when {
            duration.isNotBlank() -> duration
            entry.sizeBytes > 0L -> Formats.formatSize(entry.sizeBytes)
            else -> ""
        }
    }
    return buildString {
        if (duration.isNotBlank()) append(duration)
        if (entry.sizeBytes > 0L) {
            if (isNotEmpty()) append(" · ")
            append(Formats.formatSize(entry.sizeBytes))
        }
        if (isNotEmpty()) append(" · ")
        append(SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(entry.addedAt)))
    }
}



/**
 * 保险箱条目详情。
 *
 * 这里不给缩略图、也不抽帧：内容是为了看不到才加密的，为了"详情"去解密一帧
 * 等于把保险箱的意义拆掉。能说清"这是什么、原来在哪、多大、多久"就够了。
 */
@Composable
private fun VaultInfoDialog(entry: VaultEntry, onDismiss: () -> Unit) {
    val added = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.addedAt))
    AlertDialog(
        shape = RoundedCornerShape(26.dp),
        containerColor = appGlass().panelStrong,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text("视频详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("名称", entry.fileName)
                InfoRow(
                    "原位置",
                    entry.originalPath.substringBeforeLast('/').ifBlank { entry.originalPath },
                    copyable = true,
                )
                InfoRow(
                    "时长",
                    if (entry.durationMs > 0L) Formats.formatDuration(entry.durationMs) else "未知",
                )
                InfoRow(
                    "分辨率",
                    if (entry.width > 0 && entry.height > 0) "${entry.width} × ${entry.height}"
                    else "未知",
                )
                InfoRow("大小", Formats.formatSize(entry.sizeBytes))
                InfoRow("加入时间", added)
                InfoRow("状态", "已加密")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
