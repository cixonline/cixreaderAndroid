package com.cixonline.cixreader.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.UserProfile
import com.cixonline.cixreader.db.CachedProfileDao
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.repository.ForumRepository
import com.cixonline.cixreader.repository.HistoryRepository
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.UnknownHostException

class ForumViewModel(
    private val api: CixApi,
    private val repository: ForumRepository,
    private val cachedProfileDao: CachedProfileDao,
    private val historyRepository: HistoryRepository
) : ViewModel(), ProfileHost {

    private val profileDelegate = ProfileDelegate(api, cachedProfileDao)

    val allFolders: Flow<List<Folder>> = repository.allFolders
    
    private val _expandedForums = MutableStateFlow<Set<Int>>(emptySet())
    val expandedForums: StateFlow<Set<Int>> = _expandedForums.asStateFlow()

    private val _showOnlyUnread = MutableStateFlow(true)
    val showOnlyUnread: StateFlow<Boolean> = _showOnlyUnread.asStateFlow()

    private val _loadingForums = MutableStateFlow<Set<Int>>(emptySet())
    val loadingForums: StateFlow<Set<Int>> = _loadingForums.asStateFlow()

    private val _connectionErrorForums = MutableStateFlow<Set<Int>>(emptySet())
    val connectionErrorForums: StateFlow<Set<Int>> = _connectionErrorForums.asStateFlow()

    private val _navigateToMessage = MutableSharedFlow<Triple<String, String, Int>>()
    val navigateToMessage: SharedFlow<Triple<String, String, Int>> = _navigateToMessage

    private val _historyEvent = MutableSharedFlow<String>()
    val historyEvent: SharedFlow<String> = _historyEvent

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    override val selectedProfile: StateFlow<UserProfile?> = profileDelegate.selectedProfile
    override val selectedResume: StateFlow<String?> = profileDelegate.selectedResume
    override val selectedMugshotUrl: StateFlow<String?> = profileDelegate.selectedMugshotUrl
    override val isProfileLoading: StateFlow<Boolean> = profileDelegate.isLoading

    fun refresh() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                repository.refreshForums()
                repository.refreshAllTopicUnreads()
                
                val expanded = _expandedForums.value
                if (expanded.isNotEmpty()) {
                    val allCurrentFolders = repository.allFolders.first()
                    expanded.forEach { forumId ->
                        val forum = allCurrentFolders.find { it.id == forumId }
                        if (forum != null) {
                            _loadingForums.update { it + forumId }
                            try {
                                repository.refreshTopics(forum.name, forum.id)
                                _connectionErrorForums.update { it - forumId }
                            } catch (e: UnknownHostException) {
                                _connectionErrorForums.update { it + forumId }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                _loadingForums.update { it - forumId }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Failed to load forums: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun toggleForum(forum: Folder) {
        val forumId = forum.id
        val current = _expandedForums.value
        if (current.contains(forumId)) {
            _expandedForums.value = current - forumId
            _connectionErrorForums.update { it - forumId }
        } else {
            _loadingForums.update { it + forumId }
            _expandedForums.value = current + forumId
            
            viewModelScope.launch {
                try {
                    repository.refreshTopics(forum.name, forum.id)
                    _connectionErrorForums.update { it - forumId }
                } catch (e: UnknownHostException) {
                    _connectionErrorForums.update { it + forumId }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    _loadingForums.update { it - forumId }
                }
            }
        }
    }

    fun resignForum(forum: Folder) {
        viewModelScope.launch {
            isLoading = true
            try {
                val success = repository.resignForum(forum.name, forum.id)
                if (success) {
                    _expandedForums.value = _expandedForums.value - forum.id
                    _connectionErrorForums.update { it - forum.id }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    fun navigateHistoryBack() {
        viewModelScope.launch {
            val prev = historyRepository.getPrevious()
            if (prev != null) {
                _navigateToMessage.emit(Triple(prev.forumName, prev.topicName, prev.messageId))
            } else {
                _historyEvent.emit("Start of history reached")
            }
        }
    }

    fun navigateHistoryForward() {
        viewModelScope.launch {
            val next = historyRepository.getNext()
            if (next != null) {
                _navigateToMessage.emit(Triple(next.forumName, next.topicName, next.messageId))
            } else {
                _historyEvent.emit("End of history reached")
            }
        }
    }

    fun setShowOnlyUnread(onlyUnread: Boolean) {
        _showOnlyUnread.value = onlyUnread
    }

    override fun showProfile(user: String) = profileDelegate.showProfile(viewModelScope, user)
    override fun dismissProfile() = profileDelegate.dismissProfile()
}

class ForumViewModelFactory(
    private val api: CixApi,
    private val repository: ForumRepository,
    private val cachedProfileDao: CachedProfileDao,
    private val historyRepository: HistoryRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ForumViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ForumViewModel(api, repository, cachedProfileDao, historyRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
