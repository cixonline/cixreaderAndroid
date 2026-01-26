package com.cixonline.cixreader.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.repository.ForumRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ForumViewModel(private val repository: ForumRepository) : ViewModel() {

    private val _cutoff = MutableStateFlow(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
    
    val allFolders: Flow<List<Folder>> = _cutoff.flatMapLatest { cutoff ->
        repository.allFoldersWithCutoff(cutoff)
    }
    
    private val _expandedForums = MutableStateFlow<Set<Int>>(emptySet())
    val expandedForums: StateFlow<Set<Int>> = _expandedForums.asStateFlow()

    private val _showOnlyUnread = MutableStateFlow(false)
    val showOnlyUnread: StateFlow<Boolean> = _showOnlyUnread.asStateFlow()

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            _cutoff.value = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
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
                repository.refreshTopics(forum.name, forum.id)
            }
        }
    }

    fun setShowOnlyUnread(onlyUnread: Boolean) {
        _showOnlyUnread.value = onlyUnread
    }
}

class ForumViewModelFactory(private val repository: ForumRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ForumViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ForumViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
