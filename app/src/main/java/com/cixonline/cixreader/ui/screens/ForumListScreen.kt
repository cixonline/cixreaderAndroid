package com.cixonline.cixreader.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cixonline.cixreader.R
import com.cixonline.cixreader.models.Folder
import com.cixonline.cixreader.viewmodel.ForumViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumListScreen(
    viewModel: ForumViewModel,
    currentUsername: String?,
    onBackClick: () -> Unit,
    onTopicClick: (forumName: String, topicName: String, topicId: Int) -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit,
    onProfileClick: (String) -> Unit
) {
    val folders by viewModel.allFolders.collectAsState(initial = emptyList())
    val expandedForums by viewModel.expandedForums.collectAsState()
    val showOnlyUnread by viewModel.showOnlyUnread.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Track which forum is currently swiped to reveal the Resign button
    var swipedForumId by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        modifier = Modifier.pointerInput(Unit) {
            // Detect taps outside of items to close any swiped Resign button
            detectTapGestures {
                swipedForumId = null
            }
        },
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
                        Text( text = "My Forums",
                              color = Color.White.copy(alpha = 0.7f),
                              style= MaterialTheme.typography.labelMedium
                        )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = !showOnlyUnread,
                            onClick = { viewModel.setShowOnlyUnread(false) },
                            label = { Text("All", style= MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = Color.White.copy(alpha = 0.7f),
                                selectedLabelColor = Color.White,
                                selectedContainerColor = Color.White.copy(alpha = 0.2f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = !showOnlyUnread,
                                borderColor = Color.White.copy(alpha = 0.3f),
                                selectedBorderColor = Color.White.copy(alpha = 0.5f)
                            )
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        FilterChip(
                            selected = showOnlyUnread,
                            onClick = { viewModel.setShowOnlyUnread(true) },
                            label = { Text("Unread", style= MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = Color.White.copy(alpha = 0.7f),
                                selectedLabelColor = Color.White,
                                selectedContainerColor = Color.White.copy(alpha = 0.2f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = showOnlyUnread,
                                borderColor = Color.White.copy(alpha = 0.3f),
                                selectedBorderColor = Color.White.copy(alpha = 0.5f)
                            )
                        )
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
                                    text = { Text("Logout") },
                                    onClick = {
                                        showMenu = false
                                        onLogout()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = viewModel.isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                if (viewModel.isLoading && folders.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (viewModel.errorMessage != null && folders.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = viewModel.errorMessage!!,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.refresh() }) {
                                Text("Retry")
                            }
                        }
                    }
                } else if (folders.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading...", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    val displayList = remember(folders, expandedForums, showOnlyUnread) {
                        val list = mutableListOf<Pair<Folder, Boolean>>() // Folder, isTopic
                        
                        val filteredFolders = if (showOnlyUnread) {
                            // Keep forums if they have unread messages OR if any of their topics have unread messages
                            folders.filter { folder ->
                                folder.unread > 0 || (folder.parentId == -1 && folders.any { it.parentId == folder.id && it.unread > 0 })
                            }
                        } else {
                            folders
                        }

                        val forums = filteredFolders.filter { it.parentId == -1 }
                            .sortedWith(
                                compareBy { it.name.lowercase() }
                            )
                        
                        forums.forEach { forum ->
                            list.add(forum to false)
                            if (expandedForums.contains(forum.id)) {
                                val topics = filteredFolders.filter { it.parentId == forum.id }
                                    .sortedWith(
                                        compareBy { it.name.lowercase() }
                                    )
                                topics.forEach { topic ->
                                    list.add(topic to true)
                                }
                            }
                        }
                        list
                    }

                    val alphabet = remember(displayList) {
                        displayList.mapNotNull { 
                            val char = it.first.name.firstOrNull()?.uppercaseChar()
                            if (char != null && char in 'A'..'Z') char else null
                        }.distinct().sorted()
                    }

                    Row(modifier = Modifier.fillMaxSize()) {
                        val scrollbarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        LazyColumn(
                            state = listState, 
                            modifier = Modifier
                                .weight(1f)
                                .drawWithContent {
                                    drawContent()
                                    val firstVisibleElementIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                                    val lastVisibleElementIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                                    if (firstVisibleElementIndex != null && lastVisibleElementIndex != null) {
                                        val elementCount = listState.layoutInfo.totalItemsCount
                                        val scrollbarHeight = (size.height / elementCount) * (lastVisibleElementIndex - firstVisibleElementIndex + 1)
                                        val scrollbarOffsetY = (size.height / elementCount) * firstVisibleElementIndex
                                        drawRect(
                                            color = scrollbarColor,
                                            topLeft = Offset(size.width - 2.dp.toPx(), scrollbarOffsetY),
                                            size = Size(2.dp.toPx(), scrollbarHeight)
                                        )
                                    }
                                }
                        ) {
                            items(displayList, key = { it.first.id.toString() + if (it.second) "-topic" else "-forum" }) { (item, isTopic) ->
                                if (isTopic) {
                                    val forum = folders.find { it.id == item.parentId }
                                    CompactTopicItem(
                                        title = item.name,
                                        unreadCount = item.unread,
                                        isLoading = viewModel.isLoading,
                                        onClick = { 
                                            if (forum != null) {
                                                onTopicClick(forum.name, item.name, item.id)
                                            }
                                        }
                                    )
                                } else {
                                    SwipeToResignRow(
                                        item = item,
                                        isExpanded = expandedForums.contains(item.id),
                                        isLoading = viewModel.isLoading,
                                        isSwiped = swipedForumId == item.id,
                                        onSwipe = { swipedId -> swipedForumId = swipedId },
                                        onResign = { viewModel.resignForum(item) },
                                        onToggle = { viewModel.toggleForum(item) }
                                    )
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }

                        // Alphabet Index
                        if (alphabet.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .width(24.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceEvenly
                            ) {
                                alphabet.forEach { char ->
                                    Text(
                                        text = char.toString(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val index = displayList.indexOfFirst { 
                                                    it.first.name.startsWith(char, ignoreCase = true) 
                                                }
                                                if (index != -1) {
                                                    scope.launch {
                                                        listState.scrollToItem(index)
                                                    }
                                                }
                                            },
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToResignRow(
    item: Folder,
    isExpanded: Boolean,
    isLoading: Boolean,
    isSwiped: Boolean,
    onSwipe: (Int?) -> Unit,
    onResign: () -> Unit,
    onToggle: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onSwipe(item.id)
                true // Settle at the end to expose the button
            } else {
                onSwipe(null)
                true // Settle back
            }
        }
    )

    // Sync external state (swipedForumId) with internal state
    LaunchedEffect(isSwiped) {
        if (!isSwiped && dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Forum name remains showing on the left in the background area
                Text(
                    text = item.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Clicking this button is the ONLY way to resign
                Button(
                    onClick = {
                        onResign()
                        onSwipe(null)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Red
                    ),
                    shape = MaterialTheme.shapes.extraSmall,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Resign",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    ) {
        CompactForumItem(
            title = item.name,
            unreadCount = item.unread,
            isExpanded = isExpanded,
            isLoading = isLoading,
            onClick = {
                if (isSwiped) {
                    // If already swiped, a click covers the button again
                    onSwipe(null)
                } else {
                    // Otherwise, toggle expansion as normal
                    onToggle()
                }
            }
        )
    }
}

@Composable
fun CompactForumItem(
    title: String,
    unreadCount: Int,
    isExpanded: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    // Surface MUST have an opaque background to hide the Resign button behind it
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            UnreadIndicator(count = unreadCount, isLoading = isLoading, isExpanded = isExpanded)
        }
    }
}

@Composable
fun CompactTopicItem(
    title: String,
    unreadCount: Int,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(start = 40.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            UnreadIndicator(count = unreadCount, isLoading = isLoading, isExpanded = false)
        }
    }
}

@Composable
fun UnreadIndicator(count: Int, isLoading: Boolean, isExpanded: Boolean) {
    if (!isExpanded) {
        if (isLoading && count == 0) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (count > 0) {
            Surface(
                color = Color(0xFFD91B5C),
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = count.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
}
