package com.lukas.jarvis.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How a habit is going: days in a row now, the best run, whether today is done, and this week. */
data class Streak(
    val current: Int,
    val best: Int,
    val today: Boolean,
    /** The last seven days, oldest first, today last. */
    val week: List<Boolean>
) {
    val daysThisWeek: Int get() = week.count { it }
}

/**
 * Habits are the trackers that count things done — glasses of water, runs,
 * pages — rather than money. A day counts once anything was logged on it.
 */
object Streaks {

    fun isHabit(tracker: Tracker): Boolean = tracker.kind != Tracker.KIND_MONEY

    fun days(entries: List<Entry>, zone: ZoneId = ZoneId.systemDefault()): Set<LocalDate> =
        entries.mapTo(HashSet()) { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }

    /**
     * A streak survives until a day is missed: one that ran up to yesterday is
     * still going this morning, waiting for today.
     */
    fun of(days: Set<LocalDate>, today: LocalDate): Streak {
        val doneToday = today in days
        var cursor = if (doneToday) today else today.minusDays(1)
        var current = 0
        while (cursor in days) {
            current++
            cursor = cursor.minusDays(1)
        }
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        days.sorted().forEach { day ->
            run = if (previous != null && previous!!.plusDays(1) == day) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        val week = (6 downTo 0).map { today.minusDays(it.toLong()) in days }
        return Streak(current = current, best = maxOf(best, current), today = doneToday, week = week)
    }

    /** "5 days in a row", for a card or a reply; empty when there is no run to speak of. */
    fun describe(streak: Streak): String = when {
        streak.current >= 2 && streak.current == streak.best && streak.best >= 3 -> "${streak.current} days in a row — your best yet"
        streak.current >= 2 -> "${streak.current} days in a row"
        else -> ""
    }
}
