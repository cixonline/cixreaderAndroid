package com.cixonline.cixreader.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.db.AppDatabase
import com.cixonline.cixreader.repository.LogRepository
import com.cixonline.cixreader.repository.SyncRepository
import com.cixonline.cixreader.utils.SettingsManager
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, workerParams: WorkerParameters):
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val settingsManager = SettingsManager(applicationContext)
        
        // Don't sync if we don't have credentials
        if (!NetworkClient.hasCredentials()) {
            Log.d("SyncWorker", "Skipping sync: no credentials")
            return Result.success()
        }

        // For direct triggers (like marking read), we might want to proceed even if background sync is disabled.
        // But the 1-minute periodic sync should respect the setting.
        // We can distinguish by looking at the tags or input data if needed, but for now let's just 
        // check the setting here.
        val isManualTrigger = tags.contains("ManualTrigger")
        if (!settingsManager.isBackgroundSyncEnabled() && !isManualTrigger) {
            Log.d("SyncWorker", "Skipping periodic sync: background sync disabled")
            return Result.success()
        }

        Log.d("SyncWorker", "Starting sync (Run attempt: $runAttemptCount, Manual: $isManualTrigger)")
        try {
            val database = AppDatabase.getDatabase(applicationContext)
            val logRepository = LogRepository(database.logDao(), settingsManager)
            val syncRepository = SyncRepository(
                api = NetworkClient.api,
                folderDao = database.folderDao(),
                messageDao = database.messageDao(),
                settingsManager = settingsManager,
                logRepository = logRepository
            )
            
            // Delete old logs (48 hours)
            logRepository.deleteOldLogs(12)

            syncRepository.syncLatestMessages()
            Log.d("SyncWorker", "Sync finished successfully")

            // Re-schedule itself for 1 minute later if enabled
            if (settingsManager.isBackgroundSyncEnabled()) {
                scheduleNextSync(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
            }

            return Result.success()
        } catch (e: CancellationException) {
            Log.d("SyncWorker", "Sync cancelled")
            throw e
        } catch (e: Exception) {
            Log.e("SyncWorker", "Sync failed", e)
            
            return if (runAttemptCount < 3) {
                // WorkManager will handle the retry with backoff
                Result.retry()
            } else {
                // Retries exhausted, schedule next sync to maintain the 1-minute cadence if enabled
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

        /**
         * Enqueues an immediate sync to process pending items (like mark-read) as soon as possible.
         */
        fun enqueueImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .addTag("ManualTrigger")
                .setConstraints(constraints)
                .build()

            // We use KEEP here because if a sync is already running, it will process the pending items anyway.
            // If one is scheduled with a delay, we might want to REPLACE it with an immediate one.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "CixBackgroundSync",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
        }
    }
}
