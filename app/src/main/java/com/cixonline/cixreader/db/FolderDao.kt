package com.cixonline.cixreader.db

import androidx.room.*
import com.cixonline.cixreader.models.Folder
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("""
        SELECT id, name, parentId, flags, folder_index, unreadPriority, deletePending, resignPending, markReadRangePending,
        (SELECT COUNT(*) FROM messages WHERE (topicId = folders.id OR topicId IN (SELECT id FROM folders f2 WHERE f2.parentId = folders.id)) AND unread = 1 AND date > :cutoff) as unread
        FROM folders
        ORDER BY folder_index ASC
    """)
    fun getAllWithDynamicUnread(cutoff: Long): Flow<List<Folder>>

    @Query("SELECT * FROM folders ORDER BY folder_index ASC")
    fun getAll(): Flow<List<Folder>>

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

    @Delete
    suspend fun delete(folder: Folder)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: Int)
}
