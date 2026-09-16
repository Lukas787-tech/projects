package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.notify.Reminders
import com.lukas.jarvis.web.WebTools
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/** What changed as a result of a turn, so the UI knows which tabs to refresh. */
data class ToolEffects(
    var memoriesChanged: Boolean = false,
    var trackersChanged: Boolean = false,
    var tasksChanged: Boolean = false
) {
    val any: Boolean get() = memoriesChanged || trackersChanged || tasksChanged
}

/**
 * The bridge between the model and the phone. Every tool returns plain text
 * rather than JSON: small free-tier models read prose far more reliably than
 * they read nested objects.
 */
class Tools(
    private val brain: Brain,
    private val web: WebTools,
    private val reminders: Reminders
) {

    fun schemas(settings: Settings): List<JSONObject> {
        val list = mutableListOf(
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
                "forget",
                "Delete a memory by its id. Only use when the user asks to forget something.",
                props("id" to int("The memory id shown by recall.")),
                listOf("id")
            ),
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
                "List recent movements on a tracker, newest first.",
                props(
                    "tracker" to str("Name of the tracker, or omit for everything."),
                    "limit" to int("How many. Default 10.")
                ),
                emptyList()
            ),
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
                "now",
                "Get the current date, time and day of week.",
                props(),
                emptyList()
            )
        )

        if (settings.webSearchEnabled) {
            list += tool(
                "web_search",
                "Search the live internet. Use for news, prices, opening hours, facts you are " +
                    "unsure about, or anything after your training cutoff.",
                props(
                    "query" to str("Search terms."),
                    "limit" to int("How many results. Default 5.")
                ),
                listOf("query")
            )
            list += tool(
                "open_url",
                "Fetch a web page and read its text. Use to follow up on a search result.",
                props("url" to str("Full URL to open.")),
                listOf("url")
            )
        }
        return list
    }

    suspend fun execute(call: ToolCall, settings: Settings, effects: ToolEffects): String {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrDefault(JSONObject())
        return try {
            when (call.name) {
                "remember" -> remember(args, effects)
                "recall" -> recall(args)
                "forget" -> forget(args, effects)
                "log_entry" -> logEntry(args, settings, effects)
                "tracker_status" -> trackerStatus(args)
                "configure_tracker" -> configureTracker(args, settings, effects)
                "list_entries" -> listEntries(args)
                "add_task" -> addTask(args, effects)
                "list_tasks" -> listTasks(args)
                "complete_task" -> completeTask(args, effects)
                "web_search" -> webSearch(args)
                "open_url" -> web.readPage(args.optString("url"))
                "now" -> nowText()
                else -> "Unknown tool '${call.name}'."
            }
        } catch (e: Exception) {
            "Tool '${call.name}' failed: ${e.message ?: e::class.java.simpleName}"
        }
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

    private fun forget(args: JSONObject, effects: ToolEffects): String {
        val id = args.optLong("id", -1L)
        if (id <= 0) return "Need a valid memory id."
        val existing = brain.getMemory(id) ?: return "No memory with id $id."
        brain.deleteMemory(id)
        effects.memoriesChanged = true
        return "Forgot: ${existing.content}"
    }

    // ---------------------------------------------------------------- trackers

    private fun logEntry(args: JSONObject, settings: Settings, effects: ToolEffects): String {
        val name = args.optString("tracker").trim()
        if (name.isBlank()) return "Need a tracker name."
        val amount = args.optDouble("amount", Double.NaN)
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
            "$sign${money(e.amount, owner)} ${owner?.label ?: "?"}" +
                (e.note?.let { " ($it)" } ?: "") +
                " — ${TimeUtil.relative(e.occurredAt)}"
        }
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
                "${money(it, t)} of budget left this ${periodWord(t.period)}"
            } else {
                "${money(-it, t)} over budget this ${periodWord(t.period)}"
            }
        }
        parts += "${money(status.periodSpent, t)} used this ${periodWord(t.period)}"
        if (status.periodReceived > 0) parts += "${money(status.periodReceived, t)} added"
        append(parts.joinToString(", "))
    }

    private fun periodWord(period: String): String = when (period) {
        Tracker.PERIOD_DAILY -> "day"
        Tracker.PERIOD_WEEKLY -> "week"
        Tracker.PERIOD_MONTHLY -> "month"
        else -> "period"
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
            repeatRule = args.optString("repeat").ifBlank { Task.REPEAT_NONE }
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
        reminders.cancel(id)
        effects.tasksChanged = true
        return "Completed: ${done.title}"
    }

    // --------------------------------------------------------------------- web

    private suspend fun webSearch(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) return "Need something to search for."
        val limit = args.optInt("limit", 5).coerceIn(1, 8)

        val direct = web.instantAnswer(query)
        val results = web.search(query, limit)
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

    private fun nowText(): String {
        val now = System.currentTimeMillis()
        return "Current local date and time: ${TimeUtil.format(now)} (ISO ${TimeUtil.iso(now)})."
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

    private fun optDoubleOrNull(args: JSONObject, key: String): Double? {
        if (!args.has(key) || args.isNull(key)) return null
        val value = args.optDouble(key, Double.NaN)
        return if (value.isNaN()) null else value
    }

    private companion object {
        val CURRENCIES = setOf(
            "EUR", "USD", "GBP", "CHF", "PLN", "CZK", "SEK", "NOK", "DKK",
            "CAD", "AUD", "JPY", "TRY", "HUF", "RON", "BGN", "INR", "BRL"
        )
    }
}
