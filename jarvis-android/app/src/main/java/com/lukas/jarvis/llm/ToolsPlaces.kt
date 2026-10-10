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

// The weather, places, routes and saved spots.

internal fun Tools.weatherTool(): JSONObject = tool(
    "weather",
    "The real forecast for where the user is, or for a named place: temperature, rain and " +
        "when it starts, wind, sunrise and sunset, UV, air quality and pollen. Use for " +
        "anything about the sky, the air or whether to take a coat. Never guess the weather.",
    props(
        "place" to str("A town or area to look up. Omit for where the user is now."),
        "day" to int("Which day: 0 for now and today, 1 for tomorrow, up to 6. Use this for one day."),
        "days" to int("How many days of forecast. 1 for right now, up to 7.")
    ),
    emptyList()
)

internal fun Tools.placeTools(settings: Settings): List<JSONObject> = listOf(
    tool(
        "find_places",
        "Find real places around the user: restaurants, cafes, shops, pharmacies, " +
            "cash machines, stations, anything with an address. Use this whenever the " +
            "user wonders where to eat, drink, buy or go, or says something like " +
            "'I'm hungry' or 'is there one near me'. The results are pinned on the map.",
        props(
            "query" to str("What to look for in plain words, e.g. 'restaurants', 'pizza', 'pharmacy', 'Aldi'."),
            "near" to str("Area to search around, e.g. 'Berlin Mitte'. Omit to search around the user."),
            "radius_m" to int("How far to look, in metres. Default ${settings.searchRadiusMeters}."),
            "limit" to int("How many results. Default 5.")
        ),
        listOf("query")
    ),
    tool(
        "route_to",
        "Work out the way from the user to a place and draw it on the map. Use after " +
            "find_places, or whenever the user asks how to get somewhere or how far it is.",
        props(
            "destination" to str(
                "Where to: a name from find_places, a result number like '2', or an address. " +
                    "Omit to route to the currently selected pin."
            ),
            "mode" to str("How they are travelling. Defaults to ${settings.travelMode}.", Geo.ALL_MODES)
        ),
        emptyList()
    ),
    tool(
        "start_navigation",
        "Hand turn-by-turn directions to the phone's maps app. Only use when the user " +
            "asks to start or open navigation, not for a simple 'how far is it'.",
        props(
            "destination" to str("Where to. Omit to use the place already on the map."),
            "mode" to str("How they are travelling.", Geo.ALL_MODES)
        ),
        emptyList()
    ),
    tool(
        "where_am_i",
        "Get the user's current street and area. Use when they ask where they are, or " +
            "when an answer depends on which part of town they are in.",
        props(),
        emptyList()
    ),
    tool(
        "save_place",
        "Remember a place under a name so it can be routed to later: 'I parked here' " +
            "(name 'car'), 'this is home', 'save this as work', 'save that restaurant', " +
            "'my house is Hauptstraße 5, Berlin' (address), 'the pin is my house' (use_selected_pin). " +
            "Saving under a name that exists moves it. route_to and start_navigation accept these names afterwards.",
        props(
            "name" to str("Short name: 'car', 'home', 'work', or anything the user says."),
            "note" to str("Anything worth keeping with it, e.g. 'level 2, bay 41'."),
            "address" to str("An address or place to look up and save, instead of where the user is now."),
            "use_selected_pin" to bool("True to save the pin selected or dropped on the map instead of where the user is now.")
        ),
        listOf("name")
    ),
    tool(
        "rename_place",
        "Give a saved place a new name: 'call the flat home', 'rename work to office'.",
        props("name" to str("Its current name."), "new_name" to str("The new name.")),
        listOf("name", "new_name")
    ),
    tool(
        "show_on_map",
        "Show a place on the map and fly there: 'show me my house', 'where is work on the map', " +
            "'show Alexanderplatz'. A saved name, a place or an address.",
        props("place" to str("What to show.")),
        listOf("place")
    ),
    tool(
        "saved_places",
        "List the places the user has saved, with how far each is from here.",
        props(),
        emptyList()
    ),
    tool(
        "forget_place",
        "Delete a saved place.",
        props("name" to str("Its name.")),
        listOf("name")
    ),
    tool(
        "place_reminder",
        "A reminder that goes off at a place instead of a time: 'remind me to buy milk " +
            "when I get home', 'when I leave work remind me to call Mum', 'every time I " +
            "get to the gym remind me to stretch'. Also lists and cancels them. For a " +
            "reminder at a time use add_task.",
        props(
            "action" to str("What to do. Default add.", listOf("add", "list", "cancel")),
            "text" to str("What to remind about, e.g. 'buy milk'. To cancel: words from it, or its id."),
            "place" to str("Where: a saved place such as 'home' or 'work', a shop, or an address. Omit for where the user is now."),
            "leaving" to bool("True for 'when I leave', false for 'when I get there'."),
            "every" to bool("True for every arrival or departure, not only the next."),
            "routine" to str("A saved routine to run there instead, e.g. 'evening' for 'when I get home, run my evening routine'."),
            "list" to str("A list to show there instead, read when they arrive, e.g. 'shopping' for 'show my shopping list when I get to Rewe'.")
        ),
        emptyList()
    ),
    tool(
        "share_location",
        "Give someone the user's current position as a map link. With a number it is " +
            "texted straight away; without, the link comes back for you to use, for " +
            "example in send_chat_message.",
        props(
            "number" to str("Phone number to text it to. Use find_contact first for a name."),
            "who" to str("Who it is for, to say it back.")
        ),
        emptyList()
    )
)

internal suspend fun Tools.weather(args: JSONObject): String {
    val placeQuery = args.optString("place").trim()
    val days = args.optInt("days", 3).coerceIn(1, 7)

    // A named place is geocoded through the same client the map uses; with
    // no name it is wherever the phone is, which is what "will it rain"
    // means nine times in ten.
    val (point, label) = if (placeQuery.isNotBlank()) {
        val hit = runCatching { places.geocode(placeQuery, limit = 1) }
            .getOrDefault(emptyList())
            .firstOrNull()
            ?: return "I could not find $placeQuery on the map."
        hit.point to hit.name
    } else {
        val here = locator.current()
            ?: return "I do not have your location yet, so I cannot tell you the weather " +
                "here. Name a town and I will look that up instead."
        val described = runCatching { places.town(here) }.getOrNull()
        here to (described ?: "where you are")
    }

    // One named day is answered as that day alone: handed the whole
    // forecast, a small model reads today's numbers out for tomorrow.
    val day = number(args, "day")?.toInt()?.coerceIn(0, 6) ?: 0
    val forecast = weather.at(point, label, maxOf(days, day + 1))
        ?: return "The weather service did not answer just now."
    if (day >= 1) {
        val one = forecast.days.getOrNull(day)
            ?: return "The forecast does not reach that far for $label."
        return buildString {
            // "2026-09-27" reads as the weekday it is.
            val name = runCatching {
                java.time.LocalDate.parse(one.label).dayOfWeek
                    .getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
            }.getOrDefault(one.label)
            append("$name in $label: ${one.description.lowercase(Locale.ROOT)}, ")
            append("${one.low.roundToInt()}° to ${one.high.roundToInt()}°")
            if (one.precipitationChance >= 20) append(", ${one.precipitationChance}% chance of rain")
            append(".")
            if (one.sunrise.isNotBlank()) append(" Sunrise ${one.sunrise}, sunset ${one.sunset}.")
            if (!one.uvMax.isNaN() && one.uvMax >= 3) {
                append(" UV up to ${one.uvMax.roundToInt()} (${com.lukas.jarvis.web.Weather.uvWord(one.uvMax)}).")
            }
        }
    }
    return weather.speak(forecast)
}

//
// These write their results onto the map as well as returning prose, so the
// reply and the pins always describe the same lookup. The map has its own
// state flow, which is why none of them touch ToolEffects.

internal suspend fun Tools.findPlaces(args: JSONObject, settings: Settings): String =
    navigator.findPlaces(
        query = args.optString("query").trim(),
        near = args.optString("near").trim().takeIf { it.isNotBlank() },
        radiusMeters = args.optInt("radius_m", 0).takeIf { it > 0 },
        limit = args.optInt("limit", 5),
        settings = settings
    )

internal suspend fun Tools.routeTo(args: JSONObject, settings: Settings): String =
    navigator.routeTo(
        destination = args.optString("destination").trim().takeIf { it.isNotBlank() },
        mode = args.optString("mode").trim().takeIf { it.isNotBlank() },
        settings = settings
    )

internal suspend fun Tools.startNavigation(args: JSONObject, settings: Settings): String =
    navigator.startNavigation(
        destination = args.optString("destination").trim().takeIf { it.isNotBlank() },
        mode = args.optString("mode").trim().takeIf { it.isNotBlank() },
        settings = settings
    )

internal suspend fun Tools.placeReminder(args: JSONObject): String {
    val action = args.optString("action").trim().lowercase(Locale.ROOT).ifBlank { "add" }
    val text = args.optString("text").trim()
    when (action) {
        "list", "show" -> return placeReminders.describe()
        "cancel", "delete", "remove" -> {
            if (placeReminders.current.isEmpty()) return "There are no place reminders to cancel."
            val target = placeReminders.find(text)
                ?: return "No place reminder matches '$text'. These are set:\n${placeReminders.describe()}"
            placeReminders.remove(target.id)
            return "Cancelled: ${target.describe()}."
        }
    }
    val routineName = args.optString("routine").trim().takeIf { it.isNotBlank() }?.let { wanted ->
        routines.find(wanted)?.name
            ?: return "There is no routine called '$wanted'. Saved: " +
                routines.all.value.joinToString { it.name }.ifBlank { "none yet" } + "."
    }
    val listName = args.optString("list").trim().takeIf { it.isNotBlank() }
        ?.let { com.lukas.jarvis.data.ListBook.canonical(it) }?.takeIf { it.isNotBlank() }
    val label = text.ifBlank {
        routineName?.let { "run the $it routine" } ?: listName?.let { "your $it list" } ?: ""
    }
    if (label.isBlank()) return "What should the reminder say?"
    if (!placeReminders.canWatch) {
        placeReminders.askPermission()
        return "A place reminder needs precise location, which Mochi doesn't have (approximate " +
            "is not enough for Android to watch a spot). I've asked for it — tell the user to " +
            "choose \"Precise\" and say the reminder again."
    }
    val wanted = args.optString("place").trim()
    val target = navigator.locate(wanted.takeIf { it.isNotBlank() })
        ?: return when {
            wanted.isBlank() -> "I can't tell where you are right now, so I can't pin this here. " +
                "Name the place instead."
            navigator.isUnsavedPersonal(wanted) -> navigator.unknownPersonal(wanted)
            else -> "I couldn't find '$wanted'. Give me an address, or save it as a place while you're there."
        }
    val name = when {
        target.name == "here" -> "here"
        target.category == "saved place" && target.name == "your parked car" -> "the car"
        else -> target.name
    }
    val watch = placeReminders.add(
        text = label,
        place = name,
        point = target.point,
        leaving = args.optBoolean("leaving", false),
        every = args.optBoolean("every", false),
        here = locator.remembered(),
        routine = routineName,
        list = listName
    )
    return buildString {
        append("Set: ${watch.text}, ${watch.trigger}")
        if (watch.every) append(", every time")
        append(".")
        if (listName != null) {
            val open = lists.current.find(listName)?.open?.size ?: 0
            append(
                if (open == 0) " The $listName list is empty now; it shows whatever is on it when they arrive."
                else " It shows what is still on it then ($open now), in the order of the shop."
            )
        }
        if (!watch.leaving && !watch.armed) append(" You're there now, so it counts from the next time you arrive.")
        if (!placeReminders.canWatchClosed) {
            placeReminders.askPermission()
            append(
                " Tell the user, since a settings page just opened: for it to go off while Mochi " +
                    "is closed, location has to be allowed \"all the time\" there."
            )
        }
    }
}
