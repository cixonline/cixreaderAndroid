package com.cixonline.cixreader.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_profiles")
data class CachedProfile(
    @PrimaryKey val userName: String,
    val fullName: String?,
    val location: String?,
    val email: String?,
    val firstOn: String?,
    val lastOn: String?,
    val lastPost: String?,
    val about: String?,
    val resume: String?,
    val mugshotUrl: String?,
    val lastUpdated: Long
)
