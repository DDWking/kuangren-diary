package com.ddw.kuangren

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifications {
    const val CHANNEL_FOCUS = "focus_timer"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_LOCK = "soft_lock"

    const val ID_FOCUS = 1001
    const val ID_SOFTLOCK = 1002
    const val ID_ALERT = 1003

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val focus = NotificationChannel(CHANNEL_FOCUS, "专注计时", NotificationManager.IMPORTANCE_LOW).apply {
            description = "番茄计时状态"
            setShowBadge(false)
        }
        val alerts = NotificationChannel(CHANNEL_ALERTS, "系统提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "任务、额度与系统消息"
        }
        val lock = NotificationChannel(CHANNEL_LOCK, "软锁守护", NotificationManager.IMPORTANCE_LOW).apply {
            description = "娱乐额度监控前台服务"
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(focus, alerts, lock))
    }

    fun build(
        context: Context,
        channel: String,
        title: String,
        text: String,
        ongoing: Boolean = false
    ): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(context, 0, intent, flags)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun show(context: Context, id: Int, notification: Notification) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(id)
    }
}
