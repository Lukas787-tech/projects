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

// Memory: remember, recall, correct and forget.

internal fun Tools.memoryTools(): List<JSONObject> = listOf(
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

internal fun Tools.remember(args: JSONObject, effects: ToolEffects): String {
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

internal fun Tools.recall(args: JSONObject): String {
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

internal fun Tools.updateMemory(args: JSONObject, effects: ToolEffects): String {
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

internal fun Tools.forget(args: JSONObject, effects: ToolEffects): String {
    val id = args.optLong("id", -1L)
    if (id <= 0) return "Need a valid memory id."
    val existing = brain.getMemory(id) ?: return "No memory with id $id."
    brain.deleteMemory(id)
    effects.memoriesChanged = true
    return "Forgot: ${existing.content}"
}

internal fun Tools.recallConversation(args: JSONObject): String {
    val query = args.optString("query").trim()
    if (query.isBlank()) return "Need something to look for."
    val hits = brain.searchMessages(query, args.optInt("limit", 8).coerceIn(1, 25))
    if (hits.isEmpty()) return "Nothing in your past conversations mentions '$query'."
    return hits.reversed().joinToString("\n") { message ->
        val who = if (message.role == ChatMessage.ROLE_USER) "They said" else "You said"
        "$who (${TimeUtil.relative(message.createdAt)}): ${message.content.take(220)}"
    }
}
