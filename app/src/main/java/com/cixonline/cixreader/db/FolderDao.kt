package com.cixonline.cixreader.db

import androidx.room.*
import com.cixonline.cixreader.models.Folder
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY folder_index ASC")
    fun getAll(): Flow<List<Folder>>

    @Query("SELECT * FROM folders ORDER BY folder_index ASC")
    suspend fun getAllSync(): List<Folder>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Int): Folder?

    @Query("SELECT * FROM folders WHERE parentId = :parentId ORDER BY folder_index ASC")
    fun getChildren(parentId: Int): Flow<List<Folder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: Folder)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(folders: List<Folder>)

    @Update
    suspend fun update(folder: Folder)

    @Query("UPDATE folders SET unread = MAX(0, unread - 1) WHERE id = :id")
    suspend fun decrementUnread(id: Int)

    @Query("UPDATE folders SET unread = unread + 1 WHERE id = :id")
    suspend fun incrementUnread(id: Int)

    @Query("UPDATE folders SET unread = :unread WHERE id = :id")
    suspend fun setUnread(id: Int, unread: Int)

    @Query("UPDATE folders SET unread = 0 WHERE parentId = :parentId")
    suspend fun zeroUnreadForTopics(parentId: Int)

    @Query("UPDATE folders SET unread = 0 WHERE parentId != -1")
    suspend fun zeroAllTopicUnreads()

    @Query("""
        UPDATE folders 
        SET unread = (
            SELECT COUNT(*) 
            FROM messages 
            WHERE topicId = folders.id 
              AND unread = 1 
              AND body NOT LIKE '%<<withdrawn by author>>%'
              AND body NOT LIKE '%<<withdrawn by moderator>>%'
              AND body NOT LIKE '%<<withdrawn by system administrator>>%'
        ) 
        WHERE id = :topicId
    """)
    suspend fun recalculateTopicUnreadCount(topicId: Int)

    @Query("""
        UPDATE folders 
        SET unread = (
            SELECT COUNT(*) 
            FROM messages 
            WHERE topicId = folders.id 
              AND unread = 1 
              AND body NOT LIKE '%<<withdrawn by author>>%'
              AND body NOT LIKE '%<<withdrawn by moderator>>%'
              AND body NOT LIKE '%<<withdrawn by system administrator>>%'
        ) 
        WHERE parentId != -1 
          AND id IN (SELECT DISTINCT topicId FROM messages)
    """)
    suspend fun recalculateTopicUnreadCounts()

    @Query("""
        UPDATE folders 
        SET unread = MAX(unread, (
            SELECT COUNT(*) 
            FROM messages 
            WHERE topicId = folders.id 
              AND unread = 1 
              AND body NOT LIKE '%<<withdrawn by author>>%'
              AND body NOT LIKE '%<<withdrawn by moderator>>%'
              AND body NOT LIKE '%<<withdrawn by system administrator>>%'
        )) 
        WHERE parentId != -1 
          AND id IN (SELECT DISTINCT topicId FROM messages)
    """)
    suspend fun mergeTopicUnreadCounts()

    @Query("""
        UPDATE folders 
        SET unread = (
            SELECT COALESCE(SUM(unread), 0) 
            FROM folders f2 
            WHERE f2.parentId = folders.id
        ) 
        WHERE parentId = -1
    """)
    suspend fun recalculateForumUnreadCounts()

    @Delete
    suspend fun delete(folder: Folder)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: Int)
}
