package com.dan.lightiss

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Display formatting helpers, kept free of Android dependencies for testing. */
object IssFormatting {

    fun clockTime(timestampSec: Long, timeZone: TimeZone): String {
        val time = toLocalTime(timestampSec, timeZone)
        val hour = time.hour.toString().padStart(2, '0')
        val minute = time.minute.toString().padStart(2, '0')
        return "$hour:$minute"
    }

    /**
     * "14:32" for today, "TOMORROW 14:32" for tomorrow, otherwise the
     * three-letter weekday, e.g. "TUE 14:32".
     */
    fun dayAndTime(timestampSec: Long, timeZone: TimeZone, nowSec: Long): String {
        val time = toLocalTime(timestampSec, timeZone)
        val today = toLocalTime(nowSec, timeZone).date
        val tomorrow = toLocalTime(nowSec + 86_400L, timeZone).date
        return when (time.date) {
            today -> clockTime(timestampSec, timeZone)
            tomorrow -> "TOMORROW ${clockTime(timestampSec, timeZone)}"
            else -> "${weekdayShort(time)} ${clockTime(timestampSec, timeZone)}"
        }
    }

    fun weekdayShort(time: LocalDateTime): String =
        time.dayOfWeek.name.substring(0, 3)

    fun ageLabel(seconds: Long): String = when {
        seconds < 60L -> "now"
        else -> "${seconds / 60L} min ago"
    }

    fun durationLabel(seconds: Long): String =
        "${maxOf(1L, Math.round(seconds / 60.0))} min"

    fun formatKm(km: Double): String = formatThousands(Math.round(km).toLong()) + " km"

    fun formatKmh(kmh: Double): String = formatThousands(Math.round(kmh).toLong()) + " km/h"

    fun formatThousands(value: Long): String {
        val digits = value.toString()
        val negative = digits.startsWith("-")
        val body = if (negative) digits.substring(1) else digits
        val builder = StringBuilder()
        var count = 0
        for (index in body.length - 1 downTo 0) {
            builder.append(body[index])
            count++
            if (count % 3 == 0 && index > 0) {
                builder.append(',')
            }
        }
        val grouped = builder.reverse().toString()
        return if (negative) "-$grouped" else grouped
    }

    fun passQuality(pass: IssPass): String {
        val minutes = pass.durationSec / 60.0
        return when {
            pass.maxElevationDeg >= 45.0 || minutes >= 8.0 -> "long"
            pass.maxElevationDeg >= 20.0 || minutes >= 5.0 -> "medium"
            else -> "short"
        }
    }

    private fun toLocalTime(timestampSec: Long, timeZone: TimeZone): LocalDateTime =
        Instant.fromEpochSeconds(timestampSec).toLocalDateTime(timeZone)
}
