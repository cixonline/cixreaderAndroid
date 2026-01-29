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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

class ForumViewModel(
    private val api: CixApi,
    private val repository: ForumRepository,
    private val cachedProfileDao: CachedProfileDao
) : ViewModel(), ProfileHost {

    private val profileDelegate = ProfileDelegate(api, cachedProfileDao)

    private val _cutoff = MutableStateFlow(
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -30)
        }.timeInMillis
    )
    
    val allFolders: Flow<List<Folder>> = _cutoff.flatMapLatest { cutoff ->
        repository.allFoldersWithCutoff(cutoff)
    }
    
    private val _expandedForums = MutableStateFlow<Set<Int>>(emptySet())
    val expandedForums: StateFlow<Set<Int>> = _expandedForums.asStateFlow()

    private val _showOnlyUnread = MutableStateFlow(true)
    val showOnlyUnread: StateFlow<Boolean> = _showOnlyUnread.asStateFlow()

    private val _loadingForums = MutableStateFlow<Set<Int>>(emptySet())
    val loadingForums: StateFlow<Set<Int>> = _loadingForums.asStateFlow()

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    override val selectedProfile: StateFlow<UserProfile?> = profileDelegate.selectedProfile
    override val selectedResume: StateFlow<String?> = profileDelegate.selectedResume
    override val selectedMugshotUrl: StateFlow<String?> = profileDelegate.selectedMugshotUrl
    override val isProfileLoading: StateFlow<Boolean> = profileDelegate.isLoading

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            // Reset cutoff to 30 days ago on refresh
            _cutoff.value = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -30)
            }.timeInMillis

            try {
                repository.refreshForums()
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
        } else {
            _expandedForums.value = current + forumId
            // Refresh topics for this forum when expanded
            viewModelScope.launch {
                _loadingForums.value += forumId
                isLoading = true
                try {
                    repository.refreshTopics(forum.name, forum.id)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    _loadingForums.value -= forumId
                    isLoading = false
                }
            }
        }
    }

    fun setShowOnlyUnread(onlyUnread: Boolean) {
        _showOnlyUnread.value = onlyUnread
    }

    override fun showProfile(user: String) {
        profileDelegate.showProfile(viewModelScope, user)
    }

    override fun dismissProfile() {
        profileDelegate.dismissProfile()
    }
}

class ForumViewModelFactory(
    private val api: CixApi,
    private val repository: ForumRepository,
    private val cachedProfileDao: CachedProfileDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ForumViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ForumViewModel(api, repository, cachedProfileDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
