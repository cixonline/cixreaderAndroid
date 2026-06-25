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
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val membershipMutex = Mutex()

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
        folderDao.mergeTopicUnreadCounts()
        folderDao.recalculateForumUnreadCounts()
    }

    suspend fun refreshMessages(forumName: String, topicName: String, topicId: Int, force: Boolean = false, sinceOverride: String? = null) = withContext(Dispatchers.IO) {
        try {
            val latestMessage = messageDao.getLatestMessage(topicId)
            
            if (force || latestMessage == null) {
                Log.d(tag, "Refreshing messages for $forumName/$topicName via backfill.")
                backfillToMessageOne(forumName, topicName, topicId)
            } else {
                Log.d(tag, "Topic $forumName/$topicName already has messages. Skipping full refresh.")
            }
        } catch (e: Exception) {
            handleException(e, forumName)
        }
    }

    private fun isMembershipError(e: HttpException): Boolean {
        val code = e.code()
        if (code == 403) return true
        if (code != 400) return false
        
        val statusMessage = e.response()?.message() ?: ""
        val fullMessage = e.message ?: ""
        val combined = "$statusMessage $fullMessage".lowercase()
        
        // If it's specifically "no row" when listing a message, it's likely "Not Found" not membership
        if (combined.contains("no row at position 0") && combined.contains("listing the message")) {
            return false
        }
        
        val isMatch = combined.contains("not a member") || combined.contains("no row at position 0")
               
        if (isMatch) {
            Log.d(tag, "Confirmed membership error: code=$code, message=$fullMessage")
        }
        return isMatch
    }

    private suspend fun <T> withMembershipRetry(forumName: String, topicName: String? = null, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: Exception) {
            if (e is HttpException && isMembershipError(e)) {
                Log.i(tag, "Detected membership error for $forumName${if (topicName != null) "/$topicName" else ""}. Attempting automatic join.")
                
                val joined = membershipMutex.withLock {
                    val folders = folderDao.getAllSync()
                    val isForumJoined = folders.any { it.isRootFolder && it.name.equals(forumName, ignoreCase = true) }
                    
                    if (!isForumJoined) {
                        Log.d(tag, "Not a member of forum $forumName. Joining forum and all topics.")
                        joinForum(forumName)
                    } else if (topicName != null) {
                        // Double check topic membership if forum is already joined
                        val topicId = HtmlUtils.calculateTopicId(forumName, topicName)
                        val isTopicJoined = folders.any { it.id == topicId }
                        if (!isTopicJoined) {
                            Log.d(tag, "Member of forum $forumName but not topic $topicName. Joining topic via conf/topic.")
                            joinTopic(forumName, topicName)
                        } else {
                            Log.d(tag, "Already joined forum $forumName and topic $topicName according to DB, but server said no. Re-joining topic just in case.")
                            joinTopic(forumName, topicName)
                        }
                    } else {
                        false
                    }
                }
                
                if (joined) {
                    Log.i(tag, "Auto-join successful. Retrying operation.")
                    return block()
                }
            }
            throw e
        }
    }

    private fun handleException(e: Exception, forumName: String) {
        if (e is HttpException) {
            if (isMembershipError(e)) {
                throw NotAMemberException(forumName)
            }
            
            val code = e.code()
            if (code == 400) {
                val fullMsg = e.message ?: ""
                if (fullMsg.contains("no row at position 0", ignoreCase = true)) {
                    Log.w(tag, "HTTP 400 (Not Found/No row) for $forumName: $fullMsg")
                    throw e
                }
                Log.w(tag, "HTTP 400 for $forumName: $fullMsg")
            }
        }
        
        if (e.message?.contains("not a member", ignoreCase = true) == true) {
            throw NotAMemberException(forumName)
        }
        Log.e(tag, "Operation failed", e)
        throw e
    }

    suspend fun backfillToMessageOne(forumName: String, topicName: String, topicId: Int) = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forumName)
            val encodedTopic = HtmlUtils.cixEncode(topicName)

            Log.d(tag, "Backfilling $forumName/$topicName using threads.xml")
            val resultSet = withMembershipRetry(forumName, topicName) {
                api.getTopicThreads(encodedForum, encodedTopic)
            }

            if (resultSet.threads.isNotEmpty()) {
                Log.d(tag, "Backfill fetched ${resultSet.threads.size} roots for $forumName/$topicName")
                
                val mappedMessages = resultSet.threads.map { thread ->
                    MessageApi().apply {
                        id = thread.id
                        author = thread.author
                        body = thread.body
                        dateTime = thread.date
                        forum = thread.forum
                        topic = thread.topic
                        rootId = thread.rootId
                        replyTo = 0 
                        unread = thread.unread > 0
                        threadReplies = thread.replies
                        threadUnread = thread.unread
                    }
                }
                saveMessagesToDb(mappedMessages, forumName, topicName, topicId, isFromThreadsXml = true)
            }
            Log.d(tag, "Backfill complete for $forumName/$topicName")
        } catch (e: Exception) {
            handleException(e, forumName)
        }
    }

    private suspend fun saveMessagesToDb(
        apiMessages: List<MessageApi>, 
        forum: String, 
        topic: String, 
        topicId: Int,
        isFromThreadsXml: Boolean = false
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
                apiMsg.unread ?: (existing?.unread ?: true)
            }

            if (existing == null) {
                logRepository.log("Inserted new message #${apiMsg.id} in $forum/$topic. Unread: $isUnread", "INSERT")
            }

            CIXMessage(
                id = existing?.id ?: 0,
                remoteId = apiMsg.id,
                author = HtmlUtils.normalizeName(apiMsg.author ?: ""),
                body = HtmlUtils.formatForStorage(apiMsg.body),
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
                withdrawPending = existing?.withdrawPending ?: false,
                threadReplies = if (isFromThreadsXml) apiMsg.threadReplies else (existing?.threadReplies ?: -1),
                threadUnread = if (isFromThreadsXml) apiMsg.threadUnread else (existing?.threadUnread ?: -1)
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
            val threadSet = withMembershipRetry(forum, topic) {
                api.getThread(encodedForum, encodedTopic, msgId)
            }
            if (threadSet.messages.isNotEmpty()) {
                saveMessagesToDb(threadSet.messages, forum, topic, topicId)
            }
        } catch (e: Exception) {
            handleException(e, forum)
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
            val response = withMembershipRetry(forum, topic) {
                api.getFirstUnread(encodedForum, encodedTopic)
            }
            extractIntFromXml(response.string())
        } catch (e: Exception) {
            Log.w(tag, "Failed to get first unread for $forum/$topic: ${e.message}")
            0
        }
    }

    suspend fun resolveRootId(forum: String, topic: String, msgId: Int): Int = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forum)
            val encodedTopic = HtmlUtils.cixEncode(topic)
            val response = withMembershipRetry(forum, topic) {
                api.getRootMessageId(encodedForum, encodedTopic, msgId)
            }
            extractIntFromXml(response.string())
        } catch (e: Exception) {
            Log.w(tag, "Failed to resolve root ID for $forum/$topic/$msgId", e)
            0
        }
    }

    suspend fun fetchMessageMetadata(forum: String, topic: String, msgId: Int, topicId: Int): CIXMessage? = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forum)
            val encodedTopic = HtmlUtils.cixEncode(topic)
            
            val messageApi = withMembershipRetry(forum, topic) {
                api.getMessage(encodedForum, encodedTopic, msgId)
            }
            
            val messageDate = DateUtils.parseCixDate(messageApi.dateTime)
            val currentUsername = NetworkClient.getUsername()
            
            val isReadFromServer = messageApi.status?.contains("R", ignoreCase = true) == true || messageApi.unread == false
            val isFromSelf = messageApi.author?.equals(currentUsername, ignoreCase = true) == true
            
            val message = CIXMessage(
                remoteId = messageApi.id,
                author = HtmlUtils.normalizeName(messageApi.author ?: ""),
                body = HtmlUtils.formatForStorage(messageApi.body),
                date = messageDate,
                commentId = messageApi.replyTo,
                rootId = if (messageApi.rootId != 0) messageApi.rootId else (if (messageApi.replyTo == 0) messageApi.id else 0),
                topicId = topicId,
                forumName = forum,
                topicName = topic,
                subject = HtmlUtils.decodeHtml(messageApi.subject),
                unread = !(isReadFromServer || isFromSelf)
            )
            messageDao.insert(message)
            message
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 400) {
                val fullMsg = e.message ?: ""
                if (fullMsg.contains("no row at position 0", ignoreCase = true)) {
                    Log.w(tag, "Message $msgId not found in $forum/$topic (Server: no row at position 0)")
                    return@withContext null
                }
            }
            Log.e(tag, "fetchMessageMetadata failed for $forum/$topic/$msgId", e)
            null
        }
    }

    suspend fun fetchMessageAndChildren(forum: String, topic: String, msgId: Int, topicId: Int) = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(forum)
            val encodedTopic = HtmlUtils.cixEncode(topic)
            
            val messageApi = withMembershipRetry(forum, topic) {
                api.getMessage(encodedForum, encodedTopic, msgId)
            }
            val existing = messageDao.getByRemoteId(msgId, topicId)
            
            val messageDate = DateUtils.parseCixDate(messageApi.dateTime)
            val currentUsername = NetworkClient.getUsername()
            
            val isReadFromServer = messageApi.status?.contains("R", ignoreCase = true) == true || messageApi.unread == false
            val isFromSelf = messageApi.author?.equals(currentUsername, ignoreCase = true) == true
            
            val isUnread = if (isReadFromServer || isFromSelf) {
                false
            } else {
                messageApi.unread ?: (existing?.unread ?: true)
            }

            if (existing == null) {
                logRepository.log("Inserted new message #${msgId} in $forum/$topic. Unread: $isUnread", "INSERT")
            }

            val message = CIXMessage(
                id = existing?.id ?: 0,
                remoteId = messageApi.id,
                author = HtmlUtils.normalizeName(messageApi.author ?: ""),
                body = HtmlUtils.formatForStorage(messageApi.body),
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
                withdrawPending = existing?.withdrawPending ?: false,
                threadReplies = existing?.threadReplies ?: -1,
                threadUnread = existing?.threadUnread ?: -1
            )
            messageDao.insert(message)
            recalculateCounts() 
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 400) {
                val fullMsg = e.message ?: ""
                if (fullMsg.contains("no row at position 0", ignoreCase = true) && 
                    fullMsg.contains("listing the message", ignoreCase = true)) {
                    Log.w(tag, "Message $msgId not found in $forum/$topic during fetchMessageAndChildren")
                    return@withContext
                }
            }
            handleException(e, forum)
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
            val topicParam = HtmlUtils.normalizeTopicName(topic)

            val processedAttachments: List<PostAttachment>? = if (attachments != null) {
                attachments.map { it.copy(filename = HtmlUtils.encodeFilename(it.filename)) }
            } else {
                null
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

            val response = withMembershipRetry(forum, topic) {
                JsonNetworkClient.api.postMessageJson(request)
            }

            val rootId = if (replyTo != 0) {
                val parentMessage = messageDao.getByRemoteId(replyTo, topicId)
                parentMessage?.rootId ?: 0
            } else {
                response.id
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

    suspend fun joinForumSync(forum: String): String = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(HtmlUtils.decodeHtml(forum))
            Log.d(tag, "Joining forum: $forum (encoded: $encodedForum)")
            val response = api.joinForum(encodedForum)
            val responseBody = response.string()
            Log.d(tag, "Join forum response body: $responseBody")
            val result = extractStringFromXml(responseBody).trim()
            if (result == "Success") {
                refreshFoldersFromServer()
                delay(2000)
            } else if (result.contains("Already", ignoreCase = true)) {
                delay(200)
            }
            result
        } catch (e: Exception) {
            Log.e(tag, "Join forum failed", e)
            "Error: ${e.message}"
        }
    }

    suspend fun joinForum(forum: String): Boolean {
        val result = joinForumSync(forum)
        return result == "Success" || result.contains("Already", ignoreCase = true)
    }

    suspend fun joinTopicSync(forum: String, topic: String): String = withContext(Dispatchers.IO) {
        try {
            val encodedForum = HtmlUtils.cixEncode(HtmlUtils.decodeHtml(forum))
            val encodedTopic = HtmlUtils.cixEncode(HtmlUtils.decodeHtml(topic))
            Log.d(tag, "Joining topic: $forum/$topic")
            val response = api.joinTopic(encodedForum, encodedTopic)
            val responseBody = response.string()
            Log.d(tag, "Join topic response body: $responseBody")
            val result = extractStringFromXml(responseBody).trim()
            if (result == "Success") {
                delay(2000)
            } else if (result.contains("Already", ignoreCase = true)) {
                delay(200)
            }
            result
        } catch (e: Exception) {
            Log.e(tag, "Join topic failed", e)
            "Error: ${e.message}"
        }
    }

    suspend fun joinTopic(forum: String, topic: String): Boolean {
        val result = joinTopicSync(forum, topic)
        return result == "Success" || result.contains("Already", ignoreCase = true)
    }

    suspend fun refreshFoldersFromServer() {
        try {
            val resultSet = api.getForums()
            val folders = resultSet.forums.mapNotNull { row ->
                val name = row.name ?: return@mapNotNull null
                val normalizedName = HtmlUtils.normalizeName(name)
                Folder(id = HtmlUtils.calculateForumId(normalizedName), name = normalizedName, parentId = -1, unread = row.effectiveUnread?.toIntOrNull() ?: 0, unreadPriority = row.priority?.toIntOrNull() ?: 0)
            }
            folderDao.insertAll(folders)
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh folders from server", e)
        }
    }

    suspend fun withdrawMessage(message: CIXMessage): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = withMembershipRetry(message.forumName, message.topicName) {
                api.withdrawMessage(
                    HtmlUtils.cixEncode(message.forumName),
                    HtmlUtils.cixEncode(message.topicName),
                    message.remoteId
                )
            }
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
            messageDao.updateUnreadAndPending(message.id, unread = false, readPending = true)
            folderDao.decrementUnread(message.topicId)
            if (message.rootId != 0) {
                messageDao.decrementThreadUnread(message.rootId, message.topicId)
            }
            recalculateCounts()
            logRepository.log("Marked #${message.remoteId} as read (locally pending) in ${message.forumName}/${message.topicName}", "READ_STATUS")
        }
    }

    suspend fun markTopicAsRead(forumName: String, topicName: String, topicId: Int) = withContext(Dispatchers.IO) {
        try {
            messageDao.markTopicAsRead(topicId)
            messageDao.zeroThreadUnreads(topicId)
            folderDao.setUnread(topicId, 0)
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
            Log.d(tag, "Server returned ${resultSet.userTopics.size} user topics")
            folderDao.zeroAllTopicUnreads()

            resultSet.userTopics.forEach { result ->
                val rawForum = result.forum ?: return@forEach
                val rawTopic = result.topic ?: return@forEach
                val forumName = HtmlUtils.normalizeName(rawForum)
                val topicName = HtmlUtils.normalizeTopicName(rawTopic)
                val topicId = HtmlUtils.calculateTopicId(forumName, topicName)
                val unreadCount = result.effectiveUnread?.trim()?.toIntOrNull() ?: 0
                
                var topic = folderDao.getById(topicId)
                if (topic == null) {
                    val forumId = HtmlUtils.calculateForumId(forumName)
                    var forum = folderDao.getById(forumId)
                    if (forum == null) {
                        forum = Folder(id = forumId, name = forumName, parentId = -1)
                        folderDao.insert(forum)
                        logRepository.log("Inserted new forum $forumName during unread sync", "INSERT")
                    }
                    topic = Folder(id = topicId, name = topicName, parentId = forumId, unread = unreadCount)
                    folderDao.insert(topic)
                    logRepository.log("Inserted new topic $forumName/$topicName during unread sync with $unreadCount unreads", "INSERT")
                } else {
                    folderDao.setUnread(topicId, unreadCount)
                }
            }
            folderDao.mergeTopicUnreadCounts()
            folderDao.recalculateForumUnreadCounts()
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh all topic unreads", e)
        }
    }
}
