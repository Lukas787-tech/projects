package com.lukas.jarvis.control

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.lukas.jarvis.core.TimeUtil
import java.util.TimeZone

/** One entry out of the phone's calendars. */
data class Appointment(
    val title: String,
    val startsAt: Long,
    val endsAt: Long,
    val location: String?,
    val allDay: Boolean
)

/**
 * The calendar: read, and written when the user has allowed it.
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

    val canWrite: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Writes an event straight into the user's main calendar, with a reminder
     * a quarter of an hour before. Null when that cannot be done — no
     * permission, no calendar that takes new events — so the caller can hand
     * the event to the calendar app instead.
     */
    fun insert(title: String, start: Long, end: Long, location: String?, description: String?): String? {
        if (!canWrite || !hasPermission) return null
        val calendar = primaryCalendar() ?: return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (!description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, description)
        }
        val uri = runCatching {
            app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.getOrNull() ?: return null
        val eventId = ContentUris.parseId(uri)
        runCatching {
            app.contentResolver.insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, 15)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
            )
        }
        val place = location?.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
        return "Added to your calendar: '$title'$place, ${TimeUtil.format(start)}, with a reminder 15 minutes before."
    }

    /**
     * The calendar new events belong in: the account's primary one if the
     * provider marks it, otherwise the first visible calendar that accepts
     * events — never a read-only one such as holidays or birthdays.
     */
    private fun primaryCalendar(): Long? = runCatching {
        app.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.ACCOUNT_TYPE
            ),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null
        )?.use { cursor ->
            val found = ArrayList<Triple<Long, Boolean, String>>()
            while (cursor.moveToNext()) {
                found += Triple(cursor.getLong(0), cursor.getInt(1) == 1, cursor.getString(2).orEmpty())
            }
            (found.firstOrNull { it.second } ?: found.firstOrNull { it.third == "com.google" } ?: found.firstOrNull())?.first
        }
    }.getOrNull()

    /**
     * Moves or cancels an upcoming appointment found by its title, in the
     * next [daysAhead] days. Only single events are changed here: moving one
     * meeting out of a weekly series means deciding about the rest, which
     * belongs in the calendar app, so those are handed over by name.
     */
    fun change(title: String, newStart: Long?, cancel: Boolean, daysAhead: Int = 60): String {
        if (!hasPermission) return "Calendar access is off, so I cannot see the appointment."
        if (!canWrite) return "I can read the calendar but not change it; allow calendar access fully and ask again."
        val wanted = title.trim().lowercase()
        if (wanted.isBlank()) return "Which appointment?"
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { builder ->
            ContentUris.appendId(builder, now - 60 * 60 * 1000L)
            ContentUris.appendId(builder, now + daysAhead * 24L * 60 * 60 * 1000)
            builder.build()
        }
        data class Hit(val eventId: Long, val title: String, val begin: Long, val end: Long, val repeating: Boolean)
        val hits = ArrayList<Hit>()
        runCatching {
            app.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.RRULE
                ),
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    if (name.lowercase().contains(wanted) || wanted.contains(name.lowercase().ifBlank { "\u0000" })) {
                        hits += Hit(c.getLong(0), name, c.getLong(2), c.getLong(3), !c.getString(4).isNullOrBlank())
                    }
                }
            }
        }
        val hit = hits.firstOrNull() ?: return "Nothing called '$title' is on the calendar in the next $daysAhead days."
        if (hit.repeating) {
            return "'${hit.title}' is part of a repeating series; change it in the calendar app so the rest " +
                "of the series is handled the way you want."
        }
        val event = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, hit.eventId)
        return runCatching {
            if (cancel) {
                app.contentResolver.delete(event, null, null)
                "Cancelled '${hit.title}' on ${TimeUtil.format(hit.begin)}."
            } else {
                val start = newStart ?: return "When should it move to?"
                val length = (hit.end - hit.begin).coerceAtLeast(15 * 60 * 1000L)
                app.contentResolver.update(
                    event,
                    ContentValues().apply {
                        put(CalendarContract.Events.DTSTART, start)
                        put(CalendarContract.Events.DTEND, start + length)
                    },
                    null,
                    null
                )
                "Moved '${hit.title}' to ${TimeUtil.format(start)}."
            }
        }.getOrElse { "The calendar would not let me change '${hit.title}'." }
    }

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
