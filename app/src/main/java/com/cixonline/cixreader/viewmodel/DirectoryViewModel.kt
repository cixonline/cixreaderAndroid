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

    private val _joinedForumNames = MutableStateFlow<Set<String>>(emptySet())
    val joinedForumNames: StateFlow<Set<String>> = _joinedForumNames

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _categories = MutableStateFlow<List<String>>(listOf("All"))
    val categories: StateFlow<List<String>> = _categories

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory

    val forums: StateFlow<List<DirListing>> = combine(
        dirForumDao.getAll(),
        _selectedCategory,
        _searchQuery
    ) { dbForums, category, query ->
        val listings = dbForums.map { it.toDirListing() }
        val filteredByCategory = if (category == "All") {
            listings
        } else {
            listings.filter { it.cat?.trim()?.equals(category, ignoreCase = true) == true }
        }

        if (query.isBlank()) {
            filteredByCategory
        } else {
            filteredByCategory.filter {
                it.forum?.contains(query, ignoreCase = true) == true ||
                it.title?.contains(query, ignoreCase = true) == true
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Observe joined forums
        viewModelScope.launch {
            folderDao.getAll().collectLatest { folders ->
                _joinedForumNames.value = folders
                    .filter { it.parentId == -1 }
                    .map { it.name }
                    .toSet()
            }
        }

        // Load categories from existing data in DB
        viewModelScope.launch {
            dirForumDao.getAll().take(1).collect { dbForums ->
                val cats = dbForums.mapNotNull { it.category?.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
                _categories.value = listOf("All") + cats
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
                val categoriesResult = api.getCategories().categories
                val catNames = categoriesResult.mapNotNull { it.name?.trim() }
                    .filter { it.isNotEmpty() && !it.contains("Un-Categorised", ignoreCase = true) }
                    .distinct()
                    .sorted()
                
                _categories.value = listOf("All") + catNames

                var firstCategory = true

                for (catName in catNames) {
                    val encodedCatName = HtmlUtils.cixCategoryEncode(catName)
                    
                    try {
                        val listings = api.getForumsInCategory(encodedCatName).forums
                        val decodedListings = listings.map { listing ->
                            listing.copy(
                                forum = HtmlUtils.decodeHtml(listing.forum).trim(),
                                title = HtmlUtils.decodeHtml(listing.title).trim(),
                                cat = HtmlUtils.decodeHtml(listing.cat).trim(),
                                sub = HtmlUtils.decodeHtml(listing.sub).trim()
                            )
                        }.filter { !it.forum.isNullOrEmpty() }
                        
                        if (firstCategory) {
                            dirForumDao.deleteAll()
                            firstCategory = false
                        }

                        // Room REPLACE strategy handles duplicates if multiple categories contain the same forum
                        dirForumDao.insertAll(decodedListings.map { it.toDirForum() })
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
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
            name = (this.forum ?: "").trim(),
            title = (this.title ?: "").trim(),
            description = (this.title ?: "").trim(),
            type = this.type,
            category = this.cat?.trim(),
            subCategory = this.sub?.trim(),
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

    fun selectCategory(category: String) {
        _selectedCategory.value = category
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