package com.cixonline.cixreader.db

import androidx.room.*
import com.cixonline.cixreader.models.Folder
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("""
        SELECT f.id, f.name, f.parentId, f.flags, f.folder_index, f.unreadPriority, f.deletePending, f.resignPending, f.markReadRangePending,
        (CASE 
            WHEN f.parentId = -1 THEN (SELECT COUNT(*) FROM messages m WHERE m.topicId IN (SELECT id FROM folders WHERE parentId = f.id) AND m.unread = 1 AND m.date > :cutoff)
            ELSE (SELECT COUNT(*) FROM messages m WHERE m.topicId = f.id AND m.unread = 1 AND m.date > :cutoff)
        END) as unread
        FROM folders f 
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
