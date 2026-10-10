package com.lukas.jarvis

import com.lukas.jarvis.auto.Routine
import com.lukas.jarvis.auto.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggersTest {

    @Test fun whatWasSaidBecomesATrigger() {
        assertEquals(Trigger(Trigger.Kind.Charging), Trigger.parse("charging"))
        assertEquals(Trigger(Trigger.Kind.Charging), Trigger.parse("when I plug the phone in"))
        assertEquals(Trigger(Trigger.Kind.Connected, "car"), Trigger.parse("connected", "my car"))
        assertEquals(Trigger(Trigger.Kind.Disconnected, "airpods"), Trigger.parse("disconnected", "the AirPods"))
        assertEquals(Trigger(Trigger.Kind.EventStarts), Trigger.parse("event", "car"))
        assertEquals(Trigger(Trigger.Kind.Connected), Trigger.parse("bluetooth"))
        assertNull(Trigger.parse("whenever"))
        assertNull(Trigger.parse(""))
    }

    @Test fun aNamedDeviceIsFoundInsideTheNameThePhoneGives() {
        val car = Trigger(Trigger.Kind.Connected, "car")
        assertTrue(car.matches(Trigger.Kind.Connected, "VW Car Audio"))
        assertFalse(car.matches(Trigger.Kind.Connected, "Lukas's AirPods Pro"))
        assertFalse(car.matches(Trigger.Kind.Disconnected, "VW Car Audio"))
        // A trigger for a named device is not set off by one the phone could not name.
        assertFalse(car.matches(Trigger.Kind.Connected, null))
        // Any device at all.
        assertTrue(Trigger(Trigger.Kind.Connected).matches(Trigger.Kind.Connected, null))
        assertTrue(Trigger(Trigger.Kind.Connected, "airpods pro").matches(Trigger.Kind.Connected, "Lukas's AirPods Pro"))
    }

    @Test fun aTriggerSurvivesBeingStored() {
        listOf(Trigger(Trigger.Kind.Charging), Trigger(Trigger.Kind.Connected, "car"), Trigger(Trigger.Kind.EventStarts)).forEach {
            assertEquals(it, Trigger.decode(it.encode()))
        }
        assertNull(Trigger.decode("sometimes"))
        assertNull(Trigger.decode(null))
    }

    @Test fun aRoutineSaysWhenItStarts() {
        assertEquals("when the car connects", Routine("Drive", listOf("How's the traffic?"), trigger = Trigger(Trigger.Kind.Connected, "car")).schedule)
        assertEquals(
            "every day at 22:00, when the phone starts charging",
            Routine("Night", listOf("Turn on do not disturb"), time = "22:00", trigger = Trigger(Trigger.Kind.Charging)).schedule
        )
        assertEquals("", Routine("Plain", listOf("Hi")).schedule)
    }
}
