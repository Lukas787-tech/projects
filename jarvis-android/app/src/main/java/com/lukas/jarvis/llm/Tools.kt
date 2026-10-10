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

/**
 * The bridge between the model and the phone. Every tool returns plain text
 * rather than JSON: small free-tier models read prose far more reliably than
 * they read nested objects.
 *
 * The schema list is assembled in groups rather than as one long literal, and
 * each group is gated on the setting that owns it. That is not only tidier to
 * read — a free-tier model handed forty tool definitions starts picking the
 * wrong one, so the surface a given user sees is the one they have switched on.
 *
 * Each group's schemas and handlers live in a file of their own, ToolsMoney.kt,
 * ToolsPlaces.kt and so on, written as extensions of this class; what stays
 * here is what they share: what every tool can reach, the hooks the container
 * sets, the list of schemas and the one dispatcher.
 */
class Tools(
    internal val brain: Brain,
    internal val web: WebTools,
    internal val weather: Weather,
    internal val reminders: Reminders,
    internal val navigator: Navigator,
    internal val locator: Locator,
    internal val places: PlacesClient,
    internal val stage: StageStore,
    internal val phone: Phone,
    internal val device: Device,
    internal val launcher: Launcher,
    internal val people: People,
    internal val agenda: Agenda,
    internal val briefer: Briefer,
    internal val messenger: Messenger,
    internal val caller: Caller,
    internal val chats: Chats,
    internal val currency: Currency,
    internal val camera: CameraBus,
    internal val routines: Routines,
    internal val knowledge: Knowledge,
    internal val imagine: Imagine,
    internal val home: Home,
    internal val lists: com.lukas.jarvis.data.Lists,
    internal val timers: com.lukas.jarvis.notify.Timers,
    internal val stopwatch: com.lukas.jarvis.notify.Stopwatch,
    internal val placeReminders: com.lukas.jarvis.notify.PlaceReminders,
    internal val profiles: com.lukas.jarvis.core.ProfileStore,
    internal val countdowns: com.lukas.jarvis.core.CountdownStore,
    internal val settingsStore: com.lukas.jarvis.core.SettingsStore,
    /** For searching Fish Audio's voice library; null where nothing speaks. */
    internal val speaker: com.lukas.jarvis.voice.Speaker? = null
) : ToolBox {

    /**
     * Runs a routine's steps as turns of their own. Set by the container once
     * the agent exists, since the agent is built from these tools; null while
     * a routine is already running, so a routine cannot start itself.
     */
    @Volatile
    var routineRunner: (suspend (Routine, Settings) -> String)? = null

    /**
     * How many routines are running now. A routine may not start another
     * from inside itself; counting, rather than clearing [routineRunner]
     * while one runs, keeps two routines at once (the app and a quiet one in
     * the background) from leaving the runner switched off for good.
     */
    override val routinesRunning = java.util.concurrent.atomic.AtomicInteger(0)

    /** Told when a repeating entry starts or stops, so the daily check is kept or let go. */
    @Volatile
    var onRepeatsChanged: () -> Unit = {}

    override fun schemas(settings: Settings): List<JSONObject> = buildList {
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


    override suspend fun execute(call: ToolCall, settings: Settings, effects: ToolEffects): String {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrDefault(JSONObject())
        // Whatever fell due since the last look is counted before anything is read.
        if (call.name in MONEY_TOOLS) runCatching { brain.catchUpRecurring() }
        return try {
            when (call.name) {
                // memory
                "remember" -> remember(args, effects)
                "recall" -> recall(args)
                "update_memory" -> updateMemory(args, effects)
                "forget" -> forget(args, effects)
                "recall_conversation" -> recallConversation(args)

                // trackers
                "log_entry" -> logEntry(args, settings, effects, call.id)
                "tracker_status" -> trackerStatus(args, effects, call.id)
                "configure_tracker" -> configureTracker(args, settings, effects)
                "list_entries" -> listEntries(args)
                "spending_report" -> spendingReport(args, effects, call.id)
                "repeat_entry" -> repeatEntry(args, settings, effects)
                "stop_repeat" -> stopRepeat(args, effects)
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
                "countdown" -> countdown(args)
                "plan_trip" -> planTrip(args, effects)
                "make_plan" -> makePlan(args)
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
                    useSelectedPin = args.optBoolean("use_selected_pin", false),
                    address = args.optString("address").trim().takeIf { it.isNotBlank() }
                )
                "rename_place" -> navigator.renamePlace(args.optString("name").trim(), args.optString("new_name").trim())
                "show_on_map" -> navigator.showOnMap(args.optString("place").trim())
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
                "focus_session" -> focusSession(args)
                "profile" -> profile(args, settings)
                "timers" -> timerAction(args)
                "stopwatch" -> stopwatchAction(args)
                "change_setting" -> changeSetting(args, settings)
                "change_voice" -> changeVoice(args)
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

    /** The last voice search's results, best first, for "another one". */
    @Volatile
    internal var voiceChoices: List<com.lukas.jarvis.voice.FishVoiceOption> = emptyList()

    @Volatile
    internal var voiceChoice = 0

}
