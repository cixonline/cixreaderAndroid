package com.cixonline.cixreader.repository

import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.PostMessageRequest
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.utils.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class NotAMemberException(val forumName: String) : Exception("Not a member of forum: $forumName")

class MessageRepository(
    private val api: CixApi,
    private val messageDao: MessageDao
) {
    fun getMessagesForTopic(topicId: Int): Flow<List<CIXMessage>> {
        return messageDao.getByTopic(topicId)
    }

    suspend fun refreshMessages(forum: String, topic: String, topicId: Int, force: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            // Get the latest message we have for this topic to use as a "since" parameter
            val latestMessage = messageDao.getLatestMessage(topicId)
            
            // "api calls should only populate the database when messages in the topic have not already been cached"
            // If force is true, we always fetch. Otherwise we only fetch if we have no messages.
            if (!force && latestMessage != null) {
                // If we already have messages, check if we need to fetch new ones (since the last refresh)
                // For now, let's keep it simple: if we have any, we don't fetch unless forced.
                return@withContext
            }

            // If we have messages, we want to fetch messages *since* the last one
            val since = latestMessage?.remoteId?.toString()
            val resultSet = api.getMessages(forum, topic, since = since)
            
            val messages = resultSet.messages.map { apiMsg ->
                CIXMessage(
                    remoteId = apiMsg.id,
                    author = apiMsg.author ?: "",
                    body = apiMsg.body ?: "",
                    date = DateUtils.parseCixDate(apiMsg.dateTime),
                    commentId = apiMsg.replyTo,
                    rootId = apiMsg.rootId,
                    topicId = topicId
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
            api.postMessage(request)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun joinForum(forum: String): Boolean = withContext(Dispatchers.IO) {
        try {
            api.joinForum(forum)
            true
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
