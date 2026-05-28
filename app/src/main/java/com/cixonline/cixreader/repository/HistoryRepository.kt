package com.cixonline.cixreader.repository

import com.cixonline.cixreader.db.HistoryDao
import com.cixonline.cixreader.models.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class HistoryRepository(private val historyDao: HistoryDao) {
    val allHistory: Flow<List<HistoryEntry>> = historyDao.getAll()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    suspend fun addToHistory(forum: String, topic: String, topicId: Int, messageId: Int) = withContext(Dispatchers.IO) {
        val history = allHistory.first()
        // Don't add if it's the same as the latest entry
        if (history.isNotEmpty()) {
            val latest = history.first()
            if (latest.messageId == messageId && latest.topicId == topicId) {
                _currentIndex.value = 0
                return@withContext
            }
        }

        val entry = HistoryEntry(
            forumName = forum,
            topicName = topic,
            topicId = topicId,
            messageId = messageId
        )
        historyDao.insert(entry)
        historyDao.prune()
        _currentIndex.value = 0
    }

    suspend fun getPrevious(): HistoryEntry? = withContext(Dispatchers.IO) {
        val history = allHistory.first()
        val nextIndex = _currentIndex.value + 1
        if (nextIndex < history.size) {
            _currentIndex.value = nextIndex
            history[nextIndex]
        } else {
            null
        }
    }

    suspend fun getNext(): HistoryEntry? = withContext(Dispatchers.IO) {
        val history = allHistory.first()
        val nextIndex = _currentIndex.value - 1
        if (nextIndex >= 0 && nextIndex < history.size) {
            _currentIndex.value = nextIndex
            history[nextIndex]
        } else {
            null
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        historyDao.clear()
        _currentIndex.value = -1
    }
}
