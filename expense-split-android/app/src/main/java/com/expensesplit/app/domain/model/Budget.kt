package com.expensesplit.app.domain.model

import java.time.LocalDate

data class Budget(
    val id: Long = 0,
    /** null means an overall budget across every category. */
    val categoryId: Long?,
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val limitMinor: Long,
    val currency: String,
    val startDate: LocalDate,
    /** Percent of the limit at which a warning notification fires (default 80%). */
    val alertThresholdPercent: Int = 80,
    val active: Boolean = true,
)

/** A [Budget] evaluated against actual spend for the current period. */
data class BudgetProgress(
    val budget: Budget,
    val category: Category?,
    val spentMinor: Long,
    val limitMinor: Long,
    val currency: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val daysElapsed: Int,
    val daysInPeriod: Int,
) {
    val remainingMinor: Long get() = limitMinor - spentMinor
    val usedFraction: Float get() = if (limitMinor <= 0) 0f else spentMinor.toFloat() / limitMinor
    val isOverBudget: Boolean get() = spentMinor > limitMinor
    val isNearLimit: Boolean
        get() = !isOverBudget && usedFraction * 100 >= budget.alertThresholdPercent

    /** Straight-line spend projection for the end of the period. */
    val projectedSpendMinor: Long
        get() = if (daysElapsed <= 0) spentMinor
        else (spentMinor.toDouble() / daysElapsed * daysInPeriod).toLong()

    /** What can still be spent per remaining day without breaching the limit. */
    val safeDailyMinor: Long
        get() {
            val daysLeft = (daysInPeriod - daysElapsed).coerceAtLeast(1)
            return (remainingMinor / daysLeft).coerceAtLeast(0)
        }
}
