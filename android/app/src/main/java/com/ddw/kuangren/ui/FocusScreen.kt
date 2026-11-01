package com.ddw.kuangren.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddw.kuangren.data.formatMinutes
import com.ddw.kuangren.ui.theme.Holo

@Composable
fun FocusScreen(vm: MainViewModel, taskId: String, taskTitle: String, onBack: () -> Unit) {
    val state by vm.focus.collectAsStateWithLifecycle()
    var minutes by remember { mutableStateOf(vm.repo.pomodoroMinutes) }
    var title by remember { mutableStateOf(taskTitle) }
    var countdown by remember { mutableStateOf(true) }
    var noise by remember { mutableStateOf("off") }
    val preset = listOf(25, 45, 60)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .navigationBarsPadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HoloButton(text = "← 返回", onClick = onBack, filled = false, color = Holo.TextMuted)
            Spacer(Modifier.weight(1f))
            MonoText(text = "FOCUS", color = Holo.Secondary, size = 11, bold = true)
        }
        Spacer(Modifier.height(18.dp))

        when {
            state.finished -> {
                HoloCard(active = true) {
                    Text(text = "时间到。", color = Holo.Primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    MutedText(text = "站起来走两步。别急着滑手机。", size = 12, maxLines = 2)
                    Spacer(Modifier.height(16.dp))
                    HoloButton(
                        text = "收下",
                        onClick = {
                            vm.closeFocusFinished()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            !state.active -> {
                HoloCard {
                    SectionHeader(title = "准备专注")
                    Spacer(Modifier.height(12.dp))
                    FieldLabel(text = "任务（可选）")
                    HoloInput(value = title, onValueChange = { title = it }, placeholder = "这一段要做什么？")
                    Spacer(Modifier.height(12.dp))
                    FieldLabel(text = "时长")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        preset.forEach { value ->
                            TagChip(
                                text = value.toString() + " 分钟",
                                selected = minutes == value,
                                onClick = { minutes = value }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TagChip(text = "倒计时", selected = countdown, onClick = { countdown = true })
                        TagChip(text = "正计时", selected = !countdown, onClick = { countdown = false })
                    }
                    Spacer(Modifier.height(14.dp))
                    FieldLabel(text = "白噪音")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TagChip(text = "关", selected = noise == "off", onClick = {
                            noise = "off"
                            vm.setNoise("off")
                        })
                        TagChip(text = "雨声", selected = noise == "rain", onClick = {
                            noise = "rain"
                            vm.setNoise("rain")
                        })
                        TagChip(text = "咖啡馆", selected = noise == "cafe", onClick = {
                            noise = "cafe"
                            vm.setNoise("cafe")
                        })
                        TagChip(text = "风扇", selected = noise == "fan", onClick = {
                            noise = "fan"
                            vm.setNoise("fan")
                        })
                    }
                    Spacer(Modifier.height(18.dp))
                    HoloButton(
                        text = "开始专注",
                        onClick = {
                            vm.startFocus(minutes, countdown, taskId, title.trim())
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            else -> {
                val total = if (state.countdown) state.totalSeconds.coerceAtLeast(1) else 0
                val progress = if (state.countdown) {
                    ((total - state.remainingSeconds).toFloat() / total.toFloat()).coerceIn(0f, 1f)
                } else {
                    ((state.elapsedSeconds % 3600).toFloat() / 3600f).coerceIn(0f, 1f)
                }
                val shownSeconds = if (state.countdown) state.remainingSeconds else state.elapsedSeconds
                Box(modifier = Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(250.dp)) {
                        val stroke = 10.dp.toPx()
                        val inset = stroke / 2f
                        drawArc(
                            color = Holo.Border,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke)
                        )
                        drawArc(
                            brush = Brush.sweepGradient(listOf(Holo.Primary, Holo.Secondary, Holo.Primary)),
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = formatClock(shownSeconds),
                            color = Holo.Primary,
                            fontSize = 52.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        MutedText(
                            text = if (state.taskTitle.isBlank()) "专注中" else state.taskTitle,
                            size = 12,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(4.dp))
                        MonoText(
                            text = if (state.running) "RUNNING" else "PAUSED",
                            color = if (state.running) Holo.Success else Holo.Warning,
                            size = 9
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HoloButton(
                        text = if (state.running) "暂停" else "继续",
                        onClick = { vm.toggleFocusPause() },
                        modifier = Modifier.weight(1f),
                        filled = false,
                        color = Holo.Warning
                    )
                    HoloButton(
                        text = "完成",
                        onClick = {
                            vm.completeFocusEarly()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                HoloButton(
                    text = "放弃",
                    onClick = {
                        vm.abandonFocus()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = false,
                    color = Holo.Danger
                )
                Spacer(Modifier.height(14.dp))
                HoloCard {
                    MutedText(
                        text = if (state.countdown) {
                            "到点会震动并通知。计时期间可以退到后台，通知栏会一直显示。"
                        } else {
                            "正计时中。完成时按实际专注 " + formatMinutes(state.elapsedSeconds / 60) + " 记录。"
                        },
                        size = 10,
                        maxLines = 3
                    )
                    if (state.noise != "off") {
                        Spacer(Modifier.height(6.dp))
                        MonoText(
                            text = "NOISE: " + when (state.noise) {
                                "rain" -> "雨声"
                                "cafe" -> "咖啡馆"
                                else -> "风扇"
                            },
                            color = Holo.Secondary,
                            size = 10
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatClock(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val m = safe / 60
    val s = safe % 60
    return String.format("%02d:%02d", m, s)
}