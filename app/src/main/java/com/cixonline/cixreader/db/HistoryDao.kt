package com.cixonline.cixreader.db

import androidx.room.*
import com.cixonline.cixreader.models.HistoryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    @Query("SELECT * FROM message_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM message_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): HistoryEntry?

    @Query("DELETE FROM message_history WHERE id NOT IN (SELECT id FROM message_history ORDER BY timestamp DESC LIMIT 1000)")
    suspend fun prune()

    @Query("DELETE FROM message_history")
    suspend fun clear()
}
