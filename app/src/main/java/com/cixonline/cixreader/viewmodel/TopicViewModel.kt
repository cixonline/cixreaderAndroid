package com.cixonline.cixreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.repository.MessageRepository
import com.cixonline.cixreader.repository.NotAMemberException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TopicViewModel(
    private val repository: MessageRepository,
    val forumName: String,
    val topicName: String,
    val topicId: Int,
    val initialMessageId: Int = 0,
    val initialRootId: Int = 0
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _showJoinDialog = MutableStateFlow<String?>(null)
    val showJoinDialog: StateFlow<String?> = _showJoinDialog

    private val _scrollToMessageId = MutableStateFlow<Int?>(if (initialMessageId != 0) initialMessageId else null)
    val scrollToMessageId: StateFlow<Int?> = _scrollToMessageId

    val messages: StateFlow<List<CIXMessage>> = combine(
        repository.getMessagesForTopic(topicId),
        _searchQuery
    ) { allMessages, query ->
        if (query.isBlank()) {
            allMessages
        } else {
            allMessages.filter { msg ->
                msg.author.contains(query, ignoreCase = true) ||
                msg.body.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.refreshMessages(forumName, topicName, topicId)
            } catch (e: NotAMemberException) {
                _showJoinDialog.value = e.forumName
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun findNextUnread(currentMessageId: Int?): CIXMessage? {
        val allMessages = messages.value
        val currentIndex = if (currentMessageId != null) {
            allMessages.indexOfFirst { it.remoteId == currentMessageId }
        } else -1
        
        for (i in (currentIndex + 1) until allMessages.size) {
            if (allMessages[i].unread) return allMessages[i]
        }
        return null
    }

    suspend fun postReply(replyToId: Int, body: String): Boolean {
        _isLoading.value = true
        val result = repository.postMessage(forumName, topicName, body, replyToId)
        if (result) {
            refresh()
        }
        _isLoading.value = false
        return result
    }

    fun joinForum(forum: String) {
        viewModelScope.launch {
            _isLoading.value = true
            if (repository.joinForum(forum)) {
                _showJoinDialog.value = null
                refresh()
            }
            _isLoading.value = false
        }
    }

    fun dismissJoinDialog() {
        _showJoinDialog.value = null
    }

    fun onSearchQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun toggleStar(message: CIXMessage) {
        viewModelScope.launch {
            repository.toggleStar(message)
        }
    }

    fun markAsRead(message: CIXMessage) {
        viewModelScope.launch {
            repository.markAsRead(message)
        }
    }

    fun onScrollToMessageComplete() {
        _scrollToMessageId.value = null
    }
}

class TopicViewModelFactory(
    private val repository: MessageRepository,
    private val forumName: String,
    private val topicName: String,
    private val topicId: Int,
    private val initialMessageId: Int = 0,
    private val initialRootId: Int = 0
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TopicViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TopicViewModel(repository, forumName, topicName, topicId, initialMessageId, initialRootId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
