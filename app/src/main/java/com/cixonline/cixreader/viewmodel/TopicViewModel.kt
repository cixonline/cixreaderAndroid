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
import com.cixonline.cixreader.repository.HistoryRepository
import com.cixonline.cixreader.repository.LogRepository
import com.cixonline.cixreader.repository.MessageRepository
import com.cixonline.cixreader.repository.NotAMemberException
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.utils.HtmlUtils
import com.cixonline.cixreader.utils.SyncManager
import kotlinx.coroutines.delay
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
    _forumName: String,
    _topicName: String,
    val topicId: Int,
    val initialMessageId: Int = 0,
    val initialRootId: Int = 0,
    private val historyRepository: HistoryRepository
) : ViewModel(), ProfileHost {

    val forumName = HtmlUtils.normalizeName(_forumName)
    val topicName = HtmlUtils.normalizeTopicName(_topicName)

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

    private val _historyEvent = MutableSharedFlow<String>(replay = 0)
    val historyEvent: SharedFlow<String> = _historyEvent

    private val _navigateToMessage = MutableSharedFlow<Triple<String, String, Int>>(replay = 0)
    val navigateToMessage: SharedFlow<Triple<String, String, Int>> = _navigateToMessage

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

                // Ensure membership check first
                var allFolders = folderDao.getAllSync()

                // If folders list is empty, refresh it from server before checking membership
                if (allFolders.isEmpty()) {
                    Log.d("TopicViewModel", "Folder list empty. Refreshing from server.")
                    repository.refreshFoldersFromServer()
                    allFolders = folderDao.getAllSync()
                }

                var isMember = allFolders.any { it.isRootFolder && it.name.equals(forumName, ignoreCase = true) }

                if (!isMember) {
                    Log.d("TopicViewModel", "Not a member of $forumName. Attempting automatic join.")
                    if (repository.joinForum(forumName)) {
                        Log.d("TopicViewModel", "Automatically joined $forumName.")
                        isMember = true
                    } else {
                        Log.d("TopicViewModel", "Automatic join failed, showing dialog")
                        _showJoinDialog.value = forumName
                        _isLoading.value = false
                        return@launch
                    }
                }

                // If the topic has no messages locally, we MUST fetch something in the foreground
                if (repository.getMessageCount(topicId) == 0) {
                    Log.d("TopicViewModel", "Topic is empty locally. Performing initial foreground fetch.")
                    try {
                        repository.backfillToMessageOne(forumName, topicName, topicId)
                    } catch (e: Exception) {
                        Log.e("TopicViewModel", "Initial foreground backfill failed for $forumName/$topicName", e)
                        if (e is NotAMemberException) throw e
                    }
                }

                var targetMessage: CIXMessage? = null

                // 1. Determine landing message
                if (initialMessageId != 0 || initialRootId != 0) {
                    val msgToFetch = if (initialMessageId != 0) initialMessageId else initialRootId
                    targetMessage = repository.getMessagesForTopic(topicId).first().find { it.remoteId == msgToFetch }
                    if (targetMessage == null) {
                        try {
                            Log.d("TopicViewModel", "Fetching specific landing message $msgToFetch")
                            repository.fetchMessageAndChildren(forumName, topicName, msgToFetch, topicId)
                            targetMessage = repository.getMessagesForTopic(topicId).first().find { it.remoteId == msgToFetch }
                        } catch (e: Exception) {
                            Log.e("TopicViewModel", "Specific message fetch failed for $msgToFetch in $forumName/$topicName", e)
                            if (e is NotAMemberException) throw e
                        }
                    }
                } else {
                    targetMessage = repository.getOldestUnreadInTopic(topicId)
                    if (targetMessage == null) {
                        Log.d("TopicViewModel", "No unread found locally. Checking firstunread API.")
                        try {
                            val serverFirstUnreadId = repository.getFirstUnreadMessageId(forumName, topicName)
                            if (serverFirstUnreadId > 0) {
                                repository.fetchMessageAndChildren(forumName, topicName, serverFirstUnreadId, topicId)
                                targetMessage = repository.getMessagesForTopic(topicId).first().find { it.remoteId == serverFirstUnreadId }
                            }
                        } catch (e: Exception) {
                            Log.e("TopicViewModel", "First unread fetch failed", e)
                            if (e is NotAMemberException) throw e
                        }
                    }
                    if (targetMessage == null) {
                        targetMessage = repository.getLatestMessageInTopic(topicId)
                    }
                }

                // 2. Ensure target message thread is loaded and scroll to it
                if (targetMessage != null) {
                    val rootId = if (targetMessage.rootId != 0) targetMessage.rootId else targetMessage.remoteId
                    val allMsgs = repository.getMessagesForTopic(topicId).first()
                    val rootMsg = allMsgs.find { it.remoteId == rootId }
                    
                    val currentCount = countThreadMessages(allMsgs, rootId)
                    val expectedCount = if (rootMsg != null && rootMsg.threadReplies != -1) rootMsg.threadReplies + 1 else Int.MAX_VALUE

                    if (currentCount < expectedCount) {
                        Log.d("TopicViewModel", "Ensuring thread for #${targetMessage.remoteId} is loaded.")
                        try {
                            repository.fetchThreadThenBackfill(forumName, topicName, rootId, topicId)
                        } catch (e: Exception) {
                            Log.e("TopicViewModel", "Thread backfill failed for root $rootId", e)
                        }
                    }
                    if (_scrollToMessageId.value == null) {
                        _scrollToMessageId.value = targetMessage.remoteId
                    }
                }

                // 3. Landing successful, hide spinner while background loading happens
                _isLoading.value = false

                // 4. Background: populate the rest of the topic
                launch {
                    _isBackfilling.value = true
                    try {
                        repository.backfillToMessageOne(forumName, topicName, topicId)
                        repository.refreshMessages(forumName, topicName, topicId)
                    } catch (e: Exception) {
                        Log.e("TopicViewModel", "Background tasks failed", e)
                    } finally {
                        _isBackfilling.value = false
                    }
                }

            } catch (e: NotAMemberException) {
                Log.w("TopicViewModel", "Caught NotAMemberException for $forumName")
                _showJoinDialog.value = forumName
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

    private fun countThreadMessages(allMessages: List<CIXMessage>, startId: Int): Int {
        val children = allMessages.groupBy { it.commentId }
        var count = 0
        fun walk(mId: Int) {
            count++
            children[mId]?.forEach { walk(it.remoteId) }
        }
        if (allMessages.any { it.remoteId == startId }) {
            walk(startId)
        }
        return count
    }

    fun onThreadExpand(rootMsg: CIXMessage) {
        viewModelScope.launch {
            val currentCount = countThreadMessages(repository.getMessagesForTopic(topicId).first(), rootMsg.remoteId)
            val expectedCount = rootMsg.threadReplies + 1
            
            if (rootMsg.threadReplies != -1 && currentCount < expectedCount) {
                Log.d("TopicViewModel", "Expanding thread #${rootMsg.remoteId}. Cache has $currentCount, server says $expectedCount. Fetching thread.xml")
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
        // 1. Try to find an unread candidate locally within the current topic (unfiltered by search)
        val allMessages = repository.getMessagesForTopic(topicId).first()
        
        fun isCandidate(m: CIXMessage): Boolean {
            if (m.isActuallyUnread) return true
            if (m.commentId == 0 && m.threadUnread > 0) return true
            return false
        }

        var found: CIXMessage? = null
        if (allMessages.isNotEmpty()) {
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

            if (currentMessageId == null) {
                found = visualSequence.find { isCandidate(it) }
            } else {
                val currentIndex = visualSequence.indexOfFirst { it.remoteId == currentMessageId }
                if (currentIndex != -1) {
                    for (i in (currentIndex + 1) until visualSequence.size) {
                        if (isCandidate(visualSequence[i])) {
                            found = visualSequence[i]
                            break
                        }
                    }
                    // Removed wrap-around within current topic to allow moving to next topic
                } else {
                    found = visualSequence.find { isCandidate(it) }
                }
            }
        }

        if (found != null && found.remoteId != currentMessageId) {
            if (!found.isActuallyUnread && found.commentId == 0 && found.threadUnread > 0) {
                Log.d("TopicViewModel", "Found root #${found.remoteId} with unread children. Fetching thread.")
                repository.fetchThreadThenBackfill(forumName, topicName, found.remoteId, topicId)
                val refreshed = repository.getMessagesForTopic(topicId).first()
                val threadMsgs = mutableListOf<CIXMessage>()
                val refreshedTree = refreshed.groupBy { it.commentId }
                fun walk(m: CIXMessage) {
                    threadMsgs.add(m)
                    refreshedTree[m.remoteId]?.sortedBy { it.date }?.forEach { walk(it) }
                }
                refreshed.find { it.remoteId == found!!.remoteId }?.let { walk(it) }
                
                val firstUnreadChild = threadMsgs.find { it.isActuallyUnread }
                if (firstUnreadChild != null) {
                    logRepository.log("Next Unread (Current #$currentMessageId): Found child #${firstUnreadChild.remoteId} after fetching thread.", "NAVIGATION")
                    return NextUnreadItem.Message(firstUnreadChild)
                }
            } else {
                logRepository.log("Next Unread (Current #$currentMessageId): Found #${found.remoteId} locally.", "NAVIGATION")
                return NextUnreadItem.Message(found)
            }
        }

        // 2. Not found locally. Check the server for any unread in the current topic.
        try {
            val serverFirstUnreadId = repository.getFirstUnreadMessageId(forumName, topicName)
            if (serverFirstUnreadId > 0 && serverFirstUnreadId != currentMessageId) {
                Log.d("TopicViewModel", "Server says first unread is #$serverFirstUnreadId. Fetching.")
                repository.fetchMessageAndChildren(forumName, topicName, serverFirstUnreadId, topicId)
                val refreshed = repository.getMessagesForTopic(topicId).first()
                val serverFound = refreshed.find { it.remoteId == serverFirstUnreadId }
                if (serverFound != null && serverFound.isActuallyUnread) {
                    logRepository.log("Next Unread (Current #$currentMessageId): Found #${serverFound.remoteId} via server API.", "NAVIGATION")
                    return NextUnreadItem.Message(serverFound)
                }
            }
        } catch (e: Exception) {
            Log.e("TopicViewModel", "Failed to check server for unreads", e)
        }

        // 3. Still nothing in the current topic. Search other topics/forums.
        val nextOutside = findNextUnreadOutsideTopic()
        val logMsg = when (nextOutside) {
            is NextUnreadItem.Topic -> "Next Unread (Current #$currentMessageId): Moving to topic ${nextOutside.forum}/${nextOutside.topic}."
            is NextUnreadItem.NoMoreUnread -> "Next Unread (Current #$currentMessageId): No more unreads found anywhere."
            else -> "Next Unread (Current #$currentMessageId): Navigation result: $nextOutside"
        }
        logRepository.log(logMsg, "NAVIGATION")
        return nextOutside
    }

    private suspend fun findNextUnreadOutsideTopic(): NextUnreadItem {
        // Optimization: Use locally cached unread status unless user explicitly refreshes
        // repository.refreshAllTopicUnreads()

        val allFolders = folderDao.getAllSync()
        val rootFolders = allFolders.filter { it.isRootFolder }.sortedBy { it.name.lowercase() }
        val currentForum = rootFolders.find { it.name.equals(forumName, ignoreCase = true) }
        
        // 1. Check topics in CURRENT forum
        if (currentForum != null) {
            val forumTopics = allFolders.filter { it.parentId == currentForum.id }.sortedBy { it.name.lowercase() }
            val currentTopicIndex = forumTopics.indexOfFirst { it.id == topicId }
            
            // 1a. Topics AFTER current in same forum
            if (currentTopicIndex != -1) {
                for (i in (currentTopicIndex + 1) until forumTopics.size) {
                    if (forumTopics[i].unread > 0) {
                        Log.d("TopicViewModel", "Next Unread: Found subsequent topic ${forumTopics[i].name} in current forum")
                        return NextUnreadItem.Topic(forumName, forumTopics[i].name, forumTopics[i].id)
                    }
                }
                // 1b. Topics BEFORE current in same forum (to finish the forum before moving out)
                for (i in 0 until currentTopicIndex) {
                    if (forumTopics[i].unread > 0) {
                        Log.d("TopicViewModel", "Next Unread: Found previous topic ${forumTopics[i].name} in current forum")
                        return NextUnreadItem.Topic(forumName, forumTopics[i].name, forumTopics[i].id)
                    }
                }
            }
        }

        // 2. Check subsequent forums in alphabetical order
        val currentForumIndex = rootFolders.indexOfFirst { it.name.equals(forumName, ignoreCase = true) }
        if (currentForumIndex != -1) {
            // Check forwards from current forum
            for (i in (currentForumIndex + 1) until rootFolders.size) {
                val forum = rootFolders[i]
                val topics = allFolders.filter { it.parentId == forum.id }.sortedBy { it.name.lowercase() }
                val unreadTopic = topics.find { it.unread > 0 }
                if (unreadTopic != null) {
                    Log.d("TopicViewModel", "Next Unread: Found topic ${unreadTopic.name} in subsequent forum ${forum.name}")
                    return NextUnreadItem.Topic(forum.name, unreadTopic.name, unreadTopic.id)
                }
            }
            // Wrap around: check from beginning of list up to current forum
            for (i in 0 until currentForumIndex) {
                val forum = rootFolders[i]
                val topics = allFolders.filter { it.parentId == forum.id }.sortedBy { it.name.lowercase() }
                val unreadTopic = topics.find { it.unread > 0 }
                if (unreadTopic != null) {
                    Log.d("TopicViewModel", "Next Unread: Found topic ${unreadTopic.name} in wrapped forum ${forum.name}")
                    return NextUnreadItem.Topic(forum.name, unreadTopic.name, unreadTopic.id)
                }
            }
        } else {
            // Forum not found in DB (unlikely), just check everything
            for (forum in rootFolders) {
                val topics = allFolders.filter { it.parentId == forum.id }.sortedBy { it.name.lowercase() }
                val unreadTopic = topics.find { it.unread > 0 }
                if (unreadTopic != null) {
                    Log.d("TopicViewModel", "Next Unread: Found topic ${unreadTopic.name} in forum ${forum.name} (fallback search)")
                    return NextUnreadItem.Topic(forum.name, unreadTopic.name, unreadTopic.id)
                }
            }
        }

        Log.d("TopicViewModel", "Next Unread: No unread topics found outside current topic")
        return NextUnreadItem.NoMoreUnread
    }

    fun preparePostDialog() {
        viewModelScope.launch {
            val allFolders = folderDao.getAllSync()
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
            val response = api.getTopicThreadsRaw(encodedForum, encodedTopic)
            Log.d("RAW_XML", response.string())
        } catch (e: Exception) {
            Log.e("RAW_XML", "Failed to get raw XML", e)
        }
    }

    fun addToHistory(messageId: Int) {
        viewModelScope.launch {
            historyRepository.addToHistory(forumName, topicName, topicId, messageId)
        }
    }

    fun navigateHistoryBack() {
        viewModelScope.launch {
            historyRepository.getPrevious()?.let { entry ->
                _navigateToMessage.emit(Triple(entry.forumName, entry.topicName, entry.messageId))
                _historyEvent.emit("Back in history")
            }
        }
    }

    fun navigateHistoryForward() {
        viewModelScope.launch {
            historyRepository.getNext()?.let { entry ->
                _navigateToMessage.emit(Triple(entry.forumName, entry.topicName, entry.messageId))
                _historyEvent.emit("Forward in history")
            }
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
    private val initialRootId: Int = 0,
    private val historyRepository: HistoryRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TopicViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TopicViewModel(api, repository, cachedProfileDao, draftDao, folderDao, logRepository, syncManager, forumName, topicName, topicId, initialMessageId, initialRootId, historyRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
