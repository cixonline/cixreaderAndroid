package com.cixonline.cixreader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cixonline.cixreader.models.CachedProfile

@Dao
interface CachedProfileDao {
    @Query("SELECT * FROM cached_profiles WHERE userName = :userName")
    suspend fun getProfile(userName: String): CachedProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: CachedProfile)

    @Query("DELETE FROM cached_profiles WHERE userName = :userName")
    suspend fun deleteProfile(userName: String)
}
