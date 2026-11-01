package com.ddw.kuangren.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ddw.kuangren.KuangrenApp
import com.ddw.kuangren.Notifications
import com.ddw.kuangren.data.Achievement
import com.ddw.kuangren.data.ApiResult
import com.ddw.kuangren.data.Clocks
import com.ddw.kuangren.data.CompleteRequest
import com.ddw.kuangren.data.CreateTaskRequest
import com.ddw.kuangren.data.DiaryEntry
import com.ddw.kuangren.data.DiaryPutRequest
import com.ddw.kuangren.data.Event
import com.ddw.kuangren.data.HeatCell
import com.ddw.kuangren.data.Panel
import com.ddw.kuangren.data.PatchTaskRequest
import com.ddw.kuangren.data.Report
import com.ddw.kuangren.data.StatsSummary
import com.ddw.kuangren.data.SystemBundle
import com.ddw.kuangren.data.Task
import com.ddw.kuangren.data.TaskFilter
import com.ddw.kuangren.data.WeightEntry
import com.ddw.kuangren.data.WeightPutRequest
import com.ddw.kuangren.data.WeightResponse
import com.ddw.kuangren.focus.FocusKeepAliveService
import com.ddw.kuangren.data.formatMinutes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

data class UiState<T>(
    val loading: Boolean = false,
    val error: String? = null,
    val data: T? = null,
    val stale: Boolean = false
)

data class FocusUiState(
    val active: Boolean = false,
    val running: Boolean = false,
    val countdown: Boolean = true,
    val totalSeconds: Int = 25 * 60,
    val remainingSeconds: Int = 25 * 60,
    val elapsedSeconds: Int = 0,
    val taskId: String = "",
    val taskTitle: String = "",
    val noise: String = "off",
    val finished: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val repo = (application as KuangrenApp).container.repository
    private val app = application

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    private val _panel = MutableStateFlow(UiState<Panel>())
    val panel: StateFlow<UiState<Panel>> = _panel

    private val _filter = MutableStateFlow(TaskFilter.TODAY)
    val filter: StateFlow<TaskFilter> = _filter

    private val _tasks = MutableStateFlow(UiState<List<Task>>())
    val tasks: StateFlow<UiState<List<Task>>> = _tasks

    private val _taskDetail = MutableStateFlow(UiState<Task>())
    val taskDetail: StateFlow<UiState<Task>> = _taskDetail

    private val _diaryDate = MutableStateFlow(Clocks.today())
    val diaryDate: StateFlow<String> = _diaryDate

    private val _diary = MutableStateFlow(UiState<DiaryEntry?>())
    val diary: StateFlow<UiState<DiaryEntry?>> = _diary

    private val _diaryHistory = MutableStateFlow<List<DiaryEntry>>(emptyList())
    val diaryHistory: StateFlow<List<DiaryEntry>> = _diaryHistory

    private val _body = MutableStateFlow(UiState<WeightResponse>())
    val body: StateFlow<UiState<WeightResponse>> = _body

    private val _system = MutableStateFlow(UiState<SystemBundle>())
    val system: StateFlow<UiState<SystemBundle>> = _system

    private val _statsRange = MutableStateFlow("7d")
    val statsRange: StateFlow<String> = _statsRange

    private val _stats = MutableStateFlow(UiState<StatsSummary>())
    val stats: StateFlow<UiState<StatsSummary>> = _stats

    private val _heatmap = MutableStateFlow<List<HeatCell>>(emptyList())
    val heatmap: StateFlow<List<HeatCell>> = _heatmap

    private val _focus = MutableStateFlow(FocusUiState())
    val focus: StateFlow<FocusUiState> = _focus

    private var focusJob: Job? = null
    private var noisePlayer: WhiteNoisePlayer? = null

    private val _advancedUnlocked = MutableStateFlow(false)
    val advancedUnlocked: StateFlow<Boolean> = _advancedUnlocked

    init {
        _panel.value = UiState(data = repo.cachedPanel())
        _tasks.value = UiState(data = repo.cachedTasks(TaskFilter.TODAY))
        _body.value = UiState(data = repo.cachedWeight())
        _stats.value = UiState(data = repo.cachedStats("7d"))
        _diaryHistory.value = repo.cache.diaryList() ?: emptyList()
        viewModelScope.launch {
            refreshPanel()
            refreshSystem()
            refreshBody()
            loadHeatmap()
        }
    }

    fun postNotice(message: String) {
        _notice.value = message
    }

    fun consumeNotice() {
        _notice.value = null
    }

    private fun errorText(err: ApiResult.Err): String {
        return if (err.network) "网络不可用 · 显示缓存" else err.message
    }

    // ---------------- 面板 ----------------

    fun refreshPanel() {
        viewModelScope.launch {
            _panel.value = _panel.value.copy(loading = _panel.value.data == null, error = null)
            val result = repo.refreshPanel()
            if (result is ApiResult.Ok) {
                _panel.value = UiState(data = result.value)
            } else {
                val cached = repo.cachedPanel()
                _panel.value = UiState(
                    data = cached ?: _panel.value.data,
                    error = errorText(result as ApiResult.Err),
                    stale = cached != null
                )
            }
        }
    }

    fun refreshQuotaOnly() {
        viewModelScope.launch {
            val result = repo.refreshQuota()
            if (result is ApiResult.Ok) {
                val panel = _panel.value.data
                    ?: return@launch
                _panel.value = _panel.value.copy(data = panel.copy(quota = result.value))
            }
        }
    }

    // ---------------- 任务 ----------------

    fun selectFilter(filter: TaskFilter) {
        if (_filter.value == filter) {
            loadTasks(filter)
            return
        }
        _filter.value = filter
        _tasks.value = UiState(data = repo.cachedTasks(filter), loading = true)
        loadTasks(filter)
    }

    fun loadTasks(filter: TaskFilter = _filter.value) {
        viewModelScope.launch {
            val cached = repo.cachedTasks(filter)
            if (_tasks.value.data == null) {
                _tasks.value = UiState(data = cached, loading = true)
            } else {
                _tasks.value = _tasks.value.copy(loading = true, error = null)
            }
            val result = repo.loadTasks(filter)
            if (result is ApiResult.Ok) {
                if (_filter.value == filter) _tasks.value = UiState(data = result.value)
            } else {
                if (_filter.value == filter) {
                    _tasks.value = UiState(
                        data = cached ?: _tasks.value.data,
                        error = errorText(result as ApiResult.Err),
                        stale = cached != null
                    )
                }
            }
        }
    }

    fun openTask(id: String) {
        _taskDetail.value = UiState(data = repo.cachedTask(id), loading = true)
        viewModelScope.launch {
            val task = repo.loadTask(id)
            if (task != null) {
                _taskDetail.value = UiState(data = task)
            } else if (_taskDetail.value.data == null) {
                _taskDetail.value = UiState(error = "任务不存在或已被删除")
            } else {
                _taskDetail.value = _taskDetail.value.copy(loading = false)
            }
        }
    }

    fun createTask(req: CreateTaskRequest, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repo.createTask(req)
            when (result) {
                is ApiResult.Ok -> {
                    postNotice("任务已创建")
                    loadTasks()
                    refreshPanel()
                    onDone(true)
                }
                is ApiResult.Err -> {
                    postNotice(result.message)
                    onDone(false)
                }
            }
        }
    }

    fun patchTask(id: String, req: PatchTaskRequest, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repo.patchTask(id, req)
            when (result) {
                is ApiResult.Ok -> {
                    _taskDetail.value = UiState(data = result.value)
                    postNotice("已保存")
                    loadTasks()
                    refreshPanel()
                    onDone(true)
                }
                is ApiResult.Err -> {
                    postNotice(result.message)
                    onDone(false)
                }
            }
        }
    }

    fun completeTask(task: Task, note: String?, actualMinutes: Int?, onDone: ((Ok: Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val result = repo.completeTask(task, note, actualMinutes)
            when (result) {
                is ApiResult.Ok -> {
                    val rewards = result.value.rewards
                    val expText = if (rewards != null) " +" + rewards.exp + " EXP · +" + rewards.coins + " 积分" else ""
                    postNotice("完成：" + task.title + expText)
                    result.value.task?.let { _taskDetail.value = UiState(data = it) }
                    loadTasks()
                    refreshPanel()
                    onDone?.invoke(true)
                }
                is ApiResult.Err -> {
                    postNotice(result.message)
                    onDone?.invoke(false)
                }
            }
        }
    }

    fun reopenTask(task: Task) {
        viewModelScope.launch {
            when (val result = repo.reopenTask(task.id)) {
                is ApiResult.Ok -> {
                    postNotice("已重开")
                    _taskDetail.value = UiState(data = result.value)
                    loadTasks()
                    refreshPanel()
                }
                is ApiResult.Err -> postNotice(result.message)
            }
        }
    }

    fun postponeTask(task: Task) {
        viewModelScope.launch {
            when (val result = repo.postponeTask(task.id, Clocks.todayPlus(1))) {
                is ApiResult.Ok -> {
                    postNotice("已推迟到明天")
                    loadTasks()
                    refreshPanel()
                }
                is ApiResult.Err -> postNotice(result.message)
            }
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            when (val result = repo.deleteTask(task.id)) {
                is ApiResult.Ok -> {
                    postNotice("任务已删除")
                    loadTasks()
                    refreshPanel()
                }
                is ApiResult.Err -> postNotice(result.message)
            }
        }
    }

    // ---------------- 日记 ----------------

    fun selectDiaryDate(date: String) {
        _diaryDate.value = date
        loadDiary(date)
    }

    fun loadDiary(date: String = _diaryDate.value) {
        _diary.value = UiState(data = repo.cachedDiary(date), loading = true)
        viewModelScope.launch {
            val result = repo.loadDiary(date)
            if (date != _diaryDate.value) return@launch
            when (result) {
                is ApiResult.Ok -> _diary.value = UiState(data = result.value)
                is ApiResult.Err -> _diary.value = UiState(
                    data = repo.cachedDiary(date),
                    error = errorText(result),
                    stale = repo.cachedDiary(date) != null
                )
            }
        }
    }

    fun saveDiary(mood: Int, content: String, tags: List<String>) {
        val date = _diaryDate.value
        viewModelScope.launch {
            val result = repo.saveDiary(date, DiaryPutRequest(mood, content, tags))
            when (result) {
                is ApiResult.Ok -> {
                    _diary.value = UiState(data = result.value)
                    postNotice("日记已保存")
                    loadDiaryHistory()
                }
                is ApiResult.Err -> postNotice(result.message)
            }
        }
    }

    fun loadDiaryHistory() {
        viewModelScope.launch {
            val result = repo.loadDiaryEntries()
            if (result is ApiResult.Ok) _diaryHistory.value = result.value
            else _diaryHistory.value = repo.cache.diaryList() ?: emptyList()
        }
    }

    fun saveDiaryDraft(date: String, content: String) {
        app.getSharedPreferences("kuangren_drafts", Context.MODE_PRIVATE)
            .edit().putString("diary_" + date, content).apply()
    }

    fun diaryDraft(date: String): String {
        return app.getSharedPreferences("kuangren_drafts", Context.MODE_PRIVATE)
            .getString("diary_" + date, "") ?: ""
    }

    // ---------------- 身体 ----------------

    fun refreshBody() {
        viewModelScope.launch {
            _body.value = _body.value.copy(loading = _body.value.data == null, error = null)
            val result = repo.loadWeight(30)
            if (result is ApiResult.Ok) {
                _body.value = UiState(data = result.value)
            } else {
                _body.value = UiState(
                    data = repo.cachedWeight(),
                    error = errorText(result as ApiResult.Err),
                    stale = repo.cachedWeight() != null
                )
            }
        }
    }

    fun saveWeight(date: String, weightKg: Double, bodyFat: Double?, waistCm: Double?, note: String?) {
        viewModelScope.launch {
            val req = WeightPutRequest(weightKg, bodyFat, waistCm, note, "manual")
            when (val result = repo.saveWeight(date, req)) {
                is ApiResult.Ok -> {
                    postNotice("已记录体重 " + weightKg + " kg")
                    refreshBody()
                    refreshPanel()
                }
                is ApiResult.Err -> postNotice(result.message)
            }
        }
    }

    // ---------------- 系统 ----------------

    fun refreshSystem() {
        viewModelScope.launch {
            _system.value = _system.value.copy(loading = _system.value.data == null, error = null)
            val result = repo.refreshSystem()
            if (result is ApiResult.Ok) {
                _system.value = UiState(data = result.value)
            } else {
                _system.value = UiState(
                    data = _system.value.data,
                    error = errorText(result as ApiResult.Err),
                    stale = _system.value.data != null
                )
            }
        }
    }

    fun loadStats(range: String = _statsRange.value) {
        _statsRange.value = range
        viewModelScope.launch {
            _stats.value = UiState(data = repo.cachedStats(range), loading = true)
            val result = repo.loadStats(range)
            if (result is ApiResult.Ok) _stats.value = UiState(data = result.value)
            else _stats.value = UiState(
                data = repo.cachedStats(range),
                error = errorText(result as ApiResult.Err),
                stale = true
            )
        }
    }

    fun loadHeatmap() {
        viewModelScope.launch {
            val result = repo.loadHeatmap()
            if (result is ApiResult.Ok) _heatmap.value = result.value
        }
    }

    fun requestExtension(minutes: Int, reason: String) {
        viewModelScope.launch {
            when (val result = repo.requestExtension(minutes, reason)) {
                is ApiResult.Ok -> postNotice("已提交申请，等呆回复")
                is ApiResult.Err -> postNotice(result.message)
            }
        }
    }

    fun unlockWithQuota(reason: String) {
        viewModelScope.launch {
            repo.unlockQuota(reason)
            refreshPanel()
        }
    }

    // ---------------- 专注 ----------------

    fun startFocus(minutes: Int, countdown: Boolean, taskId: String, taskTitle: String) {
        focusJob?.cancel()
        noisePlayer?.stop()
        val total = if (countdown) minutes * 60 else 0
        _focus.value = FocusUiState(
            active = true,
            running = true,
            countdown = countdown,
            totalSeconds = total,
            remainingSeconds = total,
            elapsedSeconds = 0,
            taskId = taskId,
            taskTitle = taskTitle,
            noise = "off",
            finished = false
        )
        startTicker()
        FocusKeepAliveService.start(app, focusNotificationText(_focus.value))
    }

    private fun focusNotificationText(state: FocusUiState): String {
        val base = if (state.countdown) {
            "剩余 " + clockText(state.remainingSeconds)
        } else {
            "已专注 " + clockText(state.elapsedSeconds)
        }
        val prefix = if (state.running) "" else "已暂停 · "
        val suffix = if (state.taskTitle.isNotBlank()) " · " + state.taskTitle else ""
        return prefix + base + suffix
    }

    private fun clockText(seconds: Int): String =
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    private fun startTicker() {
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            while (_focus.value.active && _focus.value.running) {
                delay(1000)
                val state = _focus.value
                if (!state.active || !state.running) break
                if (state.countdown) {
                    val next = state.remainingSeconds - 1
                    if (next <= 0) {
                        _focus.value = state.copy(remainingSeconds = 0, running = false, finished = true)
                        FocusKeepAliveService.stop(app)
                        onFocusFinished(state)
                        break
                    }
                    _focus.value = state.copy(remainingSeconds = next)
                    if (next % 5 == 0) {
                        FocusKeepAliveService.update(app, focusNotificationText(_focus.value))
                    }
                } else {
                    _focus.value = state.copy(elapsedSeconds = state.elapsedSeconds + 1)
                    if (_focus.value.elapsedSeconds % 5 == 0) {
                        FocusKeepAliveService.update(app, focusNotificationText(_focus.value))
                    }
                }
            }
        }
    }

    fun toggleFocusPause() {
        val state = _focus.value
        if (!state.active) return
        if (state.running) {
            _focus.value = state.copy(running = false)
            noisePlayer?.stop()
            FocusKeepAliveService.update(app, focusNotificationText(_focus.value))
        } else {
            _focus.value = state.copy(running = true)
            if (_focus.value.noise != "off") noisePlayer?.start(_focus.value.noise)
            startTicker()
            FocusKeepAliveService.update(app, focusNotificationText(_focus.value))
        }
    }

    fun abandonFocus() {
        focusJob?.cancel()
        noisePlayer?.stop()
        FocusKeepAliveService.stop(app)
        _focus.value = FocusUiState()
    }

    fun completeFocusEarly() {
        val state = _focus.value
        if (!state.active) return
        focusJob?.cancel()
        noisePlayer?.stop()
        _focus.value = state.copy(running = false, active = false, finished = true)
        FocusKeepAliveService.stop(app)
        onFocusFinished(state)
    }

    private fun onFocusFinished(state: FocusUiState) {
        notifyFocusDone()
        if (state.taskId.isNotBlank()) {
            viewModelScope.launch {
                val task = repo.cachedTask(state.taskId) ?: repo.loadTask(state.taskId)
                if (task != null) {
                    val actual = if (state.countdown) state.totalSeconds / 60 else state.elapsedSeconds / 60
                    completeTask(task, "番茄计时完成", actual.coerceAtLeast(1))
                }
            }
        } else {
            postNotice("专注完成")
        }
    }

    private fun notifyFocusDone() {
        if (!repo.notificationsEnabled) return
        vibrate()
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        manager.notify(
            Notifications.ID_ALERT,
            Notifications.build(app, Notifications.CHANNEL_FOCUS, "专注结束", "时间到。站起来走两步。", ongoing = false)
        )
    }

    private fun vibrate() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1))
        } catch (_: Exception) {
        }
    }

    fun setNoise(kind: String) {
        if (noisePlayer == null) noisePlayer = WhiteNoisePlayer()
        _focus.value = _focus.value.copy(noise = kind)
        if (kind == "off") noisePlayer?.stop() else noisePlayer?.start(kind)
    }

    fun closeFocusFinished() {
        FocusKeepAliveService.stop(app)
        _focus.value = FocusUiState()
    }

    // ---------------- 设置 ----------------

    fun unlockAdvanced() {
        _advancedUnlocked.value = true
    }

    fun setSoftLockEnabled(enabled: Boolean) {
        repo.softLockEnabled = enabled
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        repo.notificationsEnabled = enabled
    }

    fun setPomodoroMinutes(minutes: Int) {
        repo.pomodoroMinutes = minutes
    }

    fun setHeight(cm: Int) {
        repo.heightCm = cm
    }

    fun applyAdvanced(base: String, token: String) {
        repo.applyAdvanced(base, token)
        postNotice("已更新服务器配置")
        refreshPanel()
    }

    fun resetAdvanced() {
        repo.clearAdvanced()
        postNotice("已恢复默认配置")
        refreshPanel()
    }

    fun exportData() {
        viewModelScope.launch {
            when (val result = repo.exportData()) {
                is ApiResult.Ok -> {
                    try {
                        val dir = app.getExternalFilesDir(null) ?: app.filesDir
                        val file = File(dir, "kuangren-export-" + Clocks.today() + "-" + System.currentTimeMillis() + ".json")
                        file.writeText(result.value.toString())
                        postNotice("已导出到 " + file.absolutePath)
                    } catch (e: Exception) {
                        postNotice("导出失败：" + (e.message ?: "写入错误"))
                    }
                }
                is ApiResult.Err -> postNotice(result.message)
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            val ok = repo.sync()
            postNotice(if (ok) "同步完成" else "同步失败，稍后再试")
            refreshPanel()
        }
    }
}