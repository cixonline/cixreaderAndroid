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
            // Forum and Topic names in the XML/JSON body should NOT be cixEncoded (like URL paths).
            // Normalizing names to ensure they match expected server values.
            val forumParam = HtmlUtils.normalizeName(forum)
            val topicParam = HtmlUtils.normalizeName(topic)

            if (attachments != null && attachments.isNotEmpty()) {
                // When attaching files, we MUST use JSON and set Flags to 1 to get returned links.
                // We create a deep copy of attachments to avoid modifying the original list.
                val processedAttachments = attachments.map { 
                    it.copy(filename = HtmlUtils.encodeFilename(it.filename))
                }

                val request = PostMessage2Request(
                    attachments = processedAttachments,
                    body = body,
                    flags = 1, // Flags: 1 returns links for attachments
                    forum = forumParam,
                    markRead = 1,
                    msgId = replyTo,
                    topic = topicParam
                )
                
                Log.d(tag, "Sending PostMessage2Request JSON with ${processedAttachments.size} attachments")
                val response = api.postMessage2Json(request)
                
                var postedBody = body
                response.attachments?.forEachIndexed { index, attachment ->
                    val marker = "{${index + 1}}"
                    val link = attachment.url
                    if (link != null && !postedBody.contains(marker)) {
                        postedBody += "\n\n$marker ($link)"
                    }
                }

                if (response.id > 0) {
                    insertNewMessageLocal(response.id, author, postedBody, replyTo, forum, topic)
                    return@withContext response.id
                }
                return@withContext 0
            } else {
                // No attachments, use XML for simplicity as before (or JSON if preferred)
                val request = PostMessageRequest(
                    body = body,
                    forum = forumParam,
                    markRead = 1,
                    msgId = replyTo,
                    topic = topicParam
                )
                
                val response = api.postMessage(request)
                val responseString = response.string()
                Log.d(tag, "Received XML response: $responseString")
                
                var messageId = 0
                if (responseString.trim().startsWith("<")) {
                    if (responseString.contains("PostMessage2Response") || responseString.contains("PostMessageResponse")) {
                        try {
                            val resp = serializer.read(PostMessage2Response::class.java, responseString)
                            messageId = resp.id
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to parse response XML", e)
                        }
                    }
                    if (messageId <= 0) {
                        messageId = extractStringFromXml(responseString).toIntOrNull() ?: 0
                    }
                } else {
                    messageId = responseString.trim().toIntOrNull() ?: 0
                }
                
                if (messageId > 0) {
                    insertNewMessageLocal(messageId, author, body, replyTo, forum, topic)
                    return@withContext messageId
                }
                return@withContext 0
            }
        } catch (e: Exception) {
            Log.e(tag, "postMessage failed", e)
            return@withContext 0
        }
    }

    private suspend fun insertNewMessageLocal(id: Int, author: String, body: String, replyTo: Int, forum: String, topic: String) {
        val topicId = HtmlUtils.calculateTopicId(forum, topic)
        val newMessage = CIXMessage(
            remoteId = id,
            author = author,
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
