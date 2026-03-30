package com.cixonline.cixreader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cixonline.cixreader.models.LogEntry
import com.cixonline.cixreader.viewmodel.ActivityLogViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityLogScreen(
    viewModel: ActivityLogViewModel,
    onBackClick: () -> Unit
) {
    val logs by viewModel.logs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Highlight the current hour in the sidebar based on scroll position
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val currentHourStr = remember(logs, firstVisibleIndex) {
        if (logs.isEmpty() || firstVisibleIndex >= logs.size) ""
        else SimpleDateFormat("HH:00", Locale.getDefault()).format(Date(logs[firstVisibleIndex].timestamp))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity Log") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
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
            // Filter Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Filter logs...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true
            )

            // Calculate index points for the hour sidebar
            val hourIndex = remember(logs) {
                val hourFormat = SimpleDateFormat("HH:00", Locale.getDefault())
                val dayFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
                logs.mapIndexedNotNull { index, log ->
                    val date = Date(log.timestamp)
                    val hourStr = hourFormat.format(date)
                    val dayStr = dayFormat.format(date)
                    val prevLog = if (index > 0) logs[index - 1] else null
                    val prevHourStr = prevLog?.let { hourFormat.format(Date(it.timestamp)) } ?: ""
                    val prevDayStr = prevLog?.let { dayFormat.format(Date(it.timestamp)) } ?: ""
                    
                    // Add index point if hour or day changes
                    if (index == 0 || hourStr != prevHourStr || dayStr != prevDayStr) {
                        hourStr to index
                    } else null
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                val scrollbarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                
                // Main Log List
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            val visibleItems = listState.layoutInfo.visibleItemsInfo
                            if (visibleItems.isNotEmpty()) {
                                val firstVisible = visibleItems.first().index
                                val lastVisible = visibleItems.last().index
                                val totalItemsCount = listState.layoutInfo.totalItemsCount

                                if (totalItemsCount > 0) {
                                    val scrollbarHeight = (size.height / totalItemsCount) * (lastVisible - firstVisible + 1)
                                    val scrollbarOffsetY = (size.height / totalItemsCount) * firstVisible
                                    // Visual scrollbar on the far right edge of the list
                                    drawRect(
                                        color = scrollbarColor,
                                        topLeft = Offset(size.width - 2.dp.toPx(), scrollbarOffsetY),
                                        size = Size(2.dp.toPx(), scrollbarHeight)
                                    )
                                }
                            }
                        }
                ) {
                    items(logs) { log ->
                        // Padding on the right to avoid overlapping with the sidebar
                        LogItem(log, modifier = Modifier.padding(end = 40.dp))
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp, end = 40.dp))
                    }
                }

                // Jump-to-Hour Sidebar as an overlay
                if (hourIndex.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(40.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            hourIndex.forEach { (hour, index) ->
                                val isSelected = hour == currentHourStr
                                Text(
                                    text = hour,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary 
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    ),
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                listState.scrollToItem(index)
                                            }
                                        }
                                        .padding(vertical = 6.dp),
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
fun LogItem(log: LogEntry, modifier: Modifier = Modifier) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = log.type,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = dateFormat.format(Date(log.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = log.message,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
    }
}
