package com.cixonline.cixreader.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dir_forums")
data class DirForum(
    @PrimaryKey val name: String,
    val title: String,
    val description: String? = null,
    val type: String? = null,
    val category: String? = null,
    val subCategory: String? = null,
    val recent: Int = 0
)
