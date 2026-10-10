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

// The calendar and the address book.

internal fun Tools.calendarTools(): List<JSONObject> = listOf(
    tool(
        "calendar",
        "Read what is on the phone's calendar. Use for 'what have I got on', 'am I free', " +
            "'when is my next meeting'.",
        props(
            "days" to int("How far ahead to look. 1 for today, 7 for the week. Default 1."),
            "limit" to int("How many entries. Default 10.")
        ),
        emptyList()
    ),
    tool(
        "add_calendar_event",
        "Put an appointment in the user's real calendar. Use for meetings, appointments and " +
            "anything with other people in it; use add_task for a private to-do.",
        props(
            "title" to str("What the event is."),
            "start" to str("ISO local start time, e.g. '2026-09-24T14:30', or '+2h'."),
            "end" to str("ISO local end time. Defaults to an hour after the start."),
            "location" to str("Where it is."),
            "description" to str("Any detail worth keeping with it.")
        ),
        listOf("title", "start")
    ),
    tool(
        "change_calendar_event",
        "Move or cancel an upcoming appointment in the user's calendar, found by its title. " +
            "'Move the dentist to Friday at ten', 'cancel lunch with Anna'.",
        props(
            "title" to str("The appointment's name, or a word from it."),
            "action" to str("What to do.", listOf("move", "cancel")),
            "start" to str("For 'move': the new ISO local start time, e.g. '2026-09-25T10:00'. It keeps its length.")
        ),
        listOf("title", "action")
    )
)

internal fun Tools.contactTool(): JSONObject = tool(
    "find_contact",
    "Look a person up in the address book and get their number. Call this before dial or " +
        "send_message whenever the user names a person rather than reading out digits.",
    props(
        "name" to str("The person's name, or part of it."),
        "limit" to int("How many matches. Default 3.")
    ),
    listOf("name")
)

internal suspend fun Tools.addEvent(args: JSONObject, settings: Settings): String {
    val title = args.optString("title").trim()
    if (title.isBlank()) return "An event needs a title."
    val start = TimeUtil.parse(args.optString("start").takeIf { it.isNotBlank() })
        ?: return "I need a start time I can read, like '2026-09-24T14:30' or '+2h'."
    val end = TimeUtil.parse(args.optString("end").takeIf { it.isNotBlank() })
    // Straight into the calendar when allowed; otherwise filled in for the
    // user to save, and the reply says which of the two happened.
    val location = args.optString("location").takeIf { it.isNotBlank() }
    agenda.insert(
        title = title,
        start = start,
        end = end ?: (start + 60 * 60 * 1000L),
        location = location,
        description = args.optString("description").takeIf { it.isNotBlank() }
    )?.let { added -> return added + leaveReminder(title, start, location, settings) }
    return launcher.createEvent(
        title = title,
        startMillis = start,
        endMillis = end,
        location = args.optString("location").takeIf { it.isNotBlank() },
        description = args.optString("description").takeIf { it.isNotBlank() }
    )
}
