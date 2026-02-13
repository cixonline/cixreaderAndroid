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
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (initialRootId != 0 || initialMessageId != 0) {
                    // Make sure we have the specific message and its immediate context
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
                        // No unread, fetch most recent
                        repository.refreshMessages(forumName, topicName, topicId)
                        val currentMessages = repository.getMessagesForTopic(topicId).first()
                        if (currentMessages.isNotEmpty()) {
                            val mostRecent = currentMessages.maxByOrNull { it.date }
                            if (mostRecent != null) {
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
        val children = allMessages.groupBy { it.commentId }
        val roots = allMessages.filter { it.isRoot }.sortedByDescending { it.date }
        
        // 1. Check current topic for unread messages
        if (currentMessageId != null) {
            val currentMsg = allMessages.find { it.remoteId == currentMessageId }
            if (currentMsg != null) {
                val currentRootId = if (currentMsg.rootId != 0) currentMsg.rootId else currentMsg.remoteId
                val currentThread = getThreadInOrder(allMessages, children, currentRootId)
                val currentIndex = currentThread.indexOfFirst { it.remoteId == currentMessageId }
                if (currentIndex != -1) {
                    for (i in (currentIndex + 1) until currentThread.size) {
                        if (currentThread[i].unread) return NextUnreadItem.Message(currentThread[i])
                    }
                }
                
                // Check following threads in same topic
                val currentRootIndex = roots.indexOfFirst { (if (it.rootId != 0) it.rootId else it.remoteId) == currentRootId }
                if (currentRootIndex != -1) {
                    for (i in (currentRootIndex + 1) until roots.size) {
                        val nextThread = getThreadInOrder(allMessages, children, if (roots[i].rootId != 0) roots[i].rootId else roots[i].remoteId)
                        val unread = nextThread.find { it.unread }
                        if (unread != null) return NextUnreadItem.Message(unread)
                    }
                }
            }
        } else {
            // No current message, find first unread in current topic
            for (root in roots) {
                val thread = getThreadInOrder(allMessages, children, root.remoteId)
                val unread = thread.find { it.unread }
                if (unread != null) return NextUnreadItem.Message(unread)
            }
        }

        // 2. No more unread in current topic. Find next topic in current forum.
        val allFolders = folderDao.getAll().first()
        val currentForum = allFolders.find { it.isRootFolder && it.name.equals(forumName, ignoreCase = true) }
        if (currentForum != null) {
            val forumTopics = allFolders.filter { it.parentId == currentForum.id }.sortedBy { it.index }
            val currentTopicIndex = forumTopics.indexOfFirst { it.id == topicId }
            if (currentTopicIndex != -1) {
                // Search forward from current topic
                for (i in (currentTopicIndex + 1) until forumTopics.size) {
                    if (forumTopics[i].unread > 0) return NextUnreadItem.Topic(forumName, forumTopics[i].name, forumTopics[i].id)
                }
                // Wrap around within forum
                for (i in 0 until currentTopicIndex) {
                    if (forumTopics[i].unread > 0) return NextUnreadItem.Topic(forumName, forumTopics[i].name, forumTopics[i].id)
                }
            }
        }

        // 3. No more unread in current forum. Find next forum with unread.
        val allForums = allFolders.filter { it.isRootFolder }.sortedBy { it.index }
        val currentForumIndex = allForums.indexOfFirst { it.name.equals(forumName, ignoreCase = true) }
        if (currentForumIndex != -1) {
            // Search forward from current forum
            for (i in (currentForumIndex + 1) until allForums.size) {
                if (allForums[i].unread > 0) {
                    val firstUnreadTopic = allFolders.find { it.parentId == allForums[i].id && it.unread > 0 }
                    if (firstUnreadTopic != null) {
                        return NextUnreadItem.Topic(allForums[i].name, firstUnreadTopic.name, firstUnreadTopic.id)
                    }
                }
            }
            // Wrap around
            for (i in 0 until currentForumIndex) {
                if (allForums[i].unread > 0) {
                    val firstUnreadTopic = allFolders.find { it.parentId == allForums[i].id && it.unread > 0 }
                    if (firstUnreadTopic != null) {
                        return NextUnreadItem.Topic(allForums[i].name, firstUnreadTopic.name, firstUnreadTopic.id)
                    }
                }
            }
        }

        return NextUnreadItem.NoMoreUnread
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
                    listOf(PostAttachment(data = Base64.encodeToString(bytes, Base64.NO_WRAP), filename = attachmentName))
                } else null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else null

        val currentAuthor = NetworkClient.getUsername()
        val resultId = repository.postMessage(forumName, topicName, body, replyToId, currentAuthor, attachments)
        
        val success = resultId != 0
        if (success) {
            draftDao.deleteDraftForContext(forumName, topicName, replyToId)
            if (resultId > 0) {
                _scrollToMessageId.value = resultId
            }
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
