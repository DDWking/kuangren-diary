package com.ddw.kuangren.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddw.kuangren.data.Clocks
import com.ddw.kuangren.data.Panel
import com.ddw.kuangren.data.Policy
import com.ddw.kuangren.data.Profile
import com.ddw.kuangren.data.Quota
import com.ddw.kuangren.data.Task
import com.ddw.kuangren.data.formatMinutes
import com.ddw.kuangren.data.lockReasonText
import com.ddw.kuangren.ui.theme.Holo
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelScreen(
    vm: MainViewModel,
    onTaskClick: (String) -> Unit,
    onTasks: () -> Unit,
    onFocus: () -> Unit,
    onDiary: () -> Unit,
    onBody: () -> Unit
) {
    val state by vm.panel.collectAsStateWithLifecycle()
    var showExtension by remember { mutableStateOf(false) }
    val panel = state.data

    PullToRefreshBox(
        isRefreshing = state.loading && panel != null,
        onRefresh = { vm.refreshPanel() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { StatusLine() }
            if (panel == null) {
                item { if (state.error != null) ErrorCard(state.error ?: "", { vm.refreshPanel() }) else SkeletonCard(64.dp) }
                items(listOf(120, 150, 170, 120)) { h -> SkeletonCard(h.dp) }
            } else {
                if (state.error != null) item { ErrorCard(state.error ?: "", { vm.refreshPanel() }) }
                item { ExamCountdownCard(panel) }
                item { LevelCard(panel.profile) }
                item { RadarCard(panel.profile) }
                item { QuotaCard(panel.quota) { showExtension = true } }
                item {
                    TodayTasksCard(
                        tasks = panel.tasks.today,
                        onTaskClick = onTaskClick,
                        onTasks = onTasks,
                        onComplete = { task -> vm.completeTask(task, null, null) }
                    )
                }
                item { OneLineCard(panel) }
                item { QuickActions(onFocus = onFocus, onDiary = onDiary, onBody = onBody) }
                item { Spacer(Modifier.height(6.dp)) }
            }
        }
    }

    if (showExtension) {
        ExtensionDialog(vm = vm, onClose = { showExtension = false })
    }
}

@Composable
private fun StatusLine() {
    val now = OffsetDateTime.now(Clocks.zone)
    val shown = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        MonoText(text = "狂人日记 // SYSTEM ONLINE", color = Holo.Primary, size = 9, modifier = Modifier.weight(1f))
        MonoText(text = shown, color = Holo.TextMuted, size = 9)
    }
}

@Composable
private fun ExamCountdownCard(panel: Panel) {
    val date = panel.examDate
    val parts = date.split("-")
    val subtitle = if (parts.size == 3) {
        val day = parts[2].toIntOrNull() ?: 19
        date + " / " + parts[1] + "-" + String.format("%02d", day + 1)
    } else {
        date
    }
    HoloCard {
        MutedText(text = "距 2027 考研初试", size = 10)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            MonoText(text = panel.examDays.toString(), color = Holo.Primary, size = 46, bold = true)
            Spacer(Modifier.width(8.dp))
            Text(text = "天", color = Holo.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                MonoText(text = "EXAM", color = Holo.Secondary, size = 10)
                MonoText(text = subtitle, color = Holo.TextMuted, size = 9)
            }
        }
    }
}

@Composable
private fun LevelCard(profile: Profile) {
    HoloCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LevelRing(level = profile.level, progress = profile.expFraction, size = 92.dp)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = profile.title, color = Holo.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                MutedText(text = profile.name + " · 等级 " + profile.level, size = 10)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    MonoText(text = "EXP " + profile.exp + " / " + profile.expNext, color = Holo.Primary, size = 11, bold = true)
                    Spacer(Modifier.weight(1f))
                    MonoText(text = "COIN " + profile.coins, color = Holo.Warning, size = 10)
                }
                Spacer(Modifier.height(5.dp))
                HoloProgress(progress = profile.expFraction, segments = 12)
                Spacer(Modifier.height(8.dp))
                MutedText(
                    text = "连续 " + profile.streak + " 天 · 累计学习 " + formatMinutes(profile.totalStudyMinutes),
                    size = 10
                )
            }
        }
    }
}

@Composable
private fun RadarCard(profile: Profile) {
    HoloCard {
        SectionHeader(title = "五维属性")
        Spacer(Modifier.height(6.dp))
        RadarChart(
            values = profile.attributes.values(),
            labels = listOf("智力", "体力", "意志", "专注", "秩序")
        )
        if (profile.attributes.values().all { it <= 0 }) {
            Spacer(Modifier.height(4.dp))
            MutedText(text = "还没有数据。完成第一个任务，呆会开始记。", size = 10)
        }
    }
}

private fun inCurfew(policy: Policy?): Boolean {
    if (policy == null) return false
    return try {
        val now = LocalTime.now(Clocks.zone)
        val start = LocalTime.parse(policy.curfewStart)
        val end = LocalTime.parse(policy.curfewEnd)
        if (start <= end) !now.isBefore(start) && now.isBefore(end)
        else !now.isBefore(start) || now.isBefore(end)
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun QuotaCard(quota: Quota, onRequest: () -> Unit) {
    val curfew = inCurfew(quota.policy)
    val statusText: String
    val statusColor: androidx.compose.ui.graphics.Color
    when {
        quota.locked -> {
            statusText = "锁定中"
            statusColor = Holo.Danger
        }
        curfew -> {
            statusText = "宵禁时段"
            statusColor = Holo.Warning
        }
        quota.remainingMinutes <= 0 -> {
            statusText = "额度已用尽"
            statusColor = Holo.Danger
        }
        else -> {
            statusText = "额度可用"
            statusColor = Holo.Primary
        }
    }
    HoloCard(active = quota.locked) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuotaRing(
                remaining = quota.remainingMinutes,
                total = quota.totalMinutes,
                locked = quota.locked,
                size = 112.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                MonoText(text = statusText, color = statusColor, size = 13, bold = true)
                Spacer(Modifier.height(5.dp))
                MutedText(
                    text = "已用 " + quota.usedMinutes + " · 基础 " + quota.baseMinutes + " · 奖励 " + quota.bonusMinutes,
                    size = 10,
                    maxLines = 2
                )
                if (quota.lockReason != null) {
                    Spacer(Modifier.height(3.dp))
                    MutedText(text = "原因：" + lockReasonText(quota.lockReason), size = 9, maxLines = 2)
                }
                if (quota.forcedTighten) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "强制收紧" + (quota.tightenReason?.let { "：" + it } ?: ""),
                        color = Holo.Warning,
                        fontSize = 9.sp,
                        maxLines = 2
                    )
                }
                if (quota.violationsToday > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text(text = "今日违规 " + quota.violationsToday + " 次", color = Holo.Danger, fontSize = 9.sp)
                }
                Spacer(Modifier.height(8.dp))
                HoloButton(text = "申请延时", onClick = onRequest, filled = false)
            }
        }
    }
}

@Composable
private fun TodayTasksCard(
    tasks: List<Task>,
    onTaskClick: (String) -> Unit,
    onTasks: () -> Unit,
    onComplete: (Task) -> Unit
) {
    HoloCard {
        SectionHeader(title = "今日任务", action = "查看全部", onAction = onTasks)
        Spacer(Modifier.height(6.dp))
        if (tasks.isEmpty()) {
            EmptyHint(text = "今天没有任务。去跟呆说一句，或者自己加一个。")
        } else {
            tasks.take(5).forEach { task ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DifficultyBars(level = task.difficulty)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f).clickable { onTaskClick(task.id) }) {
                        Text(
                            text = task.title,
                            color = if (task.isDone) Holo.TextMuted else Holo.Text,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                        MutedText(text = task.metaLine, size = 9, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    CircleCheck(done = task.isDone, onClick = { if (!task.isDone) onComplete(task) })
                }
            }
        }
    }
}

@Composable
private fun OneLineCard(panel: Panel) {
    val line = panel.notifications.firstOrNull()?.let { notification ->
        (notification.title + " " + notification.text).trim()
    }?.takeIf { it.isNotBlank() } ?: "今天也没有人来救你。很好。"
    HoloCard {
        SectionHeader(title = "今日一句")
        Spacer(Modifier.height(6.dp))
        Text(text = line, color = Holo.Text, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 3)
    }
}

@Composable
private fun QuickActions(onFocus: () -> Unit, onDiary: () -> Unit, onBody: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HoloButton(text = "开始专注", onClick = onFocus, modifier = Modifier.weight(1f))
        HoloButton(text = "写日记", onClick = onDiary, modifier = Modifier.weight(1f), filled = false)
        HoloButton(text = "记体重", onClick = onBody, modifier = Modifier.weight(1f), filled = false)
    }
}

@Composable
private fun ExtensionDialog(vm: MainViewModel, onClose: () -> Unit) {
    var minutes by remember { mutableStateOf("30") }
    var reason by remember { mutableStateOf("") }
    val minutesValue = minutes.toIntOrNull()
    val valid = minutesValue != null && minutesValue in 5..60 && reason.isNotBlank()
    HoloDialog(
        title = "申请延时",
        onDismiss = onClose,
        confirmText = "提交申请",
        confirmEnabled = valid,
        onConfirm = {
            if (minutesValue != null && valid) {
                vm.requestExtension(minutesValue, reason.trim())
            }
            onClose()
        }
    ) {
        FieldLabel(text = "时长（分钟，5–60）")
        HoloInput(
            value = minutes,
            onValueChange = { input -> minutes = input.filter { it.isDigit() }.take(2) },
            placeholder = "30",
            keyboardType = KeyboardType.Number
        )
        Spacer(Modifier.height(10.dp))
        FieldLabel(text = "理由")
        HoloInput(
            value = reason,
            onValueChange = { reason = it },
            placeholder = "为什么需要更多时间？",
            singleLine = false,
            minHeight = 64.dp
        )
    }
}
