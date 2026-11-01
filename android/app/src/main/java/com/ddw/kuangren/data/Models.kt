package com.ddw.kuangren.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------------- 基础 ----------------

@Serializable
data class ApiError(val code: String = "internal_error", val message: String = "未知错误")

@Serializable
data class HealthInfo(
    val status: String = "unknown",
    val version: String = "",
    val db: String = "",
    @SerialName("server_time") val serverTime: String? = null
)

@Serializable
data class DeletedResult(val deleted: Boolean = false)

@Serializable
data class IdResult(val id: String = "")

// ---------------- Profile ----------------

@Serializable
data class Attributes(
    val intelligence: Int = 0,
    val stamina: Int = 0,
    val willpower: Int = 0,
    val focus: Int = 0,
    val order: Int = 0
) {
    fun values(): List<Int> = listOf(intelligence, stamina, willpower, focus, order)
    fun byKey(key: String): Int = when (key) {
        "intelligence" -> intelligence
        "stamina" -> stamina
        "willpower" -> willpower
        "focus" -> focus
        "order" -> order
        else -> 0
    }
}

@Serializable
data class Profile(
    val id: String = "default",
    val name: String = "宿主",
    val title: String = "初醒者",
    val level: Int = 1,
    val exp: Int = 0,
    @SerialName("exp_next") val expNext: Int = 100,
    @SerialName("exp_progress") val expProgress: Double = 0.0,
    val coins: Int = 0,
    val streak: Int = 0,
    val attributes: Attributes = Attributes(),
    @SerialName("exam_date") val examDate: String = "2026-12-19",
    @SerialName("exam_days") val examDays: Int = 0,
    @SerialName("daily_study_target_minutes") val dailyStudyTargetMinutes: Int = 300,
    @SerialName("total_study_minutes") val totalStudyMinutes: Int = 0,
    @SerialName("total_tasks_done") val totalTasksDone: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    val expFraction: Float get() = if (expNext <= 0) 0f else (exp.toFloat() / expNext.toFloat()).coerceIn(0f, 1f)
}

@Serializable
data class PatchProfileRequest(
    val name: String? = null,
    val title: String? = null,
    @SerialName("exam_date") val examDate: String? = null,
    @SerialName("daily_study_target_minutes") val dailyStudyTargetMinutes: Int? = null
)

// ---------------- Task ----------------

@Serializable
data class Task(
    val id: String = "",
    val title: String = "",
    val description: String? = null,
    val category: String = "other",
    val difficulty: Int = 1,
    val attribute: String = "intelligence",
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("estimated_minutes") val estimatedMinutes: Int? = null,
    val status: String = "pending",
    @SerialName("repeat_rule") val repeatRule: String = "none",
    @SerialName("created_by") val createdBy: String = "user",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("completion_note") val completionNote: String? = null,
    @SerialName("actual_minutes") val actualMinutes: Int? = null,
    @SerialName("exp_reward") val expReward: Int = 0,
    @SerialName("coin_reward") val coinReward: Int = 0,
    @SerialName("deleted_at") val deletedAt: String? = null
) {
    val isDone: Boolean get() = status == "done"
    val isPending: Boolean get() = status == "pending"
    val categoryLabel: String
        get() = when (category) {
            "study" -> "学习"
            "body" -> "身体"
            "life" -> "生活"
            "mind" -> "心境"
            else -> "其他"
        }
    val attributeLabel: String
        get() = when (attribute) {
            "intelligence" -> "智力"
            "stamina" -> "体力"
            "willpower" -> "意志"
            "focus" -> "专注"
            "order" -> "秩序"
            else -> attribute
        }
    val metaLine: String
        get() = "难度 " + difficulty + " · " + (estimatedMinutes?.let { it.toString() + "min" } ?: "未设时长") + " · " + attributeLabel
}

@Serializable
data class CreateTaskRequest(
    val title: String,
    val description: String? = null,
    val category: String = "study",
    val difficulty: Int = 3,
    val attribute: String = "intelligence",
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("estimated_minutes") val estimatedMinutes: Int? = null,
    @SerialName("repeat_rule") val repeatRule: String = "none",
    @SerialName("created_by") val createdBy: String = "user"
)

@Serializable
data class PatchTaskRequest(
    val title: String? = null,
    val description: String? = null,
    val category: String? = null,
    val difficulty: Int? = null,
    val attribute: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("estimated_minutes") val estimatedMinutes: Int? = null,
    val status: String? = null
)

@Serializable
data class CompleteRequest(
    val note: String? = null,
    @SerialName("actual_minutes") val actualMinutes: Int? = null,
    @SerialName("client_completed_at") val clientCompletedAt: String? = null
)

@Serializable
data class PostponeRequest(@SerialName("to_date") val toDate: String)

@Serializable
data class Rewards(
    val exp: Int = 0,
    val coins: Int = 0,
    val attribute: String = "",
    @SerialName("attribute_delta") val attributeDelta: Int = 0,
    @SerialName("streak_multiplier") val streakMultiplier: Double = 1.0
)

@Serializable
data class CompleteResult(
    val task: Task = Task(),
    val rewards: Rewards = Rewards(),
    val profile: Profile? = null,
    val quota: Quota? = null,
    @SerialName("achievements_unlocked") val achievementsUnlocked: List<Achievement> = emptyList()
)

// ---------------- Diary ----------------

@Serializable
data class DiaryEntry(
    val date: String = "",
    val mood: Int = 3,
    val content: String = "",
    @SerialName("ai_comment") val aiComment: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class DiaryPutRequest(
    val mood: Int = 3,
    val content: String = "",
    val tags: List<String> = emptyList()
)

// ---------------- Weight ----------------

@Serializable
data class WeightEntry(
    val date: String = "",
    @SerialName("weight_kg") val weightKg: Double = 0.0,
    @SerialName("body_fat") val bodyFat: Double? = null,
    @SerialName("waist_cm") val waistCm: Double? = null,
    val note: String? = null,
    val source: String = "manual",
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class WeightPutRequest(
    @SerialName("weight_kg") val weightKg: Double,
    @SerialName("body_fat") val bodyFat: Double? = null,
    @SerialName("waist_cm") val waistCm: Double? = null,
    val note: String? = null,
    val source: String = "manual"
)

@Serializable
data class WeightResponse(
    val entries: List<WeightEntry> = emptyList(),
    val latest: WeightEntry? = null,
    @SerialName("change_7d") val change7d: Double? = null
)

// ---------------- Policy / Quota ----------------

@Serializable
data class Policy(
    val date: String = "",
    @SerialName("daily_base_minutes") val dailyBaseMinutes: Int = 180,
    @SerialName("curfew_start") val curfewStart: String = "01:00",
    @SerialName("curfew_end") val curfewEnd: String = "07:00",
    @SerialName("session_max_minutes") val sessionMaxMinutes: Int = 60,
    val blacklist: List<String> = emptyList(),
    val notes: String? = null,
    val source: String = "fallback",
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class QuotaExtension(
    val id: String = "",
    val minutes: Int = 0,
    val reason: String = "",
    val actor: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    val revoked: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class Quota(
    val date: String = "",
    @SerialName("base_minutes") val baseMinutes: Int = 0,
    @SerialName("bonus_minutes") val bonusMinutes: Int = 0,
    @SerialName("used_minutes") val usedMinutes: Int = 0,
    @SerialName("remaining_minutes") val remainingMinutes: Int = 0,
    val locked: Boolean = false,
    @SerialName("lock_reason") val lockReason: String? = null,
    @SerialName("lock_until") val lockUntil: String? = null,
    val policy: Policy? = null,
    val extensions: List<QuotaExtension> = emptyList(),
    @SerialName("violations_today") val violationsToday: Int = 0,
    @SerialName("forced_tighten") val forcedTighten: Boolean = false,
    @SerialName("tighten_reason") val tightenReason: String? = null
) {
    val totalMinutes: Int get() = baseMinutes + bonusMinutes
    val usedFraction: Float
        get() {
            val t = totalMinutes
            return if (t <= 0) 1f else (usedMinutes.toFloat() / t.toFloat()).coerceIn(0f, 1f)
        }
}

@Serializable
data class QuotaRequest(
    val id: String = "",
    val date: String = "",
    val minutes: Int = 0,
    val reason: String = "",
    @SerialName("device_id") val deviceId: String? = null,
    val status: String = "pending",
    @SerialName("decided_by") val decidedBy: String? = null,
    @SerialName("decision_note") val decisionNote: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null
)

@Serializable
data class CreateQuotaRequestRequest(
    val minutes: Int,
    val reason: String,
    @SerialName("device_id") val deviceId: String? = null
)

@Serializable
data class ConsumeQuotaRequest(
    val minutes: Int,
    val source: String,
    @SerialName("device_id") val deviceId: String? = null,
    val detail: String? = null
)

@Serializable
data class LockQuotaRequest(
    val reason: String,
    val until: String? = null,
    @SerialName("device_id") val deviceId: String? = null
)

@Serializable
data class UnlockQuotaRequest(val reason: String)

// ---------------- Panel / Stats ----------------

@Serializable
data class PanelTasks(
    val today: List<Task> = emptyList(),
    val overdue: List<Task> = emptyList()
)

@Serializable
data class PanelWeight(
    val latest: WeightEntry? = null,
    @SerialName("change_7d") val change7d: Double? = null
)

@Serializable
data class PanelStats(
    @SerialName("week_study_minutes") val weekStudyMinutes: Int = 0,
    @SerialName("week_tasks_done") val weekTasksDone: Int = 0,
    val streak: Int = 0
)

@Serializable
data class Exam(val date: String = "", val days: Int = 0)

@Serializable
data class NotificationItem(
    val id: String = "",
    val title: String = "",
    val content: String? = null,
    val body: String? = null,
    val kind: String = "system",
    @SerialName("created_at") val createdAt: String? = null,
    val read: Boolean = false
) {
    val text: String get() = content ?: body ?: ""
}

@Serializable
data class Panel(
    val date: String = "",
    val profile: Profile = Profile(),
    val quota: Quota = Quota(),
    val tasks: PanelTasks = PanelTasks(),
    val diary: DiaryEntry? = null,
    val weight: PanelWeight = PanelWeight(),
    val stats: PanelStats = PanelStats(),
    val notifications: List<NotificationItem> = emptyList(),
    val exam: Exam? = null
) {
    val examDays: Int get() = exam?.days ?: profile.examDays
    val examDate: String get() = exam?.date ?: profile.examDate
}

@Serializable
data class DailyStat(
    val date: String = "",
    @SerialName("study_minutes") val studyMinutes: Int = 0,
    @SerialName("tasks_done") val tasksDone: Int = 0,
    @SerialName("quota_used") val quotaUsed: Int = 0
)

@Serializable
data class HeatCell(val date: String = "", val value: Int = 0)

@Serializable
data class StatsSummary(
    val range: String = "7d",
    @SerialName("study_minutes") val studyMinutes: Int = 0,
    @SerialName("tasks_done") val tasksDone: Int = 0,
    @SerialName("completion_rate") val completionRate: Double = 0.0,
    @SerialName("quota_used_minutes") val quotaUsedMinutes: Int = 0,
    @SerialName("weight_start") val weightStart: Double? = null,
    @SerialName("weight_end") val weightEnd: Double? = null,
    @SerialName("weight_change") val weightChange: Double? = null,
    val streak: Int = 0,
    val daily: List<DailyStat> = emptyList(),
    val attributes: Attributes = Attributes(),
    val heatmap: List<HeatCell> = emptyList()
)

// ---------------- Reports / Achievements / Events / Devices ----------------

@Serializable
data class Report(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val kind: String = "system",
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class Achievement(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val unlocked: Boolean = false,
    @SerialName("unlocked_at") val unlockedAt: String? = null
)

@Serializable
data class Event(
    val id: String = "",
    val type: String = "",
    val severity: String = "info",
    val payload: JsonElement? = null,
    val at: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("created_at") val createdAt: String? = null
) {
    val time: String? get() = createdAt ?: at
    val payloadText: String get() = payload?.toString()?.take(120) ?: ""
}

@Serializable
data class EventCreateRequest(
    val type: String,
    val severity: String? = null,
    val payload: JsonElement? = null,
    val at: String? = null
)

@Serializable
data class DeviceInfo(
    val id: String = "",
    val name: String = "",
    val platform: String = "",
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("last_seen") val lastSeen: String? = null,
    val online: Boolean = false,
    val state: JsonElement? = null
)

@Serializable
data class CommandItem(
    val id: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val type: String = "",
    val payload: JsonElement? = null,
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("acked_at") val ackedAt: String? = null
)

@Serializable
data class HeartbeatState(
    val locked: Boolean = false,
    @SerialName("running_blacklist") val runningBlacklist: List<String> = emptyList(),
    @SerialName("last_violation_at") val lastViolationAt: String? = null
)

@Serializable
data class HeartbeatRequest(
    val name: String = "phone",
    val platform: String = "android",
    @SerialName("app_version") val appVersion: String = "1.0.0",
    val state: HeartbeatState = HeartbeatState()
)

@Serializable
data class HeartbeatResult(
    val device: DeviceInfo? = null,
    val quota: Quota? = null,
    val policy: Policy? = null,
    val commands: List<CommandItem> = emptyList()
)

// ---------------- 本地队列 ----------------

@Serializable
data class QueueItem(
    val id: String = "",
    val method: String = "POST",
    val path: String = "",
    val body: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    val label: String = "",
    @SerialName("created_at") val createdAt: String = ""
)
