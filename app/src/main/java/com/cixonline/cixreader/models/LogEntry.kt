package com.cixonline.cixreader.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activity_log")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val type: String // e.g., "SYNC", "READ_STATUS", "INSERT", "NAVIGATION"
)
