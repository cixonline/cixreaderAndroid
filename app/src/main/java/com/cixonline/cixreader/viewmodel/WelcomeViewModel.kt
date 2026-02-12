package com.cixonline.cixreader.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.BuildConfig
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.InterestingThreadApi
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.api.UserProfile
import com.cixonline.cixreader.db.MessageDao
import com.cixonline.cixreader.db.FolderDao
import com.cixonline.cixreader.db.DirForumDao
import com.cixonline.cixreader.db.CachedProfileDao
import com.cixonline.cixreader.db.DraftDao
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.models.Draft
import com.cixonline.cixreader.models.DirForum
import com.cixonline.cixreader.api.WhoApi
import com.cixonline.cixreader.api.PostAttachment
import com.cixonline.cixreader.repository.MessageRepository
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.utils.HtmlUtils
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
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
    private val messageRepository: MessageRepository,
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

    private val _selectedTopic = MutableStateFlow<Folder?>(null)
    val selectedTopic: StateFlow<Folder?> = _selectedTopic

    val topicsForSelectedForum: StateFlow<List<Folder>> = _selectedForum.flatMapLatest { forum ->
        if (forum == null) flowOf(emptyList())
        else folderDao.getChildren(forum.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _suggestedForumAndTopic = MutableStateFlow<Pair<Folder, Folder>?>(null)
    val suggestedForumAndTopic: StateFlow<Pair<Folder, Folder>?> = _suggestedForumAndTopic

    // Gemini AI model
    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    fun selectForum(forum: Folder?) {
        _selectedForum.value = forum
        _selectedTopic.value = null
    }

    fun selectTopic(topic: Folder?) {
        _selectedTopic.value = topic
    }

    fun suggestForumAndTopic(body: String) {
        Log.d("WelcomeViewModel", "suggestForumAndTopic called with body: $body")
        if (body.isBlank()) {
            _suggestedForumAndTopic.value = null
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val allFolders = folderDao.getAll().first()
                val forums = allFolders.filter { it.isRootFolder }
                val topics = allFolders.filter { !it.isRootFolder }
                var dirForums = dirForumDao.getAll().first()
                
                if (dirForums.isEmpty()) {
                    Log.d("WelcomeViewModel", "Directory empty, performing quick category sync")
                    try {
                        val categories = api.getCategories().categories
                        dirForumDao.insertAll(categories.map { cat ->
                            DirForum(
                                name = cat.name ?: "Unknown",
                                title = cat.name ?: "",
                                description = cat.sub,
                                type = "category",
                                category = cat.name,
                                subCategory = cat.sub
                            )
                        })
                        dirForums = dirForumDao.getAll().first()
                    } catch (e: Exception) {
                        Log.e("WelcomeViewModel", "Category sync failed", e)
                    }
                }

                Log.d("WelcomeViewModel", "Suggesting with ${forums.size} joined, ${dirForums.size} dir entries")

                // Try AI-based suggestion if API key is available
                if (BuildConfig.GEMINI_API_KEY.isNotEmpty()) {
                    suggestWithAI(body, forums, topics, dirForums)
                } else {
                    suggestWithKeywords(body, forums, topics, dirForums)
                }
            } catch (e: Exception) {
                Log.e("WelcomeViewModel", "Suggestion failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun suggestWithAI(body: String, forums: List<Folder>, topics: List<Folder>, dirForums: List<DirForum>) {
        Log.d("WelcomeViewModel", "Attempting AI suggestion")
        try {
            val forumInfo = dirForums.take(200).map { "${it.name}: ${it.description}" }.joinToString("\n")
            val prompt = """
                You are an assistant for CIX, a conferencing system. 
                Based on the following message content, suggest the most appropriate forum and topic to post it in.
                
                Available Forums and Descriptions:
                $forumInfo
                
                Message content:
                "$body"
                
                Respond ONLY with the forum name and the topic name (e.g., 'enquire_within' and 'general'), separated by a pipe character '|'.
            """.trimIndent()

            val response = generativeModel.generateContent(prompt)
            val result = response.text?.trim()?.split("|")
            
            if (result != null && result.size == 2) {
                val forumName = result[0].trim()
                val topicName = result[1].trim()
                
                var matchedForum = forums.find { it.name.equals(forumName, ignoreCase = true) }
                var matchedTopic: Folder? = null
                
                if (matchedForum != null) {
                    matchedTopic = topics.find { it.parentId == matchedForum.id && it.name.equals(topicName, ignoreCase = true) }
                        ?: topics.find { it.parentId == matchedForum.id } ?: fetchTopicForForum(matchedForum, topicName)
                } else {
                    val df = dirForums.find { it.name.equals(forumName, ignoreCase = true) }
                    if (df != null) {
                        matchedForum = Folder(id = HtmlUtils.calculateForumId(df.name), name = HtmlUtils.normalizeName(df.name), parentId = -1)
                        matchedTopic = fetchTopicForForum(matchedForum, topicName)
                    }
                }

                if (matchedForum != null && matchedTopic != null) {
                    _selectedForum.value = matchedForum
                    _selectedTopic.value = matchedTopic
                    _suggestedForumAndTopic.value = Pair(matchedForum, matchedTopic)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e("WelcomeViewModel", "AI error", e)
        }
        suggestWithKeywords(body, forums, topics, dirForums)
    }

    private suspend fun suggestWithKeywords(body: String, forums: List<Folder>, topics: List<Folder>, dirForums: List<DirForum>) {
        Log.d("WelcomeViewModel", "Performing keyword matching")
        val words = body.lowercase().split(Regex("[^a-zA-Z0-9]")).filter { it.length > 3 }.toSet()

        if (words.isEmpty()) {
            selectDefaultForum(forums, topics)
            return
        }

        var bestMatch: Pair<Folder, Folder>? = null
        var maxScore = 0

        // Check joined forums first
        for (topic in topics) {
            val forum = forums.find { it.id == topic.parentId } ?: continue
            val score = calculateScore(words, forum.name, topic.name, null)
            if (score > maxScore) {
                maxScore = score
                bestMatch = Pair(forum, topic)
            }
        }

        // Search directory if joined matches are weak
        if (maxScore < 10) {
            for (df in dirForums) {
                val score = calculateScore(words, df.name, null, df.description)
                if (score > maxScore) {
                    maxScore = score
                    val forum = Folder(id = HtmlUtils.calculateForumId(df.name), name = HtmlUtils.normalizeName(df.name), parentId = -1)
                    val topic = fetchTopicForForum(forum, null)
                    if (topic != null) {
                        bestMatch = Pair(forum, topic)
                    }
                }
            }
        }

        if (bestMatch != null) {
            Log.d("WelcomeViewModel", "Match found: ${bestMatch.first.name}/${bestMatch.second.name}")
            _selectedForum.value = bestMatch.first
            _selectedTopic.value = bestMatch.second
            _suggestedForumAndTopic.value = bestMatch
        } else {
            selectDefaultForum(forums, topics)
        }
    }

    private fun calculateScore(bodyWords: Set<String>, forumName: String, topicName: String?, description: String?): Int {
        var score = 0
        val fWords = forumName.lowercase().split(Regex("[^a-zA-Z0-9]")).toSet()
        val tWords = topicName?.lowercase()?.split(Regex("[^a-zA-Z0-9]"))?.toSet() ?: emptySet()
        val dWords = description?.lowercase()?.split(Regex("[^a-zA-Z0-9]"))?.toSet() ?: emptySet()
        for (word in bodyWords) {
            if (fWords.contains(word)) score += 5
            if (tWords.contains(word)) score += 10
            if (dWords.contains(word)) score += 3
        }
        return score
    }

    private suspend fun fetchTopicForForum(forum: Folder, preferredTopic: String?): Folder? {
        return try {
            val resultSet = api.getTopics(HtmlUtils.cixEncode(forum.name))
            val topics = resultSet.topics
            if (topics.isEmpty()) return null
            val matchedTopic = if (preferredTopic != null) {
                topics.find { it.name.equals(preferredTopic, ignoreCase = true) } ?: topics.first()
            } else {
                topics.find { it.name.equals("general", ignoreCase = true) } ?: topics.first()
            }
            Folder(id = HtmlUtils.calculateTopicId(forum.name, matchedTopic.name ?: ""), name = HtmlUtils.normalizeName(matchedTopic.name ?: ""), parentId = forum.id)
        } catch (e: Exception) {
            Log.e("WelcomeViewModel", "Topic fetch failed for ${forum.name}", e)
            null
        }
    }

    private suspend fun selectDefaultForum(forums: List<Folder>, topics: List<Folder>) {
        val enquireForum = forums.find { it.name.equals("enquire_within", ignoreCase = true) }
        if (enquireForum != null) {
            val generalTopic = topics.find { it.parentId == enquireForum.id && it.name.equals("general", ignoreCase = true) }
                ?: fetchTopicForForum(enquireForum, "general")
            
            if (generalTopic != null) {
                _selectedForum.value = enquireForum
                _selectedTopic.value = generalTopic
                _suggestedForumAndTopic.value = Pair(enquireForum, generalTopic)
            }
        }
    }

    fun clearSuggestion() {
        _suggestedForumAndTopic.value = null
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (folderDao.getAll().first().isEmpty()) refreshFolders()
                val response = api.getInterestingThreads(count = 20)
                val threads = response.messages ?: emptyList()
                val uniqueThreads = threads.distinctBy { 
                    "${HtmlUtils.normalizeName(it.forum)}/${HtmlUtils.normalizeName(it.topic)}/${it.effectiveRootId}" 
                }
                val memberForums = folderDao.getAll().first().filter { it.isRootFolder }.map { it.name.lowercase() }.toSet()
                val resolvedThreads = uniqueThreads.map { thread ->
                    async {
                        val forum = HtmlUtils.normalizeName(thread.forum ?: "")
                        val topic = HtmlUtils.normalizeName(thread.topic ?: "")
                        val rootId = thread.effectiveRootId
                        val topicId = HtmlUtils.calculateTopicId(forum, topic)
                        if (rootId == 0) return@async InterestingThreadUI(forum, topic, 0, HtmlUtils.decodeHtml(thread.author ?: ""), DateUtils.formatCixDate(thread.dateTime), HtmlUtils.decodeHtml(thread.body ?: ""), if (!thread.subject.isNullOrBlank()) HtmlUtils.decodeHtml(thread.subject!!) else null)
                        var cachedRoot = messageDao.getByRemoteId(rootId, topicId)
                        if (cachedRoot == null && memberForums.contains(forum.lowercase())) {
                            try {
                                val messageApi = api.getMessage(HtmlUtils.cixEncode(forum), HtmlUtils.cixEncode(topic), rootId)
                                val newMessage = CIXMessage(remoteId = messageApi.id, author = HtmlUtils.decodeHtml(messageApi.author ?: ""), body = HtmlUtils.decodeHtml(messageApi.body ?: ""), date = DateUtils.parseCixDate(messageApi.dateTime), commentId = messageApi.replyTo, rootId = messageApi.rootId, topicId = topicId, forumName = forum, topicName = topic, unread = true)
                                messageDao.insert(newMessage)
                                cachedRoot = newMessage
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                        if (cachedRoot != null) {
                            InterestingThreadUI(forum, topic, rootId, cachedRoot.author, DateUtils.formatDateTime(cachedRoot.date), cachedRoot.body, if (!thread.subject.isNullOrBlank()) HtmlUtils.decodeHtml(thread.subject!!) else null, true)
                        } else {
                            InterestingThreadUI(forum, topic, rootId, HtmlUtils.decodeHtml(thread.author ?: ""), DateUtils.formatCixDate(thread.dateTime), HtmlUtils.decodeHtml(thread.body ?: ""), if (!thread.subject.isNullOrBlank()) HtmlUtils.decodeHtml(thread.subject!!) else null, false)
                        }
                    }
                }.awaitAll()
                _interestingThreads.value = resolvedThreads
            } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false }
        }
    }

    suspend fun joinForumIfNeeded(forumName: String): Boolean {
        val isMember = folderDao.getAll().first().any { it.isRootFolder && it.name.equals(forumName, ignoreCase = true) }
        if (!isMember) {
            return try {
                val response = api.joinForum(HtmlUtils.cixEncode(forumName))
                if (extractStringFromXml(response.string()) == "Success") {
                    refreshFolders()
                    true
                } else false
            } catch (e: Exception) { e.printStackTrace(); false }
        }
        return true
    }

    private suspend fun refreshFolders() {
        try {
            val resultSet = api.getForums()
            val folders = resultSet.forums.mapNotNull { row ->
                val name = row.name ?: return@mapNotNull null
                val normalizedName = HtmlUtils.normalizeName(name)
                Folder(id = HtmlUtils.calculateForumId(normalizedName), name = normalizedName, parentId = -1, unread = row.unread?.toIntOrNull() ?: 0, unreadPriority = row.priority?.toIntOrNull() ?: 0)
            }
            folderDao.insertAll(folders)
        } catch (e: Exception) { e.printStackTrace() }
    }

    suspend fun getFirstUnreadMessage(): CIXMessage? = messageDao.getFirstUnreadMessage(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)

    private fun extractStringFromXml(xml: String): String {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.TEXT) return parser.text
                eventType = parser.next()
            }
            xml
        } catch (e: Exception) { xml }
    }

    suspend fun postMessage(context: Context, forum: String, topic: String, body: String, attachmentUri: Uri?, attachmentName: String?): Boolean {
        _isLoading.value = true
        return try {
            val attachments = if (attachmentUri != null && attachmentName != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(attachmentUri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (bytes != null) listOf(PostAttachment(data = Base64.encodeToString(bytes, Base64.NO_WRAP), filename = attachmentName)) else null
                } catch (e: Exception) { e.printStackTrace(); null }
            } else null
            joinForumIfNeeded(forum)
            val author = NetworkClient.getUsername()
            val messageId = messageRepository.postMessage(forum, topic, body, 0, author, attachments)
            if (messageId > 0) draftDao.deleteDraftForContext(forum, topic, 0)
            messageId > 0
        } catch (e: Exception) { e.printStackTrace(); false } finally { _isLoading.value = false }
    }

    fun saveDraft(body: String) {
        val forum = _selectedForum.value?.name ?: return
        val topic = _selectedTopic.value?.name ?: return
        viewModelScope.launch { draftDao.insertDraft(Draft(forumName = forum, topicName = topic, replyToId = 0, body = body)) }
    }

    suspend fun getDraftForContext(forum: String, topic: String): Draft? = draftDao.getDraft(forum, topic, 0)
    override fun showProfile(user: String) = profileDelegate.showProfile(viewModelScope, user)
    override fun dismissProfile() = profileDelegate.dismissProfile()
}

class WelcomeViewModelFactory(private val api: CixApi, private val messageDao: MessageDao, private val folderDao: FolderDao, private val dirForumDao: DirForumDao, private val cachedProfileDao: CachedProfileDao, private val draftDao: DraftDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WelcomeViewModel::class.java)) {
            val messageRepository = MessageRepository(api, messageDao)
            return WelcomeViewModel(api, messageDao, messageRepository, folderDao, dirForumDao, cachedProfileDao, draftDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
