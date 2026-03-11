package com.cixonline.cixreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.db.AppDatabase
import com.cixonline.cixreader.repository.ForumRepository
import com.cixonline.cixreader.repository.MessageRepository
import com.cixonline.cixreader.ui.AppNavHost
import com.cixonline.cixreader.ui.theme.CIXReaderTheme
import com.cixonline.cixreader.utils.SettingsManager
import com.cixonline.cixreader.utils.SyncManager
import com.cixonline.cixreader.utils.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var syncManager: SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val database = AppDatabase.getDatabase(this)
        val forumRepository = ForumRepository(NetworkClient.api, database.folderDao())
        val messageRepository = MessageRepository(NetworkClient.api, database.messageDao())
        settingsManager = SettingsManager(this)
        syncManager = SyncManager(this, settingsManager)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsManager.backgroundSyncFlow.collect { enabled ->
                    if (settingsManager.hasCredentials()) {
                        syncManager.handleSyncStateChange(enabled)
                    }
                }
            }
        }

        setContent {
            MainContent(
                settingsManager = settingsManager,
                database = database,
                forumRepository = forumRepository,
                messageRepository = messageRepository,
                syncManager = syncManager
            )
        }
    }
}

@Composable
fun MainContent(
    settingsManager: SettingsManager,
    database: AppDatabase,
    forumRepository: ForumRepository,
    messageRepository: MessageRepository,
    syncManager: SyncManager
) {
    val fontSizeMultiplier by settingsManager.fontSizeFlow.collectAsState(initial = settingsManager.getFontSize())
    val themeMode by settingsManager.themeFlow.collectAsState(initial = settingsManager.getThemeMode())
    val currentUsername by settingsManager.usernameFlow.collectAsState()
    
    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    
    CIXReaderTheme(
        darkTheme = useDarkTheme,
        fontSizeMultiplier = fontSizeMultiplier
    ) {
        val navController = rememberNavController()

        val startDestination = remember {
            val (user, pass) = settingsManager.getCredentials()
            if (user != null && pass != null) {
                NetworkClient.setCredentials(user, pass)
                syncManager.triggerImmediateSync()
                "welcome"
            } else {
                "login"
            }
        }

        AppNavHost(
            navController = navController,
            startDestination = startDestination,
            settingsManager = settingsManager,
            database = database,
            forumRepository = forumRepository,
            messageRepository = messageRepository,
            currentUsername = currentUsername
        )
    }
}
