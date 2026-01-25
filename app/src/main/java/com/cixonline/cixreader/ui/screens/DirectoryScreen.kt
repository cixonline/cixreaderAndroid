package com.cixonline.cixreader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cixonline.cixreader.api.DirListing
import com.cixonline.cixreader.viewmodel.DirectoryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryScreen(
    viewModel: DirectoryViewModel,
    onBackClick: () -> Unit,
    onForumJoined: (String) -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val forums by viewModel.forums.collectAsState()
    val joinedForumNames by viewModel.joinedForumNames.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    
    var isSearching by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val filteredForums = remember(forums, searchQuery) {
        val sorted = forums.sortedBy { it.forum?.lowercase() ?: "" }
        if (searchQuery.isBlank()) {
            sorted
        } else {
            sorted.filter { 
                it.forum?.contains(searchQuery, ignoreCase = true) == true || 
                it.title?.contains(searchQuery, ignoreCase = true) == true 
            }
        }
    }

    val alphabet = remember(filteredForums) {
        filteredForums.mapNotNull { 
            val char = it.forum?.firstOrNull()?.uppercaseChar()
            if (char != null && char in 'A'..'Z') char else null
        }.distinct().sorted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.onSearchQueryChange(it) },
                            placeholder = { Text("Search forums...", color = Color.White.copy(alpha = 0.7f)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color.White
                            )
                        )
                    } else {
                        Text("Directory")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isSearching) {
                        IconButton(onClick = { 
                            isSearching = false
                            viewModel.onSearchQueryChange("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close Search")
                        }
                    } else {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFD91B5C),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading && forums.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f)
                    ) {
                        items(filteredForums) { forum ->
                            val isJoined = joinedForumNames.contains(forum.forum)
                            ForumDirectoryItem(
                                forum = forum,
                                isJoined = isJoined,
                                onJoinClick = {
                                    viewModel.joinForum(forum.forum ?: "") { success ->
                                        if (success) {
                                            onForumJoined(forum.forum ?: "")
                                        }
                                    }
                                },
                                onViewClick = {
                                    onForumJoined(forum.forum ?: "")
                                }
                            )
                            HorizontalDivider()
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
                                            val index = filteredForums.indexOfFirst { 
                                                it.forum?.startsWith(char, ignoreCase = true) == true
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
fun ForumDirectoryItem(
    forum: DirListing, 
    isJoined: Boolean,
    onJoinClick: () -> Unit,
    onViewClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = forum.forum ?: "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = forum.title ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!forum.cat.isNullOrBlank()) {
                    Text(
                        text = "${forum.cat} / ${forum.sub}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        },
        trailingContent = {
            if (isJoined) {
                TextButton(
                    onClick = onViewClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("View")
                }
            } else {
                Button(
                    onClick = onJoinClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Join")
                }
            }
        }
    )
}
