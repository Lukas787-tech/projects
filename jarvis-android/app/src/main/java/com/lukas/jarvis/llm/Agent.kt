package com.lukas.jarvis.llm

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

    suspend fun respond(
        utterance: String,
        settings: Settings,
        history: List<ChatMessage>,
        onStage: (String) -> Unit = {},
        /** Called with each tool's real name as it is about to run. */
        onTool: (String) -> Unit = {}
    ): AgentResult {
        val effects = ToolEffects()
        val used = mutableListOf<String>()
        val schemas = tools.schemas(settings)
        val available = schemas
            .mapNotNull { it.optJSONObject("function")?.optString("name") }
            .filter { it.isNotBlank() }
            .toSet()

        // What this turn has already asked for, and what came back. Keyed on the
        // tool and its arguments, so the same question asked twice is answered
        // once.
        val answered = ConcurrentHashMap<String, String>()

        val messages = mutableListOf<LlmMessage>()
        messages += LlmMessage.system(Prompt.system(settings))
        trimToBudget(history).forEach { past ->
            messages += LlmMessage(
                role = if (past.role == ChatMessage.ROLE_USER) LlmMessage.USER else LlmMessage.ASSISTANT,
                content = past.content
            )
        }
        messages += LlmMessage.system(Prompt.context(brain, utterance, settings))
        messages += LlmMessage.user(utterance)

        var lastText: String? = null

        for (round in 1..MAX_ROUNDS) {
            onStage(if (round == 1) "thinking" else "working")
            val reply = client.chat(settings, messages, schemas) { next ->
                // Surfaced so a quota switch is visible rather than mysterious.
                onStage("switching to $next")
            }

            val nativeCalls = reply.toolCalls
            val textCalls = if (nativeCalls.isEmpty()) parseTextToolCalls(reply.content) else emptyList()
            val calls = nativeCalls.ifEmpty { textCalls }

            if (calls.isEmpty()) {
                val text = clean(reply.content)
                if (!text.isNullOrBlank()) return AgentResult(text, effects, used.distinct())
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
        val forced = runCatching {
            client.chat(
                settings,
                messages + LlmMessage.user("Answer now in plain speech, without using tools."),
                emptyList()
            )
        }.getOrNull()

        val text = clean(forced?.content) ?: lastText
        return AgentResult(
            reply = text?.takeIf { it.isNotBlank() } ?: "I got the information but lost the thread. Ask me again?",
            effects = effects,
            toolsUsed = used.distinct()
        )
    }

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

    /** Strips the text-protocol blocks so they never reach the screen or the speaker. */
    private fun clean(raw: String?): String? {
        if (raw == null) return null
        return raw
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
        val FENCED_TOOL = Regex(
            "```(?:json|tool)?\\s*(\\{[^`]*?\"(?:name|tool)\"[^`]*?\\})\\s*```",
            RegexOption.DOT_MATCHES_ALL
        )
    }
}
