package com.ddw.kuangren.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(
        val code: String,
        val message: String,
        val http: Int = 0,
        val network: Boolean = false
    ) : ApiResult<Nothing>()
}

class ApiClient(
    @Volatile var baseUrl: String,
    @Volatile var token: String,
    private val deviceId: String = "phone-main"
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
        // 请求体必须带默认值字段：后端对缺失字段有自己默认值（如 category 缺失→other）。
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Volatile
    var serverTimeOffsetMs: Long = 0L
        private set

    fun apiBase(): String {
        val raw = baseUrl.trim().trimEnd('/')
        return if (raw.endsWith("/api/v1")) raw else raw + "/api/v1"
    }

    fun requestUrl(path: String): String = apiBase() + path

    @PublishedApi
    internal suspend fun <T> rawRequest(
        method: String,
        path: String,
        bodyText: String?,
        deserializer: DeserializationStrategy<T>,
        idempotencyKey: String? = null
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastError: ApiResult.Err? = null
        while (attempt < 3) {
            val result = executeOnce(method, path, bodyText, deserializer, idempotencyKey)
            if (result is ApiResult.Ok) return@withContext result
            val err = result as ApiResult.Err
            val retriable = err.network || err.http >= 500
            val canRetry = retriable && (method == "GET" || idempotencyKey != null)
            if (!canRetry || attempt == 2) return@withContext err
            lastError = err
            delay(300L * (1L shl attempt))
            attempt += 1
        }
        lastError ?: ApiResult.Err("internal_error", "未知错误")
    }

    private suspend fun <T> executeOnce(
        method: String,
        path: String,
        bodyText: String?,
        deserializer: DeserializationStrategy<T>,
        idempotencyKey: String?
    ): ApiResult<T> {
        val builder = Request.Builder()
            .url(requestUrl(path))
            .header("Accept", "application/json")
            .header("X-Actor", "app")
            .header("X-Device-Id", deviceId)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer " + token)
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        val media = "application/json; charset=utf-8".toMediaType()
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((bodyText ?: "{}").toRequestBody(media))
            "PUT" -> builder.put((bodyText ?: "{}").toRequestBody(media))
            "PATCH" -> builder.patch((bodyText ?: "{}").toRequestBody(media))
            "DELETE" -> builder.delete()
            else -> builder.post((bodyText ?: "{}").toRequestBody(media))
        }
        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                parseResponse(resp.code, text, deserializer)
            }
        } catch (e: IOException) {
            ApiResult.Err("offline", "网络不可用，已使用缓存", network = true)
        } catch (e: Exception) {
            ApiResult.Err("internal_error", "请求失败: " + (e.message ?: "unknown"))
        }
    }

    private fun <T> parseResponse(
        code: Int,
        text: String,
        deserializer: DeserializationStrategy<T>
    ): ApiResult<T> {
        val root: JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
        val obj = root as? JsonObject
        var dataElem: JsonElement? = null
        if (obj != null && obj.containsKey("ok")) {
            val okValue = obj["ok"]?.jsonPrimitive?.booleanOrNull
            dataElem = obj["data"]
            obj["server_time"]?.let { st ->
                val iso = runCatching { st.jsonPrimitive.contentOrNull }.getOrNull()
                if (!iso.isNullOrBlank()) applyServerTime(iso)
            }
            if (okValue == false) {
                val errObj = obj["error"] as? JsonObject
                val ec = errObj?.get("code")?.jsonPrimitive?.contentOrNull ?: "internal_error"
                val em = errObj?.get("message")?.jsonPrimitive?.contentOrNull ?: "服务器返回错误"
                return ApiResult.Err(ec, em, code)
            }
        }
        if (code !in 200..299) {
            return ApiResult.Err("http_" + code, "服务器错误 HTTP " + code, code)
        }
        val payload = if (obj != null && obj.containsKey("data")) dataElem else root
        if (payload == null || payload is JsonNull) {
            @Suppress("UNCHECKED_CAST")
            return try {
                ApiResult.Ok(JsonNull as T)
            } catch (e: Exception) {
                ApiResult.Err("parse_error", "返回数据为空", code)
            }
        }
        return try {
            ApiResult.Ok(json.decodeFromJsonElement(deserializer, payload))
        } catch (e: Exception) {
            ApiResult.Err("parse_error", "数据解析失败: " + (e.message ?: ""), code)
        }
    }

    private fun applyServerTime(iso: String) {
        runCatching {
            val instant = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            serverTimeOffsetMs = instant - System.currentTimeMillis()
        }
    }

    // ---------------- 系统 ----------------
    suspend fun health(): ApiResult<HealthInfo> =
        rawRequest("GET", "/health", null, serializer<HealthInfo>())

    suspend fun profile(): ApiResult<Profile> =
        rawRequest("GET", "/profile", null, serializer<Profile>())

    suspend fun patchProfile(body: PatchProfileRequest): ApiResult<Profile> =
        rawRequest("PATCH", "/profile", json.encodeToString(PatchProfileRequest.serializer(), body), serializer<Profile>())

    suspend fun panel(date: String?): ApiResult<Panel> {
        val q = if (date.isNullOrBlank()) "" else "?date=" + date
        return rawRequest("GET", "/panel" + q, null, serializer<Panel>())
    }

    suspend fun export(): ApiResult<JsonElement> =
        rawRequest("GET", "/export", null, JsonElement.serializer())

    // ---------------- 任务 ----------------
    suspend fun tasks(
        date: String? = null,
        from: String? = null,
        to: String? = null,
        status: String? = null,
        category: String? = null,
        limit: Int? = null
    ): ApiResult<List<Task>> {
        val parts = ArrayList<String>()
        if (!date.isNullOrBlank()) parts.add("date=" + date)
        if (!from.isNullOrBlank()) parts.add("from=" + from)
        if (!to.isNullOrBlank()) parts.add("to=" + to)
        if (!status.isNullOrBlank()) parts.add("status=" + status)
        if (!category.isNullOrBlank()) parts.add("category=" + category)
        if (limit != null) parts.add("limit=" + limit)
        val q = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        return rawRequest("GET", "/tasks" + q, null, serializer<List<Task>>())
    }

    suspend fun createTask(body: CreateTaskRequest, key: String? = null): ApiResult<Task> =
        rawRequest("POST", "/tasks", json.encodeToString(CreateTaskRequest.serializer(), body), serializer<Task>(), key)

    suspend fun patchTask(id: String, body: PatchTaskRequest): ApiResult<Task> =
        rawRequest("PATCH", "/tasks/" + id, json.encodeToString(PatchTaskRequest.serializer(), body), serializer<Task>())

    suspend fun deleteTask(id: String): ApiResult<DeletedResult> =
        rawRequest("DELETE", "/tasks/" + id, null, serializer<DeletedResult>())

    suspend fun completeTask(id: String, body: CompleteRequest, key: String? = null): ApiResult<CompleteResult> =
        rawRequest("POST", "/tasks/" + id + "/complete", json.encodeToString(CompleteRequest.serializer(), body), serializer<CompleteResult>(), key)

    suspend fun reopenTask(id: String): ApiResult<Task> =
        rawRequest("POST", "/tasks/" + id + "/reopen", "{}", serializer<Task>())

    suspend fun postponeTask(id: String, body: PostponeRequest): ApiResult<Task> =
        rawRequest("POST", "/tasks/" + id + "/postpone", json.encodeToString(PostponeRequest.serializer(), body), serializer<Task>())

    // ---------------- 日记 ----------------
    // 当天没有日记时后端返回 data:null；泛型 rawRequest 会把 JsonNull 当 T 返回，
    // 调用方对 DiaryEntry? 取值时会 ClassCastException，所以这里用 JsonElement 中转。
    suspend fun diary(date: String): ApiResult<DiaryEntry?> {
        val raw = rawRequest("GET", "/diary?date=" + date, null, JsonElement.serializer())
        return when (raw) {
            is ApiResult.Ok -> {
                val elem = raw.value
                if (elem is JsonNull) ApiResult.Ok(null)
                else ApiResult.Ok(json.decodeFromJsonElement(serializer<DiaryEntry>(), elem))
            }
            is ApiResult.Err -> raw
        }
    }

    suspend fun diaryEntries(from: String, to: String, limit: Int = 30): ApiResult<List<DiaryEntry>> =
        rawRequest("GET", "/diary/entries?from=" + from + "&to=" + to + "&limit=" + limit, null, serializer<List<DiaryEntry>>())

    suspend fun putDiary(date: String, body: DiaryPutRequest): ApiResult<DiaryEntry> =
        rawRequest("PUT", "/diary/" + date, json.encodeToString(DiaryPutRequest.serializer(), body), serializer<DiaryEntry>())

    // ---------------- 体重 ----------------
    suspend fun weight(days: Int = 30): ApiResult<WeightResponse> =
        rawRequest("GET", "/weight?days=" + days, null, serializer<WeightResponse>())

    suspend fun putWeight(date: String, body: WeightPutRequest): ApiResult<WeightEntry> =
        rawRequest("PUT", "/weight/" + date, json.encodeToString(WeightPutRequest.serializer(), body), serializer<WeightEntry>())

    // ---------------- 额度 ----------------
    suspend fun quota(date: String? = null): ApiResult<Quota> {
        val q = if (date.isNullOrBlank()) "" else "?date=" + date
        return rawRequest("GET", "/quota" + q, null, serializer<Quota>())
    }

    suspend fun policy(date: String? = null): ApiResult<Policy> {
        val q = if (date.isNullOrBlank()) "" else "?date=" + date
        return rawRequest("GET", "/policy" + q, null, serializer<Policy>())
    }

    suspend fun quotaRequests(status: String = "pending"): ApiResult<List<QuotaRequest>> =
        rawRequest("GET", "/quota/requests?status=" + status + "&limit=20", null, serializer<List<QuotaRequest>>())

    suspend fun createQuotaRequest(body: CreateQuotaRequestRequest): ApiResult<QuotaRequest> =
        rawRequest("POST", "/quota/requests", json.encodeToString(CreateQuotaRequestRequest.serializer(), body), serializer<QuotaRequest>())

    suspend fun consumeQuota(body: ConsumeQuotaRequest): ApiResult<Quota> =
        rawRequest("POST", "/quota/consume", json.encodeToString(ConsumeQuotaRequest.serializer(), body), serializer<Quota>())

    suspend fun lockQuota(body: LockQuotaRequest): ApiResult<Quota> =
        rawRequest("POST", "/quota/lock", json.encodeToString(LockQuotaRequest.serializer(), body), serializer<Quota>())

    suspend fun unlockQuota(body: UnlockQuotaRequest): ApiResult<Quota> =
        rawRequest("POST", "/quota/unlock", json.encodeToString(UnlockQuotaRequest.serializer(), body), serializer<Quota>())

    // ---------------- 统计 / 报告 / 成就 ----------------
    suspend fun statsSummary(range: String): ApiResult<StatsSummary> =
        rawRequest("GET", "/stats/summary?range=" + range, null, serializer<StatsSummary>())

    suspend fun heatmap(days: Int = 90): ApiResult<List<HeatCell>> =
        rawRequest("GET", "/stats/heatmap?days=" + days, null, serializer<List<HeatCell>>())

    suspend fun reports(limit: Int = 20): ApiResult<List<Report>> =
        rawRequest("GET", "/reports?limit=" + limit, null, serializer<List<Report>>())

    suspend fun achievements(): ApiResult<List<Achievement>> =
        rawRequest("GET", "/achievements", null, serializer<List<Achievement>>())

    suspend fun notifications(unreadOnly: Boolean = false): ApiResult<List<NotificationItem>> {
        val q = if (unreadOnly) "?unread=1" else ""
        return rawRequest("GET", "/notifications" + q, null, serializer<List<NotificationItem>>())
    }

    suspend fun events(limit: Int = 20): ApiResult<List<Event>> =
        rawRequest("GET", "/events?limit=" + limit, null, serializer<List<Event>>())

    suspend fun devices(): ApiResult<List<DeviceInfo>> =
        rawRequest("GET", "/devices", null, serializer<List<DeviceInfo>>())

    suspend fun heartbeat(deviceId: String, body: HeartbeatRequest): ApiResult<HeartbeatResult> =
        rawRequest("POST", "/devices/" + deviceId + "/heartbeat", json.encodeToString(HeartbeatRequest.serializer(), body), serializer<HeartbeatResult>())

    suspend fun postEvent(deviceId: String, body: EventCreateRequest): ApiResult<IdResult> =
        rawRequest("POST", "/devices/" + deviceId + "/events", json.encodeToString(EventCreateRequest.serializer(), body), serializer<IdResult>())

    suspend fun replay(item: QueueItem): ApiResult<JsonElement> =
        rawRequest(item.method, item.path, item.body, JsonElement.serializer(), item.idempotencyKey)
}
