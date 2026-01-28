package com.cixonline.cixreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.InterestingThreadApi
import com.cixonline.cixreader.api.UserProfile
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.db.FolderDao
import com.cixonline.cixreader.db.DirForumDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.api.WhoApi
import com.cixonline.cixreader.api.PostMessageRequest
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class InterestingThreadUI(
    val forum: String,
    val topic: String,
    val rootId: Int,
    val author: String,
    val dateTime: String,
    val body: String?,
    val subject: String?,
    val isRootResolved: Boolean = false
)

class WelcomeViewModel(
    private val api: CixApi,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val dirForumDao: DirForumDao
) : ViewModel(), ProfileHost {

    private val profileDelegate = ProfileDelegate(api)

    private val _onlineUsers = MutableStateFlow<List<WhoApi>>(emptyList())
    val onlineUsers: StateFlow<List<WhoApi>> = _onlineUsers

    private val _interestingThreads = MutableStateFlow<List<InterestingThreadUI>>(emptyList())
    val interestingThreads: StateFlow<List<InterestingThreadUI>> = _interestingThreads

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    override val selectedProfile: StateFlow<UserProfile?> = profileDelegate.selectedProfile
    override val selectedResume: StateFlow<String?> = profileDelegate.selectedResume
    override val selectedMugshotUrl: StateFlow<String?> = profileDelegate.selectedMugshotUrl
    override val isProfileLoading: StateFlow<Boolean> = profileDelegate.isLoading

    val allForums: Flow<List<Folder>> = folderDao.getAll().map { folders ->
        folders.filter { it.isRootFolder }
    }

    private val _selectedForum = MutableStateFlow<Folder?>(null)
    val selectedForum: StateFlow<Folder?> = _selectedForum

    val topicsForSelectedForum: StateFlow<List<Folder>> = _selectedForum.flatMapLatest { forum ->
        if (forum == null) flowOf(emptyList())
        else folderDao.getChildren(forum.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _suggestedForumAndTopic = MutableStateFlow<Pair<Folder, Folder>?>(null)
    val suggestedForumAndTopic: StateFlow<Pair<Folder, Folder>?> = _suggestedForumAndTopic

    fun selectForum(forum: Folder?) {
        _selectedForum.value = forum
    }

    fun suggestForumAndTopic(body: String) {
        if (body.isBlank()) {
            _suggestedForumAndTopic.value = null
            return
        }
        viewModelScope.launch {
            val allFolders = folderDao.getAll().first()
            val forums = allFolders.filter { it.isRootFolder }
            val topics = allFolders.filter { !it.isRootFolder }
            val dirForums = dirForumDao.getAll().first()

            val words = body.lowercase()
                .split(Regex("[^a-zA-Z0-9]"))
                .filter { it.length > 3 }
                .toSet()

            if (words.isEmpty()) {
                _suggestedForumAndTopic.value = null
                return@launch
            }

            var bestMatch: Pair<Folder, Folder>? = null
            var maxScore = 0

            for (topic in topics) {
                val forum = forums.find { it.id == topic.parentId } ?: continue
                val dirForum = dirForums.find { it.name.equals(forum.name, ignoreCase = true) }

                var score = 0
                val topicWords = topic.name.lowercase().split(Regex("[^a-zA-Z0-9]")).toSet()
                val forumWords = forum.name.lowercase().split(Regex("[^a-zA-Z0-9]")).toSet()
                val descWords = dirForum?.description?.lowercase()?.split(Regex("[^a-zA-Z0-9]"))?.toSet() ?: emptySet()

                for (word in words) {
                    if (topicWords.contains(word)) score += 10
                    if (forumWords.contains(word)) score += 5
                    if (descWords.contains(word)) score += 2
                }

                if (score > maxScore) {
                    maxScore = score
                    bestMatch = Pair(forum, topic)
                }
            }
            _suggestedForumAndTopic.value = if (maxScore > 10) bestMatch else null
        }
    }

    fun clearSuggestion() {
        _suggestedForumAndTopic.value = null
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Fetch interesting threads from API.
                val response = api.getInterestingThreads(count = 20)
                val threads = response.messages ?: emptyList()
                
                // Group by thread to avoid duplicates, preserving latest activity order.
                val uniqueThreads = threads.distinctBy { "${it.forum}/${it.topic}/${it.rootId}" }

                // Resolve root messages in parallel.
                val resolvedThreads = uniqueThreads.map { thread ->
                    async {
                        val forum = thread.forum ?: ""
                        val topic = thread.topic ?: ""
                        val rootId = thread.rootId
                        val pseudoTopicId = (forum + topic).hashCode()
                        
                        // Initialize with current thread data (likely the latest reply info)
                        var displayAuthor = thread.author ?: ""
                        var displayBody = thread.body ?: ""
                        var displayDateTime = thread.dateTime ?: ""
                        var displaySubject = thread.subject
                        
                        // If the message from interestingthreads API already has a subject, 
                        // it is almost certainly the root message itself.
                        var isResolved = !displaySubject.isNullOrBlank()

                        // If it's a reply (no subject), try to resolve the actual root content.
                        if (!isResolved && rootId > 0) {
                            // 1. Try DB
                            val cachedRoot = messageDao.getByRemoteId(rootId, pseudoTopicId)
                            if (cachedRoot != null) {
                                displayAuthor = cachedRoot.author
                                displayBody = cachedRoot.body
                                displayDateTime = DateUtils.formatDateTime(cachedRoot.date)
                                isResolved = true
                            } else {
                                // 2. Try API
                                try {
                                    val encodedForum = HtmlUtils.cixEncode(forum)
                                    val encodedTopic = HtmlUtils.cixEncode(topic)
                                    val resultSet = api.getMessages(encodedForum, encodedTopic, since = (rootId - 1).toString())
                                    val rootApi = resultSet.messages.find { it.id == rootId }
                                    if (rootApi != null) {
                                        displayAuthor = rootApi.author ?: ""
                                        displayBody = rootApi.body ?: ""
                                        displayDateTime = rootApi.dateTime ?: ""
                                        isResolved = true
                                        
                                        // Cache the resolved root
                                        messageDao.insert(CIXMessage(
                                            remoteId = rootApi.id,
                                            author = HtmlUtils.decodeHtml(rootApi.author ?: ""),
                                            body = HtmlUtils.decodeHtml(rootApi.body ?: ""),
                                            date = DateUtils.parseCixDate(rootApi.dateTime),
                                            commentId = rootApi.replyTo,
                                            rootId = rootApi.rootId,
                                            topicId = pseudoTopicId,
                                            forumName = forum,
                                            topicName = topic,
                                            unread = true
                                        ))
                                    }
                                } catch (e: Exception) {
                                    // Ignore errors during resolution; we will fallback to displaying the interesting message info.
                                }
                            }
                        }
                        
                        val formattedDateTime = if (displayDateTime.contains("T") || displayDateTime.contains("-")) {
                            DateUtils.formatCixDate(displayDateTime)
                        } else {
                            displayDateTime
                        }

                        InterestingThreadUI(
                            forum = forum,
                            topic = topic,
                            rootId = rootId,
                            author = HtmlUtils.decodeHtml(displayAuthor),
                            dateTime = formattedDateTime,
                            body = HtmlUtils.decodeHtml(displayBody),
                            subject = if (!displaySubject.isNullOrBlank()) HtmlUtils.decodeHtml(displaySubject!!) else null,
                            isRootResolved = isResolved
                        )
                    }
                }.awaitAll()
                
                _interestingThreads.value = resolvedThreads
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun getFirstUnreadMessage(): CIXMessage? {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        return messageDao.getFirstUnreadMessage(cutoff)
    }

    private fun extractStringFromXml(xml: String): String {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.TEXT) {
                    return parser.text
                }
                eventType = parser.next()
            }
            xml
        } catch (e: Exception) {
            xml
        }
    }

    suspend fun postMessage(forum: String, topic: String, body: String): Boolean {
        return try {
            val request = PostMessageRequest(body = body, forum = forum, topic = topic)
            val response = api.postMessage(request)
            val result = extractStringFromXml(response.string())
            val messageId = result.toIntOrNull()
            if (messageId != null && messageId > 0) {
                val topicId = (forum + topic).hashCode()
                val newMessage = CIXMessage(
                    remoteId = messageId,
                    author = "me",
                    body = body,
                    date = System.currentTimeMillis(),
                    commentId = 0,
                    rootId = 0,
                    topicId = topicId,
                    forumName = forum,
                    topicName = topic,
                    unread = false
                )
                messageDao.insert(newMessage)
                true
            } else {
                result == "Success"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun showProfile(user: String) {
        profileDelegate.showProfile(viewModelScope, user)
    }

    override fun dismissProfile() {
        profileDelegate.dismissProfile()
    }
}

class WelcomeViewModelFactory(
    private val api: CixApi,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val dirForumDao: DirForumDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WelcomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WelcomeViewModel(api, messageDao, folderDao, dirForumDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
