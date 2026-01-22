package com.cixonline.cixreader.utils

import android.net.Uri
import androidx.core.text.HtmlCompat

object HtmlUtils {
    /**
     * Decodes HTML entities in the given string.
     */
    fun decodeHtml(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    }

    /**
     * URL encodes a string for use in a path segment.
     */
    fun urlEncode(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return Uri.encode(text)
    }

    /**
     * Specifically encodes a CIX category name for use in a URL path.
     * The CIX API requires ampersands to be replaced with "+and+".
     */
    fun cixCategoryEncode(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        // First decode any existing HTML entities (like &amp;)
        val decoded = decodeHtml(text)
        // CIX API specific replacement for ampersands
        val safe = decoded.replace("&", "+and+")
        // Then standard URL encode for the rest of the path segment
        return Uri.encode(safe)
    }
}
