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

    @Test fun switchesOnByItself() {
        val zone = java.util.TimeZone.getTimeZone("Europe/Berlin")
        val cal = java.util.Calendar.getInstance(zone).apply { clear(); set(2026, java.util.Calendar.SEPTEMBER, 25, 21, 0) } // a Friday
        val night = Profile.of("Night", night).copy(autoAt = "22:00")
        val at = java.util.Calendar.getInstance(zone).apply { timeInMillis = night.nextSwitch(cal.timeInMillis, zone)!! }
        assertEquals(22, at.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(25, at.get(java.util.Calendar.DAY_OF_MONTH))
        // Weekdays only: from Friday night, the next is Monday.
        val work = Profile.of("Work", Settings()).copy(autoAt = "08:00", autoDays = setOf(2, 3, 4, 5, 6))
        val monday = java.util.Calendar.getInstance(zone).apply { timeInMillis = work.nextSwitch(cal.timeInMillis, zone)!! }
        assertEquals(28, monday.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals("on weekdays at 08:00", work.scheduleLabel())
        assertNull(Profile.of("Plain", Settings()).nextSwitch(0))
        // Kept in the store.
        assertEquals(work, Profile.fromJson(work.toJson()))
        assertEquals("", Profile.fromJson(work.toJson().put("at", "25:99"))!!.autoAt)
    }

    @Test fun litWhateverItsSchedule() {
        val saved = Profile.of("Night", night).copy(autoAt = "22:00")
        assert(saved.matches(night))
        assert(!saved.matches(Settings()))
    }
}
