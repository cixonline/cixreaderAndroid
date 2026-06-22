package com.cixonline.cixreader.utils

import android.net.Uri
import androidx.core.text.HtmlCompat

object HtmlUtils {
    /**
     * Decodes HTML entities in the given string while preserving newlines.
     */
    fun decodeHtml(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        
        // CIX messages are often plain text with HTML entities.
        val marker = "___NEWLINE_MARKER___"
        val withMarkers = text.replace("\r\n", marker)
            .replace("\r", marker)
            .replace("\n", marker)
            
        val decoded = HtmlCompat.fromHtml(withMarkers, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        
        // Restore newlines
        return decoded.replace(marker, "\n")
    }

    /**
     * Cleans up CIX attachment URLs in the text (removes :80, ensures https).
     */
    fun cleanCixUrls(text: String?): String {
        if (text == null) return ""
        return text.replace(":80", "").replace("http://", "https://")
    }

    /**
     * Decodes HTML entities and trims whitespace for use in names (Forums, Topics, etc).
     */
    fun normalizeName(text: String?): String {
        return decodeHtml(text).trim()
    }

    /**
     * Returns the normalized topic name.
     */
    fun normalizeTopicName(text: String?): String {
        return normalizeName(text)
    }

    /**
     * Calculates a consistent forum ID regardless of casing or HTML encoding.
     */
    fun calculateForumId(forumName: String?): Int {
        return normalizeName(forumName).lowercase().hashCode()
    }

    /**
     * Calculates a consistent topic ID regardless of casing or HTML encoding.
     */
    fun calculateTopicId(forumName: String?, topicName: String?): Int {
        val f = normalizeName(forumName).lowercase()
        val t = normalizeTopicName(topicName).lowercase()
        return (f + t).hashCode()
    }

    /**
     * URL encodes a string for use in a path segment.
     */
    fun urlEncode(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return Uri.encode(text)
    }

    /**
     * Encodes a string for use in a CIX API path segment.
     * Note: Conferences and topics cannot have ampersands.
     */
    fun cixEncode(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val decoded = decodeHtml(text)
        return Uri.encode(decoded)
    }

    /**
     * Specifically encodes a CIX category name for use in a URL path.
     */
    fun cixCategoryEncode(text: String?): String {
        return cixEncode(text)
    }

    /**
     * Encodes a filename for CIX attachment.
     */
    fun encodeFilename(filename: String?): String {
        if (filename.isNullOrBlank()) return ""
        return filename.replace(" ", "_")
            .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }
}
