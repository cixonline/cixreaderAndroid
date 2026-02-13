package com.cixonline.cixreader.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.db.AppDatabase
import com.cixonline.cixreader.repository.SyncRepository
import com.cixonline.cixreader.utils.SettingsManager

class SyncWorker(appContext: Context, workerParams: WorkerParameters):
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Starting background sync (Run attempt: $runAttemptCount)")
        try {
            val database = AppDatabase.getDatabase(applicationContext)
            val settingsManager = SettingsManager(applicationContext)
            val syncRepository = SyncRepository(
                api = NetworkClient.api,
                folderDao = database.folderDao(),
                messageDao = database.messageDao(),
                settingsManager = settingsManager
            )
            syncRepository.syncLatestMessages()
            Log.d("SyncWorker", "Background sync finished successfully")
            return Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Background sync failed", e)
            // If it's a transient error, retry with backoff. 
            // WorkManager will handle the retry logic based on the configuration.
            return if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
