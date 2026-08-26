package com.expensesplit.app.domain.model

import java.time.LocalDate

data class RecurringRule(
    val id: Long = 0,
    val title: String,
    val categoryId: Long,
    val amountMinor: Long,
    val currency: String,
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val merchant: String? = null,
    val frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    /** Every N periods — `interval = 2` with WEEKLY means fortnightly. */
    val interval: Int = 1,
    val nextRunDate: LocalDate,
    val endDate: LocalDate? = null,
    val lastRunDate: LocalDate? = null,
    val active: Boolean = true,
) {
    fun advanceFrom(date: LocalDate): LocalDate = frequency.advance(date, interval)

    fun hasEnded(on: LocalDate): Boolean = endDate?.isBefore(on) == true
}

/**
 * Next occurrence after [from], stepping [interval] periods.
 *
 * Month and year steps use `java.time`'s own clamping, so a rule anchored on the 31st lands on the
 * 30th (or the 28th) in shorter months instead of overflowing into the next one.
 */
fun RecurrenceFrequency.advance(from: LocalDate, interval: Int = 1): LocalDate {
    val step = interval.coerceAtLeast(1).toLong()
    return when (this) {
        RecurrenceFrequency.DAILY -> from.plusDays(step)
        RecurrenceFrequency.WEEKLY -> from.plusWeeks(step)
        RecurrenceFrequency.MONTHLY -> from.plusMonths(step)
        RecurrenceFrequency.YEARLY -> from.plusYears(step)
    }
}
