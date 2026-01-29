package com.cixonline.cixreader.db

import androidx.room.*
import com.cixonline.cixreader.models.Draft
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts ORDER BY createdAt DESC")
    fun getAllDrafts(): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE forumName = :forumName AND topicName = :topicName AND replyToId = :replyToId LIMIT 1")
    suspend fun getDraft(forumName: String, topicName: String, replyToId: Int): Draft?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraft(draft: Draft)

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteDraft(id: Int)

    @Query("DELETE FROM drafts WHERE forumName = :forumName AND topicName = :topicName AND replyToId = :replyToId")
    suspend fun deleteDraftForContext(forumName: String, topicName: String, replyToId: Int)
}
