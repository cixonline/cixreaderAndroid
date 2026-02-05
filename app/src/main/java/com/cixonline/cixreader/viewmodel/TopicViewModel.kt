package com.cixonline.cixreader.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.PostAttachment
import com.cixonline.cixreader.api.UserProfile
import com.cixonline.cixreader.db.CachedProfileDao
import com.cixonline.cixreader.db.DraftDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.models.Draft
import com.cixonline.cixreader.repository.MessageRepository
import com.cixonline.cixreader.repository.NotAMemberException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TopicViewModel(
    private val api: CixApi,
    private val repository: MessageRepository,
    private val cachedProfileDao: CachedProfileDao,
    private val draftDao: DraftDao,
    val forumName: String,
    val topicName: String,
    val topicId: Int,
    val initialMessageId: Int = 0,
    val initialRootId: Int = 0
) : ViewModel(), ProfileHost {

    private val profileDelegate = ProfileDelegate(api, cachedProfileDao)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _showJoinDialog = MutableStateFlow<String?>(null)
    val showJoinDialog: StateFlow<String?> = _showJoinDialog

    private val _scrollToMessageId = MutableStateFlow<Int?>(if (initialMessageId != 0) initialMessageId else null)
    val scrollToMessageId: StateFlow<Int?> = _scrollToMessageId

    override val selectedProfile: StateFlow<UserProfile?> = profileDelegate.selectedProfile
    override val selectedResume: StateFlow<String?> = profileDelegate.selectedResume
    override val selectedMugshotUrl: StateFlow<String?> = profileDelegate.selectedMugshotUrl
    override val isProfileLoading: StateFlow<Boolean> = profileDelegate.isLoading

    val messages: StateFlow<List<CIXMessage>> = combine(
        repository.getMessagesForTopic(topicId),
        _searchQuery
    ) { allMessages, query ->
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

    fun findNextUnread(currentMessageId: Int?): CIXMessage? {
        val allMessages = messages.value
        if (allMessages.isEmpty()) return null

        val children = allMessages.groupBy { it.commentId }
        val roots = allMessages.filter { it.isRoot }.sortedByDescending { it.date }
        
        if (currentMessageId == null) {
            for (root in roots) {
                val thread = getThreadInOrder(allMessages, children, root.remoteId)
                val unread = thread.find { it.unread }
                if (unread != null) return unread
            }
            return null
        }

        val currentMsg = allMessages.find { it.remoteId == currentMessageId } ?: return null
        val currentRootId = if (currentMsg.rootId != 0) currentMsg.rootId else currentMsg.remoteId
        
        val currentThread = getThreadInOrder(allMessages, children, currentRootId)
        val currentIndex = currentThread.indexOfFirst { it.remoteId == currentMessageId }
        if (currentIndex != -1) {
            for (i in (currentIndex + 1) until currentThread.size) {
                if (currentThread[i].unread) return currentThread[i]
            }
        }
        
        val currentRootIndex = roots.indexOfFirst { (if (it.rootId != 0) it.rootId else it.remoteId) == currentRootId }
        if (currentRootIndex != -1) {
            for (i in (currentRootIndex + 1) until roots.size) {
                val nextThread = getThreadInOrder(allMessages, children, if (roots[i].rootId != 0) roots[i].rootId else roots[i].remoteId)
                val unread = nextThread.find { it.unread }
                if (unread != null) return unread
            }
        }
        
        val limit = if (currentRootIndex != -1) currentRootIndex else roots.size
        for (i in 0 until limit) {
            val nextThread = getThreadInOrder(allMessages, children, if (roots[i].rootId != 0) roots[i].rootId else roots[i].remoteId)
            val unread = nextThread.find { it.unread }
            if (unread != null) return unread
        }

        return null
    }

    private fun getThreadInOrder(allMessages: List<CIXMessage>, children: Map<Int, List<CIXMessage>>, rootId: Int): List<CIXMessage> {
        val result = mutableListOf<CIXMessage>()
        fun walk(m: CIXMessage) {
            result.add(m)
            children[m.remoteId]?.sortedBy { it.date }?.forEach { walk(it) }
        }
        val root = allMessages.find { it.remoteId == rootId }
        if (root != null) walk(root)
        return result
    }

    suspend fun postReply(
        context: Context,
        replyToId: Int, 
        body: String, 
        attachmentUri: Uri?, 
        attachmentName: String?
    ): Boolean {
        _isLoading.value = true
        
        val attachments = if (attachmentUri != null && attachmentName != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(attachmentUri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    listOf(PostAttachment(data = Base64.encodeToString(bytes, Base64.DEFAULT), filename = attachmentName))
                } else null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else null

        val result = repository.postMessage(forumName, topicName, body, replyToId, attachments)
        val success = result != 0
        if (success) {
            draftDao.deleteDraftForContext(forumName, topicName, replyToId)
            refresh()
        }
        _isLoading.value = false
        return success
    }

    fun saveDraft(replyToId: Int, body: String) {
        viewModelScope.launch {
            val draft = Draft(
                forumName = forumName,
                topicName = topicName,
                replyToId = replyToId,
                body = body
            )
            draftDao.insertDraft(draft)
        }
    }

    suspend fun getDraft(replyToId: Int): Draft? {
        return draftDao.getDraft(forumName, topicName, replyToId)
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

    override fun showProfile(user: String) {
        profileDelegate.showProfile(viewModelScope, user)
    }

    override fun dismissProfile() {
        profileDelegate.dismissProfile()
    }

    fun onScrollToMessageComplete() {
        _scrollToMessageId.value = null
    }
}

class TopicViewModelFactory(
    private val api: CixApi,
    private val repository: MessageRepository,
    private val cachedProfileDao: CachedProfileDao,
    private val draftDao: DraftDao,
    private val forumName: String,
    private val topicName: String,
    private val topicId: Int,
    private val initialMessageId: Int = 0,
    private val initialRootId: Int = 0
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TopicViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TopicViewModel(api, repository, cachedProfileDao, draftDao, forumName, topicName, topicId, initialMessageId, initialRootId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
