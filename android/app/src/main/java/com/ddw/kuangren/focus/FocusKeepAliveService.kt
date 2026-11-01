package com.ddw.kuangren.focus

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ddw.kuangren.Notifications

/** 番茄计时前台服务：保持倒计时存活，并在通知栏显示剩余时间。 */
class FocusKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundSafely(DEFAULT_TEXT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: DEFAULT_TEXT
        Notifications.show(
            this,
            Notifications.ID_FOCUS,
            Notifications.build(this, Notifications.CHANNEL_FOCUS, "专注中", text, ongoing = true)
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Notifications.cancel(this, Notifications.ID_FOCUS)
        super.onDestroy()
    }

    private fun startForegroundSafely(text: String) {
        val notification = Notifications.build(
            this,
            Notifications.CHANNEL_FOCUS,
            "专注中",
            text,
            ongoing = true
        )
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_FOCUS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (_: Throwable) {
            try {
                ServiceCompat.startForeground(this, Notifications.ID_FOCUS, notification, 0)
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.ddw.kuangren.action.STOP_FOCUS"
        private const val EXTRA_TEXT = "text"
        private const val DEFAULT_TEXT = "计时继续。回来看剩余时间。"

        fun start(context: Context, text: String = DEFAULT_TEXT) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FocusKeepAliveService::class.java).putExtra(EXTRA_TEXT, text)
                )
            }
        }

        fun update(context: Context, text: String) {
            runCatching {
                context.startService(
                    Intent(context, FocusKeepAliveService::class.java).putExtra(EXTRA_TEXT, text)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FocusKeepAliveService::class.java)) }
        }
    }
}
