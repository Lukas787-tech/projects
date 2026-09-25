package com.lukas.jarvis

import android.app.AlarmManager
import android.os.Looper
import com.lukas.jarvis.core.Profile
import com.lukas.jarvis.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/*
 * A profile with a time of day: the alarm is set for its next time, and when
 * it goes off the whole look switches on the phone, with no model involved,
 * and the next day's alarm is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileSwitchTest {

    @Test fun switchesAtItsTime() {
        val app = RuntimeEnvironment.getApplication() as JarvisApp
        val container = app.container
        container.profiles.current.forEach { container.profiles.remove(it.name) }
        container.settings.update { it.copy(accent = "arc", personality = "jarvis") }

        container.profiles.save(Profile.of("Night", Settings(accent = "crimson", personality = "zen")))
        container.profiles.schedule("Night", "22:00", emptySet())

        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val next = alarms.scheduledAlarms.firstOrNull { it.operation?.let { op -> shadowOf(op).savedIntent.action } == com.lukas.jarvis.core.ProfileStore.ACTION_SWITCH }
        assertNotNull("an alarm for the profile: ${alarms.scheduledAlarms.size} alarms", next)
        val expected = container.profiles.find("Night")!!.nextSwitch(System.currentTimeMillis())!!
        assertEquals(expected, next!!.triggerAtMs)

        // The time comes.
        shadowOf(next.operation).savedIntent.let { app.sendBroadcast(it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("crimson", container.settings.current.accent)
        assertEquals("zen", container.settings.current.personality)
        assertTrue("armed again", alarms.scheduledAlarms.any { it.operation != null && shadowOf(it.operation).savedIntent.action == com.lukas.jarvis.core.ProfileStore.ACTION_SWITCH })

        // Switched off: no alarm left.
        container.profiles.schedule("Night", "", emptySet())
        assertTrue(alarms.scheduledAlarms.none { it.operation?.let { op -> shadowOf(op).savedIntent.action } == com.lukas.jarvis.core.ProfileStore.ACTION_SWITCH })
    }
}
