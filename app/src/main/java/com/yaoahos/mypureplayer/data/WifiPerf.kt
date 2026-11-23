package com.yaoahos.mypureplayer.data

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * 浏览 / 播放网络视频期间临时把 Wi-Fi 拉到「低延迟」。
 *
 * 为什么需要这个：实测这台手机 ping NAS 平均 22ms，而同一网段的电脑只有 <1ms。
 * 差别不在信号（RSSI 约 -30dBm，协商 192Mbps），而在 2.4GHz 的省电机制 ——
 * 无线侧把数据帧攒到 DTIM 周期才发给手机，于是每个来回都被固定加上十几毫秒。
 *
 * SMB 的每次打开、目录查询都要若干个往返，读吞吐同样受往返延迟与 TCP 窗口约束：
 * 22ms 时实测只有 1~2MB/s（正好是实测值），降到 3ms 就能到十几 MB/s。
 * 也就是说「网络视频加载慢、快进慢、缩略图抽不出来」这一串问题，
 * 瓶颈全部集中在这 20ms 上，而不是解码或线程模型。
 *
 * 低延迟锁（API 29+）会关掉这层省电；老系统退到 HIGH_PERF。
 * 引用计数：列表页、浏览页、播放页可能同时持有，谁都不用管别人。
 */
object WifiPerf {

    private const val TAG = "WifiPerf"

    private var lock: WifiManager.WifiLock? = null
    private var refs = 0

    @Synchronized
    fun acquire(context: Context) {
        refs++
        if (lock != null) return
        runCatching {
            val manager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val created = manager.createWifiLock(mode, "mypureplayer:smb")
            created.setReferenceCounted(false)
            created.acquire()
            lock = created
            Log.d(TAG, "已开启低延迟 Wi-Fi（refs=$refs）")
        }.onFailure { Log.w(TAG, "低延迟 Wi-Fi 开启失败：${it.javaClass.simpleName}") }
    }

    @Synchronized
    fun release() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs > 0) return
        runCatching { lock?.release() }
        lock = null
    }
}
