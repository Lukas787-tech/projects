package com.lukas.jarvis

import com.lukas.jarvis.core.SettingChange
import com.lukas.jarvis.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingChangeTest {

    private val base = Settings()

    private fun changed(key: String, value: String, from: Settings = base): Settings {
        val result = SettingChange.apply(from, key, value)
        assertTrue("$key=$value: $result", result is SettingChange.Result.Changed)
        return (result as SettingChange.Result.Changed).settings
    }

    @Test fun lookInWords() {
        // 6.0: colour words land on the café's accents, and dark or light is the theme.
        assertEquals("berry", changed("accent", "red").accent)
        assertEquals("honey", changed("colour", "Gold").accent)
        assertEquals("custom:175", changed("accent", "teal").accent)
        assertEquals("dark", changed("background", "pure black").themeMode)
        assertEquals("dark", changed("theme", "night").themeMode)
        assertEquals("light", changed("dark_mode", "off, light please").themeMode)
        // A colour word wins: "light blue" is a colour, not the light theme.
        assertEquals("sky", changed("colour", "light blue").accent)
        assertEquals(1.1f, changed("text_size", "bigger").textScale, 0.001f)
        assertEquals(1.2f, changed("text_size", "120%").textScale, 0.001f)
    }

    @Test fun voiceAndCharacter() {
        assertEquals(0.9f, changed("speech_rate", "slower").speechRate, 0.001f)
        assertEquals(1.3f, changed("speed", "1.3").speechRate, 0.001f)
        assertEquals("boss", changed("call_me", "boss").honorific)
        assertEquals("", changed("call_me", "reset", base.copy(honorific = "sir")).honorific)
        assertEquals("Friday", changed("name", "Friday").assistantName)
        assertEquals("zen", changed("personality", "zen").personality)
        assertEquals("detailed", changed("reply_length", "longer, detailed").replyLength)
        assertEquals(false, changed("emoji", "off").emoji)
        assertEquals("fr-FR", changed("language", "French").speechLanguage)
    }

    @Test fun mapAndDay() {
        assertEquals("satellite", changed("map", "satellite").mapStyle)
        assertEquals("cycling", changed("travel_mode", "by bike").travelMode)
        assertEquals(2000, changed("radius", "2 km").searchRadiusMeters)
        assertEquals("07:00", changed("brief_time", "7").briefTime)
        assertEquals("19:30", changed("evening_time", "7:30 pm").eveningTime)
        assertEquals("USD", changed("currency", "usd").defaultCurrency)
    }

    @Test fun asksWhenItCannotTell() {
        assertTrue(SettingChange.apply(base, "accent", "the nice one") is SettingChange.Result.Refused)
        assertTrue(SettingChange.apply(base, "api_key", "abc") is SettingChange.Result.Refused)
        assertTrue(SettingChange.apply(base, "emoji", "maybe") is SettingChange.Result.Refused)
    }

    @Test fun whichVoice() {
        val keyed = base.copy(fishKey = "sk-1")
        assertEquals("fish", changed("voice_engine", "Fish Audio", keyed).voiceEngine)
        assertEquals("fish", changed("voice", "the fish audio voice", keyed).voiceEngine)
        assertEquals("device", changed("tts", "phone", keyed.copy(voiceEngine = "fish")).voiceEngine)
        // No key yet: say so rather than switching to a voice that cannot speak.
        assertTrue(SettingChange.apply(base, "voice_engine", "fish") is SettingChange.Result.Refused)
        // "voice off" is still about speaking at all.
        assertEquals(false, changed("voice", "off").speakReplies)
    }
}
