package com.yaoahos.mypureplayer.ui.more

import com.yaoahos.mypureplayer.ui.components.GlassDialogButton
import com.yaoahos.mypureplayer.ui.components.GlassButtonTone
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.data.BackupCrypto
import com.yaoahos.mypureplayer.data.BackupPrefs
import com.yaoahos.mypureplayer.data.CloudBackup
import com.yaoahos.mypureplayer.data.CloudBackupScheduler
import com.yaoahos.mypureplayer.data.ConfigBackup
import com.yaoahos.mypureplayer.data.DeviceProfile
import com.yaoahos.mypureplayer.data.WebDavClient
import com.yaoahos.mypureplayer.data.WebDavConfig
import com.yaoahos.mypureplayer.ui.components.ConfirmDialog
import com.yaoahos.mypureplayer.ui.components.glassPressable
import com.yaoahos.mypureplayer.ui.components.GlassChoiceDialog
import com.yaoahos.mypureplayer.ui.components.GlassChoiceRow
import com.yaoahos.mypureplayer.ui.components.Notice
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.GlassButton
import com.yaoahos.mypureplayer.ui.components.GlassTextField
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.PasswordEye
import com.yaoahos.mypureplayer.ui.components.SectionLabel
import com.yaoahos.mypureplayer.ui.components.alertDialogGlassSurface
import com.yaoahos.mypureplayer.ui.components.appGlassSurface
import com.yaoahos.mypureplayer.ui.components.glassPageBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置备份：本机文件与 WebDAV 两条通路，共用 [ConfigBackup] 的一份快照。
 *
 * 落盘前一律经 [BackupCrypto] 加密：
 *  - 本机文件名由应用生成（带时间戳，可留多个版本），每次备份弹窗输入密码；
 *  - 云端按设备各存一份（设备名 + 4 位短码），自动备份用页面里填写的密码，
 *    恢复时先列目录、由用户选择要恢复哪台设备的那份。
 *
 * 恢复是覆盖性操作，两条通路都先解密、解析，再弹一次确认框；解密/解析失败时不动当前配置。
 * 写盘阶段若失败则可能只恢复到一半（每段各自的落盘是原子的，段与段之间不是），
 * 提示只报「恢复失败或恢复不全」——重做一次即可收敛，不必为小概率事件加回滚。
 * 密码不进备份文件，结果提示里列出需要补填的 SMB 项。
 */
@Composable
fun BackupScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // App 启动时（后台恢复自动备份周期那一步）已经读过一次配置，这里直接同步取：
    // 8 个输入框第一帧就带真值 —— 不必先画空框再填、也不必先画骨架再换内容
    // （用户看到的「卡一下 + 残影」正是那两次切换叠在一起）。
    val seed = remember { BackupPrefs.cachedConfig() }
    val seedId = remember { BackupPrefs.cachedDeviceIdOrNull() }
    val seedPort = seed?.let { s ->
        s.port.ifBlank {
            ConfigBackup.defaultPort(if (s.url.trim().startsWith("http://", true)) "http" else "https")
                .toString()
        }
    } ?: ConfigBackup.defaultPort("https").toString()

    var davUrl by remember { mutableStateOf(seed?.url.orEmpty()) }
    var davPort by remember { mutableStateOf(seedPort) }
    var davFolder by remember {
        mutableStateOf(seed?.folder?.ifBlank { ConfigBackup.DEFAULT_FOLDER } ?: ConfigBackup.DEFAULT_FOLDER)
    }
    var davUser by remember { mutableStateOf(seed?.username.orEmpty()) }
    var davPassword by remember { mutableStateOf(seed?.password.orEmpty()) }
    var showDavPassword by remember { mutableStateOf(false) }
    // 云端备份口令：落盘（Keystore 加密），定时任务在后台也要能取到
    var backupPassword by remember { mutableStateOf(seed?.backupPassword.orEmpty()) }
    var showBackupPassword by remember { mutableStateOf(false) }
    var deviceName by remember {
        mutableStateOf(seed?.let { DeviceProfile.displayName(it.deviceName) } ?: DeviceProfile.hardwareName())
    }
    var deviceId by remember { mutableStateOf(seedId.orEmpty()) }
    var autoHours by remember { mutableStateOf(seed?.autoHours ?: WebDavConfig.DEFAULT_AUTO_HOURS) }
    var lastSyncAt by remember { mutableStateOf(seed?.lastSyncAt ?: 0L) }
    var lastResult by remember { mutableStateOf(seed?.lastResult.orEmpty()) }

    // 端口被手动改过就不再跟随协议自动切换
    var portEdited by remember { mutableStateOf(seed?.port?.isNotBlank() == true) }
    // 只有完全没有缓存时才需要等一次读盘（冷启动后立刻冲进本页）。
    // 正常路径从设置页点进来，缓存早已就绪，这一页从第一帧就是最终样子。
    var loaded by remember { mutableStateOf(seed != null) }
    // 只记录「当前正在跑哪个操作」：按钮自己转圈，不再在每张卡片上各画一份「处理中…」。
    var busyOp by remember { mutableStateOf<String?>(null) }
    val busy = busyOp != null
    // 解密并解析成功、等待用户确认的备份
    var pending by remember { mutableStateOf<ConfigBackup.Payload?>(null) }

    // 本机备份：点按钮先弹密码框，确认后才拉起系统保存面板
    var localBackupOpen by remember { mutableStateOf(false) }
    var pendingLocalPassword by remember { mutableStateOf<String?>(null) }
    // 本机恢复：选完文件再输密码解密
    var pendingLocalText by remember { mutableStateOf<String?>(null) }
    var localRestoreOpen by remember { mutableStateOf(false) }
    var localRestoreError by remember { mutableStateOf<String?>(null) }

    // 云端：列出的设备文件、自动备份周期选择
    var cloudFiles by remember { mutableStateOf<List<CloudBackup.RemoteFile>?>(null) }
    var autoDialogOpen by remember { mutableStateOf(false) }

    fun defaultPortOf(url: String): String =
        ConfigBackup.defaultPort(if (url.trim().startsWith("http://", true)) "http" else "https").toString()

    LaunchedEffect(Unit) {
        // 缓存已就绪时这里读到的值与首帧完全一致，赋值不会触发重组（Compose 的
        // MutableState 相同值直接跳过）。它只兜一种情况：冷启动后立刻进本页，
        // 缓存还没填上 —— 那时才真正读一次盘（含 Keystore 解密）。
        val (saved, id) = withContext(Dispatchers.IO) {
            BackupPrefs.get(context) to BackupPrefs.deviceId(context)
        }
        davUrl = saved.url
        davFolder = saved.folder.ifBlank { ConfigBackup.DEFAULT_FOLDER }
        davUser = saved.username
        davPassword = saved.password
        davPort = saved.port.ifBlank { defaultPortOf(saved.url) }
        portEdited = saved.port.isNotBlank()
        backupPassword = saved.backupPassword
        deviceName = DeviceProfile.displayName(saved.deviceName)
        deviceId = id
        autoHours = saved.autoHours
        lastSyncAt = saved.lastSyncAt
        lastResult = saved.lastResult
        // 所有字段一次性带上真实值再进场，标签不会从「居中」跳到「上浮」
        loaded = true
    }

    fun base(): String = ConfigBackup.baseUrl(davUrl, davPort)

    fun config(): WebDavConfig = WebDavConfig(
        url = davUrl,
        port = davPort,
        folder = davFolder,
        username = davUser,
        password = davPassword,
        backupPassword = backupPassword,
        deviceName = deviceName,
        autoHours = autoHours,
    )

    suspend fun saveDav() {
        BackupPrefs.save(context, config())
    }

    fun hasBase(): Boolean {
        if (base().isNotBlank()) return true
        Notice.warn("请先填写地址")
        return false
    }

    /** 显式保存：只登记定时任务，绝不上传，避免刚填完地址就把空配置推上云端。 */
    fun saveConnection() {
        if (!hasBase()) return
        scope.launch {
            busyOp = "保存"
            val r = runCatching {
                saveDav()
                if (autoHours > 0 && backupPassword.length >= BackupCrypto.MIN_PASSWORD) {
                    CloudBackupScheduler.ensure(context, autoHours)
                }
            }
            busyOp = null
            r.onSuccess { Notice.success("已保存") }
                .onFailure { Notice.error(it.message ?: "保存失败") }
        }
    }

    /** 云端备份（手动与自动）都靠这份口令，没设就不该往下走 */
    fun hasCloudPassword(): Boolean {
        if (backupPassword.length >= BackupCrypto.MIN_PASSWORD) return true
        Notice.warn("请先设置备份密码（至少 ${BackupCrypto.MIN_PASSWORD} 位）")
        return false
    }

    fun reportResult(r: ConfigBackup.RestoreResult) {
        val text = "已恢复 ${r.sectionCount} 类配置" +
            if (r.smbNeedPassword > 0) "；${r.smbNeedPassword} 个 SMB 共享缺密码，请重新填写" else ""
        if (r.smbNeedPassword > 0) Notice.warn(text) else Notice.success(text)
    }

    val saveLocal = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val password = pendingLocalPassword
        pendingLocalPassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyOp = "本机备份"
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    BackupCrypto.encrypt(ConfigBackup.export(), password)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入所选位置")
                }
            }
            busyOp = null
            result.onSuccess { Notice.success("已备份") }
                .onFailure { Notice.error(it.message ?: "备份失败") }
        }
    }

    val pickLocal = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyOp = "本机恢复"
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("无法读取所选文件")
                }
                if (BackupCrypto.isEncrypted(text)) {
                    pendingLocalText = text
                    localRestoreError = null
                    localRestoreOpen = true
                } else {
                    // 不带魔数的旧明文备份：没有密码可输，直接解析
                    pending = withContext(Dispatchers.IO) { ConfigBackup.parse(text, "") }
                }
            }
            busyOp = null
            result.onFailure { Notice.error(it.message ?: "读取备份失败") }
        }
    }

    /** 云端文件选好后下载、用已保存的备份密码解密，再交给确认框。 */
    fun loadCloudFile(file: CloudBackup.RemoteFile) {
        if (!hasCloudPassword()) return
        scope.launch {
            busyOp = "读取备份"
            val result = runCatching {
                saveDav()
                val text = CloudBackup.download(config(), file.fileName)
                withContext(Dispatchers.IO) { ConfigBackup.parse(text, backupPassword) }
            }
            busyOp = null
            result.onSuccess { pending = it }
                .onFailure { Notice.error(it.message ?: "云端备份读取失败") }
        }
    }

    fun loadCloudList() {
        if (!hasBase() || !hasCloudPassword()) return
        scope.launch {
            busyOp = "恢复"
            val result = runCatching {
                saveDav()
                CloudBackup.list(context, config(), deviceId)
            }
            busyOp = null
            result.onSuccess { files ->
                if (files.isEmpty()) Notice.warn("云端还没有备份") else cloudFiles = files
            }.onFailure { Notice.error(it.message ?: "读取云端备份列表失败") }
        }
    }

    fun uploadCloud() {
        if (!hasBase() || !hasCloudPassword()) return
        scope.launch {
            busyOp = "上传"
            val result = runCatching {
                saveDav()
                CloudBackup.upload(context, config(), backupPassword, deviceId)
            }
            busyOp = null
            result.onSuccess {
                lastSyncAt = it.at
                lastResult = "已上传"
                // 刚手动传过，自动备份的下一次从此刻起算一个周期，不必马上再传一遍
                if (autoHours > 0) {
                    CloudBackupScheduler.apply(context, autoHours)
                }
                Notice.success("已上传")
            }.onFailure {
                lastResult = it.message ?: "上传失败"
                Notice.error(it.message ?: "上传失败")
            }
        }
    }

    fun chooseAutoHours(hours: Int) {
        if (hours > 0 && backupPassword.length < BackupCrypto.MIN_PASSWORD) {
            Notice.warn("请先设置备份密码，否则后台无法加密备份")
            return
        }
        autoHours = hours
        scope.launch {
            busyOp = "自动备份"
            runCatching {
                saveDav()
                CloudBackupScheduler.apply(context, hours)
            }.onSuccess {
                Notice.success(if (hours == 0) "已关闭自动备份" else "已开启自动备份")
            }.onFailure { Notice.error(it.message ?: "设置失败") }
            busyOp = null
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxSize().glassPageBackground(),
        topBar = {
            PageTopBar(
                title = "配置备份",
                subtitle = "本机文件 / WebDAV · 加密 · 自动备份",
                onBack = { navController.popBackStack() },
            )
        },
    ) { padding ->
        // 数据在首帧就绪时，这一页第一帧就是最终样子：没有骨架、没有交叉淡化。
        //
        // 上一版用 Crossfade 在「骨架 → 内容」之间淡了 120ms，两层同时在场 ——
        // 灰色占位条与真实文字叠在一起，用户看到的就是「残影」；而且切换那一帧要
        // 一次性组合全部输入框，掉在转场动画里就是「进去卡一下」。
        // 现在只在真正没有缓存时（冷启动直接进本页）才画骨架，读完后直接替换。
        if (!loaded) {
            BackupSkeleton(Modifier.padding(padding))
        } else {
        // LazyColumn 在这里不只是"长列表懒加载"：首屏只组合能看见的卡片，
        // 剩下的表单滚到可见时才组合，把成本摊到滚动时（用户已经在动）。
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize()
                // adjustResize 只缩窗口；不跟着 ime 内缩的话，输入框正好落在输入法底下
                .imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
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
            }

            item {
            BackupGroup("本机文件") {
                BackupActionRow(
                    title = "备份到本机",
                    subtitle = "位置自选，文件名自动生成；点击后输入备份密码",
                    enabled = !busy,
                    onClick = {
                        pendingLocalPassword = null
                        localBackupOpen = true
                    },
                )
                BackupActionRow(
                    title = "从本机恢复",
                    subtitle = "选择备份文件，再输入当时的密码",
                    enabled = !busy,
                    onClick = { pickLocal.launch(arrayOf("*/*")) },
                )
            }
            }

            item {
            BackupGroup("WebDAV") {
                GlassTextField(
                    value = davUrl,
                    onValueChange = {
                        davUrl = it
                        if (!portEdited) davPort = defaultPortOf(it)
                    },
                    label = "地址",
                    placeholder = "dav.example.com/dav",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlassTextField(
                        value = davPort,
                        onValueChange = {
                            davPort = it.filter(Char::isDigit).take(5)
                            portEdited = true
                        },
                        label = "端口",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(108.dp),
                    )
                    GlassTextField(
                        value = davFolder,
                        onValueChange = { davFolder = it },
                        label = "目录",
                        placeholder = ConfigBackup.DEFAULT_FOLDER,
                        modifier = Modifier.weight(1f),
                    )
                }
                GlassTextField(
                    value = davUser,
                    onValueChange = { davUser = it },
                    label = "账号",
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
                GlassTextField(
                    value = davPassword,
                    onValueChange = { davPassword = it },
                    label = "密码",
                    visualTransformation = if (showDavPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        PasswordEye(showDavPassword) { showDavPassword = !showDavPassword }
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
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
                BackupButtonRow {
                    GlassButton(
                        text = "测试",
                        loading = busyOp == "测试",
                        onClick = {
                            if (busy || !hasBase()) return@GlassButton
                            scope.launch {
                                busyOp = "测试"
                                // 测试只验证当前输入框里的值；失败不应覆盖已保存的连接
                                val r = runCatching {
                                    withContext(Dispatchers.IO) {
                                        WebDavClient.check(base(), davUser, davPassword)
                                    }
                                }
                                busyOp = null
                                r.onSuccess { Notice.success("连接正常，记得点「保存」") }
                                    .onFailure { Notice.error(it.message ?: "连接失败") }
                            }
                        },
                    )
                    GlassButton(
                        text = "保存",
                        loading = busyOp == "保存",
                        onClick = { if (!busy) saveConnection() },
                    )
                }
            }
            }

            item {
            BackupGroup("云端备份") {
                NoteBlock(
                    icon = {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(17.dp),
                        )
                    },
                    text = "云端按设备各存一份：文件名带设备名与 4 位编号，同型号两台设备也不会互相覆盖。" +
                        "备份文件整体加密，密码保存在本机，用于手动与自动备份；遗失后无法还原。" +
                        "WebDAV 连接修改后点「保存」生效，测试只验证不保存；保存不会当场上传，" +
                        "第一次备份要等自动备份周期到点，手动上传请点「上传」。",
                )
                GlassTextField(
                    value = backupPassword,
                    onValueChange = { backupPassword = it },
                    label = "备份密码",
                    placeholder = "至少 ${BackupCrypto.MIN_PASSWORD} 位",
                    visualTransformation = if (showBackupPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        PasswordEye(showBackupPassword) {
                            showBackupPassword = !showBackupPassword
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                )
                GlassTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = "设备名称",
                    placeholder = DeviceProfile.hardwareName(),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
                if (deviceId.isNotBlank()) {
                    Text(
                        "$deviceId（固定不变，用于区分同型号设备）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                    )
                }
                if (base().isNotBlank()) {
                    Text(
                        "文件：" + ConfigBackup.remoteUrl(
                            base(),
                            davFolder,
                            DeviceProfile.fileName(deviceName, deviceId.ifBlank { "0000" }),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    )
                }
                AutoBackupRow(
                    autoHours = autoHours,
                    lastSyncAt = lastSyncAt,
                    lastResult = lastResult,
                    enabled = !busy,
                    onClick = { autoDialogOpen = true },
                )
                BackupButtonRow {
                    GlassButton(
                        text = "上传",
                        loading = busyOp == "上传",
                        onClick = { if (!busy) uploadCloud() },
                    )
                    GlassButton(
                        text = "恢复",
                        loading = busyOp == "恢复",
                        onClick = { if (!busy) loadCloudList() },
                    )
                }
            }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }

    // ---- 本机备份：设置密码 ----
    if (localBackupOpen) {
        PasswordDialog(
            title = "设置备份密码",
            hint = "至少 ${BackupCrypto.MIN_PASSWORD} 位。恢复时需要同一密码，密码不保存，遗失后无法还原。",
            confirmLabel = "选择位置",
            repeat = true,
            busy = false,
            onDismiss = { localBackupOpen = false },
            onConfirm = { password ->
                localBackupOpen = false
                pendingLocalPassword = password
                saveLocal.launch(ConfigBackup.suggestedFileName())
            },
        )
    }

    // ---- 本机恢复：输入密码 ----
    if (localRestoreOpen) {
        PasswordDialog(
            title = "输入备份密码",
            hint = "输入这份备份创建时设置的密码。",
            confirmLabel = "恢复",
            repeat = false,
            busy = busy,
            error = localRestoreError,
            onDismiss = {
                localRestoreOpen = false
                pendingLocalText = null
                localRestoreError = null
            },
            onConfirm = { password ->
                val text = pendingLocalText
                if (text != null) {
                    scope.launch {
                        busyOp = "本机恢复"
                        val r = runCatching {
                            withContext(Dispatchers.IO) { ConfigBackup.parse(text, password) }
                        }
                        busyOp = null
                        r.onSuccess {
                            pending = it
                            localRestoreOpen = false
                            pendingLocalText = null
                            localRestoreError = null
                        }.onFailure {
                            localRestoreError = it.message ?: "密码不正确，或备份文件已损坏"
                        }
                    }
                }
            },
        )
    }

    // ---- 云端：选择要恢复的设备 ----
    cloudFiles?.let { files ->
        GlassChoiceDialog(
            title = "从云端恢复",
            onDismiss = { cloudFiles = null },
        ) {
            files.forEach { file ->
                GlassChoiceRow(
                    icon = Icons.Rounded.PhoneAndroid,
                    // 标题只放设备名：短码在副标题里另有展示，两处都写会把长设备名挤到截断
                    title = file.deviceName.ifBlank { "未知设备" },
                    subtitle = buildString {
                        // 「本机」放在副标题开头：标题被设备名占满时也能看到
                        if (file.isLocal) append("本机 · ")
                        // 短码只留四位本身，含义由下方的设备短码说明解释
                        if (file.deviceId.isNotBlank()) {
                            append(file.deviceId)
                            append(" · ")
                        }
                        if (file.modified > 0) {
                            append(
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(Date(file.modified)),
                            )
                            append(" · ")
                        }
                        append(formatSize(file.sizeBytes))
                    },
                    onClick = {
                        cloudFiles = null
                        loadCloudFile(file)
                    },
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    // ---- 云端：自动备份周期 ----
    if (autoDialogOpen) {
        GlassChoiceDialog(
            title = "自动备份",
            onDismiss = { autoDialogOpen = false },
        ) {
            listOf(
                0 to "关闭",
                6 to "每 6 小时",
                24 to "每天",
                168 to "每 7 天",
            ).forEach { (hours, label) ->
                GlassChoiceRow(
                    icon = Icons.Rounded.Schedule,
                    title = label,
                    subtitle = when (hours) {
                        0 -> "不再自动上传，手动上传不受影响"
                        6 -> "一天最多四次，适合经常改动配置"
                        24 -> "默认；后台按周期上传，省电策略下可能延后"
                        else -> "一周一次，适合配置稳定的设备"
                    },
                    selected = hours == autoHours,
                    onClick = {
                        autoDialogOpen = false
                        // 点当前值只是关弹窗，不重排任务：否则每看一眼设置就把首轮往后推一个周期
                        if (hours != autoHours) chooseAutoHours(hours)
                    },
                )
                Spacer(Modifier.height(6.dp))
            }
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
                    busyOp = "恢复配置"
                    val result = runCatching {
                        // 中途按返回键会取消页面协程，恢复会停在「写了几类」的中间状态，
                        // 而且连提示都弹不出来。已经落盘的部分撤不回来，干脆让它跑完，
                        // 最坏也只是全部成功一次。
                        withContext(NonCancellable) { ConfigBackup.restore(payload) }
                    }
                    busyOp = null
                    result.onSuccess { reportResult(it) }
                        .onFailure { Notice.error("恢复失败或恢复不全，请重新恢复") }
                }
            },
            onDismiss = { pending = null },
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
}

@Composable
private fun AutoBackupRow(
    autoHours: Int,
    lastSyncAt: Long,
    lastResult: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interval = when (autoHours) {
        0 -> "关闭"
        6 -> "每 6 小时"
        24 -> "每天"
        168 -> "每 7 天"
        else -> "每 $autoHours 小时"
    }
    val status = buildString {
        append(interval)
        if (lastSyncAt > 0) {
            append(" · 上次 ")
            append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(lastSyncAt)))
        }
        if (lastResult.isNotBlank()) append(" · ").append(lastResult)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .glassPressable(enabled = enabled, radius = 12.dp, onClick = onClick)
            // 下边距交给紧随其后的 [BackupButtonRow]：这里再留 14dp 会与按钮行的
            // 12dp 叠成 26dp，同一页两组按钮的上距就对不上了
            .padding(start = 16.dp, end = 16.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "自动备份",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 卡片内的操作按钮行（测试/保存、上传/恢复）。
 *
 * 上下留白收在这一处，不让调用方各写一份：早先「测试/保存」是 `vertical = 10.dp`、
 * 「上传/恢复」是 `vertical = 4.dp`，而后者上方又是内建 14dp 底边距的自动备份行 ——
 * 结果一组悬在卡片中间、另一组几乎贴着底边，同页两组按钮看着像两种层级。
 */
@Composable
private fun BackupButtonRow(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(
            Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

/**
 * 备份密码输入框。新建密码要求两次一致；恢复时只输一次，错误文案留在框里方便重试。
 */
@Composable
private fun PasswordDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    repeat: Boolean,
    busy: Boolean,
    error: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showAgain by remember { mutableStateOf(false) }
    val matches = !repeat || password == again
    val valid = password.length >= BackupCrypto.MIN_PASSWORD && matches

    AlertDialog(
        shape = RoundedCornerShape(26.dp),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.alertDialogGlassSurface(),
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                GlassTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码",
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = { PasswordEye(showPassword) { showPassword = !showPassword } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (repeat) {
                    Spacer(Modifier.height(12.dp))
                    GlassTextField(
                        value = again,
                        onValueChange = { again = it },
                        label = "再输入一次",
                        visualTransformation = if (showAgain) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = { PasswordEye(showAgain) { showAgain = !showAgain } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (again.isNotEmpty() && !matches) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "两次输入的密码不一致",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    error ?: hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            GlassDialogButton(onClick = { onConfirm(password) }, enabled = valid && !busy, tone = GlassButtonTone.Accent) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = { GlassDialogButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 备份页的加载骨架：与真实布局同构的三张卡片（备份范围 / 本机文件 / 云端）。
 *
 * 正常情况下用不到它：配置在 App 启动时已经读过一次并留在 [BackupPrefs] 里，
 * 页面首帧直接就是真实内容。它只兜「冷启动后立刻冲进本页」那一次读盘 ——
 * 那时的骨架让转场第一帧就已经是"这一页"，而不是空页。
 *
 * 读完后**直接换成内容**，不做交叉淡化：两层同时在场会露出灰色占位条压在文字下的重影。
 */
@Composable
private fun BackupSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            // 横屏时三张卡的总高会超过可视区，骨架也不能被裁掉半张
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkeletonCard(titleWidth = 0.22f, lines = 4, height = 168.dp)
        SkeletonCard(titleWidth = 0.26f, lines = 2, height = 136.dp)
        SkeletonCard(titleWidth = 0.30f, lines = 3, height = 200.dp)
    }
}

/** 单张骨架卡：与 [BackupGroup] 同一种玻璃卡，内部用几条低对比灰条表示文字行 */
@Composable
private fun SkeletonCard(titleWidth: Float, lines: Int, height: Dp) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(height)
            .appGlassSurface(RoundedCornerShape(PageMetrics.CardRadius), elevation = 0.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth(titleWidth)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        )
        repeat(lines) { index ->
            Box(
                Modifier
                    .fillMaxWidth(if (index % 2 == 0) 0.92f else 0.68f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
            )
        }
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
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glassPressable(enabled = enabled, radius = 12.dp, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
