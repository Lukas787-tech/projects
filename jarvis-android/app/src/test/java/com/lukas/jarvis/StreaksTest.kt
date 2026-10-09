package com.lukas.jarvis

import com.lukas.jarvis.data.Streaks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StreaksTest {
    private val today = LocalDate.of(2026, 10, 9)
    private fun ago(vararg n: Long) = n.map { today.minusDays(it) }.toSet()

    @Test fun aRunEndingTodayCounts() {
        val s = Streaks.of(ago(0, 1, 2, 3), today)
        assertEquals(4, s.current)
        assertTrue(s.today)
        assertEquals("4 days in a row — your best yet", Streaks.describe(s))
    }

    @Test fun yesterdaysRunIsStillAliveThisMorning() {
        val s = Streaks.of(ago(1, 2, 3), today)
        assertEquals(3, s.current)
        assertFalse(s.today)
    }

    @Test fun aMissedDayEndsIt() {
        val s = Streaks.of(ago(2, 3, 4, 5, 6, 7), today)
        assertEquals(0, s.current)
        assertEquals(6, s.best)
        assertEquals("", Streaks.describe(s))
    }

    @Test fun theWeekIsOldestFirst() {
        val s = Streaks.of(ago(0, 6), today)
        assertEquals(listOf(true, false, false, false, false, false, true), s.week)
        assertEquals(2, s.daysThisWeek)
    }
}
