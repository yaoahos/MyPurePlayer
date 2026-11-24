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
import kotlinx.coroutines.sync.Mutex

class App : Application() {

    private val bootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 自动检查更新的互斥锁：冷启动与回到前台可能同时触发 */
    private val updateCheckGate = Mutex()

    /** 回到前台补查的最小间隔；冷启动不受它限制 */
    private val checkIntervalMs = 6L * 60 * 60 * 1000

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
     * 开关打开就问一次 GitHub，有新版本就弹窗带上发布说明。
     * 只在请求成功后写回时间：网络失败不占用节流窗口，下次启动会重试，
     * 避免发布当天老用户因为一次超时要等满 24 小时才收到提示。
     * 升级后的「本次更新内容」不再弹窗，版本说明统一放在关于页的更新日志里。
     *
     * 检查本身全在后台线程，失败静默，不打扰使用。
     */
    private fun autoCheckUpdate() {
        bootScope.launch {
            // 等首帧渲染完再联网，避免和媒体扫描、libVLC 初始化抢资源
            delay(2_000L)
            // 冷启动每次都查，不受节流限制：应用一直挂着、只在窗口内开关几次的用户，
            // 否则会一直错过发布提示（表现为「从来不会自动检查更新」）。
            checkUpdate(force = true)
        }
    }

    /**
     * 回到前台时补一次检查。
     *
     * 只在冷启动查的话，把应用留在后台一整天的人永远等不到第二次 ——
     * 这条路径按 6 小时节流，避免每次切回来都发一次请求。
     */
    fun checkUpdateOnForeground() {
        bootScope.launch { checkUpdate(force = false) }
    }

    /** 冷启动与回到前台共用一份逻辑；[force] 为 false 时受节流窗口约束 */
    private suspend fun checkUpdate(force: Boolean) {
        // 调试包是另一个应用，在线更新装的是正式包（等于在旁边多装一个），
        // 自动检查只会带来一个点了也装不了的弹窗
        if (BuildConfig.DEBUG) return
        // 两条路径可能同时到达（冷启动后立刻回到前台），只放行一个请求
        if (!updateCheckGate.tryLock()) return
        try {
            val settings = runCatching { Graph.settings.settings.first() }.getOrNull() ?: return
            if (!settings.autoCheckUpdate) return
            val now = System.currentTimeMillis()
            if (!force && now - settings.lastUpdateCheck < checkIntervalMs) return

            val result = UpdateChecker.check(UpdateChecker.DEFAULT_REPO, BuildConfig.VERSION_NAME)
                .getOrNull() ?: return
            // 请求成功才写回；失败保留原时间戳，下次进来重试
            runCatching { Graph.settings.setLastUpdateCheck(now) }
            if (result.hasUpdate) {
                UpdatePrompt.showNewVersion(result, UpdateLog.load(this))
            }
        } finally {
            updateCheckGate.unlock()
        }
    }
}
