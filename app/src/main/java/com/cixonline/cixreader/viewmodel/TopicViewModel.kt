package com.cixonline.cixreader.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.api.PostAttachment
import com.cixonline.cixreader.api.UserProfile
import com.cixonline.cixreader.db.CachedProfileDao
import com.cixonline.cixreader.db.DraftDao
import com.cixonline.cixreader.db.FolderDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.models.Draft
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.repository.MessageRepository
import com.cixonline.cixreader.repository.NotAMemberException
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed class NextUnreadItem {
    data class Message(val message: CIXMessage) : NextUnreadItem()
    data class Topic(val forum: String, val topic: String, val topicId: Int) : NextUnreadItem()
    data class Forum(val forum: String) : NextUnreadItem()
    object NoMoreUnread : NextUnreadItem()
}

class TopicViewModel(
    private val api: CixApi,
    private val repository: MessageRepository,
    private val cachedProfileDao: CachedProfileDao,
    private val draftDao: DraftDao,
    private val folderDao: FolderDao,
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
        val filtered = allMessages.filter { msg -> !msg.isWithdrawn() }
        
        if (query.isBlank()) {
            filtered
        } else {
            filtered.filter { msg ->
                msg.author.contains(query, ignoreCase = true) ||
                msg.body.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val messageTree = messages.map { list ->
        list.groupBy { it.commentId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val roots = messages.map { list ->
        list.filter { it.isRoot }.sortedByDescending { it.date }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (initialRootId != 0 || initialMessageId != 0) {
                    repository.fetchMessageAndChildren(
                        forumName, 
                        topicName, 
                        if (initialRootId != 0) initialRootId else initialMessageId, 
                        topicId
                    )
                } else {
                    val firstUnreadId = repository.getFirstUnreadMessageId(forumName, topicName)
                    if (firstUnreadId > 0) {
                        repository.fetchThreadThenBackfill(forumName, topicName, firstUnreadId, topicId)
                        _scrollToMessageId.value = firstUnreadId
                    } else {
                        repository.refreshMessages(forumName, topicName, topicId)
                        val currentMessages = repository.getMessagesForTopic(topicId).first()
                        if (currentMessages.isNotEmpty()) {
                            currentMessages.maxByOrNull { it.date }?.let { mostRecent ->
                                _scrollToMessageId.value = mostRecent.remoteId
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
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

    suspend fun findNextUnreadItem(currentMessageId: Int?): NextUnreadItem {
        val allMessages = messages.value
        val tree = messageTree.value
        
        if (currentMessageId != null) {
            val currentMsg = allMessages.find { it.remoteId == currentMessageId }
            if (currentMsg != null) {
                // Perform tree-based traversal within the current conversation
                val nextInThread = findNextUnreadInThread(currentMsg, allMessages, tree)
                if (nextInThread != null) {
                    return NextUnreadItem.Message(nextInThread)
                }
            }
        }

        // When current thread is exhausted, do NOT automatically jump. 
        // Return NoMoreUnread to let the user know they've reached the end of this branch.
        return NextUnreadItem.NoMoreUnread
    }

    private fun findNextUnreadInThread(current: CIXMessage, allMessages: List<CIXMessage>, tree: Map<Int, List<CIXMessage>>): CIXMessage? {
        // 1. Check direct comments (children), most recent unread first
        // CIX messages are usually chronological, so we take the "most recent" unread comment
        val children = tree[current.remoteId]?.sortedByDescending { it.date } ?: emptyList()
        for (child in children) {
            if (child.unread) return child
        }
        
        // 2. Backtrack up the tree to parent and find next most recent sibling
        var node = current
        while (node.commentId != 0) {
            val parentId = node.commentId
            // Siblings are all comments to the same parent
            val siblings = tree[parentId]?.sortedByDescending { it.date } ?: emptyList()
            val myIndex = siblings.indexOfFirst { it.remoteId == node.remoteId }
            
            if (myIndex != -1) {
                // Search for the next sibling in order (siblings are sorted by descending date)
                for (i in (myIndex + 1) until siblings.size) {
                    if (siblings[i].unread) return siblings[i]
                }
            }
            // Move up to parent and continue search
            node = allMessages.find { it.remoteId == parentId } ?: break
        }
        
        return null
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
                context.contentResolver.openInputStream(attachmentUri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    listOf(PostAttachment(data = Base64.encodeToString(bytes, Base64.NO_WRAP), filename = attachmentName))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else null

        val currentAuthor = NetworkClient.getUsername()
        val resultId = repository.postMessage(forumName, topicName, body, replyToId, currentAuthor, attachments)
        
        if (resultId != 0) {
            draftDao.deleteDraftForContext(forumName, topicName, replyToId)
            if (resultId > 0) {
                _scrollToMessageId.value = resultId
            }
        }
        _isLoading.value = false
        return resultId != 0
    }

    fun saveDraft(replyToId: Int, body: String, attachmentUri: Uri? = null, attachmentName: String? = null) {
        viewModelScope.launch {
            val draft = Draft(
                forumName = forumName,
                topicName = topicName,
                replyToId = replyToId,
                body = body,
                attachmentUri = attachmentUri?.toString(),
                attachmentName = attachmentName
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

    fun withdrawMessage(message: CIXMessage) {
        viewModelScope.launch {
            _isLoading.value = true
            if (repository.withdrawMessage(message)) {
                refresh()
            }
            _isLoading.value = false
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
    private val folderDao: FolderDao,
    private val forumName: String,
    private val topicName: String,
    private val topicId: Int,
    private val initialMessageId: Int = 0,
    private val initialRootId: Int = 0
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TopicViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TopicViewModel(api, repository, cachedProfileDao, draftDao, folderDao, forumName, topicName, topicId, initialMessageId, initialRootId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
