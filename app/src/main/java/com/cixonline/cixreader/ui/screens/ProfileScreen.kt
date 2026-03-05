package com.cixonline.cixreader.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.cixonline.cixreader.R
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.ui.components.MugshotEditor
import com.cixonline.cixreader.viewmodel.ProfileViewModel
import java.io.File

/**
 * Custom TakePicture contract to explicitly grant URI permissions, 
 * as required for Android 18+ compatibility.
 */
private class TakePictureWithGrant : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent {
        return super.createIntent(context, input).apply {
            // Explicitly set ClipData and grant permissions to avoid implicit grant warnings/errors in future Android versions
            clipData = ClipData.newRawUri(null, input)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val profile by viewModel.selectedProfile.collectAsState()
    val resume by viewModel.selectedResume.collectAsState()
    val mugshotUrl by viewModel.selectedMugshotUrl.collectAsState()
    val pendingMugshotUri by viewModel.pendingMugshotUri.collectAsState()
    val pendingMugshotBitmap by viewModel.pendingMugshotBitmap.collectAsState()
    val isLoading by viewModel.isProfileLoading.collectAsState()
    
    val currentLoggedInUser = NetworkClient.getUsername()
    val isOwnProfile = viewModel.username.equals(currentLoggedInUser, ignoreCase = true)

    var isEditing by rememberSaveable { mutableStateOf(false) }
    var editFullName by remember { mutableStateOf("") }
    var editLocation by remember { mutableStateOf("") }
    var editEmail by remember { mutableStateOf("") }
    var editAbout by remember { mutableStateOf("") }
    var editResume by remember { mutableStateOf("") }

    var showImageSourceDialog by remember { mutableStateOf(false) }
    // Use rememberSaveable for the Uri to survive activity recreation
    var tempCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setPendingMugshot(context, it) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = TakePictureWithGrant()
    ) { success ->
        if (success) {
            tempCameraUri?.let { viewModel.setPendingMugshot(context, it) }
        }
    }

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

    if (showImageSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImageSourceDialog = false },
            title = { Text("Change Profile Picture") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("Take Photo") },
                        leadingContent = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                        modifier = Modifier.clickable {
                            val file = File(context.cacheDir, "camera_mugshot_${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            tempCameraUri = uri
                            cameraLauncher.launch(uri)
                            showImageSourceDialog = false
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Choose from Gallery") },
                        leadingContent = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        modifier = Modifier.clickable {
                            photoLauncher.launch("image/*")
                            showImageSourceDialog = false
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showImageSourceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    pendingMugshotUri?.let { uri ->
        MugshotEditor(
            uri = uri,
            onDismiss = { viewModel.clearPendingMugshot() },
            onConfirm = { bitmap ->
                viewModel.setPendingMugshotBitmap(bitmap)
            }
        )
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
                                context = context,
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
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (profile == null) {
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Could not load profile.")
            }
        } else {
            val p = profile!!
            PullToRefreshBox(
                isRefreshing = isLoading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.padding(paddingValues).fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
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

                        // Use key(mugshotUrl, pendingMugshotUri, pendingMugshotBitmap) to force complete reset of AsyncImage when URL or pending image changes
                        key(mugshotUrl, pendingMugshotUri, pendingMugshotBitmap) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(80.dp)
                                    .then(
                                        if (isOwnProfile && isEditing) {
                                            Modifier.clickable { showImageSourceDialog = true }
                                        } else Modifier
                                    )
                            ) {
                                val displayImage = pendingMugshotBitmap ?: pendingMugshotUri ?: mugshotUrl
                                if (displayImage != null) {
                                    var state by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(displayImage)
                                            .placeholder(R.drawable.cix_logo)
                                            .error(R.drawable.cix_logo)
                                            .diskCachePolicy(CachePolicy.ENABLED)
                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                            .crossfade(true)
                                            .build(),
                                        imageLoader = NetworkClient.getImageLoader(LocalContext.current),
                                        contentDescription = "Mugshot",
                                        onState = { state = it },
                                        modifier = Modifier
                                            .fillMaxSize()
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
                                } else {
                                    Image(
                                        painter = painterResource(R.drawable.cix_logo),
                                        contentDescription = "No user",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                    )
                                }
                                
                                if (isOwnProfile && isEditing) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.4f),
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = "Change Photo",
                                            tint = Color.White,
                                            modifier = Modifier.padding(24.dp)
                                        )
                                    }
                                }
                            }
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
