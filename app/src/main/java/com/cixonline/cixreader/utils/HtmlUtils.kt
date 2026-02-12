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
        // HtmlCompat.fromHtml parses the input as HTML source, which collapses 
        // whitespace (including newlines) into single spaces.
        // To preserve them, we replace them with a temporary marker before decoding.
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
        val t = normalizeName(topicName).lowercase()
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
     * The CIX API requires ampersands to be replaced with "+and+".
     * We keep the '+' characters unencoded as required by the CIX API.
     */
    fun cixEncode(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        // First decode any existing HTML entities (like &amp;)
        val decoded = decodeHtml(text)
        // CIX API specific replacement for ampersands
        val withAnd = decoded.replace("&", "+and+")
        // Standard URL encode but allow '+' to remain for "+and+"
        return Uri.encode(withAnd, "+")
    }

    /**
     * Specifically encodes a CIX category name for use in a URL path.
     */
    fun cixCategoryEncode(text: String?): String {
        return cixEncode(text)
    }

    /**
     * Encodes a filename for CIX attachment.
     * CIX filenames typically replace spaces with underscores and remove other special characters.
     */
    fun encodeFilename(filename: String?): String {
        if (filename.isNullOrBlank()) return ""
        return filename.replace(" ", "_")
            .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }
}
