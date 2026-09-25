package com.lukas.jarvis

import com.lukas.jarvis.core.Countdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CountdownTest {

    private val today = LocalDate.of(2026, 9, 25)

    @Test fun oneOffCountsDownAndEnds() {
        val holiday = Countdown(1, "Holiday", LocalDate.of(2026, 10, 12))
        assertEquals(17L, holiday.daysLeft(today))
        assertEquals("Holiday in 17 days", holiday.describe(today))
        assertEquals("Holiday today", holiday.describe(LocalDate.of(2026, 10, 12)))
        assertNull(holiday.next(LocalDate.of(2026, 10, 13)))
    }

    @Test fun birthdaysComeRoundAndCountTheYears() {
        val mum = Countdown(2, "Mum", LocalDate.of(1966, 3, 3), yearly = true, knowsYear = true, birthday = true)
        assertEquals(LocalDate.of(2027, 3, 3), mum.next(today))
        assertEquals(61, mum.yearsAt(today))
        assertEquals("Mum turns 60 tomorrow", mum.describe(LocalDate.of(2026, 3, 2)))
        val noYear = Countdown(3, "Tom", LocalDate.of(2026, 9, 26), yearly = true, birthday = true)
        assertEquals("Tom's birthday tomorrow", noYear.describe(today))
        // A leap-day birthday falls on the 28th in other years.
        val leap = Countdown(4, "Lea", LocalDate.of(2000, 2, 29), yearly = true, knowsYear = true, birthday = true)
        assertEquals(LocalDate.of(2027, 2, 28), leap.next(today))
    }

    @Test fun readsTheWaysADayIsWritten() {
        assertEquals(LocalDate.of(2026, 10, 12) to true, Countdown.parseDay("2026-10-12", today))
        assertEquals(LocalDate.of(2026, 10, 12) to false, Countdown.parseDay("12.10.", today))
        assertEquals(LocalDate.of(1966, 3, 3) to true, Countdown.parseDay("3 March 1966", today))
        assertEquals(LocalDate.of(2027, 3, 3) to false, Countdown.parseDay("03-03", today))
        assertEquals(LocalDate.of(2026, 12, 24) to false, Countdown.parseDay("December 24th", today))
        assertEquals(LocalDate.of(2026, 10, 3) to false, Countdown.parseDay("3. Oktober", today))
        assertEquals(LocalDate.of(2028, 2, 29) to false, Countdown.parseDay("29.2.", today))
        assertNull(Countdown.parseDay("31.02.", today))
        assertNull(Countdown.parseDay("soon", today))
    }

    @Test fun soonestFirstAndKeptInTheStore() {
        val a = Countdown(1, "Exam", LocalDate.of(2026, 11, 1))
        val b = Countdown(2, "Tom", LocalDate.of(2026, 9, 26), yearly = true, birthday = true)
        val past = Countdown(3, "Old", LocalDate.of(2026, 1, 1))
        assertEquals(listOf(b, a), Countdown.upcoming(listOf(a, b, past), today))
        assertEquals(b, Countdown.fromJson(b.toJson()))
    }
}
