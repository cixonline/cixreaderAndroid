package com.cixonline.cixreader.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

object DateUtils {
    private const val TAG = "DateUtils"

    fun parseCixDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        
        // Remove " at " if it exists (some display formats use it)
        val cleanedDateStr = dateStr.replace(" at ", " ")

        val formats = arrayOf(
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "dd MMM yyyy HH:mm:ss",
            "dd MMM yyyy HH:mm",
            "MM/dd/yyyy HH:mm:ss",
            "MM/dd/yyyy HH:mm",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "dd/MM/yyyy hh:mm:ss a",
            "dd/MM/yyyy hh:mm a"
        )
        
        for (format in formats) {
            try {
                val parser = SimpleDateFormat(format, Locale.US)
                if (format.endsWith("'Z'") || format.endsWith("X") || format.endsWith("Z")) {
                    parser.timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = parser.parse(cleanedDateStr)
                if (date != null) return date.time
            } catch (e: Exception) {
                // ignore
            }
        }
        
        Log.w(TAG, "Failed to parse date string: $dateStr")
        return 0L
    }

    fun formatDateTime(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val sdf = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    fun formatCixDate(dateStr: String?): String {
        if (dateStr.isNullOrBlank()) return ""
        val timestamp = parseCixDate(dateStr)
        if (timestamp == 0L) {
             // If parsing failed but string is not empty, return the original string
             return dateStr
        }
        return formatDateTime(timestamp)
    }

    /**
     * Formats a timestamp for the CIX API 'since' parameter.
     * CIX usually expects dates in yyyy-MM-dd HH:mm:ss format for this.
     */
    fun formatApiDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        // CIX API dates are typically in UTC/GMT
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timestamp))
    }
}
