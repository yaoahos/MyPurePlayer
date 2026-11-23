package com.yaoahos.mypureplayer.ui.more

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.data.BackupCrypto
import com.yaoahos.mypureplayer.data.BackupPrefs
import com.yaoahos.mypureplayer.data.ConfigBackup
import com.yaoahos.mypureplayer.data.WebDavClient
import com.yaoahos.mypureplayer.data.WebDavConfig
import com.yaoahos.mypureplayer.ui.components.ConfirmDialog
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.SectionLabel
import com.yaoahos.mypureplayer.ui.components.appGlassSurface
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 配置备份：本机文件与 WebDAV 两条通路，共用 [ConfigBackup] 的一份快照。
 *
 * 落盘前一律经 [BackupCrypto] 加密：
 *  - 本机文件名由应用生成（带时间戳，可留多个版本）；
 *  - WebDAV 用「地址 + 端口 + 目录」，文件名固定，上传即覆盖上一次，恢复时不用挑文件。
 *
 * 恢复是覆盖性操作，两条通路都先解密、解析，再弹一次确认框；失败时不会先动当前配置。
 * 密码不进备份文件，结果提示里列出需要补填的项。
 */
@Composable
fun BackupScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var davUrl by remember { mutableStateOf("") }
    var davPort by remember { mutableStateOf(ConfigBackup.defaultPort("https").toString()) }
    var davFolder by remember { mutableStateOf(ConfigBackup.DEFAULT_FOLDER) }
    var davUser by remember { mutableStateOf("") }
    var davPassword by remember { mutableStateOf("") }
    // 端口被手动改过就不再跟随协议自动切换
    var portEdited by remember { mutableStateOf(false) }
    // 备份口令只在内存里：不落盘，也就没有"存哪儿了"的问题
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // 解密并解析成功、等待用户确认的备份
    var pending by remember { mutableStateOf<ConfigBackup.Payload?>(null) }

    fun defaultPortOf(url: String): String =
        ConfigBackup.defaultPort(if (url.trim().startsWith("http://", true)) "http" else "https").toString()

    LaunchedEffect(Unit) {
        val saved = BackupPrefs.get(context)
        davUrl = saved.url
        davFolder = saved.folder.ifBlank { ConfigBackup.DEFAULT_FOLDER }
        davUser = saved.username
        davPassword = saved.password
        davPort = saved.port.ifBlank { defaultPortOf(saved.url) }
        portEdited = saved.port.isNotBlank()
    }

    /** 地址 + 端口合成规范基地址；地址为空时返回空串 */
    fun base(): String = ConfigBackup.baseUrl(davUrl, davPort)

    suspend fun saveDav() {
        BackupPrefs.save(context, WebDavConfig(davUrl, davPort, davFolder, davUser, davPassword))
    }

    fun hasPassword(): Boolean {
        if (password.length >= BackupCrypto.MIN_PASSWORD) return true
        Notice.warn("请先设置备份密码（至少 ${BackupCrypto.MIN_PASSWORD} 位）")
        return false
    }

    fun hasBase(): Boolean {
        if (base().isNotBlank()) return true
        Notice.warn("请先填写地址")
        return false
    }

    fun reportResult(r: ConfigBackup.RestoreResult) {
        val text = "已恢复 ${r.sectionCount} 类配置" +
            if (r.smbNeedPassword > 0) "；${r.smbNeedPassword} 个 SMB 共享缺密码，请重新填写" else ""
        if (r.smbNeedPassword > 0) Notice.warn(text) else Notice.success(text)
    }

    suspend fun backupText(): String = withContext(Dispatchers.IO) {
        BackupCrypto.encrypt(ConfigBackup.export(), password)
    }

    suspend fun downloadText(): String {
        saveDav()
        val bytes = withContext(Dispatchers.IO) {
            WebDavClient.get(ConfigBackup.remoteUrl(base(), davFolder), davUser, davPassword)
        }
        return bytes.toString(Charsets.UTF_8)
    }

    suspend fun openPayload(text: String) {
        if (BackupCrypto.isEncrypted(text) && password.length < BackupCrypto.MIN_PASSWORD) {
            Notice.warn("该备份已加密，请先输入备份密码")
            return
        }
        // 口令派生要走十几万次迭代，不能留在主线程
        pending = withContext(Dispatchers.IO) { ConfigBackup.parse(text, password) }
    }

    val saveLocal = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = runCatching {
                val text = backupText()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入所选位置")
                }
            }
            busy = false
            result.onSuccess { Notice.success("已备份") }
                .onFailure { Notice.error(it.message ?: "备份失败") }
        }
    }

    val pickLocal = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("无法读取所选文件")
                }
                openPayload(text)
            }
            busy = false
            result.onFailure { Notice.error(it.message ?: "读取备份失败") }
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxSize().glassPageBackground(),
        topBar = {
            PageTopBar(
                title = "配置备份",
                subtitle = "本地文件 / WebDAV · 加密",
                onBack = { navController.popBackStack() },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // adjustResize 只缩窗口；不跟着 ime 内缩的话，WebDAV 那几个输入框
                // 正好落在输入法底下，点得到却看不见。
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackupGroup("备份范围") {
                NoteBlock(
                    icon = {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(17.dp),
                        )
                    },
                    text = "包含：主题与外观、播放选项、网络与 SMB 页排序、SMB 共享、串流地址、" +
                        "观看历史、正在播放队列、自建播放列表\n" +
                        "不含：密码、回收站、保险箱\n" +
                        "恢复会覆盖 SMB、串流、历史、正在播放与自建列表；换设备后本地视频路径可能失效",
                )
            }

            BackupGroup("备份密码") {
                NoteBlock(
                    icon = {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(17.dp),
                        )
                    },
                    text = "备份文件整体加密，恢复需同一密码。\n密码不保存，遗失后无法还原。",
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    placeholder = { Text("至少 ${BackupCrypto.MIN_PASSWORD} 位") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp),
                )
                Spacer(Modifier.height(14.dp))
            }

            BackupGroup("本机文件") {
                BackupActionRow(
                    title = "备份到本机",
                    subtitle = "位置自选，文件名自动生成",
                    action = "备份",
                    enabled = !busy,
                    onClick = {
                        if (hasPassword()) saveLocal.launch(ConfigBackup.suggestedFileName())
                    },
                )
                BackupActionRow(
                    title = "从本机恢复",
                    subtitle = "选择备份文件",
                    action = "恢复",
                    enabled = !busy,
                    onClick = { pickLocal.launch(arrayOf("*/*")) },
                )
            }

            BackupGroup("WebDAV") {
                OutlinedTextField(
                    value = davUrl,
                    onValueChange = {
                        davUrl = it
                        if (!portEdited) davPort = defaultPortOf(it)
                    },
                    label = { Text("地址") },
                    placeholder = { Text("dav.example.com/dav") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = davPort,
                        onValueChange = {
                            davPort = it.filter(Char::isDigit).take(5)
                            portEdited = true
                        },
                        label = { Text("端口") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(108.dp),
                    )
                    OutlinedTextField(
                        value = davFolder,
                        onValueChange = { davFolder = it },
                        label = { Text("目录") },
                        placeholder = { Text(ConfigBackup.DEFAULT_FOLDER) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = davUser,
                    onValueChange = { davUser = it },
                    label = { Text("账号") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                OutlinedTextField(
                    value = davPassword,
                    onValueChange = { davPassword = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                if (base().isNotBlank()) {
                    Text(
                        "文件：" + ConfigBackup.remoteUrl(base(), davFolder),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    )
                }
                if (davUrl.trim().startsWith("http://", true)) {
                    Text(
                        "http 为明文传输，建议改用 https",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                    )
                }
                Text(
                    "账号与密码只存本机，不写入备份",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            if (!hasBase()) return@TextButton
                            scope.launch {
                                busy = true
                                val r = runCatching {
                                    saveDav()
                                    withContext(Dispatchers.IO) {
                                        WebDavClient.check(base(), davUser, davPassword)
                                    }
                                }
                                busy = false
                                r.onSuccess { Notice.success("连接正常") }
                                    .onFailure { Notice.error(it.message ?: "连接失败") }
                            }
                        },
                        enabled = !busy,
                    ) { Text("测试连接") }
                    TextButton(
                        onClick = {
                            if (!hasPassword() || !hasBase()) return@TextButton
                            scope.launch {
                                busy = true
                                val r = runCatching {
                                    saveDav()
                                    val text = backupText()
                                    withContext(Dispatchers.IO) {
                                        WebDavClient.ensureFolder(base(), davFolder, davUser, davPassword)
                                        WebDavClient.put(
                                            ConfigBackup.remoteUrl(base(), davFolder),
                                            davUser,
                                            davPassword,
                                            text.toByteArray(Charsets.UTF_8),
                                        )
                                    }
                                }
                                busy = false
                                r.onSuccess { Notice.success("已上传") }
                                    .onFailure { Notice.error(it.message ?: "上传失败") }
                            }
                        },
                        enabled = !busy,
                    ) { Text("上传") }
                    TextButton(
                        onClick = {
                            if (!hasBase()) return@TextButton
                            scope.launch {
                                busy = true
                                val r = runCatching { openPayload(downloadText()) }
                                busy = false
                                r.onFailure { Notice.error(it.message ?: "下载失败") }
                            }
                        },
                        enabled = !busy,
                    ) { Text("恢复") }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    pending?.let { payload ->
        ConfirmDialog(
            title = "恢复配置",
            message = "将用备份覆盖外观、播放选项、排序、SMB 共享、串流地址、观看历史、" +
                "正在播放队列与自建播放列表。回收站与保险箱不受影响。\n\n" +
                "密码不在备份中，需要时重新填写。",
            confirmText = "恢复",
            onConfirm = {
                pending = null
                scope.launch {
                    busy = true
                    val result = runCatching { ConfigBackup.restore(payload) }
                    busy = false
                    result.onSuccess { reportResult(it) }
                        .onFailure { Notice.error(it.message ?: "恢复失败") }
                }
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun BackupGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(title)
        Column(
            Modifier.fillMaxWidth().appGlassSurface(RoundedCornerShape(PageMetrics.CardRadius)),
        ) { content() }
    }
}

@Composable
private fun NoteBlock(icon: @Composable () -> Unit, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        icon()
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun BackupActionRow(
    title: String,
    subtitle: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
        TextButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}
