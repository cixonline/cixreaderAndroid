package com.cixonline.cixreader.repository

import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.PostMessageRequest
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class NotAMemberException(val forumName: String) : Exception("Not a member of forum: $forumName")

class MessageRepository(
    private val api: CixApi,
    private val messageDao: MessageDao
) {
    fun getMessagesForTopic(topicId: Int): Flow<List<CIXMessage>> {
        return messageDao.getByTopic(topicId)
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
            xml // Fallback to raw if no text found
        } catch (e: Exception) {
            xml // Fallback to raw on error
        }
    }

    /**
     * Refreshes all messages for a topic.
     * Always calls allmessages.xml without a 'since' parameter to ensure the local cache is complete.
     */
    suspend fun refreshMessages(forum: String, topic: String, topicId: Int, force: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forum)
            val encodedTopic = HtmlUtils.cixEncode(topic)
            
            // Always fetch all messages for the topic to ensure local cache is complete and fix 400 errors
            val resultSet = api.getMessages(encodedForum, encodedTopic, since = null)
            
            // Lookup existing messages to preserve their local state (unread, starred, etc.)
            val existingMessages = messageDao.getByTopic(topicId).first().associateBy { it.remoteId }
            
            val messages = resultSet.messages.map { apiMsg ->
                val existing = existingMessages[apiMsg.id]
                CIXMessage(
                    id = existing?.id ?: 0,
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
                    unread = existing?.unread ?: true,
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

            if (messages.isNotEmpty()) {
                messageDao.insertAll(messages)
            }
        } catch (e: Exception) {
            if (e.message?.contains("not a member", ignoreCase = true) == true) {
                throw NotAMemberException(forum)
            }
            e.printStackTrace()
            throw e
        }
    }

    suspend fun postMessage(forum: String, topic: String, body: String, replyTo: Int): Int = withContext(Dispatchers.IO) {
        try {
            val request = PostMessageRequest(body = body, forum = forum, topic = topic, msgId = replyTo.toString())
            val response = api.postMessage(request)
            val result = extractStringFromXml(response.string())
            
            val messageId = result.toIntOrNull()
            if (messageId != null && messageId > 0) {
                val topicId = HtmlUtils.calculateTopicId(forum, topic)
                val newMessage = CIXMessage(
                    remoteId = messageId,
                    author = "me",
                    body = body,
                    date = System.currentTimeMillis(),
                    commentId = replyTo,
                    rootId = 0,
                    topicId = topicId,
                    forumName = forum,
                    topicName = topic,
                    unread = false
                )
                messageDao.insert(newMessage)
                messageId
            } else if (result == "Success") {
                -1
            } else {
                0
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    suspend fun joinForum(forum: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forum)
            val response = api.joinForum(encodedForum)
            val result = extractStringFromXml(response.string())
            result == "Success"
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun toggleStar(message: CIXMessage) = withContext(Dispatchers.IO) {
        val updatedMessage = message.copy(starred = !message.starred)
        messageDao.update(updatedMessage)
    }

    suspend fun markAsRead(message: CIXMessage) = withContext(Dispatchers.IO) {
        if (message.unread) {
            val updatedMessage = message.copy(unread = false)
            messageDao.update(updatedMessage)
        }
    }
}
