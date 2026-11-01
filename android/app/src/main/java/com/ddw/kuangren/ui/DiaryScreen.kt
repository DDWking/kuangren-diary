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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddw.kuangren.data.Clocks
import com.ddw.kuangren.data.DiaryEntry
import com.ddw.kuangren.ui.theme.Holo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

private val moodIcons = listOf("▁", "▂", "▄", "▆", "█")

@Composable
fun DiaryScreen(vm: MainViewModel) {
    val date by vm.diaryDate.collectAsStateWithLifecycle()
    val state by vm.diary.collectAsStateWithLifecycle()
    val history by vm.diaryHistory.collectAsStateWithLifecycle()
    var mood by remember { mutableStateOf(3) }
    var content by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagInput by remember { mutableStateOf("") }
    var loadedDate by remember { mutableStateOf("") }

    LaunchedEffect(date) { vm.loadDiary(date) }

    LaunchedEffect(date, state.data, state.loading) {
        // 首帧 loading=true 时 history 数据尚未返回，不能立刻置 loadedDate，
        // 否则网络返回的已有日记不会再回填输入框。
        if (loadedDate != date && !state.loading) {
            val entry = state.data
            mood = entry?.mood ?: 3
            content = entry?.content?.ifBlank { vm.diaryDraft(date) } ?: vm.diaryDraft(date)
            tags = entry?.tags ?: emptyList()
            loadedDate = date
        }
    }

    LaunchedEffect(date) {
        snapshotFlow { content }
            .distinctUntilChanged()
            .collect { latest ->
                delay(700)
                if (loadedDate == date) vm.saveDiaryDraft(date, latest)
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HoloButton(
                text = "←",
                onClick = { vm.selectDiaryDate(Clocks.dateMinusSince(date, 1)) },
                filled = false,
                color = Holo.TextMuted
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MonoText(text = date, color = Holo.Primary, size = 14, bold = true)
                MutedText(text = if (date == Clocks.today()) "今天" else "过去的某一天", size = 9)
            }
            Spacer(Modifier.weight(1f))
            HoloButton(
                text = "→",
                onClick = { vm.selectDiaryDate(Clocks.datePlusSince(date, 1)) },
                filled = false,
                color = if (date >= Clocks.today()) Holo.Border else Holo.TextMuted,
                enabled = date < Clocks.today()
            )
        }
        Spacer(Modifier.height(16.dp))

        HoloCard {
            SectionHeader(title = "今天发生了什么？不用写给谁看。")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                MutedText(text = "心情", size = 10)
                moodIcons.forEachIndexed { index, icon ->
                    val value = index + 1
                    Text(
                        text = icon,
                        color = if (mood == value) Holo.Primary else Holo.TextMuted.copy(alpha = 0.6f),
                        fontSize = 20.sp,
                        fontWeight = if (mood == value) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .clickableNoRipple { mood = value }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HoloInput(
                value = content,
                onValueChange = { content = it },
                placeholder = "今天发生了什么？不用写给谁看。",
                singleLine = false,
                minHeight = 140.dp
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                tags.forEach { tag ->
                    TagChip(
                        text = tag + " ×",
                        selected = true,
                        color = Holo.Secondary,
                        onClick = { tags = tags - tag }
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    HoloInput(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        placeholder = "标签，回车添加"
                    )
                }
                Spacer(Modifier.width(8.dp))
                HoloButton(
                    text = "添加",
                    onClick = {
                        val tag = tagInput.trim()
                        if (tag.isNotBlank() && !tags.contains(tag)) tags = tags + tag
                        tagInput = ""
                    },
                    filled = false
                )
            }
            Spacer(Modifier.height(12.dp))
            HoloButton(
                text = "保存",
                onClick = { vm.saveDiary(mood, content.trim(), tags) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(10.dp))
        HoloCard(active = state.data?.aiComment != null) {
            SectionHeader(title = "呆的批注")
            Spacer(Modifier.height(8.dp))
            Row {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(42.dp)
                        .background(Holo.Secondary)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = state.data?.aiComment?.takeIf { it.isNotBlank() } ?: "呆还没看。晚上再说。",
                    color = if (state.data?.aiComment != null) Holo.Text else Holo.TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp
                )
            }
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionHeader(title = "过往的日记")
            Spacer(Modifier.height(8.dp))
            history.take(30).forEach { entry ->
                DiaryHistoryRow(entry = entry, onClick = { vm.selectDiaryDate(entry.date) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiaryHistoryRow(entry: DiaryEntry, onClick: () -> Unit) {
    HoloCard(modifier = Modifier.clickableNoRipple(onClick).padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = moodIcons.getOrElse((entry.mood - 1).coerceIn(0, 4)) { "▄" },
                color = Holo.Secondary,
                fontSize = 16.sp
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                MonoText(text = entry.date, color = Holo.TextMuted, size = 9)
                Text(
                    text = entry.content.replace("\n", " ").take(46),
                    color = Holo.Text,
                    fontSize = 12.sp,
                    maxLines = 2
                )
                if (entry.tags.isNotEmpty()) {
                    MutedText(text = entry.tags.joinToString(" "), size = 9)
                }
            }
        }
    }
}
