package com.ddw.kuangren.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.ddw.kuangren.data.WeightEntry
import com.ddw.kuangren.data.WeightResponse
import com.ddw.kuangren.data.shortDate
import com.ddw.kuangren.ui.theme.Holo

@Composable
fun BodyScreen(vm: MainViewModel) {
    val state by vm.body.collectAsStateWithLifecycle()
    var showRecord by remember { mutableStateOf(false) }
    val data = state.data

    LaunchedEffect(Unit) {
        if (data == null) vm.refreshBody()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(title = "身体")
            Spacer(Modifier.weight(1f))
            HoloButton(text = "刷新", onClick = { vm.refreshBody() }, filled = false, color = Holo.TextMuted)
        }
        Spacer(Modifier.height(12.dp))

        if (state.loading && data == null) {
            SkeletonCard(height = 140.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonCard(height = 180.dp)
            return@Column
        }

        val latest = data?.latest
        HoloCard(active = true) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (latest != null) fmt1(latest.weightKg) else "--.-",
                    color = Holo.Primary,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Spacer(Modifier.width(6.dp))
                Text(text = "kg", color = Holo.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.weight(1f))
                val change = data?.change7d
                if (change != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        MutedText(text = "较 7 天前", size = 9)
                        Text(
                            text = (if (change > 0) "+" else "") + fmt1(change),
                            color = if (change <= 0) Holo.Success else Holo.Warning,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            MutedText(
                text = if (latest != null) "最后记录 " + shortDate(latest.date) + " · 目标 85kg" else "还没有记录。先称一次。",
                size = 10
            )
        }

        Spacer(Modifier.height(10.dp))
        HoloCard {
            SectionHeader(title = "30 天趋势")
            Spacer(Modifier.height(8.dp))
            val entries = remember(data) { data?.entries.orEmpty().sortedBy { it.date } }
            WeightChart(
                points = entries.map { it.weightKg },
                labels = if (entries.size >= 2) listOf(shortDate(entries.first().date), shortDate(entries.last().date)) else emptyList(),
                targetKg = 85.0
            )
        }

        Spacer(Modifier.height(10.dp))
        val height = vm.repo.heightCm
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BodyMetric(
                label = "体脂",
                value = latest?.bodyFat?.let { fmt1(it) + "%" } ?: "--",
                modifier = Modifier.weight(1f)
            )
            BodyMetric(
                label = "腰围",
                value = latest?.waistCm?.let { fmt1(it) + "cm" } ?: "--",
                modifier = Modifier.weight(1f)
            )
            BodyMetric(
                label = "BMI",
                value = latest?.let { fmt1(it.weightKg / ((height / 100.0) * (height / 100.0))) } ?: "--",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))
        HoloButton(
            text = "+ 记录身体数据",
            onClick = { showRecord = true },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))
        SectionHeader(title = "历史记录")
        Spacer(Modifier.height(8.dp))
        val history = data?.entries.orEmpty().sortedByDescending { it.date }
        if (history.isEmpty()) {
            EmptyHint(text = "没有记录。第一次称完，记下来就行。")
        } else {
            history.take(20).forEach { entry -> WeightRow(entry) }
        }

        Spacer(Modifier.height(10.dp))
        HoloCard {
            MutedText(text = "体脂秤截图可以直接发微信给呆就行，会帮你记。", size = 10, maxLines = 2)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showRecord) {
        WeightRecordDialog(
            onDismiss = { showRecord = false },
            onSave = { date, weight, fat, waist, note ->
                vm.saveWeight(date, weight, fat, waist, note)
                showRecord = false
            }
        )
    }
}

@Composable
private fun BodyMetric(label: String, value: String, modifier: Modifier = Modifier) {
    HoloCard(modifier = modifier, padding = 10.dp) {
        MutedText(text = label, size = 9)
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = Holo.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

@Composable
private fun WeightRow(entry: WeightEntry) {
    HoloCard(padding = 10.dp, modifier = Modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                MonoText(text = entry.date, color = Holo.TextMuted, size = 9)
                val extra = buildList {
                    entry.bodyFat?.let { add("体脂 " + fmt1(it) + "%") }
                    entry.waistCm?.let { add("腰围 " + fmt1(it) + "cm") }
                    entry.note?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" · ")
                Text(text = extra.ifBlank { "—" }, color = Holo.TextMuted, fontSize = 10.sp, maxLines = 1)
            }
            Text(
                text = fmt1(entry.weightKg) + " kg",
                color = Holo.Primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun WeightRecordDialog(
    onDismiss: () -> Unit,
    onSave: (String, Double, Double?, Double?, String?) -> Unit
) {
    var date by remember { mutableStateOf(Clocks.today()) }
    var weight by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var waist by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    HoloDialog(
        title = "记录身体数据",
        onDismiss = onDismiss,
        confirmText = "保存",
        confirmEnabled = weight.trim().toDoubleOrNull() != null,
        onConfirm = {
            val w = weight.trim().toDoubleOrNull() ?: return@HoloDialog
            onSave(
                date.trim().ifBlank { Clocks.today() },
                w,
                fat.trim().toDoubleOrNull(),
                waist.trim().toDoubleOrNull(),
                note.trim().ifBlank { null }
            )
        }
    ) {
        FieldLabel(text = "日期")
        HoloInput(value = date, onValueChange = { date = it }, placeholder = "2026-11-02")
        Spacer(Modifier.height(8.dp))
        FieldLabel(text = "体重 kg（必填）")
        HoloInput(value = weight, onValueChange = { weight = it }, placeholder = "104.7", keyboardType = KeyboardType.Decimal)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                FieldLabel(text = "体脂 %")
                HoloInput(value = fat, onValueChange = { fat = it }, placeholder = "24.0", keyboardType = KeyboardType.Decimal)
            }
            Column(modifier = Modifier.weight(1f)) {
                FieldLabel(text = "腰围 cm")
                HoloInput(value = waist, onValueChange = { waist = it }, placeholder = "96", keyboardType = KeyboardType.Decimal)
            }
        }
        Spacer(Modifier.height(8.dp))
        FieldLabel(text = "备注")
        HoloInput(value = note, onValueChange = { note = it }, placeholder = "可选")
    }
}

private fun fmt1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)