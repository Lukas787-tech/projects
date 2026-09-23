package com.lukas.jarvis.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Calendar arithmetic, done by the calendar.
 *
 * "How many days until Christmas" is the question a language model gets wrong
 * most confidently: it has to know today's date, count across month lengths and
 * remember whether this is a leap year, and it will produce a round, plausible
 * number whether or not it managed any of that. java.time does it exactly.
 *
 * Every function returns a sentence, because every one of them is read out.
 */
object DateMath {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val LONG = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())

    /** "12-24" — a month and day with no year means the next one coming. */
    private val MONTH_DAY = Regex("^(\\d{1,2})-(\\d{1,2})$")

    /** "24.12." or "24.12" — the same, written the German way round. */
    private val DAY_MONTH = Regex("^(\\d{1,2})\\.(\\d{1,2})\\.?$")

    fun between(fromRaw: String?, toRaw: String?): String {
        val from = date(fromRaw) ?: return unreadable(fromRaw)
        val to = date(toRaw) ?: return unreadable(toRaw)
        val days = ChronoUnit.DAYS.between(from, to)
        val span = span(abs(days))
        val today = LocalDate.now(zone)
        return when {
            days == 0L -> "${spoken(to)} is the same day."
            from == today && days > 0 -> "${spoken(to)} is in $span."
            from == today -> "${spoken(to)} was $span ago."
            else -> "From ${spoken(from)} to ${spoken(to)} is $span" +
                (if (days < 0) ", counting backwards." else ".")
        }
    }

    fun add(fromRaw: String?, amount: Long, unitRaw: String?): String {
        val from = date(fromRaw) ?: return unreadable(fromRaw)
        val unit = unitRaw?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { "days" }
        val (result, word) = when {
            unit.startsWith("week") || unit == "w" -> from.plusWeeks(amount) to "week"
            unit.startsWith("month") -> from.plusMonths(amount) to "month"
            unit.startsWith("year") || unit == "y" -> from.plusYears(amount) to "year"
            unit.startsWith("day") || unit == "d" -> from.plusDays(amount) to "day"
            else -> return "I can count in days, weeks, months or years, not '$unitRaw'."
        }
        val count = abs(amount)
        val direction = if (amount >= 0) "after" else "before"
        return "$count $word${if (count == 1L) "" else "s"} $direction ${spoken(from)} " +
            "is ${spoken(result)}."
    }

    fun weekday(raw: String?): String {
        val day = date(raw) ?: return unreadable(raw)
        return "${spoken(day)}. The week number is ${
            day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        }."
    }

    /** Blank means today, which is what an omitted "from" always intends. */
    private fun date(raw: String?): LocalDate? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return LocalDate.now(zone)
        val today = LocalDate.now(zone)

        MONTH_DAY.find(text)?.let { match ->
            return nextOccurrence(today, match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
        DAY_MONTH.find(text)?.let { match ->
            return nextOccurrence(today, match.groupValues[2].toInt(), match.groupValues[1].toInt())
        }

        val millis = TimeUtil.parse(text) ?: return null
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    }

    private fun nextOccurrence(today: LocalDate, month: Int, day: Int): LocalDate? {
        val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (thisYear.isBefore(today)) thisYear.plusYears(1) else thisYear
    }

    private fun span(days: Long): String {
        val weeks = days / 7
        val rest = days % 7
        val plain = "$days day${if (days == 1L) "" else "s"}"
        if (weeks == 0L) return plain
        val broken = "$weeks week${if (weeks == 1L) "" else "s"}" +
            if (rest == 0L) "" else " and $rest day${if (rest == 1L) "" else "s"}"
        return "$plain ($broken)"
    }

    private fun spoken(date: LocalDate): String = date.format(LONG)

    private fun unreadable(raw: String?): String =
        "I could not read '$raw' as a date. Use a form like 2026-12-24, 24.12. or 'tomorrow'."
}
