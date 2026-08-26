package com.expensesplit.app.domain.analytics

import com.expensesplit.app.core.DateRange
import com.expensesplit.app.domain.model.Budget
import com.expensesplit.app.domain.model.BudgetPeriod
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.DailySpend
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.TrendDirection
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class SpendingAnalyzerTest {

    private val groceries = Category(2, "groceries", null, "Groceries", 0, "cart")
    private val dining = Category(3, "dining", null, "Dining", 0, "restaurant")
    private val categories = mapOf(groceries.id to groceries, dining.id to dining)

    private fun expense(day: Int, categoryId: Long, minor: Long) = Expense(
        id = day.toLong() * 100 + categoryId,
        title = "Expense $day",
        categoryId = categoryId,
        amountMinor = minor,
        currency = "USD",
        baseAmountMinor = minor,
        date = LocalDate.of(2026, 4, day),
    )

    private val april = DateRange.ofMonth(java.time.YearMonth.of(2026, 4))

    @Test
    fun `report totals and averages match the underlying expenses`() {
        val expenses = listOf(
            expense(1, groceries.id, 5000),
            expense(2, dining.id, 3000),
            expense(3, groceries.id, 2000),
        )

        val report = SpendingAnalyzer.buildReport(april, expenses, categories, "USD")

        assertThat(report.totalMinor).isEqualTo(10_000)
        assertThat(report.transactionCount).isEqualTo(3)
        assertThat(report.averagePerTransactionMinor).isEqualTo(3333)
        assertThat(report.averagePerDayMinor).isEqualTo(10_000 / 30)
    }

    @Test
    fun `category breakdown is ordered by spend and shares add up`() {
        val expenses = listOf(
            expense(1, groceries.id, 7000),
            expense(2, dining.id, 3000),
        )

        val report = SpendingAnalyzer.buildReport(april, expenses, categories, "USD")

        assertThat(report.byCategory.first().categoryId).isEqualTo(groceries.id)
        assertThat(report.byCategory.first().shareOfTotal).isWithin(0.001f).of(0.7f)
        assertThat(report.byCategory.sumOf { it.totalMinor }).isEqualTo(report.totalMinor)
        assertThat(report.topCategory?.categoryId).isEqualTo(groceries.id)
    }

    @Test
    fun `daily series includes zero-spend days so charts keep an even axis`() {
        val expenses = listOf(expense(1, groceries.id, 5000))
        val report = SpendingAnalyzer.buildReport(april, expenses, categories, "USD")

        assertThat(report.byDay).hasSize(30)
        assertThat(report.byDay.first().totalMinor).isEqualTo(5000)
        assertThat(report.byDay.count { it.totalMinor == 0L }).isEqualTo(29)
    }

    @Test
    fun `change percent compares against the previous period`() {
        val current = listOf(expense(1, groceries.id, 12_000))
        val previous = listOf(
            Expense(
                id = 99,
                title = "March",
                categoryId = groceries.id,
                amountMinor = 10_000,
                currency = "USD",
                baseAmountMinor = 10_000,
                date = LocalDate.of(2026, 3, 15),
            ),
        )

        val report = SpendingAnalyzer.buildReport(april, current, categories, "USD", previous)

        assertThat(report.changePercent).isWithin(0.01f).of(20f)
    }

    @Test
    fun `an empty period reports zeroes rather than dividing by zero`() {
        val report = SpendingAnalyzer.buildReport(april, emptyList(), categories, "USD")

        assertThat(report.totalMinor).isEqualTo(0)
        assertThat(report.averagePerTransactionMinor).isEqualTo(0)
        assertThat(report.changePercent).isEqualTo(0f)
        assertThat(report.largestExpense).isNull()
    }

    @Test
    fun `previous range has the same length and ends the day before`() {
        val previous = SpendingAnalyzer.previousRange(april)

        assertThat(previous.endInclusive).isEqualTo(LocalDate.of(2026, 3, 31))
        assertThat(previous.dayCount).isEqualTo(april.dayCount)
    }

    @Test
    fun `counts days with no spending`() {
        val expenses = listOf(expense(1, groceries.id, 100), expense(2, dining.id, 100))
        assertThat(SpendingAnalyzer.daysWithoutSpending(april, expenses)).isEqualTo(28)
    }
}

class TrendForecasterTest {

    private fun series(vararg values: Long): List<DailySpend> =
        values.mapIndexed { index, value -> DailySpend(LocalDate.of(2026, 4, index + 1), value) }

    @Test
    fun `a rising series is reported as rising`() {
        val trend = TrendForecaster.forecast(series(100, 200, 300, 400, 500, 600), 7)

        assertThat(trend.direction).isEqualTo(TrendDirection.RISING)
        assertThat(trend.slopePerDayMinor).isGreaterThan(0.0)
        assertThat(trend.confidence).isGreaterThan(0.9f)
    }

    @Test
    fun `a falling series is reported as falling`() {
        val trend = TrendForecaster.forecast(series(600, 500, 400, 300, 200, 100), 7)

        assertThat(trend.direction).isEqualTo(TrendDirection.FALLING)
        assertThat(trend.slopePerDayMinor).isLessThan(0.0)
    }

    @Test
    fun `a flat series is reported as stable`() {
        val trend = TrendForecaster.forecast(series(300, 300, 300, 300, 300, 300), 7)
        assertThat(trend.direction).isEqualTo(TrendDirection.STABLE)
    }

    @Test
    fun `noisy data yields low confidence`() {
        val trend = TrendForecaster.forecast(series(10, 900, 20, 850, 30, 880), 7)
        assertThat(trend.confidence).isLessThan(0.5f)
    }

    @Test
    fun `too few points produce a stable zero-confidence result`() {
        val trend = TrendForecaster.forecast(series(100, 200), 7)

        assertThat(trend.direction).isEqualTo(TrendDirection.STABLE)
        assertThat(trend.confidence).isEqualTo(0f)
    }

    @Test
    fun `the projection is never negative`() {
        val trend = TrendForecaster.forecast(series(1000, 800, 600, 400, 200, 100), 30)
        assertThat(trend.projectedNextPeriodMinor).isAtLeast(0)
    }
}

class BudgetEvaluatorTest {

    private val groceries = Category(2, "groceries", null, "Groceries", 0, "cart")

    private fun expense(day: Int, minor: Long, categoryId: Long = groceries.id) = Expense(
        id = day.toLong(),
        title = "Shop",
        categoryId = categoryId,
        amountMinor = minor,
        currency = "USD",
        baseAmountMinor = minor,
        date = LocalDate.of(2026, 4, day),
    )

    private val budget = Budget(
        id = 1,
        categoryId = groceries.id,
        period = BudgetPeriod.MONTHLY,
        limitMinor = 30_000,
        currency = "USD",
        startDate = LocalDate.of(2026, 4, 1),
        alertThresholdPercent = 80,
    )

    @Test
    fun `progress reflects spend within the current period only`() {
        val expenses = listOf(
            expense(2, 10_000),
            expense(5, 5000),
            // Previous month, must not count.
            expense(5, 9999).copy(date = LocalDate.of(2026, 3, 5)),
        )

        val progress = BudgetEvaluator.evaluate(budget, expenses, groceries, LocalDate.of(2026, 4, 10))

        assertThat(progress.spentMinor).isEqualTo(15_000)
        assertThat(progress.usedFraction).isWithin(0.001f).of(0.5f)
        assertThat(progress.isOverBudget).isFalse()
    }

    @Test
    fun `only the budgeted category is counted`() {
        val expenses = listOf(expense(2, 10_000), expense(3, 8000, categoryId = 99))

        val progress = BudgetEvaluator.evaluate(budget, expenses, groceries, LocalDate.of(2026, 4, 10))

        assertThat(progress.spentMinor).isEqualTo(10_000)
    }

    @Test
    fun `an overall budget counts every category`() {
        val overall = budget.copy(categoryId = null)
        val expenses = listOf(expense(2, 10_000), expense(3, 8000, categoryId = 99))

        val progress = BudgetEvaluator.evaluate(overall, expenses, null, LocalDate.of(2026, 4, 10))

        assertThat(progress.spentMinor).isEqualTo(18_000)
    }

    @Test
    fun `crossing the limit flags an overspend`() {
        val progress = BudgetEvaluator.evaluate(
            budget,
            listOf(expense(2, 35_000)),
            groceries,
            LocalDate.of(2026, 4, 10),
        )

        assertThat(progress.isOverBudget).isTrue()
        assertThat(progress.remainingMinor).isEqualTo(-5000)
        assertThat(progress.safeDailyMinor).isEqualTo(0)
    }

    @Test
    fun `the near-limit warning fires at the configured threshold`() {
        val progress = BudgetEvaluator.evaluate(
            budget,
            listOf(expense(2, 25_000)),
            groceries,
            LocalDate.of(2026, 4, 10),
        )

        assertThat(progress.isNearLimit).isTrue()
        assertThat(progress.isOverBudget).isFalse()
    }

    @Test
    fun `projection extrapolates the current pace across the period`() {
        // 9000 over the first 9 days projects to 30000 across 30 days.
        val progress = BudgetEvaluator.evaluate(
            budget,
            listOf(expense(1, 9000)),
            groceries,
            LocalDate.of(2026, 4, 9),
        )

        assertThat(progress.daysElapsed).isEqualTo(9)
        assertThat(progress.daysInPeriod).isEqualTo(30)
        assertThat(progress.projectedSpendMinor).isEqualTo(30_000)
    }

    @Test
    fun `alerts only include budgets that are near or over the limit`() {
        val comfortable = BudgetEvaluator.evaluate(
            budget,
            listOf(expense(2, 1000)),
            groceries,
            LocalDate.of(2026, 4, 10),
        )
        val breached = BudgetEvaluator.evaluate(
            budget.copy(id = 2),
            listOf(expense(2, 31_000)),
            groceries,
            LocalDate.of(2026, 4, 10),
        )

        val alerts = BudgetEvaluator.alertsWorthSending(listOf(comfortable, breached))

        assertThat(alerts).hasSize(1)
        assertThat(alerts.first().budget.id).isEqualTo(2)
    }
}
