package com.cixonline.cixreader.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cixonline.cixreader.R
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.viewmodel.TopicViewModel
import com.cixonline.cixreader.utils.SettingsManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

sealed class ThreadDisplayItem {
    data class Collapsed(val message: CIXMessage, val childCount: Int, val unreadChildren: Int) : ThreadDisplayItem()
    data class Expanded(val message: CIXMessage, val depth: Int) : ThreadDisplayItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    viewModel: TopicViewModel,
    onBackClick: () -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit,
    settingsManager: SettingsManager
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val fontSizeMultiplier = remember { settingsManager.getFontSize() }

    val getEffectiveRootId = { msg: CIXMessage -> if (msg.rootId != 0) msg.rootId else msg.remoteId }

    var selectedRootId by remember { 
        mutableStateOf<Int?>(if (viewModel.initialRootId != 0) viewModel.initialRootId else null) 
    }
    var selectedMessage by remember { mutableStateOf<CIXMessage?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showReplyPane by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Automatically select message and expand thread
    LaunchedEffect(messages) {
        if (messages.isNotEmpty() && selectedMessage == null) {
            // Priority 1: If we have an initial rootId, try to find a message within that thread
            if (selectedRootId != null && selectedRootId != 0) {
                val threadMessages = messages.filter { getEffectiveRootId(it) == selectedRootId }
                if (threadMessages.isNotEmpty()) {
                    val nextUnreadInThread = threadMessages.find { it.unread }
                    selectedMessage = nextUnreadInThread ?: threadMessages.first()
                }
            }
            
            // Priority 2: Find any unread message in the topic
            if (selectedMessage == null) {
                val nextUnread = viewModel.findNextUnread(null)
                if (nextUnread != null) {
                    selectedMessage = nextUnread
                    selectedRootId = getEffectiveRootId(nextUnread)
                }
            }

            // Priority 3: Select the newest root message
            if (selectedMessage == null) {
                val firstRoot = messages.filter { it.isRoot }.sortedByDescending { it.date }.firstOrNull()
                if (firstRoot != null) {
                    selectedMessage = firstRoot
                    selectedRootId = getEffectiveRootId(firstRoot)
                }
            }
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
                                text = viewModel.forumName + " / " + viewModel.topicName,
                                color = Color.White.copy(alpha = 0.7f)
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
        Box(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Pane (Combined Thread List)
                Box(modifier = Modifier.weight(if (showReplyPane) 0.5f else 1f)) {
                    CombinedThreadList(
                        messages = messages,
                        selectedRootId = selectedRootId,
                        selectedMessageId = selectedMessage?.remoteId,
                        fontSizeMultiplier = fontSizeMultiplier,
                        onMessageClick = { msg ->
                            selectedMessage = msg
                            selectedRootId = getEffectiveRootId(msg)
                        },
                        onCollapse = { selectedRootId = null },
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
                                selectedRootId = getEffectiveRootId(next)
                                showReplyPane = false
                            }
                        }
                    )
                } else {
                    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }

                // Middle Pane (Message Viewer)
                Box(modifier = Modifier.weight(if (showReplyPane) 0.5f else 1f)) {
                    if (selectedMessage != null) {
                        val parentMessage = remember(selectedMessage, messages) {
                            messages.find { it.remoteId == selectedMessage?.commentId }
                        }
                        MessageViewer(
                            message = selectedMessage!!,
                            parentMessage = parentMessage,
                            fontSizeMultiplier = fontSizeMultiplier,
                            onParentClick = { parent ->
                                selectedMessage = parent
                                selectedRootId = getEffectiveRootId(parent)
                            }
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

                // Bottom Pane (Reply Pane)
                if (showReplyPane && selectedMessage != null) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    Box(modifier = Modifier.height(200.dp)) {
                        ReplyPane(
                            replyTo = selectedMessage!!,
                            onCancel = { showReplyPane = false },
                            onPost = { body ->
                                coroutineScope.launch {
                                    if (viewModel.postReply(selectedMessage!!.remoteId, body)) {
                                        showReplyPane = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
            
            if (isLoading && messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CombinedThreadList(
    messages: List<CIXMessage>,
    selectedRootId: Int?,
    selectedMessageId: Int?,
    fontSizeMultiplier: Float,
    onMessageClick: (CIXMessage) -> Unit,
    onCollapse: () -> Unit,
    listState: LazyListState
) {
    val displayItems = remember(messages, selectedRootId) {
        val result = mutableListOf<ThreadDisplayItem>()
        val roots = messages.filter { it.isRoot }.sortedByDescending { it.date }
        
        roots.forEach { root ->
            val rootId = if (root.rootId != 0) root.rootId else root.remoteId
            if (rootId == selectedRootId) {
                val tree = buildThreadTree(messages, rootId)
                tree.forEach { (msg, depth) ->
                    result.add(ThreadDisplayItem.Expanded(msg, depth))
                }
            } else {
                val childCount = messages.count { it.rootId == rootId && !it.isRoot }
                val unreadChildren = messages.count { it.rootId == rootId && it.unread }
                result.add(ThreadDisplayItem.Collapsed(root, childCount, unreadChildren))
            }
        }
        result
    }

    // Centering scroll logic
    LaunchedEffect(selectedMessageId) {
        if (selectedMessageId != null) {
            val index = displayItems.indexOfFirst {
                when (it) {
                    is ThreadDisplayItem.Collapsed -> it.message.remoteId == selectedMessageId
                    is ThreadDisplayItem.Expanded -> it.message.remoteId == selectedMessageId
                }
            }
            if (index != -1) {
                val layoutInfo = listState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height
                if (viewportHeight > 0) {
                    val visibleItem = layoutInfo.visibleItemsInfo.find { it.index == index }
                    val itemSize = visibleItem?.size ?: 120 // Estimate
                    val offset = (viewportHeight - itemSize) / 2
                    listState.animateScrollToItem(index, -offset)
                } else {
                    listState.animateScrollToItem(index)
                }
            }
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        displayItems.forEach { item ->
            if (item is ThreadDisplayItem.Expanded && item.depth == 0) {
                stickyHeader(key = "header-${item.message.remoteId}") {
                    Column {
                        ThreadRow(
                            message = item.message,
                            level = item.depth,
                            isSelected = item.message.remoteId == selectedMessageId,
                            fontSizeMultiplier = fontSizeMultiplier,
                            onClick = { onMessageClick(item.message) },
                            onCollapse = onCollapse
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = (item.depth * 12 + 32).dp))
                    }
                }
            } else {
                item(key = when(item) {
                    is ThreadDisplayItem.Collapsed -> "collapsed-${item.message.remoteId}"
                    is ThreadDisplayItem.Expanded -> "msg-${item.message.remoteId}"
                }) {
                    when (item) {
                        is ThreadDisplayItem.Collapsed -> {
                            ThreadItem(
                                message = item.message,
                                childCount = item.childCount,
                                unreadChildren = item.unreadChildren,
                                fontSizeMultiplier = fontSizeMultiplier,
                                onClick = { onMessageClick(item.message) }
                            )
                            HorizontalDivider()
                        }
                        is ThreadDisplayItem.Expanded -> {
                            ThreadRow(
                                message = item.message,
                                level = item.depth,
                                isSelected = item.message.remoteId == selectedMessageId,
                                fontSizeMultiplier = fontSizeMultiplier,
                                onClick = { onMessageClick(item.message) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = (item.depth * 12 + 32).dp))
                        }
                    }
                }
            }
        }
    }
}

fun buildThreadTree(messages: List<CIXMessage>, rootId: Int): List<Pair<CIXMessage, Int>> {
    val children = messages.groupBy { it.commentId }
    val result = mutableListOf<Pair<CIXMessage, Int>>()
    
    fun walk(m: CIXMessage, depth: Int) {
        result.add(m to depth)
        children[m.remoteId]?.sortedBy { it.date }?.forEach { walk(it, depth + 1) }
    }
    
    val root = messages.find { it.remoteId == rootId }
    if (root != null) {
        walk(root, 0)
    } else {
        val threadNodes = messages.filter { (it.rootId != 0 && it.rootId == rootId) || (it.rootId == 0 && it.remoteId == rootId) }
            .sortedBy { it.date }
        threadNodes.forEach { node ->
            if (messages.none { it.remoteId == node.commentId }) {
                walk(node, 0)
            }
        }
    }
    return result
}

@Composable
fun ReplyPane(
    replyTo: CIXMessage,
    onCancel: () -> Unit,
    onPost: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Reply to ${replyTo.author} (#${replyTo.remoteId})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", style = MaterialTheme.typography.labelLarge)
                    }
                    Button(
                        onClick = { onPost(text) },
                        enabled = text.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text("Post")
                    }
                }
            }
            
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("Type your message here...", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun MessageActionBar(
    message: CIXMessage,
    replyActive: Boolean,
    onReplyClick: () -> Unit,
    onNextUnreadClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    val dateString = remember(message.date) { dateFormat.format(Date(message.date)) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${message.remoteId}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = message.author,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = dateString,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Row {
                IconButton(onClick = onReplyClick) {
                    Icon(
                        if (replyActive) Icons.Default.Close else Icons.Default.Reply,
                        contentDescription = "Reply",
                        tint = if (replyActive) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
                if (!replyActive) {
                    IconButton(onClick = onNextUnreadClick) {
                        Icon(Icons.Default.NavigateNext, contentDescription = "Next Unread")
                    }
                }
            }
        }
    }
}

@Composable
fun ThreadRow(
    message: CIXMessage, 
    level: Int, 
    isSelected: Boolean, 
    fontSizeMultiplier: Float, 
    onClick: () -> Unit,
    onCollapse: (() -> Unit)? = null
) {
    Surface(
        color = if (isSelected) Color(0xFFD91B5C) else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(
                    start = if (onCollapse != null) 4.dp else (level * 12 + 32).dp, 
                    top = 4.dp, 
                    bottom = 4.dp, 
                    end = 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onCollapse != null) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(24.dp)
                ) {
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
                text = message.body.take(100).replace("\n", " "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = MaterialTheme.typography.bodySmall.fontSize * fontSizeMultiplier,
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
fun MessageViewer(
    message: CIXMessage,
    parentMessage: CIXMessage? = null,
    fontSizeMultiplier: Float,
    onParentClick: (CIXMessage) -> Unit = {}
) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clickable { onParentClick(parentMessage) }
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
                        text = parentMessage.body.take(200).replace("\n", " ") + if (parentMessage.body.length > 200) "..." else "",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = MaterialTheme.typography.bodySmall.fontSize * fontSizeMultiplier
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Text(
            text = message.body,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontSizeMultiplier
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ThreadItem(
    message: CIXMessage,
    childCount: Int,
    unreadChildren: Int,
    fontSizeMultiplier: Float,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    val dateString = remember(message.date) { dateFormat.format(Date(message.date)) }

    ListItem(
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.author,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (message.unread || unreadChildren > 0) FontWeight.ExtraBold else FontWeight.Normal
                )
                Text(
                    text = dateString,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        supportingContent = {
            Column {
                Text(
                    text = message.body.take(100).replace("\n", " "),
                    maxLines = 2,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontSizeMultiplier
                    ),
                    fontWeight = if (message.unread || unreadChildren > 0) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = "Replies: $childCount" + if (unreadChildren > 0) " ($unreadChildren unread)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unreadChildren > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    fontWeight = if (unreadChildren > 0) FontWeight.Bold else FontWeight.Normal
                )
            }
        },
        trailingContent = {
            Icon(Icons.Default.ChevronRight, contentDescription = "Expand", tint = MaterialTheme.colorScheme.outline)
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
