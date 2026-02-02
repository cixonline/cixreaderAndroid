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
import com.cixonline.cixreader.db.CachedProfileDao
import com.cixonline.cixreader.db.DraftDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.models.Draft
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
    private val dirForumDao: DirForumDao,
    private val cachedProfileDao: CachedProfileDao,
    private val draftDao: DraftDao
) : ViewModel(), ProfileHost {

    private val profileDelegate = ProfileDelegate(api, cachedProfileDao)

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
                // We decode names before grouping to ensure consistent keys.
                val uniqueThreads = threads.distinctBy { 
                    val f = HtmlUtils.normalizeName(it.forum)
                    val t = HtmlUtils.normalizeName(it.topic)
                    "$f/$t/${it.effectiveRootId}" 
                }

                // Get current membership set to avoid 400s on message.xml
                val memberForums = folderDao.getAll().first()
                    .filter { it.isRootFolder }
                    .map { it.name.lowercase() }
                    .toSet()

                val resolvedThreads = uniqueThreads.map { thread ->
                    async {
                        val forum = HtmlUtils.normalizeName(thread.forum ?: "")
                        val topic = HtmlUtils.normalizeName(thread.topic ?: "")
                        val rootId = thread.effectiveRootId
                        val topicId = HtmlUtils.calculateTopicId(forum, topic)
                        
                        if (rootId == 0) {
                            return@async InterestingThreadUI(
                                forum = forum,
                                topic = topic,
                                rootId = 0,
                                author = HtmlUtils.decodeHtml(thread.author ?: ""),
                                dateTime = DateUtils.formatCixDate(thread.dateTime),
                                body = HtmlUtils.decodeHtml(thread.body ?: ""),
                                subject = if (!thread.subject.isNullOrBlank()) HtmlUtils.decodeHtml(thread.subject!!) else null,
                                isRootResolved = false
                            )
                        }

                        // 1. Try to get from cache
                        var cachedRoot = messageDao.getByRemoteId(rootId, topicId)
                        
                        // 2. Not in cache AND we are a member, fetch using message.xml API
                        if (cachedRoot == null && memberForums.contains(forum.lowercase())) {
                            try {
                                val encodedForum = HtmlUtils.cixEncode(forum)
                                val encodedTopic = HtmlUtils.cixEncode(topic)
                                val messageApi = api.getMessage(encodedForum, encodedTopic, rootId)
                                
                                val newMessage = CIXMessage(
                                    remoteId = messageApi.id,
                                    author = HtmlUtils.decodeHtml(messageApi.author ?: ""),
                                    body = HtmlUtils.decodeHtml(messageApi.body ?: ""),
                                    date = DateUtils.parseCixDate(messageApi.dateTime),
                                    commentId = messageApi.replyTo,
                                    rootId = messageApi.rootId,
                                    topicId = topicId,
                                    forumName = forum,
                                    topicName = topic,
                                    unread = true
                                )
                                messageDao.insert(newMessage)
                                cachedRoot = newMessage
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        if (cachedRoot != null) {
                            InterestingThreadUI(
                                forum = forum,
                                topic = topic,
                                rootId = rootId,
                                author = cachedRoot.author,
                                dateTime = DateUtils.formatDateTime(cachedRoot.date),
                                body = cachedRoot.body,
                                // Use subject from thread set if available, as message.xml doesn't return it
                                subject = if (!thread.subject.isNullOrBlank()) HtmlUtils.decodeHtml(thread.subject!!) else null,
                                isRootResolved = true
                            )
                        } else {
                            // Fallback to original thread data if resolution fails OR not a member
                            val displayDateTime = thread.dateTime ?: ""
                            val formattedDateTime = if (displayDateTime.contains("T") || displayDateTime.contains("-")) {
                                DateUtils.formatCixDate(displayDateTime)
                            } else {
                                displayDateTime
                            }

                            InterestingThreadUI(
                                forum = forum,
                                topic = topic,
                                rootId = rootId,
                                author = HtmlUtils.decodeHtml(thread.author ?: ""),
                                dateTime = formattedDateTime,
                                body = HtmlUtils.decodeHtml(thread.body ?: ""),
                                subject = if (!thread.subject.isNullOrBlank()) HtmlUtils.decodeHtml(thread.subject!!) else null,
                                isRootResolved = false
                            )
                        }
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

    suspend fun joinForumIfNeeded(forumName: String): Boolean {
        val currentForums = folderDao.getAll().first()
        val isMember = currentForums.any { it.isRootFolder && it.name.equals(forumName, ignoreCase = true) }
        
        if (!isMember) {
            return try {
                val encodedForum = HtmlUtils.cixEncode(forumName)
                val response = api.joinForum(encodedForum)
                val result = extractStringFromXml(response.string())
                if (result == "Success") {
                    // Refresh folders after joining
                    refreshFolders()
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
        return true
    }

    private suspend fun refreshFolders() {
        try {
            val resultSet = api.getForums()
            val folders = resultSet.forums.mapNotNull { row ->
                val name = row.name ?: return@mapNotNull null
                val normalizedName = HtmlUtils.normalizeName(name)
                Folder(
                    id = HtmlUtils.calculateForumId(normalizedName),
                    name = normalizedName,
                    parentId = -1,
                    unread = row.unread?.toIntOrNull() ?: 0,
                    unreadPriority = row.priority?.toIntOrNull() ?: 0
                )
            }
            folderDao.insertAll(folders)
        } catch (e: Exception) {
            e.printStackTrace()
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
                val topicId = HtmlUtils.calculateTopicId(forum, topic)
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
                // Delete draft if exists
                draftDao.deleteDraftForContext(forum, topic, 0)
                true
            } else {
                result == "Success"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun saveDraft(body: String) {
        val forum = _selectedForum.value?.name ?: return
        val topic = topicsForSelectedForum.value.find { it.name == suggestedForumAndTopic.value?.second?.name }?.name ?: return
        viewModelScope.launch {
            val draft = Draft(
                forumName = forum,
                topicName = topic,
                replyToId = 0,
                body = body
            )
            draftDao.insertDraft(draft)
        }
    }

    suspend fun getDraftForContext(forum: String, topic: String): Draft? {
        return draftDao.getDraft(forum, topic, 0)
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
    private val dirForumDao: DirForumDao,
    private val cachedProfileDao: CachedProfileDao,
    private val draftDao: DraftDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WelcomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WelcomeViewModel(api, messageDao, folderDao, dirForumDao, cachedProfileDao, draftDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
