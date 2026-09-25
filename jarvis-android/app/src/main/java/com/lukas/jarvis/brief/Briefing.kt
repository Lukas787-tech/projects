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
    val connection: String,
    /** The top stories, when the web is switched on. */
    val headlines: List<com.lukas.jarvis.web.Headline> = emptyList(),
    /** Whether the calendar is read at all; off, an empty day is unknown, not clear. */
    val calendarOn: Boolean = true
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
                append(if (calendarOn) " Nothing is due today and the calendar is clear." else " Nothing is due today.")
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

        headlines.firstOrNull()?.let { top ->
            append(" In the news: ${top.title}.")
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
    private val device: Device,
    private val knowledge: com.lukas.jarvis.web.Knowledge? = null
) {

    companion object {
        /**
         * "Good afternoon, Lukas", for the hour it is now. Worked out when it
         * is shown rather than kept with the brief, which can be hours old
         * and made before the user's name was known.
         */
        fun greeting(
            address: String,
            hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        ): String {
            val part = when (hour) {
                in 0..4 -> "Still up"
                in 5..11 -> "Good morning"
                in 12..17 -> "Good afternoon"
                else -> "Good evening"
            }
            return if (address.isBlank()) part else "$part, $address"
        }
    }

    /**
     * The town the phone was last found in, kept from the last brief so every
     * turn can know roughly where it is without asking for a location fix.
     */
    @Volatile
    var lastPlace: String? = null
        private set

    suspend fun build(settings: Settings): DayBrief = coroutineScope {
        val now = System.currentTimeMillis()

        // The network half goes first and runs while SQLite is read.
        val sky = async {
            if (!settings.weatherEnabled) {
                null
            } else {
                val here = locator.current() ?: return@async null
                val name = runCatching { places.town(here) }.getOrNull()
                weather.at(here, name ?: "Here", days = 3)?.let { it to name }
            }
        }

        val news = async {
            if (!settings.webSearchEnabled || knowledge == null) emptyList()
            else runCatching { knowledge.headlines(3) }.getOrDefault(emptyList())
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
        resolved?.second?.let { lastPlace = it }

        DayBrief(
            greeting = greeting(com.lukas.jarvis.llm.Personas.address(settings)),
            dateLine = TimeUtil.format(now),
            forecast = resolved?.first,
            placeName = resolved?.second,
            dueToday = open.filter { it.dueAt != null && it.dueAt in now..endOfDay },
            overdue = open.filter { it.dueAt != null && it.dueAt < now },
            appointments = appointments,
            trackers = trackers,
            battery = device.battery(),
            connection = device.connection(),
            headlines = news.await(),
            calendarOn = settings.calendarEnabled
        )
    }

    /**
     * The evening's look back and ahead, as a title and a paragraph: what got
     * done and spent today, what is still open, and what tomorrow holds — its
     * first appointment, what is due, and its weather.
     */
    suspend fun evening(settings: Settings): Pair<String, String> = coroutineScope {
        val now = System.currentTimeMillis()
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val tomorrowStart = endOfToday() + 1000
        val tomorrowEnd = tomorrowStart + 24 * 60 * 60 * 1000L - 1

        val sky = async {
            if (!settings.weatherEnabled) null else {
                val here = locator.current() ?: return@async null
                weather.at(here, "Here", days = 2)?.days?.getOrNull(1)
            }
        }

        val all = runCatching { brain.tasks(includeDone = true, limit = 300) }.getOrDefault(emptyList())
        val doneToday = all.filter { it.done && (it.completedAt ?: 0L) >= dayStart }
        val stillOpen = all.filter { !it.done && it.dueAt != null && it.dueAt <= endOfToday() }
        val dueTomorrow = all.filter { !it.done && it.dueAt != null && it.dueAt in tomorrowStart..tomorrowEnd }
        val firstTomorrow = if (settings.calendarEnabled) {
            runCatching { agenda.between(tomorrowStart, tomorrowEnd, 1) }.getOrDefault(emptyList()).firstOrNull()
        } else null

        val trackers = runCatching { brain.allTrackers() }.getOrDefault(emptyList()).associateBy { it.id }
        val spent = runCatching { brain.entriesBetween(dayStart, now) }.getOrDefault(emptyList())
            .filter { it.direction == com.lukas.jarvis.data.Entry.DIR_OUT && trackers[it.trackerId]?.kind == Tracker.KIND_MONEY }
            .groupBy { trackers[it.trackerId]?.unit.orEmpty() }
            .mapValues { (_, entries) -> entries.sumOf { it.amount } }

        val tomorrowSky = sky.await()
        val address = com.lukas.jarvis.llm.Personas.address(settings)
        val title = if (address.isBlank()) "Good evening" else "Good evening, $address"
        val text = buildString {
            if (doneToday.isNotEmpty()) {
                append("Done today: ${doneToday.take(4).joinToString { it.title }}")
                if (doneToday.size > 4) append(" and ${doneToday.size - 4} more")
                append(". ")
            }
            if (spent.isNotEmpty()) {
                append("Spent ")
                append(spent.entries.joinToString(" and ") { (unit, sum) -> "${round(sum)} $unit".trim() })
                append(" today. ")
            }
            if (stillOpen.isNotEmpty()) {
                append("Still open: ${stillOpen.take(3).joinToString { it.title }}. ")
            }
            append("Tomorrow: ")
            val parts = buildList {
                firstTomorrow?.let { add("${it.title} at ${TimeUtil.formatTime(it.startsAt)}") }
                if (dueTomorrow.isNotEmpty()) {
                    add("${dueTomorrow.size} thing${if (dueTomorrow.size == 1) "" else "s"} due — " +
                        dueTomorrow.take(3).joinToString { it.title })
                }
                tomorrowSky?.let {
                    add("${it.description.lowercase(Locale.ROOT)}, ${it.low.roundToInt()}° to ${it.high.roundToInt()}°" +
                        if (it.precipitationChance >= 40) ", ${it.precipitationChance}% rain" else "")
                }
            }
            append(if (parts.isEmpty()) "nothing planned yet." else parts.joinToString("; ") + ".")
        }.trim()
        title to text
    }

    private fun round(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.US, "%.2f", value)

    private fun greeting(userName: String): String = Briefer.greeting(userName)

    private fun endOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
