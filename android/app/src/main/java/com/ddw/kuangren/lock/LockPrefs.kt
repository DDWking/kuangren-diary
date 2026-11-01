package com.ddw.kuangren.lock

import android.content.Context
import com.ddw.kuangren.data.Clocks
import com.ddw.kuangren.widget.WidgetCache
import java.time.LocalTime

data class LockDecision(
    val trigger: Boolean,
    val cooling: Boolean,
    val reason: String,
    val remainingMinutes: Int
)

/** 软锁的本地状态：黑名单、开关、违规计数、冷却时间。 */
object LockPrefs {
    private const val PREFS = "kuangren_lock"
    private const val KEY_BLACKLIST = "blacklist"
    private const val KEY_VIOLATIONS = "violations_"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until"
    private const val KEY_COOLDOWN_NOTIFIED = "cooldown_notified_"

    val DEFAULT_BLACKLIST = listOf(
        "com.tencent.tmgp.sgame",
        "com.tencent.tmgp.pubgmhd",
        "com.miHoYo.Yuanshen",
        "com.bilibili.app.in",
        "com.ss.android.ugc.aweme",
        "com.smile.gifmaker",
        "com.tencent.qqlive",
        "com.qiyi.video"
    )

    private val labels = mapOf(
        "com.tencent.tmgp.sgame" to "王者荣耀",
        "com.tencent.tmgp.pubgmhd" to "和平精英",
        "com.miHoYo.Yuanshen" to "原神",
        "com.bilibili.app.in" to "哔哩哔哩",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.smile.gifmaker" to "快手",
        "com.tencent.qqlive" to "腾讯视频",
        "com.qiyi.video" to "爱奇艺"
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun labelFor(pkg: String): String = labels[pkg] ?: pkg

    fun blacklist(context: Context): List<String> {
        val local = prefs(context).getString(KEY_BLACKLIST, null)
        if (local != null) {
            return local.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        val policy = WidgetCache.policyBlacklist(context)
        return if (policy.isNotEmpty()) policy else DEFAULT_BLACKLIST
    }

    fun setBlacklist(context: Context, packages: List<String>) {
        prefs(context).edit().putString(KEY_BLACKLIST, packages.joinToString(",")).apply()
    }

    fun enabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences("kuangren_settings", Context.MODE_PRIVATE)
        .getBoolean("soft_lock_enabled", false)

    fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences("kuangren_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("soft_lock_enabled", value).apply()
    }

    fun violations(context: Context): Int =
        prefs(context).getInt(KEY_VIOLATIONS + Clocks.today(), 0)

    fun addViolation(context: Context): Int {
        val key = KEY_VIOLATIONS + Clocks.today()
        val next = prefs(context).getInt(key, 0) + 1
        prefs(context).edit().putInt(key, next).apply()
        return next
    }

    fun cooldownUntil(context: Context): Long = prefs(context).getLong(KEY_COOLDOWN_UNTIL, 0L)

    fun setCooldown(context: Context, until: Long) {
        prefs(context).edit().putLong(KEY_COOLDOWN_UNTIL, until).apply()
    }

    fun cooldownNotified(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COOLDOWN_NOTIFIED + Clocks.today(), false)

    fun markCooldownNotified(context: Context) {
        prefs(context).edit().putBoolean(KEY_COOLDOWN_NOTIFIED + Clocks.today(), true).apply()
    }

    fun inCurfew(
        start: String,
        end: String,
        now: LocalTime = LocalTime.now(Clocks.zone)
    ): Boolean {
        val s = parseClock(start) ?: return false
        val e = parseClock(end) ?: return false
        if (s == e) return false
        return if (s < e) !now.isBefore(s) && now.isBefore(e) else !now.isBefore(s) || now.isBefore(e)
    }

    fun curfewText(context: Context): String =
        WidgetCache.curfewStart(context) + "–" + WidgetCache.curfewEnd(context)

    fun isCurfewNow(context: Context): Boolean =
        inCurfew(WidgetCache.curfewStart(context), WidgetCache.curfewEnd(context))

    private fun parseClock(text: String): LocalTime? =
        runCatching { LocalTime.parse(text.trim()) }.getOrNull()

    /** 判断当前前台应用是否该被软锁拦住。 */
    fun evaluate(context: Context, packageName: String): LockDecision {
        val remaining = WidgetCache.quotaRemaining(context)
        val total = WidgetCache.quotaTotal(context)
        val lockedFlag = WidgetCache.quotaLocked(context)
        val curfew = isCurfewNow(context)
        val exhausted = lockedFlag || (total > 0 && remaining <= 0)
        val cooling = cooldownUntil(context) > System.currentTimeMillis()

        if (!enabled(context) || packageName !in blacklist(context)) {
            return LockDecision(false, cooling, "", remaining)
        }
        if (!exhausted && !curfew) {
            return LockDecision(false, cooling, "", remaining)
        }
        val reason = when {
            curfew && exhausted -> "宵禁中，额度也用完了。"
            curfew -> "现在是宵禁时间（" + curfewText(context) + "）。"
            lockedFlag -> "额度已被锁定。"
            else -> "今天的娱乐额度已经用完。"
        }
        return LockDecision(true, cooling, reason, remaining)
    }
}
