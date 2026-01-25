package com.cixonline.cixreader.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.repository.ForumRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ForumViewModel(private val repository: ForumRepository) : ViewModel() {

    val allFolders: Flow<List<Folder>> = repository.allFolders
    
    private val _expandedForums = MutableStateFlow<Set<Int>>(emptySet())
    val expandedForums: StateFlow<Set<Int>> = _expandedForums.asStateFlow()

    private val _showOnlyUnread = MutableStateFlow(true)
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
