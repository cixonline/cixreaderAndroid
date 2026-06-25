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
     * Reflows text that has hard carriage returns at around 80 columns.
     * Preserves double newlines as paragraph breaks.
     * Attempts to preserve lists, quotes, and indented text.
     * Heuristics are used to avoid breaking long URLs that have been hard-wrapped.
     */
    fun reflowText(text: String): String {
        if (text.isEmpty()) return ""
        
        // Ensure consistent newlines for processing
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")
        if (lines.size <= 1) return normalized
        
        val result = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val currentLine = lines[i]
            val trimmedCurrent = currentLine.trimEnd()
            
            if (trimmedCurrent.isEmpty()) {
                result.append("\n")
                i++
                continue
            }
            
            // Heuristic: lines starting with space, tab, or > are probably pre-formatted or quotes
            val isIndented = currentLine.startsWith(" ") || currentLine.startsWith("\t")
            val isQuote = currentLine.trimStart().startsWith(">")
            
            if (isIndented || isQuote) {
                result.append(currentLine).append("\n")
                i++
                continue
            }

            result.append(trimmedCurrent)
            
            if (i + 1 < lines.size) {
                val nextLine = lines[i + 1]
                val trimmedNext = nextLine.trimEnd()
                
                val nextIsIndented = nextLine.startsWith(" ") || nextLine.startsWith("\t")
                val nextIsQuote = nextLine.trimStart().startsWith(">")
                val nextIsList = trimmedNext.startsWith("* ") || trimmedNext.startsWith("- ") || 
                                 Regex("^\\d+[.)]\\s+").containsMatchIn(trimmedNext)
                val nextIsEmpty = trimmedNext.isEmpty()
                
                // Heuristic for sentence termination
                val endsWithTerminator = trimmedCurrent.endsWith(".") || 
                                       trimmedCurrent.endsWith("!") || 
                                       trimmedCurrent.endsWith("?") ||
                                       trimmedCurrent.endsWith(":") ||
                                       trimmedCurrent.endsWith(";")
                
                // Hard wrap heuristic: line is long (CIX wraps at 80), next isn't a structural break.
                if (!nextIsEmpty && !nextIsIndented && !nextIsQuote && !nextIsList && 
                    trimmedCurrent.length > 65 && !endsWithTerminator) {
                    
                    // If the original line ended with whitespace, it was a clean break.
                    if (currentLine.endsWith(" ") || currentLine.endsWith("\t")) {
                        result.append(" ")
                    } else {
                        // Check if the last "word" looks like a URL or a very long word being broken.
                        val lastSpace = trimmedCurrent.lastIndexOf(' ')
                        val lastWordLength = if (lastSpace == -1) trimmedCurrent.length else trimmedCurrent.length - lastSpace - 1
                        
                        if (lastWordLength > 15 || trimmedCurrent.contains("http")) {
                            // Likely a URL or long word continuation, join without adding a space.
                        } else {
                            // Likely a regular word wrap, add a space to separate words.
                            result.append(" ")
                        }
                    }
                } else {
                    result.append("\n")
                }
            } else {
                result.append("\n")
            }
            i++
        }
        
        return result.toString().trimEnd()
    }

    /**
     * Decodes HTML, cleans CIX URLs, and reflows hard-wrapped text.
     */
    fun formatMessageBody(text: String?): String {
        if (text == null) return ""
        val decoded = decodeHtml(text)
        val cleaned = cleanCixUrls(decoded)
        return reflowText(cleaned)
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
