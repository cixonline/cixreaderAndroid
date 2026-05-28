package com.cixonline.cixreader.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message_history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val forumName: String,
    val topicName: String,
    val topicId: Int,
    val messageId: Int,
    val timestamp: Long = System.currentTimeMillis()
)
