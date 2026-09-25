package com.lukas.jarvis.llm

import com.lukas.jarvis.auto.Routine
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.ChatMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class AgentResult(
    val reply: String,
    val effects: ToolEffects,
    /** Every tool that ran this turn, in order and once each, for the chips under the reply. */
    val toolsUsed: List<String>
)

/**
 * Runs one user turn to completion: build context, call the model, run whatever
 * tools it asks for, feed the results back, repeat until it answers in words.
 *
 * Most of what is here beyond that loop exists because the models are small.
 * They ask for `get_weather` when the tool is `weather`, they ask for the same
 * lookup twice, and now and then they ask for it a third time instead of
 * answering. Each of those costs a round of somebody's free quota, so each is
 * caught here rather than paid for.
 */
class Agent(
    private val client: PooledLlm,
    private val tools: Tools,
    private val brain: Brain
) {

    /** Lines about the world right now — roughly where the phone is — for the context block. */
    @Volatile
    var ambient: () -> List<String> = { emptyList() }

    suspend fun respond(
        utterance: String,
        settings: Settings,
        history: List<ChatMessage>,
        onStage: (String) -> Unit = {},
        /** Called with each tool's real name as it is about to run. */
        onTool: (String) -> Unit = {},
        /**
         * The answer so far, while it is still being written, cleaned of any
         * reasoning or tool markup. Empty means "nothing to show".
         */
        onDraft: (String) -> Unit = {}
    ): AgentResult {
        val draft = DraftStream(onDraft)
        val effects = ToolEffects()
        val used = mutableListOf<String>()
        val everything = tools.schemas(settings)
        // Everything switched on may run if asked for by name; only the tools
        // this sentence points at are described to the model, which keeps the
        // request small and a small model's choices short.
        val available = names(everything)
        val schemas = ToolRouter.select(everything, utterance, history)
        val offered = names(schemas)

        // What this turn has already asked for, and what came back. Keyed on the
        // tool and its arguments, so the same question asked twice is answered
        // once.
        val answered = ConcurrentHashMap<String, String>()

        val messages = mutableListOf<LlmMessage>()
        messages += LlmMessage.system(Prompt.system(settings, offered))
        trimToBudget(history).forEach { past ->
            messages += LlmMessage(
                role = if (past.role == ChatMessage.ROLE_USER) LlmMessage.USER else LlmMessage.ASSISTANT,
                content = past.content
            )
        }
        messages += LlmMessage.system(
            Prompt.context(brain, utterance, settings, runCatching { ambient() }.getOrDefault(emptyList()))
        )
        messages += LlmMessage.user(utterance)

        var lastText: String? = null
        // What the last thing done (not looked up) said, in case the model's
        // answer forgets to: "Stopwatch started."
        var lastAction: String? = null
        // Asked once per turn at most: "you said it was done — do it".
        var nudged = false

        for (round in 1..MAX_ROUNDS) {
            onStage(if (round == 1) "thinking" else "working")
            draft.restart()
            val reply = client.chat(settings, messages, schemas, draft) { next ->
                // Surfaced so a quota switch is visible rather than mysterious.
                onStage("switching to $next")
            }

            val nativeCalls = reply.toolCalls
            val textCalls = if (nativeCalls.isEmpty()) parseTextToolCalls(reply.content) else emptyList()
            val calls = nativeCalls.ifEmpty { textCalls }
            // A round that ends in tool calls was not the answer, whatever
            // words it began with.
            if (calls.isNotEmpty()) draft.restart()

            if (calls.isEmpty()) {
                // Nothing was done at all, yet the sentence plainly asks for
                // something the phone can do by itself: a small model saying
                // "Stopwatch running." without starting it is caught here,
                // and the thing is done for real.
                if (used.isEmpty()) {
                    doneInstead(utterance, settings, effects, available, used, onTool)
                        ?.let { return AgentResult(it, effects, used.distinct()) }
                }
                val text = clean(reply.content)
                // A claim of something done with nothing done: once, the model
                // is told so and asked to do it — or to say it cannot.
                val asked = utterance.trim().endsWith("?") || QUESTION.containsMatchIn(utterance.trim().lowercase())
                if (!nudged && !asked && used.isEmpty() && round < MAX_ROUNDS && text != null && claimsAction(text) &&
                    offered.any { !ToolCatalog.isReadOnly(it) }
                ) {
                    nudged = true
                    messages += LlmMessage.assistant(text)
                    messages += LlmMessage.user(
                        "You said that was done, but no tool was called, so nothing happened. " +
                            "Call the tool that does it now. If no tool can, say plainly that you can't."
                    )
                    continue
                }
                if (!text.isNullOrBlank()) return AgentResult(confirmed(text, lastAction), effects, used.distinct())
                lastText = text
                break
            }

            // A round that asks only for things this turn already has is a model
            // going in a circle. Another round will not break it; being told to
            // answer with what it has will. The repeated calls are not added to
            // the transcript, so no tool call is left without a result.
            if (round > 1 && calls.all { answered.containsKey(signature(it, available)) }) {
                lastText = clean(reply.content) ?: lastText
                break
            }

            if (nativeCalls.isNotEmpty()) {
                messages += LlmMessage(
                    role = LlmMessage.ASSISTANT,
                    content = reply.content,
                    toolCalls = nativeCalls
                )
            } else {
                // The provider does not do native tool calls, so keep the raw
                // turn verbatim and hand results back as an ordinary user message.
                messages += LlmMessage.assistant(reply.content.orEmpty())
            }

            val outcomes = runRound(calls, settings, effects, available, answered, used, onStage, onTool)
            calls.indices.lastOrNull { index ->
                val info = ToolCatalog.info(ToolCatalog.resolve(calls[index].name, available) ?: calls[index].name)
                info != null && !info.readOnly
            }?.let { lastAction = outcomes[it] }

            if (nativeCalls.isNotEmpty()) {
                calls.forEachIndexed { index, call ->
                    messages += LlmMessage.toolResult(call.id, call.name, outcomes[index])
                }
            } else {
                val results = calls.indices.joinToString("\n") { index ->
                    "${calls[index].name} -> ${outcomes[index]}"
                }
                messages += LlmMessage.user(
                    "Tool results:\n$results\n\nNow answer the original question in plain speech."
                )
            }
            lastText = clean(reply.content)
        }

        // Out of rounds, or going in circles: ask once more with tools withheld
        // so it has to speak.
        onStage("thinking")
        draft.restart()
        val forced = runCatching {
            client.chat(
                settings,
                messages + LlmMessage.user("Answer now in plain speech, without using tools."),
                emptyList(),
                draft
            )
        }.getOrNull()

        val text = clean(forced?.content) ?: lastText
        return AgentResult(
            reply = text?.takeIf { it.isNotBlank() } ?: "I got the information but lost the thread. Ask me again?",
            effects = effects,
            toolsUsed = used.distinct()
        )
    }

    /**
     * The action an unmistakable sentence asks for ("set a timer for five
     * minutes", "start the stopwatch"), run when the model answered without
     * running anything. Only a thing that changes something; a lookup the
     * model answered from context is left to it.
     */
    private suspend fun doneInstead(
        utterance: String,
        settings: Settings,
        effects: ToolEffects,
        available: Set<String>,
        used: MutableList<String>,
        onTool: (String) -> Unit
    ): String? {
        val call = Reflexes.parse(utterance) ?: return null
        val tool = ToolCatalog.resolve(call.name, available) ?: return null
        // "Start over", "open a new chapter": opening an app is too easily misheard to do unasked.
        if (ToolCatalog.isReadOnly(tool) || tool == "remember" || tool == "open_app") return null
        used += tool
        onTool(tool)
        val result = runCatching { tools.execute(call.copy(name = tool), settings, effects) }.getOrNull() ?: return null
        return result.replace(Regex("\\s*\\(ISO [^)]*\\)"), "").replace(Regex("\\s*\\(id \\d+\\)"), "")
    }

    /** "I've changed…", "Done, saved", "Timer set", "Ich habe … geändert": words of a thing done. */
    private fun claimsAction(text: String): Boolean = CLAIM.containsMatchIn(text.lowercase())

    /**
     * A reply to something done that only asks "what next?" — seen from small
     * models — gets the tool's own words in front, so the user hears that it
     * happened. Anything longer, or a result too long to say, is left alone.
     */
    private fun confirmed(text: String, action: String?): String {
        if (action == null) return text
        val bare = text.trim().endsWith("?") && text.length <= 70 && text.count { it == '.' } == 0
        val short = action.length <= 160 && '\n' !in action && !action.startsWith("Error", ignoreCase = true)
        return if (bare && short) "${action.trim()} ${text.trim()}" else text
    }

    /**
     * Answers without a model, for the plain requests that never needed one —
     * the time, a sum, a timer, an alarm, the torch, a quick reminder. Used
     * when no model can be reached; null when the sentence is not one of those.
     */
    suspend fun offline(utterance: String, settings: Settings): AgentResult? {
        val call = Reflexes.parse(utterance) ?: return fromMemory(utterance)
        val available = names(tools.schemas(settings))
        if (call.name !in available) return null
        val effects = ToolEffects()
        val result = tools.execute(call, settings, effects)
        val said = if (call.name == "remember" && effects.memoriesChanged) {
            "Noted: ${Reflexes.secondPerson(JSONObject(call.argumentsJson).optString("content"))}."
        } else {
            result.replace(Regex("\\s*\\(ISO [^)]*\\)"), "").replace(Regex("\\s*\\(id \\d+\\)"), "")
        }
        return AgentResult(
            reply = "I can't reach my thinking right now, but that one I can do myself. $said",
            effects = effects,
            toolsUsed = listOf(call.name)
        )
    }

    /**
     * A translation and nothing else, for the interpreter when the free
     * translation service has none: no tools, no persona, no remarks.
     */
    suspend fun translate(text: String, from: String, to: String, settings: Settings): String? {
        val messages = listOf(
            LlmMessage(
                role = "system",
                content = "You are an interpreter. Translate the user's words from $from into $to. " +
                    "Reply with the translation only: no quotes, no notes, no romanisation."
            ),
            LlmMessage(role = "user", content = text)
        )
        return runCatching { client.chat(settings, messages).content }.getOrNull()
            ?.let { ProviderNotices.strip(it) }
            ?.trim()?.trim('"', '“', '”')
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * "What's my locker code?" with no model to ask: the memory is on the
     * phone, so a question whose words clearly match something stored is
     * answered from it. Only a close match is used — two of the question's
     * own words at least — because an unrelated memory said with confidence
     * is worse than admitting the model is out of reach.
     */
    private fun fromMemory(utterance: String): AgentResult? {
        val text = utterance.trim().lowercase()
        val asking = text.endsWith("?") || QUESTION.containsMatchIn(text)
        if (!asking) return null
        val words = text.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
        if (words.isEmpty()) return null
        val needed = if (words.size == 1) 1 else 2
        val hit = runCatching { brain.searchMemories(utterance, limit = 3) }.getOrDefault(emptyList())
            .firstOrNull { memory ->
                val content = memory.content.lowercase()
                words.count { it in content } >= needed
            } ?: return null
        return AgentResult(
            reply = "I can't reach my thinking right now, but I remember this: " +
                Reflexes.secondPerson(hit.content).trimEnd('.') + ".",
            effects = ToolEffects(),
            toolsUsed = listOf("recall")
        )
    }

    /**
     * Looks at a photo and says what is in it, in enough detail that a turn
     * with a text-only model can act on it: every number on a receipt, every
     * line of a sign, the date and place on a poster.
     */
    suspend fun look(settings: Settings, question: String, imageDataUrl: String): String =
        client.look(
            settings,
            "The user took this photo and asks: \"$question\"\n\n" +
                "First answer the question directly. Then describe the picture precisely for " +
                "an assistant who cannot see it: transcribe every piece of legible text " +
                "exactly (prices, totals, dates, times, names, addresses, phone numbers), " +
                "name the objects, and say where it seems to be. Plain text, no markdown.",
            imageDataUrl
        )

    /**
     * Carries out a routine one step at a time, each step a turn of its own.
     *
     * One turn with every step in it would ask a small model to juggle six
     * jobs in one breath, and it drops some. Separate turns each get the
     * model's whole attention and the full round budget. The runner is taken
     * away while this runs, so a step that says "run my routine" cannot loop.
     */
    suspend fun runRoutine(
        routine: Routine,
        settings: Settings,
        onStage: (String) -> Unit = {},
        onTool: (String) -> Unit = {},
        onStepFailed: () -> Unit = {}
    ): String {
        tools.routinesRunning.incrementAndGet()
        try {
            val replies = routine.steps.mapIndexed { index, step ->
                onStage("${routine.name}: step ${index + 1} of ${routine.steps.size}")
                runCatching {
                    respond(step, settings, emptyList(), onStage = {}, onTool = onTool).reply
                }.getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    onStepFailed()
                    "\"$step\" did not work: ${it.message?.lineSequence()?.firstOrNull()}"
                }
            }
            return replies.joinToString(" ")
        } finally {
            tools.routinesRunning.decrementAndGet()
        }
    }

    /**
     * Gathers a streamed answer and hands on a readable version of it: the
     * thinking of a reasoning model and any text-protocol tool block are held
     * back, since neither is ever the answer.
     */
    private inner class DraftStream(private val onDraft: (String) -> Unit) : ReplyStream {
        private val text = StringBuilder()
        private var shown = ""

        override fun restart() {
            synchronized(text) { text.setLength(0) }
            if (shown.isNotEmpty()) {
                shown = ""
                onDraft("")
            }
        }

        override fun append(text: String) {
            val whole = synchronized(this.text) { this.text.append(text).toString() }
            val readable = readable(whole)
            if (readable != shown) {
                shown = readable
                onDraft(readable)
            }
        }

        private fun readable(raw: String): String {
            var out = raw.replace(THINK_BLOCK, "")
            if (out.contains(THINK_OPEN)) out = out.substringBefore(THINK_OPEN)
            out = out.replace(TOOL_BLOCK, "")
            if (out.contains("<tool>")) out = out.substringBefore("<tool>")
            // A reply that is a bare JSON tool call in the text protocol.
            if (out.trimStart().startsWith("{") || out.trimStart().startsWith("```")) return ""
            return out.trimStart()
        }
    }

    private fun names(schemas: List<JSONObject>): Set<String> = schemas
        .mapNotNull { it.optJSONObject("function")?.optString("name") }
        .filter { it.isNotBlank() }
        .toSet()

    /** One requested call, with the real tool it maps to — or null when none does. */
    private data class Pending(val call: ToolCall, val tool: String?)

    /**
     * Runs one round's calls and returns their results in the order asked.
     *
     * When every call in the round only reads — the weather, the calendar and a
     * web search, say — they run side by side, which turns three network waits
     * into one. The moment one of them writes, the round runs in order, because
     * "log this, then tell me the balance" must read the balance after the log.
     */
    private suspend fun runRound(
        calls: List<ToolCall>,
        settings: Settings,
        effects: ToolEffects,
        available: Set<String>,
        answered: ConcurrentHashMap<String, String>,
        used: MutableList<String>,
        onStage: (String) -> Unit,
        onTool: (String) -> Unit
    ): List<String> {
        val pending = calls.map { call ->
            val tool = ToolCatalog.resolve(call.name, available)
            Pending(if (tool == null) call else call.copy(name = tool), tool)
        }
        // Earlier rounds only: two identical writes in one round can be meant
        // ("two coffees"), the same write again a round later almost never is.
        val earlier = answered.keys.toSet()

        pending.forEach { item ->
            item.tool?.let {
                used += it
                onTool(it)
            }
        }

        val names = pending.mapNotNull { it.tool }.distinct()
        val parallel = pending.size > 1 &&
            pending.all { it.tool != null && ToolCatalog.isReadOnly(it.tool) }

        return if (parallel) {
            onStage(
                if (names.size > 1) "checking ${names.size} things at once"
                else ToolCatalog.doing(names.first())
            )
            coroutineScope {
                pending.map { item ->
                    async { runOne(item, settings, effects, available, answered, earlier) }
                }.awaitAll()
            }
        } else {
            pending.map { item ->
                item.tool?.let { onStage(ToolCatalog.doing(it)) }
                runOne(item, settings, effects, available, answered, earlier)
            }
        }
    }

    private suspend fun runOne(
        item: Pending,
        settings: Settings,
        effects: ToolEffects,
        available: Set<String>,
        answered: ConcurrentHashMap<String, String>,
        earlier: Set<String>
    ): String {
        val key = signature(item.call, available)
        // Remembered like any answer, so a model that keeps asking for a tool
        // that is not there trips the circle check instead of using up rounds.
        val tool = item.tool ?: return unavailable(item.call.name, available).also { answered[key] = it }

        if (ToolCatalog.isReadOnly(tool)) {
            answered[key]?.let { return it }
        } else if (key in earlier) {
            val before = answered[key].orEmpty()
            return "Already done earlier in this turn, so not repeated. It said: $before"
        }

        val result = clamp(tools.execute(item.call, settings, effects))
        answered[key] = result
        return result
    }

    /**
     * Why a requested tool did not run, worded so the next round can fix it.
     *
     * A tool that exists but whose ability is switched off is said to be off,
     * so the answer can tell the user where the switch is. It is never run
     * anyway: the switch is the user's, and a model finding the name in its
     * training is not the user turning it back on.
     */
    private fun unavailable(requested: String, available: Set<String>): String {
        val meant = ToolCatalog.canonical(requested)
        val info = meant?.let { ToolCatalog.info(it) }
        if (info != null) {
            val ability = Abilities.ALL.firstOrNull { it.group == info.group }?.title ?: "That ability"
            return "'$requested' is not available: $ability is switched off in Settings -> " +
                "Abilities. Tell the user they can switch it on there."
        }
        val near = ToolCatalog.closest(requested, available).joinToString(", ")
        return "There is no tool called '$requested'. The closest ones are: $near."
    }

    /** The tool and its arguments with the keys in a fixed order. */
    private fun signature(call: ToolCall, available: Set<String>): String {
        val name = ToolCatalog.resolve(call.name, available) ?: call.name
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull()
            ?: return "$name:${call.argumentsJson.trim()}"
        val keys = args.keys().asSequence().toList().sorted()
        return name + ":" + keys.joinToString(",") { key -> "$key=${args.opt(key)}" }
    }

    /**
     * Drops the oldest turns until the conversation fits a sane prompt size.
     *
     * Free tiers meter tokens per minute as strictly as they meter requests, and
     * a long conversation quietly grows until every turn costs several times what
     * the first one did. Dropping the far end of the history is cheap — anything
     * that mattered is in the brain, and the context block re-retrieves it every
     * turn anyway — while being throttled mid-sentence is not.
     */
    private fun trimToBudget(history: List<ChatMessage>): List<ChatMessage> {
        var budget = MAX_HISTORY_CHARS
        val kept = ArrayDeque<ChatMessage>()
        // Newest first, so it is always the oldest turns that fall off the end.
        for (message in history.asReversed()) {
            val cost = message.content.length
            if (cost > budget && kept.isNotEmpty()) break
            budget -= cost
            kept.addFirst(message)
        }
        return kept.toList()
    }

    /**
     * Tool output is the other thing that quietly inflates a prompt: a web page
     * can be tens of thousands of characters, and every later round in the turn
     * pays for it again.
     */
    private fun clamp(result: String): String =
        if (result.length <= MAX_TOOL_RESULT_CHARS) {
            result
        } else {
            result.take(MAX_TOOL_RESULT_CHARS) + "\n… (truncated)"
        }

    /**
     * Strips the text-protocol blocks so they never reach the screen or the
     * speaker — and a reasoning model's thinking, which several free models
     * (DeepSeek R1, Qwen 3, the distills) write into the reply itself between
     * think tags. An unclosed one means the answer never came; that reads as
     * no answer rather than as the model muttering to itself out loud.
     */
    private fun clean(raw: String?): String? {
        if (raw == null) return null
        return raw
            .replace(THINK_BLOCK, " ")
            .let { text -> if (text.contains(THINK_OPEN)) text.substringBefore(THINK_OPEN) else text }
            .replace(TOOL_BLOCK, " ")
            .replace(FENCED_TOOL, " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .ifBlank { null }
    }

    /**
     * Fallback for models without native tool calling: pull `<tool>{...}</tool>`
     * blocks, or a fenced JSON object that has a name and arguments.
     */
    private fun parseTextToolCalls(raw: String?): List<ToolCall> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<ToolCall>()
        var index = 0

        fun add(json: String) {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
            val name = obj.optString("name").ifBlank { obj.optString("tool") }
            if (name.isBlank()) return
            val args = obj.opt("arguments") ?: obj.opt("parameters")
            val argsJson = when (args) {
                is JSONObject -> args.toString()
                is String -> args
                else -> "{}"
            }
            out.add(ToolCall("text_call_${index++}", name, argsJson))
        }

        TOOL_BLOCK.findAll(raw).forEach { add(it.groupValues[1].trim()) }
        if (out.isEmpty()) FENCED_TOOL.findAll(raw).forEach { add(it.groupValues[1].trim()) }
        if (out.isEmpty() && raw.trimStart().startsWith("{") && raw.contains("\"name\"")) {
            add(raw.trim())
        }
        return out
    }

    private companion object {
        private val CLAIM = Regex(
            "\\b((i've|i have) (now )?(set|changed|switched|saved|turned|started|stopped|added|created|deleted|removed|" +
                "updated|renamed|scheduled|enabled|disabled|marked|put|made)|all set|" +
                "(changed|switched|set|saved|turned (on|off)|started|stopped|added|created|deleted|removed|updated|" +
                "renamed|scheduled|enabled|disabled|marked) (to|as|for)\\b|" +
                "(timer|alarm|reminder|stopwatch|colou?r|setting|place|home|task) (is )?(now )?(set|saved|changed|started|running|on|off)\\b|" +
                "(ich habe|habe ich) .{0,40}(geändert|gespeichert|gestellt|eingeschaltet|ausgeschaltet|gestartet|angelegt|gelöscht|hinzugefügt)|" +
                "(ist|sind) (jetzt )?(geändert|gespeichert|gestellt|gestartet|an|aus)\\b)"
        )

        private val QUESTION = Regex("^(what|what's|whats|where|when|who|which|how|do you|did i|was|wo|wann|wer|welche|wie)\\b")
        private val STOP_WORDS = setOf(
            "what", "what's", "whats", "where", "when", "who", "which", "how", "the", "my", "your",
            "you", "did", "does", "was", "were", "are", "is", "and", "for", "with", "that", "this",
            "tell", "remember", "again", "about", "mein", "meine", "meinen", "der", "die", "das",
            "was", "wie", "wo", "ist", "sind", "von"
        )
        /**
         * Six rounds, up from four: "text Anna I'm late" is a contact lookup, a
         * send and an answer on its own, and a turn that also checks the time or
         * the calendar ran out before it spoke. The circle check above is what
         * keeps the extra rounds from being spent on repeats.
         */
        const val MAX_ROUNDS = 6

        /**
         * Roughly six thousand tokens of past conversation. Generous enough that
         * Jarvis still follows a thread, small enough that a long evening of
         * chatting does not end in a tokens-per-minute refusal.
         */
        const val MAX_HISTORY_CHARS = 24_000

        const val MAX_TOOL_RESULT_CHARS = 4_000
        val TOOL_BLOCK = Regex("<tool>\\s*(\\{.*?\\})\\s*</tool>", RegexOption.DOT_MATCHES_ALL)
        val THINK_BLOCK = Regex("<(think|thinking|reasoning)>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        const val THINK_OPEN = "<think>"
        val FENCED_TOOL = Regex(
            "```(?:json|tool)?\\s*(\\{[^`]*?\"(?:name|tool)\"[^`]*?\\})\\s*```",
            RegexOption.DOT_MATCHES_ALL
        )
    }
}
