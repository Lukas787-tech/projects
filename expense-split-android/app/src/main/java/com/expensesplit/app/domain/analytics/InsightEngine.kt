package com.expensesplit.app.domain.analytics

import com.expensesplit.app.R
import com.expensesplit.app.core.DateRange
import com.expensesplit.app.core.Money
import com.expensesplit.app.domain.model.BudgetProgress
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.DuplicatePurchase
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.Insight
import com.expensesplit.app.domain.model.InsightSeverity
import com.expensesplit.app.domain.model.SavingOpportunity
import com.expensesplit.app.domain.model.SpendingReport
import com.expensesplit.app.domain.model.SpendingTrend
import com.expensesplit.app.domain.model.TrendDirection
import java.time.DayOfWeek
import java.util.Locale

/**
 * Turns the analytics numbers into concrete, ranked advice.
 *
 * These rules are deliberately transparent rather than a black box: every card the user sees can be
 * traced back to one arithmetic condition on their own data, and each carries an estimated monthly
 * saving so the list can be sorted by what actually matters. No spending data leaves the device.
 */
class InsightEngine(
    private val locale: Locale = Locale.getDefault(),
    /**
     * Resolves a category to a display name. The ViewModel passes a resolver backed by Android
     * resources so insight text stays translated; the default keeps the engine unit-testable
     * without a Context.
     */
    private val categoryNamer: (Category) -> String = { category ->
        category.customName ?: category.key.replace('_', ' ')
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    },
) {

    /** Categories where "spend less" is realistic advice rather than an insult. */
    private val discretionaryCategoryKeys = setOf(
        "dining", "entertainment", "shopping", "subscriptions", "personal_care", "travel",
    )

    fun generate(
        report: SpendingReport,
        trend: SpendingTrend,
        budgets: List<BudgetProgress>,
        categories: Map<Long, Category>,
        expenses: List<Expense>,
        duplicates: List<DuplicatePurchase> = emptyList(),
        savings: List<SavingOpportunity> = emptyList(),
        baseCurrency: String,
    ): List<Insight> {
        val insights = mutableListOf<Insight>()
        val currency = baseCurrency

        insights += budgetInsights(budgets, currency)
        insights += topCategoryInsight(report, currency)
        insights += trendInsight(trend, report, currency)
        insights += smallPurchaseInsight(report, expenses, categories, currency)
        insights += diningVersusGroceriesInsight(report, categories, currency)
        insights += weekendInsight(expenses, currency)
        insights += subscriptionInsight(report, categories, currency)
        insights += largeExpenseInsight(report, currency)
        insights += duplicateInsight(duplicates, currency)
        insights += savingsInsight(savings, currency)
        insights += noSpendDaysInsight(report)

        // Most severe first, then by how much money the advice is actually worth.
        return insights.sortedWith(
            compareByDescending<Insight> { it.severity.ordinal }
                .thenByDescending { it.potentialSavingMinor },
        )
    }

    private fun budgetInsights(budgets: List<BudgetProgress>, currency: String): List<Insight> =
        budgets.mapNotNull { progress ->
            val categoryName = progress.category?.let { categoryLabel(it) }
            when {
                progress.isOverBudget -> Insight(
                    id = "budget_over_${progress.budget.id}",
                    titleRes = R.string.insight_budget_exceeded_title,
                    titleArgs = listOf(categoryName ?: ""),
                    bodyRes = R.string.insight_budget_exceeded_body,
                    bodyArgs = listOf(
                        Money.format(progress.spentMinor - progress.limitMinor, currency, locale),
                        Money.format(progress.limitMinor, currency, locale),
                    ),
                    severity = InsightSeverity.CRITICAL,
                    categoryId = progress.budget.categoryId,
                    potentialSavingMinor = progress.spentMinor - progress.limitMinor,
                )

                progress.isNearLimit -> Insight(
                    id = "budget_near_${progress.budget.id}",
                    titleRes = R.string.insight_budget_near_title,
                    titleArgs = listOf(categoryName ?: ""),
                    bodyRes = R.string.insight_budget_near_body,
                    bodyArgs = listOf(
                        (progress.usedFraction * 100).toInt(),
                        Money.format(progress.safeDailyMinor, currency, locale),
                    ),
                    severity = InsightSeverity.WARNING,
                    categoryId = progress.budget.categoryId,
                    potentialSavingMinor = progress.remainingMinor.coerceAtLeast(0),
                )

                progress.projectedSpendMinor > progress.limitMinor && progress.daysElapsed >= 5 -> Insight(
                    id = "budget_pace_${progress.budget.id}",
                    titleRes = R.string.insight_budget_pace_title,
                    titleArgs = listOf(categoryName ?: ""),
                    bodyRes = R.string.insight_budget_pace_body,
                    bodyArgs = listOf(
                        Money.format(progress.projectedSpendMinor, currency, locale),
                        Money.format(progress.limitMinor, currency, locale),
                    ),
                    severity = InsightSeverity.WARNING,
                    categoryId = progress.budget.categoryId,
                    potentialSavingMinor = progress.projectedSpendMinor - progress.limitMinor,
                )

                else -> null
            }
        }

    private fun topCategoryInsight(report: SpendingReport, currency: String): List<Insight> {
        val top = report.topCategory ?: return emptyList()
        val category = top.category ?: return emptyList()
        if (top.shareOfTotal < 0.25f) return emptyList()

        val isDiscretionary = category.key in discretionaryCategoryKeys
        // A 15% trim is an achievable ask; anything more tends to be ignored.
        val target = (top.totalMinor * 0.15).toLong()

        return listOf(
            Insight(
                id = "top_category_${top.categoryId}",
                titleRes = R.string.insight_top_category_title,
                titleArgs = listOf(categoryLabel(category)),
                bodyRes = if (isDiscretionary) {
                    R.string.insight_top_category_body_discretionary
                } else {
                    R.string.insight_top_category_body_essential
                },
                bodyArgs = listOf(
                    (top.shareOfTotal * 100).toInt(),
                    Money.format(top.totalMinor, currency, locale),
                    Money.format(target, currency, locale),
                ),
                severity = InsightSeverity.SUGGESTION,
                categoryId = top.categoryId,
                potentialSavingMinor = if (isDiscretionary) target else 0,
            ),
        )
    }

    private fun trendInsight(trend: SpendingTrend, report: SpendingReport, currency: String): List<Insight> {
        // A weak fit means the "trend" is really just noise, so stay quiet about it.
        if (trend.confidence < 0.25f) return emptyList()

        return when (trend.direction) {
            TrendDirection.RISING -> listOf(
                Insight(
                    id = "trend_rising",
                    titleRes = R.string.insight_trend_rising_title,
                    bodyRes = R.string.insight_trend_rising_body,
                    bodyArgs = listOf(
                        Money.format(trend.projectedNextPeriodMinor, currency, locale),
                        Money.format(report.totalMinor, currency, locale),
                    ),
                    severity = InsightSeverity.WARNING,
                    potentialSavingMinor = (trend.projectedNextPeriodMinor - report.totalMinor)
                        .coerceAtLeast(0),
                ),
            )

            TrendDirection.FALLING -> listOf(
                Insight(
                    id = "trend_falling",
                    titleRes = R.string.insight_trend_falling_title,
                    bodyRes = R.string.insight_trend_falling_body,
                    bodyArgs = listOf(
                        Money.format(
                            (report.previousPeriodTotalMinor - report.totalMinor).coerceAtLeast(0),
                            currency,
                            locale,
                        ),
                    ),
                    severity = InsightSeverity.INFO,
                ),
            )

            TrendDirection.STABLE -> emptyList()
        }
    }

    /**
     * Small, frequent purchases are the classic invisible drain: individually trivial, collectively
     * a meaningful share of the month.
     */
    private fun smallPurchaseInsight(
        report: SpendingReport,
        expenses: List<Expense>,
        categories: Map<Long, Category>,
        currency: String,
    ): List<Insight> {
        if (report.totalMinor <= 0 || expenses.size < 10) return emptyList()
        val threshold = Money.minorUnitsPerMajor(currency) * 15
        val small = expenses.filter { it.baseAmountMinor in 1..threshold }
        if (small.size < 8) return emptyList()

        val smallTotal = small.sumOf { it.baseAmountMinor }
        if (smallTotal.toFloat() / report.totalMinor < 0.12f) return emptyList()

        val topCategoryId = small.groupBy { it.categoryId }
            .maxByOrNull { (_, group) -> group.sumOf { it.baseAmountMinor } }?.key
        val categoryName = topCategoryId?.let { categories[it] }?.let { categoryLabel(it) }.orEmpty()

        return listOf(
            Insight(
                id = "small_purchases",
                titleRes = R.string.insight_small_purchases_title,
                titleArgs = listOf(small.size),
                bodyRes = R.string.insight_small_purchases_body,
                bodyArgs = listOf(
                    Money.format(smallTotal, currency, locale),
                    categoryName,
                    Money.format(smallTotal / 4, currency, locale),
                ),
                severity = InsightSeverity.SUGGESTION,
                categoryId = topCategoryId,
                potentialSavingMinor = smallTotal / 4,
            ),
        )
    }

    /** Eating out costing more than the weekly shop is a concrete, fixable pattern. */
    private fun diningVersusGroceriesInsight(
        report: SpendingReport,
        categories: Map<Long, Category>,
        currency: String,
    ): List<Insight> {
        val diningId = categories.values.firstOrNull { it.key == "dining" }?.id ?: return emptyList()
        val groceriesId = categories.values.firstOrNull { it.key == "groceries" }?.id ?: return emptyList()

        val dining = report.byCategory.firstOrNull { it.categoryId == diningId }?.totalMinor ?: 0L
        val groceries = report.byCategory.firstOrNull { it.categoryId == groceriesId }?.totalMinor ?: 0L
        if (dining <= 0 || groceries <= 0 || dining <= groceries) return emptyList()

        val difference = dining - groceries
        return listOf(
            Insight(
                id = "dining_vs_groceries",
                titleRes = R.string.insight_dining_vs_groceries_title,
                bodyRes = R.string.insight_dining_vs_groceries_body,
                bodyArgs = listOf(
                    Money.format(dining, currency, locale),
                    Money.format(groceries, currency, locale),
                    Money.format(difference / 3, currency, locale),
                ),
                severity = InsightSeverity.SUGGESTION,
                categoryId = diningId,
                potentialSavingMinor = difference / 3,
            ),
        )
    }

    private fun weekendInsight(expenses: List<Expense>, currency: String): List<Insight> {
        if (expenses.size < 12) return emptyList()
        val weekend = expenses.filter {
            it.date.dayOfWeek == DayOfWeek.SATURDAY || it.date.dayOfWeek == DayOfWeek.SUNDAY
        }
        val weekday = expenses - weekend.toSet()
        if (weekend.isEmpty() || weekday.isEmpty()) return emptyList()

        // Compare daily averages, since there are only two weekend days in five weekdays.
        val weekendDaily = weekend.sumOf { it.baseAmountMinor }.toDouble() /
            weekend.map { it.date }.distinct().size.coerceAtLeast(1)
        val weekdayDaily = weekday.sumOf { it.baseAmountMinor }.toDouble() /
            weekday.map { it.date }.distinct().size.coerceAtLeast(1)
        if (weekdayDaily <= 0 || weekendDaily < weekdayDaily * 1.5) return emptyList()

        val multiple = weekendDaily / weekdayDaily
        val monthlyExcess = ((weekendDaily - weekdayDaily) * 8).toLong()

        return listOf(
            Insight(
                id = "weekend_spike",
                titleRes = R.string.insight_weekend_title,
                bodyRes = R.string.insight_weekend_body,
                bodyArgs = listOf(
                    String.format(locale, "%.1f", multiple),
                    Money.format(monthlyExcess, currency, locale),
                ),
                severity = InsightSeverity.SUGGESTION,
                potentialSavingMinor = monthlyExcess / 3,
            ),
        )
    }

    private fun subscriptionInsight(
        report: SpendingReport,
        categories: Map<Long, Category>,
        currency: String,
    ): List<Insight> {
        val subscriptionsId = categories.values.firstOrNull { it.key == "subscriptions" }?.id
            ?: return emptyList()
        val spend = report.byCategory.firstOrNull { it.categoryId == subscriptionsId } ?: return emptyList()
        if (spend.transactionCount < 3) return emptyList()

        return listOf(
            Insight(
                id = "subscription_creep",
                titleRes = R.string.insight_subscriptions_title,
                titleArgs = listOf(spend.transactionCount),
                bodyRes = R.string.insight_subscriptions_body,
                bodyArgs = listOf(
                    Money.format(spend.totalMinor, currency, locale),
                    Money.format(spend.totalMinor * 12, currency, locale),
                ),
                severity = InsightSeverity.SUGGESTION,
                categoryId = subscriptionsId,
                potentialSavingMinor = spend.totalMinor / 3,
            ),
        )
    }

    private fun largeExpenseInsight(report: SpendingReport, currency: String): List<Insight> {
        val largest = report.largestExpense ?: return emptyList()
        if (report.totalMinor <= 0) return emptyList()
        val share = largest.baseAmountMinor.toFloat() / report.totalMinor
        if (share < 0.3f) return emptyList()

        return listOf(
            Insight(
                id = "large_expense_${largest.id}",
                titleRes = R.string.insight_large_expense_title,
                bodyRes = R.string.insight_large_expense_body,
                bodyArgs = listOf(
                    largest.title,
                    Money.format(largest.baseAmountMinor, currency, locale),
                    (share * 100).toInt(),
                ),
                severity = InsightSeverity.INFO,
                categoryId = largest.categoryId,
            ),
        )
    }

    private fun duplicateInsight(duplicates: List<DuplicatePurchase>, currency: String): List<Insight> {
        val worst = duplicates
            .filter { it.occurrences >= 3 && it.averageDaysBetween < 7 }
            .maxByOrNull { it.totalSpentMinor }
            ?: return emptyList()

        return listOf(
            Insight(
                id = "duplicate_${worst.normalizedItemName}",
                titleRes = R.string.insight_duplicate_title,
                titleArgs = listOf(worst.displayName),
                bodyRes = R.string.insight_duplicate_body,
                bodyArgs = listOf(
                    worst.occurrences,
                    String.format(locale, "%.0f", worst.averageDaysBetween),
                    Money.format(worst.totalSpentMinor, currency, locale),
                ),
                severity = InsightSeverity.SUGGESTION,
                potentialSavingMinor = worst.totalSpentMinor / worst.occurrences,
            ),
        )
    }

    private fun savingsInsight(savings: List<SavingOpportunity>, currency: String): List<Insight> {
        if (savings.isEmpty()) return emptyList()
        val totalSaving = savings.sumOf { it.savingMinor }
        if (totalSaving <= 0) return emptyList()
        val best = savings.maxByOrNull { it.savingMinor } ?: return emptyList()

        return listOf(
            Insight(
                id = "cheaper_elsewhere",
                titleRes = R.string.insight_cheaper_elsewhere_title,
                titleArgs = listOf(savings.size),
                bodyRes = R.string.insight_cheaper_elsewhere_body,
                bodyArgs = listOf(
                    best.displayName,
                    best.bestStore,
                    Money.format(best.savingMinor, currency, locale),
                    Money.format(totalSaving, currency, locale),
                ),
                severity = InsightSeverity.SUGGESTION,
                potentialSavingMinor = totalSaving,
            ),
        )
    }

    private fun noSpendDaysInsight(report: SpendingReport): List<Insight> {
        val zeroDays = report.byDay.count { it.totalMinor == 0L }
        if (report.byDay.size < 14 || zeroDays < 5) return emptyList()

        return listOf(
            Insight(
                id = "no_spend_days",
                titleRes = R.string.insight_no_spend_title,
                titleArgs = listOf(zeroDays),
                bodyRes = R.string.insight_no_spend_body,
                bodyArgs = listOf(zeroDays, report.byDay.size),
                severity = InsightSeverity.INFO,
            ),
        )
    }

    private fun categoryLabel(category: Category): String = categoryNamer(category)
}
