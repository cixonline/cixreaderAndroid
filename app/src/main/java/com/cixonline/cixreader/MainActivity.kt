package com.cixonline.cixreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.db.AppDatabase
import com.cixonline.cixreader.repository.ForumRepository
import com.cixonline.cixreader.repository.MessageRepository
import com.cixonline.cixreader.ui.screens.DirectoryScreen
import com.cixonline.cixreader.ui.screens.ForumListScreen
import com.cixonline.cixreader.ui.screens.LoginScreen
import com.cixonline.cixreader.ui.screens.SettingsScreen
import com.cixonline.cixreader.ui.screens.ThreadScreen
import com.cixonline.cixreader.ui.screens.TopicListScreen
import com.cixonline.cixreader.ui.screens.WelcomeScreen
import com.cixonline.cixreader.ui.theme.CIXReaderTheme
import com.cixonline.cixreader.utils.SettingsManager
import com.cixonline.cixreader.viewmodel.DirectoryViewModel
import com.cixonline.cixreader.viewmodel.DirectoryViewModelFactory
import com.cixonline.cixreader.viewmodel.ForumViewModel
import com.cixonline.cixreader.viewmodel.ForumViewModelFactory
import com.cixonline.cixreader.viewmodel.LoginViewModel
import com.cixonline.cixreader.viewmodel.LoginViewModelFactory
import com.cixonline.cixreader.viewmodel.TopicListViewModel
import com.cixonline.cixreader.viewmodel.TopicListViewModelFactory
import com.cixonline.cixreader.viewmodel.TopicViewModel
import com.cixonline.cixreader.viewmodel.TopicViewModelFactory
import com.cixonline.cixreader.viewmodel.WelcomeViewModel
import com.cixonline.cixreader.viewmodel.WelcomeViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val database = AppDatabase.getDatabase(this)
        val forumRepository = ForumRepository(NetworkClient.api, database.folderDao())
        val messageRepository = MessageRepository(NetworkClient.api, database.messageDao())
        val settingsManager = SettingsManager(this)

        setContent {
            CIXReaderTheme {
                val navController = rememberNavController()
                
                val (savedUser, savedPass) = settingsManager.getCredentials()
                val startDestination = if (savedUser != null && savedPass != null) {
                    NetworkClient.setCredentials(savedUser, savedPass)
                    "welcome"
                } else {
                    "login"
                }

                val onLogout = {
                    settingsManager.clearCredentials()
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
                
                val onSettingsClick = {
                    navController.navigate("settings")
                }

                NavHost(navController = navController, startDestination = startDestination) {
                    composable("login") {
                        val loginViewModel: LoginViewModel = viewModel(
                            factory = LoginViewModelFactory(settingsManager)
                        )
                        LoginScreen(
                            viewModel = loginViewModel,
                            onLoginSuccess = {
                                navController.navigate("welcome") {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            settingsManager = settingsManager,
                            onBackClick = { navController.popBackStack() }
                        )
                    }
                    composable("welcome") {
                        val welcomeViewModel: WelcomeViewModel = viewModel(
                            factory = WelcomeViewModelFactory(NetworkClient.api, database.messageDao())
                        )
                        WelcomeScreen(
                            viewModel = welcomeViewModel,
                            onExploreForums = {
                                navController.navigate("forums")
                            },
                            onDirectoryClick = {
                                navController.navigate("directory")
                            },
                            onThreadClick = { forum, topic, topicId, rootId ->
                                navController.navigate("thread/$forum/$topic/$topicId?rootId=$rootId")
                            },
                            onLogout = onLogout,
                            onSettingsClick = onSettingsClick
                        )
                    }
                    composable("forums") {
                        val forumViewModel: ForumViewModel = viewModel(
                            factory = ForumViewModelFactory(forumRepository)
                        )
                        ForumListScreen(
                            viewModel = forumViewModel,
                            onBackClick = { navController.popBackStack() },
                            onForumClick = { forumName, forumId ->
                                navController.navigate("topics/$forumName/$forumId")
                            },
                            onLogout = onLogout,
                            onSettingsClick = onSettingsClick
                        )
                    }
                    composable("directory") {
                        val directoryViewModel: DirectoryViewModel = viewModel(
                            factory = DirectoryViewModelFactory(NetworkClient.api, database.dirForumDao(), database.folderDao())
                        )
                        DirectoryScreen(
                            viewModel = directoryViewModel,
                            onBackClick = { navController.popBackStack() },
                            onForumJoined = { forumName ->
                                navController.navigate("forums") {
                                    popUpTo("welcome") { inclusive = false }
                                }
                            },
                            onLogout = onLogout,
                            onSettingsClick = onSettingsClick
                        )
                    }
                    composable(
                        route = "topics/{forumName}/{forumId}",
                        arguments = listOf(
                            navArgument("forumName") { type = NavType.StringType },
                            navArgument("forumId") { type = NavType.IntType }
                        )
                    ) { backStackEntry ->
                        val forumName = backStackEntry.arguments?.getString("forumName") ?: ""
                        val forumId = backStackEntry.arguments?.getInt("forumId") ?: 0
                        val viewModel: TopicListViewModel = viewModel(
                            factory = TopicListViewModelFactory(forumRepository, forumName, forumId)
                        )
                        TopicListScreen(
                            viewModel = viewModel,
                            forumName = forumName,
                            onBackClick = { navController.popBackStack() },
                            onTopicClick = { topicName, topicId ->
                                navController.navigate("thread/$forumName/$topicName/$topicId")
                            },
                            onLogout = onLogout,
                            onSettingsClick = onSettingsClick
                        )
                    }
                    composable(
                        route = "thread/{forumName}/{topicName}/{topicId}?rootId={rootId}",
                        arguments = listOf(
                            navArgument("forumName") { type = NavType.StringType },
                            navArgument("topicName") { type = NavType.StringType },
                            navArgument("topicId") { type = NavType.IntType },
                            navArgument("rootId") { 
                                type = NavType.IntType
                                defaultValue = 0
                            }
                        )
                    ) { backStackEntry ->
                        val forumName = backStackEntry.arguments?.getString("forumName") ?: ""
                        val topicName = backStackEntry.arguments?.getString("topicName") ?: ""
                        val topicId = backStackEntry.arguments?.getInt("topicId") ?: 0
                        val rootId = backStackEntry.arguments?.getInt("rootId") ?: 0
                        
                        val viewModel: TopicViewModel = viewModel(
                            factory = TopicViewModelFactory(messageRepository, forumName, topicName, topicId, initialRootId = rootId)
                        )
                        ThreadScreen(
                            viewModel = viewModel,
                            onBackClick = { navController.popBackStack() },
                            onLogout = onLogout,
                            onSettingsClick = onSettingsClick,
                            settingsManager = settingsManager
                        )
                    }
                }
            }
        }
    }
}
