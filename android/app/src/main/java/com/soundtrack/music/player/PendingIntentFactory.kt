package com.soundtrack.music.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.soundtrack.music.ui.PlayerActivity

/**
 * PendingIntent 集中工厂：所有跳转 PlayerService / PlayerActivity 的 PendingIntent 都走这里，
 * 避免每个调用点散落重复的 flag 拼装（FLAG_IMMUTABLE 是 API 31+ 强制的，漏写会崩）。
 */
object PendingIntentFactory {

    private fun immutableFlags(): Int {
        // FLAG_IMMUTABLE 自 API 23 就有；targetSdk 33 要求显式声明
        var flags = PendingIntent.FLAG_IMMUTABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // FLAG_UPDATE_CURRENT：同一 requestCode 复用时把 extras 更新上去
            flags = flags or PendingIntent.FLAG_UPDATE_CURRENT
        }
        return flags
    }

    /** 通知点按 → 打开 PlayerActivity（singleTop 已配，多次点不会堆叠） */
    fun openPlayerActivity(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            // 标记从通知进入，避免在已存在实例时被当作冷启动
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(context, requestCode, intent, immutableFlags())
    }

    /** 通知按钮 → 触发 PlayerService.onStartCommand 分发到 PlayerRepository */
    fun service(context: Context, requestCode: Int, intent: Intent): PendingIntent {
        return PendingIntent.getService(context, requestCode, intent, immutableFlags())
    }
}
