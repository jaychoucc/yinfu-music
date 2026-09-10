package com.soundtrack.music.player

import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.soundtrack.music.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 播放服务。
 *
 * 这个类的"头号任务"是 onCreate 同步调用 startForeground()，然后才做别的。
 *
 * 历史背景（上一轮没修干净导致必现崩溃）：旧实现只 `startForegroundService` 不 `startForeground`，
 * 而全项目又没有任何 MediaController 主动连接 session，Media3 内部的 MediaNotificationManager
 * 不会替我们前台化。系统 5~10s 倒计时到点后抛 ForegroundServiceDidNotStartInTimeException 杀进程，
 * 表现为「播到 28 秒自动退出」（实为解析主源 15s + 跨源回退 12s ≈ 27s 这段时间服务一直挂着）。
 * CrashGuard 拦不住 RemoteServiceException。
 *
 * 媒体控制按钮（上一首/播放暂停/下一首）通过 PendingIntent.getService 回到 onStartCommand，
 * 分发到 PlayerRepository，避免引入 BroadcastReceiver 增加复杂度。
 */
class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * 收集播放器状态变化以更新通知。
     * Service 没有 lifecycleScope，用自建 SupervisorJob scope，onDestroy 取消。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        // 1) 第一件事：建渠道 + 同步起前台通知。
        //    startForeground 必须在 onCreate 同步调用，延后到协程/Handler.post 就还是超时。
        PlaybackNotification.ensureChannel(this)
        runCatching {
            startForeground(
                PlaybackNotification.NOTIFICATION_ID,
                PlaybackNotification.build(this, song = null, isPlaying = false)
            )
        }

        // 2) 建立 MediaSession（保留 MediaSessionService 的价值：系统/蓝牙/锁屏能连接控制）
        val player = PlayerRepository.get(this).player
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

        // 3) 订阅状态变化，更新通知栏
        startObserver()
    }

    private fun startObserver() {
        observerJob?.cancel()
        val repo = PlayerRepository.get(this)
        observerJob = scope.launch {
            combine(repo.currentSong, repo.isPlaying) { song, playing -> song to playing }
                .collectLatest { (song, playing) ->
                    val nm = NotificationManagerCompat.from(this@PlayerService)
                    // 没权限时（API 33+ 未授 POST_NOTIFICATIONS）notify 是 no-op，不崩
                    runCatching { nm.notify(
                        PlaybackNotification.NOTIFICATION_ID,
                        PlaybackNotification.build(this@PlayerService, song, playing)
                    ) }
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 处理通知栏控制按钮的回传。
     * MediaSessionService.onStartCommand 不是 final，可以 override；处理完自己的 action 后
     * 对其他 intent 仍要 `return super.onStartCommand(...)` —— Media3 在没 controller 时
     * 不会主动前台化，但必须让它走自己的 session 注册流程，否则将来系统/蓝牙连接时无法正常工作。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PlaybackNotification.ACTION_PLAY_PAUSE -> {
                PlayerRepository.get(this).toggle()
                return START_STICKY
            }
            PlaybackNotification.ACTION_NEXT -> {
                PlayerRepository.get(this).nextManual()
                return START_STICKY
            }
            PlaybackNotification.ACTION_PREV -> {
                PlayerRepository.get(this).prevManual()
                return START_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // ⚠️ 不能在这里 release player：ExoPlayer 实例由 PlayerRepository（进程级单例）持有并复用，
        // 服务销毁后若在这里释放，Repository 里就剩一个已释放的播放器，
        // 任何后续 play/pause/seek 都会抛 IllegalStateException 造成播放闪退（这是上一轮专门修过的坑）。
        // 这里只取消订阅、停前台、释放 MediaSession。
        observerJob?.cancel()
        scope.cancel()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { mediaSession?.release() }
        mediaSession = null
        super.onDestroy()
    }
}
