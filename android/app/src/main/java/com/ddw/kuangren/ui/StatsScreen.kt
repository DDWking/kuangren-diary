package com.ddw.kuangren.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddw.kuangren.data.DailyStat
import com.ddw.kuangren.data.formatMinutes
import com.ddw.kuangren.data.shortDate
import com.ddw.kuangren.ui.theme.Holo

private val ranges = listOf("7d" to "7 天", "30d" to "30 天", "90d" to "90 天")

@Composable
fun StatsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.stats.collectAsStateWithLifecycle()
    val range by vm.statsRange.collectAsStateWithLifecycle()
    val heatmap by vm.heatmap.collectAsStateWithLifecycle()
    val data = state.data

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .navigationBarsPadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HoloButton(text = "← 返回", onClick = onBack, filled = false, color = Holo.TextMuted)
            Spacer(Modifier.weight(1f))
            MonoText(text = "STATS", color = Holo.Secondary, size = 10)
        }
        Spacer(Modifier.height(12.dp))

        SegmentedControl(
            options = ranges.map { it.second },
            selected = ranges.indexOfFirst { it.first == range }.coerceAtLeast(0),
            onSelect = { index ->
                val key = ranges[index].first
                vm.loadStats(key)
            }
        )

        Spacer(Modifier.height(12.dp))
        if (state.loading && data == null) {
            SkeletonCard(height = 100.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonCard(height = 160.dp)
            return@Column
        }
        if (state.error != null && data == null) {
            ErrorCard(message = state.error ?: "加载失败", onRetry = { vm.loadStats(range) })
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("学习时长", formatMinutes(data?.studyMinutes ?: 0), Modifier.weight(1f))
            StatCard("完成任务", (data?.tasksDone ?: 0).toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("完成率", ((data?.completionRate ?: 0.0) * 100).toInt().toString() + "%", Modifier.weight(1f))
            StatCard("额度使用", (data?.quotaUsedMinutes ?: 0).toString() + "m", Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        HoloCard {
            SectionHeader(title = "每日学习")
            Spacer(Modifier.height(10.dp))
            val daily = data?.daily.orEmpty()
            if (daily.isEmpty()) {
                EmptyHint(text = "这段时间没有记录。")
            } else {
                DailyBars(daily)
            }
        }

        Spacer(Modifier.height(12.dp))
        HoloCard {
            SectionHeader(title = "90 天热力图")
            Spacer(Modifier.height(10.dp))
            if (heatmap.isEmpty()) {
                MutedText(text = "暂无热力图数据。", size = 10)
            } else {
                Heatmap(cells = heatmap.map { it.date to it.value })
            }
        }

        Spacer(Modifier.height(12.dp))
        HoloCard {
            SectionHeader(title = "身体与连续")
            Spacer(Modifier.height(8.dp))
            KeyValueRow(key = "连续记录", value = (data?.streak ?: 0).toString() + " 天")
            val change = data?.weightChange
            if (change != null) {
                KeyValueRow(
                    key = "体重变化",
                    value = (if (change > 0) "+" else "") + String.format(java.util.Locale.US, "%.1f", change) + " kg",
                    valueColor = if (change <= 0) Holo.Success else Holo.Warning
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    HoloCard(modifier = modifier, padding = 12.dp) {
        MutedText(text = label, size = 9)
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = Holo.Primary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

@Composable
private fun DailyBars(daily: List<DailyStat>) {
    val maxMinutes = daily.maxOfOrNull { it.studyMinutes }?.coerceAtLeast(1) ?: 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        daily.forEach { day ->
            val fraction = (day.studyMinutes.toFloat() / maxMinutes.toFloat()).coerceIn(0f, 1f)
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((100 * fraction).dp)
                        .background(
                            if (fraction > 0f) Holo.Primary.copy(alpha = 0.35f + 0.55f * fraction)
                            else Holo.SurfaceAlt
                        )
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val first = daily.firstOrNull()?.date
        val last = daily.lastOrNull()?.date
        if (first != null) MonoText(text = shortDate(first), color = Holo.TextMuted, size = 8)
        if (last != null) MonoText(text = shortDate(last), color = Holo.TextMuted, size = 8)
    }
}