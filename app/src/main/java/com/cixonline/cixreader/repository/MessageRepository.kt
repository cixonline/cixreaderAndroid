package com.cixonline.cixreader.repository

import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.PostMessageRequest
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

    suspend fun refreshMessages(forum: String, topic: String, topicId: Int, force: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val latestMessage = messageDao.getLatestMessage(topicId)
            
            // Fetch messages since the last one we have in the database
            val since = if (force) null else latestMessage?.remoteId?.toString()
            
            val encodedForum = HtmlUtils.cixEncode(forum)
            val encodedTopic = HtmlUtils.cixEncode(topic)
            
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
                    forumName = forum,
                    topicName = topic,
                    unread = true
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

    suspend fun postMessage(forum: String, topic: String, body: String, replyTo: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = PostMessageRequest(body = body, forum = forum, topic = topic, msgId = replyTo.toString())
            val response = api.postMessage(request)
            val result = extractStringFromXml(response.string())
            result == "Success"
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
