package com.lukas.jarvis.llm

import com.lukas.jarvis.auto.Routine
import com.lukas.jarvis.auto.Routines
import com.lukas.jarvis.brief.Briefer
import com.lukas.jarvis.control.Agenda
import com.lukas.jarvis.control.Caller
import com.lukas.jarvis.control.Chat
import com.lukas.jarvis.control.Chats
import com.lukas.jarvis.control.Device
import com.lukas.jarvis.control.Launcher
import com.lukas.jarvis.control.Messenger
import com.lukas.jarvis.control.People
import com.lukas.jarvis.control.Phone
import com.lukas.jarvis.control.ScreenReader
import com.lukas.jarvis.core.Calculator
import com.lukas.jarvis.core.DateMath
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.core.Units
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.data.Money
import com.lukas.jarvis.auto.Trigger
import com.lukas.jarvis.data.Recurring
import com.lukas.jarvis.moment.Bar
import com.lukas.jarvis.moment.Chart
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.maps.Locator
import com.lukas.jarvis.maps.Navigator
import com.lukas.jarvis.maps.PlacesClient
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.stage.StageStore
import com.lukas.jarvis.notify.ReplyListener
import com.lukas.jarvis.notify.Reminders
import com.lukas.jarvis.web.Currency
import com.lukas.jarvis.web.Home
import com.lukas.jarvis.web.Imagine
import com.lukas.jarvis.web.Knowledge
import com.lukas.jarvis.web.Weather
import com.lukas.jarvis.vision.CameraBus
import com.lukas.jarvis.web.WebTools
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Reminders, plans, countdowns and trips.

internal fun Tools.taskTools(): List<JSONObject> = listOf(
    tool(
        "make_plan",
        "Lay out a plan as one card: 'plan my Saturday', 'what should I do this afternoon', " +
            "'plan the trip to Leipzig'. Look up what it needs first (calendar, weather, places, " +
            "routes), then call this once. Nothing in it happens until the user says to.",
        props(
            "title" to str("A short name for it: 'Saturday', 'This afternoon', 'Leipzig trip'."),
            "steps" to arr("3 to 8 steps in order, each one short line, starting with a time when there is one: '10:00 Market on Boxhagener Platz'.")
        ),
        listOf("title", "steps")
    ),
    tool(
        "plan_trip",
        "Get a trip ready in one go: a countdown, its own packing list for the length and the " +
            "weather there, and the trip remembered. Use for 'I'm going to Lisbon on the 14th for " +
            "five days', 'help me get ready for my trip to Rome next week'.",
        props(
            "destination" to str("Where, e.g. 'Lisbon'."),
            "start" to str("The first day, ISO date, e.g. '2026-10-14'."),
            "end" to str("The last day, ISO date. Or give nights."),
            "nights" to int("How many nights, when no end is given.")
        ),
        listOf("destination", "start")
    ),
    tool(
        "countdown",
        "Days worth counting down to, kept and shown on Today: a holiday, an exam, a wedding, " +
            "and birthdays and anniversaries that come every year. 'My holiday starts on 12 October', " +
            "'Mum's birthday is 3 March 1966', 'how long until the holiday', 'what's coming up', " +
            "'forget the exam'. For a one-off question with nothing to keep, use `date_calc`.",
        props(
            "action" to str("add, list, check (one by name), or remove.", listOf("add", "list", "check", "remove")),
            "name" to str("What it is: 'Holiday', 'Mum', 'Exam'. For a birthday, the person's name."),
            "date" to str("The day: 'YYYY-MM-DD', or '03-15' (month-day) when no year is known."),
            "yearly" to bool("True when it comes back every year: birthdays, anniversaries."),
            "birthday" to bool("True for a person's birthday.")
        ),
        listOf("action")
    ),
    tool(
        "add_task",
        "Add a task or reminder. The phone will notify at the due time.",
        props(
            "title" to str("What to do."),
            "due" to str("ISO local time such as '2026-09-17T09:00', or a relative value like '+2h'."),
            "repeat" to str("How often it repeats.", Task.ALL_REPEATS),
            "notes" to str("Extra detail.")
        ),
        listOf("title")
    ),
    tool(
        "list_tasks",
        "List tasks. Open ones by default.",
        props("include_done" to bool("Include completed tasks too.")),
        emptyList()
    ),
    tool(
        "complete_task",
        "Mark a task done by its id.",
        props("id" to int("The task id shown by list_tasks.")),
        listOf("id")
    ),
    tool(
        "update_task",
        "Change a task that already exists: move it to a new time, snooze it, rename it, " +
            "or change how it repeats. 'Move the dentist to Friday', 'remind me again in " +
            "ten minutes'. The alarm moves with it.",
        props(
            "id" to int("The task id shown by list_tasks or in the context block."),
            "title" to str("A new title, if it should change."),
            "due" to str("The new time: ISO local time, or relative like '+10m' to snooze. 'none' clears it."),
            "repeat" to str("How often it repeats.", Task.ALL_REPEATS),
            "notes" to str("Replacement notes.")
        ),
        listOf("id")
    ),
    tool(
        "delete_task",
        "Remove a task entirely, with its alarm. Use when the user cancels a plan or says " +
            "a reminder is no longer needed; use complete_task when it was done.",
        props("id" to int("The task id.")),
        listOf("id")
    )
)

internal fun Tools.addTask(args: JSONObject, effects: ToolEffects): String {
    val title = args.optString("title").trim()
    if (title.isBlank()) return "Need a task title."
    val dueAt = TimeUtil.parse(args.optString("due").takeIf { it.isNotBlank() })
    val task = Task(
        title = title,
        notes = args.optString("notes").takeIf { it.isNotBlank() },
        dueAt = dueAt,
        // A model's "every day" is not a rule; only the known ones are kept.
        repeatRule = repeatRule(args.optString("repeat"))
    )
    val id = brain.addTask(task)
    val saved = task.copy(id = id)
    if (dueAt != null) reminders.schedule(saved)
    effects.tasksChanged = true
    return if (dueAt != null) {
        "Task added (id $id): $title — due ${TimeUtil.format(dueAt)}."
    } else {
        "Task added (id $id): $title — no due date."
    }
}

internal fun Tools.repeatRule(raw: String): String {
    val key = raw.trim().lowercase(Locale.ROOT)
    return when {
        key in Task.ALL_REPEATS -> key
        key.contains("day") || key.contains("täglich") -> Task.REPEAT_DAILY
        key.contains("week") || key.contains("wöchentlich") -> Task.REPEAT_WEEKLY
        key.contains("month") || key.contains("monatlich") -> Task.REPEAT_MONTHLY
        else -> Task.REPEAT_NONE
    }
}

internal fun Tools.listTasks(args: JSONObject): String {
    val includeDone = args.optBoolean("include_done", false)
    val tasks = brain.tasks(includeDone)
    if (tasks.isEmpty()) return "No tasks."
    return tasks.joinToString("\n") { t ->
        val due = t.dueAt?.let { " — due ${TimeUtil.format(it)} (${TimeUtil.relative(it)})" }.orEmpty()
        val done = if (t.done) " [done]" else ""
        "- [id ${t.id}] ${t.title}$due$done"
    }
}

internal fun Tools.completeTask(args: JSONObject, effects: ToolEffects): String {
    val id = args.optLong("id", -1L)
    if (id <= 0) return "Need a valid task id."
    val done = brain.completeTask(id) ?: return "No task with id $id."
    effects.tasksChanged = true
    if (!done.done && done.dueAt != null) {
        reminders.schedule(done)
        return "Done for this time: ${done.title}. It repeats ${TimeUtil.repeatLabel(done.repeatRule)}; next " +
            "${TimeUtil.format(done.dueAt)}."
    }
    reminders.cancel(id)
    return "Completed: ${done.title}"
}

/**
 * Moves, renames or re-repeats a task, and moves its alarm with it.
 *
 * The alarm is cancelled and set again rather than adjusted, because a
 * PendingIntent carries the title it was made with and a renamed task should
 * not ring under its old name.
 */
internal fun Tools.updateTask(args: JSONObject, effects: ToolEffects): String {
    val id = args.optLong("id", -1L)
    if (id <= 0) return "Need a valid task id."
    val existing = brain.getTask(id) ?: return "No task with id $id."

    val dueRaw = args.optString("due").trim()
    val clearing = dueRaw.lowercase(Locale.ROOT) in setOf("none", "no", "clear", "never")
    val dueAt = when {
        dueRaw.isBlank() -> existing.dueAt
        clearing -> null
        else -> TimeUtil.parse(dueRaw)
            ?: return "I could not read '$dueRaw' as a time. Try '2026-09-24T09:00' or '+30m'."
    }
    val updated = existing.copy(
        title = args.optString("title").trim().ifBlank { existing.title },
        notes = if (args.has("notes")) args.optString("notes").takeIf { it.isNotBlank() } else existing.notes,
        dueAt = dueAt,
        repeatRule = args.optString("repeat").trim()
            .takeIf { it.isNotBlank() }?.let(::repeatRule) ?: existing.repeatRule,
        // Moving a finished task into the future means it is wanted again.
        done = if (dueAt != null && dueAt != existing.dueAt) false else existing.done,
        completedAt = if (dueAt != null && dueAt != existing.dueAt) null else existing.completedAt
    )
    brain.updateTask(updated)
    reminders.cancel(id)
    if (updated.dueAt != null && !updated.done) reminders.schedule(updated)
    effects.tasksChanged = true

    return when {
        updated.dueAt == null -> "Updated (id $id): ${updated.title} — no due time now."
        else -> "Updated (id $id): ${updated.title} — due ${TimeUtil.format(updated.dueAt)} " +
            "(${TimeUtil.relative(updated.dueAt)})."
    }
}

internal fun Tools.deleteTask(args: JSONObject, effects: ToolEffects): String {
    val id = args.optLong("id", -1L)
    if (id <= 0) return "Need a valid task id."
    val existing = brain.getTask(id) ?: return "No task with id $id."
    reminders.cancel(id)
    brain.deleteTask(id)
    effects.tasksChanged = true
    return "Removed the task: ${existing.title}."
}

/**
 * A trip, made ready in one go: a countdown on Today, its own packing list
 * for that many nights and the weather there, and the trip remembered.
 * Nothing here leaves the phone except the weather lookup.
 */
internal suspend fun Tools.planTrip(args: JSONObject, effects: ToolEffects): String {
    val today = java.time.LocalDate.now()
    fun day(key: String): java.time.LocalDate? = args.optString(key).trim().takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { java.time.LocalDate.parse(raw.take(10)) }.getOrNull()
            ?: TimeUtil.parse(raw)?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
    }
    val nights = if (args.has("nights")) args.optInt("nights", -1).takeIf { it >= 0 } else null
    val trip = com.lukas.jarvis.data.Trips.of(args.optString("destination"), day("start"), day("end"), nights)
        ?: return "A trip needs where and the first day, and the last day or how many nights."
    if (trip.end.isBefore(today)) return "That trip is already over."

    // The forecast reaches a week ahead; further off, it waits for the trip to come closer.
    val ahead = java.time.temporal.ChronoUnit.DAYS.between(today, trip.start).toInt()
    val there = if (ahead in 0..6) {
        runCatching {
            val point = navigator.locate(trip.destination)?.point ?: return@runCatching null
            val forecast = weather.at(point, trip.destination, days = 7) ?: return@runCatching null
            val during = forecast.days.drop(ahead).take(trip.nights + 1)
            if (during.isEmpty()) null else com.lukas.jarvis.data.TripWeather(
                high = during.maxOf { it.high },
                low = during.minOf { it.low },
                rainyDays = during.count { it.precipitationChance >= 50 }
            )
        }.getOrNull()
    } else null

    if (countdowns.find("${trip.destination} trip") == null) {
        countdowns.add("${trip.destination} trip", trip.start, yearly = false, knowsYear = true, birthday = false)
    }
    val packing = com.lukas.jarvis.data.Trips.packing(trip, there)
    lists.change { it.add(trip.listName, packing) }
    brain.addMemory(
        Memory(
            kind = Memory.KIND_EVENT,
            content = "Trip to ${trip.destination}, ${trip.dates} ${trip.start.year}",
            importance = 4,
            occurredAt = trip.start.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            source = "trip"
        )
    )
    effects.memoriesChanged = true
    effects.tasksChanged = true

    val nightsText = when (trip.nights) {
        0 -> "a day trip"
        1 -> "1 night"
        else -> "${trip.nights} nights"
    }
    return buildString {
        appendLine("Trip to ${trip.destination} is ready: ${trip.dates}, $nightsText.")
        appendLine(
            "- A countdown on Today: " + when (ahead) {
                0 -> "it starts today"
                1 -> "it starts tomorrow"
                else -> "$ahead days to go"
            }
        )
        appendLine("- A packing list, '${trip.listName}', with ${packing.size} things for $nightsText")
        appendLine(
            "- Weather there: " + (there?.let {
                "${it.high.roundToInt()}° by day, ${it.low.roundToInt()}° at night" +
                    if (it.rainyDays > 0) ", rain likely on ${it.rainyDays} day${if (it.rainyDays == 1) "" else "s"}" else ", dry"
            } ?: "the forecast comes closer to the date")
        )
        append("- Remembered, so \"when is my trip?\" has an answer")
    }
}
