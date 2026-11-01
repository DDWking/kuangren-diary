package com.ddw.kuangren.data

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class CacheStore(context: Context) {

    private val prefs = context.getSharedPreferences("kuangren_cache", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    private fun <T> save(key: String, serializer: KSerializer<T>, value: T) {
        runCatching { prefs.edit().putString(key, json.encodeToString(serializer, value)).apply() }
    }

    private fun <T> load(key: String, deserializer: KSerializer<T>): T? {
        val text = prefs.getString(key, null) ?: return null
        return runCatching { json.decodeFromString(deserializer, text) }.getOrNull()
    }

    private fun <T> saveList(key: String, item: KSerializer<T>, value: List<T>) {
        save(key, ListSerializer(item), value)
    }

    private fun <T> loadList(key: String, item: KSerializer<T>): List<T>? {
        return load(key, ListSerializer(item))
    }

    // ---- panel / profile / quota ----
    fun savePanel(value: Panel) = save("panel", Panel.serializer(), value)
    fun panel(): Panel? = load("panel", Panel.serializer())

    fun saveProfile(value: Profile) = save("profile", Profile.serializer(), value)
    fun profile(): Profile? = load("profile", Profile.serializer())

    fun saveQuota(value: Quota) = save("quota", Quota.serializer(), value)
    fun quota(): Quota? = load("quota", Quota.serializer())

    fun savePolicy(value: Policy) = save("policy", Policy.serializer(), value)
    fun policy(): Policy? = load("policy", Policy.serializer())

    // ---- tasks ----
    fun saveTasks(key: String, value: List<Task>) = saveList("tasks_" + key, Task.serializer(), value)
    fun tasks(key: String): List<Task>? = loadList("tasks_" + key, Task.serializer())

    fun saveTask(value: Task) = save("task_" + value.id, Task.serializer(), value)
    fun task(id: String): Task? = load("task_" + id, Task.serializer())

    // ---- diary ----
    fun saveDiaryEntry(value: DiaryEntry) = save("diary_" + value.date, DiaryEntry.serializer(), value)
    fun diary(date: String): DiaryEntry? = load("diary_" + date, DiaryEntry.serializer())

    fun saveDiaryList(value: List<DiaryEntry>) = saveList("diary_list", DiaryEntry.serializer(), value)
    fun diaryList(): List<DiaryEntry>? = loadList("diary_list", DiaryEntry.serializer())

    // ---- weight ----
    fun saveWeight(value: WeightResponse) = save("weight", WeightResponse.serializer(), value)
    fun weight(): WeightResponse? = load("weight", WeightResponse.serializer())

    // ---- system ----
    fun saveReports(value: List<Report>) = saveList("reports", Report.serializer(), value)
    fun reports(): List<Report>? = loadList("reports", Report.serializer())

    fun saveAchievements(value: List<Achievement>) = saveList("achievements", Achievement.serializer(), value)
    fun achievements(): List<Achievement>? = loadList("achievements", Achievement.serializer())

    fun saveEvents(value: List<Event>) = saveList("events", Event.serializer(), value)
    fun events(): List<Event>? = loadList("events", Event.serializer())

    fun saveNotifications(value: List<NotificationItem>) = saveList("notifications", NotificationItem.serializer(), value)
    fun notifications(): List<NotificationItem>? = loadList("notifications", NotificationItem.serializer())

    fun saveStats(range: String, value: StatsSummary) = save("stats_" + range, StatsSummary.serializer(), value)
    fun stats(range: String): StatsSummary? = load("stats_" + range, StatsSummary.serializer())

    fun saveHeatmap(value: List<HeatCell>) = saveList("heatmap", HeatCell.serializer(), value)
    fun heatmap(): List<HeatCell>? = loadList("heatmap", HeatCell.serializer())

    // ---- meta ----
    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0L)
        set(value) { prefs.edit().putLong("last_sync_at", value).apply() }

    var serverOffsetMs: Long
        get() = prefs.getLong("server_offset_ms", 0L)
        set(value) { prefs.edit().putLong("server_offset_ms", value).apply() }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
