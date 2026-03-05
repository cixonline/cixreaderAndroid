package com.cixonline.cixreader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.db.AppDatabase
import com.cixonline.cixreader.repository.ForumRepository
import com.cixonline.cixreader.repository.MessageRepository
import com.cixonline.cixreader.ui.screens.*
import com.cixonline.cixreader.utils.SettingsManager
import com.cixonline.cixreader.viewmodel.*

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    settingsManager: SettingsManager,
    database: AppDatabase,
    forumRepository: ForumRepository,
    messageRepository: MessageRepository,
    currentUsername: String?
) {
    val actions = remember(navController, settingsManager) { 
        NavigationActions(navController, settingsManager) 
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
        composable("welcome") {
            val welcomeViewModel: WelcomeViewModel = viewModel(
                factory = WelcomeViewModelFactory(
                    api = NetworkClient.api, 
                    messageDao = database.messageDao(), 
                    folderDao = database.folderDao(),
                    dirForumDao = database.dirForumDao(),
                    cachedProfileDao = database.cachedProfileDao(),
                    draftDao = database.draftDao()
                )
            )
            WelcomeScreen(
                viewModel = welcomeViewModel,
                currentUsername = currentUsername,
                onExploreForums = actions.navigateToForums,
                onDirectoryClick = actions.navigateToDirectory,
                onThreadClick = actions.navigateToThread,
                onLogout = actions.onLogout,
                onSettingsClick = actions.onSettingsClick,
                onDraftsClick = actions.onDraftsClick,
                onProfileClick = actions.onProfileClick
            )
        }
        composable("forums") {
            val forumViewModel: ForumViewModel = viewModel(
                factory = ForumViewModelFactory(
                    api = NetworkClient.api,
                    repository = forumRepository,
                    cachedProfileDao = database.cachedProfileDao()
                )
            )
            ForumListScreen(
                viewModel = forumViewModel,
                currentUsername = currentUsername,
                onBackClick = actions.onBackClick,
                onTopicClick = { forumName, topicName, topicId ->
                    navController.navigate("thread/$forumName/$topicName/$topicId")
                },
                onLogout = actions.onLogout,
                onSettingsClick = actions.onSettingsClick,
                onProfileClick = actions.onProfileClick,
                onDraftsClick = actions.onDraftsClick
            )
        }
        composable("directory") {
            val directoryViewModel: DirectoryViewModel = viewModel(
                factory = DirectoryViewModelFactory(
                    NetworkClient.api, 
                    database.dirForumDao(), 
                    database.folderDao()
                )
            )
            DirectoryScreen(
                viewModel = directoryViewModel,
                currentUsername = currentUsername,
                onBackClick = actions.onBackClick,
                onForumJoined = { _ ->
                    navController.navigate("forums") {
                        popUpTo("welcome")
                    }
                },
                onLogout = actions.onLogout,
                onSettingsClick = actions.onSettingsClick,
                onProfileClick = actions.onProfileClick,
                onDraftsClick = actions.onDraftsClick
            )
        }
        composable("settings") {
            SettingsScreen(
                settingsManager = settingsManager,
                onBackClick = actions.onBackClick,
                onLogout = actions.onLogout,
                onDraftsClick = actions.onDraftsClick,
                onProfileClick = actions.onProfileClick
            )
        }
        composable("drafts") {
            val draftsViewModel: DraftsViewModel = viewModel(
                factory = DraftsViewModelFactory(
                    database.draftDao(),
                    messageRepository
                )
            )
            DraftsScreen(
                viewModel = draftsViewModel,
                onBackClick = actions.onBackClick,
                onLogout = actions.onLogout,
                onSettingsClick = actions.onSettingsClick,
                onProfileClick = actions.onProfileClick
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
                currentUsername = currentUsername,
                onBackClick = actions.onBackClick,
                onTopicClick = { topicName, topicId ->
                    navController.navigate("thread/$forumName/$topicName/$topicId")
                },
                onLogout = actions.onLogout,
                onSettingsClick = actions.onSettingsClick,
                onProfileClick = actions.onProfileClick,
                onDraftsClick = actions.onDraftsClick
            )
        }
        composable(
            route = "thread/{forumName}/{topicName}/{topicId}?rootId={rootId}&msgId={msgId}",
            arguments = listOf(
                navArgument("forumName") { type = NavType.StringType },
                navArgument("topicName") { type = NavType.StringType },
                navArgument("topicId") { type = NavType.IntType },
                navArgument("rootId") { 
                    type = NavType.IntType
                    defaultValue = 0
                },
                navArgument("msgId") { 
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { backStackEntry ->
            val forumNameArg = backStackEntry.arguments?.getString("forumName") ?: ""
            val topicNameArg = backStackEntry.arguments?.getString("topicName") ?: ""
            val topicIdArg = backStackEntry.arguments?.getInt("topicId") ?: 0
            val rootIdArg = backStackEntry.arguments?.getInt("rootId") ?: 0
            val msgIdArg = backStackEntry.arguments?.getInt("msgId") ?: 0
            
            val viewModel: TopicViewModel = viewModel(
                factory = TopicViewModelFactory(
                    api = NetworkClient.api,
                    repository = messageRepository, 
                    cachedProfileDao = database.cachedProfileDao(),
                    draftDao = database.draftDao(),
                    folderDao = database.folderDao(),
                    forumName = forumNameArg,
                    topicName = topicNameArg,
                    topicId = topicIdArg,
                    initialMessageId = msgIdArg,
                    initialRootId = rootIdArg
                )
            )
            ThreadScreen(
                viewModel = viewModel,
                currentUsername = currentUsername,
                onBackClick = actions.onBackClick,
                onLogout = actions.onLogout,
                onSettingsClick = actions.onSettingsClick,
                settingsManager = settingsManager,
                onNavigateToThread = actions.navigateToThread,
                onNavigateToDirectory = actions.navigateToDirectory,
                onDraftsClick = actions.onDraftsClick,
                onProfileClick = actions.onProfileClick
            )
        }
        composable(
            route = "profile/{username}",
            arguments = listOf(
                navArgument("username") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username") ?: ""
            val viewModel: ProfileViewModel = viewModel(
                factory = ProfileViewModelFactory(
                    api = NetworkClient.api,
                    cachedProfileDao = database.cachedProfileDao(),
                    username = username
                )
            )
            ProfileScreen(
                viewModel = viewModel,
                onBackClick = actions.onBackClick,
                onLogout = actions.onLogout,
                onSettingsClick = actions.onSettingsClick,
                onDraftsClick = actions.onDraftsClick
            )
        }
    }
}

class NavigationActions(private val navController: NavHostController, private val settingsManager: SettingsManager) {
    val onLogout: () -> Unit = {
        settingsManager.clearCredentials()
        navController.navigate("login") {
            popUpTo(0) { inclusive = true }
        }
    }
    
    val onSettingsClick: () -> Unit = {
        navController.navigate("settings")
    }

    val onDraftsClick: () -> Unit = {
        navController.navigate("drafts")
    }

    val onProfileClick: (String) -> Unit = { username ->
        navController.navigate("profile/$username")
    }
    
    val onBackClick: () -> Unit = {
        navController.popBackStack()
    }

    val navigateToForums: () -> Unit = {
        navController.navigate("forums")
    }

    val navigateToDirectory: () -> Unit = {
        navController.navigate("directory")
    }

    val navigateToThread: (String, String, Int, Int, Int) -> Unit = { forum, topic, topicId, rootId, msgId ->
        navController.navigate("thread/$forum/$topic/$topicId?rootId=$rootId&msgId=$msgId")
    }
}
