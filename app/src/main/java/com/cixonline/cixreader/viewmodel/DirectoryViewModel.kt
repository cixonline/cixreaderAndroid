package com.cixonline.cixreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.DirListing
import com.cixonline.cixreader.db.DirForumDao
import com.cixonline.cixreader.db.FolderDao
import com.cixonline.cixreader.models.DirForum
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class DirectoryViewModel(
    private val api: CixApi,
    private val dirForumDao: DirForumDao,
    private val folderDao: FolderDao
) : ViewModel() {

    private val _forums = MutableStateFlow<List<DirListing>>(emptyList())
    val forums: StateFlow<List<DirListing>> = _forums

    private val _joinedForumNames = MutableStateFlow<Set<String>>(emptySet())
    val joinedForumNames: StateFlow<Set<String>> = _joinedForumNames

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    init {
        // Observe joined forums
        viewModelScope.launch {
            folderDao.getAll().collectLatest { folders ->
                // Filter only root folders (forums) and get their names
                _joinedForumNames.value = folders
                    .filter { it.parentId == -1 }
                    .map { it.name }
                    .toSet()
            }
        }

        // Observe database and load from it first
        viewModelScope.launch {
            dirForumDao.getAll().collectLatest { dbForums ->
                if (dbForums.isNotEmpty()) {
                    _forums.value = dbForums.map { it.toDirListing() }
                }
            }
        }
        
        refreshFromApi()
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

    private fun refreshFromApi() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val categories = api.getCategories().categories
                val allForums = mutableListOf<DirListing>()
                for (category in categories) {
                    val catName = category.name ?: continue
                    val encodedCatName = HtmlUtils.cixCategoryEncode(catName)
                    
                    try {
                        val listings = api.getForumsInCategory(encodedCatName).forums
                        val decodedListings = listings.map { listing ->
                            listing.copy(
                                forum = HtmlUtils.decodeHtml(listing.forum),
                                title = HtmlUtils.decodeHtml(listing.title),
                                cat = HtmlUtils.decodeHtml(listing.cat),
                                sub = HtmlUtils.decodeHtml(listing.sub)
                            )
                        }
                        allForums.addAll(decodedListings)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                val distinctForums = allForums.distinctBy { it.forum }
                if (distinctForums.isNotEmpty()) {
                    dirForumDao.deleteAll()
                    dirForumDao.insertAll(distinctForums.map { it.toDirForum() })
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun DirListing.toDirForum(): DirForum {
        return DirForum(
            name = this.forum ?: "",
            title = this.title ?: "",
            description = null,
            type = this.type,
            category = this.cat,
            subCategory = this.sub,
            recent = this.recent
        )
    }

    private fun DirForum.toDirListing(): DirListing {
        return DirListing(
            forum = this.name,
            title = this.title,
            recent = this.recent,
            cat = this.category,
            sub = this.subCategory,
            type = this.type
        )
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun joinForum(forumName: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val encodedForumName = HtmlUtils.urlEncode(HtmlUtils.decodeHtml(forumName))
                val responseBody = withContext(Dispatchers.IO) {
                    api.joinForum(encodedForumName)
                }
                val rawResponse = withContext(Dispatchers.IO) {
                    responseBody.string()
                }
                val result = extractStringFromXml(rawResponse)
                onResult(result == "Success")
            } catch (e: Exception) {
                e.printStackTrace()
                onResult(false)
            }
        }
    }
}

class DirectoryViewModelFactory(
    private val api: CixApi,
    private val dirForumDao: DirForumDao,
    private val folderDao: FolderDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DirectoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DirectoryViewModel(api, dirForumDao, folderDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
