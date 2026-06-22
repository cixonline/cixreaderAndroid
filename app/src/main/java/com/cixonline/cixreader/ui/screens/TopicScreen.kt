package com.cixonline.cixreader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cixonline.cixreader.models.CIXMessage
import com.cixonline.cixreader.ui.components.linkify
import com.cixonline.cixreader.utils.HtmlUtils
import com.cixonline.cixreader.viewmodel.TopicViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicScreen(
    viewModel: TopicViewModel,
    onBackClick: () -> Unit,
    onTitleClick: (forumName: String) -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit,
    onDraftsClick: () -> Unit,
    onNavigateToThread: (forum: String, topic: String, topicId: Int, rootId: Int, msgId: Int) -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val scrollToMessageId by viewModel.scrollToMessageId.collectAsState()
    val showJoinDialog by viewModel.showJoinDialog.collectAsState()
    
    var showMenu by remember { mutableStateOf(false) }
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(messages, scrollToMessageId) {
        if (scrollToMessageId != null && messages.isNotEmpty()) {
            val index = messages.indexOfFirst { it.remoteId == scrollToMessageId }
            if (index != -1) {
                coroutineScope.launch {
                    listState.scrollToItem(index)
                    viewModel.onScrollToMessageComplete()
                }
            }
        }
    }

    if (showJoinDialog != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissJoinDialog() },
            title = { Text("Join Forum") },
            text = { Text("You are not a member of $showJoinDialog. Would you like to join it?") },
            confirmButton = {
                TextButton(onClick = { viewModel.joinForum(showJoinDialog!!) }) {
                    Text("Join")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissJoinDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFD91B5C),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    Column(
                        modifier = Modifier
                            .clickable { onTitleClick(viewModel.forumName) }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = viewModel.forumName,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            text = viewModel.topicName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                },
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
                                text = { Text("Mark All Read") },
                                onClick = {
                                    showMenu = false
                                    viewModel.markTopicAsRead()
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
        Column(modifier = Modifier.padding(padding)) {
            TextField(
                value = searchQuery,
                onValueChange = { viewModel.onSearchQueryChange(it) },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                placeholder = { Text("Search messages...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                )
            )

            if (error != null) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }) {
                            Text("Retry")
                        }
                    }
                }
            } else if (isLoading && messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages found", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(messages, key = { _, it -> it.remoteId }) { _, message ->
                        MessageItem(
                            message = message,
                            onStarClick = { viewModel.toggleStar(message) },
                            onReadClick = { viewModel.markAsRead(message) },
                            onNavigateToThread = onNavigateToThread
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageItem(
    message: CIXMessage,
    onStarClick: () -> Unit,
    onReadClick: () -> Unit,
    onNavigateToThread: (forum: String, topic: String, topicId: Int, rootId: Int, msgId: Int) -> Unit
) {
    val indentLevel = message.level.coerceAtMost(10)
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    val dateString = remember(message.date) { dateFormat.format(Date(message.date)) }
    val uriHandler = LocalUriHandler.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indentLevel * 6 + 8).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (message.isActuallyUnread) 
                MaterialTheme.colorScheme.surfaceVariant 
            else 
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = message.author,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = if (message.isActuallyUnread) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = dateString,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Text(
                    text = "#${message.remoteId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            val annotatedBody = linkify(message.body, message.forumName, message.topicName)
            ClickableText(
                text = annotatedBody,
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                onClick = { offset ->
                    annotatedBody.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { uriHandler.openUri(it.item) }
                    annotatedBody.getStringAnnotations(tag = "CIX_REF", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            val parts = annotation.item.split(":")
                            onNavigateToThread(parts[0], parts[1], HtmlUtils.calculateTopicId(parts[0], parts[1]), 0, parts[2].toIntOrNull() ?: 0)
                        }
                }
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                if (message.isActuallyUnread) {
                    TextButton(onClick = onReadClick) {
                        Text("Mark Read", style = MaterialTheme.typography.labelSmall)
                    }
                }
                
                IconButton(onClick = onStarClick) {
                    Icon(
                        imageVector = if (message.starred) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Star",
                        tint = if (message.starred) 
                            MaterialTheme.colorScheme.secondary 
                        else 
                            MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
