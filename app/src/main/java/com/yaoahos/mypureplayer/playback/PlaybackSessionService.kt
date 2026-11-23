package com.yaoahos.mypureplayer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yaoahos.mypureplayer.MainActivity
import com.yaoahos.mypureplayer.R

/**
 * 媒体会话与播放通知的宿主。
 *
 * 早先是两套手写实现：`AudioPlaybackService` 发一条后台音频通知保活进程，
 * `MediaSessionBridge` 再建一个平台 MediaSession 并手拼 MediaStyle 通知。
 * 两者各自维护状态，暂停/切歌时容易边撤边建，系统媒体卡片也只能拿到一部分信息。
 *
 * 现在统一交给 Media3 的 MediaSessionService：
 *  - 播放/暂停/上一个/下一个/进度由会话直接驱动播放器，状态天然一致；
 *  - 通知、锁屏、蓝牙耳机、Android 13+ 媒体卡片、输出设备切换由库维护；
 *  - 前台服务与通知的生命周期绑定播放状态，停止播放后自动收起，不再需要手动 dismiss。
 *
 * 播放器仍由 [Playback] 单例持有（跨页面复用、投屏与字幕都挂在上面），
 * 这里只借用它建会话，退出时也只释放会话、不释放播放器。
 */
class PlaybackSessionService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = Playback.playerForSession()
        if (player == null) {
            // 应用还没完成初始化（极少数被系统直接拉起的场景），直接收摊，等下次播放再起
            stopSelf()
            return
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.notif_channel_playback)
                .setNotificationId(NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_audio) },
        )
        // 没有媒体在播就不显示通知：停止播放、退出播放页后系统媒体栏自动消失，
        // 这正是旧实现里 dismissNotification() 手动做的事。
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)

        val created = MediaSession.Builder(this, player)
            .setSessionActivity(openPlayerIntent())
            .setCallback(SessionCallback())
            .setMediaButtonPreferences(mediaButtons())
            .build()
        session = created
        addSession(created)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉应用：还在播放就继续（后台音频、连续播放都靠这个），停着就收摊
        val player = session?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 只释放会话：播放器归 Playback 单例所有，跨页面复用，不能在这里 release
        session?.release()
        session = null
        super.onDestroy()
    }

    /**
     * 通知卡片上的按钮。
     *
     * 只加一颗自定义的「退出播放」，上/下一个与播放暂停交给库的默认按钮：
     * 系统媒体卡片只有 back / central / forward 三个槽位，central 永远给播放暂停，
     * 于是第三个位置只能在「下一个」和「退出播放」里二选一。播放页内已有下一集按钮、
     * 队列本身也会自动续播，而「退出播放」（停播并收起通知）是本应用独有的能力，
     * 所以留给它。按钮不指定槽位，落进 overflow 由通知自己补进第三个位置。
     */
    private fun mediaButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName("退出播放")
            .setCustomIconResId(R.drawable.ic_notif_close)
            .setSessionCommand(SessionCommand(ACTION_EXIT, Bundle.EMPTY))
            .build(),
    )

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_EXIT, Bundle.EMPTY))
                .build()
            // 播放器命令必须显式给出：无参 AcceptedResultBuilder() 的默认值是
            // SessionCommands.EMPTY + Player.Commands.EMPTY，一旦漏掉，通知栏用来
            // 读取播放状态的内部控制器就看不到任何东西 —— 表现为「在播但没有通知」。
            return MediaSession.ConnectionResult.AcceptedResultBuilder()
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(session.player.availableCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
            ACTION_EXIT -> {
                Playback.stopAndDismiss()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }

    private fun openPlayerIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        9,
        Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL_ID = "mypureplayer_playback"
        private const val NOTIFICATION_ID = 2027
        private const val ACTION_EXIT = "com.yaoahos.mypureplayer.session.EXIT"

        /**
         * 启动会话服务。
         *
         * 用普通 startService 而不是 startForegroundService：会话在真正开始播放时才把
         * 自己提到前台，没播放就调用 startForegroundService 会触发
         * 「did not then call Service.startForeground」的 ANR。调用点都在前台（进播放页、
         * 开后台音频），系统允许此时启动后台服务。
         */
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, PlaybackSessionService::class.java))
            }
        }
    }
}
