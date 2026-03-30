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
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
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

            val dateIndex = remember(logs) {
                val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
                logs.mapIndexedNotNull { index, log ->
                    val dateStr = dateFormat.format(Date(log.timestamp))
                    if (index == 0 || dateFormat.format(Date(logs[index - 1].timestamp)) != dateStr) {
                        dateStr to index
                    } else null
                }
            }

            Row(modifier = Modifier.fillMaxSize()) {
                val scrollbarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .drawWithContent {
                            drawContent()
                            val visibleItems = listState.layoutInfo.visibleItemsInfo
                            if (visibleItems.isNotEmpty()) {
                                val firstVisibleIndex = visibleItems.first().index
                                val lastVisibleIndex = visibleItems.last().index
                                val totalItemsCount = listState.layoutInfo.totalItemsCount

                                if (totalItemsCount > 0) {
                                    val scrollbarHeight = (size.height / totalItemsCount) * (lastVisibleIndex - firstVisibleIndex + 1)
                                    val scrollbarOffsetY = (size.height / totalItemsCount) * firstVisibleIndex
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
                        LogItem(log)
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }

                if (dateIndex.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .width(40.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dateIndex.forEach { (date, index) ->
                            Text(
                                text = date,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            listState.scrollToItem(index)
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogItem(log: LogEntry) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    Column(
        modifier = Modifier
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
