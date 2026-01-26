package com.cixonline.cixreader.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    onBackClick: () -> Unit,
    onTopicClick: (forumName: String, topicName: String, topicId: Int) -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val folders by viewModel.allFolders.collectAsState(initial = emptyList())
    val expandedForums by viewModel.expandedForums.collectAsState()
    val showOnlyUnread by viewModel.showOnlyUnread.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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
                        Text( text = "My Forums",
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
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        FilterChip(
                            selected = !showOnlyUnread,
                            onClick = { viewModel.setShowOnlyUnread(false) },
                            label = { Text("All") },
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
                        Spacer(modifier = Modifier.width(4.dp))
                        FilterChip(
                            selected = showOnlyUnread,
                            onClick = { viewModel.setShowOnlyUnread(true) },
                            label = { Text("Unread") },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    Text("No forums found.", style = MaterialTheme.typography.bodyLarge)
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
                        items(displayList) { (item, isTopic) ->
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
                                CompactForumItem(
                                    title = item.name,
                                    unreadCount = item.unread,
                                    isExpanded = expandedForums.contains(item.id),
                                    isLoading = viewModel.isLoading,
                                    onClick = { viewModel.toggleForum(item) }
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

@Composable
fun CompactForumItem(
    title: String,
    unreadCount: Int,
    isExpanded: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
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
