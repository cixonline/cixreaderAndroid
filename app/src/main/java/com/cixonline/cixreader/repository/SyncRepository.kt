package com.cixonline.cixreader.repository

import android.util.Log
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.db.FolderDao
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.*

class SyncRepository(
    private val api: CixApi,
    private val folderDao: FolderDao,
    private val messageDao: MessageDao
) {
    private val tag = "SyncRepository"

    suspend fun syncLatestMessages() = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Starting periodic sync via sync.xml")
            
            var start = 0
            val count = 100
            var fetchedTotal = 0
            
            do {
                val resultSet = api.sync(count = count, start = start)
                val messages = resultSet.messages
                
                if (messages.isEmpty()) break
                
                val cixMessages = messages.map { apiMsg ->
                    val forum = HtmlUtils.normalizeName(apiMsg.forum)
                    val topic = HtmlUtils.normalizeName(apiMsg.topic)
                    val topicId = HtmlUtils.calculateTopicId(forum, topic)
                    
                    CIXMessage(
                        remoteId = apiMsg.id,
                        author = HtmlUtils.decodeHtml(apiMsg.author ?: ""),
                        body = HtmlUtils.decodeHtml(apiMsg.body ?: ""),
                        date = DateUtils.parseCixDate(apiMsg.dateTime),
                        commentId = apiMsg.replyTo,
                        rootId = apiMsg.rootId,
                        topicId = topicId,
                        forumName = forum,
                        topicName = topic,
                        subject = HtmlUtils.decodeHtml(apiMsg.subject),
                        unread = true
                    )
                }
                
                messageDao.insertAll(cixMessages)
                
                fetchedTotal = messages.size
                start += count
                
            } while (fetchedTotal >= count)

            Log.d(tag, "Periodic sync completed")
        } catch (e: Exception) {
            Log.e(tag, "Periodic sync failed", e)
        }
    }

    private suspend fun refreshMessages(forumName: String, topicName: String, topicId: Int) {
        try {
            val latestMessage = messageDao.getLatestMessage(topicId)
            
            // The CIX API expects a date string for 'since' in allmessages.xml, not a message ID.
            val since = if (latestMessage == null) null else DateUtils.formatApiDate(latestMessage.date)
            
            val encodedForum = HtmlUtils.cixEncode(forumName)
            val encodedTopic = HtmlUtils.cixEncode(topicName)
            
            val resultSet = api.getMessages(encodedForum, encodedTopic, since = since)
            
            val messages = resultSet.messages.map { apiMsg ->
                CIXMessage(
                    remoteId = apiMsg.id,
                    author = HtmlUtils.decodeHtml(apiMsg.author ?: ""),
                    body = HtmlUtils.decodeHtml(apiMsg.body ?: ""),
                    date = DateUtils.parseCixDate(apiMsg.dateTime),
                    commentId = apiMsg.replyTo,
                    rootId = apiMsg.rootId,
                    topicId = topicId,
                    forumName = forumName,
                    topicName = topicName,
                    subject = HtmlUtils.decodeHtml(apiMsg.subject),
                    unread = true 
                )
            }
            
            if (messages.isNotEmpty()) {
                messageDao.insertAll(messages)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh messages for $forumName/$topicName", e)
        }
    }

    suspend fun fullSync() = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Starting full sync")
            
            // 1. Refresh Forums
            val forumResultSet = api.getForums()
            val forums = forumResultSet.forums.mapNotNull { row ->
                val folderName = row.name ?: return@mapNotNull null
                val normalizedName = HtmlUtils.normalizeName(folderName)
                Folder(
                    id = HtmlUtils.calculateForumId(normalizedName),
                    name = normalizedName,
                    parentId = -1,
                    unread = row.unread?.toIntOrNull() ?: 0,
                    unreadPriority = row.priority?.toIntOrNull() ?: 0
                )
            }
            folderDao.insertAll(forums)

            // 2. Refresh Topics and Messages
            for (forum in forums) {
                val encodedForumName = HtmlUtils.cixEncode(forum.name)
                val topicResultSet = api.getUserForumTopics(encodedForumName)
                val topics = topicResultSet.userTopics.mapNotNull { result ->
                    val topicName = result.name ?: return@mapNotNull null
                    val normalizedTopicName = HtmlUtils.normalizeName(topicName)
                    Folder(
                        id = HtmlUtils.calculateTopicId(forum.name, normalizedTopicName),
                        name = normalizedTopicName,
                        parentId = forum.id,
                        unread = result.unread?.toIntOrNull() ?: 0
                    )
                }
                folderDao.insertAll(topics)
                
                for (topic in topics) {
                    refreshMessages(forum.name, topic.name, topic.id)
                }
            }
            
            Log.d(tag, "Full sync completed")
        } catch (e: Exception) {
            Log.e(tag, "Full sync failed", e)
        }
    }
}
