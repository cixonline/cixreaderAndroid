package com.cixonline.cixreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.InterestingThreadApi
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.db.FolderDao
import com.cixonline.cixreader.db.DirForumDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.api.WhoApi
import com.cixonline.cixreader.api.PostMessageRequest
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
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val dirForumDao: DirForumDao
) : ViewModel() {

    private val _onlineUsers = MutableStateFlow<List<WhoApi>>(emptyList())
    val onlineUsers: StateFlow<List<WhoApi>> = _onlineUsers

    private val _interestingThreads = MutableStateFlow<List<InterestingThreadUI>>(emptyList())
    val interestingThreads: StateFlow<List<InterestingThreadUI>> = _interestingThreads

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    val allForums: Flow<List<Folder>> = folderDao.getAll().map { folders ->
        folders.filter { it.isRootFolder }
    }

    private val _selectedForum = MutableStateFlow<Folder?>(null)
    val selectedForum: StateFlow<Folder?> = _selectedForum

    val topicsForSelectedForum: StateFlow<List<Folder>> = _selectedForum.flatMapLatest { forum ->
        if (forum == null) flowOf(emptyList())
        else folderDao.getChildren(forum.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectForum(forum: Folder?) {
        _selectedForum.value = forum
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Fetch interesting threads from API.
                val response = api.getInterestingThreads(count = 20)
                val threads = response.messages ?: emptyList()
                
                // Group by thread to avoid duplicates, preserving latest activity order.
                val uniqueThreads = threads.distinctBy { "${it.forum}/${it.topic}/${it.rootId}" }

                // Resolve root messages in parallel.
                val resolvedThreads = uniqueThreads.map { thread ->
                    async {
                        val forum = thread.forum ?: ""
                        val topic = thread.topic ?: ""
                        val rootId = thread.rootId
                        val pseudoTopicId = (forum + topic).hashCode()
                        
                        // Initialize with current thread data (likely the latest reply info)
                        var displayAuthor = thread.author ?: ""
                        var displayBody = thread.body ?: ""
                        var displayDateTime = thread.dateTime ?: ""
                        var displaySubject = thread.subject
                        
                        // If the message from interestingthreads API already has a subject, 
                        // it is almost certainly the root message itself.
                        var isResolved = !displaySubject.isNullOrBlank()

                        // If it's a reply (no subject), try to resolve the actual root content.
                        if (!isResolved && rootId > 0) {
                            // 1. Try DB
                            val cachedRoot = messageDao.getByRemoteId(rootId, pseudoTopicId)
                            if (cachedRoot != null) {
                                displayAuthor = cachedRoot.author
                                displayBody = cachedRoot.body
                                displayDateTime = DateUtils.formatDateTime(cachedRoot.date)
                                isResolved = true
                            } else {
                                // 2. Try API
                                try {
                                    val encodedForum = HtmlUtils.cixEncode(forum)
                                    val encodedTopic = HtmlUtils.cixEncode(topic)
                                    val resultSet = api.getMessages(encodedForum, encodedTopic, since = (rootId - 1).toString())
                                    val rootApi = resultSet.messages.find { it.id == rootId }
                                    if (rootApi != null) {
                                        displayAuthor = rootApi.author ?: ""
                                        displayBody = rootApi.body ?: ""
                                        displayDateTime = rootApi.dateTime ?: ""
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
                                    // Ignore errors during resolution; we will fallback to displaying the interesting message info.
                                }
                            }
                        }
                        
                        val formattedDateTime = if (displayDateTime.contains("T") || displayDateTime.contains("-")) {
                            DateUtils.formatCixDate(displayDateTime)
                        } else {
                            displayDateTime
                        }

                        InterestingThreadUI(
                            forum = forum,
                            topic = topic,
                            rootId = rootId,
                            author = HtmlUtils.decodeHtml(displayAuthor),
                            dateTime = formattedDateTime,
                            body = HtmlUtils.decodeHtml(displayBody),
                            subject = if (!displaySubject.isNullOrBlank()) HtmlUtils.decodeHtml(displaySubject!!) else null,
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

    suspend fun postMessage(forum: String, topic: String, body: String): Boolean {
        return try {
            val request = PostMessageRequest(body = body, forum = forum, topic = topic)
            api.postMessage(request)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

class WelcomeViewModelFactory(
    private val api: CixApi,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val dirForumDao: DirForumDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WelcomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WelcomeViewModel(api, messageDao, folderDao, dirForumDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
