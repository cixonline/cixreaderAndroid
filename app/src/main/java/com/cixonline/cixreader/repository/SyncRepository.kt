package com.cixonline.cixreader.repository

import android.util.Log
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.db.FolderDao
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.utils.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class SyncRepository(
    private val api: CixApi,
    private val folderDao: FolderDao,
    private val messageDao: MessageDao
) {
    private val tag = "SyncRepository"

    suspend fun fullSync() = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Starting full sync")
            
            // 1. Refresh Forums and Topics
            val forumResultSet = api.getForums()
            val forums = forumResultSet.forums.mapNotNull { row ->
                val folderName = row.name ?: return@mapNotNull null
                Folder(
                    id = folderName.hashCode(),
                    name = folderName,
                    parentId = -1,
                    unread = row.unread?.toIntOrNull() ?: 0,
                    unreadPriority = row.priority?.toIntOrNull() ?: 0
                )
            }
            folderDao.insertAll(forums)

            for (forum in forums) {
                val topicResultSet = api.getTopics(forum.name)
                val topics = topicResultSet.topics.mapNotNull { result ->
                    val topicName = result.name ?: return@mapNotNull null
                    Folder(
                        id = (forum.name + topicName).hashCode(),
                        name = topicName,
                        parentId = forum.id
                    )
                }
                folderDao.insertAll(topics)
                
                for (topic in topics) {
                    refreshMessages(forum.name, topic.name, topic.id)
                }
            }
            
            Log.d(tag, "Full sync completed")
        } catch (e: Exception) {
            Log.e(tag, "Sync failed", e)
        }
    }

    private suspend fun refreshMessages(forumName: String, topicName: String, topicId: Int) {
        try {
            val resultSet = api.getMessages(forumName, topicName)
            val messages = resultSet.messages.map { apiMsg ->
                val msg = CIXMessage(
                    remoteId = apiMsg.id,
                    author = apiMsg.author ?: "Unknown",
                    body = apiMsg.body ?: "",
                    date = DateUtils.parseCixDate(apiMsg.dateTime),
                    commentId = apiMsg.replyTo,
                    rootId = apiMsg.rootId,
                    topicId = topicId
                )
                msg.level = apiMsg.depth?.toIntOrNull() ?: 0
                msg
            }
            messageDao.insertAll(messages)
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh messages for $forumName/$topicName", e)
        }
    }
}
