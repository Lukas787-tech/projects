package com.lukas.jarvis.brief

import com.lukas.jarvis.control.Agenda
import com.lukas.jarvis.control.Appointment
import com.lukas.jarvis.control.Device
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.maps.Locator
import com.lukas.jarvis.maps.PlacesClient
import com.lukas.jarvis.web.Forecast
import com.lukas.jarvis.web.Weather
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One snapshot of the day: weather, what is due, what is left in the budget,
 * and how the phone itself is doing.
 *
 * It exists once and is read twice — the assistant speaks it through the
 * `briefing` tool, and the dashboard draws it — so the spoken answer and the
 * screen can never be describing different mornings. The pieces are gathered
 * concurrently because two of them are network calls and a brief that takes
 * eight seconds is a brief nobody asks for twice.
 */
data class DayBrief(
    val greeting: String,
    val dateLine: String,
    val forecast: Forecast?,
    val placeName: String?,
    val dueToday: List<Task>,
    val overdue: List<Task>,
    val appointments: List<Appointment>,
    val trackers: List<TrackerStatus>,
    val battery: String,
    val connection: String
) {

    /** The version that gets read out. Prose, no lists, no headings. */
    fun speak(): String = buildString {
        append(greeting).append(". ").append(dateLine).append(".")

        forecast?.let { f ->
            append(" ${f.now.description}, ${f.now.temperature.roundToInt()} degrees")
            placeName?.let { append(" in $it") }
            if (f.now.precipitationChance >= 40) {
                append(", ${f.now.precipitationChance} percent chance of rain")
            }
            append(".")
        }

        if (overdue.isNotEmpty()) {
            append(" ${overdue.size} thing${plural(overdue.size)} overdue")
            overdue.firstOrNull()?.let { append(", starting with ${it.title}") }
            append(".")
        }

        when {
            dueToday.isEmpty() && appointments.isEmpty() ->
                append(" Nothing is due today and the calendar is clear.")
            else -> {
                if (dueToday.isNotEmpty()) {
                    append(" ${dueToday.size} task${plural(dueToday.size)} due today")
                    append(": ${dueToday.take(3).joinToString(", ") { it.title }}.")
                }
                appointments.firstOrNull()?.let { next ->
                    append(" Next up, ${next.title} at ${TimeUtil.formatTime(next.startsAt)}.")
                }
            }
        }

        // Only the trackers with a number worth hearing: a list of every
        // tracker turns a brief into a bank statement.
        val notable = trackers.filter { it.balance != null || it.budgetLeft != null }.take(2)
        notable.forEach { status ->
            val t = status.tracker
            status.balance?.let { append(" ${t.label}: ${round(it)} ${t.unit} left.") }
                ?: status.budgetLeft?.let {
                    append(
                        if (it >= 0) {
                            " ${round(it)} ${t.unit} of the ${t.label} budget left " +
                                "this ${Tracker.periodWord(t.period)}."
                        } else {
                            " You are ${round(-it)} ${t.unit} over on ${t.label}."
                        }
                    )
                }
        }

        if (battery.contains("charger")) append(" ").append(battery)
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"

    private fun round(value: Double): String =
        if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
}

class Briefer(
    private val brain: Brain,
    private val agenda: Agenda,
    private val weather: Weather,
    private val locator: Locator,
    private val places: PlacesClient,
    private val device: Device
) {

    suspend fun build(settings: Settings): DayBrief = coroutineScope {
        val now = System.currentTimeMillis()

        // The network half goes first and runs while SQLite is read.
        val sky = async {
            if (!settings.weatherEnabled) {
                null
            } else {
                val here = locator.current() ?: return@async null
                val name = runCatching { places.describe(here) }.getOrNull()
                    ?.split(",")?.firstOrNull()?.trim()
                weather.at(here, name ?: "Here", days = 3)?.let { it to name }
            }
        }

        val endOfDay = endOfToday()
        val open = runCatching { brain.tasks(includeDone = false, limit = 100) }
            .getOrDefault(emptyList())
        val trackers = runCatching { brain.allTrackerStatus() }.getOrDefault(emptyList())
        val appointments = if (settings.calendarEnabled) {
            runCatching { agenda.between(now, endOfDay, 5) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val resolved = sky.await()

        DayBrief(
            greeting = greeting(settings.userName),
            dateLine = TimeUtil.format(now),
            forecast = resolved?.first,
            placeName = resolved?.second,
            dueToday = open.filter { it.dueAt != null && it.dueAt in now..endOfDay },
            overdue = open.filter { it.dueAt != null && it.dueAt < now },
            appointments = appointments,
            trackers = trackers,
            battery = device.battery(),
            connection = device.connection()
        )
    }

    private fun greeting(userName: String): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (hour) {
            in 0..4 -> "Still up"
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
        return if (userName.isBlank()) part else "$part, $userName"
    }

    private fun endOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
