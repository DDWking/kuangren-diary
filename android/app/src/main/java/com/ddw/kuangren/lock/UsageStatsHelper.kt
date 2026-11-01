package com.ddw.kuangren.lock

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** 读取"当前前台应用"。需要"使用情况访问"权限。 */
object UsageStatsHelper {

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = try {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } catch (_: Throwable) {
            AppOpsManager.MODE_DEFAULT
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 最近 15 秒内最后一条前台事件对应的包名。没有权限/没有事件时返回 null。 */
    @Suppress("DEPRECATION")
    fun foregroundPackage(context: Context): String? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val end = System.currentTimeMillis()
        val begin = end - 15_000L
        val events = try {
            manager.queryEvents(begin, end)
        } catch (_: Throwable) {
            return null
        } ?: return null
        val event = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val resumed = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            if (resumed && event.packageName != null) {
                pkg = event.packageName
            }
        }
        return pkg
    }
}