package com.ddw.kuangren.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddw.kuangren.data.Task
import com.ddw.kuangren.data.formatMinutes
import com.ddw.kuangren.data.shortDateTime
import com.ddw.kuangren.ui.theme.Holo

@Composable
fun TaskDetailScreen(
    vm: MainViewModel,
    taskId: String,
    onBack: () -> Unit,
    onFocus: (Task) -> Unit
) {
    val state by vm.taskDetail.collectAsStateWithLifecycle()
    LaunchedEffect(taskId) { vm.openTask(taskId) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HoloButton(text = "← 返回", onClick = onBack, filled = false, color = Holo.TextMuted)
            Spacer(Modifier.weight(1f))
            if (state.data?.isDone == true) {
                TagChip(text = "已完成", selected = true, color = Holo.Success)
            } else if (state.data?.isPending == true) {
                TagChip(text = "进行中", selected = true)
            }
        }
        Spacer(Modifier.height(14.dp))

        val task = state.data
        if (task == null) {
            if (state.error != null) {
                ErrorCard(state.error ?: "") { vm.openTask(taskId) }
            } else {
                SkeletonCard(120.dp)
                Spacer(Modifier.height(10.dp))
                SkeletonCard(160.dp)
            }
            return@Column
        }

        Text(
            text = task.title,
            color = if (task.isDone) Holo.TextMuted else Holo.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TagChip(text = task.categoryLabel, selected = true, color = Holo.categoryColor(task.category))
            TagChip(text = task.attributeLabel, selected = true, color = Holo.Secondary)
            DifficultyBars(level = task.difficulty, height = 14.dp)
        }
        Spacer(Modifier.height(6.dp))
        MutedText(
            text = task.metaLine + (task.dueDate?.let { " · 截止 " + it } ?: ""),
            size = 10,
            maxLines = 2
        )

        Spacer(Modifier.height(14.dp))
        HoloCard {
            SectionHeader(title = "描述")
            Spacer(Modifier.height(6.dp))
            Text(
                text = task.description?.takeIf { it.isNotBlank() } ?: "没有描述。",
                color = Holo.Text,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }

        Spacer(Modifier.height(10.dp))
        HoloCard {
            SectionHeader(title = "情报")
            Spacer(Modifier.height(6.dp))
            KeyValueRow(key = "预计", value = task.estimatedMinutes?.let { formatMinutes(it) } ?: "未设置")
            KeyValueRow(key = "实际", value = task.actualMinutes?.let { formatMinutes(it) } ?: "未记录")
            KeyValueRow(key = "EXP", value = "+" + task.expReward, valueColor = Holo.Primary)
            KeyValueRow(key = "积分", value = "+" + task.coinReward, valueColor = Holo.Warning)
            KeyValueRow(key = "创建", value = shortDateTime(task.createdAt))
            if (task.isDone) {
                KeyValueRow(key = "完成于", value = shortDateTime(task.completedAt), valueColor = Holo.Success)
            }
            if (!task.completionNote.isNullOrBlank()) {
                KeyValueRow(key = "完成备注", value = task.completionNote ?: "")
            }
        }

        Spacer(Modifier.height(16.dp))
        if (task.isPending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                HoloButton(
                    text = "开始番茄",
                    onClick = { onFocus(task) },
                    modifier = Modifier.weight(1f),
                    filled = false,
                    color = Holo.Secondary
                )
                HoloButton(
                    text = "完成任务",
                    onClick = { vm.completeTask(task, null, task.estimatedMinutes) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                HoloButton(
                    text = "推迟到明天",
                    onClick = { vm.postponeTask(task) },
                    modifier = Modifier.weight(1f),
                    filled = false,
                    color = Holo.Warning
                )
                HoloButton(
                    text = "删除任务",
                    onClick = {
                        vm.deleteTask(task)
                        onBack()
                    },
                    modifier = Modifier.weight(1f),
                    filled = false,
                    color = Holo.Danger
                )
            }
        } else {
            HoloButton(
                text = "重开任务",
                onClick = { vm.reopenTask(task) },
                modifier = Modifier.fillMaxWidth(),
                filled = false
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}