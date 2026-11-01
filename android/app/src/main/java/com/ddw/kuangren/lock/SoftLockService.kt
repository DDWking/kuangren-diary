package com.ddw.kuangren.lock

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ddw.kuangren.KuangrenApp
import com.ddw.kuangren.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 软锁守护前台服务：每 5 秒查一次当前前台应用；命中娱乐黑名单且（额度为 0 / 宵禁）时弹出全屏覆盖层。
 * 关闭开关后自动停止；无使用情况/悬浮窗权限时只在通知栏提示。
 */
class SoftLockService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var overlayPkg: String? = null
    private var overlayShownAt = 0L
    private var dismissedPkg: String? = null
    private var dismissedAt = 0L
    private var permissionNotified = false

    private val ticker = object : Runnable {
        override fun run() {
            try {
                evaluate()
            } catch (_: Throwable) {
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundSafely("正在守护娱乐额度")
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        refreshNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        OverlayController.hide()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely(text: String) {
        val notification = Notifications.build(
            this,
            Notifications.CHANNEL_LOCK,
            "软锁守护",
            text,
            ongoing = true
        )
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_SOFTLOCK,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (_: Throwable) {
            try {
                ServiceCompat.startForeground(this, Notifications.ID_SOFTLOCK, notification, 0)
            } catch (_: Throwable) {
            }
        }
    }

    private fun refreshNotification() {
        if (!LockPrefs.enabled(this)) return
        val blacklist = LockPrefs.blacklist(this)
        val violations = LockPrefs.violations(this)
        val cooling = LockPrefs.cooldownUntil(this) > System.currentTimeMillis()
        val text = "监控 " + blacklist.size + " 个应用 · 今日违规 " + violations + " 次" +
            if (cooling) " · 冷却中" else ""
        Notifications.show(
            this,
            Notifications.ID_SOFTLOCK,
            Notifications.build(
                this,
                Notifications.CHANNEL_LOCK,
                "软锁守护",
                text,
                ongoing = true
            )
        )
    }

    private fun evaluate() {
        if (!LockPrefs.enabled(this)) {
            OverlayController.hide()
            stopSelf()
            return
        }
        val pkg = UsageStatsHelper.foregroundPackage(this)
        if (pkg == null) return
        if (pkg == packageName) return

        if (overlayPkg != null && overlayPkg != pkg) {
            dismissedPkg = overlayPkg
            dismissedAt = System.currentTimeMillis()
            overlayPkg = null
            OverlayController.hide()
        }

        val decision = LockPrefs.evaluate(this, pkg)
        if (!decision.trigger) {
            if (overlayPkg != null && overlayPkg != pkg) {
                dismissedPkg = overlayPkg
                dismissedAt = System.currentTimeMillis()
                overlayPkg = null
                OverlayController.hide()
            }
            return
        }

        if (!UsageStatsHelper.hasUsageAccess(this)) return
        if (!OverlayController.canDraw(this)) {
            notifyMissingPermission()
            return
        }

        val now = System.currentTimeMillis()
        if (overlayPkg != pkg) {
            if (dismissedPkg == pkg && now - dismissedAt <= VIOLATION_WINDOW_MS) {
                registerViolation(pkg, decision)
            }
            overlayPkg = pkg
            overlayShownAt = now
            dismissedPkg = null
            dismissedAt = 0L
            showOverlay(pkg, decision)
        } else {
            updateOverlay(pkg, decision)
        }
        if (now - overlayShownAt > 60_000L) {
            refreshNotification()
            overlayShownAt = now
        }
    }

    private fun showOverlay(pkg: String, decision: LockDecision) {
        OverlayController.show(
            this,
            LockPrefs.labelFor(pkg),
            decision.reason,
            statusText(decision),
            LockPrefs.curfewText(this)
        )
    }

    private fun updateOverlay(pkg: String, decision: LockDecision) {
        OverlayController.update(this, decision.reason, statusText(decision))
    }

    private fun statusText(decision: LockDecision): String {
        val coolingLeft = LockPrefs.cooldownUntil(this) - System.currentTimeMillis()
        return when {
            coolingLeft > 0 -> "冷却中 · 还剩 " + formatCooldown(coolingLeft)
            decision.cooling -> "冷却中"
            decision.remainingMinutes > 0 -> "剩余 " + decision.remainingMinutes + " 分钟额度"
            else -> "今日额度 0 分钟"
        }
    }

    private fun registerViolation(pkg: String, decision: LockDecision) {
        val count = LockPrefs.addViolation(this)
        val now = System.currentTimeMillis()
        if (count >= 3 && LockPrefs.cooldownUntil(this) <= now) {
            LockPrefs.setCooldown(this, now + COOLDOWN_MS)
            if (!LockPrefs.cooldownNotified(this)) {
                LockPrefs.markCooldownNotified(this)
                Notifications.show(
                    this,
                    Notifications.ID_ALERT,
                    Notifications.build(
                        this,
                        Notifications.CHANNEL_ALERTS,
                        "今天第 3 次违规",
                        "冷却 30 分钟。呆记下了。"
                    )
                )
            }
            reportEvent(count, decision)
        }
        if (count == 3) {
            Notifications.show(
                this,
                Notifications.ID_ALERT,
                Notifications.build(
                    this,
                    Notifications.CHANNEL_ALERTS,
                    "软锁违规",
                    "今天已经第 " + count + " 次被软锁拦住。冷静一下。"
                )
            )
        }
        refreshNotification()
    }

    private fun reportEvent(violations: Int, decision: LockDecision) {
        val app = applicationContext as? KuangrenApp ?: return
        scope.launch {
            runCatching {
                app.container.repository.postEvent(
                    "soft_lock_violation",
                    "warning",
                    buildJsonObject {
                        put("violations", violations)
                        put("reason", decision.reason)
                        put("remaining_minutes", decision.remainingMinutes)
                    }
                )
            }
        }
    }

    private fun notifyMissingPermission() {
        if (permissionNotified) return
        permissionNotified = true
        Notifications.show(
            this,
            Notifications.ID_ALERT,
            Notifications.build(
                this,
                Notifications.CHANNEL_ALERTS,
                "软锁缺少权限",
                "请到 设置 → 软锁 打开「使用情况访问」和「悬浮窗」权限。"
            )
        )
    }

    private fun formatCooldown(millis: Long): String {
        val total = (millis / 1000L).coerceAtLeast(0L)
        val minutes = total / 60L
        val seconds = total % 60L
        return String.format("%02d:%02d", minutes, seconds)
    }

    companion object {
        const val ACTION_STOP = "com.ddw.kuangren.action.STOP_SOFTLOCK"
        private const val CHECK_INTERVAL_MS = 5_000L
        private const val VIOLATION_WINDOW_MS = 5 * 60_000L
        private const val COOLDOWN_MS = 30 * 60_000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SoftLockService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SoftLockService::class.java)) }
        }
    }
}
