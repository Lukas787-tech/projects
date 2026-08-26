package com.expensesplit.app.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Inclusive-start, inclusive-end day range used by every filter and report in the app. */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)

    val dayCount: Int get() = (endInclusive.toEpochDay() - start.toEpochDay()).toInt() + 1

    fun startMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
        start.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Exclusive upper bound, which is what the SQL `date < :end` comparisons want. */
    fun endExclusiveMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
        endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    companion object {
        fun thisWeek(today: LocalDate = LocalDate.now(), weekStart: DayOfWeek = DayOfWeek.MONDAY): DateRange {
            val start = today.with(TemporalAdjusters.previousOrSame(weekStart))
            return DateRange(start, start.plusDays(6))
        }

        fun thisMonth(today: LocalDate = LocalDate.now()): DateRange = ofMonth(YearMonth.from(today))

        fun thisYear(today: LocalDate = LocalDate.now()): DateRange =
            DateRange(today.withDayOfYear(1), today.withDayOfYear(today.lengthOfYear()))

        fun ofMonth(month: YearMonth): DateRange = DateRange(month.atDay(1), month.atEndOfMonth())

        fun yearToDate(today: LocalDate = LocalDate.now()): DateRange = DateRange(today.withDayOfYear(1), today)

        fun lastDays(days: Long, today: LocalDate = LocalDate.now()): DateRange =
            DateRange(today.minusDays(days - 1), today)
    }
}

fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

fun LocalDate.toEpochMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atStartOfDay(zone).toInstant().toEpochMilli()
