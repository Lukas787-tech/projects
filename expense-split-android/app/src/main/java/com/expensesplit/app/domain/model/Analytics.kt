package com.expensesplit.app.domain.model

import com.expensesplit.app.core.DateRange
import java.time.LocalDate
import java.time.YearMonth

data class CategorySpend(
    val category: Category?,
    val categoryId: Long,
    val totalMinor: Long,
    val transactionCount: Int,
    val currency: String,
    val shareOfTotal: Float,
)

data class DailySpend(val date: LocalDate, val totalMinor: Long)

data class MonthlySpend(val month: YearMonth, val totalMinor: Long, val transactionCount: Int)

data class SpendingReport(
    val range: DateRange,
    val currency: String,
    val totalMinor: Long,
    val transactionCount: Int,
    val averagePerDayMinor: Long,
    val averagePerTransactionMinor: Long,
    val largestExpense: Expense?,
    val byCategory: List<CategorySpend>,
    val byDay: List<DailySpend>,
    val previousPeriodTotalMinor: Long,
) {
    /** Percent change against the immediately preceding period of the same length. */
    val changePercent: Float
        get() = if (previousPeriodTotalMinor <= 0) 0f
        else (totalMinor - previousPeriodTotalMinor).toFloat() / previousPeriodTotalMinor * 100f

    val topCategory: CategorySpend? get() = byCategory.maxByOrNull { it.totalMinor }
}

enum class TrendDirection { RISING, FALLING, STABLE }

data class SpendingTrend(
    val direction: TrendDirection,
    /** Least-squares slope in minor units per day. */
    val slopePerDayMinor: Double,
    val projectedNextPeriodMinor: Long,
    /** 0..1 goodness of fit; low values mean the projection is a weak signal. */
    val confidence: Float,
)

enum class InsightSeverity { INFO, SUGGESTION, WARNING, CRITICAL }

/**
 * A single actionable observation rendered as a card. Text is assembled from string resources at
 * render time so insights stay translated; [titleRes]/[bodyRes] carry format arguments.
 */
data class Insight(
    val id: String,
    val titleRes: Int,
    val titleArgs: List<Any> = emptyList(),
    val bodyRes: Int,
    val bodyArgs: List<Any> = emptyList(),
    val severity: InsightSeverity = InsightSeverity.SUGGESTION,
    val categoryId: Long? = null,
    /** Estimated monthly saving if the user acts on this, in base-currency minor units. */
    val potentialSavingMinor: Long = 0,
)

data class MonthlyRecap(
    val month: YearMonth,
    val currency: String,
    val report: SpendingReport,
    val previousMonth: MonthlySpend?,
    val yearToDateMinor: Long,
    val yearToDateTransactionCount: Int,
    val budgets: List<BudgetProgress>,
    val insights: List<Insight>,
    val settlementSummary: List<GroupSettlementSummary>,
    val topMerchants: List<MerchantSpend>,
)

data class MerchantSpend(val merchant: String, val totalMinor: Long, val visits: Int)

data class GroupSettlementSummary(
    val groupName: String,
    val currency: String,
    val youAreOwedMinor: Long,
    val youOweMinor: Long,
    val openBills: Int,
)
