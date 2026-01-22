package com.cixonline.cixreader.db

import androidx.room.*
import com.cixonline.cixreader.models.DirForum
import kotlinx.coroutines.flow.Flow

@Dao
interface DirForumDao {
    @Query("SELECT * FROM dir_forums ORDER BY name ASC")
    fun getAll(): Flow<List<DirForum>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(forums: List<DirForum>)

    @Query("DELETE FROM dir_forums")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM dir_forums")
    suspend fun getCount(): Int
}
