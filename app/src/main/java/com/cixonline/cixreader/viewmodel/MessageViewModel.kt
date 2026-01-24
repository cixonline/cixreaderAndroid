package com.cixonline.cixreader.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class MessageViewModel(
    private val repository: MessageRepository,
    private val forumName: String,
    private val topicName: String,
    private val topicId: Int
) : ViewModel() {

    val messages: Flow<List<CIXMessage>> = repository.getMessagesForTopic(topicId)

    var isLoading by mutableStateOf(false)
        private set

    fun refresh() {
        viewModelScope.launch {
            isLoading = true
            try {
                repository.refreshMessages(forumName, topicName, topicId)
            } finally {
                isLoading = false
            }
        }
    }

    fun postMessage(body: String, replyTo: Int, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            isLoading = true
            try {
                val result = repository.postMessage(forumName, topicName, body, replyTo)
                val success = result != 0
                if (success) {
                    repository.refreshMessages(forumName, topicName, topicId)
                }
                onComplete(success)
            } finally {
                isLoading = false
            }
        }
    }
}

class MessageViewModelFactory(
    private val repository: MessageRepository,
    private val forumName: String,
    private val topicName: String,
    private val topicId: Int
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MessageViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MessageViewModel(repository, forumName, topicName, topicId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
