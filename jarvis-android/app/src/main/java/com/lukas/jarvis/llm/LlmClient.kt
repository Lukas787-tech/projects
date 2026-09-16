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

/**
 * A single OpenAI-chat-completions client that every provider goes through.
 * No streaming: with Groq/Cerebras latency the whole reply lands in about a
 * second, and non-streaming keeps tool-call parsing identical across the
 * providers, whose streaming deltas differ in annoying ways.
 */
class LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun chat(
        settings: Settings,
        messages: List<LlmMessage>,
        tools: List<JSONObject> = emptyList()
    ): LlmReply = withContext(Dispatchers.IO) {
        try {
            request(settings, messages, tools)
        } catch (e: LlmException) {
            // Some free models advertise an OpenAI-compatible surface but reject
            // the `tools` field outright. Drop back to the plain text protocol,
            // which the system prompt also documents, rather than dying here.
            if (tools.isNotEmpty() && e.recoverable) {
                request(settings, messages, emptyList())
            } else {
                throw e
            }
        }
    }

    private fun request(
        settings: Settings,
        messages: List<LlmMessage>,
        tools: List<JSONObject>
    ): LlmReply {
        val payload = JSONObject().apply {
            put("model", settings.model)
            put("messages", JSONArray().also { arr -> messages.forEach { arr.put(it.toJson()) } })
            put("temperature", settings.temperature.toDouble())
            put("max_tokens", settings.maxTokens)
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().also { arr -> tools.forEach { arr.put(it) } })
                put("tool_choice", "auto")
            }
        }

        val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
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
            throw LlmException(
                "Could not reach ${settings.baseUrl}. ${e.message ?: "Check your connection."}",
                recoverable = false
            )
        }

        if (code !in 200..299) {
            throw LlmException(describeError(code, body), recoverable = code == 400 || code == 422)
        }
        return parseReply(body)
    }

    private fun describeError(code: Int, body: String): String {
        val detail = runCatching {
            val json = JSONObject(body)
            val error = json.opt("error")
            when (error) {
                is JSONObject -> error.optString("message").ifBlank { error.toString() }
                is String -> error
                else -> json.optString("message")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(300)

        return when (code) {
            401, 403 -> "API key rejected ($code). Check the key in Settings. $detail"
            // The overwhelmingly common cause is a model id the provider has
            // since retired, so point at the fix rather than just the symptom.
            404 -> "Model not found ($code). It was probably retired — open Settings " +
                "and tap Refresh to load the models this key can actually call. $detail"
            429 -> "Rate limited ($code) — free tiers throttle. Wait a moment or switch provider."
            in 500..599 -> "Provider error ($code). $detail"
            400, 422 -> "Provider rejected the request ($code). If this model is new to " +
                "you it may not support tool calling. $detail"
            else -> "Request failed ($code). $detail"
        }
    }

    private fun parseReply(body: String): LlmReply {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw LlmException("Provider returned something that is not JSON.", false)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw LlmException("Provider returned no choices. ${body.take(200)}", false)
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
