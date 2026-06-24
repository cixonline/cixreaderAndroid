package com.cixonline.cixreader

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.db.AppDatabase
import com.cixonline.cixreader.repository.ForumRepository
import com.cixonline.cixreader.repository.HistoryRepository
import com.cixonline.cixreader.repository.LogRepository
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
        
        settingsManager = SettingsManager(this)
        syncManager = SyncManager(this, settingsManager)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsManager.backgroundSyncFlow.collect { enabled ->
                    if (settingsManager.isLoggedIn()) {
                        syncManager.handleSyncStateChange(enabled)
                    }
                }
            }
        }

        // Add observer to trigger sync when device wakes up / app comes to foreground
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Log.d("MainActivity", "App resumed, triggering immediate sync")
                if (settingsManager.isLoggedIn()) {
                    syncManager.triggerImmediateSync()
                }
            }
        })

        setContent {
            MainContent(
                settingsManager = settingsManager,
                syncManager = syncManager
            )
        }
    }
}

@Composable
fun MainContent(
    settingsManager: SettingsManager,
    syncManager: SyncManager
) {
    val context = LocalContext.current
    val fontSizeMultiplier by settingsManager.fontSizeFlow.collectAsState(initial = settingsManager.getFontSize())
    val themeMode by settingsManager.themeFlow.collectAsState(initial = settingsManager.getThemeMode())
    val currentUsername by settingsManager.usernameFlow.collectAsState()
    
    val database = remember(currentUsername) { 
        AppDatabase.getDatabase(context, currentUsername) 
    }
    
    val logRepository = remember(database) { LogRepository(database.logDao(), settingsManager) }
    val forumRepository = remember(database) { ForumRepository(NetworkClient.api, database.folderDao()) }
    val messageRepository = remember(database) { MessageRepository(NetworkClient.api, database.messageDao(), database.folderDao(), logRepository) }
    val historyRepository = remember(database) { HistoryRepository(database.historyDao()) }

    LaunchedEffect(logRepository) {
        logRepository.deleteOldLogs(48)
    }

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
            if (settingsManager.isLoggedIn()) {
                val (user, pass) = settingsManager.getCredentials()
                if (user != null && pass != null) {
                    NetworkClient.setCredentials(user, pass)
                    "welcome"
                } else {
                    "login"
                }
            } else {
                "login"
            }
        }

        AppNavHost(
            navController = navController,
            startDestination = startDestination,
            settingsManager = settingsManager,
            syncManager = syncManager,
            database = database,
            forumRepository = forumRepository,
            messageRepository = messageRepository,
            currentUsername = currentUsername,
            logRepository = logRepository,
            historyRepository = historyRepository
        )
    }
}
