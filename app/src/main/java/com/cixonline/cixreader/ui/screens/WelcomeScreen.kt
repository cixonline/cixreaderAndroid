package com.cixonline.cixreader.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cixonline.cixreader.R
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.utils.DateUtils
import com.cixonline.cixreader.viewmodel.InterestingThreadUI
import com.cixonline.cixreader.viewmodel.WelcomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(
    viewModel: WelcomeViewModel,
    onExploreForums: () -> Unit,
    onDirectoryClick: () -> Unit,
    onThreadClick: (forum: String, topic: String, topicId: Int, rootId: Int) -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val threads by viewModel.interestingThreads.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var showPostDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refresh()
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
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text( text = "Reader",
                              color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFD91B5C),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh, 
                            contentDescription = "Refresh",
                            tint = Color.White
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert, 
                                contentDescription = "Menu",
                                tint = Color.White
                            )
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = "Recent Threads",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )

            // Scrollable Recent Threads Section
            Box(modifier = Modifier.weight(1f)) {
                if (isLoading && threads.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (threads.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No recent threads found", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(threads) { thread ->
                            InterestingThreadItem(
                                thread = thread,
                                onClick = {
                                    onThreadClick(
                                        thread.forum,
                                        thread.topic,
                                        (thread.forum + thread.topic).hashCode(),
                                        thread.rootId
                                    )
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }

            // Static Bottom Action Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // Jump In Button
                Button(
                    onClick = {
                        scope.launch {
                            val firstUnread = viewModel.getFirstUnreadMessage()
                            if (firstUnread != null) {
                                onThreadClick(
                                    firstUnread.forumName,
                                    firstUnread.topicName,
                                    firstUnread.topicId,
                                    firstUnread.rootId
                                )
                            } else if (threads.isNotEmpty()) {
                                // Fallback to interesting threads if no unread in cache
                                val firstThread = threads.first()
                                onThreadClick(
                                    firstThread.forum,
                                    firstThread.topic,
                                    (firstThread.forum + firstThread.topic).hashCode(),
                                    firstThread.rootId
                                )
                            } else {
                                snackbarHostState.showSnackbar("No unread messages or recent threads found")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD91B5C)
                    )
                ) {
                    Icon(Icons.Default.RocketLaunch, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Jump In")
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // My Forums Button
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onExploreForums() },
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFD91B5C)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Explore, contentDescription = null)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "My Forums",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Directory Button
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onDirectoryClick() },
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFD91B5C)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.MenuBook, contentDescription = null)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Directory",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Post Button
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showPostDialog = true },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Post",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPostDialog) {
        PostMessageDialog(
            viewModel = viewModel,
            onDismiss = { showPostDialog = false },
            onPostSuccess = {
                showPostDialog = false
                scope.launch {
                    snackbarHostState.showSnackbar("Message posted successfully")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostMessageDialog(
    viewModel: WelcomeViewModel,
    onDismiss: () -> Unit,
    onPostSuccess: () -> Unit
) {
    val forums by viewModel.allForums.collectAsState(emptyList())
    val selectedForum by viewModel.selectedForum.collectAsState()
    val topics by viewModel.topicsForSelectedForum.collectAsState()
    var selectedTopic by remember { mutableStateOf<Folder?>(null) }
    var messageBody by remember { mutableStateOf("") }
    var forumExpanded by remember { mutableStateOf(false) }
    var topicExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var isPosting by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "New Message",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                // Forum Selection
                ExposedDropdownMenuBox(
                    expanded = forumExpanded,
                    onExpandedChange = { forumExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedForum?.name ?: "Select Forum",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Forum") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = forumExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = forumExpanded,
                        onDismissRequest = { forumExpanded = false }
                    ) {
                        forums.forEach { forum ->
                            DropdownMenuItem(
                                text = { Text(forum.name) },
                                onClick = {
                                    viewModel.selectForum(forum)
                                    selectedTopic = null
                                    forumExpanded = false
                                }
                            )
                        }
                    }
                }

                // Topic Selection
                ExposedDropdownMenuBox(
                    expanded = topicExpanded,
                    onExpandedChange = { topicExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedTopic?.name ?: "Select Topic",
                        onValueChange = {},
                        readOnly = true,
                        enabled = selectedForum != null,
                        label = { Text("Topic") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = topicExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = topicExpanded,
                        onDismissRequest = { topicExpanded = false }
                    ) {
                        topics.forEach { topic ->
                            DropdownMenuItem(
                                text = { Text(topic.name) },
                                onClick = {
                                    selectedTopic = topic
                                    topicExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = messageBody,
                    onValueChange = { messageBody = it },
                    label = { Text("Message") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    maxLines = 10
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (selectedForum != null && selectedTopic != null && messageBody.isNotBlank()) {
                                isPosting = true
                                scope.launch {
                                    val success = viewModel.postMessage(
                                        selectedForum!!.name,
                                        selectedTopic!!.name,
                                        messageBody
                                    )
                                    isPosting = false
                                    if (success) onPostSuccess()
                                }
                            }
                        },
                        enabled = selectedForum != null && selectedTopic != null && messageBody.isNotBlank() && !isPosting
                    ) {
                        if (isPosting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        } else {
                            Text("Post")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InterestingThreadItem(thread: InterestingThreadUI, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${thread.forum} / ${thread.topic}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = DateUtils.formatCixDate(dateStr = thread.dateTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = thread.author,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (thread.isRootResolved) {
                Text(
                    text = "ROOT",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD91B5C),
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = thread.subject ?: thread.body?.substringBefore("\n")?.trim() ?: "",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold
        )
        if (thread.body != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = thread.body!!,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
