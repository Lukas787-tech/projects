package com.expensesplit.app.domain.analytics

import com.expensesplit.app.core.DateRange
import com.expensesplit.app.domain.model.Budget
import com.expensesplit.app.domain.model.BudgetPeriod
import com.expensesplit.app.domain.model.BudgetProgress
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.Expense
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Resolves each budget's current period and measures spend against it. */
object BudgetEvaluator {

    fun currentPeriod(budget: Budget, today: LocalDate = LocalDate.now()): DateRange = when (budget.period) {
        BudgetPeriod.WEEKLY -> DateRange.thisWeek(today)
        BudgetPeriod.MONTHLY -> DateRange.ofMonth(YearMonth.from(today))
        BudgetPeriod.YEARLY -> DateRange.thisYear(today)
    }

    fun evaluate(
        budget: Budget,
        expenses: List<Expense>,
        category: Category?,
        today: LocalDate = LocalDate.now(),
    ): BudgetProgress {
        val period = currentPeriod(budget, today)
        val relevant = expenses.filter { expense ->
            expense.date in period && (budget.categoryId == null || expense.categoryId == budget.categoryId)
        }
        val spent = relevant.sumOf { it.baseAmountMinor }
        val daysElapsed = (ChronoUnit.DAYS.between(period.start, today).toInt() + 1)
            .coerceIn(1, period.dayCount)

        return BudgetProgress(
            budget = budget,
            category = category,
            spentMinor = spent,
            limitMinor = budget.limitMinor,
            currency = budget.currency,
            periodStart = period.start,
            periodEnd = period.endInclusive,
            daysElapsed = daysElapsed,
            daysInPeriod = period.dayCount,
        )
    }

    fun evaluateAll(
        budgets: List<Budget>,
        expenses: List<Expense>,
        categories: Map<Long, Category>,
        today: LocalDate = LocalDate.now(),
    ): List<BudgetProgress> = budgets.map { budget ->
        evaluate(budget, expenses, budget.categoryId?.let { categories[it] }, today)
    }.sortedByDescending { it.usedFraction }

    /** Budgets that warrant a notification right now. */
    fun alertsWorthSending(progress: List<BudgetProgress>): List<BudgetProgress> =
        progress.filter { it.isOverBudget || it.isNearLimit }
}
