package com.expensesplit.app.domain

import com.expensesplit.app.domain.model.RecurrenceFrequency
import com.expensesplit.app.domain.model.RecurringRule
import com.expensesplit.app.domain.model.advance
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class RecurringRuleTest {

    private fun rule(
        frequency: RecurrenceFrequency,
        interval: Int = 1,
        next: LocalDate = LocalDate.of(2026, 1, 31),
        end: LocalDate? = null,
    ) = RecurringRule(
        id = 1,
        title = "Rent",
        categoryId = 5,
        amountMinor = 100_000,
        currency = "USD",
        frequency = frequency,
        interval = interval,
        nextRunDate = next,
        endDate = end,
    )

    @Test
    fun `daily and weekly rules step by the interval`() {
        val start = LocalDate.of(2026, 4, 1)

        assertThat(RecurrenceFrequency.DAILY.advance(start)).isEqualTo(LocalDate.of(2026, 4, 2))
        assertThat(RecurrenceFrequency.WEEKLY.advance(start, 2)).isEqualTo(LocalDate.of(2026, 4, 15))
    }

    @Test
    fun `a month-end rule clamps into shorter months instead of overflowing`() {
        val january31 = LocalDate.of(2026, 1, 31)

        val february = RecurrenceFrequency.MONTHLY.advance(january31)
        assertThat(february).isEqualTo(LocalDate.of(2026, 2, 28))
    }

    @Test
    fun `yearly rules land on the same date the following year`() {
        val start = LocalDate.of(2026, 6, 15)
        assertThat(RecurrenceFrequency.YEARLY.advance(start)).isEqualTo(LocalDate.of(2027, 6, 15))
    }

    @Test
    fun `a zero interval is treated as one so a rule can never loop forever`() {
        val start = LocalDate.of(2026, 4, 1)
        assertThat(RecurrenceFrequency.DAILY.advance(start, 0)).isEqualTo(LocalDate.of(2026, 4, 2))
    }

    @Test
    fun `advanceFrom uses the rule's own frequency and interval`() {
        val monthly = rule(RecurrenceFrequency.MONTHLY, interval = 3, next = LocalDate.of(2026, 1, 15))
        assertThat(monthly.advanceFrom(monthly.nextRunDate)).isEqualTo(LocalDate.of(2026, 4, 15))
    }

    @Test
    fun `a rule is finished once the date passes its end date`() {
        val ending = rule(RecurrenceFrequency.MONTHLY, end = LocalDate.of(2026, 6, 30))

        assertThat(ending.hasEnded(LocalDate.of(2026, 6, 1))).isFalse()
        assertThat(ending.hasEnded(LocalDate.of(2026, 7, 1))).isTrue()
    }

    @Test
    fun `a rule with no end date never finishes`() {
        assertThat(rule(RecurrenceFrequency.MONTHLY).hasEnded(LocalDate.of(2099, 1, 1))).isFalse()
    }
}
