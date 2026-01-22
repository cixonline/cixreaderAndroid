package com.cixonline.cixreader.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dir_forums")
data class DirForum(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val title: String,
    val description: String?,
    val type: String?,
    val category: String?,
    val subCategory: String?,
    val recent: Int = 0
)
