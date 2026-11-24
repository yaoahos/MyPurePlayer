package com.yaoahos.mypureplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

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
)

class SettingsRepo(private val context: Context) {

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
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
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
        )
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
