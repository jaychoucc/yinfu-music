package com.soundtrack.music.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.soundtrack.music.R
import com.soundtrack.music.model.Song
import com.soundtrack.music.ui.PlayerActivity

/**
 * 前台服务通知构建器。
 *
 * 历史背景：旧的 PlayerService.onCreate() 只创建 MediaSession、从不调用 startForeground()，
 * 全项目又没有任何 MediaController 主动连接 session，Media3 内部的 MediaNotificationManager
 * 也不会替我们前台化。结果每次点歌后约 28 秒（解析主源 15s + 跨源回退 12s）AMS 抛
 * ForegroundServiceDidNotStartInTimeException 杀进程，CrashGuard 拦不住。
 * 修复的关键就是在 onCreate 同步起前台通知：
 *  1) startForeground() 是同步调用，延迟到协程里仍会超时；
 *  2) 必须给 startForeground() 一个真实存在的 Notification；
 *  3) 既然通知非做不可，就做成媒体控制样式（上一首 / 播放暂停 / 下一首），
 *     把锁屏、通知栏、蓝牙耳机控制都补上。
 *
 * MediaStyle 优先使用，但若编译期找不到 androidx.media.app.NotificationCompat（media3-session
 * 的传递依赖对编译期可能不可见）就退化成普通 Builder + Action。
 */
object PlaybackNotification {
    const val NOTIFICATION_ID = 1001
    const val CHANNEL_ID = "yinfu_playback"

    const val ACTION_PLAY_PAUSE = "com.soundtrack.music.action.PLAY_PAUSE"
    const val ACTION_NEXT = "com.soundtrack.music.action.NEXT"
    const val ACTION_PREV = "com.soundtrack.music.action.PREV"

    /**
     * 创建低优先级的「播放控制」渠道。
     * IMPORTANCE_LOW 不会弹横幅/声音，但仍能持续驻留（startForeground 要求持久通知）。
     * API 26 以下 NotificationChannel 不存在，但那些系统也支持普通 builder + id。
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        // 已存在就跳过，避免 setSound/setShowBadge 之类被运行时变更
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "播放控制",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "通知栏播放 / 暂停 / 切歌控制"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * 构建一条完整的播放通知。song 为 null 时显示「未在播放」占位，
     * 用来在 onCreate 第一次前台化时使用。
     *
     * 不使用 MediaStyle：media3-session 1.0.2 对 androidx.media:media 是 runtime 依赖，
     * 编译期 `androidx.media.app.NotificationCompat.MediaStyle` 不可见会直接报
     * Unresolved reference。普通 NotificationCompat + 三个 Action 视觉效果接近，
     * 功能完全一样（锁屏控制、点击按钮、点通知回 Activity 都正常），
     * 不强依赖 androidx.media 也免得为了一个样式多引一个编译期依赖。
     */
    fun build(context: Context, song: Song?, isPlaying: Boolean): Notification {
        val title = song?.title?.takeIf { it.isNotBlank() } ?: "未在播放"
        val artist = song?.artist?.takeIf { it.isNotBlank() } ?: "音符 · Soundtrack"

        val openIntent = PendingIntentFactory.openPlayerActivity(context, 0)

        val prevPi = PendingIntentFactory.service(
            context, 1,
            Intent(context, PlayerService::class.java).setAction(ACTION_PREV)
        )
        val playPausePi = PendingIntentFactory.service(
            context, 2,
            Intent(context, PlayerService::class.java).setAction(ACTION_PLAY_PAUSE)
        )
        val nextPi = PendingIntentFactory.service(
            context, 3,
            Intent(context, PlayerService::class.java).setAction(ACTION_NEXT)
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note) // 纯白 vector，符合通知 smallIcon 规范
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openIntent)
            .setOngoing(isPlaying) // 未播放时允许划掉
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW) // 与渠道 IMPORTANCE_LOW 保持一致
            .addAction(R.drawable.ic_prev, "上一首", prevPi)
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "暂停" else "播放",
                playPausePi
            )
            .addAction(R.drawable.ic_next, "下一首", nextPi)
            .build()
    }
}
