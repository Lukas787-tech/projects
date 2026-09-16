package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

data class LlmMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null
) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"

        fun system(text: String) = LlmMessage(SYSTEM, text)
        fun user(text: String) = LlmMessage(USER, text)
        fun assistant(text: String) = LlmMessage(ASSISTANT, text)
        fun toolResult(callId: String, name: String, result: String) =
            LlmMessage(TOOL, result, toolCallId = callId, name = name)
    }
}

data class LlmReply(val content: String?, val toolCalls: List<ToolCall>)

class LlmException(message: String, val recoverable: Boolean = true) : Exception(message)

/** Exactly what went over the wire, so a failure can be reported instead of guessed at. */
data class Diagnostics(
    val url: String,
    val model: String,
    val attempt: String,
    val status: Int,
    val response: String
) {
    fun asText(): String = buildString {
        appendLine("URL: $url")
        appendLine("Model: $model")
        appendLine("Attempt: $attempt")
        appendLine("HTTP: $status")
        appendLine("Response: $response")
    }.trim()
}

/**
 * One OpenAI-chat-completions client for every provider.
 *
 * Providers agree on the broad shape and disagree on the details: some reject
 * `tools`, some reject a non-default `temperature`, and newer ones want
 * `max_completion_tokens` rather than `max_tokens`. Rather than special-casing
 * each one, a rejected request is retried with a progressively plainer payload.
 */
class LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Last request's outcome, success or failure. Surfaced by the settings screen. */
    @Volatile
    var lastDiagnostics: Diagnostics? = null
        private set

    private data class Attempt(
        val label: String,
        val tools: Boolean,
        val sampling: Boolean,
        val tokenParam: String?
    )

    private fun ladder(hasTools: Boolean): List<Attempt> = buildList {
        if (hasTools) add(Attempt("standard", true, true, "max_tokens"))
        add(Attempt("without tools", false, true, "max_tokens"))
        add(Attempt("without sampling options", false, false, "max_completion_tokens"))
        add(Attempt("minimal", false, false, null))
    }

    suspend fun chat(
        settings: Settings,
        messages: List<LlmMessage>,
        tools: List<JSONObject> = emptyList()
    ): LlmReply = withContext(Dispatchers.IO) {
        var last: LlmException? = null
        for (attempt in ladder(tools.isNotEmpty())) {
            try {
                return@withContext request(settings, messages, tools, attempt)
            } catch (e: LlmException) {
                last = e
                // Only a rejected payload is worth reshaping. A bad key, a dead
                // model or a rate limit will fail identically every time.
                if (!e.recoverable) throw e
            }
        }
        throw last ?: LlmException("Request failed.", recoverable = false)
    }

    private fun request(
        settings: Settings,
        messages: List<LlmMessage>,
        tools: List<JSONObject>,
        attempt: Attempt
    ): LlmReply {
        val payload = JSONObject().apply {
            put("model", settings.model)
            put("messages", JSONArray().also { arr -> messages.forEach { arr.put(it.toJson()) } })
            if (attempt.sampling) put("temperature", settings.temperature.toDouble())
            attempt.tokenParam?.let { put(it, settings.maxTokens) }
            if (attempt.tools && tools.isNotEmpty()) {
                put("tools", JSONArray().also { arr -> tools.forEach { arr.put(it) } })
                put("tool_choice", "auto")
            }
        }

        val url = settings.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON))
        if (settings.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.apiKey.trim()}")
        }
        if (settings.providerId == Providers.OPENROUTER) {
            // OpenRouter uses these purely for attribution on its dashboard.
            builder.header("HTTP-Referer", "https://github.com/Lukas787-tech/projects")
            builder.header("X-Title", "Jarvis")
        }

        val (code, body) = try {
            http.newCall(builder.build()).execute().use { response ->
                response.code to response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            lastDiagnostics = Diagnostics(url, settings.model, attempt.label, 0, e.toString())
            throw LlmException(
                "Could not reach $url\n\n${e.message ?: "Check your connection."}",
                recoverable = false
            )
        }

        lastDiagnostics = Diagnostics(url, settings.model, attempt.label, code, body.take(600))

        if (code !in 200..299) {
            val (message, recoverable) = classify(code, body)
            throw LlmException(message, recoverable)
        }
        return parseReply(body)
    }

    /**
     * Status codes alone are not enough: Google answers a bad API key with 400,
     * not 401, so the body has to be read before deciding what went wrong.
     */
    private fun classify(code: Int, body: String): Pair<String, Boolean> {
        val detail = extractMessage(body)
        val lower = detail.lowercase()

        val looksLikeKey = lower.contains("api key") || lower.contains("api_key") ||
            lower.contains("unauthorized") || lower.contains("invalid authentication") ||
            lower.contains("incorrect api key")
        val looksLikeModel = lower.contains("model") &&
            (lower.contains("not found") || lower.contains("does not exist") ||
                lower.contains("decommissioned") || lower.contains("deprecated") ||
                lower.contains("unknown"))

        return when {
            looksLikeKey -> "API key rejected.\n\nCheck it is pasted whole with no spaces, " +
                "and that it belongs to the provider selected above.\n\n$detail" to false

            looksLikeModel -> "That model is not available on this key.\n\nTap Load models " +
                "and pick one from the list — providers retire model names regularly.\n\n$detail" to false

            code == 404 -> "Nothing at that URL (404).\n\nCheck the base URL is right for this " +
                "provider, then tap Load models.\n\n$detail" to false

            code == 401 || code == 403 -> "Key rejected ($code).\n\n$detail" to false

            code == 429 -> "Rate limited ($code). Free tiers throttle — wait a moment, " +
                "or switch provider.\n\n$detail" to false

            code in 500..599 -> "Provider error ($code). Not your setup — try again.\n\n$detail" to false

            // A plain rejected payload: worth retrying in a simpler shape.
            code == 400 || code == 422 -> "Request rejected ($code).\n\n$detail" to true

            else -> "Request failed ($code).\n\n$detail" to false
        }
    }

    /** Providers return errors as an object, or, in Google's case, an array of them. */
    private fun extractMessage(body: String): String {
        val trimmed = body.trim()
        val obj = when {
            trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull()
            trimmed.startsWith("[") -> runCatching {
                JSONArray(trimmed).optJSONObject(0)
            }.getOrNull()
            else -> null
        } ?: return trimmed.take(300).ifBlank { "(empty response)" }

        val error = obj.opt("error")
        val message = when (error) {
            is JSONObject -> error.optString("message").ifBlank { error.toString() }
            is String -> error
            else -> obj.optString("message")
        }
        return message.ifBlank { trimmed.take(300) }
    }

    private fun parseReply(body: String): LlmReply {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw LlmException(
                "Provider returned something unexpected:\n\n${body.take(300)}",
                recoverable = false
            )
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw LlmException("Provider returned no choices:\n\n${body.take(300)}", false)
        }
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw LlmException("Provider returned no message.", false)

        val content = message.opt("content").let { raw ->
            when (raw) {
                is String -> raw
                // Gemini's compatibility layer sometimes returns content parts.
                is JSONArray -> (0 until raw.length()).joinToString("") { i ->
                    raw.optJSONObject(i)?.optString("text").orEmpty()
                }
                else -> null
            }
        }?.takeIf { it.isNotBlank() }

        val calls = ArrayList<ToolCall>()
        message.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val call = arr.optJSONObject(i) ?: continue
                val function = call.optJSONObject("function") ?: continue
                val name = function.optString("name")
                if (name.isBlank()) continue
                calls.add(
                    ToolCall(
                        id = call.optString("id").ifBlank { "call_$i" },
                        name = name,
                        argumentsJson = function.optString("arguments").ifBlank { "{}" }
                    )
                )
            }
        }
        return LlmReply(content, calls)
    }

    private fun LlmMessage.toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content ?: "")
        toolCallId?.let { put("tool_call_id", it) }
        if (role == LlmMessage.TOOL) name?.let { put("name", it) }
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", JSONArray().also { arr ->
                toolCalls.forEach { call ->
                    arr.put(
                        JSONObject().apply {
                            put("id", call.id)
                            put("type", "function")
                            put(
                                "function",
                                JSONObject().apply {
                                    put("name", call.name)
                                    put("arguments", call.argumentsJson)
                                }
                            )
                        }
                    )
                }
            })
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
