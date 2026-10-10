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

// Routines: several things under one name.

internal fun Tools.routineTools(): List<JSONObject> = listOf(
    tool(
        "create_routine",
        "Save a routine: several things done together under one name, optionally at a " +
            "time. Each step is one plain sentence exactly as the user would say it to you, " +
            "e.g. 'How does my day look?', 'Play the radio'. Saving under an existing name " +
            "replaces it. With quiet true it runs by itself at its time and the answer " +
            "arrives as a notification — right for questions and checks ('every weekday at " +
            "7:30 tell me if I need an umbrella'), wrong for steps that play or open things.",
        props(
            "name" to str("Short name, e.g. 'morning', 'leaving home', 'umbrella check'."),
            "steps" to arr("The sentences to carry out, in order."),
            "time" to str("Time as 'HH:MM', or omit to run only when asked."),
            "days" to str("Which days: 'weekdays', 'weekends', or names like 'mon, wed, fri'. Omit for every day."),
            "quiet" to bool("True to run by itself at the time and send the answer as a notification."),
            "when" to str(
                "Starts it by itself when the phone notices something, instead of or as well as a time: " +
                    "'charging' (plugged in), 'connected' or 'disconnected' (a Bluetooth device, named in device), " +
                    "'event' (a calendar event starts). It then runs in the background and sends the answer.",
                listOf("charging", "connected", "disconnected", "event")
            ),
            "device" to str("For a Bluetooth trigger, the device: 'car', 'AirPods', 'headphones'. Omit for any device.")
        ),
        listOf("name", "steps")
    ),
    tool(
        "run_routine",
        "Carry out a saved routine now, every step. Use when the user names one: " +
            "'good morning routine', 'run bedtime'.",
        props("name" to str("Which routine.")),
        listOf("name")
    ),
    tool(
        "list_routines",
        "List the saved routines and their steps.",
        props(),
        emptyList()
    ),
    tool(
        "delete_routine",
        "Delete a saved routine.",
        props("name" to str("Which routine.")),
        listOf("name")
    )
)

internal fun Tools.createRoutine(args: JSONObject): String {
    val name = args.optString("name").trim()
    if (name.isBlank()) return "A routine needs a name."
    val steps = args.optJSONArray("steps").toStringList().ifEmpty {
        // Small models sometimes send the list as one string.
        args.optString("steps").split('\n', ';').map { it.trim() }.filter { it.isNotBlank() }
    }
    if (steps.isEmpty()) return "A routine needs at least one step."
    val rawTime = args.optString("time").trim()
    val time = Routines.normalizeTime(rawTime)
    val trigger = Trigger.parse(args.optString("when"), args.optString("device"))
    val saved = routines.save(
        Routine(
            name = name,
            steps = steps,
            time = time,
            days = com.lukas.jarvis.auto.RoutineDays.parse(args.optString("days")),
            quiet = args.optBoolean("quiet", false),
            trigger = trigger
        )
    )
    val whenLine = when {
        saved.time == null -> ""
        saved.quiet -> " It runs by itself ${saved.schedule} and sends the answer as a notification."
        else -> " I will offer it ${saved.schedule}."
    }
    val triggerLine = saved.trigger?.let { t ->
        buildString {
            append(" It also starts by itself ${t.describe}, runs in the background and sends the answer as a notification.")
            when (t.kind) {
                Trigger.Kind.Connected, Trigger.Kind.Disconnected -> if (!phone.hearsBluetooth) {
                    phone.askBluetooth()
                    append(" Android asks first whether Mochi may see Bluetooth devices; tell the user to allow it, or it can't notice the device.")
                }
                Trigger.Kind.EventStarts -> if (!agenda.hasPermission) {
                    append(" It needs the calendar to know when events start: tell the user to switch Calendar on in Powers.")
                }
                Trigger.Kind.Charging -> append(" It may take a few minutes after plugging in, which is how Android lets a closed app know.")
            }
        }
    }.orEmpty()
    return "Saved the '${saved.name}' routine with ${saved.steps.size} step(s): " +
        saved.steps.joinToString("; ") + "." + whenLine + triggerLine
}

internal suspend fun Tools.runRoutine(args: JSONObject, settings: Settings): String {
    val routine = routines.find(args.optString("name"))
        ?: return if (routines.all.value.isEmpty()) {
            "There are no routines yet. Tell me the steps and I will save one."
        } else {
            "No routine by that name. There is: " +
                routines.all.value.joinToString(", ") { it.name } + "."
        }
    if (routinesRunning.get() > 0) {
        return "A routine is already running, so this one was not started inside it."
    }
    val runner = routineRunner ?: return "Routines are not ready yet."
    return runner(routine, settings)
}

internal fun Tools.listRoutines(): String {
    val all = routines.all.value
    if (all.isEmpty()) return "No routines saved yet."
    return all.joinToString("\n") { routine ->
        "- ${routine.name}" +
            (routine.schedule.takeIf { it.isNotBlank() }?.let { " ($it" + (if (routine.quiet) ", runs quietly)" else ")") } ?: "") +
            ": " + routine.steps.joinToString("; ")
    }
}
