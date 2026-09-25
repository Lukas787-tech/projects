package com.lukas.jarvis.core

import org.json.JSONObject
import java.util.Locale

/**
 * A saved setup: how Jarvis looks, who it is and how it sounds, under a name.
 *
 * "Night" might be pure black, calm, quiet and short; "Work" the arc reactor,
 * the professional, detailed answers. Switching is one sentence or one tap,
 * and only these fields change — keys, places, powers and everything the
 * assistant knows stay as they are.
 */
data class Profile(
    val name: String,
    val accent: String,
    val backdrop: String,
    val coreStyle: String,
    val personality: String,
    val honorific: String,
    val replyLength: String,
    val wit: Int,
    val emoji: Boolean,
    val customInstructions: String,
    val voiceName: String,
    val speechRate: Float,
    val speechPitch: Float,
    val speakReplies: Boolean,
    val reduceMotion: Boolean,
    /** "22:00": switched to by itself at that time; blank for never. */
    val autoAt: String = "",
    /** The days [autoAt] applies on, 1 = Sunday … 7 = Saturday; empty for every day. */
    val autoDays: Set<Int> = emptySet()
) {

    /** Whether [settings] already look, sound and behave like this profile. */
    fun matches(settings: Settings): Boolean = applyTo(settings) == settings

    /** The next time this profile switches itself on, after [now]; null when it never does. */
    fun nextSwitch(now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long? {
        val (hour, minute) = clockOf(autoAt) ?: return null
        return com.lukas.jarvis.auto.RoutineDays.next(now, hour, minute, autoDays, zone)
    }

    /** "every day at 22:00", "on weekdays at 08:00"; blank when it never switches itself on. */
    fun scheduleLabel(): String =
        if (clockOf(autoAt) == null) "" else com.lukas.jarvis.auto.RoutineDays.describe(autoDays) + " at " + autoAt

    /** [settings] with this profile's look, character and voice. */
    fun applyTo(settings: Settings): Settings = settings.copy(
        accent = accent,
        backdrop = backdrop,
        coreStyle = coreStyle,
        personality = personality,
        honorific = honorific,
        replyLength = replyLength,
        wit = wit,
        emoji = emoji,
        customInstructions = customInstructions,
        voiceName = voiceName,
        speechRate = speechRate,
        speechPitch = speechPitch,
        speakReplies = speakReplies,
        reduceMotion = reduceMotion
    )

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("accent", accent)
        .put("backdrop", backdrop)
        .put("core", coreStyle)
        .put("personality", personality)
        .put("honorific", honorific)
        .put("length", replyLength)
        .put("wit", wit)
        .put("emoji", emoji)
        .put("instructions", customInstructions)
        .put("voice", voiceName)
        .put("rate", speechRate.toDouble())
        .put("pitch", speechPitch.toDouble())
        .put("speak", speakReplies)
        .put("calm", reduceMotion)
        .put("at", autoAt)
        .put("days", com.lukas.jarvis.auto.RoutineDays.encode(autoDays))

    companion object {

        /** The current setup, to be kept under [name]. */
        fun of(name: String, settings: Settings) = Profile(
            name = cleanName(name),
            accent = settings.accent,
            backdrop = settings.backdrop,
            coreStyle = settings.coreStyle,
            personality = settings.personality,
            honorific = settings.honorific,
            replyLength = settings.replyLength,
            wit = settings.wit,
            emoji = settings.emoji,
            customInstructions = settings.customInstructions,
            voiceName = settings.voiceName,
            speechRate = settings.speechRate,
            speechPitch = settings.speechPitch,
            speakReplies = settings.speakReplies,
            reduceMotion = settings.reduceMotion
        )

        /** Missing fields keep what [base] has, so an older profile still loads. */
        fun fromJson(obj: JSONObject, base: Settings = Settings()): Profile? {
            val name = obj.optString("name").trim()
            if (name.isBlank()) return null
            return Profile(
                name = name,
                accent = obj.optString("accent", base.accent),
                backdrop = obj.optString("backdrop", base.backdrop),
                coreStyle = obj.optString("core", base.coreStyle),
                personality = obj.optString("personality", base.personality),
                honorific = obj.optString("honorific", base.honorific),
                replyLength = obj.optString("length", base.replyLength),
                wit = obj.optInt("wit", base.wit),
                emoji = obj.optBoolean("emoji", base.emoji),
                customInstructions = obj.optString("instructions", base.customInstructions),
                voiceName = obj.optString("voice", base.voiceName),
                speechRate = obj.optDouble("rate", base.speechRate.toDouble()).toFloat(),
                speechPitch = obj.optDouble("pitch", base.speechPitch.toDouble()).toFloat(),
                speakReplies = obj.optBoolean("speak", base.speakReplies),
                reduceMotion = obj.optBoolean("calm", base.reduceMotion),
                autoAt = clockOf(obj.optString("at"))?.let { (h, m) -> "%02d:%02d".format(h, m) }.orEmpty(),
                autoDays = com.lukas.jarvis.auto.RoutineDays.decode(obj.optString("days"))
            )
        }

        /** "22:00", "7:30", "22.15" -> hour and minute; null for anything else. */
        fun clockOf(text: String): Pair<Int, Int>? {
            val m = Regex("^\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*$").find(text) ?: return null
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifBlank { "0" }.toInt()
            return if (hour in 0..23 && minute in 0..59) hour to minute else null
        }

        /** "the night mode" -> "night"; "Work profile" -> "Work". A bare "Mode" stays. */
        fun cleanName(raw: String): String {
            var name = raw.trim().trim('"', '\'', '.')
            for (prefix in listOf("the ", "my ", "den ", "das ", "die ", "mein ", "meinen ")) {
                if (name.length > prefix.length && name.startsWith(prefix, ignoreCase = true)) name = name.substring(prefix.length)
            }
            for (suffix in listOf(" profile", " profil", " mode", " modus", "-modus", "-mode")) {
                if (name.length > suffix.length && name.endsWith(suffix, ignoreCase = true)) name = name.dropLast(suffix.length)
            }
            return name.trim()
        }

        /** "night mode", "the Night profile", "Nacht" -> the saved one it means. */
        fun match(all: List<Profile>, wanted: String): Profile? {
            val key = cleanName(wanted).lowercase(Locale.ROOT)
            if (key.isBlank()) return null
            return all.firstOrNull { it.name.lowercase(Locale.ROOT) == key }
                ?: all.filter { key.contains(it.name.lowercase(Locale.ROOT)) || it.name.lowercase(Locale.ROOT).contains(key) }
                    .singleOrNull()
        }
    }
}
