package com.ddw.kuangren.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ddw.kuangren.data.Clocks
import com.ddw.kuangren.ui.theme.Holo
import kotlinx.coroutines.delay

private data class TabDef(val route: String, val label: String)

private val tabs = listOf(
    TabDef("panel", "面板"),
    TabDef("tasks", "任务"),
    TabDef("diary", "日记"),
    TabDef("body", "身体"),
    TabDef("system", "系统")
)

@Composable
fun KuangrenRoot(vm: MainViewModel, initialRoute: String?) {
    val navController = rememberNavController()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val offline by vm.repo.offline.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "panel"
    val mainRoutes = tabs.map { it.route }
    val showTabs = mainRoutes.contains(currentRoute)

    LaunchedEffect(initialRoute) {
        if (!initialRoute.isNullOrBlank() && mainRoutes.contains(initialRoute) && initialRoute != currentRoute) {
            navController.navigate(initialRoute) {
                popUpTo("panel") { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2800)
            vm.consumeNotice()
        }
    }

    HoloBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            OfflineBanner(visible = offline)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                NavHost(
                    navController = navController,
                    startDestination = "panel",
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable("panel") {
                        PanelScreen(
                            vm = vm,
                            onTaskClick = { id -> navController.navigate("task/" + id) },
                            onTasks = { navController.navigate("tasks") },
                            onFocus = { navController.navigate("focus?taskId=&taskTitle=") },
                            onDiary = { navController.navigate("diary") },
                            onBody = { navController.navigate("body") }
                        )
                    }
                    composable("tasks") {
                        TasksScreen(
                            vm = vm,
                            onTaskClick = { id -> navController.navigate("task/" + id) },
                            onFocus = { task ->
                                navController.navigate(
                                    "focus?taskId=" + task.id + "&taskTitle=" + java.net.URLEncoder.encode(task.title, "UTF-8")
                                )
                            }
                        )
                    }
                    composable(
                        route = "task/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType })
                    ) { entry ->
                        val id = entry.arguments?.getString("id") ?: ""
                        TaskDetailScreen(
                            vm = vm,
                            taskId = id,
                            onBack = { navController.popBackStack() },
                            onFocus = { task ->
                                navController.navigate(
                                    "focus?taskId=" + task.id + "&taskTitle=" + java.net.URLEncoder.encode(task.title, "UTF-8")
                                )
                            }
                        )
                    }
                    composable(
                        route = "focus?taskId={taskId}&taskTitle={taskTitle}",
                        arguments = listOf(
                            navArgument("taskId") { type = NavType.StringType; defaultValue = "" },
                            navArgument("taskTitle") { type = NavType.StringType; defaultValue = "" }
                        )
                    ) { entry ->
                        val taskId = entry.arguments?.getString("taskId") ?: ""
                        val rawTitle = entry.arguments?.getString("taskTitle") ?: ""
                        val title = runCatching { java.net.URLDecoder.decode(rawTitle, "UTF-8") }.getOrDefault(rawTitle)
                        FocusScreen(vm = vm, taskId = taskId, taskTitle = title, onBack = { navController.popBackStack() })
                    }
                    composable("diary") { DiaryScreen(vm = vm) }
                    composable("body") { BodyScreen(vm = vm) }
                    composable("system") {
                        SystemScreen(
                            vm = vm,
                            onStats = { navController.navigate("stats") },
                            onSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("stats") {
                        StatsScreen(vm = vm, onBack = { navController.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(vm = vm, onBack = { navController.popBackStack() })
                    }
                }
            }
            if (showTabs) {
                HoloTabBar(current = currentRoute, onSelect = { route ->
                    if (route != currentRoute) {
                        navController.navigate(route) {
                            popUpTo("panel") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                })
            }
        }
        if (notice != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 90.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                HoloCard(active = true, padding = 10.dp) {
                    Text(text = notice ?: "", color = Holo.Text, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun HoloTabBar(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Holo.Surface.copy(alpha = 0.97f))
            .border(width = 0.5.dp, color = Holo.Border)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val active = tab.route == current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(tab.route) }
                    .padding(vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                TabIcon(route = tab.route, active = active)
                Text(
                    text = tab.label,
                    color = if (active) Holo.Primary else Holo.TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TabIcon(route: String, active: Boolean) {
    val color = if (active) Holo.Primary else Holo.TextMuted
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.6.dp.toPx())
        when (route) {
            "panel" -> {
                val cell = w * 0.42f
                val gap = w * 0.10f
                val positions = listOf(
                    Offset(0f, 0f), Offset(cell + gap, 0f),
                    Offset(0f, cell + gap), Offset(cell + gap, cell + gap)
                )
                positions.forEach { p ->
                    drawRect(color = color, topLeft = p, size = Size(cell, cell), style = stroke)
                }
            }
            "tasks" -> {
                for (i in 0 until 3) {
                    val y = h * (0.2f + i * 0.3f)
                    drawLine(color, Offset(w * 0.30f, y), Offset(w * 0.95f, y), strokeWidth = stroke.width)
                    drawLine(color, Offset(w * 0.05f, y - h * 0.06f), Offset(w * 0.18f, y + h * 0.06f), strokeWidth = stroke.width)
                }
            }
            "diary" -> {
                drawRect(color, topLeft = Offset(w * 0.15f, h * 0.1f), size = Size(w * 0.7f, h * 0.8f), style = stroke)
                drawLine(color, Offset(w * 0.45f, h * 0.1f), Offset(w * 0.45f, h * 0.9f), strokeWidth = stroke.width)
                for (i in 0 until 3) {
                    val y = h * (0.3f + i * 0.2f)
                    drawLine(color, Offset(w * 0.52f, y), Offset(w * 0.76f, y), strokeWidth = stroke.width)
                }
            }
            "body" -> {
                val center = Offset(w / 2f, h * 0.56f)
                drawCircle(color = color, radius = w * 0.32f, center = center, style = stroke)
                drawLine(color, center, Offset(w / 2f, h * 0.2f), strokeWidth = stroke.width)
                drawLine(color, center, Offset(w * 0.78f, h * 0.68f), strokeWidth = stroke.width)
                drawCircle(color = color, radius = 1.5.dp.toPx(), center = center)
            }
            else -> {
                drawRect(color, topLeft = Offset(w * 0.1f, h * 0.12f), size = Size(w * 0.8f, h * 0.76f), style = stroke)
                drawLine(color, Offset(w * 0.25f, h * 0.36f), Offset(w * 0.45f, h * 0.55f), strokeWidth = stroke.width)
                drawLine(color, Offset(w * 0.45f, h * 0.55f), Offset(w * 0.25f, h * 0.72f), strokeWidth = stroke.width)
                drawLine(color, Offset(w * 0.55f, h * 0.72f), Offset(w * 0.78f, h * 0.72f), strokeWidth = stroke.width)
            }
        }
    }
}
