package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.ChatMessage
import org.json.JSONObject

data class AgentResult(
    val reply: String,
    val effects: ToolEffects,
    val toolsUsed: List<String>
)

/**
 * Runs one user turn to completion: build context, call the model, run whatever
 * tools it asks for, feed the results back, repeat until it answers in words.
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
        onStage: (String) -> Unit = {}
    ): AgentResult {
        val effects = ToolEffects()
        val used = mutableListOf<String>()
        val schemas = tools.schemas(settings)

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
                if (!text.isNullOrBlank()) return AgentResult(text, effects, used)
                lastText = text
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

            val results = StringBuilder()
            for (call in calls) {
                onStage(stageFor(call.name))
                used += call.name
                val result = clamp(tools.execute(call, settings, effects))
                if (nativeCalls.isNotEmpty()) {
                    messages += LlmMessage.toolResult(call.id, call.name, result)
                } else {
                    results.appendLine("${call.name} -> $result")
                }
            }
            if (nativeCalls.isEmpty()) {
                messages += LlmMessage.user(
                    "Tool results:\n${results.toString().trim()}\n\n" +
                        "Now answer the original question in plain speech."
                )
            }
            lastText = clean(reply.content)
        }

        // Out of rounds: ask once more with tools withheld so it has to speak.
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
            toolsUsed = used
        )
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

    private fun stageFor(toolName: String): String = when (toolName) {
        "web_search" -> "searching the web"
        "open_url" -> "reading a page"
        "recall" -> "checking memory"
        "remember" -> "saving that"
        "log_entry" -> "logging it"
        "tracker_status", "list_entries" -> "checking the numbers"
        "configure_tracker" -> "setting that up"
        "add_task", "complete_task", "list_tasks" -> "updating tasks"
        else -> "working"
    }

    private companion object {
        const val MAX_ROUNDS = 4

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
