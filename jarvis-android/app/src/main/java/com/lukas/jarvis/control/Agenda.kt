package com.lukas.jarvis.control

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.lukas.jarvis.core.TimeUtil

/** One entry out of the phone's calendars. */
data class Appointment(
    val title: String,
    val startsAt: Long,
    val endsAt: Long,
    val location: String?,
    val allDay: Boolean
)

/**
 * The calendar, read only.
 *
 * CalendarContract.Instances is used rather than Events because it expands
 * repeats: a weekly stand-up is one Event row and fifty-two instances, and the
 * question "what is on today" is always about the instances.
 */
class Agenda(context: Context) {

    private val app = context.applicationContext

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun between(from: Long, to: Long, limit: Int = 12): List<Appointment> {
        if (!hasPermission) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { builder ->
            ContentUris.appendId(builder, from)
            ContentUris.appendId(builder, to)
            builder.build()
        }
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )
        val out = ArrayList<Appointment>()
        runCatching {
            app.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && out.size < limit) {
                    out.add(
                        Appointment(
                            title = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)",
                            startsAt = cursor.getLong(1),
                            endsAt = cursor.getLong(2),
                            location = cursor.getString(3)?.takeIf { it.isNotBlank() },
                            allDay = cursor.getInt(4) == 1
                        )
                    )
                }
            }
        }
        return out
    }

    /** The next [days] days, as the assistant would say them. */
    fun describe(days: Int = 1, limit: Int = 12): String {
        if (!hasPermission) {
            return "I need the calendar permission before I can read your schedule. " +
                "Grant it in Android settings under Jarvis."
        }
        val now = System.currentTimeMillis()
        val until = now + days.coerceIn(1, 30) * 86_400_000L
        val events = between(now, until, limit)
        if (events.isEmpty()) {
            return if (days <= 1) "Nothing on the calendar for the next day." else
                "Nothing on the calendar for the next $days days."
        }
        return events.joinToString("\n") { event ->
            val when_ = if (event.allDay) {
                "${TimeUtil.formatDate(event.startsAt)} (all day)"
            } else {
                "${TimeUtil.format(event.startsAt)} (${TimeUtil.relative(event.startsAt)})"
            }
            "- ${event.title} — $when_" + (event.location?.let { ", at $it" } ?: "")
        }
    }

    /** The one-line version the dashboard and the morning brief both use. */
    fun next(): Appointment? =
        between(System.currentTimeMillis(), System.currentTimeMillis() + 7 * 86_400_000L, 1)
            .firstOrNull()
}
