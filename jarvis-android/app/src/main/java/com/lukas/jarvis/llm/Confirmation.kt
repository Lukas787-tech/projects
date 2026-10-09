package com.lukas.jarvis.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** One argument of a waiting action that the person may change before saying yes. */
data class EditableField(
    val key: String,
    val label: String,
    val value: String,
    val multiline: Boolean = false
)

/**
 * Something Mochi is ready to do and will not do until the person says yes:
 * a text, a call, an email, a share, a delete, a press of the phone's buttons.
 *
 * It holds the exact call the model asked for, so what runs on "Send" is what
 * the card showed — after any edit the person made, and nothing else.
 */
data class PendingAction(
    val id: Long,
    val tool: String,
    val argumentsJson: String,
    val risk: Risk,
    /** "Text Anna", "Call Mum", "Forget a memory". */
    val title: String,
    /** The words or the detail being acted on, quoted on the card. */
    val detail: String,
    /** The yes button's word: Send, Call, Delete, Press, Share. */
    val verb: String,
    val fields: List<EditableField>,
    val createdAt: Long
) {
    val call: ToolCall get() = ToolCall("confirmed_$id", tool, argumentsJson)

    /** How it reads in one line, for the model, TalkBack and a notification. */
    val line: String get() = if (detail.isBlank()) title else "$title: “$detail”"
}

/**
 * The one door every outward or irreversible tool call goes through.
 *
 * The agent never runs such a call itself. It hands it here, gets a
 * [PendingAction] back, and tells the model that the action is waiting on the
 * person. The card on screen (or a spoken yes) is the only way it runs. A turn
 * that ends, a model that hallucinates a yes, a routine running in the
 * background — none of them can send a text; only the person can.
 */
class ConfirmationGate(private val clock: () -> Long = System::currentTimeMillis) {

    private val ids = AtomicLong(0)
    private val _pending = MutableStateFlow<List<PendingAction>>(emptyList())

    /** Everything waiting on a yes, oldest first. */
    val pending: StateFlow<List<PendingAction>> = _pending.asStateFlow()

    /** Whether this call must wait for the person. */
    fun asksFirst(tool: String, argumentsJson: String): Boolean =
        ToolCatalog.riskFor(tool, argumentsJson).asksFirst

    /**
     * Holds [call] for the person's yes. A second request for the same tool
     * replaces the first — the model rewording a text it was asked to change
     * is one text, not two.
     */
    fun propose(call: ToolCall): PendingAction {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrDefault(JSONObject())
        val described = ActionText.describe(call.name, args)
        val action = PendingAction(
            id = ids.incrementAndGet(),
            tool = call.name,
            argumentsJson = args.toString(),
            risk = ToolCatalog.riskFor(call.name, call.argumentsJson),
            title = described.title,
            detail = described.detail,
            verb = described.verb,
            fields = described.fields,
            createdAt = clock()
        )
        _pending.update { list -> list.filterNot { it.tool == call.name } + action }
        return action
    }

    fun find(id: Long): PendingAction? = _pending.value.firstOrNull { it.id == id }

    /** Changes one field of a waiting action. The card stays waiting. */
    fun edit(id: Long, key: String, value: String): PendingAction? {
        var edited: PendingAction? = null
        _pending.update { list ->
            list.map { action ->
                if (action.id != id) return@map action
                val args = runCatching { JSONObject(action.argumentsJson) }.getOrDefault(JSONObject())
                args.put(key, value)
                val described = ActionText.describe(action.tool, args)
                action.copy(
                    argumentsJson = args.toString(),
                    title = described.title,
                    detail = described.detail,
                    fields = described.fields
                ).also { edited = it }
            }
        }
        return edited
    }

    /** Takes a waiting action out to run it. Null when it is no longer waiting. */
    fun take(id: Long): PendingAction? {
        var taken: PendingAction? = null
        _pending.update { list ->
            taken = list.firstOrNull { it.id == id }
            list.filterNot { it.id == id }
        }
        return taken
    }

    /** Drops a waiting action without running it. */
    fun cancel(id: Long): PendingAction? = take(id)

    fun clear() = _pending.update { emptyList() }

    /**
     * The action a spoken "yes" would answer: the newest one, and only while
     * the question is fresh. A yes three minutes later is answering something
     * else, so it is not taken as this one's.
     */
    fun awaitingVoice(): PendingAction? =
        _pending.value.lastOrNull()?.takeIf { clock() - it.createdAt <= VOICE_WINDOW_MS }

    /** What the model is told instead of the tool's result. */
    fun waitingLine(action: PendingAction): String =
        "NOT DONE YET — waiting for the user's OK. A card is on screen: ${action.line} " +
            "(buttons: ${action.verb} / Edit / Cancel); they can also just say yes or no. " +
            "In one short sentence, say what you are about to do and that it is waiting for their OK. " +
            "Do not say it is done, and do not call ${action.tool} again unless they ask for a change."

    companion object {
        const val VOICE_WINDOW_MS = 3 * 60_000L

        private val YES = Regex(
            "^(yes|yeah|yep|yup|sure|ok|okay|do it|go|go ahead|go on|send|send it|call|call them|ring them|" +
                "confirm|confirmed|please do|do that|that's right|correct|delete it|ja|jawohl|mach|mach das|" +
                "schick|schick es|senden|los)( please| bitte| now| jetzt)?[.!]*$"
        )
        private val NO = Regex(
            "^(no|nope|nah|cancel|cancel it|stop|don't|do not|never mind|nevermind|forget it|wait|hold on|" +
                "nein|abbrechen|lass es|stopp)( please| bitte| that)?[.!]*$"
        )

        /** A plain yes to a waiting action, said or typed. */
        fun isYes(text: String): Boolean = YES.matches(normalise(text))

        /** A plain no to a waiting action. */
        fun isNo(text: String): Boolean = NO.matches(normalise(text))

        private fun normalise(text: String): String =
            text.trim().lowercase(Locale.ROOT)
                .replace(Regex("^(hey )?(mochi|jarvis)[,!. ]+"), "")
                .replace('’', '\'')
                .trim()
    }
}

/** How a waiting action is put into words on its card. */
object ActionText {

    data class Described(
        val title: String,
        val detail: String,
        val verb: String,
        val fields: List<EditableField>
    )

    fun describe(tool: String, args: JSONObject): Described {
        fun s(key: String) = args.optString(key).trim()
        return when (tool) {
            "send_message" -> Described(
                title = "Text ${s("who").ifBlank { s("number").ifBlank { "someone" } }}",
                detail = s("text"),
                verb = "Send",
                fields = listOf(
                    EditableField("number", "To", s("number")),
                    EditableField("text", "Message", s("text"), multiline = true)
                )
            )
            "send_chat_message" -> Described(
                title = "Message ${s("who").ifBlank { "someone" }} on ${appName(s("app"))}",
                detail = s("text"),
                verb = "Send",
                fields = listOf(
                    EditableField("who", "To", s("who")),
                    EditableField("text", "Message", s("text"), multiline = true)
                )
            )
            "reply_to_message" -> Described(
                title = "Reply to ${s("who").ifBlank { "the newest message" }}",
                detail = s("text"),
                verb = "Send",
                fields = listOf(EditableField("text", "Reply", s("text"), multiline = true))
            )
            "call" -> Described(
                title = "Call ${s("who").ifBlank { s("number") }}",
                detail = if (s("who").isNotBlank()) s("number") else "",
                verb = "Call",
                fields = listOf(EditableField("number", "Number", s("number")))
            )
            "place_call" -> Described("Ring the number that's ready", "", "Call", emptyList())
            "send_email" -> Described(
                title = "Email ${s("to").ifBlank { "someone" }}",
                detail = listOf(s("subject"), s("body")).filter { it.isNotBlank() }.joinToString(" — "),
                verb = "Open to send",
                fields = listOf(
                    EditableField("to", "To", s("to")),
                    EditableField("subject", "Subject", s("subject")),
                    EditableField("body", "Message", s("body"), multiline = true)
                )
            )
            "share" -> Described(
                title = "Share ${s("title").ifBlank { "this" }}",
                detail = s("text"),
                verb = "Share",
                fields = listOf(EditableField("text", "What to share", s("text"), multiline = true))
            )
            "share_location" -> Described(
                title = "Send your location to ${s("who").ifBlank { s("number").ifBlank { "someone" } }}",
                detail = s("note"),
                verb = "Send",
                fields = emptyList()
            )
            "forget" -> Described("Forget a memory", s("content").ifBlank { "memory #${s("id")}" }, "Forget", emptyList())
            "delete_entry" -> Described("Take back a tracker entry", entryDetail(args), "Take back", emptyList())
            "delete_task" -> Described("Delete a reminder", s("title").ifBlank { "reminder #${s("id")}" }, "Delete", emptyList())
            "delete_routine" -> Described("Delete the ${s("name")} routine", "", "Delete", emptyList())
            "forget_place" -> Described("Forget the place ${s("name")}", "", "Forget", emptyList())
            "change_calendar_event" -> {
                val cancel = s("action").lowercase(Locale.ROOT) in setOf("cancel", "delete", "remove")
                if (cancel) {
                    Described("Cancel ${s("title").ifBlank { "the appointment" }}", "", "Cancel it", emptyList())
                } else {
                    Described(
                        "Move ${s("title").ifBlank { "the appointment" }}",
                        s("start"),
                        "Move",
                        listOf(EditableField("start", "New time", s("start")))
                    )
                }
            }
            "system_action" -> Described(
                "Press ${s("action").replace('_', ' ').ifBlank { "a button" }}",
                "",
                "Press",
                emptyList()
            )
            "home_control" -> Described(
                "${s("action").replaceFirstChar { it.titlecase(Locale.ROOT) }.ifBlank { "Change" }} ${s("target")}",
                "",
                "Do it",
                emptyList()
            )
            else -> Described(
                title = ToolCatalog.info(tool)?.chip ?: tool.replace('_', ' '),
                detail = args.keys().asSequence().joinToString(", ") { "$it: ${args.opt(it)}" },
                verb = "Go ahead",
                fields = emptyList()
            )
        }
    }

    private fun appName(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
        "whatsapp" -> "WhatsApp"
        "telegram" -> "Telegram"
        "signal" -> "Signal"
        else -> raw.ifBlank { "chat" }
    }

    private fun entryDetail(args: JSONObject): String {
        val id = args.optString("id").trim()
        val tracker = args.optString("tracker").trim()
        return listOfNotNull(
            tracker.takeIf { it.isNotBlank() },
            id.takeIf { it.isNotBlank() }?.let { "entry #$it" }
        ).joinToString(", ").ifBlank { "the last entry" }
    }
}
