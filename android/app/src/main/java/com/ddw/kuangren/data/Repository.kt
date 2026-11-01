package com.ddw.kuangren.data

import android.content.Context
import com.ddw.kuangren.BuildConfig
import com.ddw.kuangren.widget.WidgetCache
import com.ddw.kuangren.widget.WidgetUpdater
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

enum class TaskFilter(val label: String, val cacheKey: String) {
    TODAY("今日", "today"),
    WEEK("本周", "week"),
    ALL("全部", "all"),
    DONE("已完成", "done")
}

data class SystemBundle(
    val reports: List<Report>,
    val achievements: List<Achievement>,
    val events: List<Event>,
    val notifications: List<NotificationItem>,
    val stats7: StatsSummary,
    val heatmap: List<HeatCell>
)

class Repository(val context: Context) {

    private val app = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
    }

    val cache = CacheStore(app)
    val queue = OfflineQueue(app)
    val settings = app.getSharedPreferences("kuangren_settings", Context.MODE_PRIVATE)

    val api = ApiClient(
        baseUrl = settings.getString("api_base", null) ?: BuildConfig.API_BASE,
        token = settings.getString("api_token", null) ?: BuildConfig.API_TOKEN,
        deviceId = BuildConfig.DEVICE_ID
    )

    val offline = kotlinx.coroutines.flow.MutableStateFlow(false)
    val pendingCount = kotlinx.coroutines.flow.MutableStateFlow(queue.size())

    // ---------------- 设置 ----------------
    var softLockEnabled: Boolean
        get() = settings.getBoolean("soft_lock_enabled", false)
        set(value) { settings.edit().putBoolean("soft_lock_enabled", value).apply() }

    var notificationsEnabled: Boolean
        get() = settings.getBoolean("notifications_enabled", true)
        set(value) { settings.edit().putBoolean("notifications_enabled", value).apply() }

    var pomodoroMinutes: Int
        get() = settings.getInt("pomodoro_minutes", 25)
        set(value) { settings.edit().putInt("pomodoro_minutes", value).apply() }

    var heightCm: Int
        get() = settings.getInt("height_cm", 181)
        set(value) { settings.edit().putInt("height_cm", value).apply() }

    var focusTaskId: String
        get() = settings.getString("focus_task_id", "") ?: ""
        set(value) { settings.edit().putString("focus_task_id", value).apply() }

    fun apiBase(): String = api.baseUrl
    fun maskedToken(): String = maskToken(api.token)

    fun applyAdvanced(baseUrl: String, token: String) {
        settings.edit().putString("api_base", baseUrl).putString("api_token", token).apply()
        api.baseUrl = baseUrl
        api.token = token
    }

    fun clearAdvanced() {
        settings.edit().remove("api_base").remove("api_token").apply()
        api.baseUrl = BuildConfig.API_BASE
        api.token = BuildConfig.API_TOKEN
    }

    // ---------------- 本地缓存 ----------------
    fun cachedPanel(): Panel? = cache.panel()
    fun cachedQuota(): Quota? = cache.quota()
    fun cachedPolicy(): Policy? = cache.policy()
    fun cachedTasks(filter: TaskFilter): List<Task>? = cache.tasks(filter.cacheKey)
    fun cachedTask(id: String): Task? = cache.task(id)
    fun cachedDiary(date: String): DiaryEntry? = cache.diary(date)
    fun cachedWeight(): WeightResponse? = cache.weight()
    fun cachedStats(range: String): StatsSummary? = cache.stats(range)
    fun lastSyncAt(): Long = cache.lastSyncAt

    private fun markOffline(err: ApiResult.Err): ApiResult.Err {
        if (err.network) offline.value = true
        return err
    }

    private fun updateWidgets() {
        runCatching { WidgetUpdater.refreshAll(app) }
    }

    // ---------------- 面板 ----------------
    suspend fun refreshPanel(date: String? = null): ApiResult<Panel> {
        val result = api.panel(date ?: Clocks.today())
        if (result is ApiResult.Ok) {
            val panel = result.value
            cache.savePanel(panel)
            cache.saveProfile(panel.profile)
            cache.saveQuota(panel.quota)
            panel.quota.policy?.let { cache.savePolicy(it) }
            cache.saveTasks(TaskFilter.TODAY.cacheKey, panel.tasks.today)
            panel.tasks.overdue.forEach { cache.saveTask(it) }
            panel.tasks.today.forEach { cache.saveTask(it) }
            cache.lastSyncAt = System.currentTimeMillis()
            WidgetCache.write(app, panel)
            updateWidgets()
            offline.value = false
        } else {
            markOffline(result as ApiResult.Err)
        }
        return result
    }

    // ---------------- 任务 ----------------
    suspend fun loadTasks(filter: TaskFilter): ApiResult<List<Task>> {
        val result = when (filter) {
            TaskFilter.TODAY -> api.tasks(date = Clocks.today())
            TaskFilter.WEEK -> api.tasks(from = Clocks.weekStart(), to = Clocks.today())
            TaskFilter.ALL -> api.tasks(limit = 200)
            TaskFilter.DONE -> api.tasks(status = "done", limit = 100)
        }
        if (result is ApiResult.Ok) {
            cache.saveTasks(filter.cacheKey, result.value)
            result.value.forEach { cache.saveTask(it) }
            offline.value = false
        } else {
            markOffline(result as ApiResult.Err)
        }
        return result
    }

    suspend fun loadTask(id: String): Task? {
        cache.task(id)?.let { return it }
        val result = api.tasks(limit = 200)
        if (result is ApiResult.Ok) {
            result.value.forEach { cache.saveTask(it) }
            return result.value.firstOrNull { it.id == id }
        }
        return null
    }

    suspend fun createTask(req: CreateTaskRequest): ApiResult<Task> {
        val key = Clocks.newId()
        val bodyText = json.encodeToString(CreateTaskRequest.serializer(), req)
        val result = api.createTask(req, key)
        if (result is ApiResult.Ok) {
            cache.saveTask(result.value)
            return result
        }
        val err = result as ApiResult.Err
        if (err.network) {
            enqueueJson("POST", "/tasks", bodyText, key, "新建任务 " + req.title)
            return queued()
        }
        return err
    }

    suspend fun patchTask(id: String, req: PatchTaskRequest): ApiResult<Task> {
        val bodyText = json.encodeToString(PatchTaskRequest.serializer(), req)
        val result = api.patchTask(id, req)
        if (result is ApiResult.Ok) {
            cache.saveTask(result.value)
            return result
        }
        val err = result as ApiResult.Err
        if (err.network) {
            enqueueJson("PATCH", "/tasks/" + id, bodyText, null, "修改任务")
            return queued()
        }
        return err
    }

    suspend fun deleteTask(id: String): ApiResult<DeletedResult> {
        val result = api.deleteTask(id)
        if (result is ApiResult.Ok) return result
        val err = result as ApiResult.Err
        if (err.network) {
            enqueueJson("DELETE", "/tasks/" + id, null, null, "删除任务")
            return queued()
        }
        return err
    }

    suspend fun completeTask(task: Task, note: String?, actualMinutes: Int?): ApiResult<CompleteResult> {
        val key = Clocks.newId()
        val body = CompleteRequest(note, actualMinutes, Clocks.nowIso())
        val result = api.completeTask(task.id, body, key)
        if (result is ApiResult.Ok) {
            cache.saveTask(result.value.task)
            result.value.profile?.let { cache.saveProfile(it) }
            result.value.quota?.let {
                cache.saveQuota(it)
                WidgetCache.writeQuota(app, it)
            }
            updateWidgets()
            return result
        }
        val err = result as ApiResult.Err
        if (err.network) {
            enqueueJson(
                "POST",
                "/tasks/" + task.id + "/complete",
                json.encodeToString(CompleteRequest.serializer(), body),
                key,
                "完成任务 " + task.title
            )
            return queued()
        }
        return err
    }

    suspend fun reopenTask(id: String): ApiResult<Task> {
        val result = api.reopenTask(id)
        if (result is ApiResult.Ok) cache.saveTask(result.value)
        else {
            val err = result as ApiResult.Err
            if (err.network) {
                enqueueJson("POST", "/tasks/" + id + "/reopen", "{}", null, "重开任务")
                return queued()
            }
        }
        return result
    }

    suspend fun postponeTask(id: String, toDate: String): ApiResult<Task> {
        val body = PostponeRequest(toDate)
        val result = api.postponeTask(id, body)
        if (result is ApiResult.Ok) cache.saveTask(result.value)
        else {
            val err = result as ApiResult.Err
            if (err.network) {
                enqueueJson(
                    "POST",
                    "/tasks/" + id + "/postpone",
                    json.encodeToString(PostponeRequest.serializer(), body),
                    null,
                    "推迟任务"
                )
                return queued()
            }
        }
        return result
    }

    // ---------------- 日记 ----------------
    suspend fun loadDiary(date: String): ApiResult<DiaryEntry?> {
        val result = api.diary(date)
        if (result is ApiResult.Ok) {
            result.value?.let { cache.saveDiaryEntry(it) }
            offline.value = false
        } else {
            markOffline(result as ApiResult.Err)
        }
        return result
    }

    suspend fun saveDiary(date: String, req: DiaryPutRequest): ApiResult<DiaryEntry> {
        val bodyText = json.encodeToString(DiaryPutRequest.serializer(), req)
        val result = api.putDiary(date, req)
        if (result is ApiResult.Ok) {
            cache.saveDiaryEntry(result.value)
            return result
        }
        val err = result as ApiResult.Err
        if (err.network) {
            val draft = DiaryEntry(date = date, mood = req.mood, content = req.content, tags = req.tags)
            cache.saveDiaryEntry(draft)
            enqueueJson("PUT", "/diary/" + date, bodyText, null, "写日记 " + date)
            return queued()
        }
        return err
    }

    suspend fun loadDiaryEntries(): ApiResult<List<DiaryEntry>> {
        val result = api.diaryEntries(Clocks.dateMinus(60), Clocks.today(), 60)
        if (result is ApiResult.Ok) {
            cache.saveDiaryList(result.value)
            offline.value = false
        } else {
            markOffline(result as ApiResult.Err)
        }
        return result
    }

    // ---------------- 体重 ----------------
    suspend fun loadWeight(days: Int = 30): ApiResult<WeightResponse> {
        val result = api.weight(days)
        if (result is ApiResult.Ok) {
            cache.saveWeight(result.value)
            offline.value = false
        } else {
            markOffline(result as ApiResult.Err)
        }
        return result
    }

    suspend fun saveWeight(date: String, req: WeightPutRequest): ApiResult<WeightEntry> {
        val result = api.putWeight(date, req)
        if (result is ApiResult.Ok) {
            val existing = cache.weight() ?: WeightResponse()
            val entries = existing.entries.filterNot { it.date == date } + result.value
            val sorted = entries.sortedBy { it.date }
            cache.saveWeight(WeightResponse(sorted, result.value, existing.change7d))
            return result
        }
        val err = result as ApiResult.Err
        if (err.network) {
            enqueueJson(
                "PUT",
                "/weight/" + date,
                json.encodeToString(WeightPutRequest.serializer(), req),
                null,
                "记体重 " + date
            )
            return queued()
        }
        return err
    }

    // ---------------- 额度 ----------------
    suspend fun refreshQuota(): ApiResult<Quota> {
        val result = api.quota(Clocks.today())
        if (result is ApiResult.Ok) {
            cache.saveQuota(result.value)
            WidgetCache.writeQuota(app, result.value)
            updateWidgets()
            offline.value = false
        } else {
            markOffline(result as ApiResult.Err)
        }
        return result
    }

    suspend fun refreshPolicy(): ApiResult<Policy> {
        val result = api.policy(Clocks.today())
        if (result is ApiResult.Ok) cache.savePolicy(result.value)
        return result
    }

    suspend fun requestExtension(minutes: Int, reason: String): ApiResult<QuotaRequest> {
        val body = CreateQuotaRequestRequest(minutes, reason, BuildConfig.DEVICE_ID)
        val result = api.createQuotaRequest(body)
        if (result is ApiResult.Ok) return result
        val err = result as ApiResult.Err
        if (err.network) {
            enqueueJson(
                "POST",
                "/quota/requests",
                json.encodeToString(CreateQuotaRequestRequest.serializer(), body),
                null,
                "申请延时 " + minutes + " 分钟"
            )
            return queued()
        }
        return err
    }

    suspend fun consumeQuota(minutes: Int, source: String, detail: String?): ApiResult<Quota> {
        val body = ConsumeQuotaRequest(minutes, source, BuildConfig.DEVICE_ID, detail)
        val result = api.consumeQuota(body)
        if (result is ApiResult.Ok) {
            cache.saveQuota(result.value)
            WidgetCache.writeQuota(app, result.value)
            updateWidgets()
            return result
        }
        val err = result as ApiResult.Err
        if (err.network) {
            enqueueJson(
                "POST",
                "/quota/consume",
                json.encodeToString(ConsumeQuotaRequest.serializer(), body),
                null,
                "记录娱乐使用"
            )
            return queued()
        }
        return err
    }

    suspend fun lockQuota(reason: String, until: String?): ApiResult<Quota> {
        val result = api.lockQuota(LockQuotaRequest(reason, until, BuildConfig.DEVICE_ID))
        if (result is ApiResult.Ok) {
            cache.saveQuota(result.value)
            WidgetCache.writeQuota(app, result.value)
            updateWidgets()
        }
        return result
    }

    suspend fun unlockQuota(reason: String): ApiResult<Quota> {
        val result = api.unlockQuota(UnlockQuotaRequest(reason))
        if (result is ApiResult.Ok) {
            cache.saveQuota(result.value)
            WidgetCache.writeQuota(app, result.value)
            updateWidgets()
        }
        return result
    }

    // ---------------- 系统 ----------------
    suspend fun refreshSystem(): ApiResult<SystemBundle> = coroutineScope {
        val reportsD = async { api.reports(20) }
        val achD = async { api.achievements() }
        val eventsD = async { api.events(20) }
        val notifD = async { api.notifications(true) }
        val statsD = async { api.statsSummary("7d") }
        val heatD = async { api.heatmap(90) }
        val reports = reportsD.await()
        val achievements = achD.await()
        val events = eventsD.await()
        val notifications = notifD.await()
        val stats = statsD.await()
        val heat = heatD.await()
        val results = listOf(reports, achievements, events, notifications, stats, heat)
        val anyOk = results.any { it is ApiResult.Ok }
        if (!anyOk) {
            val firstErr = results.filterIsInstance<ApiResult.Err>().firstOrNull()
                ?: ApiResult.Err("internal_error", "加载失败")
            markOffline(firstErr)
            return@coroutineScope firstErr
        }
        val reportsValue = (reports as? ApiResult.Ok)?.value ?: cache.reports() ?: emptyList()
        val achValue = (achievements as? ApiResult.Ok)?.value ?: cache.achievements() ?: emptyList()
        val eventsValue = (events as? ApiResult.Ok)?.value ?: cache.events() ?: emptyList()
        val notifValue = (notifications as? ApiResult.Ok)?.value ?: cache.notifications() ?: emptyList()
        val statsValue = (stats as? ApiResult.Ok)?.value ?: cache.stats("7d") ?: StatsSummary()
        val heatValue = (heat as? ApiResult.Ok)?.value ?: cache.heatmap() ?: emptyList()
        cache.saveReports(reportsValue)
        cache.saveAchievements(achValue)
        cache.saveEvents(eventsValue)
        cache.saveNotifications(notifValue)
        if (stats is ApiResult.Ok) cache.saveStats("7d", statsValue)
        if (heat is ApiResult.Ok) cache.saveHeatmap(heatValue)
        offline.value = false
        ApiResult.Ok(
            SystemBundle(
                reports = reportsValue,
                achievements = achValue,
                events = eventsValue,
                notifications = notifValue,
                stats7 = statsValue,
                heatmap = heatValue
            )
        )
    }

    suspend fun loadStats(range: String): ApiResult<StatsSummary> {
        val result = api.statsSummary(range)
        if (result is ApiResult.Ok) cache.saveStats(range, result.value)
        return result
    }

    suspend fun loadHeatmap(): ApiResult<List<HeatCell>> {
        val result = api.heatmap(90)
        if (result is ApiResult.Ok) cache.saveHeatmap(result.value)
        return result
    }

    suspend fun checkHealth(): ApiResult<HealthInfo> = api.health()

    suspend fun exportData(): ApiResult<JsonElement> = api.export()

    suspend fun heartbeat(): ApiResult<HeartbeatResult> {
        val quota = cache.quota()
        return api.heartbeat(
            BuildConfig.DEVICE_ID,
            HeartbeatRequest(
                name = "phone-main",
                platform = "android",
                appVersion = "1.0.0",
                state = HeartbeatState(locked = quota?.locked ?: false)
            )
        )
    }

    suspend fun postEvent(type: String, severity: String, payload: JsonElement? = null) {
        val result = api.postEvent(
            BuildConfig.DEVICE_ID,
            EventCreateRequest(type = type, severity = severity, payload = payload, at = Clocks.nowIso())
        )
        if (result is ApiResult.Err && result.network) {
            enqueueJson(
                "POST",
                "/devices/" + BuildConfig.DEVICE_ID + "/events",
                json.encodeToString(
                    EventCreateRequest.serializer(),
                    EventCreateRequest(type = type, severity = severity, payload = payload, at = Clocks.nowIso())
                ),
                null,
                "事件 " + type
            )
        }
    }

    // ---------------- 队列 ----------------
    private fun enqueueJson(method: String, path: String, bodyText: String?, key: String?, label: String) {
        queue.enqueue(
            QueueItem(
                id = Clocks.newId(),
                method = method,
                path = path,
                body = bodyText,
                idempotencyKey = key,
                label = label,
                createdAt = Clocks.nowIso()
            )
        )
        pendingCount.value = queue.size()
    }

    private fun queued(): ApiResult.Err =
        ApiResult.Err("queued", "已离线，操作已排队，联网后自动同步")

    suspend fun flushQueue(): Int {
        var done = 0
        val items = queue.all()
        for (item in items) {
            val result = api.replay(item)
            when (result) {
                is ApiResult.Ok -> {
                    queue.remove(item.id)
                    done += 1
                }
                is ApiResult.Err -> {
                    if (result.network || result.http >= 500) {
                        pendingCount.value = queue.size()
                        return done
                    }
                    // 4xx 视为不可重试，丢弃并继续
                    queue.remove(item.id)
                }
            }
        }
        pendingCount.value = queue.size()
        return done
    }

    suspend fun sync(): Boolean {
        val flushed = flushQueue()
        val panel = refreshPanel()
        val ok = panel is ApiResult.Ok
        if (flushed > 0) {
            updateWidgets()
        }
        if (pendingCount.value == 0) {
            runCatching { heartbeat() }
        }
        return ok
    }
}
