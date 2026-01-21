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

    @Query("""
        SELECT m.*, t.name as topicName, f.name as forumName 
        FROM messages m
        JOIN folders t ON m.topicId = t.id
        JOIN folders f ON t.parentId = f.id
        ORDER BY m.date DESC LIMIT :count
    """)
    fun getRecentMessages(count: Int): Flow<List<MessageWithFolder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: CIXMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<CIXMessage>)

    @Update
    suspend fun update(message: CIXMessage)

    @Delete
    suspend fun delete(message: CIXMessage)

    @Query("SELECT COUNT(*) FROM messages WHERE topicId = :topicId AND unread = 1")
    suspend fun getUnreadCount(topicId: Int): Int
}

data class MessageWithFolder(
    @Embedded val message: CIXMessage,
    val topicName: String,
    val forumName: String
)
