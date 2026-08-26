package com.expensesplit.app.data.repository

import android.content.Context
import com.expensesplit.app.core.DateRange
import com.expensesplit.app.domain.analytics.InsightEngine
import com.expensesplit.app.domain.analytics.SpendingAnalyzer
import com.expensesplit.app.domain.analytics.TrendForecaster
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.GroupSettlementSummary
import com.expensesplit.app.domain.model.Insight
import com.expensesplit.app.domain.model.MerchantSpend
import com.expensesplit.app.domain.model.MonthlyRecap
import com.expensesplit.app.domain.model.MonthlySpend
import com.expensesplit.app.domain.model.SpendingReport
import com.expensesplit.app.domain.model.SpendingTrend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composes the raw repositories into the finished analytics the UI renders.
 *
 * All of the work happens on [Dispatchers.Default] — these are pure CPU passes over lists that can
 * run to thousands of rows, and none of it belongs on the main thread.
 */
@Singleton
class AnalyticsRepository @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val groupRepository: GroupRepository,
    private val priceRepository: PriceRepository,
    private val context: Context,
) {

    suspend fun report(range: DateRange, baseCurrency: String): SpendingReport =
        withContext(Dispatchers.Default) {
            val expenses = expenseRepository.getExpensesIn(range)
            val previous = expenseRepository.getExpensesIn(SpendingAnalyzer.previousRange(range))
            SpendingAnalyzer.buildReport(
                range = range,
                expenses = expenses,
                categories = categoryRepository.getAllById(),
                baseCurrency = baseCurrency,
                previousPeriodExpenses = previous,
            )
        }

    suspend fun trend(range: DateRange, projectionDays: Int = 30): SpendingTrend =
        withContext(Dispatchers.Default) {
            val expenses = expenseRepository.getExpensesIn(range)
            TrendForecaster.forecast(SpendingAnalyzer.dailySpend(range, expenses), projectionDays)
        }

    suspend fun insights(range: DateRange, baseCurrency: String): List<Insight> =
        withContext(Dispatchers.Default) {
            val report = report(range, baseCurrency)
            val categories = categoryRepository.getAllById()
            InsightEngine(locale = currentLocale(), categoryNamer = ::categoryName).generate(
                report = report,
                trend = trend(range),
                budgets = budgetRepository.evaluateAll(),
                categories = categories,
                expenses = expenseRepository.getExpensesIn(range),
                duplicates = priceRepository.duplicatePurchases(),
                savings = priceRepository.savingOpportunities(),
                baseCurrency = baseCurrency,
            )
        }

    /** Trailing [months] of totals, oldest first — the month-over-month bar chart. */
    suspend fun monthlySeries(months: Int, today: LocalDate = LocalDate.now()): List<MonthlySpend> =
        withContext(Dispatchers.Default) {
            val end = YearMonth.from(today)
            val start = end.minusMonths((months - 1).toLong())
            val range = DateRange(start.atDay(1), end.atEndOfMonth())
            val totals = expenseRepository.monthlyTotals(range).associateBy { it.monthKey }

            (0 until months).map { offset ->
                val month = start.plusMonths(offset.toLong())
                val key = String.format(Locale.ROOT, "%04d-%02d", month.year, month.monthValue)
                val row = totals[key]
                MonthlySpend(month, row?.totalMinor ?: 0L, row?.transactionCount ?: 0)
            }
        }

    suspend fun monthlyRecap(month: YearMonth, baseCurrency: String): MonthlyRecap =
        withContext(Dispatchers.Default) {
            val range = DateRange.ofMonth(month)
            val report = report(range, baseCurrency)
            val today = LocalDate.now()
            val yearRange = DateRange(
                month.withMonth(1).atDay(1),
                minOf(range.endInclusive, today.withDayOfYear(today.lengthOfYear())),
            )
            val yearExpenses = expenseRepository.getExpensesIn(yearRange)

            val previousMonth = month.minusMonths(1)
            val previousExpenses = expenseRepository.expensesForMonth(previousMonth)

            MonthlyRecap(
                month = month,
                currency = baseCurrency,
                report = report,
                previousMonth = MonthlySpend(
                    month = previousMonth,
                    totalMinor = previousExpenses.sumOf { it.baseAmountMinor },
                    transactionCount = previousExpenses.size,
                ),
                yearToDateMinor = yearExpenses.sumOf { it.baseAmountMinor },
                yearToDateTransactionCount = yearExpenses.size,
                budgets = budgetRepository.evaluateAll(range.endInclusive.coerceAtMost(today)),
                insights = insights(range, baseCurrency),
                settlementSummary = settlementSummaries(),
                topMerchants = expenseRepository.topMerchants(range).map {
                    MerchantSpend(it.merchant, it.totalMinor, it.visits)
                },
            )
        }

    /** Per-group "you are owed / you owe" totals used on the dashboard and in the recap. */
    suspend fun settlementSummaries(): List<GroupSettlementSummary> =
        withContext(Dispatchers.Default) {
            groupRepository.getAllGroups().filterNot { it.archived }.map { group ->
                val balances = groupRepository.balances(group.id)
                val self = balances.firstOrNull { it.member.isSelf }
                val net = self?.netMinor ?: 0L
                GroupSettlementSummary(
                    groupName = group.name,
                    currency = group.currency,
                    youAreOwedMinor = net.coerceAtLeast(0),
                    youOweMinor = (-net).coerceAtLeast(0),
                    openBills = groupRepository.openBillCount(group.id),
                )
            }
        }

    private fun categoryName(category: Category): String = categoryRepository.displayName(category)

    private fun currentLocale(): Locale =
        context.resources.configuration.locales.get(0) ?: Locale.getDefault()
}
