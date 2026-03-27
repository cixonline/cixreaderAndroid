package com.cixonline.cixreader.repository

import android.util.Log
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.MessageApi
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
            val lastSyncDate = settingsManager.getLastSyncDate()
            var currentSince = lastSyncDate
            val maxResults = 5000
            var fetchedTotal: Int
            var newestMessageDate: Long = 0
            
            do {
                val resultSet = api.sync(count = maxResults, since = currentSince)
                val messages = resultSet.messages
                Log.d(tag, "sync API returned ${messages.size} messages")
                
                if (messages.isEmpty()) break
                
                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                
                val cixMessages = messages.map { apiMsg ->
                    val forum = HtmlUtils.normalizeName(apiMsg.forum ?: "")
                    val topic = HtmlUtils.normalizeName(apiMsg.topic ?: "")
                    val topicId = HtmlUtils.calculateTopicId(forum, topic)
                    
                    // Preserve local state if message already exists
                    val existing = messageDao.getByRemoteId(apiMsg.id, topicId)
                    
                    val messageDate = if (apiMsg.dateTime != null) DateUtils.parseCixDate(apiMsg.dateTime) else (existing?.date ?: 0L)
                    
                    if (messageDate > newestMessageDate) {
                        newestMessageDate = messageDate
                    }

                    // CIX status "R" means read.
                    val isReadFromServer = apiMsg.status?.contains("R", ignoreCase = true) == true
                    val isOld = messageDate < thirtyDaysAgo
                    
                    // If message exists and is already read locally, KEEP it read.
                    // Otherwise, follow server status or 30-day rule.
                    val isUnread = if (existing != null && !existing.unread) {
                        false
                    } else if (isReadFromServer || isOld) {
                        false
                    } else {
                        existing?.unread ?: true
                    }

                    // If it was unread locally but now it's read from server, we should update folder counts
                    if (existing != null && existing.unread && !isUnread) {
                        folderDao.decrementUnread(topicId)
                        val forumId = HtmlUtils.calculateForumId(forum)
                        folderDao.decrementUnread(forumId)
                    }

                    CIXMessage(
                        id = existing?.id ?: 0,
                        remoteId = apiMsg.id,
                        author = apiMsg.author?.let { HtmlUtils.decodeHtml(it) } ?: existing?.author ?: "",
                        body = apiMsg.body?.let { HtmlUtils.cleanCixUrls(HtmlUtils.decodeHtml(it)) } ?: existing?.body ?: "",
                        date = messageDate,
                        commentId = if (apiMsg.replyTo != 0) apiMsg.replyTo else existing?.commentId ?: 0,
                        rootId = if (apiMsg.rootId != 0) apiMsg.rootId else existing?.rootId ?: 0,
                        topicId = topicId,
                        forumName = forum,
                        topicName = topic,
                        subject = apiMsg.subject?.let { HtmlUtils.decodeHtml(it) } ?: existing?.subject ?: "",
                        unread = isUnread,
                        priority = existing?.priority ?: false,
                        starred = existing?.starred ?: false,
                        readLocked = existing?.readLocked ?: false,
                        ignored = existing?.ignored ?: false,
                        // If server says it's read, clear the pending flag.
                        readPending = if (isReadFromServer) false else (existing?.readPending ?: false),
                        postPending = existing?.postPending ?: false,
                        starPending = existing?.starPending ?: false,
                        withdrawPending = existing?.withdrawPending ?: false
                    )
                }
                
                messageDao.insertAll(cixMessages)
                
                fetchedTotal = messages.size
                if (newestMessageDate > 0) {
                    currentSince = DateUtils.formatApiDate(newestMessageDate)
                }
                
            } while (fetchedTotal >= maxResults)

            if (newestMessageDate > 0) {
                settingsManager.saveLastSyncDate(DateUtils.formatApiDate(newestMessageDate))
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Periodic sync failed", e)
            throw e
        }
    }

    private suspend fun syncReadStatusToServer() {
        try {
            val pending = messageDao.getReadPendingMessages()
            if (pending.isEmpty()) return

            for (msg in pending) {
                try {
                    val encodedForum = HtmlUtils.cixEncode(msg.forumName)
                    val encodedTopic = HtmlUtils.cixEncode(msg.topicName)

                    // Use forum.markreadmessage.get (markread.xml) as markreadrange is deprecated
                    api.markRead(encodedForum, encodedTopic, msg.remoteId)

                    // Clear pending flag on success
                    messageDao.updateUnreadAndPending(msg.id, unread = false, readPending = false)

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
            Log.d(tag, "getMessages (allmessages) API returned ${resultSet.messages.size} messages for $forumName/$topicName")

            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            
            val messages = resultSet.messages.map { apiMsg ->
                val existing = messageDao.getByRemoteId(apiMsg.id, topicId)
                val messageDate = if (apiMsg.dateTime != null) DateUtils.parseCixDate(apiMsg.dateTime) else (existing?.date ?: 0L)
                
                val isReadFromServer = apiMsg.status?.contains("R", ignoreCase = true) == true
                val isOld = messageDate < thirtyDaysAgo
                
                // If message exists and is already read locally, KEEP it read.
                val isUnread = if (existing != null && !existing.unread) {
                    false
                } else if (isReadFromServer || isOld) {
                    false
                } else {
                    existing?.unread ?: true
                }

                // If it was unread locally but now it's read from server, we should update folder counts
                if (existing != null && existing.unread && !isUnread) {
                    folderDao.decrementUnread(topicId)
                    val forumId = HtmlUtils.calculateForumId(forumName)
                    folderDao.decrementUnread(forumId)
                }

                CIXMessage(
                    id = existing?.id ?: 0,
                    remoteId = apiMsg.id,
                    author = apiMsg.author?.let { HtmlUtils.decodeHtml(it) } ?: existing?.author ?: "",
                    body = apiMsg.body?.let { HtmlUtils.cleanCixUrls(HtmlUtils.decodeHtml(it)) } ?: existing?.body ?: "",
                    date = messageDate,
                    commentId = if (apiMsg.replyTo != 0) apiMsg.replyTo else existing?.commentId ?: 0,
                    rootId = if (apiMsg.rootId != 0) apiMsg.rootId else existing?.rootId ?: 0,
                    topicId = topicId,
                    forumName = forumName,
                    topicName = topicName,
                    subject = apiMsg.subject?.let { HtmlUtils.decodeHtml(it) } ?: existing?.subject ?: "",
                    unread = isUnread,
                    priority = existing?.priority ?: false,
                    starred = existing?.starred ?: false,
                    readLocked = existing?.readLocked ?: false,
                    ignored = existing?.ignored ?: false,
                    readPending = if (isReadFromServer) false else (existing?.readPending ?: false),
                    postPending = existing?.postPending ?: false,
                    starPending = existing?.starPending ?: false,
                    withdrawPending = existing?.withdrawPending ?: false
                )
            }
            
            if (messages.isNotEmpty()) {
                messageDao.insertAll(messages)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh messages for $forumName/$topicName", e)
            throw e
        }
    }

    suspend fun fullSync() = withContext(Dispatchers.IO) {
        try {
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
            
            // Update last sync date on successful full sync
            settingsManager.saveLastSyncDate(DateUtils.formatApiDate(System.currentTimeMillis()))
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Full sync failed", e)
            throw e
        }
    }
}
