package com.lukas.jarvis.core

import android.content.Context
import com.lukas.jarvis.llm.Providers
import com.lukas.jarvis.maps.Geo
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
    val defaultCurrency: String = "EUR",
    val mapsEnabled: Boolean = true,
    /** Weather, from a keyless public endpoint, using the phone's location. */
    val weatherEnabled: Boolean = true,
    /** Alarms, timers, torch, app launching, dialling, drafting a message. */
    val deviceControlEnabled: Boolean = true,
    /** Reading the phone's calendar. Off until the permission is granted. */
    val calendarEnabled: Boolean = false,
    /** Looking names up in the address book. Off until the permission is granted. */
    val contactsEnabled: Boolean = false,
    val travelMode: String = Geo.MODE_WALK,
    val searchRadiusMeters: Int = 1_500,
    /** Voice mode shows the globe and speaks; text mode shows the transcript. */
    val voiceMode: Boolean = true,
    /** The dot that floats over other apps. Off until asked for: it needs a
     *  permission Android only grants from its own settings page. */
    val floatingDot: Boolean = false,
    /** Which tiles the map is drawn with: dark, light or satellite. */
    val mapStyle: String = "dark",

    // ------------------------------------------------------------- who it is

    /** A [com.lukas.jarvis.llm.Persona] id: how Jarvis carries itself. */
    val personality: String = "jarvis",
    /** How to address the user — "sir", "boss", a nickname. Blank uses their name. */
    val honorific: String = "",
    /** short, balanced or detailed. */
    val replyLength: String = "short",
    /** 0 dry and plain … 3 as playful as it gets. */
    val wit: Int = 1,
    /** Free text: anything the user wants Jarvis to always keep in mind about them. */
    val aboutMe: String = "",
    /** Free text: how the user wants to be answered. */
    val customInstructions: String = "",
    /** A language to always answer in. Blank answers in whatever the user used. */
    val replyLanguage: String = "",
    /** Offer a useful next step after an answer, when there obviously is one. */
    val proactive: Boolean = true,
    /** Emoji in typed replies. Never spoken either way. */
    val emoji: Boolean = false,
    /** The user's own one-tap phrases, one per line, shown on the assistant. */
    val quickCommands: String = "",

    // ------------------------------------------------------------- its voice

    /** A TextToSpeech voice name. Blank lets the engine choose. */
    val voiceName: String = "",
    /** A BCP-47 tag for recognition and speech. Blank follows the phone. */
    val speechLanguage: String = "",
    /** A short tone when the microphone opens and closes. */
    val earcons: Boolean = true,
    /** A tick under the finger on the main controls. */
    val haptics: Boolean = true,
    /** On the first open of a morning, say how the day looks without being asked. */
    val morningBrief: Boolean = true,
    /** "07:30" for a written brief as a notification every morning, or blank for none. */
    val briefTime: String = "",
    /** "21:00" for an evening wrap-up notification, or blank for none. */
    val eveningTime: String = "",

    // ------------------------------------------------------------ its looks

    /** A [com.lukas.jarvis.ui.theme.AccentTone] id. */
    val accent: String = "arc",
    /** A [com.lukas.jarvis.ui.theme.Backdrop] id. */
    val backdrop: String = "space",
    val textScale: Float = 1f,
    val reduceMotion: Boolean = false,
    /** What sits at the centre of the assistant: reactor, globe or orb. */
    val coreStyle: String = "reactor",
    /** The clock, weather and status readouts around the assistant. */
    val showHud: Boolean = true,

    /** False until the first-run introduction has been completed or skipped. */
    val onboarded: Boolean = false,

    // ----------------------------------------------------------- the house

    /** Home Assistant's address, e.g. http://homeassistant.local:8123. */
    val homeUrl: String = "",
    /** A Home Assistant long-lived access token. */
    val homeToken: String = "",
    val homeEnabled: Boolean = true
) {
    val homeReady: Boolean
        get() = homeEnabled && homeUrl.isNotBlank() && homeToken.isNotBlank()

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
            defaultCurrency = prefs.getString(KEY_CURRENCY, "EUR") ?: "EUR",
            mapsEnabled = prefs.getBoolean(KEY_MAPS, true),
            weatherEnabled = prefs.getBoolean(KEY_WEATHER, true),
            deviceControlEnabled = prefs.getBoolean(KEY_DEVICE_CONTROL, true),
            calendarEnabled = prefs.getBoolean(KEY_CALENDAR, false),
            contactsEnabled = prefs.getBoolean(KEY_CONTACTS, false),
            travelMode = prefs.getString(KEY_TRAVEL_MODE, Geo.MODE_WALK) ?: Geo.MODE_WALK,
            searchRadiusMeters = prefs.getInt(KEY_SEARCH_RADIUS, 1_500),
            voiceMode = prefs.getBoolean(KEY_VOICE_MODE, true),
            floatingDot = prefs.getBoolean(KEY_FLOATING_DOT, false),
            mapStyle = prefs.getString(KEY_MAP_STYLE, "dark") ?: "dark",
            personality = prefs.getString(KEY_PERSONALITY, "jarvis") ?: "jarvis",
            honorific = prefs.getString(KEY_HONORIFIC, "").orEmpty(),
            replyLength = prefs.getString(KEY_REPLY_LENGTH, "short") ?: "short",
            wit = prefs.getInt(KEY_WIT, 1),
            aboutMe = prefs.getString(KEY_ABOUT_ME, "").orEmpty(),
            customInstructions = prefs.getString(KEY_INSTRUCTIONS, "").orEmpty(),
            replyLanguage = prefs.getString(KEY_REPLY_LANGUAGE, "").orEmpty(),
            proactive = prefs.getBoolean(KEY_PROACTIVE, true),
            emoji = prefs.getBoolean(KEY_EMOJI, false),
            quickCommands = prefs.getString(KEY_QUICK_COMMANDS, "").orEmpty(),
            voiceName = prefs.getString(KEY_VOICE_NAME, "").orEmpty(),
            speechLanguage = prefs.getString(KEY_SPEECH_LANGUAGE, "").orEmpty(),
            earcons = prefs.getBoolean(KEY_EARCONS, true),
            haptics = prefs.getBoolean(KEY_HAPTICS, true),
            morningBrief = prefs.getBoolean(KEY_MORNING_BRIEF, true),
            briefTime = prefs.getString(KEY_BRIEF_TIME, "") ?: "",
            eveningTime = prefs.getString(KEY_EVENING_TIME, "") ?: "",
            accent = prefs.getString(KEY_ACCENT, "arc") ?: "arc",
            backdrop = prefs.getString(KEY_BACKDROP, "space") ?: "space",
            textScale = prefs.getFloat(KEY_TEXT_SCALE, 1f),
            reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
            coreStyle = prefs.getString(KEY_CORE_STYLE, "reactor") ?: "reactor",
            showHud = prefs.getBoolean(KEY_SHOW_HUD, true),
            onboarded = prefs.getBoolean(KEY_ONBOARDED, false),
            homeUrl = prefs.getString(KEY_HOME_URL, "").orEmpty(),
            homeToken = prefs.getString(KEY_HOME_TOKEN, "").orEmpty(),
            homeEnabled = prefs.getBoolean(KEY_HOME_ENABLED, true)
        )
    }

    /**
     * Re-reads the store, for when something changed it from outside — which
     * today means a restored backup.
     */
    fun reload() {
        _state.value = read()
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
            .putBoolean(KEY_MAPS, next.mapsEnabled)
            .putBoolean(KEY_WEATHER, next.weatherEnabled)
            .putBoolean(KEY_DEVICE_CONTROL, next.deviceControlEnabled)
            .putBoolean(KEY_CALENDAR, next.calendarEnabled)
            .putBoolean(KEY_CONTACTS, next.contactsEnabled)
            .putString(KEY_TRAVEL_MODE, next.travelMode)
            .putInt(KEY_SEARCH_RADIUS, next.searchRadiusMeters)
            .putBoolean(KEY_VOICE_MODE, next.voiceMode)
            .putBoolean(KEY_FLOATING_DOT, next.floatingDot)
            .putString(KEY_MAP_STYLE, next.mapStyle)
            .putString(KEY_PERSONALITY, next.personality)
            .putString(KEY_HONORIFIC, next.honorific)
            .putString(KEY_REPLY_LENGTH, next.replyLength)
            .putInt(KEY_WIT, next.wit)
            .putString(KEY_ABOUT_ME, next.aboutMe)
            .putString(KEY_INSTRUCTIONS, next.customInstructions)
            .putString(KEY_REPLY_LANGUAGE, next.replyLanguage)
            .putBoolean(KEY_PROACTIVE, next.proactive)
            .putBoolean(KEY_EMOJI, next.emoji)
            .putString(KEY_QUICK_COMMANDS, next.quickCommands)
            .putString(KEY_VOICE_NAME, next.voiceName)
            .putString(KEY_SPEECH_LANGUAGE, next.speechLanguage)
            .putBoolean(KEY_EARCONS, next.earcons)
            .putBoolean(KEY_HAPTICS, next.haptics)
            .putBoolean(KEY_MORNING_BRIEF, next.morningBrief)
            .putString(KEY_BRIEF_TIME, next.briefTime)
            .putString(KEY_EVENING_TIME, next.eveningTime)
            .putString(KEY_ACCENT, next.accent)
            .putString(KEY_BACKDROP, next.backdrop)
            .putFloat(KEY_TEXT_SCALE, next.textScale)
            .putBoolean(KEY_REDUCE_MOTION, next.reduceMotion)
            .putString(KEY_CORE_STYLE, next.coreStyle)
            .putBoolean(KEY_SHOW_HUD, next.showHud)
            .putBoolean(KEY_ONBOARDED, next.onboarded)
            .putString(KEY_HOME_URL, next.homeUrl)
            .putString(KEY_HOME_TOKEN, next.homeToken)
            .putBoolean(KEY_HOME_ENABLED, next.homeEnabled)
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

    /**
     * Where the current conversation began. Messages from before it are kept
     * and searchable, but are not shown in the thread or sent as context.
     */
    fun conversationStart(): Long = runCatching { prefs.getLong(KEY_CONVERSATION_START, 0L) }.getOrDefault(0L)

    fun startConversation(at: Long) {
        prefs.edit().putLong(KEY_CONVERSATION_START, at).apply()
    }

    /**
     * Claims today's morning brief: true the first time it is asked on a given
     * day, false after that, so the brief is said once however often the app
     * is opened.
     */
    fun claimMorning(day: String): Boolean {
        if (prefs.getString(KEY_GREETED_DAY, null) == day) return false
        prefs.edit().putString(KEY_GREETED_DAY, day).apply()
        return true
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
        const val KEY_MAPS = "maps_enabled"
        const val KEY_WEATHER = "weather_enabled"
        const val KEY_DEVICE_CONTROL = "device_control"
        const val KEY_CALENDAR = "calendar_enabled"
        const val KEY_CONTACTS = "contacts_enabled"
        const val KEY_TRAVEL_MODE = "travel_mode"
        const val KEY_VOICE_MODE = "voice_mode"
        const val KEY_FLOATING_DOT = "floating_dot"
        const val KEY_SEARCH_RADIUS = "search_radius"
        const val KEY_MAP_STYLE = "map_style"
        const val KEY_PERSONALITY = "personality"
        const val KEY_HONORIFIC = "honorific"
        const val KEY_REPLY_LENGTH = "reply_length"
        const val KEY_WIT = "wit"
        const val KEY_ABOUT_ME = "about_me"
        const val KEY_INSTRUCTIONS = "custom_instructions"
        const val KEY_REPLY_LANGUAGE = "reply_language"
        const val KEY_PROACTIVE = "proactive"
        const val KEY_EMOJI = "emoji"
        const val KEY_QUICK_COMMANDS = "quick_commands"
        const val KEY_VOICE_NAME = "voice_name"
        const val KEY_SPEECH_LANGUAGE = "speech_language"
        const val KEY_EARCONS = "earcons"
        const val KEY_HAPTICS = "haptics"
        const val KEY_MORNING_BRIEF = "morning_brief"
        const val KEY_BRIEF_TIME = "brief_time"
        const val KEY_EVENING_TIME = "evening_time"
        const val KEY_GREETED_DAY = "greeted_day"
        const val KEY_CONVERSATION_START = "conversation_start"
        const val KEY_ACCENT = "accent"
        const val KEY_BACKDROP = "backdrop"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_CORE_STYLE = "core_style"
        const val KEY_SHOW_HUD = "show_hud"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_HOME_URL = "home_url"
        const val KEY_HOME_TOKEN = "home_token"
        const val KEY_HOME_ENABLED = "home_enabled"
    }
}
