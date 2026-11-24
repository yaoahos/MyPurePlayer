package com.yaoahos.mypureplayer.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 云端备份：一设备一份 + 定时上传。
 *
 * 文件名由 [DeviceProfile] 生成（设备名 + 4 位短码），上传只覆盖自己那一份，
 * 同型号的两台设备也不会互相盖掉。恢复时先列目录，由用户挑要恢复哪台设备的那份。
 *
 * 后台定时只做「上传」这一件事：后台没有界面可以确认，一旦云端是旧配置，
 * 静默覆盖本机就是不可撤销的破坏。下载恢复一律由用户手动触发。
 */
object CloudBackup {

    /** 云端的一份设备配置，供恢复时列表选择。 */
    data class RemoteFile(
        val fileName: String,
        val deviceName: String,
        val deviceId: String,
        val sizeBytes: Long,
        val modified: Long,
        val isLocal: Boolean = false,
        val legacy: Boolean = false,
    ) {}

    data class UploadResult(val at: Long, val fileName: String)

    /**
     * 上传本机配置。覆盖前先把云端现有文件留成 `.prev`：任何一次误覆盖都还能捞回上一版，
     * 而配置本身只有几十 KB，多留一份几乎没有成本。
     */
    suspend fun upload(
        context: Context,
        config: WebDavConfig,
        password: String,
        deviceId: String,
    ): UploadResult {
        require(config.configured) { "请先填写 WebDAV 地址" }
        require(password.length >= BackupCrypto.MIN_PASSWORD) {
            "备份密码至少 ${BackupCrypto.MIN_PASSWORD} 位"
        }
        val base = ConfigBackup.baseUrl(config.url, config.port)
        val fileName = DeviceProfile.fileName(config.deviceName, deviceId)

        return withContext(Dispatchers.IO) {
            val bytes = BackupCrypto.encrypt(ConfigBackup.export(), password)
                .toByteArray(Charsets.UTF_8)
            WebDavClient.ensureFolder(base, config.folder, config.username, config.password)

            val url = ConfigBackup.remoteUrl(base, config.folder, fileName)
            val previous = WebDavClient.getOrNull(url, config.username, config.password)
            if (previous != null && previous.isNotEmpty()) {
                runCatching {
                    WebDavClient.put("$url.prev", config.username, config.password, previous)
                }
            }
            WebDavClient.put(url, config.username, config.password, bytes)

            // 改过设备名时旧文件名已经没人认领：顺手删掉，免得云端堆一串幽灵设备。
            // 删不掉也不影响本次上传结果（下次改名还会再试）。
            val stale = BackupPrefs.lastFileName(context)
            if (stale.isNotBlank() && stale != fileName) {
                runCatching {
                    WebDavClient.delete(
                        ConfigBackup.remoteUrl(base, config.folder, stale),
                        config.username,
                        config.password,
                    )
                    WebDavClient.delete(
                        ConfigBackup.remoteUrl(base, config.folder, "$stale.prev"),
                        config.username,
                        config.password,
                    )
                }
            }

            val now = System.currentTimeMillis()
            BackupPrefs.markFileName(context, fileName)
            BackupPrefs.markSync(context, now, "已上传")
            UploadResult(now, fileName)
        }
    }

    /**
     * 列出云端所有设备的配置。本机那份排最前（恢复时默认选它），其余按修改时间倒序——
     * 用户通常想找「另一台设备最近上传的那份」。
     */
    suspend fun list(
        context: Context,
        config: WebDavConfig,
        deviceId: String,
    ): List<RemoteFile> {
        require(config.configured) { "请先填写 WebDAV 地址" }
        val base = ConfigBackup.baseUrl(config.url, config.port)
        return withContext(Dispatchers.IO) {
            val entries = WebDavClient.list(
                ConfigBackup.remoteDir(base, config.folder),
                config.username,
                config.password,
            )
            entries.mapNotNull { entry ->
                if (entry.isDir || entry.name.endsWith(".prev")) return@mapNotNull null
                val parsed = DeviceProfile.parse(entry.name)
                when {
                    parsed != null -> RemoteFile(
                        fileName = entry.name,
                        deviceName = parsed.first,
                        deviceId = parsed.second,
                        sizeBytes = entry.size,
                        modified = entry.modified,
                        // 认「是不是本机」只看 4 位短码，不看整个文件名：
                        // 用户改过设备名之后，云端旧文件的名字还带着旧设备名，
                        // 按整名比对会认不出来，恢复列表里就永远没有「本机」标记。
                        // 短码首次生成后改名不变，是这台设备的稳定身份。
                        isLocal = parsed.second == deviceId,
                    )
                    // 旧版单文件：升级后仍能找回自己的配置
                    entry.name == ConfigBackup.REMOTE_FILE -> RemoteFile(
                        fileName = entry.name,
                        deviceName = "旧版备份",
                        deviceId = "",
                        sizeBytes = entry.size,
                        modified = entry.modified,
                        legacy = true,
                    )
                    else -> null
                }
            }.sortedWith(
                compareByDescending<RemoteFile> { it.isLocal }
                    .thenByDescending { it.modified },
            )
        }
    }

    /** 下载指定文件的原始文本（加密态），解密与解析仍由调用方按用户输入的口令完成。 */
    suspend fun download(config: WebDavConfig, fileName: String): String =
        withContext(Dispatchers.IO) {
            val base = ConfigBackup.baseUrl(config.url, config.port)
            WebDavClient.get(
                ConfigBackup.remoteUrl(base, config.folder, fileName),
                config.username,
                config.password,
            ).toString(Charsets.UTF_8)
        }
}

/**
 * 定时上传。口令已在本机（Keystore 加密）保存，所以后台不需要用户再输入；
 * 没设口令、周期为关闭、地址没填时直接成功退出，不浪费唤醒。
 */
class CloudBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val config = runCatching { BackupPrefs.get(context) }.getOrNull() ?: return Result.success()
        if (!config.configured || config.autoHours <= 0) return Result.success()
        if (config.backupPassword.length < BackupCrypto.MIN_PASSWORD) {
            BackupPrefs.markSync(context, System.currentTimeMillis(), "未设置备份密码，已跳过自动备份")
            return Result.success()
        }

        val deviceId = BackupPrefs.deviceId(context)
        return try {
            val result = CloudBackup.upload(context, config, config.backupPassword, deviceId)
            BackupPrefs.markSync(context, result.at, "自动备份完成")
            Result.success()
        } catch (t: Throwable) {
            val message = t.message ?: "自动备份失败"
            // 只有「重试也没用」的才算真失败；超时、DNS、5xx 等交给 WorkManager 退避重试
            val permanent = isPermanentBackupFailure(message)
            BackupPrefs.markSync(
                context,
                System.currentTimeMillis(),
                if (permanent) message else "$message（将自动重试）",
            )
            if (permanent) Result.success() else Result.retry()
        }
    }
}

/** 只认凭据 / 配置 / 路径这几类确定性问题；网络类一律按可重试处理。 */
internal fun isPermanentBackupFailure(message: String): Boolean =
    message.contains("密码") || message.contains("请先填写") ||
        message.contains("认证") || message.contains("权限") ||
        message.contains("HTTP 401") || message.contains("HTTP 403") ||
        message.contains("HTTP 404") || message.contains("HTTP 405")

/** 自动备份的调度入口：周期变了就重排，关掉就撤销，不留重复任务。 */
object CloudBackupScheduler {

    private const val WORK_NAME = "mypureplayer-cloud-backup"

    /**
     * 用户主动动作（改周期、手动上传完）之后重新计时。
     *
     * 用「取消重排」而不是「更新」：更新会保留原任务的入队时间，周期已过时会立刻跑一轮，
     * 那就等于在用户刚改完设置时就抢先上传一份还没配好的配置。这里要的是「从现在起再等
     * 一个周期」，只有 CANCEL_AND_REENQUEUE 能保证首轮确实被推到 [autoHours] 之后。
     */
    fun apply(context: Context, autoHours: Int) {
        enqueue(context, autoHours, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
    }

    /**
     * 只确认「任务还在」：保存连接、冷启动都走这里。
     *
     * KEEP 表示已有任务原样保留，不重置节奏也不要提前唤醒；任务真丢了（清数据、系统
     * 清理过后台任务）才新建，新建同样推迟一个周期。这样「保存连接」只是把配置记下来，
     * 上传始终等设定的自动备份到点才发生，不会当场把空配置写进云端。
     */
    fun ensure(context: Context, autoHours: Int) {
        enqueue(context, autoHours, ExistingPeriodicWorkPolicy.KEEP)
    }

    private fun enqueue(context: Context, autoHours: Int, policy: ExistingPeriodicWorkPolicy) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (autoHours <= 0) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(autoHours.toLong(), TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            // 失败重试从 1 分钟起指数退避：网络恢复后能较快补上，真断网时不会频繁唤醒
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            // 首轮统一推迟一个周期：新建任务时 WorkManager 会立刻跑一次，那正是要避免的
            .setInitialDelay(autoHours.toLong(), TimeUnit.HOURS)
            .build()
        runCatching {
            workManager.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        }
    }
}
