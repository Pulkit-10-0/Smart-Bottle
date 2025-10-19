// DateTimeUtils.kt
package com.example.smartbottle.util

import android.os.Build
import androidx.annotation.RequiresApi
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DateTimeUtils {
    @RequiresApi(Build.VERSION_CODES.O)
    private val displayFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")
    @RequiresApi(Build.VERSION_CODES.O)
    private val sourceFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @RequiresApi(Build.VERSION_CODES.O)
    fun parseToEpochSeconds(ts: String): Long {
        val dateTime = LocalDateTime.parse(ts, sourceFormatter)
        return dateTime.atZone(ZoneId.systemDefault()).toEpochSecond()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun epochToDisplay(epochSeconds: Long): String {
        val dt = LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneId.systemDefault().rules.getOffset(LocalDateTime.now()))
        return dt.format(displayFormatter)
    }
}
