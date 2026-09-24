package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
 *
 * Almost every provider answers `GET /models` in OpenAI's shape. The two that do
 * not get their own path rather than a special case scattered through the rest:
 * Google's OpenAI shim has no `/models` at all, and GitHub publishes a bare
 * array on a different host.
 */
class ModelCatalog {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(settings: Settings): List<String> = withContext(Dispatchers.IO) {
        val preset = Providers.byId(settings.providerId)
        if (preset.needsKey && settings.apiKey.isBlank()) {
            throw LlmException("Add an API key first, then refresh.", FailureKind.Unknown)
        }
        when {
            preset.catalogStyle == CatalogStyle.Fixed -> preset.fallbackModels
            preset.catalogStyle == CatalogStyle.Gemini || isGeminiHost(settings.baseUrl) ->
                fetchGemini(settings)
            preset.catalogStyle == CatalogStyle.GitHub -> fetchGitHub(settings, preset)
            else -> fetchOpenAiCompatible(settings, preset)
        }
    }

    private fun fetchOpenAiCompatible(settings: Settings, preset: ProviderPreset): List<String> {
        val url = settings.baseUrl.trim().trimEnd('/') + "/models"
        val body = execute(request(url, settings, preset), url)

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw LlmException("$url did not return JSON.", FailureKind.Unknown)
        val data = json.optJSONArray("data")
            ?: json.optJSONArray("models")
            ?: throw LlmException(
                "$url has no 'data' array — this endpoint may not be OpenAI compatible.",
                FailureKind.Unknown
            )

        val ids = idsFrom(data)
        if (ids.isEmpty()) throw LlmException("Provider listed no models.", FailureKind.Unknown)
        return sortForDisplay(ids, preset)
    }

    /** GitHub Models lists its catalogue as a bare array, on its own host. */
    private fun fetchGitHub(settings: Settings, preset: ProviderPreset): List<String> {
        val url = GITHUB_CATALOG
        val body = execute(request(url, settings, preset), url)
        val array = runCatching { JSONArray(body.trim()) }.getOrNull()
            ?: runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
            ?: throw LlmException("GitHub did not return a model list.", FailureKind.Unknown)

        val ids = idsFrom(array)
        if (ids.isEmpty()) throw LlmException("GitHub listed no models.", FailureKind.Unknown)
        return sortForDisplay(ids, preset)
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
        return sortForDisplay(ids, Providers.byId(Providers.GEMINI))
    }

    private fun idsFrom(array: JSONArray): List<String> =
        (0 until array.length()).mapNotNull { i ->
            when (val item = array.opt(i)) {
                is JSONObject -> item.optString("id")
                    .ifBlank { item.optString("name") }
                    .takeIf { it.isNotBlank() }
                is String -> item.takeIf { it.isNotBlank() }
                else -> null
            }
        }

    private fun request(url: String, settings: Settings, preset: ProviderPreset): Request {
        val builder = Request.Builder().url(url).get()
        if (settings.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.apiKey.trim()}")
        }
        preset.extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
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

    /**
     * Free and chat-shaped models first; embedding, image and audio ones last.
     * Where a provider mixes free and paid ids in one list, its free marker
     * (OpenRouter's `:free`, Together's `-Free`) sorts to the top.
     */
    private fun sortForDisplay(ids: List<String>, preset: ProviderPreset): List<String> {
        val freeSuffix = preset.freeSuffix
        return ids.distinct().sortedWith(
            compareBy(
                { if (freeSuffix != null && it.endsWith(freeSuffix)) 0 else 1 },
                { if (UNINTERESTING.any { word -> it.contains(word, true) }) 1 else 0 },
                { it }
            )
        )
    }

    private fun isGeminiHost(baseUrl: String) = baseUrl.contains("generativelanguage.googleapis.com")

    private companion object {
        const val GEMINI_NATIVE = "https://generativelanguage.googleapis.com/v1beta"
        const val GITHUB_CATALOG = "https://models.github.ai/catalog/models"
        val UNINTERESTING = listOf(
            "embed", "aqa", "imagen", "veo", "tts", "whisper", "guard",
            "vision", "rerank", "moderation", "image", "audio", "flux",
            "stable-diffusion", "bge-", "reranker"
        )
    }
}
