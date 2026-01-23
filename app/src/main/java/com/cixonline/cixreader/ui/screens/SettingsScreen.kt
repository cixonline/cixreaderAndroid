package com.cixonline.cixreader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cixonline.cixreader.utils.SettingsManager
import com.cixonline.cixreader.utils.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onBackClick: () -> Unit
) {
    var fontSizeMultiplier by remember { mutableStateOf(settingsManager.getFontSize()) }
    var themeMode by remember { mutableStateOf(settingsManager.getThemeMode()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFD91B5C),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    ThemeMode.values().forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = themeMode == mode,
                                    onClick = {
                                        themeMode = mode
                                        settingsManager.saveThemeMode(mode)
                                    }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = {
                                    themeMode = mode
                                    settingsManager.saveThemeMode(mode)
                                }
                            )
                            Text(
                                text = when (mode) {
                                    ThemeMode.SYSTEM -> "Follow System"
                                    ThemeMode.LIGHT -> "Light Mode"
                                    ThemeMode.DARK -> "Dark Mode"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Font Size Multiplier",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "${(fontSizeMultiplier * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                    
                    Slider(
                        value = fontSizeMultiplier,
                        onValueChange = { 
                            fontSizeMultiplier = it
                            settingsManager.saveFontSize(it)
                        },
                        valueRange = 0.8f..2.0f,
                        steps = 11,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    
                    Text(
                        text = "This is a preview of how text will look at this size.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontSizeMultiplier
                        ),
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}

// Extension function for selectable to improve readability
fun Modifier.selectable(
    selected: Boolean,
    onClick: () -> Unit
): Modifier = this.clickable(onClick = onClick)
