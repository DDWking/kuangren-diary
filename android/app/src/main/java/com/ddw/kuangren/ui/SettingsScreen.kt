package com.ddw.kuangren.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddw.kuangren.BuildConfig
import com.ddw.kuangren.data.maskToken
import com.ddw.kuangren.lock.SoftLockService
import com.ddw.kuangren.ui.theme.Holo

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val advanced by vm.advancedUnlocked.collectAsStateWithLifecycle()
    val repo = vm.repo
    var notifications by remember { mutableStateOf(repo.notificationsEnabled) }
    var pomodoro by remember { mutableStateOf(repo.pomodoroMinutes) }
    var height by remember { mutableStateOf(repo.heightCm) }
    var softLock by remember { mutableStateOf(repo.softLockEnabled) }
    var versionTaps by remember { mutableStateOf(0) }
    var baseInput by remember { mutableStateOf(repo.apiBase()) }
    var tokenInput by remember { mutableStateOf("") }
    var hasUsage by remember { mutableStateOf(hasUsageAccess(context)) }
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val policy = remember { repo.cachedPolicy() }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.postNotice("通知权限被拒绝，倒计时到点将无法提醒")
    }

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
            MonoText(text = "SETTINGS", color = Holo.Secondary, size = 10)
        }
        Spacer(Modifier.height(14.dp))

        HoloCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "通知", color = Holo.Text, fontSize = 13.sp)
                    MutedText(text = "专注结束、软锁提示", size = 9)
                }
                HoloSwitch(checked = notifications, onCheckedChange = {
                    notifications = it
                    vm.setNotificationsEnabled(it)
                })
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                Spacer(Modifier.height(6.dp))
                HoloButton(
                    text = "授予系统通知权限",
                    onClick = { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    filled = false,
                    color = Holo.Warning
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        HoloCard {
            SectionHeader(title = "番茄时长")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 45, 60).forEach { value ->
                    TagChip(
                        text = value.toString() + " 分钟",
                        selected = pomodoro == value,
                        onClick = {
                            pomodoro = value
                            vm.setPomodoroMinutes(value)
                        }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            SectionHeader(title = "身高（用于 BMI）")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(165, 170, 175, 181, 185).forEach { value ->
                    TagChip(
                        text = value.toString() + "cm",
                        selected = height == value,
                        onClick = {
                            height = value
                            vm.setHeight(value)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        HoloCard(active = softLock) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "娱乐软锁", color = Holo.Text, fontSize = 13.sp)
                    MutedText(text = "额度为 0 或宵禁时，用全屏提示盖住娱乐 App", size = 9)
                }
                HoloSwitch(checked = softLock, onCheckedChange = {
                    softLock = it
                    vm.setSoftLockEnabled(it)
                    if (it) {
                        runCatching {
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, SoftLockService::class.java)
                            )
                        }
                        vm.postNotice("软锁已开启")
                    } else {
                        runCatching { context.stopService(Intent(context, SoftLockService::class.java)) }
                        vm.postNotice("软锁已关闭")
                    }
                })
            }
            Spacer(Modifier.height(10.dp))
            PermissionRow(
                title = "使用情况访问权限",
                granted = hasUsage,
                onOpen = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                },
                onRefresh = { hasUsage = hasUsageAccess(context) }
            )
            Spacer(Modifier.height(6.dp))
            PermissionRow(
                title = "悬浮窗权限",
                granted = hasOverlay,
                onOpen = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName)
                            )
                        )
                    }
                },
                onRefresh = { hasOverlay = Settings.canDrawOverlays(context) }
            )
            Spacer(Modifier.height(8.dp))
            MutedText(
                text = "软锁只是提醒，不防卸载，也不防安全模式。真管住自己还是得靠自己。",
                size = 9,
                maxLines = 4
            )
            Spacer(Modifier.height(6.dp))
            MutedText(
                text = "监控应用：" + (policy?.blacklist?.joinToString("、") {
                    it.substringAfterLast(".").ifBlank { it }
                } ?: "游戏、短视频、视频默认列表"),
                size = 9,
                maxLines = 3
            )
        }

        Spacer(Modifier.height(10.dp))
        HoloCard {
            SectionHeader(title = "数据")
            Spacer(Modifier.height(8.dp))
            HoloButton(text = "导出全部数据（GET /export）", onClick = { vm.exportData() }, modifier = Modifier.fillMaxWidth(), filled = false)
            Spacer(Modifier.height(6.dp))
            HoloButton(text = "立即同步", onClick = { vm.syncNow() }, modifier = Modifier.fillMaxWidth(), filled = false)
            Spacer(Modifier.height(6.dp))
            MutedText(text = "导出文件保存在 App 专属外部目录，可在文件管理器里找到。", size = 9, maxLines = 2)
        }

        Spacer(Modifier.height(10.dp))
        HoloCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "服务器（高级）",
                        color = if (advanced) Holo.Primary else Holo.TextMuted,
                        fontSize = 13.sp
                    )
                    MutedText(
                        text = if (advanced) "当前 " + repo.apiBase() else "连点下方版本号 5 次解锁",
                        size = 9,
                        maxLines = 2
                    )
                }
                MonoText(text = if (advanced) "UNLOCKED" else "LOCKED", color = if (advanced) Holo.Success else Holo.TextMuted, size = 9)
            }
            if (advanced) {
                Spacer(Modifier.height(10.dp))
                FieldLabel(text = "API Base")
                HoloInput(value = baseInput, onValueChange = { baseInput = it }, placeholder = "http://159.75.236.90/kuangren-api")
                Spacer(Modifier.height(8.dp))
                FieldLabel(text = "Token（留空则不修改，当前 " + maskToken(repo.maskedToken()) + "）")
                HoloInput(value = tokenInput, onValueChange = { tokenInput = it }, placeholder = "粘贴 token")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoloButton(
                        text = "保存",
                        onClick = {
                            vm.applyAdvanced(baseInput.trim(), tokenInput.trim())
                            tokenInput = ""
                        },
                        modifier = Modifier.weight(1f)
                    )
                    HoloButton(
                        text = "恢复默认",
                        onClick = {
                            vm.resetAdvanced()
                            baseInput = repo.apiBase()
                            tokenInput = ""
                        },
                        modifier = Modifier.weight(1f),
                        filled = false,
                        color = Holo.TextMuted
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        HoloCard {
            SectionHeader(title = "关于")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "狂人日记 v" + BuildConfig.VERSION_NAME,
                color = Holo.Primary,
                fontSize = 13.sp,
                modifier = Modifier.clickableNoRipple {
                    versionTaps += 1
                    if (versionTaps >= 5) vm.unlockAdvanced()
                }
            )
            Spacer(Modifier.height(4.dp))
            MutedText(text = "设备 ID " + BuildConfig.DEVICE_ID + " · 数据在你自己服务器上", size = 9, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            MutedText(text = "系统人格「呆」：朋友，不是教练。呆说短句，不说教。", size = 9, maxLines = 2)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onOpen: () -> Unit, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Holo.Text, fontSize = 12.sp)
            MutedText(text = if (granted) "已授予" else "未授予", size = 9)
        }
        if (granted) {
            MonoText(text = "OK", color = Holo.Success, size = 9)
        } else {
            HoloButton(text = "去授权", onClick = onOpen, filled = false, color = Holo.Warning)
            Spacer(Modifier.padding(horizontal = 3.dp))
            HoloButton(text = "已授权", onClick = onRefresh, filled = false, color = Holo.TextMuted)
        }
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}