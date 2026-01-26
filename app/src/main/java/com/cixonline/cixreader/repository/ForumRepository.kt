package com.cixonline.cixreader.repository

import com.cixonline.cixreader.api.CixApi
import com.cixonline.cixreader.api.TopicResult
import com.cixonline.cixreader.db.FolderDao
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.utils.HtmlUtils
import kotlinx.coroutines.flow.Flow

class ForumRepository(
    private val api: CixApi,
    private val folderDao: FolderDao
) {
    val allFolders: Flow<List<Folder>> = folderDao.getAll()

    fun allFoldersWithCutoff(cutoff: Long): Flow<List<Folder>> {
        return folderDao.getAllWithDynamicUnread(cutoff)
    }

    suspend fun refreshForums() {
        try {
            val resultSet = api.getForums()
            val folders = resultSet.forums.mapNotNull { row ->
                val name = row.name ?: return@mapNotNull null
                Folder(
                    id = name.hashCode(),
                    name = HtmlUtils.decodeHtml(name),
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

    suspend fun refreshTopics(forumName: String, forumId: Int) {
        try {
            // Encode forum name for the API call path segment
            val encodedForumName = HtmlUtils.urlEncode(HtmlUtils.decodeHtml(forumName))
            val resultSet = api.getUserForumTopics(encodedForumName)
            val topics = resultSet.userTopics.mapNotNull { result ->
                val name = result.name ?: return@mapNotNull null
                Folder(
                    id = (forumName + name).hashCode(),
                    name = HtmlUtils.decodeHtml(name),
                    parentId = forumId,
                    unread = result.unread?.toIntOrNull() ?: 0
                )
            }
            folderDao.insertAll(topics)
            
            // Note: Forum-level unread sync removed here as FolderDao.getAllWithDynamicUnread 
            // calculates it on the fly from message table with cutoff.
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getTopics(forumId: Int): Flow<List<Folder>> {
        return folderDao.getChildren(forumId)
    }
}
