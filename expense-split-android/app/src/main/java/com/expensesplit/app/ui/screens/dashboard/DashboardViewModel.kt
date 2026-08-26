package com.expensesplit.app.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.core.DateRange
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.repository.AnalyticsRepository
import com.expensesplit.app.data.repository.BudgetRepository
import com.expensesplit.app.data.repository.CategoryRepository
import com.expensesplit.app.data.repository.ExpenseRepository
import com.expensesplit.app.data.repository.RecurringRepository
import com.expensesplit.app.domain.model.BudgetProgress
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.DailySpend
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.GroupSettlementSummary
import com.expensesplit.app.domain.model.Insight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DashboardUiState(
    val isLoading: Boolean = true,
    val baseCurrency: String = "USD",
    val monthTotalMinor: Long = 0,
    val weekTotalMinor: Long = 0,
    val todayTotalMinor: Long = 0,
    val monthChangePercent: Float = 0f,
    val dailyAverageMinor: Long = 0,
    val transactionCount: Int = 0,
    val recentExpenses: List<Expense> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
    val budgets: List<BudgetProgress> = emptyList(),
    val insights: List<Insight> = emptyList(),
    val settlements: List<GroupSettlementSummary> = emptyList(),
    val dailySeries: List<DailySpend> = emptyList(),
    val hasAnyData: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val budgetRepository: BudgetRepository,
    private val recurringRepository: RecurringRepository,
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    /** Bumped by [refresh] to recompute derived data that no Flow covers. */
    private val refreshTrigger = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<DashboardUiState> = combine(
        preferencesRepository.preferences,
        expenseRepository.allExpenses,
        categoryRepository.categories,
        refreshTrigger,
    ) { preferences, expenses, categories, _ ->
        Triple(preferences.baseCurrency, expenses, categories)
    }.mapLatest { (baseCurrency, expenses, categories) ->
        val today = LocalDate.now()
        val monthRange = DateRange.thisMonth(today)
        val weekRange = DateRange.thisWeek(today)

        val monthExpenses = expenses.filter { it.date in monthRange }
        val report = analyticsRepository.report(monthRange, baseCurrency)

        DashboardUiState(
            isLoading = false,
            baseCurrency = baseCurrency,
            monthTotalMinor = report.totalMinor,
            weekTotalMinor = expenses.filter { it.date in weekRange }.sumOf { it.baseAmountMinor },
            todayTotalMinor = expenses.filter { it.date == today }.sumOf { it.baseAmountMinor },
            monthChangePercent = report.changePercent,
            dailyAverageMinor = report.averagePerDayMinor,
            transactionCount = report.transactionCount,
            recentExpenses = expenses.take(RECENT_LIMIT),
            categories = categories.associateBy { it.id },
            budgets = budgetRepository.evaluateAll(today),
            // Only the strongest few belong on the dashboard; the rest live on Analytics.
            insights = analyticsRepository.insights(monthRange, baseCurrency).take(INSIGHT_LIMIT),
            settlements = analyticsRepository.settlementSummaries()
                .filter { it.youAreOwedMinor > 0 || it.youOweMinor > 0 },
            dailySeries = report.byDay,
            hasAnyData = expenses.isNotEmpty() || monthExpenses.isNotEmpty(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    init {
        // Catch up on any recurring expenses that came due while the app was closed.
        viewModelScope.launch {
            val baseCurrency = uiState.value.baseCurrency
            runCatching { recurringRepository.materializeDue(baseCurrency) }
        }
    }

    fun categoryName(category: Category?): String =
        category?.let { categoryRepository.displayName(it) }.orEmpty()

    fun refresh() {
        refreshTrigger.value += 1
    }

    private companion object {
        const val RECENT_LIMIT = 8
        const val INSIGHT_LIMIT = 3
    }
}
