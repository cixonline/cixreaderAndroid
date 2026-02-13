package com.cixonline.cixreader.repository

import android.util.Log
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.db.FolderDao
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.utils.HtmlUtils
import com.cixonline.cixreader.utils.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.*

class SyncRepository(
    private val api: CixApi,
    private val folderDao: FolderDao,
    private val messageDao: MessageDao,
    private val settingsManager: SettingsManager
) {
    private val tag = "SyncRepository"

    suspend fun syncLatestMessages() = withContext(Dispatchers.IO) {
        try {
            // 1. Sync local read status to server
            syncReadStatusToServer()

            // 2. Sync new messages from server
            Log.d(tag, "Starting periodic sync via user/sync.xml")
            
            val lastSyncDate = settingsManager.getLastSyncDate()
            var start = 0
            val count = 100
            var fetchedTotal = 0
            var newestMessageDate: Long = 0
            
            do {
                val resultSet = api.sync(count = count, start = start, since = lastSyncDate)
                val messages = resultSet.messages
                
                if (messages.isEmpty()) break
                
                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                
                val cixMessages = messages.map { apiMsg ->
                    val forum = HtmlUtils.normalizeName(apiMsg.forum)
                    val topic = HtmlUtils.normalizeName(apiMsg.topic)
                    val topicId = HtmlUtils.calculateTopicId(forum, topic)
                    val messageDate = DateUtils.parseCixDate(apiMsg.dateTime)
                    
                    if (messageDate > newestMessageDate) {
                        newestMessageDate = messageDate
                    }

                    // Preserve local state if message already exists
                    val existing = messageDao.getByRemoteId(apiMsg.id, topicId)

                    val isReadFromServer = apiMsg.status?.equals("R", ignoreCase = true) == true
                    val isOld = messageDate < thirtyDaysAgo
                    val isUnread = if (isReadFromServer || isOld) false else (existing?.unread ?: true)

                    CIXMessage(
                        id = existing?.id ?: 0,
                        remoteId = apiMsg.id,
                        author = HtmlUtils.decodeHtml(apiMsg.author ?: ""),
                        body = HtmlUtils.decodeHtml(apiMsg.body ?: ""),
                        date = messageDate,
                        commentId = apiMsg.replyTo,
                        rootId = apiMsg.rootId,
                        topicId = topicId,
                        forumName = forum,
                        topicName = topic,
                        subject = HtmlUtils.decodeHtml(apiMsg.subject),
                        unread = isUnread,
                        starred = existing?.starred ?: false,
                        readPending = existing?.readPending ?: false
                    )
                }
                
                messageDao.insertAll(cixMessages)
                
                fetchedTotal = messages.size
                start += count
                
            } while (fetchedTotal >= count)

            if (newestMessageDate > 0) {
                settingsManager.saveLastSyncDate(DateUtils.formatApiDate(newestMessageDate))
            }

            Log.d(tag, "Periodic sync completed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Periodic sync failed", e)
        }
    }

    private suspend fun syncReadStatusToServer() {
        try {
            val pending = messageDao.getReadPendingMessages()
            if (pending.isEmpty()) return

            Log.d(tag, "Syncing ${pending.size} read status updates to server")

            for (msg in pending) {
                try {
                    val encodedForum = HtmlUtils.cixEncode(msg.forumName)
                    val encodedTopic = HtmlUtils.cixEncode(msg.topicName)

                    // Use forum.markreadmessage.get (markread.xml) as markreadrange is deprecated
                    api.markRead(encodedForum, encodedTopic, msg.remoteId)

                    // Clear pending flag on success
                    messageDao.insertAll(listOf(msg.copy(readPending = false)))

                    Log.d(tag, "Marked message ${msg.remoteId} as read in ${msg.forumName}/${msg.topicName}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(tag, "Failed to mark message ${msg.remoteId} as read", e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Error during read status sync", e)
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
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            
            val messages = resultSet.messages.map { apiMsg ->
                val existing = messageDao.getByRemoteId(apiMsg.id, topicId)
                val messageDate = DateUtils.parseCixDate(apiMsg.dateTime)
                
                val isReadFromServer = apiMsg.status?.equals("R", ignoreCase = true) == true
                val isOld = messageDate < thirtyDaysAgo
                val isUnread = if (isReadFromServer || isOld) false else (existing?.unread ?: true)

                CIXMessage(
                    id = existing?.id ?: 0,
                    remoteId = apiMsg.id,
                    author = HtmlUtils.decodeHtml(apiMsg.author ?: ""),
                    body = HtmlUtils.decodeHtml(apiMsg.body ?: ""),
                    date = messageDate,
                    commentId = apiMsg.replyTo,
                    rootId = apiMsg.rootId,
                    topicId = topicId,
                    forumName = forumName,
                    topicName = topicName,
                    subject = HtmlUtils.decodeHtml(apiMsg.subject),
                    unread = isUnread,
                    starred = existing?.starred ?: false,
                    readPending = existing?.readPending ?: false
                )
            }
            
            if (messages.isNotEmpty()) {
                messageDao.insertAll(messages)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh messages for $forumName/$topicName", e)
        }
    }

    suspend fun fullSync() = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Starting full sync")
            
            // 1. Sync Read Status first
            syncReadStatusToServer()

            // 2. Refresh Forums
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

            // 3. Refresh Topics and Messages
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Full sync failed", e)
        }
    }
}
