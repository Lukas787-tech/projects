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

// Lists: shopping, packing, anything without a time.

internal fun Tools.listTool(): JSONObject = tool(
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

internal fun Tools.listAction(args: JSONObject): String {
    val action = args.optString("action").trim().lowercase(Locale.ROOT)
    val name = args.optString("list").trim()
    val given = args.optJSONArray("items")?.let { array -> (0 until array.length()).map { array.optString(it) } }
        ?: listOfNotNull(args.optString("item").takeIf { it.isNotBlank() })
    // "eggs, milk and bread" handed over as one entry is three things.
    val items = given.flatMap { it.split(ITEM_SEPARATOR) }.map { it.trim() }.filter { it.isNotBlank() }
    if (action != "all" && name.isBlank()) return "Which list? Say its name, like 'shopping'."
    val label = com.lukas.jarvis.data.ListBook.canonical(name)

    fun describe(walking: Boolean = false): String {
        val list = lists.current.find(name) ?: return "There is no $label list yet."
        if (list.items.isEmpty()) return "The $label list is empty."
        val open = list.open.map { it.text }
        val done = list.items.filter { it.done }.map { it.text }
        // Read out at the shop, the shopping list goes round it in order.
        val walk = if (walking && open.size >= 3 && com.lukas.jarvis.data.Aisle.suits(label)) {
            com.lukas.jarvis.data.Aisle.walk(list)?.lines()?.joinToString("; ")
        } else null
        return buildString {
            append("${label.replaceFirstChar { it.titlecase(Locale.ROOT) }} list")
            append(if (walk != null) ", in the order of the shop: " else ": ")
            append(if (open.isEmpty()) "everything is ticked off" else walk ?: open.joinToString(", "))
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
        else -> describe(walking = true)
    }
}
