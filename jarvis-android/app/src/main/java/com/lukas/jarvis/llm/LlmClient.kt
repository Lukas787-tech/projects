package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    val name: String? = null,
    /**
     * Pictures attached to a user message, as `data:` URLs. Only the photo
     * turn carries one; it is never replayed from history, because every
     * later request would pay for the same megabyte again.
     */
    val images: List<String> = emptyList()
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

/**
 * The request shape an endpoint turned out to accept.
 *
 * Providers disagree about the details of a chat completion: some reject a
 * `tools` array, some reject a non-default `temperature`, and newer ones want
 * `max_completion_tokens` instead of `max_tokens`. Discovering that costs a
 * rejected request — so once an endpoint has answered, the shape that worked is
 * remembered and every later call starts there. On a free tier that saved
 * request is the whole point: it is one less call against the quota, every turn.
 */
data class Capability(
    val tools: Boolean = true,
    val sampling: Boolean = true,
    val tokenParam: String? = MAX_TOKENS
) {
    val label: String
        get() = buildString {
            append(if (tools) "tools" else "no tools")
            if (!sampling) append(", fixed sampling")
            if (tokenParam == null) append(", no token cap")
            else if (tokenParam != MAX_TOKENS) append(", $tokenParam")
        }

    companion object {
        const val MAX_TOKENS = "max_tokens"
        const val MAX_COMPLETION_TOKENS = "max_completion_tokens"
    }
}

/**
 * Where a reply's words go while they are still arriving. [restart] throws away
 * what was shown so far — a different endpoint is about to answer from the
 * beginning, or the round turned out to be a tool call rather than an answer.
 */
interface ReplyStream {
    fun restart()
    fun append(text: String)
}

data class LlmReply(
    val content: String?,
    val toolCalls: List<ToolCall>,
    /** The payload shape that succeeded, worth remembering for next time. */
    val capability: Capability = Capability(),
    /** Whatever the provider said about what is left of the quota. */
    val rate: RateSignal = RateSignal(),
    val latencyMs: Long = 0L
)

/** Why a call failed. The pool routes on this rather than on string matching. */
enum class FailureKind {
    /** Out of quota for now. Another endpoint should be tried, this one rested. */
    RateLimited,

    /** The key is wrong. Retrying costs time and will never succeed. */
    AuthFailed,

    /** The model id is gone. The endpoint needs repointing, not resting. */
    ModelMissing,

    /** The request shape was rejected; a plainer payload may work. */
    PayloadRejected,

    /** The account is out of money, which no amount of waiting fixes. */
    OutOfCredit,

    ServerError,
    Network,
    Unknown
}

/**
 * How wide a rate limit reaches. Rotating to another model of the same account
 * escapes a per-model limit and does nothing at all for an account-wide one, so
 * the pool needs to know which it just hit.
 */
enum class LimitScope { Model, Account }

class LlmException(
    message: String,
    val kind: FailureKind = FailureKind.Unknown,
    val retryAfterSeconds: Long? = null,
    val scope: LimitScope = LimitScope.Model,
    /** True when the limit resets at UTC midnight rather than in a few seconds. */
    val daily: Boolean = false
) : Exception(message) {
    /** Only a payload problem is worth re-sending to the same endpoint. */
    val recoverable: Boolean get() = kind == FailureKind.PayloadRejected
}

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
 * Providers agree on the broad shape and disagree on the details, so a rejected
 * request is retried with a progressively plainer payload. Tool calling is given
 * up last rather than first: losing `temperature` costs nothing, while losing
 * tools costs Jarvis its memory and trackers, and plenty of providers reject one
 * without minding the other.
 */
class LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // A whole call has to finish inside this, otherwise a provider that
        // accepts the connection and then stalls would hold the turn forever
        // while healthy endpoints sit unused.
        .callTimeout(110, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Last request's outcome, success or failure. Surfaced by the settings screen. */
    @Volatile
    var lastDiagnostics: Diagnostics? = null
        private set

    /**
     * Payload shapes from richest to plainest.
     *
     * [from] is what this endpoint accepted last time: everything above it is
     * known to fail here, so it is skipped entirely rather than re-tried.
     */
    private fun ladder(hasTools: Boolean, from: Capability?): List<Capability> {
        val full = listOf(
            Capability(tools = true, sampling = true, tokenParam = Capability.MAX_TOKENS),
            Capability(tools = true, sampling = true, tokenParam = Capability.MAX_COMPLETION_TOKENS),
            Capability(tools = true, sampling = false, tokenParam = Capability.MAX_COMPLETION_TOKENS),
            Capability(tools = false, sampling = true, tokenParam = Capability.MAX_TOKENS),
            Capability(tools = false, sampling = false, tokenParam = Capability.MAX_COMPLETION_TOKENS),
            Capability(tools = false, sampling = false, tokenParam = null)
        ).filter { hasTools || !it.tools }

        if (from == null) return full
        val start = full.indexOfFirst { it == from }
        return if (start < 0) {
            // A remembered shape that is not on the ladder (an older build, say):
            // try it first, then fall back through the rest.
            listOf(from) + full
        } else {
            full.drop(start)
        }
    }

    /**
     * Endpoints that turned a streamed request away. They are asked the plain
     * way from then on, which costs nothing but the words arriving all at once.
     */
    private val noStream: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    suspend fun chat(
        settings: Settings,
        messages: List<LlmMessage>,
        tools: List<JSONObject> = emptyList(),
        known: Capability? = null,
        stream: ReplyStream? = null
    ): LlmReply = withContext(Dispatchers.IO) {
        var last: LlmException? = null
        for (attempt in ladder(tools.isNotEmpty(), known)) {
            try {
                return@withContext request(settings, messages, tools, attempt, stream)
            } catch (e: LlmException) {
                last = e
                // Only a rejected payload is worth reshaping. A bad key, a dead
                // model or a rate limit will fail identically every time.
                if (!e.recoverable) throw e
            }
        }
        throw last ?: LlmException("Request failed.")
    }

    private suspend fun request(
        settings: Settings,
        messages: List<LlmMessage>,
        tools: List<JSONObject>,
        capability: Capability,
        stream: ReplyStream? = null
    ): LlmReply {
        val streamKey = settings.baseUrl.trim() + "|" + settings.model
        if (stream != null && streamKey !in noStream) {
            try {
                return streamed(settings, messages, tools, capability, stream)
            } catch (e: LlmException) {
                // A server that does not stream says so with a rejected payload;
                // anything else is a real failure and is passed on as one.
                if (e.kind != FailureKind.PayloadRejected) throw e
                noStream += streamKey
                stream.restart()
            }
        }
        val payload = payload(settings, messages, tools, capability, streaming = false)
        val preset = Providers.byId(settings.providerId)
        val url = settings.baseUrl.trim().trimEnd('/') + preset.chatPath

        val startedAt = System.currentTimeMillis()
        val response = send(post(url, payload, settings, preset), url, settings.model, capability.label)
        val elapsed = System.currentTimeMillis() - startedAt

        lastDiagnostics = Diagnostics(url, settings.model, capability.label, response.code, response.body.take(600))

        if (response.code !in 200..299) {
            throw classify(response.code, response.body, response.rate, preset)
        }
        val parsed = parseReply(response.body)
        stream?.let { sink -> parsed.content?.let(sink::append) }
        return parsed.copy(capability = capability, rate = response.rate, latencyMs = elapsed)
    }

    private fun payload(
        settings: Settings,
        messages: List<LlmMessage>,
        tools: List<JSONObject>,
        capability: Capability,
        streaming: Boolean
    ): JSONObject = JSONObject().apply {
        put("model", settings.model)
        put("messages", JSONArray().also { arr -> messages.forEach { arr.put(it.toJson()) } })
        if (capability.sampling) put("temperature", settings.temperature.toDouble())
        capability.tokenParam?.let { put(it, settings.maxTokens) }
        if (capability.tools && tools.isNotEmpty()) {
            put("tools", JSONArray().also { arr -> tools.forEach { arr.put(it) } })
            put("tool_choice", "auto")
        }
        if (streaming) put("stream", true)
    }

    private fun post(url: String, payload: JSONObject, settings: Settings, preset: ProviderPreset): Request {
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON))
        if (settings.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.apiKey.trim()}")
        }
        preset.extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    /** One tool call as it is pieced together from a stream. */
    private class PartialCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    /**
     * The same request with `stream: true`, read as server-sent events: every
     * piece of the answer is handed to [sink] the moment it arrives, and tool
     * calls, which arrive in fragments, are put back together. A server that
     * ignores the flag and answers in one piece is read the ordinary way.
     */
    private suspend fun streamed(
        settings: Settings,
        messages: List<LlmMessage>,
        tools: List<JSONObject>,
        capability: Capability,
        sink: ReplyStream
    ): LlmReply {
        val preset = Providers.byId(settings.providerId)
        val url = settings.baseUrl.trim().trimEnd('/') + preset.chatPath
        val label = capability.label + ", streamed"
        val request = post(url, payload(settings, messages, tools, capability, streaming = true), settings, preset)
        val startedAt = System.currentTimeMillis()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            lastDiagnostics = Diagnostics(url, settings.model, label, 0, e.toString())
            throw LlmException(
                "Could not reach $url\n\n${e.message ?: "Check your connection."}",
                FailureKind.Network
            )
        }

        response.use {
            val rate = RateSignal.from { name -> response.header(name) }
            if (response.code !in 200..299) {
                val body = response.body?.string().orEmpty()
                lastDiagnostics = Diagnostics(url, settings.model, label, response.code, body.take(600))
                throw classify(response.code, body, rate, preset)
            }
            val source = response.body?.source()
                ?: throw LlmException("Provider sent an empty response.", FailureKind.ServerError)

            if (!response.header("Content-Type").orEmpty().contains("event-stream")) {
                val body = source.readUtf8()
                lastDiagnostics = Diagnostics(url, settings.model, label, response.code, body.take(600))
                val parsed = parseReply(body)
                parsed.content?.let(sink::append)
                return parsed.copy(
                    capability = capability,
                    rate = rate,
                    latencyMs = System.currentTimeMillis() - startedAt
                )
            }

            val content = StringBuilder()
            val calls = sortedMapOf<Int, PartialCall>()
            try {
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val error = json.opt("error")
                    if (error != null && error != JSONObject.NULL) {
                        throw classify(400, data, rate, preset)
                    }
                    val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: continue
                    val piece = delta.opt("content")
                    if (piece is String && piece.isNotEmpty()) {
                        content.append(piece)
                        sink.append(piece)
                    }
                    delta.optJSONArray("tool_calls")?.let { array ->
                        for (i in 0 until array.length()) {
                            val fragment = array.optJSONObject(i) ?: continue
                            val call = calls.getOrPut(fragment.optInt("index", i)) { PartialCall() }
                            fragment.optString("id").takeIf { it.isNotBlank() && it != "null" }?.let { call.id = it }
                            fragment.optJSONObject("function")?.let { function ->
                                function.optString("name").takeIf { it.isNotBlank() && it != "null" }
                                    ?.let { call.name = it }
                                when (val args = function.opt("arguments")) {
                                    is String -> call.arguments.append(args)
                                    is JSONObject -> call.arguments.append(args.toString())
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // The connection dropped part way through. Whatever arrived is
                // not a whole answer; the pool will ask someone else.
                lastDiagnostics = Diagnostics(url, settings.model, label, response.code, e.toString())
                throw LlmException("The answer was cut off: ${e.message}", FailureKind.Network)
            }

            val toolCalls = calls.values.mapIndexedNotNull { index, call ->
                if (call.name.isBlank()) {
                    null
                } else {
                    ToolCall(
                        id = call.id.ifBlank { "call_$index" },
                        name = call.name,
                        argumentsJson = call.arguments.toString().ifBlank { "{}" }
                    )
                }
            }
            lastDiagnostics = Diagnostics(url, settings.model, label, response.code, content.take(600).toString())
            if (content.isBlank() && toolCalls.isEmpty()) {
                throw LlmException("Provider streamed an empty answer.", FailureKind.ServerError)
            }
            val said = ProviderNotices.strip(content.toString())
            if (toolCalls.isEmpty()) ProviderNotices.failure(said)?.let { throw it }
            return LlmReply(
                content = said.takeIf { it.isNotBlank() },
                toolCalls = toolCalls,
                capability = capability,
                rate = rate,
                latencyMs = System.currentTimeMillis() - startedAt
            )
        }
    }

    private data class RawResponse(val code: Int, val body: String, val rate: RateSignal)

    /**
     * One retry on a transport failure, because a dropped connection on a phone
     * usually means the radio switched networks rather than that the provider is
     * unwell — and resting a healthy endpoint over that wastes real quota
     * elsewhere. Anything the server actually answered is returned as-is.
     */
    private suspend fun send(
        request: Request,
        url: String,
        model: String,
        attemptLabel: String
    ): RawResponse {
        var lastError: IOException? = null
        repeat(2) { round ->
            if (round > 0) delay(350L + (0..250).random())
            try {
                return http.newCall(request).execute().use { response ->
                    RawResponse(
                        code = response.code,
                        body = response.body?.string().orEmpty(),
                        rate = RateSignal.from { name -> response.header(name) }
                    )
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        val error = lastError ?: IOException("Unknown transport failure")
        lastDiagnostics = Diagnostics(url, model, attemptLabel, 0, error.toString())
        throw LlmException(
            "Could not reach $url\n\n${error.message ?: "Check your connection."}",
            FailureKind.Network
        )
    }

    /**
     * Status codes alone are not enough: Google answers a bad API key with 400,
     * not 401, and several providers report an exhausted daily allowance as a
     * plain 400. So the body is read before deciding what went wrong, and how
     * widely it applies.
     */
    private fun classify(
        code: Int,
        body: String,
        rate: RateSignal,
        preset: ProviderPreset
    ): LlmException {
        val detail = extractMessage(body)
        val lower = detail.lowercase()

        val looksLikeKey = lower.contains("api key") || lower.contains("api_key") ||
            lower.contains("unauthorized") || lower.contains("invalid authentication") ||
            lower.contains("incorrect api key") || lower.contains("permission denied")
        val looksLikeModel = lower.contains("model") &&
            (lower.contains("not found") || lower.contains("does not exist") ||
                lower.contains("decommissioned") || lower.contains("deprecated") ||
                lower.contains("unknown") || lower.contains("not supported"))
        // Several providers report exhausted quota as 400 or 403 rather than 429.
        val looksLikeQuota = lower.contains("quota") || lower.contains("rate limit") ||
            lower.contains("rate_limit") || lower.contains("too many requests") ||
            lower.contains("resource_exhausted") || lower.contains("exceeded") ||
            lower.contains("throttl")
        val looksLikeMoney = lower.contains("insufficient") &&
            (lower.contains("credit") || lower.contains("balance") || lower.contains("fund")) ||
            lower.contains("payment required") || lower.contains("billing")

        // "per day", "daily limit", "free-models-per-day", "RPD": these only
        // clear at midnight, and they are charged to the key, not the model.
        val daily = lower.contains("per day") || lower.contains("per-day") ||
            lower.contains("daily") || lower.contains("rpd") ||
            lower.contains("requests today") || lower.contains("free-models")
        val accountWide = daily || lower.contains("account") || lower.contains("organization") ||
            lower.contains("workspace")

        val retryAfter = rate.retryAfterSeconds
            ?: rate.resetSeconds?.toLong()

        return when {
            looksLikeMoney || code == 402 -> LlmException(
                "Out of credit on this account.\n\n$detail",
                FailureKind.OutOfCredit,
                scope = LimitScope.Account
            )

            looksLikeQuota || code == 429 -> LlmException(
                (if (daily) "Daily allowance used up." else "Out of quota for now.") + "\n\n$detail",
                FailureKind.RateLimited,
                retryAfterSeconds = retryAfter,
                scope = if (accountWide && preset.accountWideDailyCap) {
                    LimitScope.Account
                } else {
                    LimitScope.Model
                },
                daily = daily
            )

            looksLikeKey -> LlmException(
                "API key rejected.\n\nCheck it is pasted whole with no spaces, and that " +
                    "it belongs to the provider selected above.\n\n$detail",
                FailureKind.AuthFailed,
                scope = LimitScope.Account
            )

            looksLikeModel -> LlmException(
                "That model is not available on this key.\n\nTap Load models and pick one " +
                    "from the list — providers retire model names regularly.\n\n$detail",
                FailureKind.ModelMissing
            )

            code == 404 -> LlmException(
                "Nothing at that URL (404).\n\nCheck the base URL is right for this " +
                    "provider, then tap Load models.\n\n$detail",
                FailureKind.ModelMissing
            )

            code == 401 || code == 403 -> LlmException(
                "Key rejected ($code).\n\n$detail",
                FailureKind.AuthFailed,
                scope = LimitScope.Account
            )

            code in 500..599 || code == 408 -> LlmException(
                "Provider error ($code). Not your setup — try again.\n\n$detail",
                FailureKind.ServerError,
                retryAfterSeconds = retryAfter
            )

            // A plain rejected payload: worth retrying in a simpler shape.
            code == 400 || code == 422 -> LlmException(
                "Request rejected ($code).\n\n$detail",
                FailureKind.PayloadRejected
            )

            else -> LlmException("Request failed ($code).\n\n$detail", FailureKind.Unknown)
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
            is JSONObject -> error.optString("message").ifBlank {
                // Cloudflare and a few others nest the text one level deeper.
                error.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty()
                    .ifBlank { error.toString() }
            }
            is JSONArray -> error.optJSONObject(0)?.optString("message").orEmpty()
            is String -> error
            else -> obj.optString("message").ifBlank { obj.optString("detail") }
        }
        return message.ifBlank { trimmed.take(300) }
    }

    private fun parseReply(body: String): LlmReply {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw LlmException(
                "Provider returned something unexpected:\n\n${body.take(300)}",
                FailureKind.Unknown
            )
        // Some gateways answer 200 with an error object in the body.
        json.opt("error")?.let { error ->
            if (error != JSONObject.NULL) {
                throw LlmException(
                    "Provider returned an error:\n\n${extractMessage(body)}",
                    FailureKind.Unknown
                )
            }
        }
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw LlmException("Provider returned no choices:\n\n${body.take(300)}")
        }
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw LlmException("Provider returned no message.")

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
        val said = content?.let { ProviderNotices.strip(it) }?.takeIf { it.isNotBlank() }
        if (calls.isEmpty() && said != null) ProviderNotices.failure(said)?.let { throw it }
        return LlmReply(said, calls)
    }

    private fun LlmMessage.toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        if (images.isEmpty()) {
            put("content", content ?: "")
        } else {
            // The multi-part shape every vision endpoint of the OpenAI dialect
            // accepts: the words first, then each picture.
            put("content", JSONArray().also { parts ->
                parts.put(JSONObject().apply {
                    put("type", "text")
                    put("text", content ?: "")
                })
                images.forEach { url ->
                    parts.put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply { put("url", url) })
                    })
                }
            })
        }
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

/**
 * What a free endpoint sometimes sends in place of an answer.
 *
 * A keyless service that has run out for the moment does not always say so
 * with a status code: it answers 200 with a notice about credits or a queue
 * as the reply, which would otherwise be shown and read out as if the
 * assistant had said it. Such a reply is a failure of that endpoint, and the
 * pool moves on. Advert footers some add to real answers are cut off instead.
 */
object ProviderNotices {

    private val STRONG = listOf(
        "doesn't have enough credits", "does not have enough credits", "not enough credits",
        "complete a quest", "top up or", "rate limit reached", "rate limit exceeded",
        "too many requests", "queue full", "queue is full", "insufficient_quota",
        "you have exceeded your", "exceeded your current quota", "please sign up",
        "get a free api key", "auth.pollinations", "enter.pollinations"
    )

    private val RATE = listOf("rate limit", "too many requests", "queue")

    /** The endpoint's own notice, as the failure it is; null for a real answer. */
    fun failure(text: String): LlmException? {
        if (text.length > 900) return null
        val lower = text.lowercase()
        val hit = STRONG.firstOrNull { it in lower } ?: return null
        val kind = if (RATE.any { it in hit }) FailureKind.RateLimited else FailureKind.OutOfCredit
        return LlmException("The endpoint answered with a notice instead of a reply: ${text.take(160)}", kind)
    }

    /** A real answer without the advert some free services append to it. */
    fun strip(text: String): String {
        val lower = text.lowercase()
        val footer = Regex("\\n\\s*-{3,}\\s*\\n").findAll(text)
            .map { it.range.first }
            .firstOrNull { start -> "pollinations" in lower.substring(start) }
        val cut = footer?.let { text.substring(0, it) } ?: text
        return cut.lines()
            .filterNot { line ->
                val l = line.lowercase()
                "🌸 ad 🌸" in l || ("powered by" in l && "pollinations" in l)
            }
            .joinToString("\n")
            .trimEnd()
    }
}
