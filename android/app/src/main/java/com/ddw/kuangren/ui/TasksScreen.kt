package com.ddw.kuangren.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddw.kuangren.data.Clocks
import com.ddw.kuangren.data.CreateTaskRequest
import com.ddw.kuangren.data.PatchTaskRequest
import com.ddw.kuangren.data.Task
import com.ddw.kuangren.data.TaskFilter
import com.ddw.kuangren.ui.theme.Holo

private val categoryOptions = listOf(
    "study" to "学习",
    "body" to "身体",
    "life" to "生活",
    "mind" to "心境",
    "other" to "其他"
)

private val attributeOptions = listOf(
    "intelligence" to "智力",
    "stamina" to "体力",
    "willpower" to "意志",
    "focus" to "专注",
    "order" to "秩序"
)

@Composable
fun TasksScreen(vm: MainViewModel, onTaskClick: (String) -> Unit, onFocus: (Task) -> Unit) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val state by vm.tasks.collectAsStateWithLifecycle()
    var editorVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var menuTask by remember { mutableStateOf<Task?>(null) }
    var pendingComplete by remember { mutableStateOf<Task?>(null) }

    // 首次进入任务 tab 时主动拉取；否则只会一直显示 skeleton（切 tab 不会触发 selectFilter）。
    LaunchedEffect(Unit) {
        if (vm.tasks.value.data == null) vm.loadTasks()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SegmentedControl(
                options = listOf("今日", "本周", "全部", "已完成"),
                selected = TaskFilter.entries.indexOf(filter),
                onSelect = { index -> vm.selectFilter(TaskFilter.entries[index]) },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            )
            val data = state.data
            when {
                data == null -> Column(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.error != null) {
                        ErrorCard(state.error ?: "") { vm.loadTasks() }
                    } else {
                        SkeletonCard(84.dp)
                        SkeletonCard(84.dp)
                        SkeletonCard(84.dp)
                    }
                }
                data.isEmpty() -> EmptyHint(
                    text = if (filter == TaskFilter.DONE) "还没有完成的任务。" else "今天没有任务。去跟呆说一句，或者自己加一个。",
                    modifier = Modifier.padding(top = 40.dp)
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.error != null) {
                        item { ErrorCard(state.error ?: "") { vm.loadTasks() } }
                    }
                    items(data, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onClick = { onTaskClick(task.id) },
                            onLongClick = { menuTask = task },
                            onToggle = {
                                if (task.isDone) {
                                    vm.reopenTask(task)
                                } else if ((task.estimatedMinutes ?: 0) >= 25) {
                                    pendingComplete = task
                                } else {
                                    vm.completeTask(task, null, task.estimatedMinutes)
                                }
                            }
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)) {
            HoloButton(
                text = "+ 新建任务",
                onClick = {
                    editing = null
                    editorVisible = true
                }
            )
        }
    }

    if (editorVisible) {
        TaskEditorSheet(
            initial = editing,
            onDismiss = { editorVisible = false },
            onSave = { create, origin ->
                if (origin == null) {
                    vm.createTask(create) { editorVisible = false }
                } else {
                    vm.patchTask(
                        origin.id,
                        PatchTaskRequest(
                            title = create.title,
                            description = create.description,
                            category = create.category,
                            difficulty = create.difficulty,
                            attribute = create.attribute,
                            dueDate = create.dueDate,
                            estimatedMinutes = create.estimatedMinutes
                        )
                    ) { editorVisible = false }
                }
            }
        )
    }

    menuTask?.let { task ->
        HoloDialog(
            title = task.title,
            onDismiss = { menuTask = null },
            confirmText = "删除",
            confirmColor = Holo.Danger,
            onConfirm = {
                vm.deleteTask(task)
                menuTask = null
            },
            dismissText = "取消"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.isPending) {
                    HoloButton(
                        text = "推迟到明天",
                        onClick = {
                            vm.postponeTask(task)
                            menuTask = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        filled = false,
                        color = Holo.Warning
                    )
                }
                if (task.isDone) {
                    HoloButton(
                        text = "重开任务",
                        onClick = {
                            vm.reopenTask(task)
                            menuTask = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        filled = false
                    )
                }
                if (task.isPending) {
                    HoloButton(
                        text = "开番茄计时",
                        onClick = {
                            menuTask = null
                            onFocus(task)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        filled = false,
                        color = Holo.Secondary
                    )
                }
            }
        }
    }

    pendingComplete?.let { task ->
        Dialog(onDismissRequest = { pendingComplete = null }) {
            HoloCard(active = true, padding = 16.dp) {
                Text(text = task.title, color = Holo.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                MutedText(text = "直接完成，还是开番茄计时？", size = 12, maxLines = 2)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoloButton(
                        text = "取消",
                        onClick = { pendingComplete = null },
                        modifier = Modifier.weight(1f),
                        filled = false,
                        color = Holo.TextMuted
                    )
                    HoloButton(
                        text = "番茄",
                        onClick = {
                            pendingComplete = null
                            onFocus(task)
                        },
                        modifier = Modifier.weight(1f),
                        filled = false,
                        color = Holo.Secondary
                    )
                    HoloButton(
                        text = "直接完成",
                        onClick = {
                            vm.completeTask(task, null, task.estimatedMinutes)
                            pendingComplete = null
                        },
                        modifier = Modifier.weight(1.2f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(task: Task, onClick: () -> Unit, onLongClick: () -> Unit, onToggle: () -> Unit) {
    val categoryColor = Holo.categoryColor(task.category)
    HoloCard(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .background(categoryColor)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    color = if (task.isDone) Holo.TextMuted else Holo.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MutedText(text = task.metaLine, size = 10, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    TagChip(text = task.categoryLabel, selected = false, color = categoryColor)
                }
                if (!task.description.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    MutedText(text = task.description ?: "", size = 9, maxLines = 1)
                }
            }
            Spacer(Modifier.width(10.dp))
            CircleCheck(done = task.isDone, onClick = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    initial: Task?,
    onDismiss: () -> Unit,
    onSave: (CreateTaskRequest, Task?) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "study") }
    var difficulty by remember { mutableStateOf(initial?.difficulty ?: 3) }
    var attribute by remember { mutableStateOf(initial?.attribute ?: "intelligence") }
    var minutes by remember {
        mutableStateOf(initial?.estimatedMinutes?.toString() ?: "45")
    }
    var dueDate by remember { mutableStateOf(initial?.dueDate ?: Clocks.today()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val valid = title.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Holo.Surface,
        contentColor = Holo.Text
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (initial == null) "新建任务" else "编辑任务",
                color = Holo.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(14.dp))
            FieldLabel(text = "标题（必填）")
            HoloInput(value = title, onValueChange = { title = it }, placeholder = "例如：数学真题一套")
            Spacer(Modifier.height(12.dp))
            FieldLabel(text = "描述")
            HoloInput(
                value = description,
                onValueChange = { description = it },
                placeholder = "可选",
                singleLine = false,
                minHeight = 60.dp
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel(text = "分类")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                categoryOptions.forEach { option ->
                    TagChip(
                        text = option.second,
                        selected = category == option.first,
                        color = Holo.categoryColor(option.first),
                        onClick = { category = option.first }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FieldLabel(text = "难度")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { value ->
                    TagChip(
                        text = value.toString(),
                        selected = difficulty == value,
                        color = if (value >= 4) Holo.Warning else Holo.Primary,
                        onClick = { difficulty = value }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FieldLabel(text = "属性")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                attributeOptions.forEach { option ->
                    TagChip(
                        text = option.second,
                        selected = attribute == option.first,
                        color = Holo.Secondary,
                        onClick = { attribute = option.first }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FieldLabel(text = "预计分钟")
            HoloInput(
                value = minutes,
                onValueChange = { input -> minutes = input.filter { it.isDigit() }.take(4) },
                placeholder = "45",
                keyboardType = KeyboardType.Number
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel(text = "截止日期")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagChip(text = "今天", selected = dueDate == Clocks.today(), onClick = { dueDate = Clocks.today() })
                TagChip(text = "明天", selected = dueDate == Clocks.todayPlus(1), onClick = { dueDate = Clocks.todayPlus(1) })
            }
            Spacer(Modifier.height(8.dp))
            HoloInput(value = dueDate, onValueChange = { dueDate = it }, placeholder = "2026-11-02")
            Spacer(Modifier.height(18.dp))
            HoloButton(
                text = if (initial == null) "创建" else "保存",
                onClick = {
                    onSave(
                        CreateTaskRequest(
                            title = title.trim(),
                            description = description.trim().ifBlank { null },
                            category = category,
                            difficulty = difficulty,
                            attribute = attribute,
                            dueDate = dueDate.trim().ifBlank { null },
                            estimatedMinutes = minutes.toIntOrNull()
                        ),
                        initial
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = valid
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}
