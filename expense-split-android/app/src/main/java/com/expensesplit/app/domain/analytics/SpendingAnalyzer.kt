package com.expensesplit.app.domain.analytics

import com.expensesplit.app.core.DateRange
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.CategorySpend
import com.expensesplit.app.domain.model.DailySpend
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.SpendingReport
import java.time.LocalDate

/**
 * Builds the numbers behind the Analytics and Recap screens.
 *
 * Everything works off `baseAmountMinor` so a report can mix currencies without lying: an expense
 * paid in EUR is compared against one paid in USD using the rate captured when it was recorded.
 */
object SpendingAnalyzer {

    fun buildReport(
        range: DateRange,
        expenses: List<Expense>,
        categories: Map<Long, Category>,
        baseCurrency: String,
        previousPeriodExpenses: List<Expense> = emptyList(),
    ): SpendingReport {
        val total = expenses.sumOf { it.baseAmountMinor }
        val byCategory = categorySpend(expenses, categories, baseCurrency, total)
        val byDay = dailySpend(range, expenses)

        return SpendingReport(
            range = range,
            currency = baseCurrency,
            totalMinor = total,
            transactionCount = expenses.size,
            averagePerDayMinor = if (range.dayCount <= 0) 0 else total / range.dayCount,
            averagePerTransactionMinor = if (expenses.isEmpty()) 0 else total / expenses.size,
            largestExpense = expenses.maxByOrNull { it.baseAmountMinor },
            byCategory = byCategory,
            byDay = byDay,
            previousPeriodTotalMinor = previousPeriodExpenses.sumOf { it.baseAmountMinor },
        )
    }

    fun categorySpend(
        expenses: List<Expense>,
        categories: Map<Long, Category>,
        baseCurrency: String,
        totalOverride: Long? = null,
    ): List<CategorySpend> {
        val total = totalOverride ?: expenses.sumOf { it.baseAmountMinor }
        return expenses.groupBy { it.categoryId }
            .map { (categoryId, group) ->
                val categoryTotal = group.sumOf { it.baseAmountMinor }
                CategorySpend(
                    category = categories[categoryId],
                    categoryId = categoryId,
                    totalMinor = categoryTotal,
                    transactionCount = group.size,
                    currency = baseCurrency,
                    shareOfTotal = if (total <= 0) 0f else categoryTotal.toFloat() / total,
                )
            }
            .sortedByDescending { it.totalMinor }
    }

    /** One entry per day in [range], including zero-spend days so charts keep an even x-axis. */
    fun dailySpend(range: DateRange, expenses: List<Expense>): List<DailySpend> {
        val totals = expenses.groupBy { it.date }.mapValues { (_, group) -> group.sumOf { it.baseAmountMinor } }
        return generateSequence(range.start) { current ->
            val next = current.plusDays(1)
            if (next.isAfter(range.endInclusive)) null else next
        }.map { date -> DailySpend(date, totals[date] ?: 0L) }.toList()
    }

    /** The period of equal length immediately before [range], for month-over-month comparisons. */
    fun previousRange(range: DateRange): DateRange {
        val length = range.dayCount.toLong()
        val end = range.start.minusDays(1)
        return DateRange(end.minusDays(length - 1), end)
    }

    fun weekdayAverages(expenses: List<Expense>): Map<java.time.DayOfWeek, Long> =
        expenses.groupBy { it.date.dayOfWeek }
            .mapValues { (_, group) -> group.sumOf { it.baseAmountMinor } / group.size.coerceAtLeast(1) }

    fun daysWithoutSpending(range: DateRange, expenses: List<Expense>): Int {
        val spentDays = expenses.map { it.date }.toSet()
        var count = 0
        var cursor: LocalDate = range.start
        while (!cursor.isAfter(range.endInclusive)) {
            if (cursor !in spentDays) count++
            cursor = cursor.plusDays(1)
        }
        return count
    }
}
