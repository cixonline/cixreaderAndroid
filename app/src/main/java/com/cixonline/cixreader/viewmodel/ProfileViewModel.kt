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
        delegate.showProfile(viewModelScope, username)
    }

    fun refresh() {
        delegate.showProfile(viewModelScope, username, forceRefresh = true)
    }

    fun setPendingMugshot(context: Context, uri: Uri?) {
        if (uri == null) {
            _pendingMugshotUri.value = null
            return
        }
        _pendingMugshotUri.value = uri
    }
    
    fun setPendingMugshotBitmap(bitmap: Bitmap?) {
        _pendingMugshotBitmap.value = bitmap
        // When a bitmap is set via editor, we clear the raw URI as the bitmap is the "final" version to upload
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
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        
        Log.d("ProfileViewModel", "Uploading edited mugshot bytes as PNG (${bytes.size} bytes)")
        
        try {
            val requestBody = bytes.toRequestBody("image/png".toMediaTypeOrNull())
            val response = api.setMugshot(requestBody)
            Log.d("ProfileViewModel", "Mugshot upload response: ${response.string()}")
        } catch (e: Exception) {
            Log.e("ProfileViewModel", "Mugshot upload failed", e)
        }
    }

    private suspend fun uploadMugshotInternal(context: Context, uri: Uri) {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return
        val originalBytes = inputStream.readBytes()
        inputStream.close()
        
        val bytes = try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, options)
            
            val maxSide = 100
            val bitmap = if (options.outWidth > maxSide || options.outHeight > maxSide) {
                val scale = Math.max(options.outWidth / maxSide, options.outHeight / maxSide)
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
                BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOptions)
            } else {
                BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
            }

            if (bitmap != null) {
                val scaledBitmap = if (bitmap.width > maxSide || bitmap.height > maxSide) {
                    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    var width = maxSide
                    var height = maxSide
                    if (ratio > 1) {
                        height = (maxSide / ratio).toInt()
                    } else {
                        width = (maxSide * ratio).toInt()
                    }
                    Bitmap.createScaledBitmap(bitmap, width, height, true)
                } else {
                    bitmap
                }

                val out = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                val result = out.toByteArray()
                if (scaledBitmap != bitmap) scaledBitmap.recycle()
                bitmap.recycle()
                result
            } else originalBytes
        } catch (e: Exception) {
            Log.e("ProfileViewModel", "Error resizing or converting image", e)
            originalBytes
        }
        
        Log.d("ProfileViewModel", "Uploading raw mugshot bytes as PNG to user/setmugshot.xml (${bytes.size} bytes)")
        
        try {
            val requestBody = bytes.toRequestBody("image/png".toMediaTypeOrNull())
            val response = api.setMugshot(requestBody)
            Log.d("ProfileViewModel", "Mugshot upload response: ${response.string()}")
        } catch (e: Exception) {
            Log.e("ProfileViewModel", "Mugshot upload failed", e)
        }
        
        kotlinx.coroutines.delay(1000)
    }

    private fun uriToFile(context: Context, uri: Uri, extension: String): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val file = File(context.cacheDir, "mugshot_pending.$extension")
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
