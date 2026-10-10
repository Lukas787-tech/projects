package com.lukas.jarvis.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Something logged by itself on a schedule: the rent, a subscription, pocket
 * money, a salary. Each time it falls due it becomes an ordinary [Entry],
 * dated the day it was due, so a phone that was off for a week still ends up
 * with the right entries on the right days.
 */
data class Recurring(
    val id: Long = 0,
    val trackerId: Long,
    val amount: Double,
    val direction: String = Entry.DIR_OUT,
    val note: String? = null,
    val every: String = EVERY_MONTH,
    /** When the first one was due. Every later one keeps its day and time. */
    val anchor: Long,
    /** How many have been logged so far; the next is due at occurrence [logged]. */
    val logged: Int = 0,
    val nextAt: Long = anchor,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val EVERY_DAY = "daily"
        const val EVERY_WEEK = "weekly"
        const val EVERY_MONTH = "monthly"
        const val EVERY_YEAR = "yearly"

        /** "monthly", "every month", "a month", "monatlich" → the rule's word, or null. */
        fun parseEvery(raw: String): String? {
            val s = raw.trim().lowercase(Locale.ROOT)
            return when {
                s.isBlank() -> null
                Regex("dai|day|täglich|tag").containsMatchIn(s) -> EVERY_DAY
                Regex("week|wöchentlich|woche").containsMatchIn(s) -> EVERY_WEEK
                Regex("month|monat").containsMatchIn(s) -> EVERY_MONTH
                Regex("year|annual|jähr|jahr").containsMatchIn(s) -> EVERY_YEAR
                else -> null
            }
        }

        fun word(every: String): String = when (every) {
            EVERY_DAY -> "every day"
            EVERY_WEEK -> "every week"
            EVERY_YEAR -> "every year"
            else -> "every month"
        }
    }
}

/** The start and end of a tracker's current budget window. */
data class Window(val start: Long, val end: Long)

/** One tracker's month so far, next to the same days of last month. */
data class MonthLine(
    val trackerId: Long,
    val out: Double,
    val income: Double,
    val count: Int,
    /** What had gone out of it by this day last month. */
    val lastByNow: Double,
    /** All of last month. */
    val lastTotal: Double
)

object Money {

    /** The hour a schedule given only as a day falls due: early, before the day's spending. */
    private val DUE_TIME: LocalTime = LocalTime.of(8, 0)

    /**
     * The [n]-th time a rule falls due, counted from its first. Always worked
     * out from the first rather than step by step, so a rent due on the 31st is
     * due on the 30th in April and back on the 31st in May.
     */
    fun occurrence(anchor: Long, every: String, n: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val first = Instant.ofEpochMilli(anchor).atZone(zone)
        val at = when (every) {
            Recurring.EVERY_DAY -> first.plusDays(n.toLong())
            Recurring.EVERY_WEEK -> first.plusWeeks(n.toLong())
            Recurring.EVERY_YEAR -> first.plusYears(n.toLong())
            else -> first.plusMonths(n.toLong())
        }
        return at.toInstant().toEpochMilli()
    }

    /**
     * The times [rule] fell due up to [now] that are not logged yet, oldest
     * first, at most [cap] of them, so a rule set up years in the past cannot
     * flood a tracker.
     */
    fun due(rule: Recurring, now: Long, zone: ZoneId = ZoneId.systemDefault(), cap: Int = 62): List<Long> {
        if (!rule.active) return emptyList()
        val out = mutableListOf<Long>()
        var n = rule.logged
        while (out.size < cap) {
            val at = occurrence(rule.anchor, rule.every, n, zone)
            if (at > now) break
            out += at
            n++
        }
        return out
    }

    /**
     * When the first one falls due. A [day] of the month ("1", "15th") or of
     * the week ("friday") picks the next such day, today included; otherwise
     * [starting] if one was given, else right now.
     */
    fun firstDue(
        every: String,
        day: String?,
        starting: Long?,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        if (starting != null) return starting
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val text = day?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (text.isEmpty()) return now
        val date: LocalDate? = when (every) {
            Recurring.EVERY_WEEK -> weekday(text)?.let { today.with(TemporalAdjusters.nextOrSame(it)) }
            Recurring.EVERY_MONTH, Recurring.EVERY_YEAR -> Regex("\\d{1,2}").find(text)?.value?.toIntOrNull()
                ?.takeIf { it in 1..31 }
                ?.let { d ->
                    val thisMonth = today.withDayOfMonth(minOf(d, today.lengthOfMonth()))
                    if (!thisMonth.isBefore(today)) thisMonth else {
                        val next = today.plusMonths(1)
                        next.withDayOfMonth(minOf(d, next.lengthOfMonth()))
                    }
                }
            else -> null
        }
        return date?.atTime(DUE_TIME)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: now
    }

    private fun weekday(text: String): DayOfWeek? {
        val names = mapOf(
            "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY, "mo" to DayOfWeek.MONDAY, "di" to DayOfWeek.TUESDAY,
            "mi" to DayOfWeek.WEDNESDAY, "do" to DayOfWeek.THURSDAY, "fr" to DayOfWeek.FRIDAY,
            "sa" to DayOfWeek.SATURDAY, "so" to DayOfWeek.SUNDAY
        )
        return names.entries.sortedByDescending { it.key.length }.firstOrNull { text.startsWith(it.key) }?.value
    }

    /** The budget window a [period] is in at [now]; null for a tracker that never resets. */
    fun window(
        period: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        firstDay: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    ): Window? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val (start, end) = when (period) {
            Tracker.PERIOD_DAILY -> today to today.plusDays(1)
            Tracker.PERIOD_WEEKLY -> today.with(TemporalAdjusters.previousOrSame(firstDay)).let { it to it.plusWeeks(1) }
            Tracker.PERIOD_MONTHLY -> today.withDayOfMonth(1).let { it to it.plusMonths(1) }
            else -> return null
        }
        return Window(start.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli())
    }

    /** Whole days left in [window] after today, for "with 9 days to go". */
    fun daysLeft(window: Window, now: Long): Int =
        (((window.end - now) / 86_400_000L).toInt()).coerceAtLeast(0)

    /**
     * What to say when an entry carried a budget past four fifths of itself,
     * or past all of it; null when it crossed neither. Only the crossing is
     * news, so the warning comes once a period rather than on every coffee.
     */
    fun budgetNews(
        budget: Double,
        before: Double,
        after: Double,
        period: String,
        window: Window?,
        now: Long,
        format: (Double) -> String
    ): String? {
        if (budget <= 0.0 || after <= before) return null
        val span = Tracker.thisPeriod(period)
        if (after > budget && before <= budget) {
            return "That's ${format(after - budget)} over $span's budget."
        }
        if (after >= budget * WARN_AT && before < budget * WARN_AT && after <= budget) {
            val percent = (after / budget * 100).roundToInt()
            val left = window?.takeIf { period != Tracker.PERIOD_DAILY }?.let { daysLeft(it, now) }
            val rest = when (left) {
                null -> ""
                0 -> ", on its last day"
                1 -> ", with a day to go"
                else -> ", with $left days to go"
            }
            return "Heads up: that's $percent% of $span's budget$rest."
        }
        return null
    }

    /**
     * Where a budget is heading at the pace so far, when that is clearly over
     * it. Says nothing in the first quarter of a window, when one big shop
     * would make any pace look alarming.
     */
    fun pace(spent: Double, budget: Double, window: Window, now: Long): Double? {
        if (budget <= 0.0 || spent <= 0.0 || spent > budget) return null
        val length = (window.end - window.start).toDouble()
        val gone = ((now - window.start) / length).coerceIn(0.0, 1.0)
        if (gone < 0.25) return null
        val projected = spent / gone
        return projected.takeIf { it > budget * 1.1 }
    }

    /**
     * This month so far for each tracker that moved, next to the same days of
     * last month and all of last month. [entries] must reach back to the start
     * of last month.
     */
    fun month(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<MonthLine> {
        val today: ZonedDateTime = Instant.ofEpochMilli(now).atZone(zone)
        val thisStart = today.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
        val lastStart = thisStart.minusMonths(1)
        // The same point in last month, clamped: the 31st of March is "by the 28th" in February.
        val lastMonth = lastStart.toLocalDate()
        val lastByNow = lastMonth.withDayOfMonth(minOf(today.dayOfMonth, lastMonth.lengthOfMonth()))
            .atTime(today.toLocalTime())
            .atZone(zone)
        val thisFrom = thisStart.toInstant().toEpochMilli()
        val lastFrom = lastStart.toInstant().toEpochMilli()
        val lastCut = lastByNow.toInstant().toEpochMilli()
        return entries.groupBy { it.trackerId }.mapNotNull { (id, rows) ->
            val current = rows.filter { it.occurredAt in thisFrom..now }
            val previous = rows.filter { it.occurredAt in lastFrom until thisFrom && it.direction == Entry.DIR_OUT }
            if (current.isEmpty() && previous.isEmpty()) return@mapNotNull null
            MonthLine(
                trackerId = id,
                out = current.filter { it.direction == Entry.DIR_OUT }.sumOf { it.amount },
                income = current.filter { it.direction == Entry.DIR_IN }.sumOf { it.amount },
                count = current.size,
                lastByNow = previous.filter { it.occurredAt <= lastCut }.sumOf { it.amount },
                lastTotal = previous.sumOf { it.amount }
            )
        }.sortedByDescending { it.out }
    }

    /** Where in a window the warning comes: at four fifths of the budget. */
    const val WARN_AT = 0.8
}
