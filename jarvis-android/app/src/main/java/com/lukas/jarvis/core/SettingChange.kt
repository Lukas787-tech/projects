package com.lukas.jarvis.core

import com.lukas.jarvis.llm.Personas
import com.lukas.jarvis.maps.Geo
import java.util.Locale

/**
 * The settings put into words, so the assistant can change them as asked:
 * "make it red", "speak slower", "call me boss", "satellite map", "bigger
 * text". Each change comes back as the new settings and one line saying
 * what changed; anything not understood comes back as a question instead.
 * API keys are not here on purpose: those are typed, never spoken.
 */
object SettingChange {

    sealed class Result {
        data class Changed(val settings: Settings, val said: String) : Result()
        data class Refused(val why: String) : Result()
    }

    /** The names accepted, for the tool's description. */
    val KEYS = listOf(
        "assistant_name", "user_name", "call_me", "personality", "reply_length", "wit", "emoji",
        "accent", "backdrop", "core_style", "reduce_motion", "text_size", "show_hud",
        "speak_replies", "speech_rate", "speech_pitch", "speech_language", "auto_language", "reply_language",
        "map_style", "travel_mode", "search_radius", "currency", "proactive",
        "morning_brief", "brief_time", "evening_time", "haptics", "sounds", "wake_word", "wake_phrase",
        "about_me", "instructions"
    )

    fun apply(settings: Settings, rawKey: String, rawValue: String): Result {
        val key = ALIASES[norm(rawKey)] ?: norm(rawKey)
        val value = rawValue.trim()
        val v = value.lowercase(Locale.ROOT)
        fun changed(s: Settings, said: String) = Result.Changed(s, said)
        fun flag(set: (Boolean) -> Settings, what: String): Result {
            val on = bool(v) ?: return Result.Refused("Should $what be on or off?")
            return changed(set(on), "$what ${if (on) "on" else "off"}.".replaceFirstChar { it.uppercase() })
        }
        return when (key) {
            "assistant_name" -> if (value.isBlank()) ask("What should I be called?")
                else changed(settings.copy(assistantName = value.take(24)), "From now on I'm ${value.take(24)}.")
            "user_name" -> if (value.isBlank()) ask("What's your name?")
                else changed(settings.copy(userName = value.take(40)), "Got it, ${value.take(40)}.")
            "call_me" -> changed(
                settings.copy(honorific = if (v in CLEAR) "" else value.take(30)),
                if (v in CLEAR) "I'll use your name again." else "I'll call you ${value.take(30)}."
            )
            "personality" -> Personas.ALL.firstOrNull { it.id == v || it.label.lowercase(Locale.ROOT) == v }
                ?.let { changed(settings.copy(personality = it.id), "Personality: ${it.label}.") }
                ?: ask("Which personality? ${Personas.ALL.joinToString { it.label }}.")
            "reply_length" -> when {
                v.startsWith("short") || v.startsWith("brief") || v.startsWith("kurz") -> "short"
                v.startsWith("bal") || v.startsWith("medium") || v.startsWith("normal") || v.startsWith("mittel") -> "balanced"
                v.startsWith("detail") || v.startsWith("long") || v.startsWith("lang") || v.startsWith("ausführ") -> "detailed"
                else -> null
            }?.let { changed(settings.copy(replyLength = it), "Answers will be $it.") }
                ?: ask("Short, balanced or detailed answers?")
            "wit" -> (v.toIntOrNull() ?: when {
                v.contains("more") || v.contains("funnier") || v.contains("mehr") -> settings.wit + 1
                v.contains("less") || v.contains("weniger") || v.contains("serious") || v.contains("ernst") -> settings.wit - 1
                v.contains("dry") || v.contains("none") || v.contains("plain") -> 0
                v.contains("max") || v.contains("playful") -> 3
                else -> null
            })?.coerceIn(0, 3)?.let { changed(settings.copy(wit = it), "Wit set to $it of 3.") }
                ?: ask("How witty, from 0 (dry) to 3 (playful)?")
            "emoji" -> flag({ settings.copy(emoji = it) }, "emoji")
            "accent" -> accent(v)?.let { changed(settings.copy(accent = it.first), "Colour: ${it.second}.") }
                ?: ask("Which colour? For example blue, gold, red, green, violet, orange, pink, silver, or any colour name.")
            "backdrop" -> BACKDROPS.entries.firstOrNull { (id, words) -> v == id || words.any { v.contains(it) } }
                ?.let { changed(settings.copy(backdrop = it.key), "Background: ${it.key}.") }
                ?: ask("Which background? ${BACKDROPS.keys.joinToString()}.")
            "core_style" -> when {
                v.contains("orb") || v.contains("kugel") -> "orb"
                v.contains("globe") || v.contains("earth") || v.contains("erde") || v.contains("planet") -> "globe"
                v.contains("reactor") || v.contains("reaktor") || v.contains("arc") -> "reactor"
                else -> null
            }?.let { changed(settings.copy(coreStyle = it), "The core is now the $it.") }
                ?: ask("Reactor, orb or globe?")
            "reduce_motion" -> flag({ settings.copy(reduceMotion = it) }, "calm motion")
            "text_size" -> scaled(v, settings.textScale, 0.1f, 0.8f, 1.6f)
                ?.let { changed(settings.copy(textScale = it), "Text size ${(it * 100).toInt()}%.") }
                ?: ask("Bigger, smaller, or a size like 120%?")
            "show_hud" -> flag({ settings.copy(showHud = it) }, "the clock and weather display")
            "speak_replies" -> flag({ settings.copy(speakReplies = it) }, "speaking answers aloud")
            "speech_rate" -> scaled(v, settings.speechRate, 0.15f, 0.5f, 2f)
                ?.let { changed(settings.copy(speechRate = it), "Speaking at ${"%.2f".format(Locale.US, it)}×.") }
                ?: ask("Faster, slower, or a speed like 1.2?")
            "speech_pitch" -> scaled(v, settings.speechPitch, 0.1f, 0.5f, 2f)
                ?.let { changed(settings.copy(speechPitch = it), "Voice pitch ${"%.2f".format(Locale.US, it)}.") }
                ?: ask("Higher, lower, or a pitch like 0.9?")
            "speech_language" -> changed(
                settings.copy(speechLanguage = if (v in CLEAR) "" else language(value), voiceName = ""),
                if (v in CLEAR) "Listening and speaking in the phone's language." else "Main language: ${language(value)}."
            )
            "auto_language" -> flag({ settings.copy(autoLanguage = it) }, "recognising each language by itself")
            "reply_language" -> changed(
                settings.copy(replyLanguage = if (v in CLEAR) "" else value.take(30)),
                if (v in CLEAR) "I'll answer in whatever language you use." else "I'll always answer in ${value.take(30)}."
            )
            "map_style" -> when {
                v.contains("sat") -> "satellite"
                v.contains("light") || v.contains("hell") -> "light"
                v.contains("street") || v.contains("osm") || v.contains("straße") || v.contains("strasse") -> "streets"
                v.contains("dark") || v.contains("dunkel") -> "dark"
                else -> null
            }?.let { changed(settings.copy(mapStyle = it), "Map style: $it.") }
                ?: ask("Dark, light, streets or satellite?")
            "travel_mode" -> when {
                listOf("walk", "foot", "zu fuß", "laufen", "gehen").any { v.contains(it) } -> Geo.MODE_WALK
                listOf("bike", "cycl", "fahrrad", "rad").any { v.contains(it) } -> Geo.MODE_CYCLE
                listOf("driv", "car", "auto").any { v.contains(it) } -> Geo.MODE_DRIVE
                else -> null
            }?.let { changed(settings.copy(travelMode = it), "Routes are now for $it.") }
                ?: ask("Walking, cycling or driving?")
            "search_radius" -> metres(v)?.coerceIn(200, 20_000)
                ?.let { changed(settings.copy(searchRadiusMeters = it), "Searching within ${Geo.formatDistance(it.toDouble())}.") }
                ?: ask("How far should I look, e.g. 2 km?")
            "currency" -> Regex("^[a-z]{3}$").find(v.replace("€", "eur").replace("$", "usd").replace("£", "gbp").trim())
                ?.let { changed(settings.copy(defaultCurrency = it.value.uppercase(Locale.ROOT)), "Money in ${it.value.uppercase(Locale.ROOT)}.") }
                ?: ask("Which currency, as a code like EUR or USD?")
            "proactive" -> flag({ settings.copy(proactive = it) }, "suggesting next steps")
            "morning_brief" -> flag({ settings.copy(morningBrief = it) }, "the morning brief")
            "brief_time" -> clock(v)?.let { changed(settings.copy(briefTime = it), if (it.isEmpty()) "No morning brief notification." else "Morning brief at $it.") }
                ?: ask("At what time, as HH:MM?")
            "evening_time" -> clock(v)?.let { changed(settings.copy(eveningTime = it), if (it.isEmpty()) "No evening wrap-up." else "Evening wrap-up at $it.") }
                ?: ask("At what time, as HH:MM?")
            "haptics" -> flag({ settings.copy(haptics = it) }, "vibration")
            "sounds" -> flag({ settings.copy(earcons = it) }, "listening sounds")
            "wake_word" -> flag({ settings.copy(wakeWordEnabled = it) }, "the wake word")
            "wake_phrase" -> if (value.isBlank()) ask("Which word should wake me?")
                else changed(settings.copy(wakePhrase = v.take(30)), "I'll wake to '${v.take(30)}'.")
            "about_me" -> changed(settings.copy(aboutMe = add(settings.aboutMe, value)), "Noted about you.")
            "instructions" -> changed(settings.copy(customInstructions = add(settings.customInstructions, value)), "I'll keep that in mind in every answer.")
            else -> Result.Refused("I can't change '$rawKey'. I can change: ${KEYS.joinToString()}.")
        }
    }

    private fun ask(question: String) = Result.Refused(question)

    private fun norm(key: String) = key.trim().lowercase(Locale.ROOT).replace(' ', '_').replace('-', '_')

    private val CLEAR = setOf("", "none", "nothing", "clear", "reset", "default", "off", "keins", "nichts", "standard")

    private fun bool(v: String): Boolean? = when (v) {
        "on", "true", "yes", "enable", "enabled", "an", "ein", "ja", "1" -> true
        "off", "false", "no", "disable", "disabled", "aus", "nein", "0" -> false
        else -> null
    }

    /** "faster", "slower", "1.2", "120%" -> a new value within bounds. */
    private fun scaled(v: String, now: Float, step: Float, min: Float, max: Float): Float? {
        val up = listOf("faster", "higher", "bigger", "larger", "more", "schneller", "höher", "größer", "grösser", "up")
        val down = listOf("slower", "lower", "smaller", "less", "langsamer", "tiefer", "kleiner", "down")
        val number = Regex("(\\d+(?:[.,]\\d+)?)\\s*(%)?").find(v)
        val next = when {
            number != null -> number.groupValues[1].replace(',', '.').toFloat().let { if (number.groupValues[2] == "%" || it > 3f) it / 100f else it }
            up.any { v.contains(it) } -> now + step
            down.any { v.contains(it) } -> now - step
            v.contains("normal") || v.contains("default") -> 1f
            else -> return null
        }
        return (Math.round(next.coerceIn(min, max) * 100) / 100f)
    }

    private fun metres(v: String): Int? {
        val m = Regex("(\\d+(?:[.,]\\d+)?)\\s*(km|kilomet|m\\b|met|mile)?").find(v) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDouble()
        val unit = m.groupValues[2]
        return when {
            unit.startsWith("k") -> n * 1000
            unit.startsWith("mile") -> n * 1609
            unit.isEmpty() && n < 50 -> n * 1000
            else -> n
        }.toInt()
    }

    private fun clock(v: String): String? {
        if (v in CLEAR) return ""
        val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?").find(v) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifBlank { "0" }.toInt()
        if (m.groupValues[3] == "pm" && h < 12) h += 12
        if (m.groupValues[3] == "am" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return "%02d:%02d".format(h, min)
    }

    private fun language(value: String): String {
        val v = value.trim().lowercase(Locale.ROOT)
        return LANGUAGES[v] ?: value.trim()
    }

    private fun add(existing: String, more: String): String =
        if (existing.isBlank()) more.trim() else existing.trimEnd() + "\n" + more.trim()

    /** A preset by its id or colour word, or any colour as a hue of its own. */
    private fun accent(v: String): Pair<String, String>? {
        PRESETS.entries.firstOrNull { (id, words) -> v == id || words.any { v.contains(it) } }
            ?.let { return it.key to it.value.first() }
        HUES.entries.firstOrNull { v.contains(it.key) }?.let { return "custom:${it.value}" to it.key }
        v.toIntOrNull()?.takeIf { it in 0..360 }?.let { return "custom:$it" to "hue $it" }
        return null
    }

    private val PRESETS = linkedMapOf(
        "arc" to listOf("blue", "blau", "arc", "cyan"),
        "stark" to listOf("gold", "yellow", "gelb"),
        "crimson" to listOf("red", "rot", "crimson", "mark iii"),
        "emerald" to listOf("green", "grün", "gruen", "emerald"),
        "violet" to listOf("violet", "purple", "lila", "violett"),
        "solar" to listOf("orange", "solar"),
        "rose" to listOf("pink", "rosa", "rose"),
        "silver" to listOf("silver", "silber", "white", "weiß", "grey", "gray", "grau")
    )

    private val HUES = linkedMapOf(
        "teal" to 175, "türkis" to 175, "turquoise" to 175, "mint" to 150, "lime" to 90,
        "magenta" to 300, "navy" to 225, "indigo" to 250, "coral" to 10, "amber" to 40
    )

    private val BACKDROPS = linkedMapOf(
        "space" to listOf("space", "weltall", "deep"),
        "oled" to listOf("black", "schwarz", "oled"),
        "graphite" to listOf("graphite", "grey", "gray", "grau"),
        "midnight" to listOf("midnight", "mitternacht", "navy"),
        "nebula" to listOf("nebula", "nebel", "purple"),
        "aurora" to listOf("aurora", "polarlicht", "green"),
        "ember" to listOf("ember", "glut", "warm", "red"),
        "abyss" to listOf("abyss", "ocean", "sea", "meer", "blue")
    )

    private val LANGUAGES = mapOf(
        "german" to "de-DE", "deutsch" to "de-DE", "english" to "en-US", "englisch" to "en-US",
        "british" to "en-GB", "french" to "fr-FR", "französisch" to "fr-FR", "spanish" to "es-ES",
        "spanisch" to "es-ES", "italian" to "it-IT", "italienisch" to "it-IT", "turkish" to "tr-TR",
        "türkisch" to "tr-TR", "portuguese" to "pt-PT", "portugiesisch" to "pt-PT", "dutch" to "nl-NL",
        "niederländisch" to "nl-NL", "polish" to "pl-PL", "polnisch" to "pl-PL"
    )

    private val ALIASES = mapOf(
        "name" to "assistant_name", "your_name" to "assistant_name", "my_name" to "user_name",
        "honorific" to "call_me", "address" to "call_me", "nickname" to "call_me",
        "persona" to "personality", "character" to "personality", "length" to "reply_length",
        "answer_length" to "reply_length", "humor" to "wit", "humour" to "wit", "funny" to "wit",
        "color" to "accent", "colour" to "accent", "theme" to "accent", "farbe" to "accent",
        "background" to "backdrop", "hintergrund" to "backdrop", "core" to "core_style",
        "animations" to "reduce_motion", "font_size" to "text_size", "text_scale" to "text_size",
        "hud" to "show_hud", "voice" to "speak_replies", "speak" to "speak_replies",
        "speed" to "speech_rate", "rate" to "speech_rate", "pitch" to "speech_pitch",
        "language" to "speech_language", "sprache" to "speech_language", "detect_language" to "auto_language",
        "answer_language" to "reply_language", "map" to "map_style", "karte" to "map_style",
        "travel" to "travel_mode", "radius" to "search_radius", "earcons" to "sounds",
        "vibration" to "haptics", "brief" to "morning_brief", "wake" to "wake_word",
        "custom_instructions" to "instructions", "rules" to "instructions"
    )
}
