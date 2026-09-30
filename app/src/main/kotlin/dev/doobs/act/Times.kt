package dev.doobs.act

import android.content.Context
import android.text.format.DateFormat
import dev.doobs.R
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object Times {

    /** Respects the phone's 12/24-hour setting, because the confirmation is read back to a human. */
    fun format(context: Context, time: LocalTime): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    fun describe(context: Context, duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        val seconds = duration.seconds % 60
        val parts = buildList {
            if (hours > 0) add(context.resources.getQuantityString(R.plurals.hours, hours.toInt(), hours))
            if (minutes > 0) add(context.resources.getQuantityString(R.plurals.minutes, minutes.toInt(), minutes))
            if (hours == 0L && minutes == 0L && seconds > 0) {
                add(context.resources.getQuantityString(R.plurals.seconds, seconds.toInt(), seconds))
            }
        }
        return parts.joinToString(" ")
    }
}
