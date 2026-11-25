package com.yaoahos.mypureplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    // 配置文件损坏时必须能自愈。没有这个 handler，DataStore 会抛 CorruptionException
    // 给每一个 collect 点（根 Composable 的 collectAsStateWithLifecycle 就在其中），
    // 表现是「每次冷启动必崩」，而用户唯一的自救手段是清应用数据 —— 顺带丢掉
    // 历史、播放列表、回收站索引。这里用空配置顶替并让 DataStore 重建文件。
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 播放顺序：0 不连播 / 1 顺序 / 2 列表循环 / 3 单集循环 / 4 随机 */
object PlayModes {
    const val NO_AUTO = 0
    const val SEQUENCE = 1
    const val LOOP_ALL = 2
    const val LOOP_ONE = 3
    const val SHUFFLE = 4

    fun label(mode: Int): String = when (mode) {
        NO_AUTO -> "播完暂停"
        LOOP_ALL -> "列表循环"
        LOOP_ONE -> "单集循环"
        SHUFFLE -> "随机播放"
        else -> "顺序播放"
    }

    fun description(mode: Int): String = when (mode) {
        NO_AUTO -> "每个视频播完就暂停"
        LOOP_ALL -> "播完最后一集回到第一集"
        LOOP_ONE -> "反复播放当前这一集"
        SHUFFLE -> "随机切换到队列里的另一集"
        else -> "依次播放，最后一集播完暂停"
    }
}

/** 设置项（只保留视频播放用得上的，音频/高级项一律不做） */
data class AppSettings(
    val darkMode: Int = 0, // 0 跟随系统 / 1 浅色 / 2 深色
    /**
     * 动态取色默认关闭：壁纸给的主色色相/饱和度不可控，玻璃面会随之漂移。
     * 默认使用固定配色 [themePalette]，需要跟壁纸时再在设置里打开。
     */
    val dynamicColor: Boolean = false,
    /** 固定主题色（见 ui.theme.ThemePalette），动态取色开启时被忽略 */
    val themePalette: String = "blue",
    val autoLoadSubtitle: Boolean = true,
    val hardwareDecode: Boolean = true,
    val autoPlayNext: Boolean = true,
    /**
     * 播放顺序：0 不连播 / 1 顺序播放 / 2 列表循环 / 3 单集循环 / 4 随机播放。
     *
     * 旧版本只有 autoPlayNext 一个开关，默认 true 对应「顺序播放」（最后一集停下），
     * false 对应「不连播」，首次读取时按旧键迁移，老用户行为不变。
     */
    val playMode: Int = 1,
    val resumePlayback: Boolean = true,
    /**
     * 无痕播放：开启后不再写新的观看历史与播放进度，已有记录保留不动。
     *
     * 只是「不记新的」，不做任何删除 —— 用户想清空历史有独立的删除入口，
     * 一个开关顺手抹掉记录会让人措手不及。
     */
    val incognito: Boolean = false,
    /** 启动时检查更新（默认开启：只在发现新版本时弹窗，平时静默） */
    val autoCheckUpdate: Boolean = true,
    /** 上次自动检查更新的时间，用于节流 */
    val lastUpdateCheck: Long = 0L,
    /** 播放中回到桌面 / 切任务时自动开启小窗 */
    val pipOnHome: Boolean = true,
    /** 网络位置页的排列与排序（持久化，冷启动后保持） */
    val networkGrid: Boolean = true,
    val networkSort: String = "NAME",
    val networkAscending: Boolean = true,
    /** SMB 浏览页的排列与排序（持久化，冷启动后保持） */
    val smbBrowserGrid: Boolean = true,
    val smbBrowserSort: String = "NAME",
    val smbBrowserAscending: Boolean = true,
    /**
     * 回收站自动清理。
     *
     * 双条件同时成立才动手：单条超过 [trashRetentionDays] 天 **并且** 回收站总量
     * 超过阈值。只看天数会让小回收站也被莫名清空，只看体量会让几百 MB 的老视频
     * 永远占着地方。
     */
    val trashAutoClean: Boolean = true,
    /** 自动清理的保留天数：30 / 60 / 90 */
    val trashRetentionDays: Int = 30,
)

class SettingsRepo(private val context: Context) {

    /**
     * 最近一次读到的真实设置，供**首帧**直接使用。
     *
     * 为什么需要它：DataStore 的第一次读盘是异步的（哪怕后续已命中内存缓存，
     * flow 的第一次 emit 也至少晚一帧）。页面若拿 `AppSettings()` 当兜底初值，
     * 首帧画的就是**默认值** —— 用户把硬件解码关成软件解码后进入设置页，
     * 会看到开关当着自己的面从"开"滑到"关"；深色模式、主题色选中环同理。
     *
     * 进程刚起、首读还没回来时是 null，调用方回落到默认值即可（这条路径只在
     * 冷启动后立刻进某个页面的极短窗口里出现）。
     */
    @Volatile
    private var latest: AppSettings? = null

    /** 首帧用：有真实值就用真实值，没有就返回 null 由调用方决定怎么兜底 */
    fun currentOrNull(): AppSettings? = latest

    private object Keys {
        val darkMode = intPreferencesKey("dark_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val themePalette = stringPreferencesKey("theme_palette")
        val autoSubtitle = booleanPreferencesKey("auto_subtitle")
        val hwDecode = booleanPreferencesKey("hw_decode")
        val autoNext = booleanPreferencesKey("auto_next")
        val playMode = intPreferencesKey("play_mode")
        val resume = booleanPreferencesKey("resume")
        val incognito = booleanPreferencesKey("incognito")
        val autoCheckUpdate = booleanPreferencesKey("auto_check_update")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val pipOnHome = booleanPreferencesKey("pip_on_home")
        val networkGrid = booleanPreferencesKey("network_grid")
        val networkSort = stringPreferencesKey("network_sort")
        val networkAscending = booleanPreferencesKey("network_ascending")
        val smbBrowserGrid = booleanPreferencesKey("smb_browser_grid")
        val smbBrowserSort = stringPreferencesKey("smb_browser_sort")
        val smbBrowserAscending = booleanPreferencesKey("smb_browser_ascending")
        val trashAutoClean = booleanPreferencesKey("trash_auto_clean")
        val trashRetentionDays = intPreferencesKey("trash_retention_days")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        // 读盘 IO 错误（损坏之外的场景，如权限、空间）同样退回默认值：
        // 设置页少一个开关不值得让应用起不来，下一次写操作会把文件覆盖成正常的。
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
        AppSettings(
            darkMode = prefs[Keys.darkMode] ?: 0,
            dynamicColor = prefs[Keys.dynamicColor] ?: false,
            themePalette = prefs[Keys.themePalette] ?: "blue",
            autoLoadSubtitle = prefs[Keys.autoSubtitle] ?: true,
            hardwareDecode = prefs[Keys.hwDecode] ?: true,
            autoPlayNext = prefs[Keys.autoNext] ?: true,
            playMode = prefs[Keys.playMode] ?: if (prefs[Keys.autoNext] == false) 0 else 1,
            resumePlayback = prefs[Keys.resume] ?: true,
            incognito = prefs[Keys.incognito] ?: false,
            autoCheckUpdate = prefs[Keys.autoCheckUpdate] ?: true,
            lastUpdateCheck = prefs[Keys.lastUpdateCheck] ?: 0L,
            pipOnHome = prefs[Keys.pipOnHome] ?: true,
            networkGrid = prefs[Keys.networkGrid] ?: true,
            networkSort = prefs[Keys.networkSort] ?: "NAME",
            networkAscending = prefs[Keys.networkAscending] ?: true,
            smbBrowserGrid = prefs[Keys.smbBrowserGrid] ?: true,
            smbBrowserSort = prefs[Keys.smbBrowserSort] ?: "NAME",
            smbBrowserAscending = prefs[Keys.smbBrowserAscending] ?: true,
            trashAutoClean = prefs[Keys.trashAutoClean] ?: true,
            trashRetentionDays = prefs[Keys.trashRetentionDays] ?: 30,
        )
    }
        // 每个收集点都顺手刷新快照：设置页、播放页、根主题共用同一份 flow，
        // 谁先收到就谁写，后面所有页面的首帧都能拿到真值
        .onEach { latest = it }

    /**
     * 启动时读一次，把 [latest] 填上。
     *
     * 这样从「更多」点进设置页时（至少几百毫秒后），首帧已经是真实设置，
     * 开关不会先画默认值再滑过去。失败静默：读不到就继续用默认值。
     */
    suspend fun warmUp() {
        runCatching { settings.first() }
    }

    /** 配置恢复：与导出一一对应，一次 edit 写完，避免每写一项目录就触发一次重组 */
    suspend fun restore(s: ConfigBackup.BackupSettings) {
        context.settingsDataStore.edit {
            it[Keys.darkMode] = s.darkMode
            it[Keys.dynamicColor] = s.dynamicColor
            it[Keys.themePalette] = s.themePalette
            it[Keys.autoSubtitle] = s.autoLoadSubtitle
            it[Keys.hwDecode] = s.hardwareDecode
            it[Keys.autoNext] = s.autoPlayNext
            it[Keys.playMode] = s.playMode
            it[Keys.resume] = s.resumePlayback
            it[Keys.autoCheckUpdate] = s.autoCheckUpdate
            it[Keys.pipOnHome] = s.pipOnHome
            it[Keys.networkGrid] = s.networkGrid
            it[Keys.networkSort] = s.networkSort
            it[Keys.networkAscending] = s.networkAscending
            it[Keys.smbBrowserGrid] = s.smbBrowserGrid
            it[Keys.smbBrowserSort] = s.smbBrowserSort
            it[Keys.smbBrowserAscending] = s.smbBrowserAscending
        }
    }

    suspend fun setDarkMode(value: Int) {
        context.settingsDataStore.edit { it[Keys.darkMode] = value }
    }

    suspend fun setDynamicColor(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.dynamicColor] = value }
    }

    suspend fun setThemePalette(key: String) {
        context.settingsDataStore.edit { it[Keys.themePalette] = key }
    }

    suspend fun setAutoLoadSubtitle(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.autoSubtitle] = value }
    }

    suspend fun setTrashAutoClean(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.trashAutoClean] = value }
    }

    suspend fun setTrashRetentionDays(value: Int) {
        // 只接受三档：给错值时回落到 30，避免界面上写着 60 实际用的是 7 这种不一致
        val days = if (value in intArrayOf(30, 60, 90)) value else 30
        context.settingsDataStore.edit { it[Keys.trashRetentionDays] = days }
    }

    suspend fun setHardwareDecode(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.hwDecode] = value }
    }

    suspend fun setAutoPlayNext(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.autoNext] = value }
    }

    suspend fun setPlayMode(value: Int) {
        context.settingsDataStore.edit {
            it[Keys.playMode] = value
            // 旧键同步维护：备份/还原与第三方读取仍按「是否连播」理解
            it[Keys.autoNext] = value != 0
        }
    }

    suspend fun setResumePlayback(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.resume] = value }
    }

    suspend fun setIncognito(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.incognito] = value }
    }

    suspend fun setAutoCheckUpdate(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.autoCheckUpdate] = value }
    }

    suspend fun setLastUpdateCheck(value: Long) {
        context.settingsDataStore.edit { it[Keys.lastUpdateCheck] = value }
    }

    suspend fun setPipOnHome(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.pipOnHome] = value }
    }

    suspend fun setNetworkLayout(grid: Boolean, sort: String, ascending: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.networkGrid] = grid
            it[Keys.networkSort] = sort
            it[Keys.networkAscending] = ascending
        }
    }

    suspend fun setSmbBrowserLayout(grid: Boolean, sort: String, ascending: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.smbBrowserGrid] = grid
            it[Keys.smbBrowserSort] = sort
            it[Keys.smbBrowserAscending] = ascending
        }
    }
}
