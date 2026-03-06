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
        val ids = list.map { it.remoteId }.toSet()
        // Include actual roots (commentId == 0) and pseudo-roots (parent not in list)
        // Sort by date descending to match UI order (newest thread first)
        list.filter { it.commentId == 0 || !ids.contains(it.commentId) }.sortedByDescending { it.date }
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
        val currentRoots = roots.value
        
        if (currentMessageId != null) {
            val currentMsg = allMessages.find { it.remoteId == currentMessageId }
            if (currentMsg != null) {
                // 1. Try to find the next unread message in the current thread tree (DFS)
                val nextInThread = findNextInThread(currentMsg, allMessages, tree)
                if (nextInThread != null) {
                    return NextUnreadItem.Message(nextInThread)
                }
                
                // 2. Thread exhausted. Move to next unread thread root in this topic
                val localRoot = findLocalRoot(currentMsg, allMessages)
                val currentRootIndex = currentRoots.indexOfFirst { it.remoteId == localRoot.remoteId }
                if (currentRootIndex != -1) {
                    for (i in (currentRootIndex + 1) until currentRoots.size) {
                        val nextRoot = currentRoots[i]
                        if (nextRoot.unread) return NextUnreadItem.Message(nextRoot)
                        
                        // Check for unread within that next thread root's tree
                        val firstUnreadInNextThread = findFirstUnreadInSubtree(nextRoot, tree)
                        if (firstUnreadInNextThread != null) return NextUnreadItem.Message(firstUnreadInNextThread)
                    }
                }
            }
        } else {
            // Find the very first unread message in the topic (visual order)
            for (root in currentRoots) {
                if (root.unread) return NextUnreadItem.Message(root)
                val unread = findFirstUnreadInSubtree(root, tree)
                if (unread != null) return NextUnreadItem.Message(unread)
            }
        }

        // 3. No more unread in current topic. Search other topics/forums.
        return findNextUnreadOutsideTopic()
    }

    private fun findNextInThread(current: CIXMessage, allMessages: List<CIXMessage>, tree: Map<Int, List<CIXMessage>>): CIXMessage? {
        // A. Check subtree of current message (DFS)
        findFirstUnreadInSubtree(current, tree)?.let { return it }
        
        // B. Backtrack up the tree to find the next oldest sibling
        var node = current
        val rootOfCurrentThread = findLocalRoot(current, allMessages)
        
        while (node.remoteId != rootOfCurrentThread.remoteId) {
            val parentId = node.commentId
            val siblings = tree[parentId]?.sortedBy { it.date } ?: emptyList()
            val myIndex = siblings.indexOfFirst { it.remoteId == node.remoteId }
            
            if (myIndex != -1) {
                for (i in (myIndex + 1) until siblings.size) {
                    val sibling = siblings[i]
                    if (sibling.unread) return sibling
                    
                    // If sibling is read, check its subtree
                    val unreadInSiblingBranch = findFirstUnreadInSubtree(sibling, tree)
                    if (unreadInSiblingBranch != null) return unreadInSiblingBranch
                }
            }
            // Move up to parent
            node = allMessages.find { it.remoteId == parentId } ?: break
        }
        return null
    }

    private fun findFirstUnreadInSubtree(root: CIXMessage, tree: Map<Int, List<CIXMessage>>): CIXMessage? {
        val children = tree[root.remoteId]?.sortedBy { it.date } ?: return null
        for (child in children) {
            if (child.unread) return child
            val unread = findFirstUnreadInSubtree(child, tree)
            if (unread != null) return unread
        }
        return null
    }

    private fun findLocalRoot(msg: CIXMessage, allMessages: List<CIXMessage>): CIXMessage {
        val ids = allMessages.map { it.remoteId }.toSet()
        val msgMap = allMessages.associateBy { it.remoteId }
        var current = msg
        // Trace back until we hit a real root (commentId=0) or a pseudo-root (parent not in view)
        while (current.commentId != 0 && ids.contains(current.commentId)) {
            current = msgMap[current.commentId]!!
        }
        return current
    }

    private suspend fun findNextUnreadOutsideTopic(): NextUnreadItem {
        val allFolders = folderDao.getAll().first()
        val currentForum = allFolders.find { it.isRootFolder && it.name.equals(forumName, ignoreCase = true) }
        
        if (currentForum != null) {
            val forumTopics = allFolders.filter { it.parentId == currentForum.id }.sortedBy { it.index }
            val currentTopicIndex = forumTopics.indexOfFirst { it.id == topicId }
            if (currentTopicIndex != -1) {
                // Check next topics in current forum
                for (i in (currentTopicIndex + 1) until forumTopics.size) {
                    if (forumTopics[i].unread > 0) return NextUnreadItem.Topic(forumName, forumTopics[i].name, forumTopics[i].id)
                }
            }
        }

        // Check next forums
        val allForums = allFolders.filter { it.isRootFolder }.sortedBy { it.index }
        val currentForumIndex = allForums.indexOfFirst { it.name.equals(forumName, ignoreCase = true) }
        if (currentForumIndex != -1) {
            for (i in (currentForumIndex + 1) until allForums.size) {
                if (allForums[i].unread > 0) {
                    allFolders.find { it.parentId == allForums[i].id && it.unread > 0 }?.let {
                        return NextUnreadItem.Topic(allForums[i].name, it.name, it.id)
                    }
                }
            }
            // Wrap around to start of forums
            for (i in 0 until currentForumIndex) {
                if (allForums[i].unread > 0) {
                    allFolders.find { it.parentId == allForums[i].id && it.unread > 0 }?.let {
                        return NextUnreadItem.Topic(allForums[i].name, it.name, it.id)
                    }
                }
            }
        }

        // Finally, check earlier topics in current forum (wrap around)
        if (currentForum != null) {
            val forumTopics = allFolders.filter { it.parentId == currentForum.id }.sortedBy { it.index }
            val currentTopicIndex = forumTopics.indexOfFirst { it.id == topicId }
            if (currentTopicIndex != -1) {
                for (i in 0 until currentTopicIndex) {
                    if (forumTopics[i].unread > 0) return NextUnreadItem.Topic(forumName, forumTopics[i].name, forumTopics[i].id)
                }
            }
        }

        return NextUnreadItem.NoMoreUnread
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

    suspend fun getDraft(replyToId: Int): Draft? = draftDao.getDraft(forumName, topicName, replyToId)

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

    fun dismissJoinDialog() = run { _showJoinDialog.value = null }

    fun onSearchQueryChange(newQuery: String) = run { _searchQuery.value = newQuery }

    fun toggleStar(message: CIXMessage) = viewModelScope.launch { repository.toggleStar(message) }

    fun markAsRead(message: CIXMessage) = viewModelScope.launch { repository.markAsRead(message) }

    fun withdrawMessage(message: CIXMessage) = viewModelScope.launch {
        _isLoading.value = true
        if (repository.withdrawMessage(message)) refresh()
        _isLoading.value = false
    }

    override fun showProfile(user: String) = profileDelegate.showProfile(viewModelScope, user)
    override fun dismissProfile() = profileDelegate.dismissProfile()
    fun onScrollToMessageComplete() = run { _scrollToMessageId.value = null }
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
