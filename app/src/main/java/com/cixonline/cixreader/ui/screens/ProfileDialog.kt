package com.cixonline.cixreader.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.cixonline.cixreader.R
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.api.UserProfile

@Composable
fun ProfileDialog(
    profile: UserProfile, 
    resume: String?,
    mugshotUrl: String?,
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
                    
                    val imageModel = mugshotUrl ?: profile.userName?.let { "https://api.cixonline.com/v2.0/cix.svc/user/$it/mugshot" }
                    
                    if (imageModel != null) {
                        var state by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
                        
                        Box(contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(imageModel)
                                    .placeholder(R.drawable.cix_logo)
                                    .error(R.drawable.cix_logo)
                                    .crossfade(true)
                                    .build(),
                                imageLoader = NetworkClient.getImageLoader(LocalContext.current),
                                contentDescription = "Mugshot",
                                onState = { state = it },
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            
                            if (state is AsyncImagePainter.State.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else {
                        Image(
                            painter = painterResource(R.drawable.cix_logo),
                            contentDescription = "No user",
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
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
                    profile.firstOn?.takeIf { it.isNotBlank() }?.let {
                        ProfileRow(label = "First On", value = it)
                    }
                    profile.lastOn?.takeIf { it.isNotBlank() }?.let {
                        ProfileRow(label = "Last On", value = it)
                    }
                    profile.lastPost?.takeIf { it.isNotBlank() }?.let {
                        ProfileRow(label = "Last Post", value = it)
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
