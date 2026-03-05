package com.cixonline.cixreader.utils

import android.content.Context
import androidx.work.*
import com.cixonline.cixreader.workers.SyncWorker

class SyncManager(private val context: Context, private val settingsManager: SettingsManager) {

    fun handleSyncStateChange(enabled: Boolean) {
        if (enabled) {
            SyncWorker.scheduleNextSync(context)
        } else {
            cancelBackgroundSync()
        }
    }

    fun triggerImmediateSync() {
        if (!settingsManager.isBackgroundSyncEnabled()) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "CixImmediateSync",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }

    private fun cancelBackgroundSync() {
        WorkManager.getInstance(context).cancelUniqueWork("CixBackgroundSync")
    }
}
