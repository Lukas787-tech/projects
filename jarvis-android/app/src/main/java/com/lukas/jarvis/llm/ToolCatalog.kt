package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.moment.CardKind
import com.lukas.jarvis.moment.FollowUp
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
 * How much a tool may do before it has to ask.
 *
 * The rule the whole app follows: Mochi may read freely, may change your own
 * things with an undo, and must ask before anything that leaves the phone or
 * cannot be taken back.
 */
enum class Risk {
    /** Only looks: the weather, a search, a memory, where you are. */
    Read,

    /** Changes your own things on the phone, and can be put back. */
    Local,

    /** Reaches someone or something outside the phone: a text, a call, a share. */
    Outward,

    /** Hard to take back: deleting, changing a booking, pressing the phone's own buttons. */
    Sensitive;

    /** True when the tool must wait for the person's yes before it runs. */
    val asksFirst: Boolean get() = this == Outward || this == Sensitive
}

/**
 * What is known about one tool beyond its schema.
 *
 * [doing] is the line shown while it runs, [chip] the word left under the reply
 * afterwards. [readOnly] changes how the agent runs it: a tool that only reads
 * can run beside others in the same round and can be answered from this turn's
 * own cache when a small model asks for it twice. [risk] decides whether it
 * may run at all before the person says yes. [view] is the card its result is
 * shown on, and [next] the follow-ups that card offers.
 */
data class ToolInfo(
    val name: String,
    val group: ToolGroup,
    val doing: String,
    val chip: String,
    val readOnly: Boolean = false,
    val risk: Risk = if (readOnly) Risk.Read else Risk.Local,
    val view: CardKind = CardKind.Answer,
    val next: List<FollowUp> = emptyList()
)

/**
 * Every tool, described once.
 *
 * One table feeds the agent loop, the confirmation gate, the live trail, the
 * canvas's cards and the Skills screen, so a tool added in one place cannot be
 * half-known in the others.
 */
object ToolCatalog {

    private fun f(label: String, say: String) = FollowUp(label, say)

    val ALL: List<ToolInfo> = listOf(
        // memory
        ToolInfo("remember", ToolGroup.Memory, "saving that", "Saved", view = CardKind.Memory,
            next = listOf(f("What do you know about me?", "What do you remember about me?"))),
        ToolInfo("recall", ToolGroup.Memory, "checking memory", "Memory", readOnly = true, view = CardKind.Memory,
            next = listOf(f("Remember something new", "Remember that "))),
        ToolInfo("update_memory", ToolGroup.Memory, "correcting a memory", "Memory", view = CardKind.Memory,
            next = listOf(f("What else do you know?", "What do you remember about me?"))),
        ToolInfo("forget", ToolGroup.Memory, "forgetting that", "Forgot", risk = Risk.Sensitive, view = CardKind.Memory,
            next = listOf(f("What do you still know?", "What do you remember about me?"))),
        ToolInfo("recall_conversation", ToolGroup.Memory, "looking back", "Past talks", readOnly = true,
            next = listOf(f("Tell me more", "Tell me more about that"))),

        // money and anything else that is counted
        ToolInfo("log_entry", ToolGroup.Money, "logging it", "Logged", view = CardKind.Entry,
            next = listOf(f("What's left?", "How much is left on {tracker}?"), f("Where did it go?", "Where did my money go this month?"))),
        ToolInfo("tracker_status", ToolGroup.Money, "checking the numbers", "Balances", readOnly = true, view = CardKind.Chart,
            next = listOf(f("Where did it go?", "Where did my money go this month?"))),
        ToolInfo("configure_tracker", ToolGroup.Money, "setting that up", "Tracker", view = CardKind.Entry,
            next = listOf(f("How's it looking?", "How are my trackers looking?"))),
        ToolInfo("list_entries", ToolGroup.Money, "reading the entries", "Entries", readOnly = true, view = CardKind.Chart,
            next = listOf(f("Where did it go?", "Where did my money go this month?"))),
        ToolInfo("spending_report", ToolGroup.Money, "adding it up", "Report", readOnly = true, view = CardKind.Chart,
            next = listOf(f("Set a budget", "Help me set a monthly budget"))),
        ToolInfo("delete_entry", ToolGroup.Money, "taking that back", "Undone", risk = Risk.Sensitive, view = CardKind.Entry,
            next = listOf(f("What's left now?", "How much is left?"))),

        // tasks
        ToolInfo("add_task", ToolGroup.Tasks, "adding a reminder", "Reminder", view = CardKind.Task,
            next = listOf(f("Move it to tomorrow", "Move {title} to tomorrow at the same time"), f("What's still open?", "What's still open?"))),
        ToolInfo("countdown", ToolGroup.Tasks, "counting the days", "Countdown", view = CardKind.Task,
            next = listOf(f("All my countdowns", "What are my countdowns?"))),
        ToolInfo("list", ToolGroup.Tasks, "the list", "List", view = CardKind.List,
            next = listOf(f("Remind me at the shop", "Remind me about the {list} list when I get to the supermarket"), f("Read it out", "What's on my {list} list?"), f("All my lists", "What lists do I have?"))),
        ToolInfo("list_tasks", ToolGroup.Tasks, "checking tasks", "Tasks", readOnly = true, view = CardKind.Task,
            next = listOf(f("Plan my day", "Plan my day"))),
        ToolInfo("complete_task", ToolGroup.Tasks, "ticking it off", "Done", view = CardKind.Task,
            next = listOf(f("What's next?", "What's still open?"))),
        ToolInfo("update_task", ToolGroup.Tasks, "moving the task", "Moved", view = CardKind.Task,
            next = listOf(f("What's still open?", "What's still open?"))),
        ToolInfo("delete_task", ToolGroup.Tasks, "removing the task", "Removed", risk = Risk.Sensitive, view = CardKind.Task,
            next = listOf(f("What's still open?", "What's still open?"))),

        // exact answers
        ToolInfo("now", ToolGroup.Thinking, "checking the time", "Clock", readOnly = true, view = CardKind.Exact,
            next = listOf(f("How does my day look?", "How does my day look?"))),
        ToolInfo("calculate", ToolGroup.Thinking, "working it out", "Maths", readOnly = true, view = CardKind.Exact,
            next = listOf(f("Split it", "Split that between "))),
        ToolInfo("convert_units", ToolGroup.Thinking, "converting", "Units", readOnly = true, view = CardKind.Exact,
            next = listOf(f("The other way round", "Convert that the other way round"))),
        ToolInfo("date_calc", ToolGroup.Thinking, "counting the days", "Dates", readOnly = true, view = CardKind.Exact,
            next = listOf(f("Make it a countdown", "Count down the days to that"))),
        ToolInfo("briefing", ToolGroup.Thinking, "gathering your day", "Briefing", readOnly = true, view = CardKind.Brief,
            next = listOf(f("What's still open?", "What's still open?"), f("Plan my day", "Plan my day"))),

        // the internet
        ToolInfo("web_search", ToolGroup.Web, "searching the web", "Web", readOnly = true, view = CardKind.Web,
            next = listOf(f("Tell me more", "Tell me more about that"), f("Remember this", "Remember the main point of that"))),
        ToolInfo("open_url", ToolGroup.Web, "reading a page", "Page", readOnly = true, view = CardKind.Web,
            next = listOf(f("Shorter, please", "Sum that page up in three lines"))),
        ToolInfo("convert_currency", ToolGroup.Web, "checking the rate", "Currency", readOnly = true, view = CardKind.Exact,
            next = listOf(f("Log it as spent", "Log that as spent"))),
        ToolInfo("wikipedia", ToolGroup.Web, "reading Wikipedia", "Wikipedia", readOnly = true, view = CardKind.Web,
            next = listOf(f("Show it on the globe", "Show {topic} on the map"), f("Tell me more", "Tell me more about that"))),
        ToolInfo("weather", ToolGroup.Weather, "checking the sky", "Weather", readOnly = true, view = CardKind.Weather,
            next = listOf(f("And tomorrow?", "What's the weather tomorrow?"), f("Remind me to take an umbrella", "Remind me to take an umbrella tomorrow morning"))),

        // the world, beyond a search box
        ToolInfo("news", ToolGroup.News, "reading the headlines", "News", readOnly = true, view = CardKind.Web,
            next = listOf(f("More on the first one", "Tell me more about the first headline"))),
        ToolInfo("translate", ToolGroup.Language, "translating", "Translate", readOnly = true, view = CardKind.Translation,
            next = listOf(f("Be my interpreter", "Be my interpreter for {language}"), f("How do I say it?", "How do I pronounce that?"))),
        ToolInfo("interpreter", ToolGroup.Language, "opening the interpreter", "Interpreter", view = CardKind.Translation,
            next = listOf(f("Stop interpreting", "Stop the interpreter"))),
        ToolInfo("define_word", ToolGroup.Language, "opening the dictionary", "Dictionary", readOnly = true,
            next = listOf(f("Use it in a sentence", "Use that word in a sentence"))),
        ToolInfo("market_price", ToolGroup.Markets, "checking the markets", "Markets", readOnly = true, view = CardKind.Chart,
            next = listOf(f("And last week?", "How did it do over the last week?"))),
        ToolInfo("holidays", ToolGroup.Knowledge, "checking the holidays", "Holidays", readOnly = true, view = CardKind.Calendar,
            next = listOf(f("Count down to the next one", "Count down to the next holiday"))),
        ToolInfo("recipe", ToolGroup.Knowledge, "finding a recipe", "Recipe", readOnly = true,
            next = listOf(f("Add it to my shopping list", "Add the ingredients for that to my shopping list"))),
        ToolInfo("sports", ToolGroup.Knowledge, "checking the scores", "Sports", readOnly = true,
            next = listOf(f("When do they play next?", "When do they play next?"))),
        ToolInfo("tv_show", ToolGroup.Knowledge, "looking up the show", "TV", readOnly = true,
            next = listOf(f("Remind me when it's on", "Remind me when the next episode airs"))),
        ToolInfo("book", ToolGroup.Knowledge, "looking up the book", "Books", readOnly = true,
            next = listOf(f("Remember to read it", "Remember I want to read that book"))),
        ToolInfo("fun", ToolGroup.Fun, "finding something good", "Fun", readOnly = true,
            next = listOf(f("Another one", "Another one, please"))),
        ToolInfo("random", ToolGroup.Fun, "rolling the dice", "Random", view = CardKind.Exact,
            next = listOf(f("Again", "Do that again"))),
        ToolInfo("generate_image", ToolGroup.Create, "drawing", "Picture", view = CardKind.Picture,
            next = listOf(f("Another take", "Draw that again, a little different"))),

        // the house
        ToolInfo("home_status", ToolGroup.Home, "checking the house", "Home", readOnly = true, view = CardKind.Home,
            next = listOf(f("Turn everything off", "Turn off all the lights"))),
        ToolInfo("home_control", ToolGroup.Home, "working the house", "Home", view = CardKind.Home,
            next = listOf(f("What's on at home?", "What's on at home?"))),

        // places; these draw on the map, so none of them counts as a pure read
        ToolInfo("find_places", ToolGroup.Places, "looking around you", "Places", risk = Risk.Read, view = CardKind.Places,
            next = listOf(f("Route there", "How do I get to {place}?"), f("Save this place", "Save {place} as a place"), f("Something else nearby", "What else is around here?"))),
        ToolInfo("route_to", ToolGroup.Places, "finding the way", "Route", risk = Risk.Read, view = CardKind.Route,
            next = listOf(f("Start directions", "Start navigation to {place}"), f("Remind me to leave", "Remind me when it's time to leave for {place}"), f("Walking or by bike?", "How long would it take by bike?"))),
        ToolInfo("start_navigation", ToolGroup.Places, "opening directions", "Navigation", view = CardKind.Route,
            next = listOf(f("Share my arrival time", "How long until I get there?"))),
        ToolInfo("where_am_i", ToolGroup.Places, "checking where you are", "Location", risk = Risk.Read, view = CardKind.Map,
            next = listOf(f("Save this spot", "Save this place as a spot I parked"), f("What's around?", "What's around here?"))),
        ToolInfo("save_place", ToolGroup.Places, "saving the spot", "Saved place", view = CardKind.Place,
            next = listOf(f("Take me there later", "How do I get to {place}?"), f("My places", "What are my saved places?"))),
        ToolInfo("saved_places", ToolGroup.Places, "checking your places", "Places", readOnly = true, view = CardKind.Map,
            next = listOf(f("Take me home", "Take me home"))),
        ToolInfo("rename_place", ToolGroup.Places, "renaming the place", "Places", view = CardKind.Place,
            next = listOf(f("My places", "What are my saved places?"))),
        ToolInfo("show_on_map", ToolGroup.Places, "finding it on the globe", "Map", readOnly = true, view = CardKind.Globe,
            next = listOf(f("Route there", "How do I get to {place}?"), f("What's it like there?", "What's the weather there?"))),
        ToolInfo("forget_place", ToolGroup.Places, "forgetting the place", "Place", risk = Risk.Sensitive, view = CardKind.Place,
            next = listOf(f("My places", "What are my saved places?"))),
        ToolInfo("place_reminder", ToolGroup.Places, "setting a place reminder", "Place reminder", view = CardKind.Task,
            next = listOf(f("All my place reminders", "What place reminders do I have?"))),
        ToolInfo("share_location", ToolGroup.Places, "sharing where you are", "Location", risk = Risk.Outward, view = CardKind.Message,
            next = listOf(f("Where am I?", "Where am I?"))),

        // calendar and people
        ToolInfo("calendar", ToolGroup.Calendar, "reading your calendar", "Calendar", readOnly = true, view = CardKind.Calendar,
            next = listOf(f("Plan around it", "Plan my day around that"))),
        ToolInfo("change_calendar_event", ToolGroup.Calendar, "changing the appointment", "Calendar", risk = Risk.Sensitive, view = CardKind.Event,
            next = listOf(f("What's on today?", "What's on my calendar today?"))),
        ToolInfo("add_calendar_event", ToolGroup.Calendar, "filling in the event", "Event", view = CardKind.Event,
            next = listOf(f("Remind me to leave", "Remind me when it's time to leave for {title}"), f("What else is on?", "What's on my calendar this week?"))),
        ToolInfo("find_contact", ToolGroup.People, "looking them up", "Contacts", readOnly = true, view = CardKind.Contact,
            next = listOf(f("Text them", "Text {who}"), f("Call them", "Call {who}"), f("Their birthday?", "When is their birthday?"))),

        // the phone itself
        ToolInfo("set_alarm", ToolGroup.Phone, "setting the alarm", "Alarm", view = CardKind.Timer,
            next = listOf(f("Show my alarms", "Show my alarms"))),
        ToolInfo("set_timer", ToolGroup.Phone, "starting the timer", "Timer", view = CardKind.Timer,
            next = listOf(f("How long is left?", "How long is left on the timer?"))),
        ToolInfo("focus_session", ToolGroup.Phone, "starting a focus session", "Focus", view = CardKind.Timer,
            next = listOf(f("How long is left?", "How long is left on the focus session?"))),
        ToolInfo("profile", ToolGroup.Phone, "switching the profile", "Profile", view = CardKind.Settings,
            next = listOf(f("Save this look", "Save this as a profile"))),
        ToolInfo("change_setting", ToolGroup.Phone, "changing the setting", "Settings", view = CardKind.Settings,
            next = listOf(f("Change something else", "What else can I change?"))),
        ToolInfo("change_voice", ToolGroup.Phone, "finding the voice", "Voice", view = CardKind.Settings,
            next = listOf(f("Say something", "Say hello in your new voice"))),
        ToolInfo("timers", ToolGroup.Phone, "checking the timers", "Timers", view = CardKind.Timer,
            next = listOf(f("Add five minutes", "Give the timer five more minutes"))),
        ToolInfo("stopwatch", ToolGroup.Phone, "the stopwatch", "Stopwatch", view = CardKind.Stopwatch,
            next = listOf(f("Stop it", "Stop the stopwatch"))),
        ToolInfo("show_alarms", ToolGroup.Phone, "opening your alarms", "Alarms", view = CardKind.Timer,
            next = listOf(f("Set another", "Set an alarm for "))),
        ToolInfo("device_status", ToolGroup.Phone, "checking the phone", "Phone", readOnly = true, view = CardKind.Device,
            next = listOf(f("Save battery", "Turn the brightness down"))),
        ToolInfo("torch", ToolGroup.Phone, "the torch", "Torch", view = CardKind.Device,
            next = listOf(f("Turn it off", "Turn off the torch"))),
        ToolInfo("ringer", ToolGroup.Phone, "the ringer", "Ringer", view = CardKind.Device,
            next = listOf(f("Quiet for an hour", "Do not disturb for an hour"))),
        ToolInfo("volume", ToolGroup.Phone, "the volume", "Volume", view = CardKind.Device,
            next = listOf(f("A bit louder", "Volume up a little"))),
        ToolInfo("brightness", ToolGroup.Phone, "the brightness", "Brightness", view = CardKind.Device,
            next = listOf(f("Auto brightness", "Turn on auto brightness"))),
        ToolInfo("do_not_disturb", ToolGroup.Phone, "do not disturb", "Quiet", view = CardKind.Device,
            next = listOf(f("Until when?", "When does do not disturb end?"))),
        ToolInfo("clipboard", ToolGroup.Phone, "the clipboard", "Clipboard",
            next = listOf(f("Read it to me", "What's on my clipboard?"))),
        ToolInfo("open_app", ToolGroup.Phone, "opening the app", "App",
            next = listOf(f("Open another", "Open "))),
        ToolInfo("open_settings_page", ToolGroup.Phone, "opening settings", "Settings",
            next = listOf(f("Change it for me", "Can you change that setting for me?"))),
        ToolInfo("open_link", ToolGroup.Phone, "opening the page", "Browser", view = CardKind.Web,
            next = listOf(f("Sum it up", "Sum up that page"))),
        ToolInfo("system_action", ToolGroup.Phone, "pressing the button", "Phone", risk = Risk.Sensitive, view = CardKind.Device,
            next = listOf(f("Go home", "Go home"))),

        // reaching people
        ToolInfo("call", ToolGroup.Messages, "readying the call", "Call", risk = Risk.Outward, view = CardKind.Call,
            next = listOf(f("Text instead", "Text them instead"))),
        ToolInfo("place_call", ToolGroup.Messages, "ringing", "Call", risk = Risk.Outward, view = CardKind.Call,
            next = listOf(f("Text instead", "Text them instead"))),
        ToolInfo("cancel_call", ToolGroup.Messages, "dropping the call", "Call", view = CardKind.Call,
            next = listOf(f("Text instead", "Text them instead"))),
        ToolInfo("dial", ToolGroup.Messages, "dialling", "Dialler", view = CardKind.Call,
            next = listOf(f("Ring it for me", "Call that number"))),
        ToolInfo("send_message", ToolGroup.Messages, "sending the text", "Text", risk = Risk.Outward, view = CardKind.Message,
            next = listOf(f("Anything unread?", "Do I have any unread messages?"))),
        ToolInfo("send_chat_message", ToolGroup.Messages, "writing the message", "Chat", risk = Risk.Outward, view = CardKind.Message,
            next = listOf(f("Anything unread?", "Do I have any unread messages?"))),
        ToolInfo("reply_to_message", ToolGroup.Messages, "replying", "Reply", risk = Risk.Outward, view = CardKind.Message,
            next = listOf(f("Anything else unread?", "Do I have any other unread messages?"))),
        ToolInfo("unread_messages", ToolGroup.Messages, "checking messages", "Inbox", readOnly = true, view = CardKind.Inbox,
            next = listOf(f("Reply to the newest", "Help me reply to the newest message"))),
        ToolInfo("send_email", ToolGroup.Messages, "drafting the email", "Email", risk = Risk.Outward, view = CardKind.Message,
            next = listOf(f("Remind me to follow up", "Remind me to follow up on that email in two days"))),
        ToolInfo("share", ToolGroup.Messages, "sharing", "Share", risk = Risk.Outward, view = CardKind.Message,
            next = listOf(f("Save it too", "Remember that"))),

        // media and the screen
        ToolInfo("play_music", ToolGroup.Media, "starting the music", "Music", view = CardKind.Media,
            next = listOf(f("Skip", "Next song"))),
        ToolInfo("control_playback", ToolGroup.Media, "the music", "Playback", view = CardKind.Media,
            next = listOf(f("What's playing?", "What's playing?"))),
        ToolInfo("now_playing", ToolGroup.Media, "checking what's playing", "Playing", readOnly = true, view = CardKind.Media,
            next = listOf(f("Skip", "Next song"))),
        ToolInfo("bluetooth", ToolGroup.Media, "checking Bluetooth", "Bluetooth", view = CardKind.Device,
            next = listOf(f("Open Bluetooth", "Open Bluetooth settings"))),
        ToolInfo("show", ToolGroup.Screen, "putting it on screen", "Screen",
            next = listOf(f("Back to talking", "Show the globe"))),

        // eyes
        ToolInfo("take_photo", ToolGroup.Vision, "opening the camera", "Camera", view = CardKind.Camera,
            next = listOf(f("Remember this", "Remember what that picture showed"))),
        ToolInfo("read_screen", ToolGroup.Vision, "reading your screen", "Screen", readOnly = true, view = CardKind.Screen,
            next = listOf(f("Reply to this", "Help me reply to this"), f("Sum it up", "Sum up what's on my screen"))),
        ToolInfo("open_camera", ToolGroup.Vision, "opening the camera app", "Camera", view = CardKind.Camera,
            next = listOf(f("What is this?", "What is this?"))),

        // doing several things at once
        ToolInfo("create_routine", ToolGroup.Automation, "setting up the routine", "Routine", view = CardKind.Routine,
            next = listOf(f("Try it now", "Run my {routine} routine"), f("My routines", "What routines do I have?"))),
        ToolInfo("run_routine", ToolGroup.Automation, "running the routine", "Routine", view = CardKind.Routine,
            next = listOf(f("My routines", "What routines do I have?"))),
        ToolInfo("list_routines", ToolGroup.Automation, "checking routines", "Routines", readOnly = true, view = CardKind.Routine,
            next = listOf(f("Make a new one", "Help me make a new routine"))),
        ToolInfo("delete_routine", ToolGroup.Automation, "removing the routine", "Routine", risk = Risk.Sensitive, view = CardKind.Routine,
            next = listOf(f("My routines", "What routines do I have?")))
    )

    /**
     * The risk of one particular call, which can be higher than the tool's own:
     * switching a light is a home control like any other, unlocking the front
     * door is not.
     */
    fun riskFor(name: String, argumentsJson: String = "{}"): Risk {
        val base = info(name)?.risk ?: Risk.Local
        if (name == "home_control") {
            val args = argumentsJson.lowercase(Locale.ROOT)
            if (UNLOCKING.containsMatchIn(args)) return Risk.Sensitive
        }
        return base
    }

    private val UNLOCKING = Regex("\"action\"\\s*:\\s*\"(unlock|open)\"|garage|front door|alarm_disarm|disarm")

    fun risk(name: String): Risk = info(name)?.risk ?: Risk.Local

    fun view(name: String): CardKind = info(name)?.view ?: CardKind.Answer

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
        "add_countdown" to "countdown", "birthday" to "countdown", "add_birthday" to "countdown",
        "countdowns" to "countdown", "upcoming" to "countdown",
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
        "start_stopwatch" to "stopwatch", "lap" to "stopwatch", "stop_watch" to "stopwatch",
        "set_setting" to "change_setting", "update_setting" to "change_setting",
        "set_voice" to "change_voice", "switch_voice" to "change_voice", "find_voice" to "change_voice",
        "voice" to "change_voice", "use_voice" to "change_voice", "search_voice" to "change_voice",
        "customize" to "change_setting", "set_color" to "change_setting", "set_accent" to "change_setting",
        "rename_saved_place" to "rename_place", "show_place" to "show_on_map", "show_map" to "show_on_map",
        "map_show" to "show_on_map", "set_home" to "save_place", "mark_place" to "save_place",
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
        "switch_profile" to "profile", "save_profile" to "profile", "apply_profile" to "profile",
        "focus" to "focus_session", "pomodoro" to "focus_session", "focus_mode" to "focus_session",
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
            "Reminders with real alarms, moved or cancelled by saying so, lists for the shop or the trip, " +
                "and countdowns to holidays and birthdays.",
            listOf(
                "Remind me to call mum tomorrow at six",
                "Move that reminder to Friday",
                "What's still open?",
                "Add oat milk to the shopping list",
                "My holiday starts on 12 October",
                "Mum's birthday is 3 March 1966"
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
                "My house is Hauptstraße 5, Berlin",
                "Show me my house",
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
            "Alarms, timers, a stopwatch, profiles, the torch, the ringer, apps and settings pages.",
            listOf(
                "Wake me at seven",
                "Ten minutes for the pasta",
                "Start the stopwatch",
                "Switch to night mode",
                "Make it red and speak a bit slower",
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
