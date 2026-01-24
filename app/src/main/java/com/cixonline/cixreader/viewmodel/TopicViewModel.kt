package com.cixonline.cixreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.UserProfile
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.repository.MessageRepository
import com.cixonline.cixreader.repository.NotAMemberException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TopicViewModel(
    private val api: CixApi,
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

    private val _selectedProfile = MutableStateFlow<UserProfile?>(null)
    val selectedProfile: StateFlow<UserProfile?> = _selectedProfile

    val messages: StateFlow<List<CIXMessage>> = combine(
        repository.getMessagesForTopic(topicId),
        _searchQuery
    ) { allMessages, query ->
        // Filter out withdrawn messages
        val filtered = allMessages.filter { msg ->
            !msg.body.contains("<<withdrawn by author>>", ignoreCase = true) &&
            !msg.body.contains("<<withdrawn by moderator>>", ignoreCase = true) &&
            !msg.body.contains("<<withdrawn by system administrator>>", ignoreCase = true)
        }
        
        if (query.isBlank()) {
            filtered
        } else {
            filtered.filter { msg ->
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

    private fun getEffectiveRootId(msg: CIXMessage): Int {
        return if (msg.rootId != 0) msg.rootId else msg.remoteId
    }

    private fun getThreadInOrder(allMessages: List<CIXMessage>, rootId: Int): List<CIXMessage> {
        val children = allMessages.groupBy { it.commentId }
        val result = mutableListOf<CIXMessage>()
        
        fun walk(m: CIXMessage) {
            result.add(m)
            children[m.remoteId]?.sortedBy { it.date }?.forEach { walk(it) }
        }
        
        val root = allMessages.find { it.remoteId == rootId }
        if (root != null) {
            walk(root)
        } else {
            // If explicit root is missing, collect all messages with this rootId
            val threadNodes = allMessages.filter { (it.rootId != 0 && it.rootId == rootId) || (it.rootId == 0 && it.remoteId == rootId) }
                .sortedBy { it.date }
            
            val seen = mutableSetOf<Int>()
            threadNodes.forEach { node ->
                if (!seen.contains(node.remoteId) && allMessages.none { it.remoteId == node.commentId }) {
                    // This is a "local root"
                    fun localWalk(m: CIXMessage) {
                        if (seen.add(m.remoteId)) {
                            result.add(m)
                            children[m.remoteId]?.sortedBy { it.date }?.forEach { localWalk(it) }
                        }
                    }
                    localWalk(node)
                }
            }
        }
        return result
    }

    fun findNextUnread(currentMessageId: Int?): CIXMessage? {
        val allMessages = messages.value
        if (allMessages.isEmpty()) return null

        val roots = allMessages.filter { it.isRoot }.sortedByDescending { it.date }
        
        if (currentMessageId == null) {
            // Find first unread following thread order
            for (root in roots) {
                val thread = getThreadInOrder(allMessages, root.remoteId)
                val unread = thread.find { it.unread }
                if (unread != null) return unread
            }
            return null
        }

        val currentMsg = allMessages.find { it.remoteId == currentMessageId } ?: return null
        val currentRootId = getEffectiveRootId(currentMsg)
        
        // 1. Search in the current thread after the current message
        val currentThread = getThreadInOrder(allMessages, currentRootId)
        val currentIndex = currentThread.indexOfFirst { it.remoteId == currentMessageId }
        if (currentIndex != -1) {
            for (i in (currentIndex + 1) until currentThread.size) {
                if (currentThread[i].unread) return currentThread[i]
            }
        }
        
        // 2. Search in subsequent threads
        val currentRootIndex = roots.indexOfFirst { getEffectiveRootId(it) == currentRootId }
        if (currentRootIndex != -1) {
            for (i in (currentRootIndex + 1) until roots.size) {
                val nextThread = getThreadInOrder(allMessages, getEffectiveRootId(roots[i]))
                val unread = nextThread.find { it.unread }
                if (unread != null) return unread
            }
        }
        
        // 3. Wrap around to earlier threads
        val limit = if (currentRootIndex != -1) currentRootIndex else roots.size
        for (i in 0 until limit) {
            val nextThread = getThreadInOrder(allMessages, getEffectiveRootId(roots[i]))
            val unread = nextThread.find { it.unread }
            if (unread != null) return unread
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

    fun showProfile(user: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val profile = api.getProfile(user)
                _selectedProfile.value = profile
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun dismissProfile() {
        _selectedProfile.value = null
    }

    fun onScrollToMessageComplete() {
        _scrollToMessageId.value = null
    }
}

class TopicViewModelFactory(
    private val api: CixApi,
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
            return TopicViewModel(api, repository, forumName, topicName, topicId, initialMessageId, initialRootId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
