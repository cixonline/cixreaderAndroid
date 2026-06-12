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
    private val settingsManager: SettingsManager,
    private val logRepository: LogRepository
) {
    private val tag = "SyncRepository"

    private suspend fun recalculateCounts() {
        // Removed the 30-day cutoff to ensure all unread messages are counted correctly.
        folderDao.recalculateTopicUnreadCounts()
        folderDao.recalculateForumUnreadCounts()
    }

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
                val rawMessages = resultSet.messages
                
                if (rawMessages.isEmpty()) break

                // Group by forum, topic, and remoteId to handle duplicates in the same response.
                val groupedMessages = rawMessages.groupBy {
                    val f = HtmlUtils.normalizeName(it.forum ?: "")
                    val t = HtmlUtils.normalizeName(it.topic ?: "")
                    Triple(f, t, it.id)
                }
                
                val cixMessages = groupedMessages.map { (key, apiMsgs) ->
                    val (forum, topic, remoteId) = key
                    val topicId = HtmlUtils.calculateTopicId(forum, topic)
                    
                    // Use the last message in the group as the primary source for content fields
                    val apiMsg = apiMsgs.last()
                    
                    // Preserve local state if message already exists
                    val existing = messageDao.getByRemoteId(remoteId, topicId)
                    
                    val messageDate = if (apiMsg.dateTime != null) DateUtils.parseCixDate(apiMsg.dateTime) else (existing?.date ?: 0L)
                    
                    if (messageDate > newestMessageDate) {
                        newestMessageDate = messageDate
                    }

                    // CIX status "R" means read.
                    // The API 'unread' field is also checked.
                    val isReadFromServer = apiMsgs.any { it.status?.contains("R", ignoreCase = true) == true || it.unread == false }
                    
                    // Follow server status.
                    val isUnread = if (isReadFromServer) {
                        false
                    } else {
                        // If server says unread (isReadFromServer is false), we check the API unread status.
                        apiMsgs.any { it.unread == true }
                    }

                    // Log cache update
                    if (existing != null && existing.unread != isUnread) {
                        Log.d(tag, "Updating cache for message #$remoteId: local unread=${existing.unread} -> server unread=$isUnread")
                        logRepository.log("Updated #$remoteId in $forum/$topic: ${existing.unread} -> $isUnread", "READ_STATUS")
                    }

                    if (existing == null) {
                        logRepository.log("Message #$remoteId in $forum/$topic. Unread: $isUnread", "INSERT")
                    }

                    CIXMessage(
                        id = existing?.id ?: 0,
                        remoteId = remoteId,
                        author = apiMsg.author?.let { HtmlUtils.decodeHtml(it) } ?: existing?.author ?: "",
                        body = apiMsg.body?.let { HtmlUtils.cleanCixUrls(HtmlUtils.decodeHtml(it)) } ?: existing?.body ?: "",
                        date = messageDate,
                        commentId = if (apiMsg.replyTo != 0) apiMsg.replyTo else existing?.commentId ?: 0,
                        rootId = if (apiMsg.rootId != 0) apiMsg.rootId else (if (apiMsg.replyTo == 0) remoteId else (existing?.rootId ?: 0)),
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
                        withdrawPending = existing?.withdrawPending ?: false,
                        threadReplies = existing?.threadReplies ?: -1,
                        threadUnread = existing?.threadUnread ?: -1
                    )
                }
                
                messageDao.insertAll(cixMessages)
                
                fetchedTotal = rawMessages.size
                if (newestMessageDate > 0) {
                    currentSince = DateUtils.formatApiDate(newestMessageDate)
                }
                
            } while (fetchedTotal >= maxResults)

            if (newestMessageDate > 0) {
                settingsManager.saveLastSyncDate(DateUtils.formatApiDate(newestMessageDate))
            }

            // Recalculate folder unread counts after sync
            recalculateCounts()

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

                    // Use forum.markreadmessage.get (markread.xml)
                    api.markRead(encodedForum, encodedTopic, msg.remoteId)

                    // Clear pending flag on success
                    messageDao.updateUnreadAndPending(msg.id, unread = false, readPending = false)
                    logRepository.log("Synced read status for #${msg.remoteId} to server", "SYNC")

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

    private suspend fun backfillTopic(forumName: String, topicName: String, topicId: Int) = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forumName)
            val encodedTopic = HtmlUtils.cixEncode(topicName)
            
            Log.d(tag, "Backfilling $forumName/$topicName using threads.xml")
            val resultSet = api.getTopicThreads(encodedForum, encodedTopic)
            
            // Map ThreadApi to MessageApi to reuse logic
            val mappedMessages = resultSet.threads.map { thread ->
                MessageApi().apply {
                    this.id = thread.id
                    this.author = thread.author
                    this.body = thread.body
                    this.dateTime = thread.date
                    this.forum = forumName
                    this.topic = topicName
                    this.rootId = thread.rootId
                    this.replyTo = 0
                    this.unread = thread.unread > 0
                    this.threadReplies = thread.replies
                    this.threadUnread = thread.unread
                }
            }

            // Group by message ID to handle duplicates in the same response
            val groupedMessages = mappedMessages.groupBy { it.id }

            val messages = groupedMessages.map { (remoteId, apiMsgs) ->
                // Use the last message in the group for other fields
                val apiMsg = apiMsgs.last()
                
                val existing = messageDao.getByRemoteId(remoteId, topicId)
                val messageDate = if (apiMsg.dateTime != null) DateUtils.parseCixDate(apiMsg.dateTime) else (existing?.date ?: 0L)
                
                // If ANY of the records for this message say it's read, then it's read.
                val isReadFromServer = apiMsgs.any { it.status?.contains("R", ignoreCase = true) == true || it.unread == false }
                
                // Follow server status.
                val isUnread = if (isReadFromServer) {
                    false
                } else {
                    apiMsgs.any { it.unread == true }
                }

                if (existing == null) {
                    logRepository.log("Message #$remoteId in $forumName/$topicName. Unread: $isUnread", "INSERT")
                }

                CIXMessage(
                    id = existing?.id ?: 0,
                    remoteId = remoteId,
                    author = apiMsg.author?.let { HtmlUtils.decodeHtml(it) } ?: existing?.author ?: "",
                    body = apiMsg.body?.let { HtmlUtils.cleanCixUrls(HtmlUtils.decodeHtml(it)) } ?: existing?.body ?: "",
                    date = messageDate,
                    // For threads.xml backfill, these are roots, so commentId should be 0.
                    commentId = 0,
                    rootId = remoteId,
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
                    withdrawPending = existing?.withdrawPending ?: false,
                    threadReplies = apiMsg.threadReplies,
                    threadUnread = apiMsg.threadUnread
                )
            }
            
            if (messages.isNotEmpty()) {
                messageDao.insertAll(messages)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Failed to backfill for $forumName/$topicName", e)
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
                    unread = row.effectiveUnread?.toIntOrNull() ?: 0,
                    unreadPriority = row.priority?.toIntOrNull() ?: 0
                )
            }
            folderDao.insertAll(forums)

            // 3. Refresh Topics and Backfill
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
                        unread = result.effectiveUnread?.toIntOrNull() ?: 0
                    )
                }
                folderDao.insertAll(topics)
                
                for (topic in topics) {
                    backfillTopic(forum.name, topic.name, topic.id)
                }
            }
            
            // 4. Recalculate counts
            recalculateCounts()

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
