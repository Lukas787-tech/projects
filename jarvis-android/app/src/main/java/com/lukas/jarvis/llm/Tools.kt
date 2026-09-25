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

/** What changed as a result of a turn, so the UI knows which tabs to refresh. */
data class ToolEffects(
    var memoriesChanged: Boolean = false,
    var trackersChanged: Boolean = false,
    var tasksChanged: Boolean = false,
    /** Pictures drawn this turn, as file paths, to be shown with the reply. */
    val images: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
) {
    val any: Boolean get() = memoriesChanged || trackersChanged || tasksChanged
}

/**
 * The bridge between the model and the phone. Every tool returns plain text
 * rather than JSON: small free-tier models read prose far more reliably than
 * they read nested objects.
 *
 * The schema list is assembled in groups rather than as one long literal, and
 * each group is gated on the setting that owns it. That is not only tidier to
 * read — a free-tier model handed forty tool definitions starts picking the
 * wrong one, so the surface a given user sees is the one they have switched on.
 */
class Tools(
    private val brain: Brain,
    private val web: WebTools,
    private val weather: Weather,
    private val reminders: Reminders,
    private val navigator: Navigator,
    private val locator: Locator,
    private val places: PlacesClient,
    private val stage: StageStore,
    private val phone: Phone,
    private val device: Device,
    private val launcher: Launcher,
    private val people: People,
    private val agenda: Agenda,
    private val briefer: Briefer,
    private val messenger: Messenger,
    private val caller: Caller,
    private val chats: Chats,
    private val currency: Currency,
    private val camera: CameraBus,
    private val routines: Routines,
    private val knowledge: Knowledge,
    private val imagine: Imagine,
    private val home: Home,
    private val lists: com.lukas.jarvis.data.Lists,
    private val timers: com.lukas.jarvis.notify.Timers,
    private val placeReminders: com.lukas.jarvis.notify.PlaceReminders
) {

    /**
     * Runs a routine's steps as turns of their own. Set by the container once
     * the agent exists, since the agent is built from these tools; null while
     * a routine is already running, so a routine cannot start itself.
     */
    @Volatile
    var routineRunner: (suspend (Routine, Settings) -> String)? = null

    fun schemas(settings: Settings): List<JSONObject> = buildList {
        addAll(memoryTools())
        addAll(trackerTools(settings))
        addAll(taskTools())
        add(listTool())
        addAll(thinkingTools())
        if (settings.webSearchEnabled) addAll(webTools())
        if (settings.webSearchEnabled) addAll(worldTools())
        add(randomTool())
        if (settings.homeReady) addAll(homeTools())
        if (settings.weatherEnabled) add(weatherTool())
        if (settings.mapsEnabled) addAll(placeTools(settings))
        if (settings.calendarEnabled) addAll(calendarTools())
        if (settings.contactsEnabled) add(contactTool())
        if (settings.deviceControlEnabled) addAll(deviceTools())
        addAll(mediaTools())
        add(showTool())
        addAll(visionTools(settings))
        addAll(routineTools())
    }

    // ------------------------------------------------------------ the schemas

    private fun memoryTools(): List<JSONObject> = listOf(
        tool(
            "remember",
            "Save something to long-term memory. Use whenever the user states a fact, " +
                "preference, plan, name, place, or anything worth recalling later.",
            props(
                "content" to str("The fact, written as a clear standalone sentence in third person, e.g. 'Lukas's bike lock code is 4821'."),
                "kind" to str("Category of memory.", Memory.ALL_KINDS),
                "tags" to arr("Short lowercase keywords to help find this later."),
                "importance" to int("1 trivial to 5 critical. Default 3.")
            ),
            listOf("content")
        ),
        tool(
            "recall",
            "Search long-term memory. Use before answering anything about the user's own life, " +
                "belongings, plans or past statements.",
            props(
                "query" to str("What to look for, in keywords."),
                "kind" to str("Restrict to one category.", Memory.ALL_KINDS),
                "limit" to int("How many to return. Default 6.")
            ),
            listOf("query")
        ),
        tool(
            "update_memory",
            "Correct something already remembered. Use when the user says a stored fact has " +
                "changed rather than asking to forget it — a new address, a new password, a " +
                "changed plan.",
            props(
                "id" to int("The memory id shown by recall."),
                "content" to str("The corrected sentence."),
                "importance" to int("1 to 5, if it should change."),
                "pinned" to bool("Keep it permanently at hand.")
            ),
            listOf("id")
        ),
        tool(
            "forget",
            "Delete a memory by its id. Only use when the user asks to forget something.",
            props("id" to int("The memory id shown by recall.")),
            listOf("id")
        ),
        tool(
            "recall_conversation",
            "Search everything the two of you have said before. Use for 'what did I tell you " +
                "about', 'what did you say when', or anything referring back to an earlier talk.",
            props(
                "query" to str("Words that would appear in that conversation."),
                "limit" to int("How many turns. Default 8.")
            ),
            listOf("query")
        )
    )

    private fun trackerTools(settings: Settings): List<JSONObject> = listOf(
        tool(
            "log_entry",
            "Record one movement on a tracker: money spent or received, calories eaten, " +
                "kilometres run, hours worked. Creates the tracker automatically if it is new. " +
                "Use this every time the user mentions buying something or doing a countable thing.",
            props(
                "tracker" to str("Short name of the thing being tracked, e.g. 'groceries', 'card', 'calories'."),
                "amount" to num("How much. Always positive."),
                "direction" to str("'out' for spending/using, 'in' for income/adding.", listOf("out", "in")),
                "note" to str("What it was, e.g. 'chips'."),
                "unit" to str("Currency code or unit, e.g. 'EUR', 'kcal', 'km'. Defaults to ${settings.defaultCurrency}."),
                "kind" to str("What sort of quantity this is.", Tracker.ALL_KINDS),
                "occurred_at" to str("ISO time if it did not happen just now, e.g. '2026-09-15T18:30'.")
            ),
            listOf("tracker", "amount")
        ),
        tool(
            "tracker_status",
            "Read current totals: money left on a card, budget remaining this period, " +
                "calories today. Call this whenever the user asks how much of anything is left.",
            props("tracker" to str("Name of one tracker, or omit for all of them.")),
            emptyList()
        ),
        tool(
            "configure_tracker",
            "Create a tracker or change its budget, starting balance, reset period or unit. " +
                "Use when the user says things like 'my card has 50 euros on it' or " +
                "'my food budget is 200 a month'.",
            props(
                "tracker" to str("Short name."),
                "label" to str("Nicer display name."),
                "kind" to str("What sort of quantity.", Tracker.ALL_KINDS),
                "unit" to str("Currency code or unit."),
                "budget" to num("Spending cap per period. Omit to leave unchanged."),
                "starting_balance" to num("Starting amount, for card or wallet style balances."),
                "period" to str("When the budget resets.", Tracker.ALL_PERIODS)
            ),
            listOf("tracker")
        ),
        tool(
            "list_entries",
            "List recent movements on a tracker, newest first, with their ids.",
            props(
                "tracker" to str("Name of the tracker, or omit for everything."),
                "limit" to int("How many. Default 10.")
            ),
            emptyList()
        ),
        tool(
            "spending_report",
            "Add up a period and break it down: totals per tracker, the biggest single items, " +
                "and the daily average. Use for 'where did my money go', 'how much did I spend " +
                "this week', 'what am I averaging'.",
            props(
                "days" to int("How far back to look. Default 30."),
                "tracker" to str("Restrict to one tracker, or omit for all of them.")
            ),
            emptyList()
        ),
        tool(
            "delete_entry",
            "Take a logged movement back off its tracker: 'undo that', 'I did not actually " +
                "buy it', 'that was logged twice'. With no id, the most recent entry is removed.",
            props("id" to int("The entry id shown by list_entries. Omit for the latest entry.")),
            emptyList()
        )
    )

    private fun taskTools(): List<JSONObject> = listOf(
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

    private fun thinkingTools(): List<JSONObject> = listOf(
        tool(
            "now",
            "Get the current date, time and day of week.",
            props(),
            emptyList()
        ),
        tool(
            "calculate",
            "Work out an arithmetic expression exactly. ALWAYS use this instead of doing sums " +
                "in your head — percentages, splitting a bill, totals, unit prices. Supports " +
                "+ - * / ^ %, brackets, sqrt, ln, log, sin, cos, tan, abs, round, pi and e.",
            props("expression" to str("The sum, e.g. '(249.99 * 0.175) + 12' or '15% of 80'.")),
            listOf("expression")
        ),
        tool(
            "date_calc",
            "Calendar arithmetic, exactly. ALWAYS use this for 'how many days until', 'how long " +
                "ago was', 'what date is three weeks from now', 'what weekday is the 24th'. " +
                "Never count days in your head.",
            props(
                "operation" to str("What to work out.", listOf("between", "add", "weekday")),
                "from" to str("Start date: ISO like '2026-12-24', '24.12.', 'tomorrow'. Omit for today."),
                "to" to str("For 'between': the other date. For 'weekday': the date to look at."),
                "amount" to int("For 'add': how many units to add. Negative goes back."),
                "unit" to str("For 'add'.", listOf("days", "weeks", "months", "years"))
            ),
            listOf("operation")
        ),
        tool(
            "convert_units",
            "Convert between units exactly. Known units — ${Units.known()}.",
            props(
                "amount" to num("How much of the source unit."),
                "from" to str("The unit it is in now, e.g. 'miles'."),
                "to" to str("The unit wanted, e.g. 'km'.")
            ),
            listOf("amount", "from", "to")
        ),
        tool(
            "briefing",
            "The whole day in one call: weather, what is due, the next appointment, budgets and " +
                "the phone's own state. Use for 'how does my day look', 'good morning', " +
                "'catch me up', or any question that spans more than one of those. With " +
                "evening true it looks back instead — done and spent today, still open, and " +
                "tomorrow — for 'how did my day go', 'wrap up my day', 'what's tomorrow'.",
            props("evening" to bool("True for the look back over today and ahead to tomorrow.")),
            emptyList()
        )
    )

    private fun webTools(): List<JSONObject> = listOf(
        tool(
            "web_search",
            "Search the live internet. Use for news, prices, opening hours, facts you are " +
                "unsure about, or anything after your training cutoff.",
            props(
                "query" to str("Search terms."),
                "limit" to int("How many results. Default 5.")
            ),
            listOf("query")
        ),
        tool(
            "open_url",
            "Fetch a web page and read its text. Use to follow up on a search result.",
            props("url" to str("Full URL to open.")),
            listOf("url")
        ),
        tool(
            "wikipedia",
            "The opening of the Wikipedia article on a person, place, thing or idea. Use for " +
                "'who was', 'what is', 'tell me about' — facts that do not change by the day.",
            props(
                "topic" to str("What to look up, e.g. 'Brandenburg Gate'."),
                "language" to str("Two-letter language code to prefer, e.g. 'de'. Omit for the phone's.")
            ),
            listOf("topic")
        ),
        tool(
            "convert_currency",
            "Convert money at today's real exchange rate. ALWAYS use this for any amount in " +
                "one currency asked for in another; never use a rate you remember.",
            props(
                "amount" to num("How much."),
                "from" to str("Currency it is in: a code like 'USD' or a word like 'dollars'."),
                "to" to str("Currency wanted, e.g. 'EUR'.")
            ),
            listOf("amount", "from", "to")
        )
    )

    private fun worldTools(): List<JSONObject> = listOf(
        tool(
            "news",
            "Current headlines, or the latest news about a topic. Use for 'what's in the " +
                "news', 'what happened with', 'any news about'.",
            props(
                "topic" to str("What the news should be about. Omit for top headlines."),
                "limit" to int("How many headlines. Default 5.")
            ),
            emptyList()
        ),
        tool(
            "translate",
            "Translate text into another language exactly. Use for 'how do you say', " +
                "'translate', 'what is this in German'.",
            props(
                "text" to str("The words to translate."),
                "to" to str("Target language, e.g. 'Spanish' or 'es'."),
                "from" to str("Source language, e.g. 'English' or 'en'.")
            ),
            listOf("text", "to", "from")
        ),
        tool(
            "interpreter",
            "Open live interpreter mode on screen: the user and someone who speaks another " +
                "language talk through the phone, each tapping their side, and every line is " +
                "translated and spoken aloud. Use for 'be my interpreter', 'help me talk to this " +
                "person in Spanish', 'interpret for me'. Not for translating one phrase.",
            props(
                "language" to str("The other person's language, e.g. 'Spanish'."),
                "action" to str("start (default) or stop.", listOf("start", "stop"))
            ),
            emptyList()
        ),
        tool(
            "define_word",
            "Dictionary definition of a word, in any language.",
            props("word" to str("The word.")),
            listOf("word")
        ),
        tool(
            "market_price",
            "Live price of a share, fund, index or cryptocurrency, with today's change. " +
                "Never quote a price from memory.",
            props(
                "query" to str("Company name, ticker or coin, e.g. 'Apple', 'TSLA', 'bitcoin'."),
                "kind" to str("What it is.", listOf("auto", "stock", "crypto")),
                "currency" to str("For crypto: the currency to price it in, e.g. 'EUR'.")
            ),
            listOf("query")
        ),
        tool(
            "holidays",
            "Public holidays in a country: the next ones, or a whole year.",
            props(
                "country" to str("Two-letter country code, e.g. 'DE'. Omit for the phone's country."),
                "year" to int("A year, to list all of it. Omit for the ones still to come.")
            ),
            emptyList()
        ),
        tool(
            "recipe",
            "A real recipe for a dish: ingredients and method.",
            props("dish" to str("The dish, in English, e.g. 'lasagne', 'pad thai'.")),
            listOf("dish")
        ),
        tool(
            "sports",
            "A sports team's last result and next fixture.",
            props("team" to str("The team, e.g. 'Arsenal', 'Bayern Munich', 'Lakers'.")),
            listOf("team")
        ),
        tool(
            "tv_show",
            "About a TV series: whether it is still running, where, and when the next episode airs.",
            props("name" to str("The show.")),
            listOf("name")
        ),
        tool(
            "book",
            "Look a book up: author, year, length, rating.",
            props("query" to str("Title and/or author.")),
            listOf("query")
        ),
        tool(
            "fun",
            "A real joke, an odd fact or a good quote, when the user asks for one.",
            props("kind" to str("Which.", listOf("joke", "fact", "quote"))),
            listOf("kind")
        ),
        tool(
            "generate_image",
            "Draw a picture from a description and show it in the chat. Use for 'draw', " +
                "'imagine', 'make me a picture/wallpaper/logo of'.",
            props(
                "prompt" to str("A vivid, specific description in English: subject, style, lighting, mood."),
                "shape" to str("The picture's shape.", listOf("square", "portrait", "landscape"))
            ),
            listOf("prompt")
        )
    )

    private fun homeTools(): List<JSONObject> = listOf(
        tool(
            "home_status",
            "What the house is doing: which lights are on, doors and locks, heating and " +
                "temperatures. With a name, just those devices.",
            props("query" to str("A device or room name, e.g. 'front door', 'living room'. Omit for an overview.")),
            emptyList()
        ),
        tool(
            "home_control",
            "Operate devices in the user's house through Home Assistant: lights, plugs, fans, " +
                "blinds, locks, thermostats, media players, scenes and scripts. Use the names " +
                "the user says, e.g. 'living room lights', 'all lights', 'movie scene'.",
            props(
                "target" to str("The device, room or scene as the user named it."),
                "action" to str(
                    "What to do.",
                    listOf(
                        "on", "off", "toggle", "open", "close", "stop", "lock", "unlock",
                        "set_brightness", "set_temperature", "set_position", "set_volume", "set_speed",
                        "activate"
                    )
                ),
                "value" to num("For set_*: brightness %, temperature in degrees, position %, volume % or speed %.")
            ),
            listOf("target", "action")
        )
    )

    private fun listTool(): JSONObject = tool(
        "list",
        "The user's named lists — shopping, packing, films to watch, anything without a time. " +
            "Add, remove, tick off or read items. 'Add milk to the shopping list', 'what is on my " +
            "packing list', 'I got the eggs' (check). Something with a time is add_task instead.",
        props(
            "action" to str(
                "What to do.",
                listOf("add", "remove", "check", "uncheck", "show", "clear_done", "clear", "delete", "all")
            ),
            "list" to str("Which list, as the user named it: 'shopping', 'packing'. Not needed for 'all'."),
            "items" to arr("For add, remove, check and uncheck: the items, one per entry.")
        ),
        listOf("action")
    )

    private fun randomTool(): JSONObject = tool(
        "random",
        "Truly random choices: flip a coin, roll dice, a random number, pick one of several " +
            "options, or make a strong password. Always use this instead of choosing yourself.",
        props(
            "kind" to str("What to do.", listOf("coin", "dice", "number", "pick", "password")),
            "min" to int("For 'number': the lowest value. Default 1."),
            "max" to int(
                "For 'number': the highest value. For 'dice': the sides. For 'password': its length. " +
                    "Default 6, 100 or 20."
            ),
            "count" to int("How many coins, dice or numbers. Default 1."),
            "options" to arr("For 'pick': the things to choose between.")
        ),
        listOf("kind")
    )

    private fun weatherTool(): JSONObject = tool(
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

    private fun placeTools(settings: Settings): List<JSONObject> = listOf(
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
                "(name 'car'), 'this is home', 'save this as work', 'save that restaurant'. " +
                "route_to and start_navigation accept these names afterwards.",
            props(
                "name" to str("Short name: 'car', 'home', 'work', or anything the user says."),
                "note" to str("Anything worth keeping with it, e.g. 'level 2, bay 41'."),
                "use_selected_pin" to bool("True to save the pin selected on the map instead of where the user is now.")
            ),
            listOf("name")
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
                "every" to bool("True for every arrival or departure, not only the next.")
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

    private fun calendarTools(): List<JSONObject> = listOf(
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

    private fun contactTool(): JSONObject = tool(
        "find_contact",
        "Look a person up in the address book and get their number. Call this before dial or " +
            "send_message whenever the user names a person rather than reading out digits.",
        props(
            "name" to str("The person's name, or part of it."),
            "limit" to int("How many matches. Default 3.")
        ),
        listOf("name")
    )

    private fun deviceTools(): List<JSONObject> = listOf(
        tool(
            "system_action",
            "Press one of the phone's own system buttons: go back, go to the home screen, " +
                "show recent apps, pull down notifications or quick settings, open the power " +
                "menu, split the screen, lock the phone, or take a screenshot.",
            props("action" to str("Which button.", com.lukas.jarvis.control.SystemAction.ids)),
            listOf("action")
        ),
        tool(
            "set_alarm",
            "Set an alarm in the phone's clock app. Use for wake-ups and fixed times of day; " +
                "use add_task when it is a thing to do rather than a time to be woken.",
            props(
                "time" to str("When, as 'HH:MM' or an ISO time. Relative values like '+30m' work too."),
                "label" to str("What the alarm is for.")
            ),
            listOf("time")
        ),
        tool(
            "set_timer",
            "Start a named countdown: pasta, laundry, a break. It shows on screen and in the " +
                "notification shade, rings until stopped, and can be asked about with `timers`.",
            props(
                "minutes" to num("How long, in minutes. Decimals are fine."),
                "label" to str("What it is timing."),
                "stop_music" to bool("True for a sleep timer: 'stop the music in 30 minutes' — the music pauses at the end and nothing rings.")
            ),
            listOf("minutes")
        ),
        tool(
            "timers",
            "The timers Jarvis is running: how long is left, cancel one or all, or add time. " +
                "'How long on the pasta', 'stop the timer', 'give it five more minutes'.",
            props(
                "action" to str("What to do.", listOf("list", "cancel", "add")),
                "label" to str("Which timer, by its name, or 'all'. Omit for the newest."),
                "minutes" to num("For 'add': minutes to add; negative takes time away.")
            ),
            listOf("action")
        ),
        tool(
            "show_alarms",
            "Open the clock app's list of alarms, for 'what alarms have I got' or to switch one " +
                "off. Android does not let other apps read or delete alarms, so say it is on screen.",
            props(),
            emptyList()
        ),
        tool(
            "device_status",
            "How the phone itself is doing: battery, charging, network, free storage, ringer, " +
                "make and model. Use for 'how much battery', 'am I online', 'what phone is this'.",
            props(
                "what" to str(
                    "Which part, or 'all'.",
                    listOf("all", "battery", "network", "storage", "ringer", "volume", "brightness", "hardware")
                )
            ),
            emptyList()
        ),
        tool(
            "torch",
            "Switch the phone's flashlight on or off.",
            props("on" to bool("True to light it, false to put it out.")),
            listOf("on")
        ),
        tool(
            "ringer",
            "Set the ringer to normal, vibrate or silent.",
            props("mode" to str("Which one.", listOf("normal", "vibrate", "silent"))),
            listOf("mode")
        ),
        tool(
            "volume",
            "Set, raise, lower or mute one of the phone's volumes, or read them all. " +
                "'Turn it up' while music plays means media.",
            props(
                "stream" to str("Which volume. Default media.", listOf("media", "ring", "notification", "alarm", "call")),
                "action" to str("What to do.", listOf("set", "up", "down", "mute", "unmute", "max", "check")),
                "level" to int("For 'set': the percentage, 0 to 100.")
            ),
            listOf("action")
        ),
        tool(
            "brightness",
            "Set the screen brightness as a percentage, make it brighter or dimmer, or switch " +
                "automatic brightness on or off.",
            props(
                "level" to int("The percentage, 1 to 100."),
                "change" to str("A relative change instead of a level.", listOf("up", "down", "max", "min")),
                "auto" to bool("True for automatic brightness, false for manual.")
            ),
            emptyList()
        ),
        tool(
            "do_not_disturb",
            "Switch Do Not Disturb on or off, or read whether it is on. 'Priority' lets " +
                "favourites and alarms through; 'alarms' only alarms; 'total' nothing at all. " +
                "Give minutes for 'for an hour', 'until my meeting ends'.",
            props(
                "mode" to str("What to set.", listOf("priority", "alarms", "total", "off", "check")),
                "minutes" to int("End it again by itself after this many minutes.")
            ),
            listOf("mode")
        ),
        tool(
            "clipboard",
            "Copy text to the phone's clipboard, or read what is on it.",
            props(
                "action" to str("What to do.", listOf("copy", "read")),
                "text" to str("For 'copy': what to put there.")
            ),
            listOf("action")
        ),
        tool(
            "open_app",
            "Open an app that is installed on the phone, by whatever the user calls it.",
            props("name" to str("The app's name, e.g. 'spotify', 'whatsapp', 'camera'.")),
            listOf("name")
        ),
        tool(
            "open_settings_page",
            "Open a page of Android settings — wifi, bluetooth, battery, display, sound, " +
                "location, storage, apps, notifications, airplane. Use when something needs " +
                "changing that only the system itself may change.",
            props("page" to str("Which page.")),
            listOf("page")
        ),
        tool(
            "call",
            "Step one of ringing someone: this does NOT ring yet. It readies the call and hands " +
                "you back a question. Read that question out and wait for the user to answer. " +
                "Use find_contact first when given a name.",
            props(
                "number" to str("The phone number, digits and an optional leading +."),
                "who" to str("The person's name, if you have it, so it can be read back.")
            ),
            listOf("number")
        ),
        tool(
            "place_call",
            "Step two: actually rings the number readied by `call`. Only ever use this after " +
                "you asked and the user clearly said yes in their next message. If they said " +
                "anything else, or said nothing about it, use `cancel_call` instead. Never call " +
                "this in the same turn as `call`.",
            props(),
            emptyList()
        ),
        tool(
            "cancel_call",
            "Drop a readied call, when the user says no, names someone else, or changes the " +
                "subject.",
            props(),
            emptyList()
        ),
        tool(
            "dial",
            "Put a number in the dialler without ringing it, for when the user wants to press " +
                "call themselves. Prefer `call` when they asked you to ring someone.",
            props("number" to str("The phone number, digits and an optional leading +.")),
            listOf("number")
        ),
        tool(
            "send_message",
            "Send a text message. This really sends it — there is no draft and nothing for the " +
                "user to press. Say it has been sent, in the past tense. Use find_contact first " +
                "when given a name.",
            props(
                "number" to str("Who to send to."),
                "text" to str("The message, in the user's own voice and language.")
            ),
            listOf("number", "text")
        ),
        tool(
            "send_chat_message",
            "Start a conversation in WhatsApp, Telegram or Signal — 'message Ralf on WhatsApp'. " +
                "Give the person's name as the user said it; it is looked up in contacts here. " +
                "Read the answer back honestly: it says whether the message went or is sitting " +
                "typed out waiting for one press.",
            props(
                "app" to str("Which app.", listOf("whatsapp", "telegram", "signal")),
                "who" to str("The person's name as the user said it, or a phone number."),
                "text" to str("The message, in the user's own voice and language.")
            ),
            listOf("app", "who", "text")
        ),
        tool(
            "reply_to_message",
            "Answer a message that has just arrived in WhatsApp, Signal, Telegram, SMS or any " +
                "other app, straight from its notification. It really sends. With no name, the " +
                "newest message is answered, which is usually the one meant.",
            props(
                "text" to str("The reply, in the user's own voice and language."),
                "who" to str("The sender or the app, if the user named one. Leave out for the newest.")
            ),
            listOf("text")
        ),
        tool(
            "unread_messages",
            "List the messages waiting that can be answered, with who they are from and what " +
                "they say. Use before replying when the user asks what came in.",
            props(),
            emptyList()
        ),
        tool(
            "send_email",
            "Draft an email and leave it open for the user to send. It is NOT sent by you.",
            props(
                "to" to str("Recipient address."),
                "subject" to str("Subject line."),
                "body" to str("The message.")
            ),
            emptyList()
        ),
        tool(
            "share",
            "Hand some text to whichever app the user picks — a note to a friend, a link, a list.",
            props(
                "text" to str("What to share."),
                "title" to str("A subject line, if the target app uses one.")
            ),
            listOf("text")
        ),
        tool(
            "open_link",
            "Open a web page in the user's browser. Use when they want to look at something " +
                "themselves; use open_url when YOU need to read it.",
            props("url" to str("The address to open.")),
            listOf("url")
        )
    )

    private fun mediaTools(): List<JSONObject> = listOf(
        tool(
            "play_music",
            "Start music. With a song, artist or album, the phone's music app searches for it; " +
                "with nothing, whatever was last playing resumes.",
            props("query" to str("What to play, e.g. 'Rammstein Sonne'. Leave out to just resume.")),
            emptyList()
        ),
        tool(
            "now_playing",
            "What song or podcast is playing right now, by whom, and in which app.",
            props(),
            emptyList()
        ),
        tool(
            "control_playback",
            "Pause, resume or skip what is playing, or set the media volume.",
            props(
                "action" to str(
                    "What to do.",
                    listOf("pause", "resume", "next", "previous", "volume")
                ),
                "percent" to int("For 'volume' only: 0 to 100.")
            ),
            listOf("action")
        ),
        tool(
            "bluetooth",
            "List the phone's paired Bluetooth devices, or open the Bluetooth settings page. " +
                "Android does not let this app connect a device itself, so say so plainly " +
                "rather than claiming a connection was made.",
            props(
                "action" to str("What to do.", listOf("list", "open_settings"))
            ),
            listOf("action")
        )
    )

    private fun showTool(): JSONObject = tool(
        "show",
        "Put something on the user's screen. Call this whenever an answer is better " +
            "looked at than listened to, and whenever the user asks to see something. " +
            "Available: ${Element.names()}.",
        props(
            "element" to str("Which one to show.", Element.entries.map { it.title.lowercase(Locale.ROOT) }),
            "note" to str("One short line about why, shown under it. Optional.")
        ),
        listOf("element")
    )

    private fun visionTools(settings: Settings): List<JSONObject> = buildList {
        add(
            tool(
                "take_photo",
                "Open the camera so you can SEE something. Use whenever the user wants you to " +
                    "look at, read, identify, translate, count or scan something in front of " +
                    "them: 'what is this', 'read this', 'scan this receipt', 'translate that " +
                    "sign', 'what plant is this'. The picture arrives as their next message, " +
                    "described for you, with this question attached. After calling it, say " +
                    "one short line such as 'Go ahead, take the picture' and nothing else.",
                props(
                    "question" to str("What to find out from the picture, in the user's words."),
                    "source" to str("Where the picture comes from.", listOf("camera", "gallery"))
                ),
                listOf("question")
            )
        )
        add(
            tool(
                "read_screen",
                "Read the text on the phone's screen — the app the user is looking at. Use for " +
                    "'summarise this', 'what does this say', 'what's on my screen', 'explain this " +
                    "page', 'reply to this'. Then answer from what it returns.",
                props(),
                emptyList()
            )
        )
        if (settings.deviceControlEnabled) {
            add(
                tool(
                    "open_camera",
                    "Open the phone's own camera app for the user to take pictures or video " +
                        "themselves. Use take_photo instead when YOU need to see the picture.",
                    props("mode" to str("What to shoot.", listOf("photo", "video"))),
                    emptyList()
                )
            )
        }
    }

    private fun routineTools(): List<JSONObject> = listOf(
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
                "quiet" to bool("True to run by itself at the time and send the answer as a notification.")
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

    // ---------------------------------------------------------------- dispatch

    suspend fun execute(call: ToolCall, settings: Settings, effects: ToolEffects): String {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrDefault(JSONObject())
        return try {
            when (call.name) {
                // memory
                "remember" -> remember(args, effects)
                "recall" -> recall(args)
                "update_memory" -> updateMemory(args, effects)
                "forget" -> forget(args, effects)
                "recall_conversation" -> recallConversation(args)

                // trackers
                "log_entry" -> logEntry(args, settings, effects)
                "tracker_status" -> trackerStatus(args)
                "configure_tracker" -> configureTracker(args, settings, effects)
                "list_entries" -> listEntries(args)
                "spending_report" -> spendingReport(args)
                "delete_entry" -> deleteEntry(args, effects)

                // tasks
                "add_task" -> addTask(args, effects)
                "list" -> listAction(args)
                "list_tasks" -> listTasks(args)
                "complete_task" -> completeTask(args, effects)
                "update_task" -> updateTask(args, effects)
                "delete_task" -> deleteTask(args, effects)

                // thinking
                "now" -> nowText()
                "calculate" -> calculate(args)
                "convert_units" -> convert(args)
                "date_calc" -> dateCalc(args)
                "briefing" -> if (args.optBoolean("evening", false)) {
                    briefer.evening(settings).let { (title, text) -> "$title. $text" }
                } else {
                    briefer.build(settings).speak()
                }

                // the world
                "web_search" -> webSearch(args)
                "open_url" -> web.readPage(args.optString("url"))
                "convert_currency" -> convertCurrency(args)
                "weather" -> weather(args)
                "find_places" -> findPlaces(args, settings)
                "route_to" -> routeTo(args, settings)
                "start_navigation" -> startNavigation(args, settings)
                "where_am_i" -> navigator.whereAmI()
                "save_place" -> navigator.savePlace(
                    name = args.optString("name").trim(),
                    note = args.optString("note").trim().takeIf { it.isNotBlank() },
                    useSelectedPin = args.optBoolean("use_selected_pin", false)
                )
                "saved_places" -> navigator.savedPlaces(locator.remembered())
                "place_reminder" -> placeReminder(args)
                "forget_place" -> navigator.forgetPlace(args.optString("name").trim())
                "share_location" -> shareLocation(args)
                "wikipedia" -> wikipedia(args)
                "news" -> knowledge.news(
                    args.optString("topic").takeIf { it.isNotBlank() },
                    args.optInt("limit", 5)
                )
                "interpreter" -> interpreter(args, settings)
                "translate" -> knowledge.translate(
                    args.optString("text"),
                    args.optString("from"),
                    args.optString("to")
                )
                "define_word" -> knowledge.define(args.optString("word"))
                "market_price" -> knowledge.price(
                    args.optString("query"),
                    args.optString("kind").ifBlank { "auto" },
                    args.optString("currency").ifBlank { settings.defaultCurrency }
                )
                "holidays" -> knowledge.holidays(
                    args.optString("country"),
                    args.optInt("year", 0).takeIf { it > 1900 }
                )
                "recipe" -> knowledge.recipe(args.optString("dish"))
                "sports" -> knowledge.sports(args.optString("team"))
                "tv_show" -> knowledge.tvShow(args.optString("name"))
                "book" -> knowledge.book(args.optString("query"))
                "fun" -> knowledge.amuse(args.optString("kind"))
                "random" -> knowledge.random(
                    args.optString("kind"),
                    if (args.has("min")) args.optInt("min") else null,
                    if (args.has("max")) args.optInt("max") else null,
                    if (args.has("count")) args.optInt("count") else null,
                    args.optJSONArray("options").toStringList()
                )
                "generate_image" -> generateImage(args, effects)

                // the house
                "home_status" -> home.status(
                    settings.homeUrl,
                    settings.homeToken,
                    args.optString("query").takeIf { it.isNotBlank() }
                )
                "home_control" -> home.control(
                    settings.homeUrl,
                    settings.homeToken,
                    args.optString("target"),
                    args.optString("action").ifBlank { "toggle" },
                    optDoubleOrNull(args, "value")
                )

                // calendar and people
                "calendar" -> agenda.describe(args.optInt("days", 1), args.optInt("limit", 10))
                "add_calendar_event" -> addEvent(args, settings)
                "change_calendar_event" -> {
                    val cancel = args.optString("action").trim().lowercase(Locale.ROOT) in setOf("cancel", "delete", "remove")
                    val start = TimeUtil.parse(args.optString("start").takeIf { it.isNotBlank() })
                    if (!cancel && start == null) {
                        "I need the new time as something I can read, like '2026-09-25T10:00'."
                    } else {
                        agenda.change(args.optString("title"), start, cancel)
                    }
                }
                "find_contact" -> people.describe(
                    args.optString("name"),
                    args.optInt("limit", 3).coerceIn(1, 10)
                )

                // the phone
                "set_alarm" -> setAlarm(args)
                "set_timer" -> setTimer(args)
                "timers" -> timerAction(args)
                "show_alarms" -> launcher.showAlarms()
                "device_status" -> deviceStatus(args)
                "torch" -> device.torch(args.optBoolean("on", true))
                "ringer" -> device.setRinger(args.optString("mode"))
                "volume" -> device.volume(
                    args.optString("stream"),
                    args.optString("action"),
                    number(args, "level")?.toInt()
                )
                "brightness" -> device.brightness(
                    number(args, "level")?.toInt(),
                    if (args.has("auto") && !args.isNull("auto")) args.optBoolean("auto") else null,
                    args.optString("change").ifBlank { null }
                )
                "do_not_disturb" -> device.doNotDisturb(
                    args.optString("mode"),
                    number(args, "minutes")?.toInt()
                )
                "clipboard" -> clipboard(args)
                "open_app" -> launcher.openApp(args.optString("name"))
                "open_settings_page" -> device.openSettings(args.optString("page"))
                "dial" -> launcher.dial(args.optString("number"))
                "call" -> caller.arm(
                    args.optString("number"),
                    args.optString("who").takeIf { it.isNotBlank() }
                )
                "place_call" -> caller.place()
                "cancel_call" -> caller.cancel()
                "send_message" -> sendMessage(args)
                "send_chat_message" -> sendChatMessage(args)
                "reply_to_message" -> replyToMessage(args)
                "unread_messages" -> unreadMessages()
                "send_email" -> launcher.composeEmail(
                    args.optString("to").takeIf { it.isNotBlank() },
                    args.optString("subject").takeIf { it.isNotBlank() },
                    args.optString("body").takeIf { it.isNotBlank() }
                )
                "share" -> launcher.share(
                    args.optString("text"),
                    args.optString("title").takeIf { it.isNotBlank() }
                )
                "open_link" -> launcher.openUrl(args.optString("url"))

                // media and the screen
                "play_music" -> phone.play(args.optString("query").takeIf { it.isNotBlank() })
                "control_playback" -> playback(args)
                "now_playing" -> phone.describeNowPlaying()
                "bluetooth" -> bluetooth(args)
                "show" -> show(args)

                // eyes
                "take_photo" -> takePhoto(args)
                "read_screen" -> readScreen()
                "system_action" -> systemAction(args)
                "open_camera" -> launcher.openCamera(args.optString("mode") == "video")

                // routines
                "create_routine" -> createRoutine(args)
                "run_routine" -> runRoutine(args, settings)
                "list_routines" -> listRoutines()
                "delete_routine" -> {
                    val name = args.optString("name").trim()
                    if (routines.remove(name)) "Deleted the $name routine." else "No routine called '$name'."
                }

                else -> "Unknown tool '${call.name}'."
            }
        } catch (e: Exception) {
            "Tool '${call.name}' failed: ${e.message ?: e::class.java.simpleName}"
        }
    }

    private fun interpreter(args: JSONObject, settings: Settings): String {
        if (args.optString("action").trim().lowercase(Locale.ROOT) == "stop") {
            stage.show(stage.current, StageStore.INTERPRETER_STOP)
            return "Interpreter closed."
        }
        val wanted = args.optString("language").trim()
        val code = knowledge.codeFor(wanted)
            ?: return "Which language does the other person speak?"
        // The same check the screen makes, so the reply never promises a
        // panel that will not open.
        val own = knowledge.codeFor(settings.speechLanguage) ?: Locale.getDefault().language
        if (code == own) {
            return "${knowledge.nameOf(code)} is the user's own language, so there is nothing to " +
                "interpret. Ask which language the other person speaks."
        }
        stage.show(Element.Globe, StageStore.INTERPRETER_PREFIX + code)
        // Asked from the floating dot, the app is not on screen yet.
        launcher.showJarvis()
        return "The interpreter is open on screen for ${knowledge.nameOf(code)}. In one short line, tell " +
            "the user to tap their own button to speak, or the other button for the other person; " +
            "each line is translated and read aloud."
    }

    private fun readScreen(): String {
        val reading = ScreenReader.capture()
            ?: return "Screen reading is switched off. Tell the user it is in Settings -> Powers -> " +
                "Screen reading, and that Android asks once on its accessibility page."
        if (reading.app.isBlank()) {
            return "Only Jarvis is on screen right now, so there is nothing else to read. Tell the " +
                "user to ask from the floating dot or with the wake word while the other app is open."
        }
        if (reading.text.isBlank()) return "The screen (${reading.app}) shows no readable text."
        return "On screen in ${reading.app}:\n${reading.text}"
    }

    private suspend fun systemAction(args: JSONObject): String {
        val action = com.lukas.jarvis.control.SystemAction.byId(args.optString("action"))
            ?: return "Which button? One of: ${com.lukas.jarvis.control.SystemAction.ids.joinToString()}."
        if (android.os.Build.VERSION.SDK_INT < action.minSdk) {
            return "This phone's Android is too old to press ${action.id.replace('_', ' ')} from an app."
        }
        if (!ScreenReader.running) {
            return "Pressing system buttons goes through Jarvis's screen access, which is switched " +
                "off. Tell the user it is in Settings -> Powers -> Screen reading, and that Android " +
                "asks once on its accessibility page."
        }
        // A screenshot of Jarvis's own answer coming up is not the one wanted,
        // and a lock that lands mid-sentence cuts the answer off: a beat first.
        if (action == com.lukas.jarvis.control.SystemAction.Screenshot ||
            action == com.lukas.jarvis.control.SystemAction.Lock
        ) {
            kotlinx.coroutines.delay(700)
        }
        return when (ScreenReader.press(action)) {
            true -> action.done
            false -> "Android would not press ${action.id.replace('_', ' ')} just now."
            null -> "The screen access stopped running. It can be switched on again under Settings -> Powers -> Screen reading."
        }
    }

    private suspend fun generateImage(args: JSONObject, effects: ToolEffects): String {
        val prompt = args.optString("prompt").trim()
        if (prompt.isBlank()) return "Need a description of the picture."
        return runCatching { imagine.generate(prompt, args.optString("shape")) }
            .map { file ->
                effects.images += file.absolutePath
                "The picture is drawn and showing in the chat now. Describe it in one short line; " +
                    "do not include a link."
            }
            .getOrElse { "Could not draw that: ${it.message}." }
    }

    // ------------------------------------------------------------------ memory

    private fun remember(args: JSONObject, effects: ToolEffects): String {
        val content = args.optString("content").trim()
        if (content.isBlank()) return "Nothing to remember: 'content' was empty."
        val id = brain.addMemory(
            Memory(
                kind = args.optString("kind").ifBlank { Memory.KIND_FACT },
                content = content,
                tags = args.optJSONArray("tags").toStringList(),
                importance = args.optInt("importance", 3),
                source = "assistant"
            )
        )
        effects.memoriesChanged = true
        return "Remembered (id $id): $content"
    }

    private fun recall(args: JSONObject): String {
        val query = args.optString("query")
        val kind = args.optString("kind").takeIf { it.isNotBlank() }
        val limit = args.optInt("limit", 6).coerceIn(1, 20)
        val hits = brain.searchMemories(query, limit, kind)
        if (hits.isEmpty()) return "No memories match '$query'."
        return buildString {
            appendLine("${hits.size} memory match(es) for '$query':")
            hits.forEach { m ->
                appendLine("- [id ${m.id}] (${m.kind}, ${TimeUtil.relative(m.createdAt)}) ${m.content}")
            }
        }.trim()
    }

    private fun updateMemory(args: JSONObject, effects: ToolEffects): String {
        val id = args.optLong("id", -1L)
        if (id <= 0) return "Need a valid memory id."
        val existing = brain.getMemory(id) ?: return "No memory with id $id."
        val updated = existing.copy(
            content = args.optString("content").trim().ifBlank { existing.content },
            importance = if (args.has("importance")) {
                args.optInt("importance", existing.importance)
            } else {
                existing.importance
            },
            pinned = if (args.has("pinned")) args.optBoolean("pinned") else existing.pinned
        )
        brain.updateMemory(updated)
        effects.memoriesChanged = true
        return "Updated (id $id): ${updated.content}"
    }

    private fun forget(args: JSONObject, effects: ToolEffects): String {
        val id = args.optLong("id", -1L)
        if (id <= 0) return "Need a valid memory id."
        val existing = brain.getMemory(id) ?: return "No memory with id $id."
        brain.deleteMemory(id)
        effects.memoriesChanged = true
        return "Forgot: ${existing.content}"
    }

    private fun recallConversation(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) return "Need something to look for."
        val hits = brain.searchMessages(query, args.optInt("limit", 8).coerceIn(1, 25))
        if (hits.isEmpty()) return "Nothing in your past conversations mentions '$query'."
        return hits.reversed().joinToString("\n") { message ->
            val who = if (message.role == ChatMessage.ROLE_USER) "They said" else "You said"
            "$who (${TimeUtil.relative(message.createdAt)}): ${message.content.take(220)}"
        }
    }

    // ---------------------------------------------------------------- trackers

    private fun logEntry(args: JSONObject, settings: Settings, effects: ToolEffects): String {
        val name = args.optString("tracker").trim()
        if (name.isBlank()) return "Need a tracker name."
        val amount = number(args, "amount") ?: Double.NaN
        if (amount.isNaN()) return "Need a numeric amount."

        val requestedUnit = args.optString("unit").trim()
        val requestedKind = args.optString("kind").trim()
        val tracker = brain.findTracker(name) ?: brain.upsertTracker(
            Tracker(
                name = name,
                label = name.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                kind = requestedKind.ifBlank { inferKind(requestedUnit, settings) },
                unit = requestedUnit.ifBlank { settings.defaultCurrency },
                period = Tracker.PERIOD_MONTHLY
            )
        )

        val direction = args.optString("direction").ifBlank { Entry.DIR_OUT }
            .let { if (it == Entry.DIR_IN) Entry.DIR_IN else Entry.DIR_OUT }

        brain.addEntry(
            Entry(
                trackerId = tracker.id,
                amount = abs(amount),
                direction = direction,
                note = args.optString("note").takeIf { it.isNotBlank() },
                category = args.optString("category").takeIf { it.isNotBlank() },
                occurredAt = TimeUtil.parse(args.optString("occurred_at").takeIf { it.isNotBlank() })
                    ?: System.currentTimeMillis()
            )
        )
        effects.trackersChanged = true

        val status = brain.trackerStatus(brain.findTracker(name) ?: tracker)
        val verb = if (direction == Entry.DIR_OUT) "Logged" else "Added"
        return "$verb ${money(abs(amount), tracker)} on ${tracker.label}. ${summaryLine(status)}"
    }

    private fun trackerStatus(args: JSONObject): String {
        val name = args.optString("tracker").trim()
        if (name.isNotBlank()) {
            val tracker = brain.findTracker(name)
                ?: return "No tracker called '$name' yet. Create one with configure_tracker " +
                    "or just log something to it."
            return summaryLine(brain.trackerStatus(tracker))
        }
        val all = brain.allTrackerStatus()
        if (all.isEmpty()) return "No trackers set up yet."
        return all.joinToString("\n") { summaryLine(it) }
    }

    private fun configureTracker(
        args: JSONObject,
        settings: Settings,
        effects: ToolEffects
    ): String {
        val name = args.optString("tracker").trim()
        if (name.isBlank()) return "Need a tracker name."
        val existing = brain.findTracker(name)
        val unit = args.optString("unit").trim()
            .ifBlank { existing?.unit ?: settings.defaultCurrency }

        val updated = brain.upsertTracker(
            Tracker(
                id = existing?.id ?: 0,
                name = name,
                label = args.optString("label").trim().ifBlank {
                    existing?.label ?: name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                },
                kind = args.optString("kind").trim()
                    .ifBlank { existing?.kind ?: inferKind(unit, settings) },
                unit = unit,
                budget = optDoubleOrNull(args, "budget") ?: existing?.budget,
                period = args.optString("period").trim()
                    .ifBlank { existing?.period ?: Tracker.PERIOD_MONTHLY },
                startingBalance = optDoubleOrNull(args, "starting_balance")
                    ?: existing?.startingBalance
            )
        )
        effects.trackersChanged = true
        return "Tracker ready. " + summaryLine(brain.trackerStatus(updated))
    }

    private fun listEntries(args: JSONObject): String {
        val name = args.optString("tracker").trim()
        val limit = args.optInt("limit", 10).coerceIn(1, 50)
        val tracker = if (name.isBlank()) null else brain.findTracker(name)
        if (name.isNotBlank() && tracker == null) return "No tracker called '$name'."

        val entries = brain.recentEntries(tracker?.id, limit)
        if (entries.isEmpty()) return "No entries recorded yet."
        val byId = brain.allTrackers().associateBy { it.id }
        return entries.joinToString("\n") { e ->
            val owner = byId[e.trackerId]
            val sign = if (e.direction == Entry.DIR_OUT) "-" else "+"
            "[id ${e.id}] $sign${money(e.amount, owner)} ${owner?.label ?: "?"}" +
                (e.note?.let { " ($it)" } ?: "") +
                " — ${TimeUtil.relative(e.occurredAt)}"
        }
    }

    /**
     * Undo, for the tracker.
     *
     * Speech recognition mishears amounts and small models sometimes log the
     * same purchase twice, so a balance that can only ever grow wrong is one
     * nobody trusts. With no id the newest entry goes, because "undo that" is
     * nearly always about the last thing said.
     */
    private fun deleteEntry(args: JSONObject, effects: ToolEffects): String {
        val requested = args.optLong("id", -1L)
        val entry = if (requested > 0) {
            brain.recentEntries(null, 200).firstOrNull { it.id == requested }
                ?: return "No entry with id $requested."
        } else {
            brain.recentEntries(null, 1).firstOrNull() ?: return "There are no entries to undo."
        }
        val tracker = brain.allTrackers().firstOrNull { it.id == entry.trackerId }
        if (!brain.deleteEntry(entry.id)) return "That entry would not delete."
        effects.trackersChanged = true

        val what = entry.note?.let { " for $it" }.orEmpty()
        val head = "Removed ${money(entry.amount, tracker)}$what from ${tracker?.label ?: "its tracker"}."
        val status = tracker?.let { brain.trackerStatus(it) } ?: return head
        return "$head ${summaryLine(status)}"
    }

    /**
     * The arithmetic done here rather than in the model.
     *
     * "Where did my money go" is the question most likely to be answered with a
     * plausible invention, so every number in this reply is summed from the rows
     * and the model is left with nothing to do but read it out.
     */
    private fun spendingReport(args: JSONObject): String {
        val days = args.optInt("days", 30).coerceIn(1, 365)
        val name = args.optString("tracker").trim()
        val only = if (name.isBlank()) null else brain.findTracker(name)
        if (name.isNotBlank() && only == null) return "No tracker called '$name'."

        val now = System.currentTimeMillis()
        val from = now - days * 86_400_000L
        val entries = brain.entriesBetween(from, now, only?.id)
        if (entries.isEmpty()) return "Nothing recorded in the last $days days."

        val byId = brain.allTrackers().associateBy { it.id }
        val grouped = entries.groupBy { it.trackerId }

        return buildString {
            appendLine("Last $days days:")
            grouped.entries
                .sortedByDescending { (_, rows) ->
                    rows.filter { it.direction == Entry.DIR_OUT }.sumOf { it.amount }
                }
                .forEach { (trackerId, rows) ->
                    val tracker = byId[trackerId]
                    val out = rows.filter { it.direction == Entry.DIR_OUT }.sumOf { it.amount }
                    val income = rows.filter { it.direction == Entry.DIR_IN }.sumOf { it.amount }
                    append("- ${tracker?.label ?: "Unknown"}: ${money(out, tracker)} out")
                    if (income > 0) append(", ${money(income, tracker)} in")
                    append(" across ${rows.size} entries")
                    append(", averaging ${money(out / days, tracker)} a day")
                    appendLine(".")
                }

            val biggest = entries.filter { it.direction == Entry.DIR_OUT }
                .sortedByDescending { it.amount }
                .take(3)
            if (biggest.isNotEmpty()) {
                appendLine("Biggest single items:")
                biggest.forEach { e ->
                    val tracker = byId[e.trackerId]
                    appendLine(
                        "- ${money(e.amount, tracker)} ${e.note ?: tracker?.label ?: ""}" +
                            " (${TimeUtil.relative(e.occurredAt)})"
                    )
                }
            }
        }.trim()
    }

    private fun inferKind(unit: String, settings: Settings): String {
        val candidate = unit.ifBlank { settings.defaultCurrency }.uppercase(Locale.ROOT)
        return if (candidate in CURRENCIES) Tracker.KIND_MONEY else Tracker.KIND_QUANTITY
    }

    /** Money gets two decimals; counts drop a pointless trailing zero. */
    private fun money(amount: Double, tracker: Tracker?): String {
        val unit = tracker?.unit ?: ""
        val isMoney = tracker?.kind == Tracker.KIND_MONEY
        val number = if (isMoney) {
            String.format(Locale.US, "%.2f", amount)
        } else if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", amount)
        }
        return if (unit.isBlank()) number else "$number $unit"
    }

    private fun summaryLine(status: TrackerStatus): String = buildString {
        val t = status.tracker
        append("${t.label}: ")
        val parts = mutableListOf<String>()
        status.balance?.let { parts += "${money(it, t)} left" }
        status.budgetLeft?.let {
            parts += if (it >= 0) {
                "${money(it, t)} of budget left this ${Tracker.periodWord(t.period)}"
            } else {
                "${money(-it, t)} over budget this ${Tracker.periodWord(t.period)}"
            }
        }
        parts += "${money(status.periodSpent, t)} used this ${Tracker.periodWord(t.period)}"
        if (status.periodReceived > 0) parts += "${money(status.periodReceived, t)} added"
        append(parts.joinToString(", "))
    }

    // ------------------------------------------------------------------- tasks

    private fun addTask(args: JSONObject, effects: ToolEffects): String {
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

    private fun repeatRule(raw: String): String {
        val key = raw.trim().lowercase(Locale.ROOT)
        return when {
            key in Task.ALL_REPEATS -> key
            key.contains("day") || key.contains("täglich") -> Task.REPEAT_DAILY
            key.contains("week") || key.contains("wöchentlich") -> Task.REPEAT_WEEKLY
            key.contains("month") || key.contains("monatlich") -> Task.REPEAT_MONTHLY
            else -> Task.REPEAT_NONE
        }
    }

    private fun listTasks(args: JSONObject): String {
        val includeDone = args.optBoolean("include_done", false)
        val tasks = brain.tasks(includeDone)
        if (tasks.isEmpty()) return "No tasks."
        return tasks.joinToString("\n") { t ->
            val due = t.dueAt?.let { " — due ${TimeUtil.format(it)} (${TimeUtil.relative(it)})" }.orEmpty()
            val done = if (t.done) " [done]" else ""
            "- [id ${t.id}] ${t.title}$due$done"
        }
    }

    private fun completeTask(args: JSONObject, effects: ToolEffects): String {
        val id = args.optLong("id", -1L)
        if (id <= 0) return "Need a valid task id."
        val done = brain.completeTask(id) ?: return "No task with id $id."
        effects.tasksChanged = true
        if (!done.done && done.dueAt != null) {
            reminders.schedule(done)
            return "Done for this time: ${done.title}. It repeats ${done.repeatRule}; next " +
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
    private fun updateTask(args: JSONObject, effects: ToolEffects): String {
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

    private fun deleteTask(args: JSONObject, effects: ToolEffects): String {
        val id = args.optLong("id", -1L)
        if (id <= 0) return "Need a valid task id."
        val existing = brain.getTask(id) ?: return "No task with id $id."
        reminders.cancel(id)
        brain.deleteTask(id)
        effects.tasksChanged = true
        return "Removed the task: ${existing.title}."
    }

    // ---------------------------------------------------------------- thinking

    private fun nowText(): String {
        val now = System.currentTimeMillis()
        return "Current local date and time: ${TimeUtil.format(now)} (ISO ${TimeUtil.iso(now)})."
    }

    private fun calculate(args: JSONObject): String {
        val expression = args.optString("expression").trim()
        if (expression.isBlank()) return "Need an expression."
        return when (val result = Calculator.evaluate(expression)) {
            is Calculator.Result.Ok -> "$expression = ${Calculator.format(result.value)}"
            is Calculator.Result.Error -> "I could not work that out: ${result.reason}."
        }
    }

    private fun convert(args: JSONObject): String {
        val amount = number(args, "amount") ?: Double.NaN
        if (amount.isNaN()) return "Need an amount to convert."
        return when (
            val result = Units.convert(amount, args.optString("from"), args.optString("to"))
        ) {
            is Units.Result.Ok -> result.spoken + "."
            is Units.Result.Error -> result.reason
        }
    }

    private fun dateCalc(args: JSONObject): String {
        val from = args.optString("from").takeIf { it.isNotBlank() }
        val to = args.optString("to").takeIf { it.isNotBlank() }
        return when (args.optString("operation").trim().lowercase(Locale.ROOT)) {
            "add", "plus", "offset" -> {
                if (!args.has("amount")) return "For 'add' I need an amount."
                DateMath.add(from, args.optLong("amount", 0L), args.optString("unit"))
            }
            "weekday", "day", "which_day" -> DateMath.weekday(to ?: from)
            else -> {
                // "between" with only one date given means "from today to that one",
                // which is what "how long until" is always asking.
                if (to == null && from == null) return "Which date should I count to?"
                if (to == null) DateMath.between(null, from) else DateMath.between(from, to)
            }
        }
    }

    private suspend fun convertCurrency(args: JSONObject): String {
        val amount = number(args, "amount") ?: Double.NaN
        if (amount.isNaN()) return "Need an amount to convert."
        val from = args.optString("from").trim()
        val to = args.optString("to").trim()
        if (from.isBlank() || to.isBlank()) return "Need both currencies."
        return currency.convert(amount, from, to)
    }

    // ----------------------------------------------------------------- weather

    private suspend fun weather(args: JSONObject): String {
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

    // -------------------------------------------------------------------- maps
    //
    // These write their results onto the map as well as returning prose, so the
    // reply and the pins always describe the same lookup. The map has its own
    // state flow, which is why none of them touch ToolEffects.

    private suspend fun findPlaces(args: JSONObject, settings: Settings): String =
        navigator.findPlaces(
            query = args.optString("query").trim(),
            near = args.optString("near").trim().takeIf { it.isNotBlank() },
            radiusMeters = args.optInt("radius_m", 0).takeIf { it > 0 },
            limit = args.optInt("limit", 5),
            settings = settings
        )

    private suspend fun routeTo(args: JSONObject, settings: Settings): String =
        navigator.routeTo(
            destination = args.optString("destination").trim().takeIf { it.isNotBlank() },
            mode = args.optString("mode").trim().takeIf { it.isNotBlank() },
            settings = settings
        )

    private suspend fun startNavigation(args: JSONObject, settings: Settings): String =
        navigator.startNavigation(
            destination = args.optString("destination").trim().takeIf { it.isNotBlank() },
            mode = args.optString("mode").trim().takeIf { it.isNotBlank() },
            settings = settings
        )

    private suspend fun placeReminder(args: JSONObject): String {
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
        if (text.isBlank()) return "What should the reminder say?"
        if (!placeReminders.canWatch) {
            placeReminders.askPermission()
            return "A place reminder needs precise location, which Jarvis doesn't have (approximate " +
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
            text = text,
            place = name,
            point = target.point,
            leaving = args.optBoolean("leaving", false),
            every = args.optBoolean("every", false),
            here = locator.remembered()
        )
        return buildString {
            append("Set: ${watch.text}, ${watch.trigger}")
            if (watch.every) append(", every time")
            append(".")
            if (!watch.leaving && !watch.armed) append(" You're there now, so it counts from the next time you arrive.")
            if (!placeReminders.canWatchClosed) {
                placeReminders.askPermission()
                append(
                    " Tell the user, since a settings page just opened: for it to go off while Jarvis " +
                        "is closed, location has to be allowed \"all the time\" there."
                )
            }
        }
    }

    // --------------------------------------------------------------------- web

    private suspend fun webSearch(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) return "Need something to search for."
        val limit = args.optInt("limit", 5).coerceIn(1, 8)

        // Both at once: they are separate services, and a search is the slowest
        // thing most turns wait on.
        val (direct, results) = kotlinx.coroutines.coroutineScope {
            val answer = async { runCatching { web.instantAnswer(query) }.getOrNull() }
            val found = async { runCatching { web.search(query, limit) }.getOrDefault(emptyList()) }
            answer.await() to found.await()
        }
        if (direct == null && results.isEmpty()) {
            return "No results for '$query'. The search endpoint may be rate limiting."
        }
        return buildString {
            direct?.let { appendLine("Direct answer: $it").appendLine() }
            results.forEachIndexed { index, r ->
                appendLine("${index + 1}. ${r.title}")
                appendLine("   ${r.url}")
                if (r.snippet.isNotBlank()) appendLine("   ${r.snippet}")
            }
        }.trim()
    }

    // ------------------------------------------------------------------- lists

    private fun listAction(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        val name = args.optString("list").trim()
        val given = args.optJSONArray("items")?.let { array -> (0 until array.length()).map { array.optString(it) } }
            ?: listOfNotNull(args.optString("item").takeIf { it.isNotBlank() })
        // "eggs, milk and bread" handed over as one entry is three things.
        val items = given.flatMap { it.split(ITEM_SEPARATOR) }.map { it.trim() }.filter { it.isNotBlank() }
        if (action != "all" && name.isBlank()) return "Which list? Say its name, like 'shopping'."
        val label = com.lukas.jarvis.data.ListBook.canonical(name)

        fun describe(): String {
            val list = lists.current.find(name) ?: return "There is no $label list yet."
            if (list.items.isEmpty()) return "The $label list is empty."
            val open = list.open.map { it.text }
            val done = list.items.filter { it.done }.map { it.text }
            return buildString {
                append("${label.replaceFirstChar { it.titlecase(Locale.ROOT) }} list: ")
                append(if (open.isEmpty()) "everything is ticked off" else open.joinToString(", "))
                append(" (${open.size} open")
                if (done.isNotEmpty()) append("; done: ${done.joinToString(", ")}")
                append(").")
            }
        }

        return when (action) {
            "all" -> lists.current.lists.takeIf { it.isNotEmpty() }?.joinToString("\n") {
                "${it.name}: ${it.open.size} open, ${it.items.size - it.open.size} done"
            } ?: "There are no lists yet."
            "add" -> {
                if (items.isEmpty()) return "What should go on the $label list?"
                lists.change { it.add(name, items) }
                "Added ${items.joinToString(", ")}. " + describe()
            }
            "remove" -> {
                var gone = emptyList<String>()
                lists.change { book -> book.remove(name, items).also { gone = it.second }.first }
                if (gone.isEmpty()) "None of that is on the $label list. " + describe()
                else "Removed ${gone.joinToString(", ")}. " + describe()
            }
            "check", "uncheck" -> {
                var hit = emptyList<String>()
                lists.change { book -> book.check(name, items, action == "check").also { hit = it.second }.first }
                if (hit.isEmpty()) "None of that is on the $label list. " + describe()
                else "${if (action == "check") "Ticked off" else "Put back"} ${hit.joinToString(", ")}. " + describe()
            }
            "clear_done" -> {
                lists.change { it.clear(name, onlyDone = true) }
                "Cleared what was ticked off. " + describe()
            }
            "clear" -> {
                lists.change { it.clear(name, onlyDone = false) }
                "Emptied the $label list."
            }
            "delete" -> {
                if (lists.current.find(name) == null) return "There is no $label list."
                lists.change { it.delete(name) }
                "Deleted the $label list."
            }
            else -> describe()
        }
    }

    // ---------------------------------------------------------------- calendar

    private suspend fun addEvent(args: JSONObject, settings: Settings): String {
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

    // -------------------------------------------------------------- the phone

    private fun setAlarm(args: JSONObject): String {
        val raw = args.optString("time").trim()
        if (raw.isBlank()) return "Need a time for the alarm."

        // "07:30" on its own is the common case and is not an ISO timestamp, so
        // it is read directly rather than forced through the parser.
        val short = Regex("^(\\d{1,2})[:.](\\d{2})$").find(raw)
        if (short != null) {
            return launcher.setAlarm(
                hour = short.groupValues[1].toInt(),
                minute = short.groupValues[2].toInt(),
                label = args.optString("label").takeIf { it.isNotBlank() }
            )
        }

        val at = TimeUtil.parse(raw) ?: return "I could not read '$raw' as a time."
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        return launcher.setAlarm(
            hour = calendar.get(Calendar.HOUR_OF_DAY),
            minute = calendar.get(Calendar.MINUTE),
            label = args.optString("label").takeIf { it.isNotBlank() }
        )
    }

    private fun setTimer(args: JSONObject): String {
        val minutes = number(args, "minutes") ?: Double.NaN
        if (minutes.isNaN() || minutes <= 0) return "A timer needs a length in minutes."
        val seconds = (minutes * 60).toInt().coerceAtLeast(1)
        val label = args.optString("label").takeIf { it.isNotBlank() }
        val sleep = args.optBoolean("stop_music", false)
        val timer = timers.start(seconds, label, sleep)
        val length = com.lukas.jarvis.notify.Timers.spoken(timer.lengthMs)
        if (sleep) return "Sleep timer set: the music stops in $length, at ${TimeUtil.formatTime(timer.endsAt)}."
        val what = if (label == null) "Timer set for $length" else "${timer.label} timer set for $length"
        return "$what — done at ${TimeUtil.formatTime(timer.endsAt)}."
    }

    private fun timerAction(args: JSONObject): String {
        val which = args.optString("label").takeIf { it.isNotBlank() }
        return when (args.optString("action").trim().lowercase(Locale.ROOT)) {
            "cancel", "stop" -> {
                val gone = timers.cancel(which)
                if (gone.isEmpty()) "No timer like that is running. " + timers.describe()
                else "Cancelled: ${gone.joinToString { it.label }}."
            }
            "add" -> {
                val minutes = number(args, "minutes") ?: Double.NaN
                if (minutes.isNaN() || minutes == 0.0) return "How many minutes to add?"
                val moved = timers.extend(which, (minutes * 60).toInt())
                    ?: return "No timer like that is running."
                "${moved.label} now ends at ${TimeUtil.formatTime(moved.endsAt)} — " +
                    "${com.lukas.jarvis.notify.Timers.spoken(moved.leftMs())} left."
            }
            else -> timers.describe()
        }
    }

    /**
     * For an appointment somewhere, a reminder to set off: the travel time
     * from where the phone is now, in the usual mode, plus ten minutes to
     * spare. Nothing when the place, the location or the route is unknown,
     * or when it would already be time to go.
     */
    private suspend fun leaveReminder(title: String, start: Long, location: String?, settings: Settings): String {
        if (location == null || !settings.mapsEnabled) return ""
        val seconds = runCatching { navigator.travelSeconds(location, settings) }.getOrNull() ?: return ""
        val leaveAt = start - (seconds * 1000).toLong() - 10 * 60 * 1000L
        if (leaveAt < System.currentTimeMillis() + 2 * 60 * 1000L) return ""
        val way = "${com.lukas.jarvis.maps.Geo.formatDuration(seconds)} " +
            com.lukas.jarvis.maps.Geo.modeVerb(com.lukas.jarvis.maps.Geo.normalizeMode(settings.travelMode))
        val task = Task(title = "Leave for $title", notes = "About $way to $location", dueAt = leaveAt)
        val saved = task.copy(id = brain.addTask(task))
        reminders.schedule(saved)
        return " I'll remind you to leave at ${TimeUtil.formatTime(leaveAt)} — it's about $way from here."
    }

    private fun deviceStatus(args: JSONObject): String =
        when (args.optString("what").trim().lowercase(Locale.ROOT)) {
            "battery" -> device.battery()
            "network", "internet", "connection" -> device.connection()
            "storage", "space" -> device.storage()
            "ringer", "sound" -> device.ringer() + " " + device.describeQuiet()
            "volume" -> device.volumes()
            "brightness", "screen" -> device.describeBrightness()
            "hardware", "model", "phone" -> device.hardware()
            else -> device.status()
        }

    private fun clipboard(args: JSONObject): String =
        when (args.optString("action").trim().lowercase(Locale.ROOT)) {
            "read", "paste", "get" -> device.paste()
            else -> device.copy(args.optString("text"))
        }

    private fun playback(args: JSONObject): String =
        when (args.optString("action").trim().lowercase(Locale.ROOT)) {
            "pause", "stop" -> phone.pause()
            "resume", "play" -> phone.play(null)
            "next", "skip" -> phone.next()
            "previous", "back" -> phone.previous()
            "volume" -> if (number(args, "percent") != null) {
                phone.setVolume(number(args, "percent")!!.toInt())
            } else {
                phone.volume()
            }
            else -> "I can pause, resume, skip, go back, or set the volume."
        }

    private fun bluetooth(args: JSONObject): String =
        when (args.optString("action").trim().lowercase(Locale.ROOT)) {
            "open_settings", "settings", "open" -> phone.openBluetoothSettings()
            else -> phone.bluetoothDevices()
        }

    // ------------------------------------------------------------- the screen

    private fun show(args: JSONObject): String {
        // The history is not an element of its own but an overlay on the
        // assistant's screen; asked for by name, it opens there.
        val raw = args.optString("element").trim().lowercase(Locale.ROOT)
        if (raw.contains("history") || raw.contains("past conversation") || raw.contains("verlauf")) {
            stage.show(Element.Globe, StageStore.HISTORY)
            return "Showing the conversation history."
        }
        val wanted = Element.match(args.optString("element"))
            ?: return "I do not have an element called that. I have: ${Element.names()}."
        if (wanted == Element.Settings) {
            // "Open the voice settings", "change your colour": the right tab,
            // not the first one.
            val words = (raw + " " + args.optString("note")).lowercase(Locale.ROOT)
            val tab = SETTINGS_TABS.entries.firstOrNull { (_, cues) -> cues.any { it in words } }?.key
            if (tab != null) {
                stage.show(Element.Settings, "settings:tab:$tab")
                return "Showing the settings."
            }
        }
        stage.show(wanted, args.optString("note").trim())
        return "Showing the ${wanted.title.lowercase(Locale.ROOT)}."
    }

    // ------------------------------------------------------------------- eyes

    private fun takePhoto(args: JSONObject): String {
        val question = args.optString("question").trim().ifBlank { "What is this?" }
        camera.ask(question, fromGallery = args.optString("source") == "gallery")
        return "The camera is opening for the user now. Tell them in one short line to take " +
            "the picture, then stop. You will get the photo in their next message."
    }

    private suspend fun wikipedia(args: JSONObject): String {
        val topic = args.optString("topic").trim()
        if (topic.isBlank()) return "Need a topic to look up."
        val language = args.optString("language").trim().ifBlank { Locale.getDefault().language }
        return web.wikipedia(topic, language)
            ?: "Wikipedia has no article that matches '$topic'."
    }

    private suspend fun shareLocation(args: JSONObject): String {
        val (_, link) = navigator.locationLink()
            ?: return "I do not have a location fix to share right now."
        val number = args.optString("number").trim()
        val who = args.optString("who").trim().ifBlank { number }
        if (number.isBlank()) return "Current location link: $link"
        if (!messenger.maySend) {
            messenger.requestPermission()
            return "I need permission to send texts — it is asking you now. The link is $link"
        }
        val sent = messenger.sendSms(number, "I'm here: $link")
        return "$sent (location sent to $who)"
    }

    // --------------------------------------------------------------- routines

    private fun createRoutine(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isBlank()) return "A routine needs a name."
        val steps = args.optJSONArray("steps").toStringList().ifEmpty {
            // Small models sometimes send the list as one string.
            args.optString("steps").split('\n', ';').map { it.trim() }.filter { it.isNotBlank() }
        }
        if (steps.isEmpty()) return "A routine needs at least one step."
        val rawTime = args.optString("time").trim()
        val time = Routines.normalizeTime(rawTime)
        val saved = routines.save(
            Routine(
                name = name,
                steps = steps,
                time = time,
                days = com.lukas.jarvis.auto.RoutineDays.parse(args.optString("days")),
                quiet = args.optBoolean("quiet", false)
            )
        )
        val whenLine = when {
            saved.time == null -> ""
            saved.quiet -> " It runs by itself ${saved.schedule} and sends the answer as a notification."
            else -> " I will offer it ${saved.schedule}."
        }
        return "Saved the '${saved.name}' routine with ${saved.steps.size} step(s): " +
            saved.steps.joinToString("; ") + "." + whenLine
    }

    private suspend fun runRoutine(args: JSONObject, settings: Settings): String {
        val routine = routines.find(args.optString("name"))
            ?: return if (routines.all.value.isEmpty()) {
                "There are no routines yet. Tell me the steps and I will save one."
            } else {
                "No routine by that name. There is: " +
                    routines.all.value.joinToString(", ") { it.name } + "."
            }
        val runner = routineRunner
            ?: return "A routine is already running, so this one was not started inside it."
        return runner(routine, settings)
    }

    private fun listRoutines(): String {
        val all = routines.all.value
        if (all.isEmpty()) return "No routines saved yet."
        return all.joinToString("\n") { routine ->
            "- ${routine.name}" +
                (routine.time?.let { " (${routine.schedule}" + (if (routine.quiet) ", runs quietly)" else ")") } ?: "") +
                ": " + routine.steps.joinToString("; ")
        }
    }

    // ---------------------------------------------------------------- messages

    /**
     * A text, sent rather than drafted.
     *
     * The point of asking an assistant to send something is not having to pick
     * the phone up, so a draft waiting on a screen is a job half done. The
     * sentence that comes back is in the past tense only when it really went.
     */
    private fun sendMessage(args: JSONObject): String {
        val text = args.optString("text").trim()
        if (text.isBlank()) return "There was no message to send."
        val number = args.optString("number")
        // This used to hand the message to the phone's own messaging app when
        // the permission was missing, which pushed Jarvis into the background
        // and looked exactly like the app closing. Asking for the permission
        // keeps everything where the user left it.
        if (!messenger.maySend) {
            messenger.requestPermission()
            return "I need permission to send texts — it is asking you now. " +
                "Say it again once you have allowed it."
        }
        return messenger.sendSms(number, text)
    }

    /**
     * A new conversation in WhatsApp, Telegram or Signal.
     *
     * The name is resolved against contacts here rather than by the model,
     * because a model guessing a phone number is how the right message reaches
     * the wrong person.
     */
    private fun sendChatMessage(args: JSONObject): String {
        val chat = Chat.match(args.optString("app"))
            ?: return "I can start a conversation in WhatsApp, Telegram or Signal."
        val who = args.optString("who").trim()
        val text = args.optString("text").trim()
        if (who.isBlank()) return "Who should it go to?"
        if (text.isBlank()) return "There was no message to send."
        return chats.send(chat, who, text)
    }

    /**
     * An answer to whatever just came in, through the notification it arrived on.
     *
     * This is the only route into WhatsApp and the like that Android supports,
     * and it only exists while the notification does — once the message has been
     * read on the phone, its notification is gone and so is the way back in.
     * Saying that is more use than a generic failure.
     */
    private fun replyToMessage(args: JSONObject): String {
        val text = args.optString("text").trim()
        if (text.isBlank()) return "There was no reply to send."

        val target = ReplyListener.match(args.optString("who").takeIf { it.isNotBlank() })
            ?: return if (ReplyListener.waiting().isEmpty()) {
                "Nothing is waiting that I can answer. A message can only be answered " +
                    "while its notification is still there."
            } else {
                "I cannot find that conversation among the ones waiting."
            }

        return if (ReplyListener.reply(target, text)) {
            "Replied to ${target.from.ifBlank { target.appLabel }}."
        } else {
            "${target.appLabel} would not take the reply."
        }
    }

    private fun unreadMessages(): String {
        val waiting = ReplyListener.waiting()
        if (waiting.isEmpty()) return "Nothing is waiting to be answered."
        return waiting.take(8).joinToString("\n") { item ->
            val who = item.from.ifBlank { "Someone" }
            "$who on ${item.appLabel} (${TimeUtil.relative(item.postedAt)}): " +
                item.text.take(200)
        }
    }

    // ------------------------------------------------------------ schema sugar

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put(
                    "parameters",
                    JSONObject().apply {
                        put("type", "object")
                        put("properties", properties)
                        put("required", JSONArray(required))
                    }
                )
            }
        )
    }

    private fun props(vararg entries: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply { entries.forEach { (key, value) -> put(key, value) } }

    private fun str(description: String, values: List<String>? = null): JSONObject =
        JSONObject().apply {
            put("type", "string")
            put("description", description)
            values?.let { put("enum", JSONArray(it)) }
        }

    private fun num(description: String): JSONObject =
        JSONObject().apply {
            put("type", "number")
            put("description", description)
        }

    private fun int(description: String): JSONObject =
        JSONObject().apply {
            put("type", "integer")
            put("description", description)
        }

    private fun bool(description: String): JSONObject =
        JSONObject().apply {
            put("type", "boolean")
            put("description", description)
        }

    private fun arr(description: String): JSONObject =
        JSONObject().apply {
            put("type", "array")
            put("description", description)
            put("items", JSONObject().apply { put("type", "string") })
        }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }

    /**
     * A number however a small model chose to write it: 50, 50.0, "50",
     * "50%" or "30 minutes". A plain optInt reads "50%" as 0, which for a
     * volume means silence.
     */
    private fun number(args: JSONObject, key: String): Double? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val raw = args.opt(key)) {
            is Number -> raw.toDouble()
            is String -> com.lukas.jarvis.core.Numbers.first(raw)
            else -> null
        }?.takeIf { !it.isNaN() }
    }

    private fun optDoubleOrNull(args: JSONObject, key: String): Double? = number(args, key)

    private companion object {
        /** Settings tabs by what people call what is on them. */
        val SETTINGS_TABS = linkedMapOf(
            1 to listOf("voice", "speak", "speech", "wake word", "stimme", "sprache"),
            2 to listOf("look", "colour", "color", "theme", "accent", "backdrop", "farbe", "design"),
            3 to listOf("brain", "model", "api key", "provider", "modell"),
            4 to listOf("power", "abilit", "permission", "home assistant", "smart home", "fähigkeit"),
            5 to listOf("backup", "restore", "data", "sicherung"),
            0 to listOf("personality", "name", "about me", "instruction", "persönlichkeit")
        )
        val ITEM_SEPARATOR = Regex("\\s*(?:,|;|\\band\\b|\\bund\\b)\\s*")
        val CURRENCIES = setOf(
            "EUR", "USD", "GBP", "CHF", "PLN", "CZK", "SEK", "NOK", "DKK",
            "CAD", "AUD", "JPY", "TRY", "HUF", "RON", "BGN", "INR", "BRL"
        )
    }
}
