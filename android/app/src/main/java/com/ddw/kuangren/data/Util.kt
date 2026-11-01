package com.ddw.kuangren.data

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object Clocks {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun today(): String = LocalDate.now(zone).toString()

    fun yesterday(): String = LocalDate.now(zone).minusDays(1).toString()

    fun dateMinus(days: Long): String = LocalDate.now(zone).minusDays(days).toString()

    fun todayPlus(days: Long): String = LocalDate.now(zone).plusDays(days).toString()

    fun dateMinusSince(date: String, days: Long): String =
        runCatching { LocalDate.parse(date).minusDays(days).toString() }.getOrDefault(today())

    fun datePlusSince(date: String, days: Long): String =
        runCatching { LocalDate.parse(date).plusDays(days).toString() }.getOrDefault(today())

    fun weekStart(): String {
        val d = LocalDate.now(zone)
        val offset = ((d.dayOfWeek.value + 6) % 7).toLong()
        return d.minusDays(offset).toString()
    }

    fun nowIso(): String = OffsetDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun newId(): String = UUID.randomUUID().toString()

    fun isValidDate(text: String): Boolean =
        runCatching { LocalDate.parse(text.trim()) }.isSuccess
}

fun formatMinutes(total: Int): String {
    if (total <= 0) return "0m"
    val h = total / 60
    val m = total % 60
    return if (h > 0) h.toString() + "h " + m.toString() + "m" else m.toString() + "m"
}

fun shortDateTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return iso.replace("T", " ").take(16)
}

fun shortDate(date: String?): String {
    if (date.isNullOrBlank()) return ""
    return if (date.length > 5) date.substring(5) else date
}

fun lockReasonText(reason: String?): String {
    val raw = reason?.trim().orEmpty()
    if (raw.isBlank()) return ""
    val key = raw.lowercase().replace('-', '_').replace(' ', '_')
    return when {
        key == "curfew" || key.contains("curfew") -> "现在处于宵禁时段"
        key.contains("exhaust") || key.contains("used_up") || key.contains("empty") ||
            key.contains("quota_used") || key.contains("no_quota") -> "今天的娱乐额度已经用完"
        key.contains("lock") -> "额度已被锁定"
        key.contains("forced") || key.contains("tighten") -> "连续无学习记录，额度被强制收紧"
        key.contains("violation") -> "违规次数过多，额度被锁定"
        else -> raw
    }
}

fun maskToken(token: String): String {
    if (token.isBlank()) return "未设置"
    if (token.length <= 8) return "****"
    return token.take(4) + "****" + token.takeLast(4)
}