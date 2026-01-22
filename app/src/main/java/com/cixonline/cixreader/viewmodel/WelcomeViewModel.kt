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

data class InterestingThreadUI(
    val forum: String,
    val topic: String,
    val rootId: Int,
    val author: String,
    val dateTime: String,
    val body: String?,
    val subject: String?,
    val isRootResolved: Boolean = false
)

class WelcomeViewModel(
    private val api: CixApi,
    private val messageDao: MessageDao
) : ViewModel() {

    private val _onlineUsers = MutableStateFlow<List<WhoApi>>(emptyList())
    val onlineUsers: StateFlow<List<WhoApi>> = _onlineUsers

    private val _interestingThreads = MutableStateFlow<List<InterestingThreadUI>>(emptyList())
    val interestingThreads: StateFlow<List<InterestingThreadUI>> = _interestingThreads

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private fun InterestingThreadApi.toUI(isResolved: Boolean = false): InterestingThreadUI {
        return InterestingThreadUI(
            forum = forum ?: "",
            topic = topic ?: "",
            rootId = rootId,
            author = if (author != null) HtmlUtils.decodeHtml(author) else "",
            dateTime = dateTime ?: "",
            body = if (body != null) HtmlUtils.decodeHtml(body) else null,
            subject = if (subject != null && subject!!.isNotBlank()) HtmlUtils.decodeHtml(subject) else null,
            isRootResolved = isResolved
        )
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = api.getInterestingThreads(count = 20)
                val threads = response.messages ?: emptyList()
                
                // Show raw threads immediately while we resolve roots
                _interestingThreads.value = threads.map { it.toUI() }

                // Resolve root messages in parallel
                val resolvedThreads = threads.map { thread ->
                    async {
                        val forum = thread.forum ?: ""
                        val topic = thread.topic ?: ""
                        val rootId = thread.rootId
                        var isResolved = false
                        
                        // Default to original data from InterestingThreadApi (which is the LATEST message)
                        var currentAuthor = thread.author
                        var currentBody = thread.body
                        var currentSubject = thread.subject
                        var currentDateTime = thread.dateTime

                        if (rootId > 0) {
                            val pseudoTopicId = (forum + topic).hashCode()
                            
                            // Check DB first for the ROOT message
                            val cachedRoot = messageDao.getByRemoteId(rootId, pseudoTopicId)
                            if (cachedRoot != null) {
                                currentAuthor = cachedRoot.author
                                currentBody = cachedRoot.body
                                currentSubject = null // ROOTs often don't have subjects in CIX, or we prefer body
                                currentDateTime = DateUtils.formatDateTime(cachedRoot.date)
                                isResolved = true
                            } else {
                                // Fetch from API: get messages around rootId to find the root
                                try {
                                    val encodedForum = HtmlUtils.cixEncode(forum)
                                    val encodedTopic = HtmlUtils.cixEncode(topic)
                                    // since=rootId-1 usually gets the root as the first message
                                    val resultSet = api.getMessages(encodedForum, encodedTopic, since = (rootId - 1).toString())
                                    val rootApi = resultSet.messages.find { it.id == rootId }
                                    if (rootApi != null) {
                                        currentAuthor = rootApi.author
                                        currentBody = rootApi.body
                                        currentSubject = null
                                        currentDateTime = DateUtils.formatCixDate(rootApi.dateTime)
                                        isResolved = true
                                        
                                        // Cache the resolved root
                                        messageDao.insert(CIXMessage(
                                            remoteId = rootApi.id,
                                            author = HtmlUtils.decodeHtml(rootApi.author ?: ""),
                                            body = HtmlUtils.decodeHtml(rootApi.body ?: ""),
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
                                    // Fallback if API fails
                                }
                            }
                        }
                        
                        if (!isResolved) {
                            currentDateTime = DateUtils.formatCixDate(currentDateTime)
                        }

                        InterestingThreadUI(
                            forum = forum,
                            topic = topic,
                            rootId = rootId,
                            author = if (currentAuthor != null) HtmlUtils.decodeHtml(currentAuthor) else "",
                            dateTime = currentDateTime ?: "",
                            body = if (currentBody != null) HtmlUtils.decodeHtml(currentBody) else null,
                            subject = if (currentSubject != null && currentSubject.isNotBlank()) HtmlUtils.decodeHtml(currentSubject) else null,
                            isRootResolved = isResolved
                        )
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
