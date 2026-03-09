package com.cixonline.cixreader.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "drafts",
    indices = [
        Index(value = ["forumName", "topicName", "replyToId"], unique = true)
    ]
)
data class Draft(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val forumName: String,
    val topicName: String,
    val replyToId: Int, // 0 if it's a new message, not a reply
    val body: String,
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
