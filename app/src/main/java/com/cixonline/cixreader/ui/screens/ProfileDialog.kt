package com.cixonline.cixreader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.cixonline.cixreader.R
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.api.UserProfile
import com.cixonline.cixreader.viewmodel.ProfileDelegate

@Composable
fun ProfileDialog(
    profile: UserProfile, 
    resume: String?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.userName ?: "Unknown",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!profile.fullName.isNullOrBlank()) {
                            Text(text = profile.fullName!!, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    
                    ProfileDelegate.getMugshotUrl(profile.userName)?.let { mugshotUrl ->
                        AsyncImage(
                            model = mugshotUrl,
                            imageLoader = NetworkClient.getImageLoader(LocalContext.current),
                            contentDescription = "Mugshot",
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                            placeholder = painterResource(R.drawable.cix_logo),
                            error = painterResource(R.drawable.cix_logo)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profile.location?.takeIf { it.isNotBlank() }?.let {
                        ProfileRow(label = "Location", value = it)
                    }
                    profile.email?.takeIf { it.isNotBlank() }?.let {
                        ProfileRow(label = "Email", value = it)
                    }
                    profile.about?.takeIf { it.isNotBlank() }?.let {
                        ProfileRow(label = "About", value = it)
                    }
                    
                    if (!resume.isNullOrBlank()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "Resume", 
                            style = MaterialTheme.typography.labelLarge, 
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = resume,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f, fill = false))
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun ProfileRow(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
