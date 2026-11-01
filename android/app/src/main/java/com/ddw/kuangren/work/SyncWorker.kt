package com.ddw.kuangren.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ddw.kuangren.KuangrenApp
import com.ddw.kuangren.widget.WidgetUpdater

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext as KuangrenApp
            val ok = app.container.repository.sync()
            WidgetUpdater.refreshAll(applicationContext)
            if (ok) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
