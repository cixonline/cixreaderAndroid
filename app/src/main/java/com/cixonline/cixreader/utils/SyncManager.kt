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

    /**
     * Triggers a sync immediately. Used for periodic refreshes when the app is active.
     * Respects the background sync setting.
     */
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

    /**
     * Triggers a sync specifically to process pending local changes (like mark-read).
     * This will run even if background sync is disabled, as it's a direct result of user action.
     */
    fun triggerPendingSync() {
        SyncWorker.enqueueImmediateSync(context)
    }

    fun cancelBackgroundSync() {
        WorkManager.getInstance(context).cancelUniqueWork("CixBackgroundSync")
        WorkManager.getInstance(context).cancelUniqueWork("CixImmediateSync")
    }
}
