package com.cixonline.cixreader.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Ignore

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey val id: Int,
    val name: String,
    val parentId: Int,
    val flags: Int = 0,
    @ColumnInfo(name = "folder_index") val index: Int = 0,
    val unread: Int = 0,
    val unreadPriority: Int = 0,
    val lastMessageDate: Long = 0,
    val deletePending: Boolean = false,
    val resignPending: Boolean = false,
    val markReadPending: Boolean = false
) {
    @Ignore
    var isModified: Boolean = false

    val isRootFolder: Boolean
        get() = parentId == -1

    fun hasFlag(flag: FolderFlags): Boolean {
        return (flags and flag.value) != 0
    }
}

enum class FolderFlags(val value: Int) {
    ReadOnly(1),
    Resigned(2),
    CannotResign(4),
    OwnerCommentsOnly(8),
    JoinFailed(16),
    Recent(32)
}
