package com.cixonline.cixreader.db

import androidx.room.*
import com.cixonline.cixreader.models.LogEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: LogEntry)

    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT 500")
    fun getAll(): Flow<List<LogEntry>>

    @Query("DELETE FROM activity_log")
    suspend fun clear()

    @Query("DELETE FROM activity_log WHERE timestamp < :threshold")
    suspend fun deleteOlderThan(threshold: Long)
}
