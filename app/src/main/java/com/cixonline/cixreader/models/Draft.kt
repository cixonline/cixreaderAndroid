package com.cixonline.cixreader.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "drafts")
data class Draft(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val forumName: String,
    val topicName: String,
    val replyToId: Int, // 0 if it's a new message, not a reply
    val body: String,
    val createdAt: Long = System.currentTimeMillis()
)
