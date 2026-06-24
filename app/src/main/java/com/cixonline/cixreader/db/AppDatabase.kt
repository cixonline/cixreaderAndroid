package com.cixonline.cixreader.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.cixonline.cixreader.models.*

@Database(entities = [Folder::class, CIXMessage::class, DirForum::class, CachedProfile::class, Draft::class, LogEntry::class, HistoryEntry::class], version = 14, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun messageDao(): MessageDao
    abstract fun dirForumDao(): DirForumDao
    abstract fun cachedProfileDao(): CachedProfileDao
    abstract fun draftDao(): DraftDao
    abstract fun logDao(): LogDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private var CURRENT_USER: String? = null

        fun getDatabase(context: Context, username: String? = null): AppDatabase {
            val dbName = if (username.isNullOrBlank()) "cix_database" else "cix_database_$username"
            
            return synchronized(this) {
                if (INSTANCE != null && (username == CURRENT_USER || (username == null && CURRENT_USER == null))) {
                    INSTANCE!!
                } else {
                    INSTANCE?.close()
                    val instance = Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        dbName
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    INSTANCE = instance
                    CURRENT_USER = username
                    instance
                }
            }
        }
    }
}
