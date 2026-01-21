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
import kotlinx.coroutines.launch

class TopicListViewModel(
    private val repository: ForumRepository,
    private val forumName: String,
    private val forumId: Int
) : ViewModel() {

    val topics: Flow<List<Folder>> = repository.getTopics(forumId)

    var isLoading by mutableStateOf(false)
        private set

    fun refresh() {
        viewModelScope.launch {
            isLoading = true
            try {
                repository.refreshTopics(forumName, forumId)
            } finally {
                isLoading = false
            }
        }
    }
}

class TopicListViewModelFactory(
    private val repository: ForumRepository,
    private val forumName: String,
    private val forumId: Int
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TopicListViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TopicListViewModel(repository, forumName, forumId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
