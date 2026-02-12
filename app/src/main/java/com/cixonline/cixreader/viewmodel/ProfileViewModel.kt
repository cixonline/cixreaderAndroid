package com.cixonline.cixreader.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.UserProfile
import com.cixonline.cixreader.db.CachedProfileDao
import com.cixonline.cixreader.models.CachedProfile
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

interface ProfileHost {
    val selectedProfile: StateFlow<UserProfile?>
    val selectedResume: StateFlow<String?>
    val selectedMugshotUrl: StateFlow<String?>
    val isProfileLoading: StateFlow<Boolean>
    fun showProfile(user: String)
    fun dismissProfile()
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

    fun showProfile(scope: kotlinx.coroutines.CoroutineScope, user: String) {
        scope.launch {
            _isLoading.value = true
            _selectedProfile.value = null
            _selectedResume.value = null
            _selectedMugshotUrl.value = null
            
            try {
                val cached = cachedProfileDao.getProfile(user)
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
                    }
                    _selectedProfile.value = profile
                    _selectedResume.value = cached.resume
                    _selectedMugshotUrl.value = cached.mugshotUrl
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
                    val mugshotJob = async {
                        try {
                            val mugshot = api.getMugshot(user)
                            val url = mugshot.image
                            if (!url.isNullOrBlank()) {
                                val cleanedUrl = HtmlUtils.cleanCixUrls(url)
                                Log.d(tag, "Mugshot URL for $user: $cleanedUrl")
                                cleanedUrl
                            } else {
                                // Default to the direct mugshot endpoint if XML returns empty
                                "https://api.cixonline.com/v2.0/cix.svc/user/$user/mugshot"
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to fetch mugshot XML for $user, falling back to direct URL", e)
                            "https://api.cixonline.com/v2.0/cix.svc/user/$user/mugshot"
                        }
                    }

                    val resume = resumeJob.await()
                    val mugshotUrl = mugshotJob.await()
                    
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
                            lastUpdated = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "showProfile failed for $user", e)
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
        fun getMugshotUrl(userName: String?): String? {
            if (userName.isNullOrBlank()) return null
            return "https://api.cixonline.com/v2.0/cix.svc/user/$userName/mugshot"
        }
    }
}
