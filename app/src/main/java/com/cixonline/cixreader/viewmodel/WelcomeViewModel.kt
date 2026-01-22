package com.cixonline.cixreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.InterestingThreadApi
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.api.WhoApi
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WelcomeViewModel(
    private val api: CixApi,
    private val messageDao: MessageDao
) : ViewModel() {

    private val _onlineUsers = MutableStateFlow<List<WhoApi>>(emptyList())
    val onlineUsers: StateFlow<List<WhoApi>> = _onlineUsers

    private val _interestingThreads = MutableStateFlow<List<InterestingThreadApi>>(emptyList())
    val interestingThreads: StateFlow<List<InterestingThreadApi>> = _interestingThreads

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = api.getInterestingThreads(count = 20)
                val threads = response.messages ?: emptyList()
                
                // Show raw threads immediately while we resolve roots
                _interestingThreads.value = threads

                // Resolve root messages in parallel
                val resolvedThreads = threads.map { thread ->
                    async {
                        val forum = thread.forum ?: ""
                        val topic = thread.topic ?: ""
                        val rootId = thread.rootId
                        if (rootId > 0) {
                            val pseudoTopicId = (forum + topic).hashCode()
                            
                            // Check DB first
                            val cachedRoot = messageDao.getByRemoteId(rootId, pseudoTopicId)
                            if (cachedRoot != null) {
                                thread.author = cachedRoot.author
                                thread.body = cachedRoot.body
                                thread.subject = null // Force fallback to body (first line) in UI
                                thread.dateTime = DateUtils.formatDateTime(cachedRoot.date)
                            } else {
                                // Fetch from API
                                try {
                                    val encodedForum = HtmlUtils.cixEncode(forum)
                                    val encodedTopic = HtmlUtils.cixEncode(topic)
                                    val resultSet = api.getMessages(encodedForum, encodedTopic, since = (rootId - 1).toString())
                                    val rootApi = resultSet.messages.find { it.id == rootId }
                                    if (rootApi != null) {
                                        val decodedAuthor = HtmlUtils.decodeHtml(rootApi.author ?: "")
                                        val decodedBody = HtmlUtils.decodeHtml(rootApi.body ?: "")
                                        
                                        thread.author = decodedAuthor
                                        thread.body = decodedBody
                                        thread.subject = null // Force fallback to body (first line) in UI
                                        thread.dateTime = rootApi.dateTime
                                        
                                        // Cache it
                                        messageDao.insert(CIXMessage(
                                            remoteId = rootApi.id,
                                            author = decodedAuthor,
                                            body = decodedBody,
                                            date = DateUtils.parseCixDate(rootApi.dateTime),
                                            commentId = rootApi.replyTo,
                                            rootId = rootApi.rootId,
                                            topicId = pseudoTopicId,
                                            forumName = forum,
                                            topicName = topic,
                                            unread = true
                                        ))
                                    }
                                } catch (e: Exception) {
                                    // Fallback to original "latest" message data if root fetch fails
                                }
                            }
                        }
                        thread
                    }
                }.awaitAll()
                
                _interestingThreads.value = resolvedThreads
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun getFirstUnreadMessage(): CIXMessage? {
        return messageDao.getFirstUnreadMessage()
    }
}

class WelcomeViewModelFactory(
    private val api: CixApi,
    private val messageDao: MessageDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WelcomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WelcomeViewModel(api, messageDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
