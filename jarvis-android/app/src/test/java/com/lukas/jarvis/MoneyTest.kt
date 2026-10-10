package com.lukas.jarvis

import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.Money
import com.lukas.jarvis.data.Recurring
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.Window
import com.lukas.jarvis.moment.Bar
import com.lukas.jarvis.moment.Chart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class MoneyTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun date(ms: Long): LocalDate = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f EUR", v)

    @Test fun rentOnTheThirtyFirstKeepsItsDayAfterAShortMonth() {
        val anchor = at("2026-01-31T08:00")
        val dates = (0..3).map { date(Money.occurrence(anchor, Recurring.EVERY_MONTH, it, zone)) }
        assertEquals(
            listOf(LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28"), LocalDate.parse("2026-03-31"), LocalDate.parse("2026-04-30")),
            dates
        )
    }

    @Test fun aPhoneOffForMonthsCatchesUpOnTheRightDaysButNotForever() {
        val rule = Recurring(trackerId = 1, amount = 800.0, anchor = at("2026-01-01T08:00"))
        val due = Money.due(rule, at("2026-04-15T12:00"), zone)
        assertEquals(listOf("2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01"), due.map { date(it).toString() })
        // Already logged three: only April is left.
        assertEquals(1, Money.due(rule.copy(logged = 3), at("2026-04-15T12:00"), zone).size)
        // A daily rule set up years ago cannot flood the tracker.
        val daily = Recurring(trackerId = 1, amount = 1.0, every = Recurring.EVERY_DAY, anchor = at("2020-01-01T08:00"))
        assertEquals(62, Money.due(daily, at("2026-01-01T08:00"), zone).size)
        assertTrue(Money.due(rule.copy(active = false), at("2026-04-15T12:00"), zone).isEmpty())
    }

    @Test fun theFirstOneFallsOnTheNextSuchDayTodayIncluded() {
        val now = at("2026-10-09T14:00")
        assertEquals(LocalDate.parse("2026-11-01"), date(Money.firstDue(Recurring.EVERY_MONTH, "1st", null, now, zone)))
        assertEquals(LocalDate.parse("2026-10-09"), date(Money.firstDue(Recurring.EVERY_MONTH, "9", null, now, zone)))
        assertEquals(LocalDate.parse("2026-10-25"), date(Money.firstDue(Recurring.EVERY_MONTH, "the 25th", null, now, zone)))
        // The 31st in a 30-day month is its last day.
        assertEquals(LocalDate.parse("2026-11-30"), date(Money.firstDue(Recurring.EVERY_MONTH, "31", null, at("2026-11-02T10:00"), zone)))
        // 9 October 2026 is a Friday.
        assertEquals(LocalDate.parse("2026-10-09"), date(Money.firstDue(Recurring.EVERY_WEEK, "friday", null, now, zone)))
        assertEquals(LocalDate.parse("2026-10-12"), date(Money.firstDue(Recurring.EVERY_WEEK, "Monday", null, now, zone)))
        // Nothing said: right now. A date said: that date.
        assertEquals(now, Money.firstDue(Recurring.EVERY_MONTH, null, null, now, zone))
        assertEquals(at("2027-01-01T00:00"), Money.firstDue(Recurring.EVERY_MONTH, "5", at("2027-01-01T00:00"), now, zone))
    }

    @Test fun howOftenInPlainWords() {
        assertEquals(Recurring.EVERY_MONTH, Recurring.parseEvery("every month"))
        assertEquals(Recurring.EVERY_MONTH, Recurring.parseEvery("monatlich"))
        assertEquals(Recurring.EVERY_WEEK, Recurring.parseEvery("weekly"))
        assertEquals(Recurring.EVERY_YEAR, Recurring.parseEvery("annually"))
        assertEquals(Recurring.EVERY_DAY, Recurring.parseEvery("daily"))
        assertNull(Recurring.parseEvery("sometimes"))
    }

    @Test fun aBudgetWarnsOnceAtFourFifthsAndOnceWhenItIsGone() {
        val now = at("2026-10-21T12:00")
        val window = Money.window(Tracker.PERIOD_MONTHLY, now, zone)
        val warn = Money.budgetNews(300.0, 230.0, 250.0, Tracker.PERIOD_MONTHLY, window, now, ::fmt)
        assertEquals("Heads up: that's 83% of this month's budget, with 10 days to go.", warn)
        // Already past four fifths: a coffee is not news.
        assertNull(Money.budgetNews(300.0, 250.0, 253.0, Tracker.PERIOD_MONTHLY, window, now, ::fmt))
        assertEquals("That's 12.40 EUR over this month's budget.", Money.budgetNews(300.0, 290.0, 312.4, Tracker.PERIOD_MONTHLY, window, now, ::fmt))
        // Already over: not said again.
        assertNull(Money.budgetNews(300.0, 312.4, 320.0, Tracker.PERIOD_MONTHLY, window, now, ::fmt))
        // A daily budget has no days to go.
        assertEquals("Heads up: that's 90% of today's budget.", Money.budgetNews(10.0, 5.0, 9.0, Tracker.PERIOD_DAILY, Money.window(Tracker.PERIOD_DAILY, now, zone), now, ::fmt))
        // Money coming in is never a warning.
        assertNull(Money.budgetNews(300.0, 250.0, 240.0, Tracker.PERIOD_MONTHLY, window, now, ::fmt))
    }

    @Test fun thePaceSpeaksOnlyWhenItIsClearlyHeadingOver() {
        val window = Window(at("2026-10-01T00:00"), at("2026-11-01T00:00"))
        // Halfway through with 200 of 300 gone: heading for about 400.
        val half = at("2026-10-16T12:00")
        val heading = Money.pace(200.0, 300.0, window, half)
        assertNotNull(heading)
        assertTrue(heading!! in 390.0..410.0)
        // On track: nothing.
        assertNull(Money.pace(140.0, 300.0, window, half))
        // Too early in the month to tell.
        assertNull(Money.pace(150.0, 300.0, window, at("2026-10-04T12:00")))
        // Already over: the over-budget line says it.
        assertNull(Money.pace(320.0, 300.0, window, half))
    }

    @Test fun thisMonthSitsBesideTheSameDaysOfLastMonth() {
        val now = at("2026-10-09T18:00")
        fun e(tracker: Long, amount: Double, at: String, dir: String = Entry.DIR_OUT) =
            Entry(trackerId = tracker, amount = amount, direction = dir, occurredAt = at(at))
        val entries = listOf(
            e(1, 40.0, "2026-10-02T10:00"), e(1, 20.0, "2026-10-09T09:00"),
            e(1, 30.0, "2026-09-05T10:00"), e(1, 100.0, "2026-09-20T10:00"),
            e(2, 2400.0, "2026-10-01T09:00", Entry.DIR_IN)
        )
        val lines = Money.month(entries, now, zone)
        val food = lines.first { it.trackerId == 1L }
        assertEquals(60.0, food.out, 0.001)
        assertEquals(30.0, food.lastByNow, 0.001)
        assertEquals(130.0, food.lastTotal, 0.001)
        assertEquals(2, food.count)
        val salary = lines.first { it.trackerId == 2L }
        assertEquals(2400.0, salary.income, 0.001)
        assertEquals(0.0, salary.out, 0.001)
        // The biggest spender first.
        assertEquals(1L, lines.first().trackerId)
    }

    @Test fun theThirtyFirstOfMarchIsComparedWithTheEndOfFebruary() {
        val now = at("2026-03-31T20:00")
        val entries = listOf(Entry(trackerId = 1, amount = 50.0, occurredAt = at("2026-02-28T09:00")))
        assertEquals(50.0, Money.month(entries, now, zone).single().lastByNow, 0.001)
    }

    @Test fun aChartSurvivesBeingPinned() {
        val chart = Chart(
            bars = listOf(Bar("Groceries", 250.0, "250.00 EUR", limit = 300.0, note = "of 300.00 EUR this month"), Bar("Fun", 40.0, "40.00 EUR", compare = 55.0)),
            week = listOf(true, false, true, true, false, true, true),
            caption = "This month so far"
        )
        assertEquals(chart, Chart.fromJson(org.json.JSONObject(chart.toJson().toString())))
        assertNull(Chart.fromJson(Chart().toJson()))
        assertTrue(Bar("x", 12.0, "12", limit = 10.0).over)
    }

    @Test fun aToolsNumbersReachItsCard() {
        val chart = Chart(bars = listOf(Bar("Groceries", 250.0, "250.00 EUR", limit = 300.0)))
        val card = com.lukas.jarvis.moment.Cards.fromOutput(
            com.lukas.jarvis.llm.ToolOutput("tracker_status", "{}", "Groceries: 50.00 EUR of budget left this month", chart = chart)
        )
        assertEquals(chart, card.chart)
        val failed = com.lukas.jarvis.moment.Cards.fromOutput(
            com.lukas.jarvis.llm.ToolOutput("tracker_status", "{}", "Tool 'tracker_status' failed: boom", chart = chart)
        )
        assertNull(failed.chart)
    }
}
