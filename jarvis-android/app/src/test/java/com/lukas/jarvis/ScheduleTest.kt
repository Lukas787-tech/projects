package com.lukas.jarvis

/*
 * Which days a timed routine keeps, as people say them, and when it next
 * comes round.
 */
import com.lukas.jarvis.auto.RoutineDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleTest {

    private val berlin = TimeZone.getTimeZone("Europe/Berlin")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(berlin).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    @Test fun daysAsPeopleSayThem() {
        assertEquals(RoutineDays.WEEKDAYS, RoutineDays.parse("weekdays"))
        assertEquals(RoutineDays.WEEKDAYS, RoutineDays.parse("Werktags"))
        assertEquals(RoutineDays.WEEKDAYS, RoutineDays.parse("Mo-Fr"))
        assertEquals(RoutineDays.WEEKDAYS, RoutineDays.parse("monday to friday"))
        assertEquals(RoutineDays.WEEKEND, RoutineDays.parse("at weekends"))
        assertEquals(
            setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY),
            RoutineDays.parse("mon, wed and fri")
        )
        assertEquals(setOf(Calendar.TUESDAY, Calendar.THURSDAY), RoutineDays.parse("Dienstags und Donnerstags"))
        assertEquals(setOf(Calendar.SUNDAY), RoutineDays.parse("on Sundays"))
        // A range across the weekend wraps round.
        assertEquals(setOf(Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY), RoutineDays.parse("fri-sun"))
        assertTrue(RoutineDays.parse("every day").isEmpty())
        assertTrue(RoutineDays.parse("täglich").isEmpty())
        assertTrue(RoutineDays.parse("").isEmpty())
        assertTrue(RoutineDays.parse("mon tue wed thu fri sat sun").isEmpty())
    }

    @Test fun exceptionsAreTakenAway() {
        val allButSunday = setOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY
        )
        assertEquals(allButSunday, RoutineDays.parse("every day except sunday"))
        assertEquals(allButSunday, RoutineDays.parse("täglich außer sonntags"))
        assertEquals(
            setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY),
            RoutineDays.parse("weekdays but not friday")
        )
    }

    @Test fun ordinaryWordsAreNotDays() {
        // "do", "so", "we" and "mit" start like day names but are words here.
        assertEquals(setOf(Calendar.FRIDAY), RoutineDays.parse("so we do it on fridays"))
        assertEquals(setOf(Calendar.MONDAY), RoutineDays.parse("montags mit Kaffee"))
        assertEquals(
            setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY),
            RoutineDays.parse("Mo, Mi, Fr")
        )
    }

    @Test fun describedBack() {
        assertEquals("every day", RoutineDays.describe(emptySet()))
        assertEquals("on weekdays", RoutineDays.describe(RoutineDays.WEEKDAYS))
        assertEquals("at weekends", RoutineDays.describe(RoutineDays.WEEKEND))
        assertEquals("on Mondays and Fridays", RoutineDays.describe(setOf(Calendar.FRIDAY, Calendar.MONDAY)))
        val days = setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY)
        assertEquals(days, RoutineDays.decode(RoutineDays.encode(days)))
    }

    @Test fun nextRunSkipsTheDaysOff() {
        // Friday 2026-09-25, 08:00: a weekday 07:30 check has passed today.
        val friday = at(2026, 9, 25, 8, 0)
        assertEquals(at(2026, 9, 28, 7, 30), RoutineDays.next(friday, 7, 30, RoutineDays.WEEKDAYS, berlin))
        // Every day: tomorrow.
        assertEquals(at(2026, 9, 26, 7, 30), RoutineDays.next(friday, 7, 30, emptySet(), berlin))
        // Later today still counts.
        assertEquals(at(2026, 9, 25, 18, 0), RoutineDays.next(friday, 18, 0, RoutineDays.WEEKDAYS, berlin))
        // Across the change to winter time, the wall clock holds.
        val saturday = at(2026, 10, 24, 12, 0)
        assertEquals(at(2026, 10, 25, 7, 0), RoutineDays.next(saturday, 7, 0, RoutineDays.WEEKEND, berlin))
    }
}
