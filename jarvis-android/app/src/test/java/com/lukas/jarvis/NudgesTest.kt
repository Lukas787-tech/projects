package com.lukas.jarvis

import com.lukas.jarvis.brief.Nudges
import com.lukas.jarvis.control.Appointment
import com.lukas.jarvis.data.Streak
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NudgesTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val all = Nudges.ALL.toSet()

    @Test fun nothingAtNightAndNothingOnAQuietDay() {
        val inputs = Nudges.Inputs(now = at("2026-10-10T23:00"), rainFrom = "23:30")
        assertNull(Nudges.pick(inputs, all))
        assertNull(Nudges.pick(Nudges.Inputs(now = at("2026-10-10T07:30"), rainFrom = "08:00"), all))
        assertNull(Nudges.pick(Nudges.Inputs(now = at("2026-10-10T12:00")), all))
    }

    @Test fun rainOnTheWayIsSaidOnceADay() {
        val inputs = Nudges.Inputs(now = at("2026-10-10T14:10"), rainFrom = "16:00")
        val nudge = Nudges.pick(inputs, all)
        assertEquals("Rain from about 16:00", nudge?.title)
        assertNull(Nudges.pick(inputs.copy(said = mapOf(nudge!!.key to inputs.now)), all))
        // Already raining, or not for hours: nothing.
        assertNull(Nudges.pick(inputs.copy(rainingNow = true), all))
        assertNull(Nudges.pick(inputs.copy(rainFrom = "21:00"), all))
        // Switched off: nothing.
        assertNull(Nudges.pick(inputs, all - Nudges.RAIN))
    }

    @Test fun anAppointmentSomewhereComesFirst() {
        val now = at("2026-10-10T14:00")
        val inputs = Nudges.Inputs(
            now = now,
            rainFrom = "15:00",
            appointments = listOf(Appointment("Dentist", now + 45 * 60_000L, now + 90 * 60_000L, "Hauptstraße 5", false))
        )
        val nudge = Nudges.pick(inputs, all)
        assertEquals(Nudges.EVENT, nudge?.kind)
        assertEquals("How do I get to Hauptstraße 5?", nudge?.ask)
        // Without a place there is nowhere to go; too far off, not yet.
        val nowhere = inputs.copy(appointments = listOf(Appointment("Call", now + 45 * 60_000L, now + 60 * 60_000L, null, false)))
        assertEquals(Nudges.RAIN, Nudges.pick(nowhere, all)?.kind)
    }

    @Test fun aBudgetAtNineTenthsWithDaysToGo() {
        val t = Tracker(id = 4, name = "groceries", label = "Groceries", budget = 300.0, period = Tracker.PERIOD_MONTHLY)
        val status = TrackerStatus(t, periodSpent = 275.0, periodReceived = 0.0, periodStart = 0, entryCount = 9, allTimeSpent = 275.0, allTimeReceived = 0.0)
        val nudge = Nudges.pick(Nudges.Inputs(now = at("2026-10-12T12:00"), trackers = listOf(status)), all)
        assertEquals("Groceries: 92% of the budget", nudge?.title)
        // Over it already, or on the last day: nothing more to warn about.
        assertNull(Nudges.pick(Nudges.Inputs(now = at("2026-10-12T12:00"), trackers = listOf(status.copy(periodSpent = 320.0))), all))
        assertNull(Nudges.pick(Nudges.Inputs(now = at("2026-10-31T12:00"), trackers = listOf(status)), all))
    }

    @Test fun eveningNudgesWaitForTheEvening() {
        val yoga = Tracker(id = 2, name = "yoga", label = "yoga", kind = Tracker.KIND_COUNT)
        val run = Streak(current = 5, best = 9, today = false, week = List(7) { it != 6 })
        val afternoon = Nudges.Inputs(now = at("2026-10-10T16:00"), habits = listOf(yoga to run), dueToday = listOf(Task(title = "Pay rent")))
        assertNull(Nudges.pick(afternoon, all))
        val evening = afternoon.copy(now = at("2026-10-10T19:30"))
        assertEquals("Your 5-day yoga run", Nudges.pick(evening, all)?.title)
        assertEquals("One thing still open today", Nudges.pick(evening, all - Nudges.HABIT)?.title)
        // Done today: nothing to save.
        assertEquals(Nudges.OPEN, Nudges.pick(evening.copy(habits = listOf(yoga to run.copy(today = true))), all)?.kind)
    }

    @Test fun noMoreThanThreeADay() {
        val now = at("2026-10-10T19:30")
        val said = mapOf("a" to now - 3_600_000L, "b" to now - 7_200_000L, "c" to now - 10_800_000L)
        assertNull(Nudges.pick(Nudges.Inputs(now = now, dueToday = listOf(Task(title = "Pay rent")), said = said), all))
        // Yesterday's do not count against today.
        val yesterday = said.mapValues { it.value - 86_400_000L }
        assertEquals(Nudges.OPEN, Nudges.pick(Nudges.Inputs(now = now, dueToday = listOf(Task(title = "Pay rent")), said = yesterday), all)?.kind)
    }

    @Test fun kindsFromTheSetting() {
        assertEquals(setOf(Nudges.RAIN, Nudges.EVENT), Nudges.kinds("rain, event"))
        assertEquals(all, Nudges.kinds(""))
        assertEquals(all, Nudges.kinds("nonsense"))
    }
}
