package com.lukas.jarvis.core

import android.content.Context
import com.lukas.jarvis.llm.Providers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val providerId: String = Providers.GROQ,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val userName: String = "",
    val assistantName: String = "Jarvis",
    val temperature: Float = 0.6f,
    val maxTokens: Int = 1024,
    val speakReplies: Boolean = true,
    val speechRate: Float = 1.05f,
    val speechPitch: Float = 1.0f,
    val handsFree: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val wakePhrase: String = "jarvis",
    val webSearchEnabled: Boolean = true,
    val autoCapture: Boolean = true,
    val defaultCurrency: String = "EUR"
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() &&
            (apiKey.isNotBlank() || !Providers.byId(providerId).needsKey)
}

/**
 * SharedPreferences-backed settings. API keys and per-provider endpoints are
 * namespaced by provider id, so switching provider and switching back does not
 * lose the key you already pasted in.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    private fun read(): Settings {
        val providerId = prefs.getString(KEY_PROVIDER, Providers.GROQ) ?: Providers.GROQ
        val preset = Providers.byId(providerId)
        return Settings(
            providerId = providerId,
            baseUrl = prefs.getString(scoped(KEY_BASE_URL, providerId), null) ?: preset.baseUrl,
            apiKey = prefs.getString(scoped(KEY_API_KEY, providerId), null).orEmpty(),
            model = prefs.getString(scoped(KEY_MODEL, providerId), null) ?: preset.defaultModel,
            userName = prefs.getString(KEY_USER_NAME, "").orEmpty(),
            assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis",
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.6f),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, 1024),
            speakReplies = prefs.getBoolean(KEY_SPEAK, true),
            speechRate = prefs.getFloat(KEY_RATE, 1.05f),
            speechPitch = prefs.getFloat(KEY_PITCH, 1.0f),
            handsFree = prefs.getBoolean(KEY_HANDS_FREE, true),
            wakeWordEnabled = prefs.getBoolean(KEY_WAKE_ENABLED, false),
            wakePhrase = prefs.getString(KEY_WAKE_PHRASE, "jarvis") ?: "jarvis",
            webSearchEnabled = prefs.getBoolean(KEY_WEB_SEARCH, true),
            autoCapture = prefs.getBoolean(KEY_AUTO_CAPTURE, true),
            defaultCurrency = prefs.getString(KEY_CURRENCY, "EUR") ?: "EUR"
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        val providerId = next.providerId
        prefs.edit()
            .putString(KEY_PROVIDER, providerId)
            .putString(scoped(KEY_BASE_URL, providerId), next.baseUrl)
            .putString(scoped(KEY_API_KEY, providerId), next.apiKey)
            .putString(scoped(KEY_MODEL, providerId), next.model)
            .putString(KEY_USER_NAME, next.userName)
            .putString(KEY_ASSISTANT_NAME, next.assistantName)
            .putFloat(KEY_TEMPERATURE, next.temperature)
            .putInt(KEY_MAX_TOKENS, next.maxTokens)
            .putBoolean(KEY_SPEAK, next.speakReplies)
            .putFloat(KEY_RATE, next.speechRate)
            .putFloat(KEY_PITCH, next.speechPitch)
            .putBoolean(KEY_HANDS_FREE, next.handsFree)
            .putBoolean(KEY_WAKE_ENABLED, next.wakeWordEnabled)
            .putString(KEY_WAKE_PHRASE, next.wakePhrase)
            .putBoolean(KEY_WEB_SEARCH, next.webSearchEnabled)
            .putBoolean(KEY_AUTO_CAPTURE, next.autoCapture)
            .putString(KEY_CURRENCY, next.defaultCurrency)
            .apply()
        _state.value = next
    }

    /**
     * What is stored for one provider, whether or not it is the selected one.
     *
     * Keys are already namespaced per provider, so this is just reading them
     * back — which is what lets the pool enrol every provider you have ever
     * pasted a key for without walking the picker one entry at a time.
     */
    fun saved(providerId: String): Settings {
        val preset = Providers.byId(providerId)
        return _state.value.copy(
            providerId = providerId,
            baseUrl = prefs.getString(scoped(KEY_BASE_URL, providerId), null) ?: preset.baseUrl,
            apiKey = prefs.getString(scoped(KEY_API_KEY, providerId), null).orEmpty(),
            model = prefs.getString(scoped(KEY_MODEL, providerId), null) ?: preset.defaultModel
        )
    }

    /**
     * Every provider worth asking to enrol: one with a key pasted, or a keyless
     * one whose URL you have actually edited.
     *
     * The "actually edited" part matters. The local presets ship pointing at a
     * guessed LAN address, and asking four imaginary servers for their model
     * lists means four connection timeouts before anything useful happens.
     */
    fun savedProviders(): List<Settings> = Providers.ALL
        .filter { preset ->
            if (preset.needsKey) {
                !prefs.getString(scoped(KEY_API_KEY, preset.id), null).isNullOrBlank()
            } else {
                prefs.contains(scoped(KEY_BASE_URL, preset.id))
            }
        }
        .map { saved(it.id) }

    /** Switching provider pulls that provider's own saved URL/key/model back in. */
    fun switchProvider(providerId: String) {
        val preset = Providers.byId(providerId)
        val next = _state.value.copy(
            providerId = providerId,
            baseUrl = prefs.getString(scoped(KEY_BASE_URL, providerId), null) ?: preset.baseUrl,
            apiKey = prefs.getString(scoped(KEY_API_KEY, providerId), null).orEmpty(),
            model = prefs.getString(scoped(KEY_MODEL, providerId), null) ?: preset.defaultModel
        )
        update { next }
    }

    private fun scoped(key: String, providerId: String) = "$key.$providerId"

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_USER_NAME = "user_name"
        const val KEY_ASSISTANT_NAME = "assistant_name"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_SPEAK = "speak_replies"
        const val KEY_RATE = "speech_rate"
        const val KEY_PITCH = "speech_pitch"
        const val KEY_HANDS_FREE = "hands_free"
        const val KEY_WAKE_ENABLED = "wake_enabled"
        const val KEY_WAKE_PHRASE = "wake_phrase"
        const val KEY_WEB_SEARCH = "web_search"
        const val KEY_AUTO_CAPTURE = "auto_capture"
        const val KEY_CURRENCY = "currency"
    }
}
