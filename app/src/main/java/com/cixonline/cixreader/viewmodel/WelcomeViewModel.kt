package com.cixonline.cixreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.InterestingThreadApi
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.api.WhoApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WelcomeViewModel(
    private val api: CixApi,
    private val messageDao: MessageDao
) : ViewModel() {

    private val _onlineUsers = MutableStateFlow<List<WhoApi>>(emptyList())
    val onlineUsers: StateFlow<List<WhoApi>> = _onlineUsers

    private val _interestingThreads = MutableStateFlow<List<InterestingThreadApi>>(emptyList())
    val interestingThreads: StateFlow<List<InterestingThreadApi>> = _interestingThreads

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = api.getInterestingThreads(count = 20)
                _interestingThreads.value = response.messages ?: emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun getFirstUnreadMessage(): CIXMessage? {
        return messageDao.getFirstUnreadMessage()
    }
}

class WelcomeViewModelFactory(
    private val api: CixApi,
    private val messageDao: MessageDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WelcomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WelcomeViewModel(api, messageDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
