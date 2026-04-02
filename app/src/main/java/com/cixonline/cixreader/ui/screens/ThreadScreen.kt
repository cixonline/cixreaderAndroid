package com.cixonline.cixreader.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.cixonline.cixreader.BuildConfig
import com.cixonline.cixreader.R
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.models.Draft
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.viewmodel.NextUnreadItem
import com.cixonline.cixreader.viewmodel.TopicViewModel
import com.cixonline.cixreader.utils.SettingsManager
import com.cixonline.cixreader.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

sealed class ThreadDisplayItem {
    data class Collapsed(val message: CIXMessage, val totalCount: Int, val unreadCount: Int, val isSelected: Boolean) : ThreadDisplayItem()
    data class Expanded(val message: CIXMessage, val depth: Int, val isSelected: Boolean) : ThreadDisplayItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    viewModel: TopicViewModel,
    currentUsername: String?,
    onBackClick: () -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit,
    settingsManager: SettingsManager,
    onNavigateToThread: (forum: String, topic: String, topicId: Int, rootId: Int, msgId: Int) -> Unit,
    onNavigateToDirectory: () -> Unit,
    onDraftsClick: () -> Unit,
    onProfileClick: (String) -> Unit,
    onActivityLogClick: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isBackfilling by viewModel.isBackfilling.collectAsState()
    val error by viewModel.error.collectAsState()
    val scrollToMessageId by viewModel.scrollToMessageId.collectAsState()
    val debugModeEnabled by settingsManager.debugModeFlow.collectAsState()
    val context = LocalContext.current

    var expandedRootIds by remember { mutableStateOf(setOf<Int>()) }
    var selectedMessage by remember { mutableStateOf<CIXMessage?>(null) }
    var replyingToMessage by remember { mutableStateOf<CIXMessage?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showReplyPane by remember { mutableStateOf(false) }
    var showPostDialog by remember { mutableStateOf(false) }
    var initialDraft by remember { mutableStateOf<Draft?>(null) }
    var showNoMoreUnreadDialog by remember { mutableStateOf(false) }
    var showVersionDialog by remember { mutableStateOf(false) }
    var showDebugDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Lifted state for ReplyPane to handle back button confirmation
    var replyText by remember { mutableStateOf("") }
    var replyAttachmentUri by remember { mutableStateOf<Uri?>(null) }
    var replyAttachmentName by remember { mutableStateOf<String?>(null) }
    var showReplyCancelConfirm by remember { mutableStateOf(false) }

    fun findRootForMessage(msg: CIXMessage, allMsgs: List<CIXMessage>): Int {
        val msgMap = allMsgs.associateBy { it.remoteId }
        var current = msg
        while (current.commentId != 0 && msgMap.containsKey(current.commentId)) {
            current = msgMap[current.commentId]!!
        }
        return current.remoteId
    }

    // Initial message selection logic
    LaunchedEffect(messages) {
        if (messages.isNotEmpty() && selectedMessage == null) {
            val targetMsg = if (viewModel.initialMessageId != 0) {
                messages.find { it.remoteId == viewModel.initialMessageId }
            } else {
                when (val next = viewModel.findNextUnreadItem(null)) {
                    is NextUnreadItem.Message -> next.message
                    else -> messages.maxByOrNull { it.date } // Fallback to absolute latest message
                }
            }

            if (targetMsg != null) {
                selectedMessage = targetMsg
                val rootId = findRootForMessage(targetMsg, messages)
                expandedRootIds = expandedRootIds + rootId
            }
        }
    }

    // Keep selectedMessage in sync with the underlying list data
    LaunchedEffect(messages) {
        selectedMessage?.let { selected ->
            messages.find { it.remoteId == selected.remoteId }?.let { latest ->
                if (latest.isActuallyUnread || latest.body != selected.body) {
                    selectedMessage = latest
                }
            }
        }
    }

    LaunchedEffect(scrollToMessageId, messages) {
        if (scrollToMessageId != null && messages.isNotEmpty()) {
            val targetMsg = messages.find { it.remoteId == scrollToMessageId }
            if (targetMsg != null) {
                selectedMessage = targetMsg
                val rootId = findRootForMessage(targetMsg, messages)
                expandedRootIds = expandedRootIds + rootId
                viewModel.onScrollToMessageComplete()
            }
        }
    }

    LaunchedEffect(showReplyPane, replyingToMessage) {
        if (showReplyPane && replyingToMessage != null) {
            initialDraft = viewModel.getDraft(replyingToMessage!!.remoteId)
            replyText = initialDraft?.body ?: ""
            replyAttachmentUri = initialDraft?.attachmentUri?.let { Uri.parse(it) }
            replyAttachmentName = initialDraft?.attachmentName
        } else if (!showReplyPane) {
            replyText = ""
            replyAttachmentUri = null
            replyAttachmentName = null
            replyingToMessage = null
        }
    }

    val handleBackAction = {
        if (showReplyPane && (replyText.isNotBlank() || replyAttachmentUri != null)) {
            showReplyCancelConfirm = true
        } else if (showReplyPane) {
            showReplyPane = false
            replyingToMessage = null
        } else {
            onBackClick()
        }
    }

    BackHandler(enabled = showReplyPane) {
        handleBackAction()
    }

    if (showNoMoreUnreadDialog) {
        AlertDialog(
            onDismissRequest = { showNoMoreUnreadDialog = false },
            title = { Text("No More Unread Messages") },
            text = { Text("You have caught up with everything! Why not explore the Directory to find more interesting forums to join?") },
            confirmButton = {
                TextButton(onClick = { 
                    showNoMoreUnreadDialog = false
                    onNavigateToDirectory()
                }) {
                    Text("Go to Directory")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoMoreUnreadDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showReplyCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showReplyCancelConfirm = false },
            title = { Text("Cancel Message") },
            text = { Text("Are you sure you want to discard this message? You can also save it as a draft.") },
            confirmButton = {
                TextButton(onClick = { 
                    showReplyCancelConfirm = false
                    showReplyPane = false 
                    replyingToMessage = null
                }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { 
                        if (replyingToMessage != null) {
                            viewModel.saveDraft(replyingToMessage!!.remoteId, replyText, replyAttachmentUri, replyAttachmentName)
                        }
                        showReplyCancelConfirm = false
                        showReplyPane = false
                        replyingToMessage = null
                    }) {
                        Text("Save Draft")
                    }
                    TextButton(onClick = { showReplyCancelConfirm = false }) {
                        Text("Keep Editing")
                    }
                }
            }
        )
    }

    if (showDebugDialog && selectedMessage != null) {
        DebugMessageDialog(message = selectedMessage!!, onDismiss = { showDebugDialog = false })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.cix_logo),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "${viewModel.forumName} / ${viewModel.topicName}",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFD91B5C),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = handleBackAction) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedMessage != null && debugModeEnabled) {
                        IconButton(onClick = { showDebugDialog = true }) {
                            Icon(Icons.Default.BugReport, contentDescription = "Debug Cache")
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Profile") },
                                onClick = {
                                    showMenu = false
                                    currentUsername?.let { onProfileClick(it) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showMenu = false
                                    onSettingsClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Activity Log") },
                                onClick = {
                                    showMenu = false
                                    onActivityLogClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Drafts") },
                                onClick = {
                                    showMenu = false
                                    onDraftsClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("About") },
                                onClick = {
                                    showMenu = false
                                    showVersionDialog = true
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Logout") },
                                onClick = {
                                    showMenu = false
                                    onLogout()
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        // ... rest of the file
    }
}
