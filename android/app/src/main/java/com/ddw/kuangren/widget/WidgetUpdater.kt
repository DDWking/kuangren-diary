package com.ddw.kuangren.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WidgetUpdater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var lastRefresh = 0L

    fun refreshAll(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 1500L) return
        lastRefresh = now
        val app = context.applicationContext
        scope.launch {
            runCatching { PanelWidget().updateAll(app) }
            runCatching { QuotaWidget().updateAll(app) }
            runCatching { LevelWidget().updateAll(app) }
            runCatching { LineWidget().updateAll(app) }
            runCatching { FullWidget().updateAll(app) }
        }
    }
}
