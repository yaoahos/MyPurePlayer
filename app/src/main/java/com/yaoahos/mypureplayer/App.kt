package com.yaoahos.mypureplayer

import android.app.Application
import android.os.StrictMode
import com.yaoahos.mypureplayer.data.BackupPrefs
import com.yaoahos.mypureplayer.data.CloudBackupScheduler
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.UpdateChecker
import com.yaoahos.mypureplayer.data.UpdateLog
import com.yaoahos.mypureplayer.data.UpdatePrompt
import com.yaoahos.mypureplayer.media.Thumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application() {

    private val bootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) installStrictMode()
        Graph.init(this)
        restoreCloudBackupSchedule()
        autoCheckUpdate()
    }

    /**
     * 每次启动把自动备份的周期恢复回来。
     *
     * WorkManager 自己的周期任务在重启后会保留，但用户可能清过数据/系统清理过后台任务；
     * 这里用同一份唯一任务名重新登记。用 KEEP 而不是 UPDATE：已有任务保持原来的节奏，
     * 不会被这次启动重置、更不会当场跑一轮把刚配好的（还可能是空的）配置抢先传上去；
     * 只有任务真的丢了才会新建，新建同样推迟一个周期。
     */
    private fun restoreCloudBackupSchedule() {
        bootScope.launch {
            runCatching {
                val config = BackupPrefs.get(this@App)
                CloudBackupScheduler.ensure(
                    this@App,
                    if (config.autoBackupOn) config.autoHours else 0,
                )
            }
        }
    }

    /**
     * 系统低内存回调：缩略图内存缓存（24MB LRU + 封面字节）跟着裁剪，
     * 避免应用在后台时因缓存占着内存被系统加狠杀伤。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Thumbnails.trimMemory(level)
    }

    /**
     * 仅 debug 构建启用：主线程有磁盘读写或网络时在 logcat 打一条栈。
     *
     * 体检时靠人工 grep 找主线程 IO 有盲区（比如某条冷偏的路径只在特定操作下才走到），
     * StrictMode 会把调用栈直接打出来。用 penaltyLog 而不是 penaltyDeath，
     * 免得一个可忽略的读把调试打断；release 构建里这段代码被 R8 连同 BuildConfig.DEBUG 常量一起裁掉。
     */
    private fun installStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }

    /**
     * 冷启动自动检查更新。
     *
     * 两件事：
     * 1. 版本号变了（刚升级上来）→ 弹一次「本次更新内容」，数据取自内置更新日志；
     * 2. 开关打开且距上次检查超过 24 小时 → 问一次 GitHub，有新版本就弹窗带上发布说明。
     *
     * 检查本身全在后台线程，失败静默，不打扰使用。
     */
    private fun autoCheckUpdate() {
        bootScope.launch {
            val current = BuildConfig.VERSION_NAME
            // 升级后的更新日志读本地资源，和设备是否联网无关，可以立即弹
            runCatching {
                UpdatePrompt.maybeShowWhatsNew(this@App, current, UpdateLog.load(this@App))
            }

            // 等首帧渲染完再联网，避免和媒体扫描、libVLC 初始化抢资源
            delay(2_000L)
            val settings = runCatching { Graph.settings.settings.first() }.getOrNull() ?: return@launch
            if (!settings.autoCheckUpdate) return@launch
            val now = System.currentTimeMillis()
            if (now - settings.lastUpdateCheck < 24L * 60 * 60 * 1000) return@launch

            // 先写回时间，避免网络失败时每次启动都重试
            runCatching { Graph.settings.setLastUpdateCheck(now) }
            val result = UpdateChecker.check(UpdateChecker.DEFAULT_REPO, current).getOrNull()
                ?: return@launch
            if (result.hasUpdate) {
                UpdatePrompt.showNewVersion(result, UpdateLog.load(this@App))
            }
        }
    }
}
