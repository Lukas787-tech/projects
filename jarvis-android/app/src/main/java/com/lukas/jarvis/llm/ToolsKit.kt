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

// The shared pieces every group of tools is written with: the schema sugar, number reading, constants.

internal fun Tools.tool(
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

internal fun Tools.props(vararg entries: Pair<String, JSONObject>): JSONObject =
    JSONObject().apply { entries.forEach { (key, value) -> put(key, value) } }

internal fun Tools.str(description: String, values: List<String>? = null): JSONObject =
    JSONObject().apply {
        put("type", "string")
        put("description", description)
        values?.let { put("enum", JSONArray(it)) }
    }

internal fun Tools.num(description: String): JSONObject =
    JSONObject().apply {
        put("type", "number")
        put("description", description)
    }

internal fun Tools.int(description: String): JSONObject =
    JSONObject().apply {
        put("type", "integer")
        put("description", description)
    }

internal fun Tools.bool(description: String): JSONObject =
    JSONObject().apply {
        put("type", "boolean")
        put("description", description)
    }

internal fun Tools.arr(description: String): JSONObject =
    JSONObject().apply {
        put("type", "array")
        put("description", description)
        put("items", JSONObject().apply { put("type", "string") })
    }

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}

/**
 * A number however a small model chose to write it: 50, 50.0, "50",
 * "50%" or "30 minutes". A plain optInt reads "50%" as 0, which for a
 * volume means silence.
 */
internal fun Tools.number(args: JSONObject, key: String): Double? {
    if (!args.has(key) || args.isNull(key)) return null
    return when (val raw = args.opt(key)) {
        is Number -> raw.toDouble()
        is String -> com.lukas.jarvis.core.Numbers.first(raw)
        else -> null
    }?.takeIf { !it.isNaN() }
}

internal fun Tools.optDoubleOrNull(args: JSONObject, key: String): Double? = number(args, key)

/** The tools that read or write trackers, for which anything already due is logged first. */
internal val MONEY_TOOLS = setOf(
    "log_entry", "tracker_status", "configure_tracker", "list_entries",
    "spending_report", "repeat_entry", "stop_repeat", "delete_entry"
)

/** "My own voice", "meine eigene Stimme": the account's own clones. */
internal val MY_VOICE = Regex("\\b(my own|my clone|my cloned|my voice|meine eigene|meiner eigenen|meine stimme|meiner stimme|mein klon)")

/** Timer names that are no name at all. */
internal val GENERIC_TIMER_LABELS = setOf("timer", "countdown", "count down", "alarm", "wecker", "kurzzeitwecker")

/** Settings tabs by what people call what is on them. */
internal val SETTINGS_TABS = linkedMapOf(
    1 to listOf("voice", "speak", "speech", "wake word", "stimme", "sprache"),
    2 to listOf("look", "colour", "color", "theme", "accent", "backdrop", "farbe", "design"),
    3 to listOf("brain", "model", "api key", "provider", "modell"),
    4 to listOf("power", "abilit", "permission", "home assistant", "smart home", "fähigkeit"),
    5 to listOf("backup", "restore", "data", "sicherung"),
    0 to listOf("personality", "name", "about me", "instruction", "persönlichkeit")
)
internal val ITEM_SEPARATOR = Regex("\\s*(?:,|;|\\band\\b|\\bund\\b)\\s*")
internal val CURRENCIES = setOf(
    "EUR", "USD", "GBP", "CHF", "PLN", "CZK", "SEK", "NOK", "DKK",
    "CAD", "AUD", "JPY", "TRY", "HUF", "RON", "BGN", "INR", "BRL"
)
