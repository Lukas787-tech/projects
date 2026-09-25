package com.lukas.jarvis

/*
 * Profiles: a whole look, character and voice saved under a name, put back
 * whole, and found again however it is said.
 */
import com.lukas.jarvis.core.Profile
import com.lukas.jarvis.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileTest {

    private val night = Settings(
        accent = "crimson", backdrop = "oled", coreStyle = "orb", personality = "zen",
        replyLength = "short", wit = 0, speechRate = 0.9f, speechPitch = 0.95f, reduceMotion = true,
        apiKey = "secret", userName = "Lukas"
    )

    @Test fun applyingPutsBackTheWholeSetupAndNothingElse() {
        val saved = Profile.of("Night", night)
        val day = Settings(apiKey = "other key", userName = "Lukas", wit = 2)
        val switched = saved.applyTo(day)
        assertEquals("crimson", switched.accent)
        assertEquals("oled", switched.backdrop)
        assertEquals("zen", switched.personality)
        assertEquals(0.9f, switched.speechRate, 0.001f)
        assertEquals(true, switched.reduceMotion)
        // Keys and who the user is are not part of a profile.
        assertEquals("other key", switched.apiKey)
        assertEquals("Lukas", switched.userName)
    }

    @Test fun survivesTheStore() {
        val saved = Profile.of("Night", night)
        assertEquals(saved, Profile.fromJson(saved.toJson()))
        assertNull(Profile.fromJson(org.json.JSONObject().put("accent", "arc")))
    }

    @Test fun foundHoweverItIsSaid() {
        val all = listOf(Profile.of("Night", night), Profile.of("Work", Settings()))
        assertEquals("Night", Profile.match(all, "night mode")?.name)
        assertEquals("Work", Profile.match(all, "the Work profile")?.name)
        assertEquals("Night", Profile.match(all, "Night-Modus")?.name)
        assertNull(Profile.match(all, "party"))
    }

    @Test fun savedUnderItsPlainName() {
        assertEquals("night", Profile.of("the night mode", night).name)
        assertEquals("Arbeit", Profile.of("den Arbeit-Modus", night).name)
        assertEquals("Mode", Profile.cleanName("Mode"))
        assertEquals("Work", Profile.cleanName("\"Work profile\""))
    }
}
