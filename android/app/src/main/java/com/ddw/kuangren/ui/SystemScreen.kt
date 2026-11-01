package com.ddw.kuangren.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddw.kuangren.data.Achievement
import com.ddw.kuangren.data.Event
import com.ddw.kuangren.data.Report
import com.ddw.kuangren.data.SystemBundle
import com.ddw.kuangren.data.formatMinutes
import com.ddw.kuangren.data.shortDate
import com.ddw.kuangren.data.shortDateTime
import com.ddw.kuangren.ui.theme.Holo

@Composable
fun SystemScreen(vm: MainViewModel, onStats: () -> Unit, onSettings: () -> Unit) {
    val state by vm.system.collectAsStateWithLifecycle()
    var expandedReport by remember { mutableStateOf<String?>(null) }
    val bundle: SystemBundle? = state.data

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(title = "系统")
            Spacer(Modifier.weight(1f))
            HoloButton(text = "刷新", onClick = { vm.refreshSystem() }, filled = false, color = Holo.TextMuted)
            Spacer(Modifier.padding(horizontal = 3.dp))
            HoloButton(text = "设置", onClick = onSettings, filled = false)
        }
        Spacer(Modifier.height(12.dp))

        if (state.loading && bundle == null) {
            SkeletonCard(height = 100.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonCard(height = 140.dp)
            return@Column
        }
        if (state.error != null && bundle == null) {
            ErrorCard(message = state.error ?: "加载失败", onRetry = { vm.refreshSystem() })
            return@Column
        }

        SectionHeader(title = "呆的消息")
        Spacer(Modifier.height(8.dp))
        val reports = bundle?.reports.orEmpty()
        if (reports.isEmpty()) {
            EmptyHint(text = "呆还没有留言。")
        } else {
            reports.take(12).forEach { report ->
                ReportCard(
                    report = report,
                    expanded = expandedReport == report.id,
                    onClick = { expandedReport = if (expandedReport == report.id) null else report.id }
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionHeader(title = "事件日志")
        Spacer(Modifier.height(8.dp))
        HoloCard {
            val events = bundle?.events.orEmpty().take(20)
            if (events.isEmpty()) {
                MutedText(text = "暂无事件。", size = 10)
            } else {
                events.forEach { event ->
                    EventRow(event)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionHeader(title = "本周统计", action = "详情 ›", onAction = onStats)
        Spacer(Modifier.height(8.dp))
        val stats = bundle?.stats7
        HoloCard(modifier = Modifier.clickableNoRipple(onStats)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatCell("学习", stats?.let { formatMinutes(it.studyMinutes) } ?: "--", Modifier.weight(1f))
                StatCell("完成", stats?.let { it.tasksDone.toString() } ?: "--", Modifier.weight(1f))
                StatCell("完成率", stats?.let { (it.completionRate * 100).toInt().toString() + "%" } ?: "--", Modifier.weight(1f))
                StatCell("额度", stats?.let { it.quotaUsedMinutes.toString() + "m" } ?: "--", Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionHeader(title = "成就")
        Spacer(Modifier.height(8.dp))
        val achievements = bundle?.achievements.orEmpty()
        if (achievements.isEmpty()) {
            EmptyHint(text = "成就还在路上。")
        } else {
            achievements.chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    rowItems.forEach { achievement ->
                        AchievementCell(achievement, Modifier.weight(1f))
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HoloCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeader(title = "设置")
                    MutedText(text = "通知、番茄时长、软锁、导出、服务器地址", size = 10)
                }
                HoloButton(text = "进入", onClick = onSettings, filled = false)
            }
        }

        Spacer(Modifier.height(16.dp))
        MutedText(
            text = "狂人日记 v1.0.0 · 数据在你自己服务器上",
            size = 9,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReportCard(report: Report, expanded: Boolean, onClick: () -> Unit) {
    HoloCard(active = expanded, modifier = Modifier.clickableNoRipple(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = report.title.ifBlank { "呆的消息" }, color = Holo.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                MonoText(text = shortDateTime(report.createdAt), color = Holo.TextMuted, size = 9)
            }
            MonoText(text = if (expanded) "收起" else "展开", color = Holo.Secondary, size = 9)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = report.content,
            color = Holo.TextMuted,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 3
        )
    }
}

@Composable
private fun EventRow(event: Event) {
    val color = when (event.severity) {
        "danger", "critical", "error" -> Holo.Danger
        "warning", "warn" -> Holo.Warning
        else -> Holo.Primary
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(text = "●", color = color, fontSize = 8.sp, modifier = Modifier.padding(top = 3.dp, end = 6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row {
                Text(text = event.type, color = color, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                MonoText(text = shortDateTime(event.time), color = Holo.TextMuted, size = 8)
            }
            if (event.payloadText.isNotBlank()) {
                MutedText(text = event.payloadText, size = 9, maxLines = 2)
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = Holo.Primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
        MutedText(text = label, size = 8, maxLines = 1)
    }
}

@Composable
private fun AchievementCell(achievement: Achievement, modifier: Modifier = Modifier) {
    HoloCard(modifier = modifier, padding = 9.dp) {
        val color = if (achievement.unlocked) Holo.Primary else Holo.TextMuted.copy(alpha = 0.5f)
        Text(
            text = if (achievement.unlocked) "★" else "☆",
            color = color,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = achievement.title,
            color = color,
            fontSize = 10.sp,
            fontWeight = if (achievement.unlocked) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2
        )
        if (achievement.unlockedAt != null) {
            MutedText(text = shortDate(achievement.unlockedAt), size = 8, maxLines = 1)
        }
    }
}
