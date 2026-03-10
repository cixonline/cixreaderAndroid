package com.cixonline.cixreader.models

import android.util.Log
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Ignore
import androidx.room.Index
import java.util.Date

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["remoteId", "topicId"], unique = true),
        Index(value = ["topicId", "unread", "date"])
    ]
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
    val forumName: String = "",
    val topicName: String = "",
    val subject: String? = null,
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

    val isActuallyUnread: Boolean
        get() {
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            val result = unread && date >= thirtyDaysAgo
            // Only log if it would have been unread but is now read due to the 30-day rule
            if (unread && !result) {
                Log.d("CIXMessage", "Message #$remoteId is unread in DB but > 30 days old. Marking as read in UI.")
            }
            return result
        }

    val isRoot: Boolean
        get() = commentId == 0

    val isPseudo: Boolean
        get() = remoteId >= Int.MAX_VALUE / 2

    fun isWithdrawn(): Boolean {
        return body.contains("<<withdrawn by author>>", ignoreCase = true) ||
               body.contains("<<withdrawn by moderator>>", ignoreCase = true) ||
               body.contains("<<withdrawn by system administrator>>", ignoreCase = true)
    }
}
