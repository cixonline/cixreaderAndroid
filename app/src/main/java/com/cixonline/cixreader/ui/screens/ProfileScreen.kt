package com.cixonline.cixreader.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.cixonline.cixreader.R
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.viewmodel.ProfileViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBackClick: () -> Unit
) {
    val profile by viewModel.selectedProfile.collectAsState()
    val resume by viewModel.selectedResume.collectAsState()
    val mugshotUrl by viewModel.selectedMugshotUrl.collectAsState()
    val isLoading by viewModel.isProfileLoading.collectAsState()
    
    val currentLoggedInUser = NetworkClient.getUsername()
    val isOwnProfile = viewModel.username.equals(currentLoggedInUser, ignoreCase = true)

    var isEditing by remember { mutableStateOf(false) }
    var editFullName by remember { mutableStateOf("") }
    var editLocation by remember { mutableStateOf("") }
    var editEmail by remember { mutableStateOf("") }
    var editAbout by remember { mutableStateOf("") }
    var editResume by remember { mutableStateOf("") }

    LaunchedEffect(profile) {
        profile?.let {
            editFullName = it.fullName ?: ""
            editLocation = it.location ?: ""
            editEmail = it.email ?: ""
            editAbout = it.about ?: ""
        }
    }
    
    LaunchedEffect(resume) {
        editResume = resume ?: ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profile?.userName ?: "Profile") },
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
        },
        floatingActionButton = {
            if (isOwnProfile && profile != null) {
                FloatingActionButton(
                    onClick = {
                        if (isEditing) {
                            viewModel.updateProfile(
                                fullName = editFullName,
                                email = editEmail,
                                location = editLocation,
                                about = editAbout,
                                experience = profile?.experience,
                                resume = editResume
                            )
                            isEditing = false
                        } else {
                            isEditing = true
                        }
                    },
                    containerColor = Color(0xFFD91B5C),
                    contentColor = Color.White
                ) {
                    Icon(
                        imageVector = if (isEditing) Icons.Default.Save else Icons.Default.Edit,
                        contentDescription = if (isEditing) "Save" else "Edit"
                    )
                }
            }
        }
    ) { paddingValues ->
        if (isLoading && profile == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (profile == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Could not load profile.")
            }
        } else {
            val p = profile!!
            Column(
                modifier = Modifier
                    .padding(paddingValues)
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
                            text = p.userName ?: "Unknown",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (isEditing) {
                            OutlinedTextField(
                                value = editFullName,
                                onValueChange = { editFullName = it },
                                label = { Text("Full Name") },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                        } else if (!p.fullName.isNullOrBlank()) {
                            Text(text = p.fullName!!, style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    val imageModel = mugshotUrl

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
                    if (isEditing) {
                        OutlinedTextField(
                            value = editLocation,
                            onValueChange = { editLocation = it },
                            label = { Text("Location") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editEmail,
                            onValueChange = { editEmail = it },
                            label = { Text("Email") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editAbout,
                            onValueChange = { editAbout = it },
                            label = { Text("About") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                    } else {
                        p.location?.takeIf { it.isNotBlank() }?.let {
                            ProfileRow(label = "Location", value = it)
                        }
                        p.email?.takeIf { it.isNotBlank() }?.let {
                            ProfileRow(label = "Email", value = it)
                        }
                        p.firstOn?.takeIf { it.isNotBlank() }?.let {
                            ProfileRow(label = "First On", value = it)
                        }
                        p.lastOn?.takeIf { it.isNotBlank() }?.let {
                            ProfileRow(label = "Last On", value = it)
                        }
                        p.lastPost?.takeIf { it.isNotBlank() }?.let {
                            ProfileRow(label = "Last Post", value = it)
                        }
                        p.about?.takeIf { it.isNotBlank() }?.let {
                            ProfileRow(label = "About", value = it)
                        }
                    }

                    if (isEditing) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        OutlinedTextField(
                            value = editResume,
                            onValueChange = { editResume = it },
                            label = { Text("Resume") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 5
                        )
                    } else if (!resume.isNullOrBlank()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "Resume",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = resume!!,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
