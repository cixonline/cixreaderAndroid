package com.cixonline.cixreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

interface ProfileHost {
    val selectedProfile: StateFlow<UserProfile?>
    val isProfileLoading: StateFlow<Boolean>
    fun showProfile(user: String)
    fun dismissProfile()
}

class ProfileDelegate(private val api: CixApi) {
    private val _selectedProfile = MutableStateFlow<UserProfile?>(null)
    val selectedProfile: StateFlow<UserProfile?> = _selectedProfile

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun showProfile(scope: kotlinx.coroutines.CoroutineScope, user: String) {
        scope.launch {
            _isLoading.value = true
            try {
                val profile = api.getProfile(user)
                _selectedProfile.value = profile
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun dismissProfile() {
        _selectedProfile.value = null
    }
}
