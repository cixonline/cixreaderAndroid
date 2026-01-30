package com.cixonline.cixreader.db

import androidx.room.*
import com.cixonline.cixreader.models.CIXMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE topicId = :topicId ORDER BY date ASC")
    fun getByTopic(topicId: Int): Flow<List<CIXMessage>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Int): CIXMessage?

    @Query("SELECT * FROM messages WHERE remoteId = :remoteId AND topicId = :topicId")
    suspend fun getByRemoteId(remoteId: Int, topicId: Int): CIXMessage?

    @Query("SELECT * FROM messages WHERE topicId = :topicId ORDER BY remoteId DESC LIMIT 1")
    suspend fun getLatestMessage(topicId: Int): CIXMessage?

    @Query("SELECT COUNT(*) FROM messages WHERE topicId = :topicId")
    suspend fun getMessageCount(topicId: Int): Int

    @Query("SELECT * FROM messages ORDER BY date DESC LIMIT :count")
    fun getRecentMessages(count: Int): Flow<List<CIXMessage>>

    @Query("SELECT * FROM messages WHERE unread = 1 AND date > :cutoffDate ORDER BY date ASC LIMIT 1")
    suspend fun getFirstUnreadMessage(cutoffDate: Long): CIXMessage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: CIXMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<CIXMessage>)

    @Update
    suspend fun update(message: CIXMessage)

    @Delete
    suspend fun delete(message: CIXMessage)

    @Query("SELECT COUNT(*) FROM messages WHERE topicId = :topicId AND unread = 1 AND date > :cutoffDate")
    suspend fun getUnreadCount(topicId: Int, cutoffDate: Long): Int

    @Query("UPDATE messages SET unread = 0 WHERE topicId = :topicId AND unread = 1")
    suspend fun markTopicAsRead(topicId: Int)
}
