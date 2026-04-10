package com.cixonline.cixreader.repository

import android.util.Log
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.JsonNetworkClient
import com.cixonline.cixreader.api.MessageApi
import com.cixonline.cixreader.api.MessageResultSet
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.api.PostAttachment
import com.cixonline.cixreader.api.PostMessage2Request
import com.cixonline.cixreader.db.FolderDao
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.simpleframework.xml.core.Persister
import org.xmlpull.v1.XmlPullParserFactory
import retrofit2.HttpException
import java.io.StringReader
import java.io.StringWriter

class NotAMemberException(val forumName: String) : Exception("Not a member of forum: $forumName")

class MessageRepository(
    private val api: CixApi,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val logRepository: LogRepository
) {
    private val tag = "MessageRepository"

    fun getMessagesForTopic(topicId: Int): Flow<List<CIXMessage>> {
        return messageDao.getByTopic(topicId)
    }

    suspend fun getMessageCount(topicId: Int): Int = withContext(Dispatchers.IO) {
        messageDao.getMessageCount(topicId)
    }

    suspend fun getLatestMessageInTopic(topicId: Int): CIXMessage? = withContext(Dispatchers.IO) {
        messageDao.getLatestMessage(topicId)
    }

    suspend fun getOldestMessageInTopic(topicId: Int): CIXMessage? = withContext(Dispatchers.IO) {
        messageDao.getOldestMessage(topicId)
    }

    suspend fun getOldestUnreadInTopic(topicId: Int): CIXMessage? = withContext(Dispatchers.IO) {
        val allMessages = messageDao.getByTopic(topicId).first()
        allMessages.filter { it.isActuallyUnread }.minByOrNull { it.date }
    }

    private suspend fun recalculateCounts() {
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        folderDao.recalculateTopicUnreadCounts(thirtyDaysAgo)
        folderDao.recalculateForumUnreadCounts()
    }

    suspend fun refreshMessages(forum: String, topic: String, topicId: Int, force: Boolean = false, sinceOverride: String? = null) = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forum)
            val encodedTopic = HtmlUtils.cixEncode(topic)
            
            val latestMessage = messageDao.getLatestMessage(topicId)
            val since = sinceOverride ?: if (force || latestMessage == null) null else DateUtils.formatApiDate(latestMessage.date)

            Log.d(tag, "Refreshing messages for $forum/$topic. Since: $since")
            val resultSet = api.getMessages(encodedForum, encodedTopic, since = since)
            val apiMessages = resultSet.messages
            
            if (apiMessages.isNotEmpty()) {
                Log.d(tag, "Fetched ${apiMessages.size} messages for $forum/$topic")
                saveMessagesToDb(apiMessages, forum, topic, topicId)
            } else {
                Log.d(tag, "No new messages for $forum/$topic")
            }
        } catch (e: Exception) {
            if (e.message?.contains("not a member", ignoreCase = true) == true) {
                throw NotAMemberException(forum)
            }
            Log.e(tag, "Refresh messages failed", e)
            throw e
        }
    }

    suspend fun backfillToMessageOne(forum: String, topic: String, topicId: Int) = withContext(Dispatchers.IO) {
        try {
            val oldestLocal = messageDao.getOldestMessage(topicId)
            if (oldestLocal != null && oldestLocal.remoteId <= 1) {
                Log.d(tag, "Already have message 1 for $forum/$topic")
                return@withContext
            }

            val encodedForum = HtmlUtils.cixEncode(forum)
            val encodedTopic = HtmlUtils.cixEncode(topic)

            Log.d(tag, "Backfilling $forum/$topic using threads.xml")
            val resultSet = api.getTopicThreads(encodedForum, encodedTopic)

            if (resultSet.messages.isNotEmpty()) {
                Log.d(tag, "Backfill fetched ${resultSet.messages.size} roots for $forum/$topic")
                saveMessagesToDb(resultSet.messages, forum, topic, topicId)
            }
            Log.d(tag, "Backfill complete for $forum/$topic")
        } catch (e: Exception) {
            Log.e(tag, "Backfill failed for $forum/$topic", e)
        }
    }

    private suspend fun saveMessagesToDb(
        apiMessages: List<MessageApi>, 
        forum: String, 
        topic: String, 
        topicId: Int
    ) {
        val currentMessages = messageDao.getByTopic(topicId).first()
        val existingMessages = currentMessages.associateBy { it.remoteId }
        val currentUsername = NetworkClient.getUsername()
        
        val messagesToInsert = apiMessages.map { apiMsg ->
            val existing = existingMessages[apiMsg.id]
            val messageDate = DateUtils.parseCixDate(apiMsg.dateTime)
            
            val isReadFromServer = apiMsg.status?.contains("R", ignoreCase = true) == true || apiMsg.unread == false
            val isFromSelf = apiMsg.author?.equals(currentUsername, ignoreCase = true) == true
            
            val isUnread = if (isReadFromServer || isFromSelf) {
                false
            } else {
                apiMsg.unread
            }

            if (existing == null) {
                logRepository.log("Inserted new message #${apiMsg.id} in $forum/$topic. Unread: $isUnread", "INSERT")
            }

            CIXMessage(
                id = existing?.id ?: 0,
                remoteId = apiMsg.id,
                author = HtmlUtils.decodeHtml(apiMsg.author ?: ""),
                body = HtmlUtils.cleanCixUrls(HtmlUtils.decodeHtml(apiMsg.body ?: "")),
                date = messageDate,
                commentId = apiMsg.replyTo,
                rootId = if (apiMsg.rootId != 0) apiMsg.rootId else (if (apiMsg.replyTo == 0) apiMsg.id else (existing?.rootId ?: 0)),
                topicId = topicId,
                forumName = forum,
                topicName = topic,
                subject = HtmlUtils.decodeHtml(apiMsg.subject),
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
        messageDao.insertAll(messagesToInsert)
        recalculateCounts()
    }

    suspend fun fetchThreadThenBackfill(forum: String, topic: String, msgId: Int, topicId: Int) = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forum)
            val encodedTopic = HtmlUtils.cixEncode(topic)
            
            Log.d(tag, "Fetching thread for msg $msgId in $forum/$topic")
            val threadSet = api.getThread(encodedForum, encodedTopic, msgId)
            if (threadSet.messages.isNotEmpty()) {
                saveMessagesToDb(threadSet.messages, forum, topic, topicId)
            }

            refreshMessages(forum, topic, topicId)
            
        } catch (e: Exception) {
            Log.e(tag, "fetchThreadThenBackfill failed for $msgId", e)
        }
    }

    private fun extractIntFromXml(xml: String): Int {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.TEXT) {
                    return parser.text.toIntOrNull() ?: 0
                }
                eventType = parser.next()
            }
            0
        } catch (e: Exception) {
            0
        }
    }

    private fun extractStringFromXml(xml: String): String {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.TEXT) {
                    return parser.text
                }
                eventType = parser.next()
            }
            xml
        } catch (e: Exception) {
            xml
        }
    }

    suspend fun getFirstUnreadMessageId(forum: String, topic: String): Int = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forum)
            val encodedTopic = HtmlUtils.cixEncode(topic)
            val response = api.getFirstUnread(encodedForum, encodedTopic)
            extractIntFromXml(response.string())
        } catch (e: Exception) {
            0
        }
    }

    suspend fun fetchMessageAndChildren(forum: String, topic: String, msgId: Int, topicId: Int) = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forum)
            val encodedTopic = HtmlUtils.cixEncode(topic)
            
            val messageApi = api.getMessage(encodedForum, encodedTopic, msgId)
            val existing = messageDao.getByRemoteId(msgId, topicId)
            
            val messageDate = DateUtils.parseCixDate(messageApi.dateTime)
            val currentUsername = NetworkClient.getUsername()
            
            val isReadFromServer = messageApi.status?.contains("R", ignoreCase = true) == true || messageApi.unread == false
            val isFromSelf = messageApi.author?.equals(currentUsername, ignoreCase = true) == true
            
            val isUnread = if (isReadFromServer || isFromSelf) {
                false
            } else {
                messageApi.unread
            }

            if (existing == null) {
                logRepository.log("Inserted new message #${msgId} in $forum/$topic. Unread: $isUnread", "INSERT")
            }

            val message = CIXMessage(
                id = existing?.id ?: 0,
                remoteId = messageApi.id,
                author = HtmlUtils.decodeHtml(messageApi.author ?: ""),
                body = HtmlUtils.cleanCixUrls(HtmlUtils.decodeHtml(messageApi.body ?: "")),
                date = messageDate,
                commentId = messageApi.replyTo,
                rootId = if (messageApi.rootId != 0) messageApi.rootId else (if (messageApi.replyTo == 0) messageApi.id else (existing?.rootId ?: 0)),
                topicId = topicId,
                forumName = forum,
                topicName = topic,
                subject = HtmlUtils.decodeHtml(messageApi.subject),
                unread = isUnread,
                readPending = if (isReadFromServer) false else (existing?.readPending ?: false),
                priority = existing?.priority ?: false,
                starred = existing?.starred ?: false,
                readLocked = existing?.readLocked ?: false,
                ignored = existing?.ignored ?: false,
                postPending = existing?.postPending ?: false,
                starPending = existing?.starPending ?: false,
                withdrawPending = existing?.withdrawPending ?: false
            )
            messageDao.insert(message)

            refreshMessages(forum, topic, topicId)
            
        } catch (e: Exception) {
            Log.e(tag, "fetchMessageAndChildren failed for $msgId", e)
        }
    }

    suspend fun postMessage(
        forum: String, 
        topic: String, 
        topicId: Int,
        body: String, 
        replyTo: Int,
        author: String,
        attachments: List<PostAttachment>? = null
    ): Int = withContext(Dispatchers.IO) {
        try {
            val forumParam = HtmlUtils.normalizeName(forum)
            val topicParam = HtmlUtils.normalizeName(topic)

            val processedAttachments = attachments?.map { 
                it.copy(filename = HtmlUtils.encodeFilename(it.filename))
            }

            var bodyForRequest = body
            processedAttachments?.forEachIndexed { index, _ ->
                val marker = "{${index + 1}}"
                if (!bodyForRequest.contains(marker)) {
                    bodyForRequest += "\n\n$marker"
                }
            }

            val request = PostMessage2Request(
                forum = forumParam,
                topic = topicParam,
                body = bodyForRequest,
                msgId = replyTo,
                attachments = processedAttachments
            )

            val response = JsonNetworkClient.api.postMessageJson(request)

            var rootId = 0
            if (replyTo != 0) {
                val parentMessage = messageDao.getByRemoteId(replyTo, topicId)
                rootId = parentMessage?.rootId ?: 0
            } else {
                rootId = response.id
            }
            
            val message = CIXMessage(
                remoteId = response.id,
                author = author,
                body = body,
                date = System.currentTimeMillis(),
                commentId = replyTo,
                rootId = rootId,
                topicId = topicId,
                forumName = forum,
                topicName = topic,
                subject = "",
                unread = false,
                postPending = false
            )
            messageDao.insert(message)
            logRepository.log("Posted new message #${response.id} in $forum/$topic", "INSERT")
            
            response.id
        } catch (e: Exception) {
            Log.e(tag, "postMessage failed", e)
            throw e
        }
    }

    suspend fun joinForum(forum: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forum)
            val response = api.joinForum(encodedForum)
            val result = extractStringFromXml(response.string())
            result.trim() == "Success"
        } catch (e: Exception) {
            Log.e(tag, "Join forum failed", e)
            false
        }
    }

    suspend fun withdrawMessage(message: CIXMessage): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = api.withdrawMessage(
                HtmlUtils.cixEncode(message.forumName),
                HtmlUtils.cixEncode(message.topicName),
                message.remoteId
            )
            val result = extractStringFromXml(response.string())
            result.trim() == "Success"
        } catch (e: Exception) {
            Log.e(tag, "Withdraw message failed", e)
            false
        }
    }

    suspend fun toggleStar(message: CIXMessage) = withContext(Dispatchers.IO) {
        val newStarred = !message.starred
        messageDao.updateStarred(message.id, newStarred)
    }

    suspend fun markAsRead(message: CIXMessage) = withContext(Dispatchers.IO) {
        if (message.unread) {
            // Optimistically mark as read and pending in local DB
            messageDao.updateUnreadAndPending(message.id, unread = false, readPending = true)
            
            // Recalculate folder unread counts to ensure consistency
            recalculateCounts()

            logRepository.log("Marked #${message.remoteId} as read (locally pending) in ${message.forumName}/${message.topicName}", "READ_STATUS")
            // Immediate server sync removed to improve UI responsiveness.
            // Read status will be synced to server by SyncWorker in the background.
        }
    }

    suspend fun markTopicAsRead(forumName: String, topicName: String, topicId: Int) = withContext(Dispatchers.IO) {
        try {
            // 1. Update local messages
            messageDao.markTopicAsRead(topicId)

            // 2. Recalculate folder unread counts
            recalculateCounts()

            logRepository.log("Marked topic $forumName/$topicName as read", "READ_STATUS")
        } catch (e: Exception) {
            Log.e(tag, "Failed to mark topic as read", e)
        }
    }

    suspend fun refreshAllTopicUnreads() = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Refreshing all topic unread counts from server using User.AllTopics")
            val resultSet = api.getAllTopics()
            resultSet.userTopics.forEach { result ->
                val forumName = result.forum ?: return@forEach
                val topicName = result.topic ?: return@forEach
                val topicId = HtmlUtils.calculateTopicId(forumName, topicName)
                val unreadCount = result.effectiveUnread?.toIntOrNull() ?: 0
                folderDao.setUnread(topicId, unreadCount)
            }
            folderDao.recalculateForumUnreadCounts()
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh all topic unreads", e)
        }
    }
}
