package com.cixonline.cixreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.models.LogEntry
import com.cixonline.cixreader.repository.LogRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ActivityLogViewModel(private val logRepository: LogRepository) : ViewModel() {
    val logs: StateFlow<List<LogEntry>> = logRepository.getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearLogs() {
        viewModelScope.launch {
            logRepository.clearLogs()
        }
    }
}
