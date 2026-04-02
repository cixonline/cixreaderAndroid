package com.cixonline.cixreader.repository

import com.cixonline.cixreader.db.LogDao
import com.cixonline.cixreader.models.LogEntry
import com.cixonline.cixreader.utils.SettingsManager
import kotlinx.coroutines.flow.Flow

class LogRepository(
    private val logDao: LogDao,
    private val settingsManager: SettingsManager
) {
    fun getAllLogs(): Flow<List<LogEntry>> = logDao.getAll()

    suspend fun log(message: String, type: String = "INFO") {
        if (settingsManager.isDebugModeEnabled()) {
            logDao.insert(LogEntry(message = message, type = type))
        }
    }

    suspend fun clearLogs() {
        logDao.clear()
    }

    suspend fun deleteOldLogs(hours: Int = 48) {
        val threshold = System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
        logDao.deleteOlderThan(threshold)
    }
}
