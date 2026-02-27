package com.cixonline.cixreader.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.api.PostAttachment
import com.cixonline.cixreader.db.DraftDao
import com.cixonline.cixreader.models.Draft
import com.cixonline.cixreader.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DraftsViewModel(
    private val draftDao: DraftDao,
    private val repository: MessageRepository
) : ViewModel() {

    val drafts: Flow<List<Draft>> = draftDao.getAllDrafts()

    private val _isPosting = MutableStateFlow<Int?>(null) // ID of draft being posted
    val isPosting: StateFlow<Int?> = _isPosting

    fun deleteDraft(draftId: Int) {
        viewModelScope.launch {
            draftDao.deleteDraft(draftId)
        }
    }

    fun postDraft(context: Context, draft: Draft, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isPosting.value = draft.id
            val author = NetworkClient.getUsername()
            
            val attachments = if (draft.attachmentUri != null && draft.attachmentName != null) {
                try {
                    val uri = Uri.parse(draft.attachmentUri)
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (bytes != null) {
                        listOf(PostAttachment(data = Base64.encodeToString(bytes, Base64.NO_WRAP), filename = draft.attachmentName))
                    } else null
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            } else null

            val resultId = repository.postMessage(
                forum = draft.forumName,
                topic = draft.topicName,
                body = draft.body,
                replyTo = draft.replyToId,
                author = author,
                attachments = attachments
            )
            
            if (resultId > 0) {
                draftDao.deleteDraft(draft.id)
                onSuccess()
            }
            _isPosting.value = null
        }
    }

    fun saveDraft(draft: Draft) {
        viewModelScope.launch {
            draftDao.insertDraft(draft)
        }
    }
}

class DraftsViewModelFactory(
    private val draftDao: DraftDao,
    private val repository: MessageRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DraftsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DraftsViewModel(draftDao, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
