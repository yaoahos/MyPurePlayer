package com.yaoahos.mypureplayer.ui.player

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.Role
import com.yaoahos.mypureplayer.core.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「从设备目录选择字幕」——播放页之上的浮层。
 *
 * 之前这里走的是系统 SAF 目录选择器（ActivityResultContracts.OpenDocumentTree）：
 * 那是另一个 Activity，系统按竖屏拉起，横屏播放页 → 竖屏文件页 → 横屏播放页，
 * 屏幕来回转两次，播放还被打断。
 *
 * 现在直接在播放页里读目录：`MANAGE_EXTERNAL_STORAGE` 已授权，java.io.File 能列全盘，
 * 不必再借 SAF 的 Uri。列表只给目录与字幕文件（选字幕的地方列视频没有意义）。
 */
@Composable
fun LocalSubtitlePickerDialog(
    startDir: File,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit,
) {
    var current by remember { mutableStateOf(startDir) }
    // 子目录 + 字幕文件，目录在前
    var dirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var subs by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableStateOf(0) }

    fun refresh(dir: File) {
        current = dir
        reloadKey++
    }

    // 面包屑：从外部存储根（/storage/emulated/0）到当前目录。根那一级显示友好名「根目录」，
    // 完整真实路径仍由面板副标题显示。当前目录在根之外（如 /storage 上层）时按真实分段铺开。
    val storageRoot = remember { Environment.getExternalStorageDirectory().absolutePath }
    val crumbs = remember(current, storageRoot) {
        val root = File(storageRoot)
        val chain = mutableListOf<File>()
        var cursor: File? = current
        while (cursor != null) {
            chain.add(0, cursor)
            if (cursor.absolutePath == root.absolutePath) break
            cursor = cursor.parentFile
        }
        if (chain.firstOrNull()?.absolutePath != root.absolutePath) {
            // 起点不在外部存储根下，整条按真实绝对路径展示
            chain.clear()
            cursor = current
            while (cursor != null) {
                chain.add(0, cursor)
                cursor = cursor.parentFile
            }
        }
        chain.map { f ->
            PickerCrumb(
                label = if (f.absolutePath == root.absolutePath) "根目录" else f.name.ifEmpty { f.absolutePath },
                path = f.absolutePath,
            )
        }
    }

    LaunchedEffect(current, reloadKey) {
        loading = true
        val dir = current
        val (d, s) = withContext(Dispatchers.IO) {
            val all = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()
            val subDirs = all
                .filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
                .sortedBy { it.name.lowercase() }
            val subFiles = all
                .filter { it.isFile && Formats.isSubtitle(it.name) && runCatching { it.canRead() }.getOrDefault(false) }
                .sortedBy { it.name.lowercase() }
            subDirs to subFiles
        }
        dirs = d
        subs = s
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        // 宽度自己控：横屏要能一次看到更多文件名，竖屏也不该被默认宽度挤成一条
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        PlayerPickerPanel(
            onDismiss = onDismiss,
            title = "选择本地字幕",
            onBack = {
                // 回上一层；已站在外部存储根（面包屑第一级）就关闭浮层
                val parent = current.parentFile
                if (parent == null || current.absolutePath == storageRoot) {
                    onDismiss()
                } else {
                    refresh(parent)
                }
            },
            actions = {
                val glass = playerGlass()
                IconButton(onClick = { refresh(File(storageRoot)) }) {
                    Icon(Icons.Rounded.Home, contentDescription = "回到根目录", tint = glass.onGlass)
                }
                IconButton(onClick = { reloadKey++ }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = glass.onGlass)
                }
            },
            breadcrumb = {
                PickerBreadcrumb(crumbs = crumbs, onNavigate = { path -> refresh(File(path)) })
            },
        ) {
            val glass = playerGlass()
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        Modifier.size(28.dp),
                        strokeWidth = 2.5.dp,
                        color = glass.accent,
                    )
                }

                dirs.isEmpty() && subs.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.Subtitles,
                        contentDescription = null,
                        tint = glass.onGlassSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这里没有字幕，可点上方面包屑或返回上一层继续找",
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.onGlassSecondary,
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 8.dp, end = 8.dp, top = 6.dp, bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(dirs, key = { "d:${it.absolutePath}" }) { dir ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { refresh(dir) }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = glass.accent,
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                dir.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = glass.onGlass,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = glass.onGlassSecondary,
                            )
                        }
                    }
                    items(subs, key = { "f:${it.absolutePath}" }) { file ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { onPick(file) }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Description,
                                contentDescription = null,
                                tint = glass.accent,
                            )
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = glass.onGlass,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    Formats.formatSize(runCatching { file.length() }.getOrDefault(0L)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = glass.onGlassSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
