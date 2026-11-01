package com.ddw.kuangren

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.ddw.kuangren.lock.SoftLockService
import com.ddw.kuangren.ui.KuangrenRoot
import com.ddw.kuangren.ui.MainViewModel
import com.ddw.kuangren.ui.theme.KuangrenTheme
import com.ddw.kuangren.widget.WidgetUpdater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.ensureChannels(this)
        pendingRoute = intent?.getStringExtra("open_tab")
        setContent {
            KuangrenTheme {
                KuangrenRoot(vm = vm, initialRoute = pendingRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra("open_tab")
    }

    override fun onResume() {
        super.onResume()
        if (vm.repo.softLockEnabled) SoftLockService.start(this)
        lifecycleScope.launch {
            try {
                WidgetUpdater.refreshAll(applicationContext)
            } catch (_: Exception) {
            }
            vm.refreshQuotaOnly()
        }
    }
}