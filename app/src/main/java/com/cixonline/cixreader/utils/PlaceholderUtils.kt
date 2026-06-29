package com.cixonline.cixreader.utils

import kotlin.math.abs

object PlaceholderUtils {
    private val LOREM_IPSUM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum. "
    
    private val PLACEHOLDER_USERNAMES = listOf(
        "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet",
        "kilo", "lima", "mike", "november", "oscar", "papa", "quebec", "romeo", "sierra", "tango",
        "uniform", "victor", "whiskey", "x-ray", "yankee", "zulu"
    )

    fun getPlaceholderText(original: String, maxLength: Int = Int.MAX_VALUE): String {
        if (original.isEmpty()) return ""
        
        val targetLength = minOf(original.length, maxLength)
        val sb = StringBuilder()
        val startOffset = abs(original.hashCode()) % LOREM_IPSUM.length
        
        var currentOffset = startOffset
        while (sb.length < targetLength) {
            val remainingInLorem = LOREM_IPSUM.length - currentOffset
            val toAppend = minOf(remainingInLorem, targetLength - sb.length)
            sb.append(LOREM_IPSUM.substring(currentOffset, currentOffset + toAppend))
            currentOffset = (currentOffset + toAppend) % LOREM_IPSUM.length
        }
        
        val result = sb.toString().trim()
        return if (original.length > result.length && maxLength > result.length) "$result..." else result
    }

    fun getPlaceholderUsername(username: String): String {
        if (username.isEmpty()) return ""
        val index = abs(username.hashCode()) % PLACEHOLDER_USERNAMES.size
        // Usernames should be entirely lower case as requested
        return PLACEHOLDER_USERNAMES[index].lowercase()
    }
}
