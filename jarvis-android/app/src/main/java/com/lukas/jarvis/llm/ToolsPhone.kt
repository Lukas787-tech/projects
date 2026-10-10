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

// The phone itself: switches, sounds, apps, timers, media, voices and the house.

internal fun Tools.homeTools(): List<JSONObject> = listOf(
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

internal fun Tools.deviceTools(): List<JSONObject> = listOf(
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
        "profile",
        "Saved setups of Mochi's look, character and voice. 'Save this as night mode', " +
            "'switch to work mode', 'Mark III profile', 'what profiles do I have', " +
            "'delete the party profile', 'switch to night mode every day at 22:00' (schedule). " +
            "Switching changes colour, backdrop, core, " +
            "personality, reply length, wit, voice and speech; nothing else.",
        props(
            "action" to str("What to do.", listOf("apply", "save", "list", "delete", "schedule")),
            "name" to str("The profile's name, e.g. 'night', 'work'."),
            "time" to str("For schedule: 'HH:MM' it switches on by itself, or 'off' to stop that."),
            "days" to str("For schedule: 'weekdays', 'weekends', 'mon,wed,fri', 'every day except sunday'. Omit for every day.")
        ),
        listOf("action")
    ),
    tool(
        "focus_session",
        "A focus block: Do Not Disturb for the length given, with a Focus timer on screen " +
            "that rings at the end. 'Focus for 25 minutes', 'pomodoro', 'I need an hour to " +
            "concentrate'. Default 25 minutes.",
        props(
            "minutes" to num("How long. Default 25."),
            "action" to str("start (default) or stop, to end the session early.", listOf("start", "stop"))
        ),
        emptyList()
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
        "The timers Mochi is running: how long is left, cancel one or all, or add time. " +
            "'How long on the pasta', 'stop the timer', 'give it five more minutes'.",
        props(
            "action" to str("What to do.", listOf("list", "cancel", "add")),
            "label" to str("Which timer, by its name, or 'all'. Omit for the newest."),
            "minutes" to num("For 'add': minutes to add; negative takes time away.")
        ),
        listOf("action")
    ),
    tool(
        "change_setting",
        "Change how Mochi looks, sounds and behaves, as the user asks: 'make it red', 'speak slower', " +
            "'call me boss', 'your name is Friday', 'satellite map', 'bigger text', 'shorter answers', " +
            "'turn off the emoji', 'always answer in English', 'morning brief at 7'. One setting per call; " +
            "call it again for several. API keys cannot be changed this way.",
        props(
            "setting" to str("Which one.", com.lukas.jarvis.core.SettingChange.KEYS),
            "value" to str("The new value in plain words: 'red', 'slower', 'on', 'boss', '07:00', '120%'.")
        ),
        listOf("setting", "value")
    ),
    tool(
        "change_voice",
        "Change the voice Mochi speaks with, by describing it: 'speak like Morgan Freeman', 'a deep " +
            "male narrator', 'sprich mit einer ruhigen deutschen Frauenstimme', 'a British butler', 'my own " +
            "cloned voice'. Searches Fish Audio's voice library, switches to the best match at once and " +
            "names a few others. 'another one' tries the next match; 'your normal voice' goes back.",
        props(
            "description" to str("The voice as the user described it, in their words: a name, a character, " +
                "gender, age, accent, mood. 'another' for the next match, 'default' to go back."),
            "language" to str("Two-letter language the voice should speak, if they said one: de, en, fr…")
        ),
        listOf("description")
    ),
    tool(
        "stopwatch",
        "Mochi's stopwatch, counting up: 'start the stopwatch', 'lap', 'how long has it been " +
            "running', 'pause it', 'stop and reset'. It shows in the notification shade.",
        props(
            "action" to str(
                "start (or resume), pause, lap, status, reset (stop and clear).",
                listOf("start", "pause", "lap", "status", "reset")
            )
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
        "Ring someone. This does NOT ring yet: it puts the call on screen and it rings when " +
            "the user taps Call or says yes. Use find_contact first when given a name.",
        props(
            "number" to str("The phone number, digits and an optional leading +."),
            "who" to str("The person's name, if you have it, so it can be read back.")
        ),
        listOf("number")
    ),
    tool(
        "place_call",
        "Rings a call that is already waiting on screen. The user's own yes does this; you " +
            "should not need it.",
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
        "Send a text message. It goes up on screen and is sent when the user says yes or taps " +
            "Send. Use find_contact first when given a name.",
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
            "other app, straight from its notification, once the user says yes to the card. With " +
            "no name, the newest message is answered, which is usually the one meant.",
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

internal fun Tools.mediaTools(): List<JSONObject> = listOf(
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

internal fun Tools.setAlarm(args: JSONObject): String {
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

internal fun Tools.profile(args: JSONObject, settings: Settings): String {
    val name = com.lukas.jarvis.core.Profile.cleanName(args.optString("name"))
    val all = profiles.current
    return when (args.optString("action").trim().lowercase(Locale.ROOT)) {
        "save" -> {
            if (name.isBlank()) return "What should the profile be called?"
            profiles.save(com.lukas.jarvis.core.Profile.of(name, settings))
            "Saved the current look, character and voice as '$name'."
        }
        "list" -> if (all.isEmpty()) {
            "No profiles yet. Set Mochi up the way you like and say 'save this as night mode'."
        } else {
            "Profiles: " + all.joinToString { p ->
                p.name + p.scheduleLabel().takeIf { it.isNotBlank() }?.let { " (switches on $it)" }.orEmpty()
            } + "."
        }
        "schedule" -> {
            val target = profiles.find(name)
                ?: return if (all.isEmpty()) "There are no saved profiles yet." else
                    "No profile called '$name'. There is: ${all.joinToString { it.name }}."
            val raw = args.optString("time").trim()
            if (raw.lowercase(Locale.ROOT) in setOf("", "off", "none", "never", "no", "aus")) {
                profiles.schedule(target.name, "", emptySet())
                return "'${target.name}' no longer switches on by itself."
            }
            val at = com.lukas.jarvis.auto.Routines.normalizeTime(raw)
                ?: return "What time should '${target.name}' switch on? Say it as 'HH:MM'."
            val days = com.lukas.jarvis.auto.RoutineDays.parse(args.optString("days"))
            val saved = profiles.schedule(target.name, at, days) ?: return "No profile called '$name'."
            "'${saved.name}' will switch on ${saved.scheduleLabel()}, even with no signal."
        }
        "delete", "remove" -> if (profiles.remove(name)) "Deleted the '$name' profile." else "No profile called '$name'."
        else -> {
            val target = profiles.find(name)
                ?: return if (all.isEmpty()) {
                    "There are no saved profiles yet. Say 'save this as $name' once Mochi looks the way you want."
                } else {
                    "No profile called '$name'. There is: ${all.joinToString { it.name }}."
                }
            settingsStore.update { target.applyTo(it) }
            "Switched to '${target.name}'."
        }
    }
}

internal fun Tools.changeSetting(args: JSONObject, settings: Settings): String {
    val current = settingsStore.current
    return when (val result = com.lukas.jarvis.core.SettingChange.apply(current, args.optString("setting"), args.optString("value"))) {
        is com.lukas.jarvis.core.SettingChange.Result.Changed -> {
            settingsStore.update { result.settings }
            result.said
        }
        is com.lukas.jarvis.core.SettingChange.Result.Refused -> result.why
    }
}

internal suspend fun Tools.changeVoice(args: JSONObject): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val match = com.lukas.jarvis.voice.VoiceMatch
    val description = args.optString("description").trim()
    val current = settingsStore.current
    if (description.isNotBlank() && match.wantsDefault(description)) {
        settingsStore.update { it.copy(fishVoiceId = "", fishVoiceName = "", voiceName = "") }
        return@withContext "Back to the default voice."
    }
    if (current.fishKey.isBlank()) {
        return@withContext "For that I need Fish Audio's voice library: paste a Fish Audio key under " +
            "You → Voice (fish.audio has a free tier). Until then only the phone's own voices can be picked there."
    }
    val voices = speaker ?: return@withContext "No voice to change here."
    val choices = voiceChoices
    if (match.wantsAnother(description) && choices.size > 1) {
        voiceChoice = (voiceChoice + 1) % choices.size
        return@withContext useVoice(choices[voiceChoice], choices)
    }
    val mine = MY_VOICE.containsMatchIn(description.lowercase(Locale.ROOT))
    val plan = match.plan(if (mine) "" else description, args.optString("language").takeIf { it.isNotBlank() })
    val found = mutableListOf<com.lukas.jarvis.voice.FishVoiceOption>()
    var failure: Throwable? = null
    fun search(query: String, language: String) {
        runCatching { voices.cloudVoices(current.fishKey, query, language, mine) }
            .onSuccess { found += it }
            .onFailure { failure = it }
    }
    for (query in plan.queries) {
        search(query, plan.language.orEmpty())
        if (found.size >= 8 || failure != null) break
    }
    // A name the library only has in another language is still worth having.
    if (found.isEmpty() && failure == null && plan.language != null) search(plan.queries.first(), "")
    if (found.isEmpty()) {
        return@withContext failure?.let { "I couldn't search Fish Audio's voices: ${it.message}." }
            ?: if (mine) "There are no voices of your own on the Fish Audio account yet — they are made at fish.audio."
            else "Fish Audio's library has nothing that matches '$description'. Other words may find one, like 'deep male narrator'."
    }
    val ranked = match.rank(found, plan).take(6)
    voiceChoices = ranked
    voiceChoice = 0
    useVoice(ranked.first(), ranked)
}

internal fun Tools.useVoice(
    voice: com.lukas.jarvis.voice.FishVoiceOption,
    choices: List<com.lukas.jarvis.voice.FishVoiceOption>
): String {
    settingsStore.update { it.copy(voiceEngine = "fish", fishVoiceId = voice.id, fishVoiceName = voice.title) }
    val languages = voice.languages.takeIf { it.isNotEmpty() }?.joinToString("/", " (", ")").orEmpty()
    val others = choices.filter { it.id != voice.id }.take(3).joinToString { "'${it.title}'" }
    return "Now speaking as '${voice.title}'$languages from Fish Audio." +
        if (others.isNotEmpty()) " Also found: $others — say 'another voice' for the next." else ""
}

internal fun Tools.stopwatchAction(args: JSONObject): String {
    // Said, not shown: "1 minute 23 seconds" reads aloud better than "1:23.4".
    val clock = { ms: Long -> com.lukas.jarvis.notify.Timers.spoken((ms / 1000) * 1000) }
    val before = stopwatch.state.value
    val now = System.currentTimeMillis()
    return when (args.optString("action").trim().lowercase(Locale.ROOT)) {
        "start", "resume", "go" -> when {
            before.running -> "The stopwatch is already running: ${clock(before.elapsed(now))}."
            before.idle -> { stopwatch.start(now); "Stopwatch started." }
            else -> { stopwatch.start(now); "Carrying on from ${clock(before.elapsed(now))}." }
        }
        "pause", "stop", "halt" -> if (!before.running) {
            if (before.idle) "The stopwatch isn't running." else "It is already paused at ${clock(before.elapsed(now))}."
        } else {
            val after = stopwatch.pause(now)
            "Stopped at ${clock(after.elapsed(now))}."
        }
        "lap", "split" -> if (before.idle) "The stopwatch isn't running." else {
            val after = stopwatch.lap(now)
            "Lap ${after.laps.size}: ${clock(after.lapLengths().last())}, ${clock(after.elapsed(now))} in total."
        }
        "reset", "clear", "cancel" -> if (before.idle) "The stopwatch is already at zero." else {
            val total = clock(before.elapsed(now))
            stopwatch.reset()
            "Reset. It had reached $total."
        }
        else -> when {
            before.idle -> "The stopwatch isn't running."
            else -> {
                val laps = before.lapLengths()
                val lapText = if (laps.isEmpty()) "" else " Laps: " +
                    laps.mapIndexed { i, ms -> "${i + 1}) ${clock(ms)}" }.joinToString(", ") + "."
                (if (before.running) "Running: " else "Paused at ") + clock(before.elapsed(now)) + "." + lapText
            }
        }
    }
}

/** A plan is only laid out here; its steps are done when the user says so. */
internal fun Tools.makePlan(args: JSONObject): String {
    val title = args.optString("title").trim().ifBlank { "Plan" }
    val raw = args.opt("steps")
    val steps = when (raw) {
        is org.json.JSONArray -> (0 until raw.length()).map { raw.optString(it) }
        is String -> raw.split('\n', ';')
        else -> emptyList()
    }.map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() }.take(12)
    if (steps.isEmpty()) return "A plan needs its steps: give them as a list, one short line each."
    return "Plan: $title\n" + steps.joinToString("\n") { "- $it" } +
        "\nIn one short line, say what the plan is; the card shows the steps. Nothing is done until they say go."
}

internal fun Tools.countdown(args: JSONObject): String {
    val today = java.time.LocalDate.now()
    val name = args.optString("name").trim()
    return when (args.optString("action").trim().lowercase(Locale.ROOT)) {
        "add", "save", "set" -> {
            if (name.isBlank()) return "What should it be called?"
            val (date, knowsYear) = com.lukas.jarvis.core.Countdown.parseDay(args.optString("date"), today)
                ?: return "Which day is '$name'? Give it as YYYY-MM-DD."
            val birthday = args.optBoolean("birthday", false)
            val yearly = args.optBoolean("yearly", birthday)
            if (!yearly && date.isBefore(today)) return "${date} has already passed."
            val saved = countdowns.add(name, date, yearly, knowsYear && yearly, birthday)
            "Kept: ${saved.describe(today)}. It shows on Today."
        }
        "remove", "delete", "forget" -> countdowns.remove(name)?.let { "Forgot '${it.name}'." }
            ?: "Nothing called '$name' is being counted down."
        "check", "get", "when" -> countdowns.find(name)?.let { it.describe(today) + " (" + it.next(today) + ")." }
            ?: "Nothing called '$name' is being counted down."
        else -> {
            val coming = com.lukas.jarvis.core.Countdown.upcoming(countdowns.current, today)
            if (coming.isEmpty()) "Nothing is being counted down yet."
            else coming.take(10).joinToString("\n") { it.describe(today) + " (" + it.next(today) + ")" }
        }
    }
}

internal fun Tools.focusSession(args: JSONObject): String {
    if (args.optString("action").trim().lowercase(Locale.ROOT) in setOf("stop", "end", "off", "cancel")) {
        val gone = timers.cancel("Focus")
        val quiet = if (device.canSilence) device.doNotDisturb("off", null) else ""
        return if (gone.isEmpty()) "No focus session was running. $quiet".trim()
        else "Focus session ended. $quiet".trim()
    }
    // No timer without the silence: a session that does not quiet the
    // phone is only a countdown, and a second ask would make two.
    if (!device.canSilence) return device.doNotDisturb("priority", null)
    val minutes = (number(args, "minutes") ?: 25.0).toInt().coerceIn(1, 8 * 60)
    timers.cancel("Focus")
    val quiet = device.doNotDisturb("priority", minutes)
    val timer = timers.start(minutes * 60, "Focus")
    return "Focus session: ${com.lukas.jarvis.notify.Timers.spoken(timer.lengthMs)}, until " +
        "${TimeUtil.formatTime(timer.endsAt)}. $quiet"
}

internal fun Tools.setTimer(args: JSONObject): String {
    val minutes = number(args, "minutes") ?: Double.NaN
    if (minutes.isNaN() || minutes <= 0) return "A timer needs a length in minutes."
    val seconds = (minutes * 60).toInt().coerceAtLeast(1)
    // "timer" says nothing the notification does not; the length names it better.
    val label = args.optString("label").trim().takeIf {
        it.isNotBlank() && it.lowercase(Locale.ROOT).removePrefix("a ").removePrefix("the ") !in GENERIC_TIMER_LABELS
    }
    val sleep = args.optBoolean("stop_music", false)
    val timer = timers.start(seconds, label, sleep)
    val length = com.lukas.jarvis.notify.Timers.spoken(timer.lengthMs)
    if (sleep) return "Sleep timer set: the music stops in $length, at ${TimeUtil.formatTime(timer.endsAt)}."
    val what = if (label == null) "Timer set for $length" else "${timer.label} timer set for $length"
    return "$what — done at ${TimeUtil.formatTime(timer.endsAt)}."
}

internal fun Tools.timerAction(args: JSONObject): String {
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
internal suspend fun Tools.leaveReminder(title: String, start: Long, location: String?, settings: Settings): String {
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

internal fun Tools.deviceStatus(args: JSONObject): String =
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

internal fun Tools.clipboard(args: JSONObject): String =
    when (args.optString("action").trim().lowercase(Locale.ROOT)) {
        "read", "paste", "get" -> device.paste()
        else -> device.copy(args.optString("text"))
    }

internal fun Tools.playback(args: JSONObject): String =
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

internal fun Tools.bluetooth(args: JSONObject): String =
    when (args.optString("action").trim().lowercase(Locale.ROOT)) {
        "open_settings", "settings", "open" -> phone.openBluetoothSettings()
        else -> phone.bluetoothDevices()
    }
