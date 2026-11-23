package com.yaoahos.mypureplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.backupDataStore: DataStore<Preferences> by preferencesDataStore(name = "backup")

/**
 * WebDAV 备份目标。
 *
 * url 只到主机与路径（例如 `dav.example.com/dav`），port 单独填（默认按协议 443/80），
 * 真正的文件地址 = 地址（含端口）/ 目录 / 固定文件名。
 * folder 是服务器上的目录名，默认取应用名，可改成已有目录；不填就落在地址所在目录。
 * password 在内存里是明文，落盘时用独立的 Keystore 密钥加密。
 */
data class WebDavConfig(
    val url: String = "",
    val port: String = "",
    val folder: String = ConfigBackup.DEFAULT_FOLDER,
    val username: String = "",
    val password: String = "",
)

object BackupPrefs {

    private object Keys {
        val url = stringPreferencesKey("webdav_url")
        val port = stringPreferencesKey("webdav_port")
        val folder = stringPreferencesKey("webdav_folder")
        val username = stringPreferencesKey("webdav_username")
        val password = stringPreferencesKey("webdav_password")
    }

    // 与 SMB 用不同的 Keystore 别名：一个凭据泄露/换钥匙不该波及另一处
    private val cipher = CredentialCipher("mypureplayer_webdav_cred_v1")

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
        return WebDavConfig(
            url = directoryOf(prefs[Keys.url].orEmpty()),
            port = prefs[Keys.port].orEmpty(),
            folder = prefs[Keys.folder] ?: ConfigBackup.DEFAULT_FOLDER,
            username = prefs[Keys.username].orEmpty(),
            password = cipher.decrypt(prefs[Keys.password].orEmpty()),
        )
    }

    suspend fun save(context: Context, config: WebDavConfig) {
        context.backupDataStore.edit {
            it[Keys.url] = directoryOf(config.url)
            it[Keys.port] = config.port.trim()
            it[Keys.folder] = config.folder.trim().trim('/')
            it[Keys.username] = config.username.trim()
            it[Keys.password] = cipher.encrypt(config.password)
        }
    }
}
