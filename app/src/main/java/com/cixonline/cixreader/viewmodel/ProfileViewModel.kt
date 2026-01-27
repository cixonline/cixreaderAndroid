package com.cixonline.cixreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.cixonline.cixreader.api.NetworkClient

interface ProfileHost {
    val selectedProfile: StateFlow<UserProfile?>
    val selectedResume: StateFlow<String?>
    val isProfileLoading: StateFlow<Boolean>
    fun showProfile(user: String)
    fun dismissProfile()
}

class ProfileDelegate(private val api: CixApi) {
    private val _selectedProfile = MutableStateFlow<UserProfile?>(null)
    val selectedProfile: StateFlow<UserProfile?> = _selectedProfile

    private val _selectedResume = MutableStateFlow<String?>(null)
    val selectedResume: StateFlow<String?> = _selectedResume

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun showProfile(scope: kotlinx.coroutines.CoroutineScope, user: String) {
        scope.launch {
            _isLoading.value = true
            try {
                coroutineScope {
                    val profileDeferred = async { api.getProfile(user) }
                    val resumeDeferred = async { 
                        try { api.getResume(user).body } catch (e: Exception) { null }
                    }
                    
                    _selectedProfile.value = profileDeferred.await()
                    _selectedResume.value = resumeDeferred.await()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun dismissProfile() {
        _selectedProfile.value = null
        _selectedResume.value = null
    }

    companion object {
        fun getMugshotUrl(userName: String?): String? {
            if (userName.isNullOrBlank()) return null
            // Use the base URL from NetworkClient to ensure consistency
            return "https://api.cixonline.com/v2.0/cix.svc/user/$userName/mugshot"
        }
    }
}
