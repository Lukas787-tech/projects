package com.lukas.jarvis.auto

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The days of the week a timed routine keeps, as [Calendar.DAY_OF_WEEK]
 * values; an empty set is every day.
 *
 * "Weekdays at half seven" is how people schedule things, and a check on the
 * trains that also turns up on Sunday morning is one that gets switched off.
 */
object RoutineDays {

    val WEEKDAYS: Set<Int> = setOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY
    )
    val WEEKEND: Set<Int> = setOf(Calendar.SATURDAY, Calendar.SUNDAY)

    /** Monday first, the way a week is read in most of Europe. */
    private val ORDER = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    )

    private val NAMES: Map<Int, List<String>> = mapOf(
        Calendar.MONDAY to listOf("monday", "montag"),
        Calendar.TUESDAY to listOf("tuesday", "dienstag"),
        Calendar.WEDNESDAY to listOf("wednesday", "mittwoch"),
        Calendar.THURSDAY to listOf("thursday", "donnerstag"),
        Calendar.FRIDAY to listOf("friday", "freitag"),
        Calendar.SATURDAY to listOf("saturday", "samstag"),
        Calendar.SUNDAY to listOf("sunday", "sonntag")
    )

    private val PLURAL = listOf("Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")

    /** "weekdays", "weekends", "mon, wed, fri", "Mo-Fr", "täglich" -> the days. */
    fun parse(raw: String?): Set<Int> {
        val text = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (text.isBlank() || EVERY.any { text == it }) return emptySet()
        if (WORKDAY_WORDS.any { text.contains(it) }) return WEEKDAYS
        if (WEEKEND_WORDS.any { text.contains(it) }) return WEEKEND

        val days = mutableSetOf<Int>()
        // "mon-fri", "montag bis freitag", "monday to thursday"
        Regex("(\\p{L}+)\\s*(?:-|–|to|until|through|bis)\\s*(\\p{L}+)").findAll(text).forEach { match ->
            val from = day(match.groupValues[1])
            val to = day(match.groupValues[2])
            if (from != null && to != null) {
                var index = ORDER.indexOf(from)
                val end = ORDER.indexOf(to)
                repeat(7) {
                    days += ORDER[index]
                    if (index == end) return@forEach
                    index = (index + 1) % 7
                }
            }
        }
        text.split(Regex("[^\\p{L}]+")).mapNotNullTo(days) { day(it) }
        return if (days.size == 7) emptySet() else days
    }

    /** One word as a day: "mon", "Mondays", "Mo", "freitags". */
    private fun day(word: String): Int? {
        val clean = word.lowercase(Locale.ROOT).removeSuffix("s")
        if (clean.length < 2) return null
        return NAMES.entries.firstOrNull { (_, names) -> names.any { it.startsWith(clean) } }?.key
    }

    fun describe(days: Set<Int>): String = when {
        days.isEmpty() || days.size == 7 -> "every day"
        days == WEEKDAYS -> "on weekdays"
        days == WEEKEND -> "at weekends"
        else -> {
            val names = ORDER.filter { it in days }.map { PLURAL[ORDER.indexOf(it)] }
            "on " + if (names.size == 1) names.first() else names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    fun encode(days: Set<Int>): String = days.sorted().joinToString(",")

    fun decode(raw: String?): Set<Int> =
        raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()

    /** The next moment at [hour]:[minute] on one of [days], strictly after [now]. */
    fun next(
        now: Long,
        hour: Int,
        minute: Int,
        days: Set<Int>,
        zone: TimeZone = TimeZone.getDefault()
    ): Long {
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        repeat(7) {
            if (days.isEmpty() || calendar.get(Calendar.DAY_OF_WEEK) in days) return calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private val EVERY = listOf("daily", "every day", "everyday", "all days", "täglich", "jeden tag", "each day")
    private val WORKDAY_WORDS = listOf("weekday", "week day", "workday", "work day", "werktag", "wochentag", "arbeitstag")
    private val WEEKEND_WORDS = listOf("weekend", "wochenende")
}
