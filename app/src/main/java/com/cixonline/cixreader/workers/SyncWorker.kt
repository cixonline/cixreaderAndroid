package com.cixonline.cixreader.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.db.AppDatabase
import com.cixonline.cixreader.repository.SyncRepository
import com.cixonline.cixreader.utils.SettingsManager
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, workerParams: WorkerParameters):
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val settingsManager = SettingsManager(applicationContext)
        if (!settingsManager.isBackgroundSyncEnabled()) {
            return Result.success()
        }

        Log.d("SyncWorker", "Starting background sync (Run attempt: $runAttemptCount)")
        try {
            val database = AppDatabase.getDatabase(applicationContext)
            val syncRepository = SyncRepository(
                api = NetworkClient.api,
                folderDao = database.folderDao(),
                messageDao = database.messageDao(),
                settingsManager = settingsManager
            )
            syncRepository.syncLatestMessages()
            Log.d("SyncWorker", "Background sync finished successfully")

            // Re-schedule itself for 1 minute later if enabled
            // Use APPEND_OR_REPLACE to avoid cancelling the current worker
            if (settingsManager.isBackgroundSyncEnabled()) {
                scheduleNextSync(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
            }

            return Result.success()
        } catch (e: CancellationException) {
            Log.d("SyncWorker", "Background sync cancelled")
            throw e
        } catch (e: Exception) {
            Log.e("SyncWorker", "Background sync failed", e)
            
            return if (runAttemptCount < 3) {
                // WorkManager will handle the retry with backoff
                Result.retry()
            } else {
                // Retries exhausted, schedule next sync to maintain the 1-minute cadence
                if (settingsManager.isBackgroundSyncEnabled()) {
                    scheduleNextSync(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
                }
                Result.failure()
            }
        }
    }

    companion object {
        /**
         * Schedules the next background sync.
         * Default policy is KEEP to avoid interrupting an already running or scheduled sync.
         */
        fun scheduleNextSync(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "CixBackgroundSync",
                policy,
                syncRequest
            )
        }
    }
}
