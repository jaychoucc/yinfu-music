package com.soundtrack.music.import_

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.soundtrack.music.R
import com.soundtrack.music.ui.MyPlaylistsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 歌单导入前台服务。
 *
 * **为什么需要服务**：导入最早跑在 [MyPlaylistsActivity] 的 `lifecycleScope` 里，
 * 页面被系统回收 / 用户切走 / 旋转屏幕都会取消协程，已匹配的歌曲条目就停在某个中间数
 * （真机实测 657 首歌单卡在 244），既不继续也不写 misses。放进前台服务后：
 *  - 导入协程挂在服务自己的 [scope] 上，与 Activity 生命周期完全解耦；
 *  - 常驻通知既保活又向用户展示进度，通知里「恢复」按钮可回到页面看进度弹窗；
 *  - 进度通过 [ImportEngine] 广播，页面重建后仍能接着显示。
 *
 * 用 `dataSync` 类型：导入是「把远端歌单同步到本地」的网络任务，Android 14+
 * 要求前台服务声明类型，否则 [Context.startForegroundService] 会被系统拒绝。
 *
 * 全程不绑定 Activity（[onBind] 返回 null），进度靠 [ImportEngine] 流转。
 */
class PlaylistImportService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importJob: Job? = null
    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 必须在 onCreate 同步起前台通知：startForegroundService 之后 5 秒内不 startForeground
        // 会被 AMS 抛 ForegroundServiceDidNotStartInTimeException 杀进程（PlayerService 踩过的坑）
        startForeground(NOTIFICATION_ID, buildNotification(null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                importJob?.cancel()
                ImportEngine.cancel()
                stopForegroundAndNotifyDone()
            }
            ACTION_START, null -> {
                val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isBlank()) {
                    stopForegroundAndNotifyDone()
                    return START_NOT_STICKY
                }
                // 已有导入在跑 → 忽略重复启动（页面重建/通知重复点击都会走到这里）
                if (running.compareAndSet(false, true)) {
                    startImport(url)
                }
            }
        }
        // 导入是「尽力而为」的任务：进程被杀后不自动重启（重启了也没有页面承接结果）
        return START_NOT_STICKY
    }

    /** 真正执行导入；协程挂在服务级 scope 上，Activity 销毁不影响。 */
    private fun startImport(urlOrId: String) {
        ImportEngine.start()
        importJob = scope.launch {
            // matched/skipped/missed 由 importer 回调累计，这里只负责汇总广播
            var matched = 0
            var skipped = 0
            var missed = 0
            var total = 0
            // 歌单名在解析阶段由 onPlaylistCreated 回调写入，之后每轮 onProgress 都带上
            var playlistName = ""
            val outcome = runCatching {
                PlaylistImporter.importNeteasePlaylist(
                    context = this@PlaylistImportService,
                    urlOrId = urlOrId,
                    onProgress = { m, s, miss, t, title ->
                        matched = m; skipped = s; missed = miss; total = t
                        ImportEngine.update(
                            total = t, matched = m, skipped = s, missed = miss,
                            title = title, playlistName = playlistName
                        )
                        updateNotification()
                    },
                    onPlaylistCreated = { _, name -> playlistName = name }
                )
            }
            val result = outcome.getOrNull()
            ImportEngine.finish(result)
            stopForegroundAndNotifyDone()
            running.set(false)
            // 没有页面承接结果时（用户已切走），停掉服务；结果留在 ImportEngine 等页面回来消费
            stopSelfIfConsumed()
        }
    }

    /** 通知栏「完成」态：非 ongoing、可划掉，点按仍回页面。 */
    private fun stopForegroundAndNotifyDone() {
        val st = ImportEngine.state.value
        val mgr = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        mgr?.notify(NOTIFICATION_ID, buildDoneNotification(st))
    }

    /** 若导入结果已被页面消费（ImportEngine 被清空），服务就没必要再驻留。 */
    private fun stopSelfIfConsumed() {
        if (ImportEngine.state.value == null) stopSelf()
    }

    override fun onDestroy() {
        importJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // —— 通知 —— //

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, "歌单导入", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "网易云歌单导入进度 / 完成 / 取消"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        mgr.createNotificationChannel(ch)
    }

    private fun openIntent(): PendingIntent {
        val intent = Intent(this, MyPlaylistsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun cancelIntent(): PendingIntent {
        val intent = Intent(this, PlaylistImportService::class.java).apply {
            action = ACTION_CANCEL
        }
        return PendingIntent.getService(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 导入中的通知：标题 + 明细行 + 确定性进度条 + 「取消」操作。 */
    private fun buildNotification(st: ImportEngine.State?): Notification {
        val total = st?.total ?: 0
        val done = st?.done ?: 0
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_import)
            .setContentTitle("正在导入歌单${st?.playlistName?.takeIf { it.isNotBlank() }?.let { "· $it" }.orEmpty()}")
            .setContentText(notifyText(st))
            .setContentIntent(openIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_close, "取消", cancelIntent())
        if (total > 0) {
            builder.setProgress(total, done, false)
        } else {
            // 解析阶段还不知道总数，用不确定进度条
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    /** 完成态通知：可划掉、不常驻。 */
    private fun buildDoneNotification(st: ImportEngine.State?): Notification {
        val (title, text) = when {
            st == null -> "导入已取消" to ""
            st.cancelled -> "导入已取消" to "已匹配的歌曲会保留在歌单里"
            st.failed -> "导入失败" to "歌单解析失败，可能是链接错误或需要登录"
            st.result != null -> {
                val r = st.result
                "导入完成·${r.playlistName}" to
                    "成功 ${r.matched}/${r.total} 首" +
                    (if (r.total - r.matched - r.misses.size > 0)
                        "（${r.total - r.matched - r.misses.size} 首已存在）" else "") +
                    (if (r.misses.isNotEmpty()) " · 失败 ${r.misses.size} 首" else "")
            }
            else -> "导入已取消" to ""
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_import)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent())
            .setAutoCancel(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyText(st: ImportEngine.State?): String {
        if (st == null) return "正在解析歌单…"
        val parts = ArrayList<String>(3)
        if (st.matched > 0) parts += "已匹配 ${st.matched}"
        if (st.skipped > 0) parts += "跳过 ${st.skipped}"
        if (st.missed > 0) parts += "未匹配 ${st.missed}"
        val head = if (parts.isEmpty()) "正在解析歌单…"
        else if (st.total > 0) parts.joinToString(" · ") + " / ${st.total}"
        else parts.joinToString(" · ")
        return if (st.title.isNotBlank()) "$head · 《${st.title}》" else head
    }

    private fun updateNotification() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        mgr.notify(NOTIFICATION_ID, buildNotification(ImportEngine.state.value))
    }

    companion object {
        const val CHANNEL_ID = "yinfu_import"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START = "com.soundtrack.music.action.IMPORT_START"
        const val ACTION_CANCEL = "com.soundtrack.music.action.IMPORT_CANCEL"
        const val EXTRA_URL = "import_url"

        /**
         * 页面统一用这个入口启动导入服务（API 26+ 必须走 startForegroundService）。
         * 已在跑时直接返回，调用方再自行恢复进度弹窗。
         */
        fun start(context: Context, urlOrId: String) {
            val intent = Intent(context, PlaylistImportService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, urlOrId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 通知「取消」/ 对话框「取消」统一走这里，保证状态与通知一致。 */
        fun cancel(context: Context) {
            val intent = Intent(context, PlaylistImportService::class.java).apply {
                action = ACTION_CANCEL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
