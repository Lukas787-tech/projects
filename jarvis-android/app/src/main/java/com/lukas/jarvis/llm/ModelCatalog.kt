package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Asks the provider which models it actually has, rather than trusting a list
 * baked into the app.
 *
 * Hardcoded model IDs rot fast — Groq retired the Llama 3.x ids in mid-2026 and
 * Google renames Gemini versions regularly — and a stale id fails as an opaque
 * 404. Everything here exists so the picker shows what the account can really
 * call today.
 */
class ModelCatalog {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(settings: Settings): List<String> = withContext(Dispatchers.IO) {
        if (Providers.byId(settings.providerId).needsKey && settings.apiKey.isBlank()) {
            throw LlmException("Add an API key first, then refresh.", FailureKind.Unknown)
        }
        // Gemini's OpenAI compatibility layer answers 404 for /models, so its
        // native endpoint is the only way to enumerate them.
        if (settings.providerId == Providers.GEMINI || isGeminiHost(settings.baseUrl)) {
            fetchGemini(settings)
        } else {
            fetchOpenAiCompatible(settings)
        }
    }

    private fun fetchOpenAiCompatible(settings: Settings): List<String> {
        val url = settings.baseUrl.trimEnd('/') + "/models"
        val builder = Request.Builder().url(url).get()
        if (settings.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.apiKey.trim()}")
        }
        val body = execute(builder.build(), url)

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw LlmException("$url did not return JSON.", FailureKind.Unknown)
        val data = json.optJSONArray("data")
            ?: throw LlmException(
                "$url has no 'data' array — this endpoint may not be OpenAI compatible.",
                FailureKind.Unknown
            )

        val ids = (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
        }
        if (ids.isEmpty()) throw LlmException("Provider listed no models.", FailureKind.Unknown)
        return sortForDisplay(ids)
    }

    private fun fetchGemini(settings: Settings): List<String> {
        val url = "$GEMINI_NATIVE/models?pageSize=200&key=${settings.apiKey.trim()}"
        // The key is in the query string here because that is what the native
        // endpoint accepts; it is never logged.
        val body = execute(Request.Builder().url(url).get().build(), "$GEMINI_NATIVE/models")

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw LlmException("Google did not return JSON.", FailureKind.Unknown)
        val models = json.optJSONArray("models")
            ?: throw LlmException("Google listed no models.", FailureKind.Unknown)

        val ids = (0 until models.length()).mapNotNull { i ->
            val model = models.optJSONObject(i) ?: return@mapNotNull null
            val methods = model.optJSONArray("supportedGenerationMethods")
            val supportsChat = methods != null && (0 until methods.length()).any {
                methods.optString(it) == "generateContent"
            }
            if (!supportsChat) return@mapNotNull null
            // Names come back as "models/gemini-x"; the OpenAI shim wants the bare id.
            model.optString("name").removePrefix("models/").takeIf { it.isNotBlank() }
        }
        if (ids.isEmpty()) throw LlmException("No chat-capable Gemini models.", FailureKind.Unknown)
        return sortForDisplay(ids)
    }

    private fun execute(request: Request, displayUrl: String): String {
        val (code, body) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw LlmException(
                "Could not reach $displayUrl. ${e.message ?: "Check your connection."}",
                FailureKind.Network
            )
        }
        if (code !in 200..299) {
            val detail = runCatching {
                JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
            }.getOrNull().orEmpty().ifBlank { body.take(200) }
            throw LlmException(
                when (code) {
                    401, 403 -> "Key rejected ($code). $detail"
                    404 -> "$displayUrl not found ($code). Check the base URL."
                    429 -> "Rate limited ($code). Try again shortly."
                    else -> "Could not list models ($code). $detail"
                },
                when (code) {
                    401, 403 -> FailureKind.AuthFailed
                    429 -> FailureKind.RateLimited
                    404 -> FailureKind.ModelMissing
                    else -> FailureKind.Unknown
                }
            )
        }
        return body
    }

    /** Free and chat-shaped models first; embedding and vision-only ones last. */
    private fun sortForDisplay(ids: List<String>): List<String> =
        ids.distinct().sortedWith(
            compareBy(
                { if (it.contains(":free")) 0 else 1 },
                { if (UNINTERESTING.any { word -> it.contains(word, true) }) 1 else 0 },
                { it }
            )
        )

    private fun isGeminiHost(baseUrl: String) = baseUrl.contains("generativelanguage.googleapis.com")

    private companion object {
        const val GEMINI_NATIVE = "https://generativelanguage.googleapis.com/v1beta"
        val UNINTERESTING = listOf(
            "embed", "aqa", "imagen", "veo", "tts", "whisper", "guard",
            "vision", "rerank", "moderation", "image"
        )
    }
}
