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

        // "every day except sunday", "weekdays but not friday": the days
        // after the exception are taken away, not added.
        val cut = EXCEPT.find(text)
        if (cut != null) {
            val before = text.substring(0, cut.range.first).trim()
            val after = text.substring(cut.range.last + 1)
            val base = parse(before).ifEmpty { ORDER.toSet() }
            val left = base - mentioned(after)
            return if (left.size == 7) emptySet() else left
        }

        if (WORKDAY_WORDS.any { text.contains(it) }) return WEEKDAYS
        if (WEEKEND_WORDS.any { text.contains(it) }) return WEEKEND
        val days = mentioned(text)
        return if (days.size == 7) emptySet() else days
    }

    /** The days named in [text], ranges included. */
    private fun mentioned(text: String): Set<Int> {
        val words = text.split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }
        // Two-letter abbreviations ("Mo, Mi, Fr") only count in a phrase
        // made of nothing else, so "do", "so" and "we" in a sentence are
        // words and not Thursday, Sunday and Wednesday.
        val terse = words.all { it in CONNECTORS || day(it, terse = true) != null }
        val days = mutableSetOf<Int>()
        // "mon-fri", "montag bis freitag", "monday to thursday"
        Regex("(\\p{L}+)\\s*(?:-|–|to|until|through|bis)\\s*(\\p{L}+)").findAll(text).forEach { match ->
            val from = day(match.groupValues[1], terse)
            val to = day(match.groupValues[2], terse)
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
        words.mapNotNullTo(days) { day(it, terse) }
        return days
    }

    /** One word as a day: "mon", "Mondays", "freitags", and "Mo" in a terse list. */
    private fun day(word: String, terse: Boolean): Int? {
        val clean = word.lowercase(Locale.ROOT).removeSuffix("s")
        if (clean.length < 2) return null
        if (!terse && (clean.length < 3 || clean in AMBIGUOUS)) return null
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

    private val EXCEPT = Regex("\\b(except|but not|apart from|excluding|außer|ausser|nicht am|ohne)\\b")
    private val CONNECTORS = setOf("and", "und", "to", "bis", "until", "through", "on", "am", "or", "oder")

    /** Words that start like a day but are ordinary words in a sentence. */
    private val AMBIGUOUS = setOf("mit", "son", "die", "don", "fre", "sam")

    private val EVERY = listOf("daily", "every day", "everyday", "all days", "täglich", "jeden tag", "each day")
    private val WORKDAY_WORDS = listOf("weekday", "week day", "workday", "work day", "werktag", "wochentag", "arbeitstag")
    private val WEEKEND_WORDS = listOf("weekend", "wochenende")
}
