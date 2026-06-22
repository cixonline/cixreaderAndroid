package com.cixonline.cixreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.cixonline.cixreader.R
import com.cixonline.cixreader.utils.HtmlUtils

sealed class MessageChunk {
    data class Text(val text: String) : MessageChunk()
    data class Image(val url: String) : MessageChunk()
    data class Link(val url: String) : MessageChunk()
}

@Composable
fun MediaPreview(url: String) {
    val extension = url.substringAfterLast(".").lowercase()
    val isImage = remember(extension) { listOf("jpg", "jpeg", "png", "gif", "webp").contains(extension) }
    val isVideo = remember(extension) { listOf("mp4", "webm", "ogg").contains(extension) }
    val isAudio = remember(extension) { listOf("mp3", "wav", "m4a").contains(extension) }
    when {
        isImage -> Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            AsyncImage(
                model = url, contentDescription = "Image preview",
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                contentScale = ContentScale.FillWidth,
                placeholder = painterResource(R.drawable.cix_logo),
                error = painterResource(android.R.drawable.stat_notify_error)
            )
        }
        isVideo -> VideoPlayer(url = url)
        isAudio -> AudioPlayer(url = url)
    }
}

@Composable
fun VideoPlayer(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare() } }
    androidx.compose.runtime.DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(16/9f).padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.medium
    ) { AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } }, modifier = Modifier.fillMaxSize()) }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AudioPlayer(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare() } }
    androidx.compose.runtime.DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.medium
    ) { AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true; showController(); controllerHideOnTouch = false } }, modifier = Modifier.fillMaxWidth().height(100.dp)) }
}

fun extractUrls(text: String): List<String> {
    val urlPattern = Regex("https?://[\\w:#@%/;$()~_?+\\-=.\u0026!*]+")
    return urlPattern.findAll(text).map { it.value }.toList()
}

fun linkify(text: String, currentForum: String = "", currentTopic: String = ""): AnnotatedString {
    return buildAnnotatedString {
        val urlPattern = Regex("https?://[\\w:#@%/;$()~_?+\\-=.\u0026!*]+")
        val cixRefFullPattern = Regex("cix:([\\w+]+)/([\\w+]+):(\\d+)")
        val cixRefShortPattern = Regex("cix:(\\d+)")
        var lastIndex = 0
        val allMatches = (urlPattern.findAll(text).map { it to "URL" } + 
                          cixRefFullPattern.findAll(text).map { it to "CIX_FULL" } + 
                          cixRefShortPattern.findAll(text).map { it to "CIX_SHORT" })
                         .sortedBy { it.first.range.first }

        allMatches.forEach { (match, type) ->
            if (match.range.first >= lastIndex) {
                append(text.substring(lastIndex, match.range.first))
                val value = match.value
                when (type) {
                    "URL" -> { 
                        pushStringAnnotation(tag = "URL", annotation = value)
                        withStyle(style = SpanStyle(color = Color(0xFFD0BCFF), textDecoration = TextDecoration.Underline)) { append(value) }
                        pop() 
                    }
                    "CIX_FULL" -> { 
                        pushStringAnnotation(tag = "CIX_REF", annotation = "${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}")
                        withStyle(style = SpanStyle(color = Color(0xFFD0BCFF), fontWeight = FontWeight.Bold)) { append(value) }
                        pop() 
                    }
                    "CIX_SHORT" -> { 
                        if (currentForum.isNotEmpty() && currentTopic.isNotEmpty()) { 
                            pushStringAnnotation(tag = "CIX_REF", annotation = "$currentForum:$currentTopic:${match.groupValues[1]}")
                            withStyle(style = SpanStyle(color = Color(0xFFD0BCFF), fontWeight = FontWeight.Bold)) { append(value) }
                            pop() 
                        } else { 
                            append(value) 
                        } 
                    }
                }
                lastIndex = match.range.last + 1
            }
        }
        append(text.substring(lastIndex))
    }
}
