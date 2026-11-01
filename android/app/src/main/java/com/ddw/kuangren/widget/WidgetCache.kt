package com.ddw.kuangren.widget

import android.content.Context
import com.ddw.kuangren.data.Panel
import com.ddw.kuangren.data.Quota

object WidgetCache {

    private const val PREFS = "kuangren_widget"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun write(context: Context, panel: Panel) {
        val prof = panel.profile
        val quota = panel.quota
        val tasks = panel.tasks.today
        val done = tasks.count { it.isDone }
        val total = tasks.size
        val lines = tasks.take(3).joinToString("||") { (if (it.isDone) "[x] " else "[ ] ") + it.title }
        val attrs = prof.attributes
        val examDays = panel.examDays
        val line = "距考研 " + examDays + " 天 · 今日额度 " + quota.remainingMinutes + " 分 · 任务 " + done + "/" + total
        val updated = System.currentTimeMillis()
        val p = prefs(context)
        p.edit()
            .putInt("level", prof.level)
            .putString("title", prof.title)
            .putInt("exp", prof.exp)
            .putInt("exp_next", prof.expNext)
            .putInt("exam_days", examDays)
            .putString("exam_date", panel.examDate)
            .putInt("quota_remaining", quota.remainingMinutes)
            .putInt("quota_total", quota.totalMinutes)
            .putInt("quota_used", quota.usedMinutes)
            .putBoolean("quota_locked", quota.locked)
            .putString("quota_reason", quota.lockReason ?: "")
            .putString("quota_date", quota.date)
            .putInt("tasks_done", done)
            .putInt("tasks_total", total)
            .putString("task_lines", lines)
            .putInt("attr_intelligence", attrs.intelligence)
            .putInt("attr_stamina", attrs.stamina)
            .putInt("attr_willpower", attrs.willpower)
            .putInt("attr_focus", attrs.focus)
            .putInt("attr_order", attrs.order)
            .putString("line", line)
            .putString("updated_at", com.ddw.kuangren.data.shortDateTime(com.ddw.kuangren.data.Clocks.nowIso()))
            .putLong("updated_epoch", updated)
            .putString("policy_curfew_start", quota.policy?.curfewStart ?: "01:00")
            .putString("policy_curfew_end", quota.policy?.curfewEnd ?: "07:00")
            .putString("policy_blacklist", quota.policy?.blacklist?.joinToString(",") ?: "")
            .apply()
    }

    fun writeQuota(context: Context, quota: Quota) {
        prefs(context).edit()
            .putInt("quota_remaining", quota.remainingMinutes)
            .putInt("quota_total", quota.totalMinutes)
            .putInt("quota_used", quota.usedMinutes)
            .putBoolean("quota_locked", quota.locked)
            .putString("quota_reason", quota.lockReason ?: "")
            .putString("quota_date", quota.date)
            .putLong("updated_epoch", System.currentTimeMillis())
            .apply()
    }

    fun level(context: Context): Int = prefs(context).getInt("level", 1)
    fun title(context: Context): String = prefs(context).getString("title", "初醒者") ?: "初醒者"
    fun exp(context: Context): Int = prefs(context).getInt("exp", 0)
    fun expNext(context: Context): Int = prefs(context).getInt("exp_next", 100)
    fun examDays(context: Context): Int = prefs(context).getInt("exam_days", 0)
    fun examDate(context: Context): String = prefs(context).getString("exam_date", "2026-12-19") ?: "2026-12-19"
    fun quotaRemaining(context: Context): Int = prefs(context).getInt("quota_remaining", 0)
    fun quotaTotal(context: Context): Int = prefs(context).getInt("quota_total", 0)
    fun quotaUsed(context: Context): Int = prefs(context).getInt("quota_used", 0)
    fun quotaLocked(context: Context): Boolean = prefs(context).getBoolean("quota_locked", false)
    fun quotaReason(context: Context): String = prefs(context).getString("quota_reason", "") ?: ""
    fun quotaDate(context: Context): String = prefs(context).getString("quota_date", "") ?: ""
    fun tasksDone(context: Context): Int = prefs(context).getInt("tasks_done", 0)
    fun tasksTotal(context: Context): Int = prefs(context).getInt("tasks_total", 0)
    fun taskLines(context: Context): List<String> {
        val raw = prefs(context).getString("task_lines", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("||").filter { it.isNotBlank() }
    }
    fun attrs(context: Context): List<Int> = listOf(
        prefs(context).getInt("attr_intelligence", 0),
        prefs(context).getInt("attr_stamina", 0),
        prefs(context).getInt("attr_willpower", 0),
        prefs(context).getInt("attr_focus", 0),
        prefs(context).getInt("attr_order", 0)
    )
    fun line(context: Context): String = prefs(context).getString("line", "狂人日记 · 还没有数据") ?: "狂人日记 · 还没有数据"
    fun updatedAt(context: Context): String = prefs(context).getString("updated_at", "") ?: ""
    fun curfewStart(context: Context): String = prefs(context).getString("policy_curfew_start", "01:00") ?: "01:00"
    fun curfewEnd(context: Context): String = prefs(context).getString("policy_curfew_end", "07:00") ?: "07:00"
    fun policyBlacklist(context: Context): List<String> {
        val raw = prefs(context).getString("policy_blacklist", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
    fun quotaStatusText(context: Context): String {
        if (quotaLocked(context)) return "锁定中"
        val remaining = quotaRemaining(context)
        val total = quotaTotal(context)
        if (total <= 0) return "未同步"
        return if (remaining <= 0) "已用尽" else "额度可用"
    }
}
