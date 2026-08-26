package com.expensesplit.app.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class DateRangeTest {

    @Test
    fun `this week starts on the configured day`() {
        val wednesday = LocalDate.of(2026, 4, 15)
        val week = DateRange.thisWeek(wednesday, DayOfWeek.MONDAY)

        assertThat(week.start).isEqualTo(LocalDate.of(2026, 4, 13))
        assertThat(week.endInclusive).isEqualTo(LocalDate.of(2026, 4, 19))
        assertThat(week.dayCount).isEqualTo(7)
    }

    @Test
    fun `month range covers the whole month including leap days`() {
        val february = DateRange.ofMonth(YearMonth.of(2028, 2))

        assertThat(february.start).isEqualTo(LocalDate.of(2028, 2, 1))
        assertThat(february.endInclusive).isEqualTo(LocalDate.of(2028, 2, 29))
        assertThat(february.dayCount).isEqualTo(29)
    }

    @Test
    fun `contains is inclusive at both ends`() {
        val range = DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))

        assertThat(LocalDate.of(2026, 1, 1) in range).isTrue()
        assertThat(LocalDate.of(2026, 1, 31) in range).isTrue()
        assertThat(LocalDate.of(2025, 12, 31) in range).isFalse()
        assertThat(LocalDate.of(2026, 2, 1) in range).isFalse()
    }

    @Test
    fun `last days counts the current day as one of them`() {
        val range = DateRange.lastDays(7, LocalDate.of(2026, 4, 15))

        assertThat(range.start).isEqualTo(LocalDate.of(2026, 4, 9))
        assertThat(range.endInclusive).isEqualTo(LocalDate.of(2026, 4, 15))
        assertThat(range.dayCount).isEqualTo(7)
    }
}
