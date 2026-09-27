package com.lukas.jarvis.voice

import com.lukas.jarvis.core.Settings
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** What the cloud voice needs: a key, and optionally a voice and a model. */
data class FishConfig(
    val key: String,
    /** A fish.audio voice id ("reference_id"). Blank lets Fish choose. */
    val voiceId: String = "",
    val model: String = FishVoice.DEFAULT_MODEL,
    /** 0.5..2.0, from the speed slider. */
    val speed: Float = 1f
) {
    companion object {
        /** The cloud voice to use, or null when the phone's own voice is wanted. */
        fun from(settings: Settings): FishConfig? =
            if (settings.voiceEngine == FishVoice.ENGINE && settings.fishKey.isNotBlank()) {
                FishConfig(
                    key = settings.fishKey.trim(),
                    voiceId = settings.fishVoiceId.trim(),
                    model = settings.fishModel.ifBlank { FishVoice.DEFAULT_MODEL },
                    speed = settings.speechRate
                )
            } else {
                null
            }
    }
}

/** One voice from fish.audio's library, as the picker lists it. */
data class FishVoiceOption(val id: String, val title: String, val languages: List<String>, val uses: Int)

/**
 * Fish Audio's text-to-speech (S2.1): a far more natural voice than the
 * phone's own, in whatever language the reply is in, with the user's own
 * key. Every sentence is fetched as a short MP3 and kept in a small cache,
 * so the lines Jarvis says over and over cost nothing the second time.
 */
class FishVoice(cacheRoot: File, private val base: String = BASE) {

    private val folder = File(cacheRoot, "fish_voice").apply { mkdirs() }

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * A model the account turned out not to be allowed, remembered so every
     * sentence after the first goes straight to one that works.
     */
    @Volatile
    private var refused: String? = null

    /** The MP3 for [text], from the cache or freshly made. Throws on failure. */
    fun synthesize(text: String, config: FishConfig): File {
        val model = if (config.model == refused) FREE_MODEL else config.model
        val file = File(folder, cacheName(text, config, model))
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        try {
            fetch(text, config, model, file)
        } catch (e: FishError) {
            // A paid model on a free account: the free S2.1 tier says the same.
            if (e.code in PLAN_CODES && model != FREE_MODEL) {
                refused = model
                val free = File(folder, cacheName(text, config, FREE_MODEL))
                fetch(text, config, FREE_MODEL, free)
                prune()
                return free
            }
            throw e
        }
        prune()
        return file
    }

    private fun fetch(text: String, config: FishConfig, model: String, into: File) {
        val body = JSONObject()
            .put("text", text)
            .put("format", "mp3")
            .put("mp3_bitrate", 128)
            .put("latency", "balanced")
            .put("normalize", true)
            .put("prosody", JSONObject().put("speed", config.speed.coerceIn(0.5f, 2f).toDouble()).put("volume", 0))
        if (config.voiceId.isNotBlank()) body.put("reference_id", config.voiceId)
        val request = Request.Builder()
            .url("$base/v1/tts")
            .header("Authorization", "Bearer ${config.key}")
            .header("model", model)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val part = File(into.parentFile, into.name + ".part")
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw FishError(response.code, explain(response.code, response.body?.string().orEmpty()))
            }
            val stream = response.body?.byteStream() ?: throw IOException("Fish Audio sent nothing")
            part.outputStream().use { out -> stream.copyTo(out) }
        }
        // An error page with a 200 would be a few bytes of JSON, not sound.
        if (part.length() < 200) {
            part.delete()
            throw IOException("Fish Audio sent no audio")
        }
        if (!part.renameTo(into)) {
            part.delete()
            throw IOException("could not keep the audio")
        }
    }

    /** Voices from the library, most used first; [mine] lists the account's own clones. */
    fun voices(key: String, query: String = "", language: String = "", mine: Boolean = false): List<FishVoiceOption> {
        val url = "$base/model".toHttpUrl().newBuilder()
            .addQueryParameter("page_size", "30")
            .addQueryParameter("page_number", "1")
            .addQueryParameter("sort_by", "task_count")
            .apply {
                if (query.isNotBlank()) addQueryParameter("title", query.trim())
                if (language.isNotBlank()) addQueryParameter("language", language.trim())
                if (mine) addQueryParameter("self", "true")
            }
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${key.trim()}").build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw FishError(response.code, explain(response.code, text))
            return parseVoices(text)
        }
    }

    private fun cacheName(text: String, config: FishConfig, model: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$model|${config.voiceId}|${"%.2f".format(config.speed)}|$text".toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".mp3"
    }

    /** Keeps the newest few dozen sentences; the rest would only fill the phone. */
    private fun prune() {
        val files = folder.listFiles { f -> f.name.endsWith(".mp3") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CACHED).forEach { it.delete() }
    }

    class FishError(val code: Int, message: String) : IOException(message)

    companion object {
        const val ENGINE = "fish"
        const val DEVICE = "device"
        const val DEFAULT_MODEL = "s2.1-pro"
        const val FREE_MODEL = "s2.1-pro-free"
        val MODELS = listOf("s2.1-pro", "s2.1-pro-free", "s2-pro", "s1")
        private const val BASE = "https://api.fish.audio"
        private const val MAX_CACHED = 80
        private val JSON = "application/json".toMediaType()
        /** Answers that mean "not on your plan" rather than "you are not allowed at all". */
        private val PLAN_CODES = setOf(402, 403, 429)

        /** Something a person can act on, rather than a status code. */
        fun explain(code: Int, body: String): String {
            val detail = runCatching { JSONObject(body).optString("message") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            return when (code) {
                401 -> "Fish Audio did not accept the key"
                402 -> "the Fish Audio account has no credit for that model"
                404 -> "Fish Audio does not know that voice"
                429 -> "Fish Audio is rate-limiting — too many sentences at once"
                else -> "Fish Audio answered $code" + (detail?.let { ": $it" } ?: "")
            }
        }

        /** The library listing: `{ total, items: [ { _id, title, languages, task_count } ] }`. */
        fun parseVoices(json: String): List<FishVoiceOption> {
            val items = runCatching { JSONObject(json).optJSONArray("items") }.getOrNull() ?: return emptyList()
            return (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("_id").ifBlank { item.optString("id") }
                if (id.isBlank()) return@mapNotNull null
                if (item.optString("type", "tts") != "tts") return@mapNotNull null
                val languages = item.optJSONArray("languages")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
                }.orEmpty()
                FishVoiceOption(
                    id = id,
                    title = item.optString("title").ifBlank { "Voice ${id.take(6)}" }.trim(),
                    languages = languages,
                    uses = item.optInt("task_count")
                )
            }
        }

        /** A voice id pasted as a whole fish.audio link still works. */
        fun voiceIdOf(input: String): String {
            val trimmed = input.trim()
            return Regex("[0-9a-f]{32}").find(trimmed.lowercase())?.value ?: trimmed
        }
    }
}
