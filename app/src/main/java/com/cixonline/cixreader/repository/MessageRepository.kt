package com.cixonline.cixreader.repository

import android.util.Log
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.JsonNetworkClient
import com.cixonline.cixreader.api.MessageApi
import com.cixonline.cixreader.api.MessageRangeRequest
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.api.PostAttachment
import com.cixonline.cixreader.api.PostMessage2Request
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
    private val messageDao: MessageDao
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
            val oldestId = oldestLocal?.remoteId ?: return@withContext
            
            if (oldestId <= 1) {
                Log.d(tag, "Already have message 1 for $forum/$topic")
                return@withContext
            }

            // Range is 1 to (oldestId - 1)
            var currentEnd = oldestId - 1
            val batchSize = 1000 // Arbitrary safe batch size for range requests
            
            val encodedForum = HtmlUtils.cixEncode(forum)
            val encodedTopic = HtmlUtils.cixEncode(topic)

            while (currentEnd >= 1) {
                val currentStart = (currentEnd - batchSize + 1).coerceAtLeast(1)
                Log.d(tag, "Backfilling $forum/$topic range: $currentStart to $currentEnd")
                
                val request = MessageRangeRequest(
                    end = currentEnd,
                    forum = encodedForum,
                    start = currentStart,
                    topic = encodedTopic
                )

                 // Log XML payload
                try {
                    val serializer = Persister()
                    val writer = StringWriter()
                    serializer.write(request, writer)
                    Log.d(tag, "messagerange.xml POST payload: ${writer.toString()}")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to log messagerange.xml payload", e)
                }
                
                val resultSet = api.getMessageRange(request)
                if (resultSet.messages.isNotEmpty()) {
                    saveMessagesToDb(resultSet.messages, forum, topic, topicId)
                } else {
                    // If no messages in this range (e.g. gap), we just move on
                    Log.d(tag, "No messages in range $currentStart to $currentEnd for $forum/$topic")
                }
                
                currentEnd = currentStart - 1
            }
            Log.d(tag, "Backfill complete for $forum/$topic")
        } catch (e: Exception) {
            Log.e(tag, "Backfill to message 1 failed for $forum/$topic", e)
        }
    }

    private suspend fun saveMessagesToDb(apiMessages: List<MessageApi>, forum: String, topic: String, topicId: Int) {
        val currentMessages = messageDao.getByTopic(topicId).first()
        val existingMessages = currentMessages.associateBy { it.remoteId }
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        val currentUsername = NetworkClient.getUsername()
        
        val messagesToInsert = apiMessages.map { apiMsg ->
            val existing = existingMessages[apiMsg.id]
            val messageDate = DateUtils.parseCixDate(apiMsg.dateTime)
            
            val isReadFromServer = apiMsg.status?.equals("R", ignoreCase = true) == true
            val isOld = messageDate < thirtyDaysAgo
            val isFromSelf = apiMsg.author?.equals(currentUsername, ignoreCase = true) == true
            
            val isUnread = if (existing != null && !existing.unread) {
                false
            } else if (isReadFromServer || isOld || isFromSelf) {
                false
            } else {
                existing?.unread ?: true
            }

            CIXMessage(
                id = existing?.id ?: 0,
                remoteId = apiMsg.id,
                author = HtmlUtils.decodeHtml(apiMsg.author ?: ""),
                body = HtmlUtils.cleanCixUrls(HtmlUtils.decodeHtml(apiMsg.body ?: "")),
                date = messageDate,
                commentId = apiMsg.replyTo,
                rootId = apiMsg.rootId,
                topicId = topicId,
                forumName = forum,
                topicName = topic,
                subject = HtmlUtils.decodeHtml(apiMsg.subject),
                unread = isUnread,
                priority = existing?.priority ?: false,
                starred = existing?.starred ?: false,
                readLocked = existing?.readLocked ?: false,
                ignored = existing?.ignored ?: false,
                readPending = existing?.readPending ?: false,
                postPending = existing?.postPending ?: false,
                starPending = existing?.starPending ?: false,
                withdrawPending = existing?.withdrawPending ?: false
            )
        }
        messageDao.insertAll(messagesToInsert)
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
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            val currentUsername = NetworkClient.getUsername()
            
            val isReadFromServer = messageApi.status?.equals("R", ignoreCase = true) == true
            val isOld = messageDate < thirtyDaysAgo
            val isFromSelf = messageApi.author?.equals(currentUsername, ignoreCase = true) == true
            
            val isUnread = if (existing != null && !existing.unread) {
                false
            } else if (isReadFromServer || isOld || isFromSelf) {
                false
            } else {
                existing?.unread ?: true
            }

            val message = CIXMessage(
                id = existing?.id ?: 0,
                remoteId = messageApi.id,
                author = HtmlUtils.decodeHtml(messageApi.author ?: ""),
                body = HtmlUtils.cleanCixUrls(HtmlUtils.decodeHtml(messageApi.body ?: "")),
                date = messageDate,
                commentId = messageApi.replyTo,
                rootId = messageApi.rootId,
                topicId = topicId,
                forumName = forum,
                topicName = topic,
                subject = HtmlUtils.decodeHtml(messageApi.subject),
                unread = isUnread
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
            
            val message = CIXMessage(
                remoteId = response.id,
                author = author,
                body = body,
                date = System.currentTimeMillis(),
                commentId = replyTo,
                rootId = if (replyTo == 0) response.id else 0, // Simplified: if new message, it is its own root
                topicId = 0,
                forumName = forum,
                topicName = topic,
                subject = "",
                unread = false,
                postPending = false
            )
            messageDao.insert(message)
            
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
            messageDao.updateUnread(message.id, false)
            try {
                api.markRead(
                    HtmlUtils.cixEncode(message.forumName),
                    HtmlUtils.cixEncode(message.topicName),
                    message.remoteId
                )
            } catch (e: Exception) {
                Log.e(tag, "Mark as read on server failed", e)
            }
        }
    }
}
