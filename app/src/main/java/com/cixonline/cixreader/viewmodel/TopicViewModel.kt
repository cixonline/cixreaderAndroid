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
import com.cixonline.cixreader.repository.LogRepository
import com.cixonline.cixreader.repository.MessageRepository
import com.cixonline.cixreader.repository.NotAMemberException
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.utils.HtmlUtils
import com.cixonline.cixreader.utils.SyncManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.UnknownHostException

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
    private val logRepository: LogRepository,
    private val syncManager: SyncManager?,
    val rawForumName: String,
    val rawTopicName: String,
    val providedTopicId: Int,
    val initialMessageId: Int = 0,
    val initialRootId: Int = 0
) : ViewModel(), ProfileHost {

    val forumName = HtmlUtils.normalizeName(rawForumName)
    val topicName = HtmlUtils.normalizeName(rawTopicName)

    val topicId: Int = if (providedTopicId != 0) {
        providedTopicId
    } else {
        HtmlUtils.calculateTopicId(forumName, topicName)
    }

    private val profileDelegate = ProfileDelegate(api, cachedProfileDao)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isBackfilling = MutableStateFlow(false)
    val isBackfilling: StateFlow<Boolean> = _isBackfilling

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _showJoinDialog = MutableStateFlow<String?>(null)
    val showJoinDialog: StateFlow<String?> = _showJoinDialog

    private val _scrollToMessageId = MutableStateFlow<Int?>(if (initialMessageId != 0) initialMessageId else null)
    val scrollToMessageId: StateFlow<Int?> = _scrollToMessageId

    override val selectedProfile: StateFlow<UserProfile?> = profileDelegate.selectedProfile
    override val selectedResume: StateFlow<String?> = profileDelegate.selectedResume
    override val selectedMugshotUrl: StateFlow<String?> = profileDelegate.selectedMugshotUrl
    override val isProfileLoading: StateFlow<Boolean> = profileDelegate.isLoading

    val messages: StateFlow<List<CIXMessage>> = repository.getMessagesForTopic(topicId)
        .combine(_searchQuery) { allMessages, query ->
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

    val allForums: Flow<List<Folder>> = folderDao.getAll().map { folders ->
        folders.filter { it.isRootFolder }
    }

    private val _selectedForumForPost = MutableStateFlow<Folder?>(null)
    val selectedForumForPost: StateFlow<Folder?> = _selectedForumForPost

    private val _selectedTopicForPost = MutableStateFlow<Folder?>(null)
    val selectedTopicForPost: StateFlow<Folder?> = _selectedTopicForPost

    val topicsForSelectedForum: StateFlow<List<Folder>> = _selectedForumForPost.flatMapLatest { forum ->
        if (forum == null) flowOf(emptyList())
        else folderDao.getChildren(forum.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        Log.d("TopicViewModel", "Initializing topic: $forumName/$topicName (ID: $topicId)")
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                
                // Log raw threads.xml for debugging
                viewModelScope.launch {
                    try {
                        val encodedForum = HtmlUtils.cixEncode(forumName)
                        val encodedTopic = HtmlUtils.cixEncode(topicName)
                        val response = api.getTopicThreadsRaw(encodedForum, encodedTopic)
                        Log.d("THREADS_XML", response.string())
                    } catch (e: Exception) {
                        Log.e("THREADS_XML", "Failed to get raw threads XML", e)
                    }
                }

                // 1. App displays any messages from the message cache (via 'messages' Flow)
                
                if (initialRootId != 0 || initialMessageId != 0) {
                    repository.fetchMessageAndChildren(
                        forumName,
                        topicName,
                        if (initialRootId != 0) initialRootId else initialMessageId,
                        topicId
                    )
                } else {
                    // 2. Backfill root messages using the threads.xml command
                    repository.backfillToMessageOne(forumName, topicName, topicId)

                    // 3. Refresh for latest messages
                    repository.refreshMessages(forumName, topicName, topicId)

                    // 4. Determine landing message (oldest unread or latest)
                    var targetMessage = repository.getOldestUnreadInTopic(topicId)
                    
                    if (targetMessage == null) {
                        Log.d("TopicViewModel", "No unread found locally. Checking firstunread API.")
                        val serverFirstUnreadId = repository.getFirstUnreadMessageId(forumName, topicName)
                        if (serverFirstUnreadId > 0) {
                            repository.fetchMessageAndChildren(forumName, topicName, serverFirstUnreadId, topicId)
                            targetMessage = repository.getMessagesForTopic(topicId).first().find { it.remoteId == serverFirstUnreadId }
                        }
                    }

                    if (targetMessage == null) {
                        targetMessage = repository.getLatestMessageInTopic(topicId)
                    }

                    if (targetMessage != null) {
                        Log.d("TopicViewModel", "Landing on message #${targetMessage.remoteId}. Ensuring thread is loaded.")
                        repository.fetchThreadThenBackfill(forumName, topicName, targetMessage.remoteId, topicId)
                        if (_scrollToMessageId.value == null) {
                            _scrollToMessageId.value = targetMessage.remoteId
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("TopicViewModel", "Init failed for $forumName/$topicName", e)
                if (isOfflineError(e) && repository.getMessageCount(topicId) == 0) {
                    _error.value = "No Messages found in the cache. You are offline, go online for content"
                } else {
                    _error.value = "Failed to load messages: ${e.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun isOfflineError(e: Throwable): Boolean {
        return e is UnknownHostException || e.cause is UnknownHostException
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                repository.refreshMessages(forumName, topicName, topicId, force = true)
            } catch (e: NotAMemberException) {
                _showJoinDialog.value = e.forumName
            } catch (e: Exception) {
                Log.e("TopicViewModel", "Refresh failed", e)
                if (isOfflineError(e) && repository.getMessageCount(topicId) == 0) {
                    _error.value = "No Messages found in the cache. You are offline, go online for content"
                } else {
                    _error.value = "Refresh failed: ${e.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshMessage(message: CIXMessage) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.fetchMessageAndChildren(forumName, topicName, message.remoteId, topicId)
            } catch (e: Exception) {
                Log.e("TopicViewModel", "Message refresh failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onThreadExpand(rootMsg: CIXMessage) {
        val currentThreadMessages = messages.value.filter { it.rootId == rootMsg.remoteId }
        val expectedCount = rootMsg.threadReplies + 1
        
        if (rootMsg.threadReplies != -1 && currentThreadMessages.size < expectedCount) {
            Log.d("TopicViewModel", "Expanding thread #${rootMsg.remoteId}. Cache has ${currentThreadMessages.size}, server says $expectedCount. Fetching thread.xml")
            viewModelScope.launch {
                _isLoading.value = true
                try {
                    repository.fetchThreadThenBackfill(forumName, topicName, rootMsg.remoteId, topicId)
                } catch (e: Exception) {
                    Log.e("TopicViewModel", "Thread expansion fetch failed", e)
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    suspend fun findNextUnreadItem(currentMessageId: Int?): NextUnreadItem {
        val allMessages = messages.value
        if (allMessages.isEmpty()) return NextUnreadItem.NoMoreUnread

        // Use a consistent tree/sequence construction
        val tree = allMessages.groupBy { it.commentId }
        val ids = allMessages.map { it.remoteId }.toSet()
        val currentRoots = allMessages.filter { it.commentId == 0 || !ids.contains(it.commentId) }
            .sortedByDescending { it.date }

        val visualSequence = mutableListOf<CIXMessage>()
        fun walk(m: CIXMessage) {
            visualSequence.add(m)
            tree[m.remoteId]?.sortedBy { it.date }?.forEach { walk(it) }
        }
        currentRoots.forEach { walk(it) }

        val nextItem: NextUnreadItem
        if (currentMessageId == null) {
            // Finding initial unread: return first unread in visual sequence
            val msg = visualSequence.find { it.isActuallyUnread }
            nextItem = if (msg != null) NextUnreadItem.Message(msg) else findNextUnreadOutsideTopic()
        } else {
            // Navigating from a specific message
            val currentIndex = visualSequence.indexOfFirst { it.remoteId == currentMessageId }
            if (currentIndex != -1) {
                // Check messages FOLLOWING the current one in the thread view
                var found: CIXMessage? = null
                for (i in (currentIndex + 1) until visualSequence.size) {
                    if (visualSequence[i].isActuallyUnread) {
                        found = visualSequence[i]
                        break
                    }
                }
                nextItem = if (found != null) NextUnreadItem.Message(found) else {
                    // Check messages BEFORE the current one (wrap around within topic)
                    val before = visualSequence.take(currentIndex).find { it.isActuallyUnread }
                    if (before != null) NextUnreadItem.Message(before) else findNextUnreadOutsideTopic()
                }
            } else {
                // If current message isn't in sequence for some reason, find first unread
                val first = visualSequence.find { it.isActuallyUnread }
                nextItem = if (first != null) NextUnreadItem.Message(first) else findNextUnreadOutsideTopic()
            }
        }

        val logMessage = when (nextItem) {
            is NextUnreadItem.Message -> "Next Unread tapped (Current #$currentMessageId). Found message #${nextItem.message.remoteId} in current topic."
            is NextUnreadItem.Topic -> "Next Unread tapped (Current #$currentMessageId). Moving to topic ${nextItem.forum}/${nextItem.topic}."
            is NextUnreadItem.NoMoreUnread -> "Next Unread tapped (Current #$currentMessageId). No more unreads found."
            else -> "Next Unread tapped (Current #$currentMessageId). Navigation result: $nextItem"
        }
        logRepository.log(logMessage, "NAVIGATION")

        return nextItem
    }

    private suspend fun findNextUnreadOutsideTopic(): NextUnreadItem {
        // Refresh all topic unread counts from server to ensure we have the latest status
        repository.refreshAllTopicUnreads()

        val allFolders = folderDao.getAll().first()
        val rootFolders = allFolders.filter { it.isRootFolder }.sortedBy { it.name.lowercase() }
        val currentForum = rootFolders.find { it.name.equals(forumName, ignoreCase = true) }
        
        // 1. Check remaining topics in CURRENT forum
        if (currentForum != null) {
            val forumTopics = allFolders.filter { it.parentId == currentForum.id }.sortedBy { it.name.lowercase() }
            val currentTopicIndex = forumTopics.indexOfFirst { it.id == topicId }
            if (currentTopicIndex != -1) {
                for (i in (currentTopicIndex + 1) until forumTopics.size) {
                    if (forumTopics[i].unread > 0) return NextUnreadItem.Topic(forumName, forumTopics[i].name, forumTopics[i].id)
                }
            }
        }

        // 2. Check subsequent forums in alphabetical order
        val currentForumIndex = rootFolders.indexOfFirst { it.name.equals(forumName, ignoreCase = true) }
        if (currentForumIndex != -1) {
            // Check forwards from current forum
            for (i in (currentForumIndex + 1) until rootFolders.size) {
                if (rootFolders[i].unread > 0) {
                    val topics = allFolders.filter { it.parentId == rootFolders[i].id }.sortedBy { it.name.lowercase() }
                    topics.find { it.unread > 0 }?.let {
                        return NextUnreadItem.Topic(rootFolders[i].name, it.name, it.id)
                    }
                }
            }
            // Wrap around: check from beginning of list
            for (i in 0 until currentForumIndex) {
                if (rootFolders[i].unread > 0) {
                    val topics = allFolders.filter { it.parentId == rootFolders[i].id }.sortedBy { it.name.lowercase() }
                    topics.find { it.unread > 0 }?.let {
                        return NextUnreadItem.Topic(rootFolders[i].name, it.name, it.id)
                    }
                }
            }
        }

        // 3. Final check: earlier topics in CURRENT forum (wrap around within forum)
        if (currentForum != null) {
            val forumTopics = allFolders.filter { it.parentId == currentForum.id }.sortedBy { it.name.lowercase() }
            val currentTopicIndex = forumTopics.indexOfFirst { it.id == topicId }
            if (currentTopicIndex != -1) {
                for (i in 0 until currentTopicIndex) {
                    if (forumTopics[i].unread > 0) return NextUnreadItem.Topic(forumName, forumTopics[i].name, forumTopics[i].id)
                }
            }
        }

        return NextUnreadItem.NoMoreUnread
    }

    fun preparePostDialog() {
        viewModelScope.launch {
            val allFolders = folderDao.getAll().first()
            val currentForum = allFolders.find { it.isRootFolder && it.name.equals(forumName, ignoreCase = true) }
            _selectedForumForPost.value = currentForum
            if (currentForum != null) {
                val topics = folderDao.getChildren(currentForum.id).first()
                val currentTopic = topics.find { it.id == topicId } 
                    ?: topics.find { it.name.equals(topicName, ignoreCase = true) }
                
                if (currentTopic != null) {
                    _selectedTopicForPost.value = currentTopic
                } else {
                    // Fallback to creating a Folder object if it's missing from DB children list
                    _selectedTopicForPost.value = Folder(id = topicId, name = topicName, parentId = currentForum.id)
                }
            }
        }
    }

    fun selectForumForPost(forum: Folder?) {
        _selectedForumForPost.value = forum
        _selectedTopicForPost.value = null
    }

    fun selectTopicForPost(topic: Folder?) {
        _selectedTopicForPost.value = topic
    }

    suspend fun postMessage(
        context: Context,
        forum: String,
        topic: String,
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
        val postTopicId = HtmlUtils.calculateTopicId(forum, topic)
        val resultId = repository.postMessage(forum, topic, postTopicId, body, 0, currentAuthor, attachments)
        
        if (resultId != 0) {
            draftDao.deleteDraftForContext(forum, topic, 0)
            if (resultId > 0 && forum.equals(forumName, ignoreCase = true) && topic.equals(topicName, ignoreCase = true)) {
                _scrollToMessageId.value = resultId
            }
        }
        _isLoading.value = false
        return resultId != 0
    }

    suspend fun postReply(
        context: Context,
        replyToId: Int, 
        body: String, 
        attachmentUri: Uri?, 
        attachmentName: String?
    ): Boolean {
        _isLoading.value = true
        return try {
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
            val resultId = repository.postMessage(forumName, topicName, topicId, body, replyToId, currentAuthor, attachments)
            
            if (resultId != 0) {
                draftDao.deleteDraftForContext(forumName, topicName, replyToId)
                if (resultId > 0) {
                    _scrollToMessageId.value = resultId
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("TopicViewModel", "Post reply failed", e)
            false
        } finally {
            _isLoading.value = false
        }
    }

    fun saveDraft(replyToId: Int, body: String, attachmentUri: Uri? = null, attachmentName: String? = null) {
        viewModelScope.launch {
            val existing = draftDao.getDraft(forumName, topicName, replyToId)
            val draft = Draft(
                id = existing?.id ?: 0,
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

    fun saveDraftForContext(forum: String, topic: String, body: String) {
        viewModelScope.launch {
            val existing = draftDao.getDraft(forum, topic, 0)
            val draft = Draft(
                id = existing?.id ?: 0,
                forumName = forum,
                topicName = topic,
                replyToId = 0,
                body = body
            )
            draftDao.insertDraft(draft)
        }
    }

    suspend fun getDraft(replyToId: Int): Draft? = draftDao.getDraft(forumName, topicName, replyToId)

    suspend fun getDraftForContext(forum: String, topic: String): Draft? = draftDao.getDraft(forum, topic, 0)

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

    fun markAsRead(message: CIXMessage) = viewModelScope.launch { 
        repository.markAsRead(message)
        syncManager?.triggerPendingSync()
    }

    fun markTopicAsRead() = viewModelScope.launch {
        repository.markTopicAsRead(forumName, topicName, topicId)
        syncManager?.triggerPendingSync()
    }

    fun withdrawMessage(message: CIXMessage) = viewModelScope.launch {
        _isLoading.value = true
        if (repository.withdrawMessage(message)) refresh()
        _isLoading.value = false
    }

    fun logRawXml() = viewModelScope.launch {
        try {
            val encodedForum = HtmlUtils.cixEncode(forumName)
            val encodedTopic = HtmlUtils.cixEncode(topicName)
            val response = api.getMessagesRaw(encodedForum, encodedTopic)
            Log.d("RAW_XML", response.string())
        } catch (e: Exception) {
            Log.e("RAW_XML", "Failed to get raw XML", e)
        }
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
    private val logRepository: LogRepository,
    private val syncManager: SyncManager?,
    private val forumName: String,
    private val topicName: String,
    private val topicId: Int,
    private val initialMessageId: Int = 0,
    private val initialRootId: Int = 0
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TopicViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TopicViewModel(api, repository, cachedProfileDao, draftDao, folderDao, logRepository, syncManager, forumName, topicName, topicId, initialMessageId, initialRootId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
