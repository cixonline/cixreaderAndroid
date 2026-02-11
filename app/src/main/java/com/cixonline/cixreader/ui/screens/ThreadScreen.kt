package com.cixonline.cixreader.ui.screens

import android.net.Uri
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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.cixonline.cixreader.R
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.viewmodel.TopicViewModel
import com.cixonline.cixreader.utils.SettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    onNavigateToThread: (forum: String, topic: String, topicId: Int, rootId: Int, msgId: Int) -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val selectedResume by viewModel.selectedResume.collectAsState()
    val selectedMugshotUrl by viewModel.selectedMugshotUrl.collectAsState()
    val scrollToMessageId by viewModel.scrollToMessageId.collectAsState()
    val context = LocalContext.current

    // State for expanded roots (by remoteId)
    var expandedRootIds by remember { mutableStateOf(setOf<Int>()) }
    
    var selectedMessage by remember { mutableStateOf<CIXMessage?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showReplyPane by remember { mutableStateOf(false) }
    var replyInitialText by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Function to find which root a message belongs to
    fun findRootForMessage(msg: CIXMessage, allMsgs: List<CIXMessage>): Int {
        val msgMap = allMsgs.associateBy { it.remoteId }
        var current = msg
        while (current.commentId != 0 && msgMap.containsKey(current.commentId)) {
            current = msgMap[current.commentId]!!
        }
        return current.remoteId
    }

    LaunchedEffect(messages) {
        if (messages.isNotEmpty() && selectedMessage == null) {
            val targetMsg = if (viewModel.initialMessageId != 0) {
                messages.find { it.remoteId == viewModel.initialMessageId }
            } else {
                viewModel.findNextUnread(null) ?: messages.filter { it.commentId == 0 }.maxByOrNull { it.date }
            }

            if (targetMsg != null) {
                selectedMessage = targetMsg
                val rootId = findRootForMessage(targetMsg, messages)
                expandedRootIds = expandedRootIds + rootId
            }
        }
    }

    // Effect to handle jump-to-position when scrollToMessageId changes
    LaunchedEffect(scrollToMessageId, messages) {
        if (scrollToMessageId != null && messages.isNotEmpty()) {
            val targetMsg = messages.find { it.remoteId == scrollToMessageId }
            if (targetMsg != null) {
                selectedMessage = targetMsg
                val rootId = findRootForMessage(targetMsg, messages)
                expandedRootIds = expandedRootIds + rootId
                // Reset the scroll state after jumping
                viewModel.onScrollToMessageComplete()
            }
        }
    }

    LaunchedEffect(showReplyPane, selectedMessage) {
        if (showReplyPane && selectedMessage != null) {
            replyInitialText = viewModel.getDraft(selectedMessage!!.remoteId)?.body ?: ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.cix_logo),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
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
                    IconButton(onClick = onBackClick) {
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
                                    currentUsername?.let { viewModel.showProfile(it) }
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
                    Box(modifier = Modifier.weight(if (showReplyPane) 0.5f else 1f)) {
                        CombinedThreadList(
                            messages = messages,
                            expandedRootIds = expandedRootIds,
                            selectedMessageId = selectedMessage?.remoteId,
                            onMessageClick = { msg ->
                                selectedMessage = msg
                            },
                            onToggleExpand = { rootId ->
                                val expanding = !expandedRootIds.contains(rootId)
                                expandedRootIds = if (expanding) {
                                    expandedRootIds + rootId
                                } else {
                                    expandedRootIds - rootId
                                }
                                
                                if (expanding) {
                                    // Focus on the root message when expanding
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
                            replyActive = showReplyPane,
                            onReplyClick = { showReplyPane = !showReplyPane },
                            onNextUnreadClick = {
                                val next = viewModel.findNextUnread(selectedMessage?.remoteId)
                                if (next != null) {
                                    if (selectedMessage!!.unread) {
                                        viewModel.markAsRead(selectedMessage!!)
                                    }
                                    selectedMessage = next
                                    val rootId = findRootForMessage(next, messages)
                                    expandedRootIds = expandedRootIds + rootId
                                    showReplyPane = false
                                }
                            },
                            onAuthorClick = { viewModel.showProfile(selectedMessage!!.author) }
                        )
                    } else {
                        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    Box(modifier = Modifier.weight(if (showReplyPane) 0.5f else 1f)) {
                        if (isLoading && messages.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (selectedMessage != null) {
                            val parentMessage = remember(selectedMessage, messages) {
                                messages.find { it.remoteId == selectedMessage?.commentId }
                            }
                            MessageViewer(
                                message = selectedMessage!!,
                                parentMessage = parentMessage,
                                onParentClick = { parent ->
                                    selectedMessage = parent
                                    val rootId = findRootForMessage(parent, messages)
                                    expandedRootIds = expandedRootIds + rootId
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

                    if (showReplyPane && selectedMessage != null) {
                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        Box(modifier = Modifier.height(200.dp)) {
                            ReplyPane(
                                replyTo = selectedMessage!!,
                                initialText = replyInitialText,
                                onCancel = { showReplyPane = false },
                                onPost = { body, uri, name ->
                                    coroutineScope.launch {
                                        if (viewModel.postReply(context, selectedMessage!!.remoteId, body, uri, name) != 0) {
                                            showReplyPane = false
                                        }
                                    }
                                },
                                onSaveDraft = { body ->
                                    viewModel.saveDraft(selectedMessage!!.remoteId, body)
                                    showReplyPane = false
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

    selectedProfile?.let { profile ->
        ProfileDialog(
            profile = profile, 
            resume = selectedResume,
            mugshotUrl = selectedMugshotUrl,
            onDismiss = { viewModel.dismissProfile() }
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
        
        // Roots: commentId 0 OR parent missing
        val roots = messages.filter { it.commentId == 0 || !messageIds.contains(it.commentId) }
            .sortedByDescending { it.date }

        roots.forEach { root ->
            val tree = buildThreadTree(messages, root.remoteId)
            val unreadCount = tree.count { it.first.unread }

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

    // Improved scrolling logic with small delay to ensure layout is ready
    LaunchedEffect(selectedMessageId, displayItems) {
        if (selectedMessageId != null) {
            val index = displayItems.indexOfFirst {
                when (it) {
                    is ThreadDisplayItem.Collapsed -> it.message.remoteId == selectedMessageId
                    is ThreadDisplayItem.Expanded -> it.message.remoteId == selectedMessageId
                }
            }
            if (index != -1) {
                delay(100) // Small delay to allow list to settle
                val layoutInfo = listState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height
                if (viewportHeight > 0) {
                    val visibleItem = layoutInfo.visibleItemsInfo.find { it.index == index }
                    val itemSize = visibleItem?.size ?: 64 // Use a more realistic default size
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
                    HorizontalDivider(modifier = Modifier.padding(start = (item.depth * 12 + 32).dp))
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
    if (startMsg != null) {
        walk(startMsg, 0)
    }
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
                fontWeight = if (unreadCount > 0) FontWeight.ExtraBold else FontWeight.Normal,
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
                fontWeight = if (unreadCount > 0) FontWeight.ExtraBold else FontWeight.Normal,
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
                start = if (onToggleExpand != null) 4.dp else (level * 12 + 32).dp, 
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
                fontWeight = if (message.unread) FontWeight.ExtraBold else FontWeight.Normal,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message.author,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (message.unread) FontWeight.ExtraBold else FontWeight.Normal,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
fun MessageActionBar(
    message: CIXMessage,
    replyActive: Boolean,
    onReplyClick: () -> Unit,
    onNextUnreadClick: () -> Unit,
    onAuthorClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    val dateString = remember(message.date) { dateFormat.format(Date(message.date)) }

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

        val annotatedString = remember(normalizedBody, message.forumName, message.topicName) {
            linkify(normalizedBody, message.forumName, message.topicName)
        }

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

        val urls = remember(normalizedBody) { extractUrls(normalizedBody) }
        urls.forEach { url ->
            Spacer(modifier = Modifier.height(16.dp))
            MediaPreview(url = url)
        }
    }
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
    initialText: String = "",
    onCancel: () -> Unit,
    onPost: (String, Uri?, String?) -> Unit,
    onSaveDraft: (String) -> Unit
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    var attachmentUri by remember { mutableStateOf<Uri?>(null) }
    var attachmentName by remember { mutableStateOf<String?>(null) }
    var showCancelConfirm by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        attachmentUri = uri
        attachmentName = uri?.let { u ->
            context.contentResolver.query(u, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            }
        }
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Cancel Message") },
            text = { Text("Are you sure you want to discard this message? You can also save it as a draft.") },
            confirmButton = {
                TextButton(onClick = { 
                    showCancelConfirm = false
                    onCancel() 
                }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { 
                        onSaveDraft(text)
                        showCancelConfirm = false
                    }) {
                        Text("Save Draft")
                    }
                    TextButton(onClick = { showCancelConfirm = false }) {
                        Text("Keep Editing")
                    }
                }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(8.dp)) {
            OutlinedTextField(
                value = text, 
                onValueChange = { text = it }, 
                modifier = Modifier.fillMaxWidth().weight(1f), 
                placeholder = { Text("Type your message here...") }, 
                textStyle = MaterialTheme.typography.bodySmall, 
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), 
                horizontalArrangement = Arrangement.SpaceBetween, 
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Reply to ${replyTo.author} (#${replyTo.remoteId})", 
                    style = MaterialTheme.typography.labelMedium, 
                    color = MaterialTheme.colorScheme.primary, 
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { launcher.launch("*/*") }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach File", tint = if (attachmentUri != null) Color(0xFFD91B5C) else LocalContentColor.current)
                    }
                    if (attachmentName != null) {
                        Text(text = attachmentName!!, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 100.dp))
                    }
                    TextButton(onClick = {
                        if (text.isNotBlank() && text != initialText) {
                            showCancelConfirm = true
                        } else {
                            onCancel()
                        }
                    }) { Text("Cancel") }
                    TextButton(onClick = { onSaveDraft(text) }, enabled = text.isNotBlank()) { Text("Draft") }
                    Button(onClick = { onPost(text, attachmentUri, attachmentName) }, enabled = text.isNotBlank()) { Text("Post") }
                }
            }
        }
    }
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
