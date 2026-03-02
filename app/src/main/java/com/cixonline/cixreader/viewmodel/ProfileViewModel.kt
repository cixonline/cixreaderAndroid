package com.cixonline.cixreader.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
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

    init {
        delegate.showProfile(viewModelScope, username)
    }

    fun refresh() {
        delegate.showProfile(viewModelScope, username, forceRefresh = true)
    }

    fun updateProfile(fullName: String?, email: String?, location: String?, about: String?, experience: String?, resume: String?) {
        viewModelScope.launch {
            try {
                // Split full name into first and last name as expected by the new API model
                val names = (fullName ?: "").trim().split(Regex("\\s+"), 2)
                val firstName = names.getOrNull(0) ?: ""
                val lastName = names.getOrNull(1) ?: ""

                // Get current profile to preserve dates if needed, or default to current date format if empty
                val currentProfile = selectedProfile.value
                
                val profileRequest = SetProfileRequest(
                    uname = username,
                    fname = firstName,
                    sname = lastName,
                    email = email ?: "",
                    location = location ?: "",
                    // These fields are in the contract but we'll leave them blank or use current values
                    firstOn = currentProfile?.firstOn ?: "",
                    lastOn = currentProfile?.lastOn ?: "",
                    lastPost = currentProfile?.lastPost ?: "",
                    sex = "" // Not currently collected in UI
                )
                api.setProfile(profileRequest)
                
                if (resume != null) {
                    // The server expects the raw resume string, not an XML-wrapped object
                    api.setResume(resume)
                }

                // Refresh profile after update
                delegate.showProfile(viewModelScope, username, forceRefresh = true)
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Failed to update profile", e)
            }
        }
    }

    fun uploadMugshot(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"
                val file = uriToFile(context, uri, extension) ?: return@launch
                
                val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
                // The API parameter name in CixApi is "image", so the multipart form data part name must match.
                val body = MultipartBody.Part.createFormData("image", file.name, requestFile)
                
                // Pre-emptively clear mugshot to show loading state/reset UI
                delegate.clearMugshot()
                
                val response = api.setMugshot(body)
                Log.d("ProfileViewModel", "Mugshot upload response: ${response.string()}")
                
                // Wait a short moment for the server to process the image update
                kotlinx.coroutines.delay(1000)
                
                // Refresh to show new image
                delegate.showProfile(viewModelScope, username, forceRefresh = true)
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Failed to upload mugshot", e)
            }
        }
    }

    private fun uriToFile(context: Context, uri: Uri, extension: String): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val file = File(context.cacheDir, "mugshot_upload.$extension")
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            file
        } catch (e: Exception) {
            Log.e("ProfileViewModel", "Error converting Uri to File", e)
            null
        }
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

    private val CACHE_EXPIRATION_MS = 24 * 60 * 60 * 1000L // 1 day

    fun clearMugshot() {
        _selectedMugshotUrl.value = null
    }

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
                    // Ensure we have a mugshot URL even if it was null in cache
                    _selectedMugshotUrl.value = cached.mugshotUrl ?: getMugshotXmlUrl(user)
                    _isLoading.value = false
                    return@launch
                }

                val profile = api.getProfile(user)
                if (profile.userName.isNullOrBlank()) {
                    profile.userName = user
                }
                _selectedProfile.value = profile

                coroutineScope {
                    val resumeJob = async {
                        try {
                            val response = api.getResume(user)
                            val xml = response.string()
                            val rawContent = extractRawContent(xml)
                            if (!rawContent.isNullOrBlank()) {
                                HtmlUtils.decodeHtml(rawContent).trim()
                            } else null
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to fetch resume for $user", e)
                            null
                        }
                    }
                    
                    val resume = resumeJob.await()
                    val mugshotUrl = getMugshotXmlUrl(user)
                    
                    _selectedResume.value = resume
                    _selectedMugshotUrl.value = mugshotUrl

                    cachedProfileDao.insertProfile(
                        CachedProfile(
                            userName = user,
                            fullName = profile.fullName,
                            location = profile.location,
                            email = profile.email,
                            firstOn = profile.firstOn,
                            lastOn = profile.lastOn,
                            lastPost = profile.lastPost,
                            about = profile.about,
                            resume = resume,
                            mugshotUrl = mugshotUrl,
                            experience = profile.experience,
                            lastUpdated = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "showProfile failed for $user", e)
                // Even on error, try to set a default mugshot URL if we have a user
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
        val bodyMatch = Regex("<Body[^>]*>(.*?)</Body>", RegexOption.DOT_MATCHES_ALL).find(xml)
        if (bodyMatch != null) return bodyMatch.groupValues[1]
        val resumeMatch = Regex("<Resume[^>]*>(.*?)</Resume>", RegexOption.DOT_MATCHES_ALL).find(xml)
        if (resumeMatch != null) return resumeMatch.groupValues[1]
        
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            val sb = StringBuilder()
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.TEXT) sb.append(parser.text)
                else if (eventType == XmlPullParser.START_TAG) {
                    val name = parser.name.lowercase()
                    if (name == "br") sb.append("\n")
                    else if (name == "p" || name == "div") sb.append("\n\n")
                }
                eventType = parser.next()
            }
            val result = sb.toString().trim()
            if (result.isNotEmpty()) result else null
        } catch (e: Exception) {
            xml.replace(Regex("<[^>]*>"), " ").trim().replace(Regex(" +"), " ")
        }
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
