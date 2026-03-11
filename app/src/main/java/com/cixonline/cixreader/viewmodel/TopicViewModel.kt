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
import com.cixonline.cixreader.repository.MessageRepository
import com.cixonline.cixreader.repository.NotAMemberException
import com.cixonline.cixreader.utils.DateUtils
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

    init {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                
                val currentMessages = repository.getMessagesForTopic(topicId).first()
                
                if (initialRootId != 0 || initialMessageId != 0) {
                    repository.fetchMessageAndChildren(
                        forumName,
                        topicName,
                        if (initialRootId != 0) initialRootId else initialMessageId,
                        topicId
                    )
                } else if (currentMessages.isEmpty()) {
                    // 1: Get the last 30 days of messages for this topic using an API call and cache them.
                    val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                    val since = DateUtils.formatApiDate(thirtyDaysAgo)
                    repository.refreshMessages(forumName, topicName, topicId, sinceOverride = since)

                    // 2: Ascertain the oldest unread message from the cached messages.
                    val updatedMessages = repository.getMessagesForTopic(topicId).first()
                    val oldestUnread = updatedMessages.filter { it.isActuallyUnread }.minByOrNull { it.date }

                    if (oldestUnread != null) {
                        // 3: Get the entire thread that contains this unread message using an API call and cache all these messages.
                        repository.fetchThreadThenBackfill(forumName, topicName, oldestUnread.remoteId, topicId)
                        
                        // 4: Display the thread landing on the oldest unread message.
                        _scrollToMessageId.value = oldestUnread.remoteId
                    }
                } else {
                    // Cache is NOT empty, but we might still want to refresh to get latest
                    repository.refreshMessages(forumName, topicName, topicId)
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
                repository.refreshMessages(forumName, topicName, topicId, force = true)
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
        if (allMessages.isEmpty()) return NextUnreadItem.NoMoreUnread

        if (currentMessageId == null) {
            // Initial entry: find OLDEST unread message (taking 30-day rule into account)
            val oldestUnread = allMessages.filter { it.isActuallyUnread }.minByOrNull { it.date }
            if (oldestUnread != null) return NextUnreadItem.Message(oldestUnread)
        } else {
            // Navigation: find NEXT unread message in visual thread sequence
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

            val currentIndex = visualSequence.indexOfFirst { it.remoteId == currentMessageId }
            if (currentIndex != -1) {
                for (i in (currentIndex + 1) until visualSequence.size) {
                    if (visualSequence[i].isActuallyUnread) return NextUnreadItem.Message(visualSequence[i])
                }
            }
        }

        return findNextUnreadOutsideTopic()
    }

    private suspend fun findNextUnreadOutsideTopic(): NextUnreadItem {
        val allFolders = folderDao.getAll().first()
        val currentForum = allFolders.find { it.isRootFolder && it.name.equals(forumName, ignoreCase = true) }
        
        if (currentForum != null) {
            val forumTopics = allFolders.filter { it.parentId == currentForum.id }.sortedBy { it.index }
            val currentTopicIndex = forumTopics.indexOfFirst { it.id == topicId }
            if (currentTopicIndex != -1) {
                for (i in (currentTopicIndex + 1) until forumTopics.size) {
                    if (forumTopics[i].unread > 0) return NextUnreadItem.Topic(forumName, forumTopics[i].name, forumTopics[i].id)
                }
            }
        }

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
            for (i in 0 until currentForumIndex) {
                if (allForums[i].unread > 0) {
                    allFolders.find { it.parentId == allForums[i].id && it.unread > 0 }?.let {
                        return NextUnreadItem.Topic(allForums[i].name, it.name, it.id)
                    }
                }
            }
        }

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
            // Fetch existing draft ID to ensure we update instead of inserting duplicates
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
