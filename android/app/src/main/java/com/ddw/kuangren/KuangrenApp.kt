package com.ddw.kuangren

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ddw.kuangren.work.SyncWorker
import java.util.concurrent.TimeUnit

class KuangrenApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        scheduleSync()
    }

    private fun scheduleSync() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork("kuangren_sync", ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (_: Exception) {
        }
    }
}
