package com.cixonline.cixreader.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.SetProfileRequest
import com.cixonline.cixreader.api.UserProfile
import com.cixonline.cixreader.db.CachedProfileDao
import com.cixonline.cixreader.models.CachedProfile
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader

interface ProfileHost {
    val selectedProfile: StateFlow<UserProfile?>
    val selectedResume: StateFlow<String?>
    val selectedMugshotUrl: StateFlow<String?>
    val isProfileLoading: StateFlow<Boolean>
    fun showProfile(user: String)
    fun dismissProfile()
}

class ProfileViewModel(
    private val api: CixApi,
    private val cachedProfileDao: CachedProfileDao,
    val username: String
) : ViewModel() {
    private val delegate = ProfileDelegate(api, cachedProfileDao)
    val selectedProfile = delegate.selectedProfile
    val selectedResume = delegate.selectedResume
    val selectedMugshotUrl = delegate.selectedMugshotUrl
    val isProfileLoading = delegate.isLoading

    private val _pendingMugshotUri = MutableStateFlow<Uri?>(null)
    val pendingMugshotUri: StateFlow<Uri?> = _pendingMugshotUri
    
    private val _pendingMugshotBitmap = MutableStateFlow<Bitmap?>(null)
    val pendingMugshotBitmap: StateFlow<Bitmap?> = _pendingMugshotBitmap

    init {
        // Force refresh on every page load to ensure fresh data and mugshot
        refresh()
    }

    fun refresh() {
        delegate.showProfile(viewModelScope, username, forceRefresh = true)
    }

    fun setPendingMugshot(context: Context, uri: Uri?) {
        _pendingMugshotUri.value = uri
    }
    
    fun setPendingMugshotBitmap(bitmap: Bitmap?) {
        _pendingMugshotBitmap.value = bitmap
        // Clearing the pending URI will close the MugshotEditor in ProfileScreen
        _pendingMugshotUri.value = null
    }

    fun clearPendingMugshot() {
        _pendingMugshotUri.value = null
        _pendingMugshotBitmap.value = null
    }

    fun updateProfile(context: Context, fullName: String?, email: String?, location: String?, about: String?, experience: String?, resume: String?) {
        viewModelScope.launch {
            try {
                val names = (fullName ?: "").trim().split(Regex("\\s+"), 2)
                val firstName = names.getOrNull(0) ?: ""
                val lastName = names.getOrNull(1) ?: ""

                val currentProfile = selectedProfile.value
                
                val profileRequest = SetProfileRequest(
                    uname = username,
                    fname = firstName,
                    sname = lastName,
                    email = email ?: "",
                    location = location ?: "",
                    firstOn = currentProfile?.firstOn ?: "",
                    lastOn = currentProfile?.lastOn ?: "",
                    lastPost = currentProfile?.lastPost ?: "",
                    sex = ""
                )
                api.setProfile(profileRequest)
                
                if (resume != null) {
                    api.setResume(resume)
                }

                // Upload the mugshot only when the profile is saved
                _pendingMugshotBitmap.value?.let { bitmap ->
                    try {
                        uploadMugshotBitmapInternal(bitmap)
                        _pendingMugshotBitmap.value = null
                    } catch (e: Exception) {
                        Log.e("ProfileViewModel", "Mugshot bitmap upload failed", e)
                    }
                } ?: _pendingMugshotUri.value?.let { uri ->
                    try {
                        uploadMugshotInternal(context, uri)
                        _pendingMugshotUri.value = null
                    } catch (e: Exception) {
                        Log.e("ProfileViewModel", "Mugshot upload failed", e)
                    }
                }

                delegate.showProfile(viewModelScope, username, forceRefresh = true)
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Failed to update profile", e)
            }
        }
    }

    private suspend fun uploadMugshotBitmapInternal(bitmap: Bitmap) {
        val out = ByteArrayOutputStream()
        // Ensure 100x100 as per CIX requirements
        val finalBitmap = if (bitmap.width != 100 || bitmap.height != 100) {
             Bitmap.createScaledBitmap(bitmap, 100, 100, true)
        } else bitmap

        finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        
        try {
            val requestBody = bytes.toRequestBody("image/png".toMediaTypeOrNull())
            api.setMugshot(requestBody)
        } finally {
            if (finalBitmap != bitmap) finalBitmap.recycle()
        }
    }

    private suspend fun uploadMugshotInternal(context: Context, uri: Uri) {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return
        val bytes = inputStream.readBytes()
        inputStream.close()
        
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        
        val maxSide = 100
        if (options.outWidth > maxSide || options.outHeight > maxSide) {
            val scale = Math.max(options.outWidth / maxSide, options.outHeight / maxSide)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            if (bitmap != null) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                val resizedBytes = out.toByteArray()
                api.setMugshot(resizedBytes.toRequestBody("image/png".toMediaTypeOrNull()))
                bitmap.recycle()
                return
            }
        }
        api.setMugshot(bytes.toRequestBody("image/png".toMediaTypeOrNull()))
    }
}

class ProfileViewModelFactory(
    private val api: CixApi,
    private val cachedProfileDao: CachedProfileDao,
    private val username: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(api, cachedProfileDao, username) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class ProfileDelegate(
    private val api: CixApi,
    private val cachedProfileDao: CachedProfileDao
) {
    private val tag = "ProfileDelegate"
    private val _selectedProfile = MutableStateFlow<UserProfile?>(null)
    val selectedProfile: StateFlow<UserProfile?> = _selectedProfile

    private val _selectedResume = MutableStateFlow<String?>(null)
    val selectedResume: StateFlow<String?> = _selectedResume

    private val _selectedMugshotUrl = MutableStateFlow<String?>(null)
    val selectedMugshotUrl: StateFlow<String?> = _selectedMugshotUrl

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val CACHE_EXPIRATION_MS = 24 * 60 * 60 * 1000L

    fun showProfile(scope: kotlinx.coroutines.CoroutineScope, user: String, forceRefresh: Boolean = false) {
        scope.launch {
            _isLoading.value = true
            
            try {
                val cached = if (forceRefresh) null else cachedProfileDao.getProfile(user)
                val now = System.currentTimeMillis()
                
                if (cached != null && (now - cached.lastUpdated) < CACHE_EXPIRATION_MS) {
                    val profile = UserProfile().apply {
                        userName = cached.userName
                        fullName = cached.fullName
                        location = cached.location
                        email = cached.email
                        firstOn = cached.firstOn
                        lastOn = cached.lastOn
                        lastPost = cached.lastPost
                        about = cached.about
                        experience = cached.experience
                    }
                    _selectedProfile.value = profile
                    _selectedResume.value = cached.resume
                    _selectedMugshotUrl.value = cached.mugshotUrl ?: getMugshotXmlUrl(user)
                    _isLoading.value = false
                    return@launch
                }

                val profile = api.getProfile(user)
                val serverUser = profile.userName?.takeIf { it.isNotBlank() } ?: user
                if (profile.userName.isNullOrBlank()) profile.userName = serverUser
                _selectedProfile.value = profile
                
                _selectedMugshotUrl.value = getMugshotXmlUrl(serverUser)

                coroutineScope {
                    val resumeJob = async {
                        try {
                            val response = api.getResume(serverUser)
                            extractRawContent(response.string())?.let { HtmlUtils.decodeHtml(it).trim() }
                        } catch (e: Exception) { null }
                    }
                    
                    val resume = resumeJob.await()
                    _selectedResume.value = resume

                    cachedProfileDao.insertProfile(
                        CachedProfile(
                            userName = serverUser,
                            fullName = profile.fullName,
                            location = profile.location,
                            email = profile.email,
                            firstOn = profile.firstOn,
                            lastOn = profile.lastOn,
                            lastPost = profile.lastPost,
                            about = profile.about,
                            resume = resume,
                            mugshotUrl = _selectedMugshotUrl.value,
                            experience = profile.experience,
                            lastUpdated = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("ProfileDelegate", "showProfile failed for $user", e)
                if (_selectedMugshotUrl.value == null) {
                    _selectedMugshotUrl.value = getMugshotXmlUrl(user)
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun extractRawContent(xml: String): String? {
        val trimmedXml = xml.trim()
        if (!trimmedXml.startsWith("<")) return trimmedXml
        Regex("<Body[^>]*>(.*?)</Body>", RegexOption.DOT_MATCHES_ALL).find(xml)?.let { return it.groupValues[1] }
        Regex("<Resume[^>]*>(.*?)</Resume>", RegexOption.DOT_MATCHES_ALL).find(xml)?.let { return it.groupValues[1] }
        return null
    }

    fun dismissProfile() {
        _selectedProfile.value = null
        _selectedResume.value = null
        _selectedMugshotUrl.value = null
    }

    companion object {
        fun getMugshotXmlUrl(userName: String?): String? {
            if (userName.isNullOrBlank()) return null
            return "https://api.cixonline.com/v2.0/cix.svc/user/$userName/mugshot.xml"
        }
    }
}
