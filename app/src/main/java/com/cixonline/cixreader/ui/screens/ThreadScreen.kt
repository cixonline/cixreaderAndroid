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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.cixonline.cixreader.BuildConfig
import com.cixonline.cixreader.R
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.models.Draft
import com.cixonline.cixreader.viewmodel.NextUnreadItem
import com.cixonline.cixreader.viewmodel.TopicViewModel
import com.cixonline.cixreader.utils.SettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

sealed class ThreadDisplayItem {
    data class Collapsed(val message: CIXMessage, val totalCount: Int, val unreadCount: Int) : ThreadDisplayItem()
    data class Expanded(val message: CIXMessage, val depth: Int) : ThreadDisplayItem()
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
    onProfileClick: (String) -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val scrollToMessageId by viewModel.scrollToMessageId.collectAsState()
    val context = LocalContext.current

    var expandedRootIds by remember { mutableStateOf(setOf<Int>()) }
    var selectedMessage by remember { mutableStateOf<CIXMessage?>(null) }
    var replyingToMessage by remember { mutableStateOf<CIXMessage?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showReplyPane by remember { mutableStateOf(false) }
    var initialDraft by remember { mutableStateOf<Draft?>(null) }
    var showNoMoreUnreadDialog by remember { mutableStateOf(false) }
    var showVersionDialog by remember { mutableStateOf(false) }
    var showDebugDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

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
                if (latest.unread != selected.unread || latest.body != selected.body) {
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

    if (showDebugDialog && selectedMessage != null) {
        AlertDialog(
            onDismissRequest = { showDebugDialog = false },
            title = { Text("Debug Cache Info (#${selectedMessage!!.remoteId})") },
            text = {
                Column {
                    Text("Database ID: ${selectedMessage!!.id}")
                    Text("Remote ID: ${selectedMessage!!.remoteId}")
                    Text("Author: ${selectedMessage!!.author}")
                    Text("Date: ${selectedMessage!!.date}")
                    Text("Unread (DB): ${selectedMessage!!.unread}")
                    Text("isActuallyUnread (UI): ${selectedMessage!!.isActuallyUnread}")
                    Text("Topic ID: ${selectedMessage!!.topicId}")
                    Text("Comment ID: ${selectedMessage!!.commentId}")
                    Text("Root ID: ${selectedMessage!!.rootId}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { viewModel.logRawXml() }) {
                        Text("Log All Topic XML")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDebugDialog = false }) {
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

    Scaffold(
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
                                text = { Text("Drafts") },
                                onClick = {
                                    showMenu = false
                                    onDraftsClick()
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
                                text = { Text("Version") },
                                onClick = {
                                    showMenu = false
                                    showVersionDialog = true
                                }
                            )
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
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Box(modifier = Modifier.fillMaxSize().imePadding()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Thread list: Shrinks further when replying to prioritize viewer and input
                    Box(modifier = Modifier.weight(if (showReplyPane) 0.35f else 1f)) {
                        CombinedThreadList(
                            messages = messages,
                            expandedRootIds = expandedRootIds,
                            selectedMessageId = selectedMessage?.remoteId,
                            onMessageClick = { msg ->
                                if (!showReplyPane) {
                                    selectedMessage = msg
                                }
                            },
                            onToggleExpand = { rootId ->
                                val expanding = !expandedRootIds.contains(rootId)
                                expandedRootIds = if (expanding) {
                                    expandedRootIds + rootId
                                } else {
                                    expandedRootIds - rootId
                                }
                                if (expanding && !showReplyPane) {
                                    messages.find { it.remoteId == rootId }?.let { rootMsg ->
                                        selectedMessage = rootMsg
                                    }
                                }
                            },
                            listState = listState
                        )
                    }

                    if (selectedMessage != null) {
                        MessageActionBar(
                            message = selectedMessage!!,
                            currentUsername = currentUsername,
                            replyActive = showReplyPane,
                            onReplyClick = {
                                if (showReplyPane) {
                                    handleBackAction()
                                } else {
                                    replyingToMessage = selectedMessage
                                    showReplyPane = true
                                }
                            },
                            onNextUnreadClick = {
                                coroutineScope.launch {
                                    val nextItem = viewModel.findNextUnreadItem(selectedMessage?.remoteId)
                                    if (selectedMessage!!.isActuallyUnread) {
                                        viewModel.markAsRead(selectedMessage!!)
                                    }
                                    
                                    when (nextItem) {
                                        is NextUnreadItem.Message -> {
                                            selectedMessage = nextItem.message
                                            val rootId = findRootForMessage(nextItem.message, messages)
                                            expandedRootIds = expandedRootIds + rootId
                                            showReplyPane = false
                                            replyingToMessage = null
                                        }
                                        is NextUnreadItem.Topic -> {
                                            onNavigateToThread(nextItem.forum, nextItem.topic, nextItem.topicId, 0, 0)
                                        }
                                        is NextUnreadItem.Forum -> {
                                            // This case is handled via NextUnreadItem.Topic which also knows the forum
                                        }
                                        is NextUnreadItem.NoMoreUnread -> {
                                            showNoMoreUnreadDialog = true
                                        }
                                    }
                                }
                            },
                            onAuthorClick = { onProfileClick(selectedMessage!!.author) },
                            onWithdrawClick = { viewModel.withdrawMessage(selectedMessage!!) },
                            onDebugClick = { showDebugDialog = true }
                        )
                    } else {
                        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    // Message viewer: Keeps significant portion of screen
                    Box(modifier = Modifier.weight(if (showReplyPane) 0.45f else 1f)) {
                        if (isLoading && messages.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Fetching messages...", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else if (selectedMessage != null) {
                            val parentMessage = remember(selectedMessage, messages) {
                                messages.find { it.remoteId == selectedMessage?.commentId }
                            }
                            MessageViewer(
                                message = selectedMessage!!,
                                parentMessage = parentMessage,
                                onParentClick = { parent ->
                                    if (!showReplyPane) {
                                        selectedMessage = parent
                                        val rootId = findRootForMessage(parent, messages)
                                        expandedRootIds = expandedRootIds + rootId
                                    }
                                },
                                onNavigateToThread = onNavigateToThread
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Select a thread to start reading",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }

                    // Reply pane: Compact 3-line input with buttons snug against keyboard
                    if (showReplyPane && replyingToMessage != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(), // Let it be compact
                            tonalElevation = 4.dp,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        ) {
                            ReplyPane(
                                replyTo = replyingToMessage!!,
                                text = replyText,
                                onTextChange = { replyText = it },
                                attachmentUri = replyAttachmentUri,
                                onAttachmentUriChange = { replyAttachmentUri = it },
                                attachmentName = replyAttachmentName,
                                onAttachmentNameChange = { replyAttachmentName = it },
                                onCancel = handleBackAction,
                                onPost = { body, uri, name ->
                                    coroutineScope.launch {
                                        if (viewModel.postReply(context, replyingToMessage!!.remoteId, body, uri, name)) {
                                            showReplyPane = false
                                            replyingToMessage = null
                                        }
                                    }
                                },
                                onSaveDraft = { body, uri, name ->
                                    viewModel.saveDraft(replyingToMessage!!.remoteId, body, uri, name)
                                }
                            )
                        }
                    }
                }

                if (isLoading && messages.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    if (showVersionDialog) {
        AlertDialog(
            onDismissRequest = { showVersionDialog = false },
            title = { Text("App Information") },
            text = {
                Column {
                    Text("Version: ${BuildConfig.VERSION_NAME}")
                    Text("Build Date: ${BuildConfig.BUILD_TIME}")
                }
            },
            confirmButton = {
                TextButton(onClick = { showVersionDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CombinedThreadList(
    messages: List<CIXMessage>,
    expandedRootIds: Set<Int>,
    selectedMessageId: Int?,
    onMessageClick: (CIXMessage) -> Unit,
    onToggleExpand: (Int) -> Unit,
    listState: LazyListState
) {
    val displayItems = remember(messages, expandedRootIds) {
        if (messages.isEmpty()) return@remember emptyList()
        val result = mutableListOf<ThreadDisplayItem>()
        val messageIds = messages.map { it.remoteId }.toSet()
        val roots = messages.filter { it.commentId == 0 || !messageIds.contains(it.commentId) }
            .sortedByDescending { it.date }

        roots.forEach { root ->
            val tree = buildThreadTree(messages, root.remoteId)
            val unreadCount = tree.count { it.first.isActuallyUnread }

            if (expandedRootIds.contains(root.remoteId)) {
                tree.forEach { (msg, depth) ->
                    result.add(ThreadDisplayItem.Expanded(msg, depth))
                }
            } else {
                result.add(ThreadDisplayItem.Collapsed(root, tree.size, unreadCount))
            }
        }
        result
    }

    LaunchedEffect(selectedMessageId, displayItems) {
        if (selectedMessageId != null) {
            val index = displayItems.indexOfFirst {
                when (it) {
                    is ThreadDisplayItem.Collapsed -> it.message.remoteId == selectedMessageId
                    is ThreadDisplayItem.Expanded -> it.message.remoteId == selectedMessageId
                }
            }
            if (index != -1) {
                delay(100)
                val layoutInfo = listState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height
                if (viewportHeight > 0) {
                    val visibleItem = layoutInfo.visibleItemsInfo.find { it.index == index }
                    val itemSize = visibleItem?.size ?: 64
                    val offset = (viewportHeight - itemSize) / 2
                    listState.animateScrollToItem(index, -offset)
                } else {
                    listState.animateScrollToItem(index)
                }
            }
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(displayItems, key = { item ->
            when(item) {
                is ThreadDisplayItem.Collapsed -> "collapsed-${item.message.remoteId}"
                is ThreadDisplayItem.Expanded -> "msg-${item.message.remoteId}"
            }
        }) { item ->
            when (item) {
                is ThreadDisplayItem.Collapsed -> {
                    ThreadItem(
                        message = item.message,
                        totalMessages = item.totalCount,
                        unreadCount = item.unreadCount,
                        onClick = { onToggleExpand(item.message.remoteId) }
                    )
                    HorizontalDivider()
                }
                is ThreadDisplayItem.Expanded -> {
                    ThreadRow(
                        message = item.message,
                        level = item.depth,
                        isSelected = item.message.remoteId == selectedMessageId,
                        onClick = { onMessageClick(item.message) },
                        onToggleExpand = if (item.depth == 0) { { onToggleExpand(item.message.remoteId) } } else null
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = (item.depth * 6 + 32).dp))
                }
            }
        }
    }
}

fun buildThreadTree(messages: List<CIXMessage>, startId: Int): List<Pair<CIXMessage, Int>> {
    val children = messages.groupBy { it.commentId }
    val result = mutableListOf<Pair<CIXMessage, Int>>()
    fun walk(m: CIXMessage, depth: Int) {
        result.add(m to depth)
        children[m.remoteId]?.sortedBy { it.date }?.forEach { walk(it, depth + 1) }
    }
    val startMsg = messages.find { it.remoteId == startId }
    if (startMsg != null) walk(startMsg, 0)
    return result
}

@Composable
fun ThreadItem(
    message: CIXMessage,
    totalMessages: Int,
    unreadCount: Int,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClick, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            val summary = remember(message) {
                val text = if (!message.subject.isNullOrBlank()) message.subject else message.body
                text.take(100).replace("\r\n", " ").replace("\r", " ").replace("\n", " ")
            }
            Text(
                text = summary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$unreadCount/$totalMessages",
                style = MaterialTheme.typography.labelSmall,
                color = if (unreadCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message.author,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
fun ThreadRow(
    message: CIXMessage, 
    level: Int, 
    isSelected: Boolean, 
    onClick: () -> Unit,
    onToggleExpand: (() -> Unit)? = null
) {
    Surface(
        color = if (isSelected) Color(0xFFD91B5C) else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (onToggleExpand != null) 4.dp else (level * 6 + 32).dp,
                top = 4.dp, bottom = 4.dp, end = 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onToggleExpand != null) {
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.ExpandLess,
                        contentDescription = "Collapse",
                        tint = if (isSelected) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = message.body.take(100).replace("\r\n", " ").replace("\r", " ").replace("\n", " "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    textAlign = if (isSelected) TextAlign.Center else TextAlign.Start
                ),
                fontWeight = if (message.isActuallyUnread) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message.author,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (message.isActuallyUnread) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
fun MessageActionBar(
    message: CIXMessage,
    currentUsername: String?,
    replyActive: Boolean,
    onReplyClick: () -> Unit,
    onNextUnreadClick: () -> Unit,
    onAuthorClick: () -> Unit,
    onWithdrawClick: () -> Unit,
    onDebugClick: () -> Unit = {}
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    val dateString = remember(message.date) { dateFormat.format(Date(message.date)) }
    var showWithdrawConfirm by remember { mutableStateOf(false) }

    if (showWithdrawConfirm) {
        AlertDialog(
            onDismissRequest = { showWithdrawConfirm = false },
            title = { Text("Withdraw Message") },
            text = { Text("Are you sure you want to withdraw this message? This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { 
                    showWithdrawConfirm = false
                    onWithdrawClick()
                }) {
                    Text("Withdraw", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWithdrawConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Surface(
        color = Color(0xFFD91B5C),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${message.remoteId}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = message.author,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFD0BCFF),
                        modifier = Modifier.clickable(onClick = onAuthorClick)
                    )
                }
                Text(
                    text = dateString,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
            Row {
                IconButton(onClick = onDebugClick) {
                    Icon(Icons.Default.BugReport, contentDescription = "Debug", tint = Color.White)
                }
                if (currentUsername != null && message.author.equals(currentUsername, ignoreCase = true) && !message.isWithdrawn()) {
                    IconButton(onClick = { showWithdrawConfirm = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Withdraw",
                            tint = Color.White
                        )
                    }
                }
                IconButton(onClick = onReplyClick) {
                    Icon(
                        if (replyActive) Icons.Default.Close else Icons.AutoMirrored.Filled.Reply,
                        contentDescription = "Reply",
                        tint = if (replyActive) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
                if (!replyActive) {
                    IconButton(onClick = onNextUnreadClick) {
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next Unread")
                    }
                }
            }
        }
    }
}

@Composable
fun MessageViewer(
    message: CIXMessage,
    parentMessage: CIXMessage? = null,
    onParentClick: (CIXMessage) -> Unit = {},
    onNavigateToThread: (forum: String, topic: String, topicId: Int, rootId: Int, msgId: Int) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val normalizedBody = remember(message.body) {
        message.body.replace("\r\n", "\n").replace("\r", "\n")
    }

    val chunks = remember(normalizedBody) {
        val urlPattern = Regex("https?://[\\w:#@%/;$()~_?+\\-=.&!*]+")
        val result = mutableListOf<MessageChunk>()
        var lastIndex = 0
        
        urlPattern.findAll(normalizedBody).forEach { match ->
            if (match.range.first > lastIndex) {
                result.add(MessageChunk.Text(normalizedBody.substring(lastIndex, match.range.first)))
            }
            val url = match.value
            val extension = url.substringAfterLast(".").lowercase()
            if (listOf("jpg", "jpeg", "png", "gif", "webp").contains(extension)) {
                result.add(MessageChunk.Image(url))
            } else {
                result.add(MessageChunk.Link(url))
            }
            lastIndex = match.range.last + 1
        }
        
        if (lastIndex < normalizedBody.length) {
            result.add(MessageChunk.Text(normalizedBody.substring(lastIndex)))
        }
        result
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (parentMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onParentClick(parentMessage) }
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SubdirectoryArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "In reply to ${parentMessage.author} (#${parentMessage.remoteId})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = parentMessage.body.take(200).replace("\r\n", " ").replace("\r", " ").replace("\n", " ") + if (parentMessage.body.length > 200) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        chunks.forEach { chunk ->
            when (chunk) {
                is MessageChunk.Text -> {
                    val annotatedString = linkify(chunk.text, message.forumName, message.topicName)
                    ClickableText(
                        text = annotatedString,
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        onClick = { offset ->
                            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { uriHandler.openUri(it.item) }
                            annotatedString.getStringAnnotations(tag = "CIX_REF", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    val parts = annotation.item.split(":")
                                    onNavigateToThread(parts[0], parts[1], (parts[0] + parts[1]).hashCode(), 0, parts[2].toIntOrNull() ?: 0)
                                }
                        }
                    )
                }
                is MessageChunk.Link -> {
                    val annotatedString = buildAnnotatedString {
                        pushStringAnnotation(tag = "URL", annotation = chunk.url)
                        withStyle(style = SpanStyle(color = Color(0xFFD0BCFF), textDecoration = TextDecoration.Underline)) {
                            append(chunk.url)
                        }
                        pop()
                    }
                    ClickableText(
                        text = annotatedString,
                        style = MaterialTheme.typography.bodyMedium,
                        onClick = { uriHandler.openUri(chunk.url) }
                    )
                }
                is MessageChunk.Image -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Box(modifier = Modifier.width(150.dp).padding(end = 12.dp)) {
                            AsyncImage(
                                model = chunk.url,
                                contentDescription = "Inline image",
                                modifier = Modifier.fillMaxWidth().wrapContentHeight().clickable { uriHandler.openUri(chunk.url) },
                                contentScale = ContentScale.FillWidth,
                                placeholder = painterResource(R.drawable.cix_logo),
                                error = painterResource(android.R.drawable.stat_notify_error)
                            )
                        }
                        // Note: Compose doesn't support true text-wrap around images in a single text layout easily.
                        // This uses a Row/Column approach which flows text next to the image for that chunk.
                    }
                }
            }
        }

        val urls = remember(normalizedBody) { extractUrls(normalizedBody) }
        urls.filter { url -> 
            val ext = url.substringAfterLast(".").lowercase()
            !listOf("jpg", "jpeg", "png", "gif", "webp").contains(ext)
        }.forEach { url ->
            Spacer(modifier = Modifier.height(16.dp))
            MediaPreview(url = url)
        }
    }
}

sealed class MessageChunk {
    data class Text(val text: String) : MessageChunk()
    data class Image(val url: String) : MessageChunk()
    data class Link(val url: String) : MessageChunk()
}

@Composable
fun MediaPreview(url: String) {
    val extension = url.substringAfterLast(".").lowercase()
    val isImage = remember(extension) { listOf("jpg", "jpeg", "png", "gif", "webp").contains(extension) }
    val isVideo = remember(extension) { listOf("mp4", "webm", "ogg").contains(extension) }
    val isAudio = remember(extension) { listOf("mp3", "wav", "m4a").contains(extension) }
    when {
        isImage -> Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            AsyncImage(
                model = url, contentDescription = "Image preview",
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                contentScale = ContentScale.FillWidth,
                placeholder = painterResource(R.drawable.cix_logo),
                error = painterResource(android.R.drawable.stat_notify_error)
            )
        }
        isVideo -> VideoPlayer(url = url)
        isAudio -> AudioPlayer(url = url)
    }
}

@Composable
fun VideoPlayer(url: String) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare() } }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(16/9f).padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.medium
    ) { AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } }, modifier = Modifier.fillMaxSize()) }
}

@Composable
fun AudioPlayer(url: String) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare() } }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.medium
    ) { AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true; showController(); controllerHideOnTouch = false } }, modifier = Modifier.fillMaxWidth().height(100.dp)) }
}

@Composable
fun ReplyPane(
    replyTo: CIXMessage,
    text: String,
    onTextChange: (String) -> Unit,
    attachmentUri: Uri?,
    onAttachmentUriChange: (Uri?) -> Unit,
    attachmentName: String?,
    onAttachmentNameChange: (String?) -> Unit,
    onCancel: () -> Unit,
    onPost: (String, Uri?, String?) -> Unit,
    onSaveDraft: (String, Uri?, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var isListening by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val speechRecognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    val recognitionListener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { isListening = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) { isListening = false }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val newText = matches[0]
                    onTextChange(if (text.isBlank()) newText else "$text $newText")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    DisposableEffect(Unit) {
        speechRecognizer.setRecognitionListener(recognitionListener)
        onDispose { speechRecognizer.destroy() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isListening = true
            speechRecognizer.startListening(speechRecognizerIntent)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        onAttachmentUriChange(uri)
        onAttachmentNameChange(uri?.let { u ->
            context.contentResolver.query(u, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            }
        })
    }

    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempPhotoUri != null) {
            onAttachmentUriChange(tempPhotoUri)
            onAttachmentNameChange("camera_photo_${System.currentTimeMillis()}.jpg")
        }
    }

    var showAttachmentSourceDialog by remember { mutableStateOf(false) }

    if (showAttachmentSourceDialog) {
        AlertDialog(
            onDismissRequest = { showAttachmentSourceDialog = false },
            title = { Text("Add Attachment") },
            text = { Text("Choose a source for your attachment") },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showAttachmentSourceDialog = false
                            val uri = createTempImageUri(context)
                            tempPhotoUri = uri
                            cameraLauncher.launch(uri)
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Camera")
                        }
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showAttachmentSourceDialog = false
                            fileLauncher.launch("*/*")
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AttachFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Files")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAttachmentSourceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = text, 
                onValueChange = onTextChange, 
                modifier = Modifier.fillMaxWidth().height(110.dp), // Approx 3 lines
                placeholder = { Text(if (isListening) "Listening..." else "Type your message here...") }, 
                textStyle = MaterialTheme.typography.bodyMedium, 
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween, 
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Reply to ${replyTo.author} (#${replyTo.remoteId})", 
                    style = MaterialTheme.typography.labelSmall, 
                    color = MaterialTheme.colorScheme.primary, 
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { 
                        if (isListening) {
                            speechRecognizer.stopListening()
                            isListening = false
                        } else {
                            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Icon(
                            if (isListening) Icons.Default.MicOff else Icons.Default.Mic, 
                            contentDescription = "Dictate",
                            tint = if (isListening) Color.Red else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { showAttachmentSourceDialog = true }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Add Attachment", tint = if (attachmentUri != null) Color(0xFFD91B5C) else LocalContentColor.current)
                    }
                    if (attachmentName != null) {
                        Text(text = attachmentName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 60.dp))
                        IconButton(onClick = {
                            onAttachmentUriChange(null)
                            onAttachmentNameChange(null)
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove attachment", modifier = Modifier.size(16.dp))
                        }
                    }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = { onPost(text, attachmentUri, attachmentName) }, enabled = text.isNotBlank()) { Text("Post") }
                }
            }
        }
    }
}

private fun createTempImageUri(context: Context): Uri {
    val tempFile = File.createTempFile("cix_upload_", ".jpg", context.cacheDir).apply {
        createNewFile()
        deleteOnExit()
    }
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        tempFile
    )
}

fun extractUrls(text: String): List<String> {
    val urlPattern = Regex("https?://[\\w:#@%/;$()~_?+\\-=.&!*]+")
    return urlPattern.findAll(text).map { it.value }.toList()
}

fun linkify(text: String, currentForum: String = "", currentTopic: String = ""): AnnotatedString {
    return buildAnnotatedString {
        val urlPattern = Regex("https?://[\\w:#@%/;$()~_?+\\-=.&!*]+")
        val cixRefFullPattern = Regex("cix:([\\w+]+)/([\\w+]+):(\\d+)")
        val cixRefShortPattern = Regex("cix:(\\d+)")
        var lastIndex = 0
        val allMatches = (urlPattern.findAll(text).map { it to "URL" } + cixRefFullPattern.findAll(text).map { it to "CIX_FULL" } + cixRefShortPattern.findAll(text).map { it to "CIX_SHORT" }).sortedBy { it.first.range.first }
        allMatches.forEach { (match, type) ->
            if (match.range.first >= lastIndex) {
                append(text.substring(lastIndex, match.range.first))
                val value = match.value
                when (type) {
                    "URL" -> { pushStringAnnotation(tag = "URL", annotation = value); withStyle(style = SpanStyle(color = Color(0xFFD0BCFF), textDecoration = TextDecoration.Underline)) { append(value) }; pop() }
                    "CIX_FULL" -> { pushStringAnnotation(tag = "CIX_REF", annotation = "${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}"); withStyle(style = SpanStyle(color = Color(0xFFD0BCFF), fontWeight = FontWeight.Bold)) { append(value) }; pop() }
                    "CIX_SHORT" -> { if (currentForum.isNotEmpty() && currentTopic.isNotEmpty()) { pushStringAnnotation(tag = "CIX_REF", annotation = "$currentForum:$currentTopic:${match.groupValues[1]}"); withStyle(style = SpanStyle(color = Color(0xFFD0BCFF), fontWeight = FontWeight.Bold)) { append(value) }; pop() } else { append(value) } }
                }
                lastIndex = match.range.last + 1
            }
        }
        append(text.substring(lastIndex))
    }
}
