package com.lukas.jarvis.moment

import com.lukas.jarvis.llm.PendingAction
import com.lukas.jarvis.llm.Risk
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolOutput
import org.json.JSONObject

/** Where a room button leads. */
enum class Room { Talk, Today, Library, Lists, Memory, Money, Tasks, Map, Settings, Powers, History }

/**
 * What a tap on a card does. Every one of them leads somewhere: something is
 * said to Mochi, something is done, undone, kept, or opened.
 */
sealed interface ActionIntent {
    /** Handed to Mochi as if the person had said it. */
    data class Say(val text: String) : ActionIntent
    data class Confirm(val id: Long) : ActionIntent
    data class EditPending(val id: Long, val key: String, val value: String) : ActionIntent
    data class Cancel(val id: Long) : ActionIntent
    /** Puts back what a tool just did, by running its opposite. The tap is the person's yes. */
    data class Undo(val tool: String, val argumentsJson: String, val label: String) : ActionIntent
    data class Open(val room: Room) : ActionIntent
    data class Pin(val cardId: String) : ActionIntent
    data class Unpin(val cardId: String) : ActionIntent
    data class Dismiss(val cardId: String) : ActionIntent
    data object Retry : ActionIntent
    /** The free keyless models, or offline: the other way when the first one failed. */
    data object UseFreeModels : ActionIntent
    data object Listen : ActionIntent
    /** Opens the keyboard, with [prefill] already written: "Change that so ". */
    data class Type(val prefill: String = "") : ActionIntent
    data object Stop : ActionIntent
    data class StopTimer(val id: Int) : ActionIntent
    data class AddMinute(val id: Int) : ActionIntent
    data class Grant(val permission: String) : ActionIntent
    data class ReadAloud(val text: String) : ActionIntent
    /** Ticks a thing on a list straight from its card. */
    data class CheckItem(val list: String, val item: String, val done: Boolean) : ActionIntent
    /** Takes a card off the history strip and back onto the canvas. */
    data class BringBack(val cardId: String) : ActionIntent
    data object StopwatchToggle : ActionIntent
    data object StopwatchReset : ActionIntent
}

/** One button on a card. [primary] is the card's own next step. */
data class CardAction(
    val label: String,
    val intent: ActionIntent,
    val primary: Boolean = false
) {
    /** Leads forward — does, changes, undoes or asks more — rather than only tidying the canvas. */
    val forward: Boolean
        get() = intent !is ActionIntent.Pin && intent !is ActionIntent.Unpin && intent !is ActionIntent.Dismiss
}

enum class CardStatus { Working, Done, Waiting, Failed }

/**
 * One card on the canvas. Built from what a tool really did, so what it shows
 * is never invented: [body] is the tool's own result, [lines] its items.
 */
data class CanvasCard(
    val id: String,
    val kind: CardKind,
    val title: String,
    val body: String = "",
    val tool: String? = null,
    val lines: List<String> = emptyList(),
    val slots: Map<String, String> = emptyMap(),
    val actions: List<CardAction> = emptyList(),
    val followUps: List<FollowUp> = emptyList(),
    val status: CardStatus = CardStatus.Done,
    val image: String? = null,
    val pending: PendingAction? = null,
    val pinned: Boolean = false,
    val at: Long = 0L,
    /** Real numbers to draw, from the tool that worked them out. */
    val chart: Chart? = null
) {
    val primary: CardAction? get() = actions.firstOrNull { it.primary } ?: actions.firstOrNull { it.forward }
}

/**
 * Builds cards from what the agent did. Each tool's registry entry says which
 * kind of card and which follow-ups; this file only knows kinds, so a new tool
 * gets its card without anyone touching the canvas.
 */
object Cards {

    /** A card for one tool call: its result, or the action now waiting on a yes. */
    fun fromOutput(output: ToolOutput, index: Int = 0): CanvasCard {
        output.waiting?.let { return confirm(it) }
        val info = ToolCatalog.info(output.tool)
        val kind = if (output.failed) CardKind.Problem else (info?.view ?: CardKind.Answer)
        val args = runCatching { JSONObject(output.argumentsJson) }.getOrDefault(JSONObject())
        val slots = slots(output.tool, args)
        val body = clean(output.result)
        val follow = (info?.next ?: emptyList()).mapNotNull { it.fill(slots) }.take(3)
        val id = "tool:${output.at}:$index:${output.tool}"
        val actions = buildList {
            if (output.failed) {
                add(CardAction("Try again", ActionIntent.Retry, primary = true))
                add(CardAction("Ask another way", ActionIntent.Say("That didn't work. Can you do it another way?")))
                return@buildList
            }
            follow.firstOrNull()?.let { add(CardAction(it.label, intentFor(it), primary = true)) }
            undoFor(output)?.let { add(CardAction("Undo", it)) }
            if (kind.mode == CardMode.Create) add(CardAction("Change it", ActionIntent.Type("Change that so ")))
            add(CardAction("Tell me more", ActionIntent.Say("Tell me more about that")))
            add(CardAction("Pin", ActionIntent.Pin(id)))
        }
        return CanvasCard(
            id = id,
            kind = kind,
            title = title(kind, output.tool, slots),
            body = body,
            tool = output.tool,
            lines = lines(body),
            slots = slots,
            actions = actions,
            followUps = follow.drop(1),
            status = if (output.failed) CardStatus.Failed else CardStatus.Done,
            at = output.at,
            chart = output.chart?.takeUnless { output.failed || it.isEmpty }
        )
    }

    /** The card for an action waiting on the person's yes: do it, change it, or don't. */
    fun confirm(action: PendingAction): CanvasCard = CanvasCard(
        id = "pending:${action.id}",
        kind = CardKind.Confirm,
        title = action.title,
        body = action.detail,
        tool = action.tool,
        actions = listOf(
            CardAction(action.verb, ActionIntent.Confirm(action.id), primary = true),
            CardAction("Edit", ActionIntent.EditPending(action.id, "", "")),
            CardAction("Cancel", ActionIntent.Cancel(action.id))
        ),
        status = CardStatus.Waiting,
        pending = action,
        at = action.createdAt
    )

    /**
     * A plan for the day or an errand: steps Mochi will take, each one
     * editable before it runs. Keep runs them; change them by saying so.
     */
    fun plan(title: String, steps: List<String>, at: Long): CanvasCard = CanvasCard(
        id = "plan:$at",
        kind = CardKind.Plan,
        title = title,
        lines = steps,
        actions = listOf(
            CardAction("Do it", ActionIntent.Say("Go ahead with that plan"), primary = true),
            CardAction("Change it", ActionIntent.Type("Change the plan so ")),
            CardAction("Pin", ActionIntent.Pin("plan:$at"))
        ),
        followUps = listOf(FollowUp("Add it to my calendar", "Put that plan in my calendar")),
        at = at
    )

    /** A picture the person showed Mochi, with what it saw. */
    fun photo(path: String?, seen: String, at: Long): CanvasCard = CanvasCard(
        id = "photo:$at",
        kind = CardKind.Photo,
        title = "What I saw",
        body = seen,
        image = path,
        actions = listOf(
            CardAction("Remember this", ActionIntent.Say("Remember what that picture showed"), primary = true),
            CardAction("Ask about it", ActionIntent.Type("About that picture: ")),
            CardAction("Pin", ActionIntent.Pin("photo:$at"))
        ),
        followUps = listOf(FollowUp("Read the text out", "Read me the text in that picture")),
        at = at
    )

    /** What went wrong, in plain words, with a retry and another way. */
    fun problem(message: String, at: Long, offline: Boolean): CanvasCard = CanvasCard(
        id = "problem:$at",
        kind = CardKind.Problem,
        title = if (offline) "I can't reach my thinking right now" else "That didn't work",
        body = message,
        actions = listOf(
            CardAction("Try again", ActionIntent.Retry, primary = true),
            if (offline) {
                CardAction("Do it offline", ActionIntent.Say("Try that offline"))
            } else {
                CardAction("Use the free models", ActionIntent.UseFreeModels)
            },
            CardAction("Type it differently", ActionIntent.Type())
        ),
        status = CardStatus.Failed,
        at = at
    )

    /**
     * The opposite of what a tool just did, when there is a clean one. A tap
     * on Undo is the person's own decision, so it runs without asking again.
     */
    fun undoFor(output: ToolOutput): ActionIntent.Undo? {
        if (output.failed || output.waiting != null) return null
        val args = runCatching { JSONObject(output.argumentsJson) }.getOrDefault(JSONObject())
        val id = ID.find(output.result)?.groupValues?.get(1)
        return when (output.tool) {
            "add_task" -> id?.let { ActionIntent.Undo("delete_task", JSONObject().put("id", it.toLong()).toString(), "Removed that reminder.") }
            "log_entry" -> id?.let { ActionIntent.Undo("delete_entry", JSONObject().put("id", it.toLong()).toString(), "Taken back.") }
            "repeat_entry" -> id?.takeIf { args.optString("action").lowercase() != "list" }?.let {
                ActionIntent.Undo("stop_repeat", JSONObject().put("id", it.toLong()).toString(), "Stopped. Nothing more is logged by itself.")
            }
            "remember" -> id?.let { ActionIntent.Undo("forget", JSONObject().put("id", it.toLong()).toString(), "Forgotten.") }
            "list" -> if (args.optString("action").lowercase() == "add") {
                ActionIntent.Undo("list", JSONObject(args.toString()).put("action", "remove").toString(), "Taken off the list.")
            } else {
                null
            }
            "create_routine" -> args.optString("name").takeIf { it.isNotBlank() }?.let {
                ActionIntent.Undo("delete_routine", JSONObject().put("name", it).toString(), "Routine removed.")
            }
            "save_place" -> args.optString("name").takeIf { it.isNotBlank() }?.let {
                ActionIntent.Undo("forget_place", JSONObject().put("name", it).toString(), "Place forgotten.")
            }
            else -> null
        }
    }

    /** The words a card's follow-ups and title can use, from the call's own arguments. */
    fun slots(tool: String, args: JSONObject): Map<String, String> {
        fun s(vararg keys: String) = keys.asSequence().map { args.optString(it).trim() }.firstOrNull { it.isNotEmpty() }
        val out = linkedMapOf<String, String>()
        s("place", "destination", "name", "query", "address", "to")?.takeIf { tool in PLACE_TOOLS }?.let { out["place"] = it }
        s("title", "text")?.takeIf { tool in TASK_TOOLS }?.let { out["title"] = it }
        s("list", "name")?.takeIf { tool == "list" }?.let { out["list"] = it }
        s("tracker", "name")?.takeIf { tool in MONEY_TOOLS }?.let { out["tracker"] = it }
        s("to", "language")?.takeIf { tool == "translate" || tool == "interpreter" }?.let { out["language"] = it }
        s("who", "name")?.takeIf { tool == "find_contact" }?.let { out["who"] = it }
        s("query", "topic", "word")?.let { out["topic"] = it }
        s("name")?.takeIf { tool in ROUTINE_TOOLS }?.let { out["routine"] = it }
        return out
    }

    private fun title(kind: CardKind, tool: String, slots: Map<String, String>): String =
        slots["list"]?.let { "${it.replaceFirstChar { c -> c.titlecase() }} list" }
            ?: slots["place"]?.takeIf { kind == CardKind.Route || kind == CardKind.Place }
            ?: slots["title"]?.takeIf { kind == CardKind.Task || kind == CardKind.Event || kind == CardKind.Plan }
            ?: ToolCatalog.info(tool)?.chip?.takeIf { kind == CardKind.Answer }
            ?: kind.label

    /** A tool's result without the bookkeeping meant for the model. */
    fun clean(result: String): String = result
        .replace(Regex("\\s*\\(ISO [^)]*\\)"), "")
        .replace(ID, "")
        .replace(Regex("(?m)^(Tell the user|In one short line|Describe it)[^\\n]*$"), "")
        .trim()

    /** A result that is a list, one item per line, for a card that shows items. */
    private fun lines(body: String): List<String> {
        val rows = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val bullets = rows.filter { it.startsWith("- ") || it.startsWith("• ") }
        return if (bullets.size >= 2) bullets.map { it.drop(2).trim() } else emptyList()
    }

    private val ID = Regex("\\s*\\(id (\\d+)\\)")
    private val PLACE_TOOLS = setOf("find_places", "route_to", "start_navigation", "save_place", "show_on_map", "rename_place", "place_reminder", "wikipedia")
    private val TASK_TOOLS = setOf("add_task", "update_task", "complete_task", "add_calendar_event", "change_calendar_event", "countdown", "make_plan")
    private val MONEY_TOOLS = setOf("log_entry", "tracker_status", "configure_tracker", "list_entries", "spending_report", "repeat_entry", "stop_repeat")
    private val ROUTINE_TOOLS = setOf("create_routine", "run_routine", "delete_routine")

    /**
     * What a follow-up does when tapped: said straight away, or — when it is
     * the start of a sentence for the person to finish, "Remember that " —
     * written into the composer for them to complete.
     */
    fun intentFor(follow: FollowUp): ActionIntent =
        if (follow.say.endsWith(" ")) ActionIntent.Type(follow.say) else ActionIntent.Say(follow.say)

    /** True when a tool is one the person should be asked about before it runs. */
    fun asksFirst(tool: String): Boolean = ToolCatalog.risk(tool) in setOf(Risk.Outward, Risk.Sensitive)
}
