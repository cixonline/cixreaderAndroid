package com.cixonline.cixreader.repository

import android.util.Log
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.PostAttachment
import com.cixonline.cixreader.api.PostMessageRequest
import com.cixonline.cixreader.api.PostMessage2Request
import com.cixonline.cixreader.api.PostMessage2Response
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
import java.io.StringWriter
import org.simpleframework.xml.core.Persister
import org.simpleframework.xml.convert.AnnotationStrategy

class NotAMemberException(val forumName: String) : Exception("Not a member of forum: $forumName")

class MessageRepository(
    private val api: CixApi,
    private val messageDao: MessageDao
) {
    private val tag = "MessageRepository"
    // Use AnnotationStrategy to prevent SimpleXML from adding 'class' attributes to elements
    private val serializer = Persister(AnnotationStrategy())

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
                    val text = parser.text?.trim()
                    if (!text.isNullOrEmpty()) return text
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
            Log.e(tag, "Refresh messages failed", e)
            throw e
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
            // Encode filenames and append attachment links to the message body
            var postedBody = body
            attachments?.forEachIndexed { index, attachment ->
                // Use CIX specific encoding for filenames (no spaces, alphanumeric only)
                val encodedFilename = HtmlUtils.encodeFilename(attachment.filename)
                attachment.filename = encodedFilename
                
                // Add marker to body as per documentation
                val marker = "{${index + 1}}"
                val link = "https://forums.cix.co.uk/secure/download.aspx?f=$encodedFilename"
                
                if (!postedBody.contains(marker)) {
                    postedBody += "\n\n$marker ($link)"
                }
            }
            
            // Forum and Topic names in the XML body should NOT be cixEncoded (like URL paths).
            // Normalizing names to ensure they match expected server values.
            val forumParam = HtmlUtils.normalizeName(forum)
            val topicParam = HtmlUtils.normalizeName(topic)

            val response = if (attachments != null && attachments.isNotEmpty()) {
                val request = PostMessage2Request(
                    attachments = attachments,
                    body = postedBody,
                    forum = forumParam,
                    markRead = 1,
                    msgId = replyTo,
                    topic = topicParam
                )
                
                // Debug: Log the request XML
                try {
                    val writer = StringWriter()
                    serializer.write(request, writer)
                    Log.d(tag, "Sending PostMessage2Request XML: ${writer.toString()}")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to serialize debug request XML (PostMessage2Request)", e)
                }
                
                api.postMessage2(request)
            } else {
                val request = PostMessageRequest(
                    body = postedBody,
                    forum = forumParam,
                    markRead = 1,
                    msgId = replyTo,
                    topic = topicParam
                )
                
                // Debug: Log the request XML
                try {
                    val writer = StringWriter()
                    serializer.write(request, writer)
                    Log.d(tag, "Sending PostMessageRequest XML: ${writer.toString()}")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to serialize debug request XML (PostMessageRequest)", e)
                }
                
                api.postMessage(request)
            }
            
            val responseString = response.string()
            Log.d(tag, "Received response: $responseString")
            
            var messageId = 0
            val finalBody = postedBody

            if (responseString.trim().startsWith("<")) {
                // If it looks like a proper response object, try parsing it
                if (responseString.contains("PostMessage2Response") || responseString.contains("PostMessageResponse")) {
                    try {
                        val resp = serializer.read(PostMessage2Response::class.java, responseString)
                        messageId = resp.id
                        Log.d(tag, "Parsed messageId from expected XML: $messageId")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to parse response XML as PostMessage2Response", e)
                    }
                }
                
                // Fallback: extract text from XML if we didn't get an ID (e.g. <string>84</string>)
                if (messageId <= 0) {
                    messageId = extractStringFromXml(responseString).toIntOrNull() ?: 0
                    Log.d(tag, "Extracted messageId from simple or failed XML: $messageId")
                }
            } else {
                messageId = responseString.trim().toIntOrNull() ?: 0
                Log.d(tag, "Parsed messageId from non-XML response: $messageId")
            }
            
            if (messageId > 0) {
                val topicId = HtmlUtils.calculateTopicId(forum, topic)
                val newMessage = CIXMessage(
                    remoteId = messageId,
                    author = author,
                    body = finalBody,
                    date = System.currentTimeMillis(),
                    commentId = replyTo,
                    rootId = 0,
                    topicId = topicId,
                    forumName = forum,
                    topicName = topic,
                    unread = false
                )
                messageDao.insert(newMessage)
                return@withContext messageId
            } else if (responseString.contains("Success")) {
                return@withContext -1
            } else {
                return@withContext 0
            }
        } catch (e: Exception) {
            Log.e(tag, "postMessage failed", e)
            return@withContext 0
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
            val updatedMessage = message.copy(unread = false, readPending = true)
            messageDao.update(updatedMessage)
        }
    }
}
