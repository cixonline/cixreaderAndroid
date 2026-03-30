package com.cixonline.cixreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.models.LogEntry
import com.cixonline.cixreader.repository.LogRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ActivityLogViewModel(private val logRepository: LogRepository) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val logs: StateFlow<List<LogEntry>> = combine(
        logRepository.getAllLogs(),
        _searchQuery
    ) { logs, query ->
        if (query.isBlank()) {
            logs
        } else {
            logs.filter { it.message.contains(query, ignoreCase = true) || it.type.contains(query, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearLogs() {
        viewModelScope.launch {
            logRepository.clearLogs()
        }
    }
}
