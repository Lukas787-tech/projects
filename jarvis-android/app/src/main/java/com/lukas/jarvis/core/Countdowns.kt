package com.lukas.jarvis.core

import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A day worth counting down to: a holiday, an exam, a birthday. A [yearly]
 * one comes round again every year, and when the first year is known, it
 * says how many years it will be — "Mum turns 60".
 */
data class Countdown(
    val id: Long,
    val name: String,
    /** The day itself; for a yearly one, any year it fell on (the first, if known). */
    val date: LocalDate,
    val yearly: Boolean = false,
    /** For a yearly one: whether [date]'s year is real, so the years can be counted. */
    val knowsYear: Boolean = false,
    /** A birthday rather than an anniversary: "turns 60" rather than "60 years". */
    val birthday: Boolean = false
) {

    /** The next time the day comes, today included; null once a one-off has passed. */
    fun next(today: LocalDate): LocalDate? {
        if (!yearly) return date.takeIf { !it.isBefore(today) }
        var candidate = onYear(today.year)
        if (candidate.isBefore(today)) candidate = onYear(today.year + 1)
        return candidate
    }

    fun daysLeft(today: LocalDate): Long? = next(today)?.let { ChronoUnit.DAYS.between(today, it) }

    /** How many years it will be on the next occasion, when that is known. */
    fun yearsAt(today: LocalDate): Int? {
        if (!yearly || !knowsYear) return null
        val next = next(today) ?: return null
        return (next.year - date.year).takeIf { it > 0 }
    }

    /** 29 February, in a year without one, falls on the 28th. */
    private fun onYear(year: Int): LocalDate =
        if (date.monthValue == 2 && date.dayOfMonth == 29 && !java.time.Year.isLeap(year.toLong())) {
            LocalDate.of(year, 2, 28)
        } else {
            date.withYear(year)
        }

    /** "Holiday in 17 days", "Mum turns 60 tomorrow", "Exam today". */
    fun describe(today: LocalDate): String {
        val days = daysLeft(today) ?: return "$name was on $date"
        val years = yearsAt(today)
        val `when` = when (days) {
            0L -> "today"
            1L -> "tomorrow"
            else -> "in $days days"
        }
        return when {
            years != null && birthday -> "$name turns $years ${`when`}"
            years != null -> "$name: $years years ${`when`}"
            birthday -> "$name's birthday ${`when`}"
            else -> "$name ${`when`}"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("date", date.toString())
        .put("yearly", yearly).put("knowsYear", knowsYear).put("birthday", birthday)

    companion object {
        fun fromJson(o: JSONObject): Countdown? = runCatching {
            Countdown(
                id = o.getLong("id"),
                name = o.getString("name").trim().ifBlank { return null },
                date = LocalDate.parse(o.getString("date")),
                yearly = o.optBoolean("yearly"),
                knowsYear = o.optBoolean("knowsYear"),
                birthday = o.optBoolean("birthday")
            )
        }.getOrNull()

        /** The ones still to come, soonest first. */
        fun upcoming(all: List<Countdown>, today: LocalDate): List<Countdown> =
            all.filter { it.next(today) != null }.sortedBy { it.daysLeft(today) }

        /**
         * "2026-10-12", "12.10.2026", "12.10.", "10-12", "12 October", "October 12 1966" ->
         * the day and whether a year was given. Null for anything else.
         */
        fun parseDay(raw: String, today: LocalDate): Pair<LocalDate, Boolean>? {
            val text = raw.trim().lowercase().replace(Regex("(\\d)(st|nd|rd|th)\\b"), "$1").replace(",", " ")
            runCatching { return LocalDate.parse(text) to true }
            Regex("^(\\d{1,2})\\.(\\d{1,2})\\.?(\\d{4})?$").find(text)?.let { m ->
                return safe(m.groupValues[3].toIntOrNull(), m.groupValues[2].toInt(), m.groupValues[1].toInt(), today)
            }
            Regex("^(\\d{1,2})-(\\d{1,2})$").find(text)?.let { m ->
                return safe(null, m.groupValues[1].toInt(), m.groupValues[2].toInt(), today)
            }
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() && it != "of" }
            val month = words.firstNotNullOfOrNull { w -> MONTHS.entries.firstOrNull { w.startsWith(it.key) }?.value } ?: return null
            val numbers = words.mapNotNull { it.trimEnd('.').toIntOrNull() }
            val day = numbers.firstOrNull { it in 1..31 } ?: return null
            val year = numbers.firstOrNull { it in 1000..9999 }
            return safe(year, month, day, today)
        }

        private fun safe(year: Int?, month: Int, day: Int, today: LocalDate): Pair<LocalDate, Boolean>? = runCatching {
            if (year != null) return@runCatching LocalDate.of(year, month, day) to true
            // No year: the next time it comes; a leap day, in the next leap year.
            (today.year..today.year + 8).asSequence()
                .filter { y -> !(month == 2 && day == 29) || java.time.Year.isLeap(y.toLong()) }
                .map { y -> LocalDate.of(y, month, day) }
                .firstOrNull { !it.isBefore(today) }
                ?.let { it to false }
        }.getOrNull()

        private val MONTHS = linkedMapOf(
            "jan" to 1, "feb" to 2, "mär" to 3, "mar" to 3, "apr" to 4, "may" to 5, "mai" to 5,
            "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "okt" to 10, "nov" to 11,
            "dec" to 12, "dez" to 12
        )
    }
}
