package com.lukas.jarvis.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The model is told the current local date and time in every request and is
 * asked to return absolute ISO timestamps, but spoken input produces sloppy
 * values, so parsing stays deliberately forgiving.
 */
object TimeUtil {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val DATE_TIME_FORMATS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "dd.MM.yyyy HH:mm",
        "MM/dd/yyyy HH:mm"
    )

    private val DATE_FORMATS = listOf("yyyy-MM-dd", "dd.MM.yyyy", "MM/dd/yyyy")

    private val RELATIVE = Regex("^\\+?(\\d+)\\s*(m|min|mins|minutes?|h|hours?|d|days?|w|weeks?)$")

    fun parse(raw: String?, now: Long = System.currentTimeMillis()): Long? {
        val text = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (text.isBlank()) return null

        when (text) {
            "now" -> return now
            "today" -> return atHour(LocalDate.now(zone), 9)
            "tonight" -> return atHour(LocalDate.now(zone), 20)
            "tomorrow" -> return atHour(LocalDate.now(zone).plusDays(1), 9)
            "next week" -> return atHour(LocalDate.now(zone).plusWeeks(1), 9)
        }

        RELATIVE.find(text)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return@let
            val millis = when (match.groupValues[2].first()) {
                'm' -> amount * 60_000
                'h' -> amount * 3_600_000
                'd' -> amount * 86_400_000
                'w' -> amount * 604_800_000
                else -> return@let
            }
            return now + millis
        }

        // Bare epoch values, in either unit.
        text.toLongOrNull()?.let { value ->
            return if (value > 100_000_000_000L) value else value * 1000
        }

        val cleaned = (raw ?: return null).trim().removeSuffix("Z")
        for (pattern in DATE_TIME_FORMATS) {
            try {
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
                return LocalDateTime.parse(cleaned, formatter).atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
                // Wrong shape for this pattern; fall through to the next one.
            }
        }
        for (pattern in DATE_FORMATS) {
            try {
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
                return atHour(LocalDate.parse(cleaned, formatter), 9)
            } catch (_: Exception) {
                // Wrong shape for this pattern; fall through to the next one.
            }
        }
        return null
    }

    private fun atHour(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** "Wed, 17 Sep 2026, 09:00" — what the model and the UI both read. */
    fun format(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp)
            .atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy, HH:mm", Locale.getDefault()))

    fun formatDate(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp)
            .atZone(zone)
            .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))

    fun formatTime(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp)
            .atZone(zone)
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    fun iso(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT))

    /** "2 hours ago", "in 3 days" — used in list rows. */
    fun relative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val delta = timestamp - now
        val future = delta > 0
        val seconds = kotlin.math.abs(delta) / 1000
        val text = when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60} min"
            seconds < 86_400 -> "${seconds / 3600} h"
            seconds < 2_592_000 -> "${seconds / 86_400} d"
            else -> formatDate(timestamp)
        }
        return when {
            text == "just now" -> text
            text.first().isDigit() && future -> "in $text"
            text.first().isDigit() -> "$text ago"
            else -> text
        }
    }

    /**
     * The next slot of a repeating time that is still ahead of [now]. A phone
     * that was off for a week must not ring seven missed days one after
     * another; it skips to the next one to come.
     */
    fun rollForward(from: Long, repeatRule: String, now: Long = System.currentTimeMillis()): Long? {
        var next = nextOccurrence(from, repeatRule) ?: return null
        var guard = 0
        while (next <= now && guard++ < 5000) {
            next = nextOccurrence(next, repeatRule) ?: return null
        }
        return next
    }

    fun nextOccurrence(from: Long, repeatRule: String): Long? {
        val base = Instant.ofEpochMilli(from).atZone(zone)
        return when (repeatRule) {
            "daily" -> base.plusDays(1)
            "weekly" -> base.plusWeeks(1)
            "monthly" -> base.plusMonths(1)
            else -> null
        }?.toInstant()?.toEpochMilli()
    }
}
