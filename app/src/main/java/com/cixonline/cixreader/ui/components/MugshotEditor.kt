package com.cixonline.cixreader.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.cixonline.cixreader.api.NetworkClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MugshotEditor(
    uri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    
    // Track original image dimensions for cropping
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding() // Ensures controls are visible around notch/status bar
            ) {
                // Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                    }
                    
                    Text(
                        "Crop Mugshot",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    Button(
                        onClick = {
                            Log.d("MugshotEditor", "Save clicked. imageSize=$imageSize, containerSize=$containerSize")
                            val croppedBitmap = createCroppedBitmap(
                                context, uri, scale, offset, containerSize, imageSize
                            )
                            if (croppedBitmap != null) {
                                Log.d("MugshotEditor", "Crop successful: ${croppedBitmap.width}x${croppedBitmap.height}")
                                onConfirm(croppedBitmap)
                            } else {
                                Log.e("MugshotEditor", "Crop failed")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD91B5C))
                    ) {
                        Text("SAVE", fontWeight = FontWeight.Bold)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // This represents the circular frame
                    Box(
                        modifier = Modifier
                            .size(280.dp) 
                            .clip(CircleShape)
                            .background(Color(0xFF1A1A1A))
                            .onGloballyPositioned { containerSize = it.size }
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(0.5f, 10f)
                                    offset += pan
                                }
                            }
                            .clipToBounds()
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(uri)
                                .size(Size.ORIGINAL)
                                .build(),
                            imageLoader = NetworkClient.getImageLoader(context),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            onSuccess = { state ->
                                imageSize = IntSize(
                                    state.painter.intrinsicSize.width.toInt(),
                                    state.painter.intrinsicSize.height.toInt()
                                )
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                        )
                    }
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Pinch to zoom, drag to frame within the circle",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun createCroppedBitmap(
    context: android.content.Context,
    uri: Uri,
    scale: Float,
    offset: Offset,
    containerSize: IntSize,
    imageSize: IntSize
): Bitmap? {
    if (imageSize.width <= 0 || imageSize.height <= 0 || containerSize.width <= 0) return null
    
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val originalBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        if (originalBitmap == null) return null

        val targetSize = 100
        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        
        val matrix = Matrix()
        
        // 1. Calculate how the image fits in the UI container (ContentScale.Fit)
        val fitScale = if (imageSize.width > imageSize.height) {
            containerSize.width.toFloat() / imageSize.width.toFloat()
        } else {
            containerSize.height.toFloat() / imageSize.height.toFloat()
        }
        
        // 2. Apply transformations to match the UI view
        // Start with the initial fit scale
        matrix.postScale(fitScale, fitScale)
        
        // Center the scaled image in the container
        val startX = (containerSize.width - imageSize.width * fitScale) / 2f
        val startY = (containerSize.height - imageSize.height * fitScale) / 2f
        matrix.postTranslate(startX, startY)
        
        // Apply user zoom (centered on the container)
        matrix.postScale(scale, scale, containerSize.width / 2f, containerSize.height / 2f)
        
        // Apply user pan
        matrix.postTranslate(offset.x, offset.y)
        
        // 3. Finally, scale everything down to the target 100x100 size
        val uiToTargetScale = targetSize.toFloat() / containerSize.width.toFloat()
        matrix.postScale(uiToTargetScale, uiToTargetScale)

        canvas.drawBitmap(originalBitmap, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        
        originalBitmap.recycle()
        result
    } catch (e: Exception) {
        Log.e("MugshotEditor", "Error cropping bitmap", e)
        null
    }
}
