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

// The screen and the eyes: showing things, reading the screen, the camera, pictures, the interpreter.

internal fun Tools.showTool(): JSONObject = tool(
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

internal fun Tools.visionTools(settings: Settings): List<JSONObject> = buildList {
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

internal fun Tools.interpreter(args: JSONObject, settings: Settings): String {
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

internal fun Tools.readScreen(): String {
    val reading = ScreenReader.capture()
        ?: return "Screen reading is switched off. Tell the user it is in You -> Powers -> " +
            "Reading the screen, and that Android asks once on its accessibility page."
    if (reading.app.isBlank()) {
        return "Only Mochi is on screen right now, so there is nothing else to read. Tell the " +
            "user to ask from the floating dot or with the wake word while the other app is open."
    }
    if (reading.text.isBlank()) return "The screen (${reading.app}) shows no readable text."
    return "On screen in ${reading.app}:\n${reading.text}"
}

internal suspend fun Tools.systemAction(args: JSONObject): String {
    val action = com.lukas.jarvis.control.SystemAction.byId(args.optString("action"))
        ?: return "Which button? One of: ${com.lukas.jarvis.control.SystemAction.ids.joinToString()}."
    if (android.os.Build.VERSION.SDK_INT < action.minSdk) {
        return "This phone's Android is too old to press ${action.id.replace('_', ' ')} from an app."
    }
    if (!ScreenReader.running) {
        return "Pressing system buttons goes through Mochi's screen access, which is switched " +
            "off. Tell the user it is in You -> Powers -> Reading the screen, and that Android " +
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
        null -> "The screen access stopped running. It can be switched on again under You -> Powers -> Reading the screen."
    }
}

internal suspend fun Tools.generateImage(args: JSONObject, effects: ToolEffects): String {
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

internal fun Tools.show(args: JSONObject): String {
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

internal fun Tools.takePhoto(args: JSONObject): String {
    val question = args.optString("question").trim().ifBlank { "What is this?" }
    camera.ask(question, fromGallery = args.optString("source") == "gallery")
    return "The camera is opening for the user now. Tell them in one short line to take " +
        "the picture, then stop. You will get the photo in their next message."
}

internal suspend fun Tools.wikipedia(args: JSONObject): String {
    val topic = args.optString("topic").trim()
    if (topic.isBlank()) return "Need a topic to look up."
    val language = args.optString("language").trim().ifBlank { Locale.getDefault().language }
    return web.wikipedia(topic, language)
        ?: "Wikipedia has no article that matches '$topic'."
}

internal suspend fun Tools.shareLocation(args: JSONObject): String {
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
