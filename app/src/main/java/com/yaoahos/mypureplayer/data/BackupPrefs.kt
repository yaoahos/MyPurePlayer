package com.yaoahos.mypureplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.backupDataStore: DataStore<Preferences> by preferencesDataStore(name = "backup")

/**
 * 云端备份目标与自动备份设置。
 *
 * url 只到主机与路径（例如 `dav.example.com/dav`），port 单独填（默认按协议 443/80），
 * 真正的文件地址 = 地址（含端口）/ 目录 / 按设备生成的文件名。
 * folder 是服务器上的目录名，默认取应用名，可改成已有目录；不填就落在地址所在目录。
 *
 * [password] 是 WebDAV 账号密码，[backupPassword] 是给备份文件加密的口令。
 * 两者在内存里是明文，落盘时用独立的 Keystore 密钥加密——云端备份要能定时在后台跑，
 * 就必须能在没有用户输入的情况下取到口令；Keystore 绑本机，换设备后解不开也拿不走。
 */
data class WebDavConfig(
    val url: String = "",
    val port: String = "",
    val folder: String = ConfigBackup.DEFAULT_FOLDER,
    val username: String = "",
    val password: String = "",
    val backupPassword: String = "",
    /** 云端文件名里的设备名；空串表示还没改过，取设备型号 */
    val deviceName: String = "",
    /** 自动备份周期（小时），0 = 关闭 */
    val autoHours: Int = DEFAULT_AUTO_HOURS,
    val lastSyncAt: Long = 0L,
    val lastResult: String = "",
) {
    val configured: Boolean get() = url.isNotBlank()

    /** 真正会跑自动备份：连接填了、周期不是关闭、口令也在 */
    val autoBackupOn: Boolean
        get() = configured && autoHours > 0 && backupPassword.length >= BackupCrypto.MIN_PASSWORD

    companion object {
        /** 默认每天一次：配好地址与口令就该「配完即生效」，不必再回来拨一次开关 */
        const val DEFAULT_AUTO_HOURS = 24
    }
}

object BackupPrefs {

    private object Keys {
        val url = stringPreferencesKey("webdav_url")
        val port = stringPreferencesKey("webdav_port")
        val folder = stringPreferencesKey("webdav_folder")
        val username = stringPreferencesKey("webdav_username")
        val password = stringPreferencesKey("webdav_password")
        val backupPassword = stringPreferencesKey("backup_cipher")
        val deviceName = stringPreferencesKey("backup_device_name")
        val deviceId = stringPreferencesKey("backup_device_id")
        val lastFile = stringPreferencesKey("backup_last_file")
        val autoHours = intPreferencesKey("backup_auto_hours")
        val lastSyncAt = longPreferencesKey("backup_last_sync_at")
        val lastResult = stringPreferencesKey("backup_last_result")
    }

    // 与 SMB 用不同的 Keystore 别名：一个凭据泄露/换钥匙不该波及另一处
    private val cipher = CredentialCipher("mypureplayer_webdav_cred_v1")

    /**
     * 进程内最后一次读到的配置与短码。
     *
     * 备份页有 8 个输入框：若先用空值组合、读盘后再把值填进去，`OutlinedTextField`
     * 的标签会当着用户的面从居中浮到顶部，同时还要多组合一轮。App 启动时
     * [CloudBackupScheduler] 的恢复流程已经读过一次，这里把结果留下来，
     * 页面首帧就能同步拿到真值 —— 既不跳，也不必先画骨架再换内容。
     *
     * 只在同一进程内有效；写盘一律经本对象的 [save]，因此不会读到过期值。
     */
    @Volatile
    private var cached: WebDavConfig? = null

    @Volatile
    private var cachedDeviceId: String? = null

    /** 页面首帧用；进程刚起、还没读过时是 null */
    fun cachedConfig(): WebDavConfig? = cached

    fun cachedDeviceIdOrNull(): String? = cachedDeviceId

    /**
     * 地址栏允许粘贴带文件名的完整地址（`.../MyPurePlayer-config.mpp`），
     * 这里把它折回目录，避免最终路径里多出一层假目录。
     */
    private val fileExtensions = setOf(ConfigBackup.EXTENSION)

    private fun directoryOf(raw: String): String {
        val clean = raw.trim()
        val looksLikeFile = fileExtensions.any { clean.endsWith(it, ignoreCase = true) }
        return if (looksLikeFile) clean.substringBeforeLast('/') else clean
    }

    suspend fun get(context: Context): WebDavConfig {
        val prefs = context.backupDataStore.data.first()
        val config = WebDavConfig(
            url = directoryOf(prefs[Keys.url].orEmpty()),
            port = prefs[Keys.port].orEmpty(),
            folder = prefs[Keys.folder] ?: ConfigBackup.DEFAULT_FOLDER,
            username = prefs[Keys.username].orEmpty(),
            password = cipher.decrypt(prefs[Keys.password].orEmpty()),
            backupPassword = cipher.decrypt(prefs[Keys.backupPassword].orEmpty()),
            deviceName = prefs[Keys.deviceName].orEmpty(),
            autoHours = prefs[Keys.autoHours] ?: WebDavConfig.DEFAULT_AUTO_HOURS,
            lastSyncAt = prefs[Keys.lastSyncAt] ?: 0L,
            lastResult = prefs[Keys.lastResult].orEmpty(),
        )
        cached = config
        return config
    }

    suspend fun save(context: Context, config: WebDavConfig) {
        context.backupDataStore.edit {
            it[Keys.url] = directoryOf(config.url)
            it[Keys.port] = config.port.trim()
            it[Keys.folder] = config.folder.trim().trim('/')
            it[Keys.username] = config.username.trim()
            it[Keys.password] = cipher.encrypt(config.password)
            it[Keys.backupPassword] = cipher.encrypt(config.backupPassword)
            // 设备名落盘时补全默认值：定时任务在后台跑，不能依赖界面先算一遍
            it[Keys.deviceName] = DeviceProfile.displayName(config.deviceName)
            it[Keys.autoHours] = config.autoHours
        }
        cached = config
    }

    /**
     * 本机短码：首次生成后不再变。改名只影响文件名前半段，短码始终能区分同型号设备。
     */
    suspend fun deviceId(context: Context): String {
        cachedDeviceId?.let { return it }
        context.backupDataStore.data.first()[Keys.deviceId]
            ?.takeIf { it.length == 4 }
            ?.let {
                cachedDeviceId = it
                return it
            }
        val generated = DeviceProfile.newId()
        context.backupDataStore.edit { it[Keys.deviceId] = generated }
        cachedDeviceId = generated
        return generated
    }

    /** 上次成功上传用的文件名：改名后据此把旧文件清掉，不在云端留幽灵设备。 */
    suspend fun lastFileName(context: Context): String =
        context.backupDataStore.data.first()[Keys.lastFile].orEmpty()

    suspend fun markFileName(context: Context, fileName: String) {
        context.backupDataStore.edit { it[Keys.lastFile] = fileName }
    }

    suspend fun markSync(context: Context, at: Long, result: String) {
        context.backupDataStore.edit {
            it[Keys.lastSyncAt] = at
            it[Keys.lastResult] = result
        }
    }
}
