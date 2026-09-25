package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import java.util.Locale

/**
 * The families the tools fall into.
 *
 * A family is what a person thinks in — "it can do my money", "it can do the
 * weather" — so it is the unit the Skills screen shows, the unit a switch in
 * Settings turns off, and the icon on the chip under a reply.
 */
enum class ToolGroup {
    Memory, Money, Tasks, Thinking, Web, Weather, Places,
    Calendar, People, Phone, Messages, Media, Screen, Vision, Automation,
    News, Language, Create, Markets, Knowledge, Fun, Home
}

/**
 * What is known about one tool beyond its schema.
 *
 * [doing] is the line the dot shows while it runs, [chip] the word left under
 * the reply afterwards. [readOnly] is the one that changes behaviour: a tool
 * that only reads can run beside others in the same round and can be answered
 * from this turn's own cache when a small model asks for it twice.
 */
data class ToolInfo(
    val name: String,
    val group: ToolGroup,
    val doing: String,
    val chip: String,
    val readOnly: Boolean = false
)

/**
 * Every tool, described once.
 *
 * Before this the stage line knew a dozen tools by name and called the rest
 * "working", and nothing on screen said which tools a reply had used. One table
 * feeds the agent loop, the live trail and the Skills screen, so a tool added in
 * one place cannot be half-known in the others.
 */
object ToolCatalog {

    val ALL: List<ToolInfo> = listOf(
        // memory
        ToolInfo("remember", ToolGroup.Memory, "saving that", "Saved"),
        ToolInfo("recall", ToolGroup.Memory, "checking memory", "Memory", readOnly = true),
        ToolInfo("update_memory", ToolGroup.Memory, "correcting a memory", "Memory"),
        ToolInfo("forget", ToolGroup.Memory, "forgetting that", "Forgot"),
        ToolInfo("recall_conversation", ToolGroup.Memory, "looking back", "Past talks", readOnly = true),

        // money and anything else that is counted
        ToolInfo("log_entry", ToolGroup.Money, "logging it", "Logged"),
        ToolInfo("tracker_status", ToolGroup.Money, "checking the numbers", "Balances", readOnly = true),
        ToolInfo("configure_tracker", ToolGroup.Money, "setting that up", "Tracker"),
        ToolInfo("list_entries", ToolGroup.Money, "reading the entries", "Entries", readOnly = true),
        ToolInfo("spending_report", ToolGroup.Money, "adding it up", "Report", readOnly = true),
        ToolInfo("delete_entry", ToolGroup.Money, "taking that back", "Undone"),

        // tasks
        ToolInfo("add_task", ToolGroup.Tasks, "adding a reminder", "Reminder"),
        ToolInfo("list", ToolGroup.Tasks, "the list", "List"),
        ToolInfo("list_tasks", ToolGroup.Tasks, "checking tasks", "Tasks", readOnly = true),
        ToolInfo("complete_task", ToolGroup.Tasks, "ticking it off", "Done"),
        ToolInfo("update_task", ToolGroup.Tasks, "moving the task", "Moved"),
        ToolInfo("delete_task", ToolGroup.Tasks, "removing the task", "Removed"),

        // exact answers
        ToolInfo("now", ToolGroup.Thinking, "checking the time", "Clock", readOnly = true),
        ToolInfo("calculate", ToolGroup.Thinking, "working it out", "Maths", readOnly = true),
        ToolInfo("convert_units", ToolGroup.Thinking, "converting", "Units", readOnly = true),
        ToolInfo("date_calc", ToolGroup.Thinking, "counting the days", "Dates", readOnly = true),
        ToolInfo("briefing", ToolGroup.Thinking, "gathering your day", "Briefing", readOnly = true),

        // the internet
        ToolInfo("web_search", ToolGroup.Web, "searching the web", "Web", readOnly = true),
        ToolInfo("open_url", ToolGroup.Web, "reading a page", "Page", readOnly = true),
        ToolInfo("convert_currency", ToolGroup.Web, "checking the rate", "Currency", readOnly = true),
        ToolInfo("wikipedia", ToolGroup.Web, "reading Wikipedia", "Wikipedia", readOnly = true),
        ToolInfo("weather", ToolGroup.Weather, "checking the sky", "Weather", readOnly = true),

        // the world, beyond a search box
        ToolInfo("news", ToolGroup.News, "reading the headlines", "News", readOnly = true),
        ToolInfo("translate", ToolGroup.Language, "translating", "Translate", readOnly = true),
        ToolInfo("interpreter", ToolGroup.Language, "opening the interpreter", "Interpreter"),
        ToolInfo("define_word", ToolGroup.Language, "opening the dictionary", "Dictionary", readOnly = true),
        ToolInfo("market_price", ToolGroup.Markets, "checking the markets", "Markets", readOnly = true),
        ToolInfo("holidays", ToolGroup.Knowledge, "checking the holidays", "Holidays", readOnly = true),
        ToolInfo("recipe", ToolGroup.Knowledge, "finding a recipe", "Recipe", readOnly = true),
        ToolInfo("sports", ToolGroup.Knowledge, "checking the scores", "Sports", readOnly = true),
        ToolInfo("tv_show", ToolGroup.Knowledge, "looking up the show", "TV", readOnly = true),
        ToolInfo("book", ToolGroup.Knowledge, "looking up the book", "Books", readOnly = true),
        ToolInfo("fun", ToolGroup.Fun, "finding something good", "Fun", readOnly = true),
        ToolInfo("random", ToolGroup.Fun, "rolling the dice", "Random"),
        ToolInfo("generate_image", ToolGroup.Create, "drawing", "Picture"),

        // the house
        ToolInfo("home_status", ToolGroup.Home, "checking the house", "Home", readOnly = true),
        ToolInfo("home_control", ToolGroup.Home, "working the house", "Home"),

        // places; these draw on the map, so none of them counts as a pure read
        ToolInfo("find_places", ToolGroup.Places, "looking around you", "Places"),
        ToolInfo("route_to", ToolGroup.Places, "finding the way", "Route"),
        ToolInfo("start_navigation", ToolGroup.Places, "opening directions", "Navigation"),
        ToolInfo("where_am_i", ToolGroup.Places, "checking where you are", "Location"),
        ToolInfo("save_place", ToolGroup.Places, "saving the spot", "Saved place"),
        ToolInfo("saved_places", ToolGroup.Places, "checking your places", "Places", readOnly = true),
        ToolInfo("forget_place", ToolGroup.Places, "forgetting the place", "Place"),
        ToolInfo("place_reminder", ToolGroup.Places, "setting a place reminder", "Place reminder"),
        ToolInfo("share_location", ToolGroup.Places, "sharing where you are", "Location"),

        // calendar and people
        ToolInfo("calendar", ToolGroup.Calendar, "reading your calendar", "Calendar", readOnly = true),
        ToolInfo("change_calendar_event", ToolGroup.Calendar, "changing the appointment", "Calendar"),
        ToolInfo("add_calendar_event", ToolGroup.Calendar, "filling in the event", "Event"),
        ToolInfo("find_contact", ToolGroup.People, "looking them up", "Contacts", readOnly = true),

        // the phone itself
        ToolInfo("set_alarm", ToolGroup.Phone, "setting the alarm", "Alarm"),
        ToolInfo("set_timer", ToolGroup.Phone, "starting the timer", "Timer"),
        ToolInfo("timers", ToolGroup.Phone, "checking the timers", "Timers"),
        ToolInfo("show_alarms", ToolGroup.Phone, "opening your alarms", "Alarms"),
        ToolInfo("device_status", ToolGroup.Phone, "checking the phone", "Phone", readOnly = true),
        ToolInfo("torch", ToolGroup.Phone, "the torch", "Torch"),
        ToolInfo("ringer", ToolGroup.Phone, "the ringer", "Ringer"),
        ToolInfo("volume", ToolGroup.Phone, "the volume", "Volume"),
        ToolInfo("brightness", ToolGroup.Phone, "the brightness", "Brightness"),
        ToolInfo("do_not_disturb", ToolGroup.Phone, "do not disturb", "Quiet"),
        ToolInfo("clipboard", ToolGroup.Phone, "the clipboard", "Clipboard"),
        ToolInfo("open_app", ToolGroup.Phone, "opening the app", "App"),
        ToolInfo("open_settings_page", ToolGroup.Phone, "opening settings", "Settings"),
        ToolInfo("open_link", ToolGroup.Phone, "opening the page", "Browser"),
        ToolInfo("system_action", ToolGroup.Phone, "pressing the button", "Phone"),

        // reaching people
        ToolInfo("call", ToolGroup.Messages, "readying the call", "Call"),
        ToolInfo("place_call", ToolGroup.Messages, "ringing", "Call"),
        ToolInfo("cancel_call", ToolGroup.Messages, "dropping the call", "Call"),
        ToolInfo("dial", ToolGroup.Messages, "dialling", "Dialler"),
        ToolInfo("send_message", ToolGroup.Messages, "sending the text", "Text"),
        ToolInfo("send_chat_message", ToolGroup.Messages, "writing the message", "Chat"),
        ToolInfo("reply_to_message", ToolGroup.Messages, "replying", "Reply"),
        ToolInfo("unread_messages", ToolGroup.Messages, "checking messages", "Inbox", readOnly = true),
        ToolInfo("send_email", ToolGroup.Messages, "drafting the email", "Email"),
        ToolInfo("share", ToolGroup.Messages, "sharing", "Share"),

        // media and the screen
        ToolInfo("play_music", ToolGroup.Media, "starting the music", "Music"),
        ToolInfo("control_playback", ToolGroup.Media, "the music", "Playback"),
        ToolInfo("now_playing", ToolGroup.Media, "checking what's playing", "Playing", readOnly = true),
        ToolInfo("bluetooth", ToolGroup.Media, "checking Bluetooth", "Bluetooth"),
        ToolInfo("show", ToolGroup.Screen, "putting it on screen", "Screen"),

        // eyes
        ToolInfo("take_photo", ToolGroup.Vision, "opening the camera", "Camera"),
        ToolInfo("read_screen", ToolGroup.Vision, "reading your screen", "Screen", readOnly = true),
        ToolInfo("open_camera", ToolGroup.Vision, "opening the camera app", "Camera"),

        // doing several things at once
        ToolInfo("create_routine", ToolGroup.Automation, "setting up the routine", "Routine"),
        ToolInfo("run_routine", ToolGroup.Automation, "running the routine", "Routine"),
        ToolInfo("list_routines", ToolGroup.Automation, "checking routines", "Routines", readOnly = true),
        ToolInfo("delete_routine", ToolGroup.Automation, "removing the routine", "Routine")
    )

    private val byName: Map<String, ToolInfo> = ALL.associateBy { it.name }

    fun info(name: String): ToolInfo? = byName[name]

    fun doing(name: String): String = info(name)?.doing ?: "working"

    fun chip(name: String): String = info(name)?.chip ?: name.replace('_', ' ')

    fun isReadOnly(name: String): Boolean = info(name)?.readOnly == true

    fun count(group: ToolGroup): Int = ALL.count { it.group == group }

    /**
     * The names small models reach for instead of the real ones.
     *
     * A free-tier model that has seen a thousand weather plugins will ask for
     * `get_weather` however clearly the schema says `weather`. Failing that call
     * costs a whole round of quota to learn nothing, so the obvious misnamings
     * are simply taken to mean what they plainly mean.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "get_weather" to "weather", "weather_forecast" to "weather", "forecast" to "weather",
        "search" to "web_search", "search_web" to "web_search", "google" to "web_search",
        "internet_search" to "web_search", "browse" to "web_search",
        "read_url" to "open_url", "fetch_url" to "open_url", "open_page" to "open_url",
        "read_page" to "open_url",
        "save_memory" to "remember", "store_memory" to "remember", "memorize" to "remember",
        "add_memory" to "remember",
        "search_memory" to "recall", "get_memory" to "recall", "memory_search" to "recall",
        "recall_memory" to "recall",
        "set_reminder" to "add_task", "create_reminder" to "add_task",
        "shopping_list" to "list", "add_to_list" to "list", "manage_list" to "list", "lists" to "list",
        "add_reminder" to "add_task", "create_task" to "add_task", "reminder" to "add_task",
        "get_tasks" to "list_tasks", "tasks" to "list_tasks",
        "reschedule_task" to "update_task", "snooze" to "update_task",
        "edit_task" to "update_task", "remove_task" to "delete_task",
        "get_time" to "now", "current_time" to "now", "time" to "now",
        "get_date" to "now", "date" to "now",
        "calculator" to "calculate", "math" to "calculate", "compute" to "calculate",
        "calc" to "calculate",
        "convert" to "convert_units", "unit_convert" to "convert_units",
        "currency" to "convert_currency", "exchange_rate" to "convert_currency",
        "convert_money" to "convert_currency",
        "days_between" to "date_calc", "date_diff" to "date_calc", "count_days" to "date_calc",
        "log_expense" to "log_entry", "add_expense" to "log_entry",
        "log_purchase" to "log_entry", "add_entry" to "log_entry",
        "get_balance" to "tracker_status", "balance" to "tracker_status",
        "undo_entry" to "delete_entry", "remove_entry" to "delete_entry",
        "search_places" to "find_places", "nearby" to "find_places",
        "find_nearby" to "find_places", "places" to "find_places",
        "directions" to "route_to", "get_directions" to "route_to", "route" to "route_to",
        "navigate" to "start_navigation",
        "location" to "where_am_i", "get_location" to "where_am_i",
        "current_location" to "where_am_i",
        "calendar_events" to "calendar", "get_calendar" to "calendar", "events" to "calendar",
        "contacts" to "find_contact", "lookup_contact" to "find_contact",
        "search_contacts" to "find_contact",
        "send_sms" to "send_message", "sms" to "send_message", "text" to "send_message",
        "send_whatsapp" to "send_chat_message", "whatsapp" to "send_chat_message",
        "make_call" to "call", "phone_call" to "call", "call_contact" to "call",
        "alarm" to "set_alarm", "timer" to "set_timer", "start_timer" to "set_timer",
        "flashlight" to "torch",
        "play" to "play_music", "play_song" to "play_music",
        "open" to "open_app", "launch_app" to "open_app", "open_application" to "open_app",
        "display" to "show", "show_screen" to "show", "show_element" to "show",
        "camera" to "take_photo", "photo" to "take_photo", "look" to "take_photo",
        "analyze_image" to "take_photo", "scan" to "take_photo", "see" to "take_photo",
        "wiki" to "wikipedia", "encyclopedia" to "wikipedia",
        "park" to "save_place", "remember_place" to "save_place", "save_location" to "save_place",
        "list_places" to "saved_places", "my_places" to "saved_places",
        "lock_phone" to "system_action", "lock_screen" to "system_action",
        "take_screenshot" to "system_action", "screenshot" to "system_action",
        "go_back" to "system_action", "go_home" to "system_action", "global_action" to "system_action",
        "interpret" to "interpreter", "interpreter_mode" to "interpreter", "live_translate" to "interpreter",
        "conversation_mode" to "interpreter",
        "location_reminder" to "place_reminder", "geofence" to "place_reminder",
        "remind_at_place" to "place_reminder", "add_place_reminder" to "place_reminder",
        "send_location" to "share_location", "my_location" to "share_location",
        "routine" to "run_routine", "start_routine" to "run_routine",
        "make_routine" to "create_routine", "add_routine" to "create_routine",
        "routines" to "list_routines",
        "headlines" to "news", "get_news" to "news", "news_search" to "news",
        "translate_text" to "translate", "translation" to "translate",
        "define" to "define_word", "dictionary" to "define_word", "definition" to "define_word",
        "stock_price" to "market_price", "crypto_price" to "market_price", "stock" to "market_price",
        "get_stock" to "market_price", "price" to "market_price",
        "public_holidays" to "holidays", "get_recipe" to "recipe", "find_recipe" to "recipe",
        "sports_score" to "sports", "team" to "sports", "tv" to "tv_show", "show_info" to "tv_show",
        "find_book" to "book", "joke" to "fun", "tell_joke" to "fun", "fact" to "fun", "quote" to "fun",
        "coin_flip" to "random", "flip_coin" to "random", "roll_dice" to "random", "dice" to "random",
        "random_number" to "random", "image" to "generate_image", "draw" to "generate_image",
        "create_image" to "generate_image", "imagine" to "generate_image", "text_to_image" to "generate_image",
        "screen" to "read_screen", "read_the_screen" to "read_screen", "screen_text" to "read_screen",
        "summarize_screen" to "read_screen", "get_screen" to "read_screen",
        "turn_on" to "home_control", "turn_off" to "home_control", "lights" to "home_control",
        "smart_home" to "home_control", "home_assistant" to "home_control", "set_light" to "home_control",
        "house_status" to "home_status", "get_devices" to "home_status", "devices" to "home_status"
    )

    /**
     * The real tool a requested name refers to, among those switched on.
     *
     * Null when nothing fits — including when the name is right but its ability
     * is switched off, which the caller says in words rather than running it
     * anyway behind the user's back.
     */
    fun resolve(requested: String, available: Set<String>): String? {
        val key = normalize(requested)
        if (key in available) return key
        return canonical(requested)?.takeIf { it in available }
    }

    /** The real tool a name means, whether or not its ability is switched on. */
    fun canonical(requested: String): String? {
        val key = normalize(requested)
        if (byName.containsKey(key)) return key
        return ALIASES[key]
    }

    /** The nearest few real names, so a wrong guess can correct itself next round. */
    fun closest(requested: String, available: Collection<String>, limit: Int = 3): List<String> {
        val key = normalize(requested)
        return available
            .map { it to distance(key, it) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun normalize(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
            .removePrefix("functions.").removePrefix("tool_")

    /** Plain edit distance; the names are short enough that nothing cleverer pays. */
    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}

/** The switches in Settings -> Abilities, each owning some of the families. */
enum class AbilitySwitch {
    Web, Weather, Maps, Calendar, Contacts, Phone, Home;

    fun isOn(settings: Settings): Boolean = when (this) {
        Web -> settings.webSearchEnabled
        Weather -> settings.weatherEnabled
        Maps -> settings.mapsEnabled
        Calendar -> settings.calendarEnabled
        Contacts -> settings.contactsEnabled
        Phone -> settings.deviceControlEnabled
        Home -> settings.homeReady
    }

    fun applyTo(settings: Settings, on: Boolean): Settings = when (this) {
        Web -> settings.copy(webSearchEnabled = on)
        Weather -> settings.copy(weatherEnabled = on)
        Maps -> settings.copy(mapsEnabled = on)
        Calendar -> settings.copy(calendarEnabled = on)
        Contacts -> settings.copy(contactsEnabled = on)
        Phone -> settings.copy(deviceControlEnabled = on)
        Home -> settings.copy(homeEnabled = on)
    }
}

/**
 * One thing Jarvis can do, as a person would put it, with sentences that reach it.
 *
 * The examples are not decoration. Each is the shortest thing to say that lands
 * on that family's tools, and tapping one on the Skills screen sends it, so the
 * list doubles as the fastest way to find out what the assistant is for.
 */
data class Ability(
    val group: ToolGroup,
    val title: String,
    val summary: String,
    val examples: List<String>,
    /** Null for the families that are always on. */
    val switch: AbilitySwitch? = null
) {
    fun isOn(settings: Settings): Boolean = switch?.isOn(settings) ?: true
    val toolCount: Int get() = ToolCatalog.count(group)
}

object Abilities {

    val ALL: List<Ability> = listOf(
        Ability(
            ToolGroup.Memory,
            "Memory",
            "Keeps anything you tell it and finds it again, along with what was said before.",
            listOf(
                "Remember my bike lock code is 4821",
                "What's my bike lock code?",
                "What did I tell you about the landlord?"
            )
        ),
        Ability(
            ToolGroup.Money,
            "Money and trackers",
            "Running totals for spending, budgets, calories or kilometres, summed exactly.",
            listOf(
                "I spent 12 euros on lunch",
                "How much is left this week?",
                "Undo that last entry",
                "Where did my money go this month?"
            )
        ),
        Ability(
            ToolGroup.Tasks,
            "Tasks, reminders and lists",
            "Reminders with real alarms, moved or cancelled by saying so, and lists for the shop or the trip.",
            listOf(
                "Remind me to call mum tomorrow at six",
                "Move that reminder to Friday",
                "What's still open?",
                "Add oat milk to the shopping list"
            )
        ),
        Ability(
            ToolGroup.Thinking,
            "Exact answers",
            "Sums, units and dates worked out by the phone rather than guessed by a model.",
            listOf(
                "What's 17.5% of 249?",
                "How many days until Christmas?",
                "5 miles in kilometres",
                "How does my day look?"
            )
        ),
        Ability(
            ToolGroup.Web,
            "The web",
            "Live search, reading a page, and today's exchange rates.",
            listOf(
                "What's in the news today?",
                "What's 50 dollars in euros?",
                "Tell me about the Brandenburg Gate"
            ),
            AbilitySwitch.Web
        ),
        Ability(
            ToolGroup.Weather,
            "Weather",
            "Real forecasts for where you are or anywhere you name.",
            listOf("Do I need a coat today?", "Weather in Lisbon this weekend"),
            AbilitySwitch.Weather
        ),
        Ability(
            ToolGroup.Places,
            "Places and routes",
            "Finds what is nearby, pins it on the map, draws the way there, and reminds " +
                "you of things when you arrive or leave.",
            listOf(
                "I'm hungry, what's around here?",
                "How do I get to the second one?",
                "I parked here",
                "Take me home",
                "Remind me to buy milk when I get home",
                "Send Anna my location"
            ),
            AbilitySwitch.Maps
        ),
        Ability(
            ToolGroup.Calendar,
            "Calendar",
            "Reads your day and fills in new appointments for you to save.",
            listOf("What have I got on today?", "Lunch with Sam on Friday at one"),
            AbilitySwitch.Calendar
        ),
        Ability(
            ToolGroup.People,
            "Contacts",
            "Looks people up so a name is enough to reach them.",
            listOf("What's Anna's number?"),
            AbilitySwitch.Contacts
        ),
        Ability(
            ToolGroup.Phone,
            "The phone",
            "Alarms, timers, the torch, the ringer, apps and settings pages.",
            listOf(
                "Wake me at seven",
                "Ten minutes for the pasta",
                "Turn on the torch",
                "How much battery have I got?"
            ),
            AbilitySwitch.Phone
        ),
        Ability(
            ToolGroup.Messages,
            "Messages and calls",
            "Texts, WhatsApp, replies and calls — finished for you, not left as drafts.",
            listOf(
                "Text Anna that I'm running late",
                "What came in?",
                "Call Ralf"
            ),
            AbilitySwitch.Phone
        ),
        Ability(
            ToolGroup.Media,
            "Music",
            "Drives whichever music app is already playing.",
            listOf("Play Sonne by Rammstein", "Skip this song", "Volume to 40 percent")
        ),
        Ability(
            ToolGroup.Vision,
            "Camera and photos",
            "Takes a picture and looks at it: reads signs and menus, logs a receipt, " +
                "copies an event off a poster, tells you what something is. With screen " +
                "reading on, it can read and summarise whatever app you are looking at.",
            listOf(
                "What am I looking at?",
                "Scan this receipt and log it",
                "Translate this sign",
                "Summarise what's on my screen"
            )
        ),
        Ability(
            ToolGroup.Automation,
            "Routines",
            "Several things under one name, run when you say it, nudged at a set time, or " +
                "run quietly by themselves with the answer sent as a notification.",
            listOf(
                "Make a morning routine: my day, the weather, then play music at 7",
                "Every weekday at 7:30, tell me if I need an umbrella",
                "Run my morning routine",
                "What routines do I have?"
            )
        ),
        Ability(
            ToolGroup.News,
            "News",
            "Today's headlines, or the latest on anything, in your language.",
            listOf("What's in the news?", "Latest news about SpaceX"),
            AbilitySwitch.Web
        ),
        Ability(
            ToolGroup.Language,
            "Languages",
            "Translates between languages, interprets a whole conversation out loud, and " +
                "looks words up in the dictionary.",
            listOf(
                "How do you say 'where is the station' in Spanish?",
                "Be my interpreter for Italian",
                "What does 'serendipity' mean?"
            ),
            AbilitySwitch.Web
        ),
        Ability(
            ToolGroup.Create,
            "Pictures",
            "Draws whatever you describe, straight into the conversation. Free, no key.",
            listOf("Draw a fox in a space suit", "Make me a wallpaper of a neon city at night"),
            AbilitySwitch.Web
        ),
        Ability(
            ToolGroup.Markets,
            "Markets",
            "Live share and crypto prices with today's move.",
            listOf("How's Apple stock doing?", "Bitcoin price"),
            AbilitySwitch.Web
        ),
        Ability(
            ToolGroup.Knowledge,
            "Knowledge",
            "Public holidays, recipes, football scores, TV shows and books.",
            listOf(
                "When is the next public holiday?",
                "Give me a recipe for lasagne",
                "How did Bayern play?",
                "When is the next episode of Severance?"
            ),
            AbilitySwitch.Web
        ),
        Ability(
            ToolGroup.Fun,
            "Fun",
            "Jokes, odd facts, quotes, coin flips and dice — really random, not a model's favourite number.",
            listOf("Tell me a joke", "Flip a coin", "Roll two dice", "Give me a quote")
        ),
        Ability(
            ToolGroup.Home,
            "Smart home",
            "Lights, plugs, heating, blinds, locks and scenes through your own Home Assistant — " +
                "free, local, no cloud. Set it up in Settings -> Powers.",
            listOf(
                "Turn off all the lights",
                "Set the living room to 21 degrees",
                "Is the front door locked?",
                "Dim the bedroom light to 30 percent"
            ),
            AbilitySwitch.Home
        ),
        Ability(
            ToolGroup.Screen,
            "The screen",
            "Puts the right thing in front of you when an answer is better seen.",
            listOf("Show my tasks", "Show me the map")
        )
    )

    /**
     * What to offer on an empty screen: the user's own quick commands when they
     * have written some, otherwise one sentence from each family that is on.
     */
    fun starters(settings: Settings, limit: Int = 4): List<Pair<ToolGroup, String>> {
        val own = settings.quickCommands.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (own.isNotEmpty()) {
            return own.take(8).map { phrase ->
                val group = ToolRouter.groupsFor(phrase, emptyList())
                    .firstOrNull { it != ToolGroup.Memory && it != ToolGroup.Thinking && it != ToolGroup.Screen && it != ToolGroup.Web }
                    ?: ToolGroup.Thinking
                group to phrase
            }
        }
        return suggested(settings, limit)
    }

    private fun suggested(settings: Settings, limit: Int): List<Pair<ToolGroup, String>> =
        ALL.filter { it.isOn(settings) && it.group != ToolGroup.Screen }
            .map { it.group to it.examples.first() }
            .let { firsts ->
                // The day and the money lead, because they are what a first
                // question most often turns out to be about.
                val preferred = listOf(
                    ToolGroup.Thinking, ToolGroup.Vision, ToolGroup.Weather,
                    ToolGroup.Money, ToolGroup.Phone
                )
                firsts.sortedBy { (group, _) ->
                    preferred.indexOf(group).let { if (it < 0) preferred.size else it }
                }
            }
            .take(limit)
}
