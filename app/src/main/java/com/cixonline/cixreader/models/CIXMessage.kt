package com.cixonline.cixreader.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Ignore
import androidx.room.Index
import java.util.Date

@Entity(
    tableName = "messages",
    indices = [Index(value = ["remoteId", "topicId"], unique = true)]
)
data class CIXMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val remoteId: Int,
    val author: String,
    val body: String,
    val date: Long, // Using timestamp for Room
    val commentId: Int,
    val rootId: Int,
    val topicId: Int,
    val unread: Boolean = true,
    val priority: Boolean = false,
    val starred: Boolean = false,
    val readLocked: Boolean = false,
    val ignored: Boolean = false,
    val readPending: Boolean = false,
    val postPending: Boolean = false,
    val starPending: Boolean = false,
    val withdrawPending: Boolean = false
) {
    @Ignore
    var level: Int = 0

    val isRoot: Boolean
        get() = commentId == 0

    val isPseudo: Boolean
        get() = remoteId >= Int.MAX_VALUE / 2
}
