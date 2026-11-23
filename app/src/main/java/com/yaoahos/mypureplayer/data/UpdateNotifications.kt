package com.yaoahos.mypureplayer.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 更新下载的系统通知。
 *
 * 为什么要有这一套（对照 SafePassword 的做法补的）：
 * 之前下载进度只画在弹窗里，而且下载中不允许关弹窗 —— 用户一旦切到别的应用
 * 就完全看不到进度，切回来又不知道下到哪了；下载完成后弹窗直接拉起系统安装器，
 * 万一系统拦了未知来源，这次更新就彻底没有入口了。
 *
 * 现在：
 *  - 下载中：进度通知（不可划掉），切到任何界面都能看到；
 *  - 完成：撤掉进度、另发一条 "已下载，点击安装"，点一下回到 App；
 *  - 失败 / 取消：两条一起清掉，不留一条点不动的死通知。
 */
object UpdateNotifications {

    private const val CHANNEL_ID = "app_updates"
    private const val PROGRESS_ID = 0x5A1E
    private const val DONE_ID = 0x5A1F

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW).apply {
                description = "下载新版本安装包时显示进度"
                setShowBadge(false)
            },
        )
    }

    fun progress(context: Context, percent: Int) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载 MyPurePlayer 更新")
            .setContentText("已完成 ${percent.coerceIn(0, 100)}%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        runCatching { NotificationManagerCompat.from(context).notify(PROGRESS_ID, builder.build()) }
    }

    /** 下载完成：撤进度、发安装入口。用两个 id，避免"进度 100% 但点不动"的死通知 */
    fun completed(context: Context, version: String, apkPath: String) {
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.cancel(PROGRESS_ID) }
        ensureChannel(context)
        val intent = Intent(context, com.yaoahos.mypureplayer.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_INSTALL_APK, apkPath)
        }
        val pending = PendingIntent.getActivity(
            context,
            0x42,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MyPurePlayer $version 已下载")
            .setContentText("点击安装新版本")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        runCatching { manager.notify(DONE_ID, builder.build()) }
    }

    fun clear(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.cancel(PROGRESS_ID) }
        runCatching { manager.cancel(DONE_ID) }
    }

    const val EXTRA_INSTALL_APK = "extra_install_apk"
}
