package com.expensesplit.app.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.R
import com.expensesplit.app.core.DateRange
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.repository.AnalyticsRepository
import com.expensesplit.app.data.repository.BudgetRepository
import com.expensesplit.app.data.repository.CategoryRepository
import com.expensesplit.app.domain.model.Budget
import com.expensesplit.app.domain.model.BudgetPeriod
import com.expensesplit.app.domain.model.BudgetProgress
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.Insight
import com.expensesplit.app.domain.model.MonthlySpend
import com.expensesplit.app.domain.model.SpendingReport
import com.expensesplit.app.domain.model.SpendingTrend
import com.expensesplit.app.domain.model.TrendDirection
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

/** The period the analytics screen is looking at. */
enum class AnalyticsPeriod(val labelRes: Int, val projectionDays: Int) {
    WEEK(R.string.filter_this_week, 7),
    MONTH(R.string.filter_this_month, 30),
    YEAR(R.string.filter_this_year, 90);

    fun toRange(today: LocalDate = LocalDate.now()): DateRange = when (this) {
        WEEK -> DateRange.thisWeek(today)
        MONTH -> DateRange.thisMonth(today)
        YEAR -> DateRange.thisYear(today)
    }
}

data class AnalyticsUiState(
    val isLoading: Boolean = true,
    val period: AnalyticsPeriod = AnalyticsPeriod.MONTH,
    val baseCurrency: String = "USD",
    val report: SpendingReport? = null,
    val trend: SpendingTrend? = null,
    val monthlySeries: List<MonthlySpend> = emptyList(),
    val budgets: List<BudgetProgress> = emptyList(),
    val insights: List<Insight> = emptyList(),
    val categories: List<Category> = emptyList(),
) {
    val trendIsMeaningful: Boolean get() = (trend?.confidence ?: 0f) >= 0.25f
    val trendDirection: TrendDirection get() = trend?.direction ?: TrendDirection.STABLE
}

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val period = MutableStateFlow(AnalyticsPeriod.MONTH)
    private val refreshTrigger = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<AnalyticsUiState> = combine(
        period,
        preferencesRepository.preferences,
        categoryRepository.categories,
        refreshTrigger,
    ) { selectedPeriod, preferences, categories, _ ->
        Triple(selectedPeriod, preferences.baseCurrency, categories)
    }.mapLatest { (selectedPeriod, baseCurrency, categories) ->
        val range = selectedPeriod.toRange()
        AnalyticsUiState(
            isLoading = false,
            period = selectedPeriod,
            baseCurrency = baseCurrency,
            report = analyticsRepository.report(range, baseCurrency),
            trend = analyticsRepository.trend(range, selectedPeriod.projectionDays),
            monthlySeries = analyticsRepository.monthlySeries(MONTHS_OF_HISTORY),
            budgets = budgetRepository.evaluateAll(),
            insights = analyticsRepository.insights(range, baseCurrency),
            categories = categories,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AnalyticsUiState(),
    )

    fun onPeriodChanged(value: AnalyticsPeriod) {
        period.value = value
    }

    fun categoryName(category: Category?): String =
        category?.let { categoryRepository.displayName(it) }.orEmpty()

    fun saveBudget(categoryId: Long?, limitMinor: Long, currency: String, budgetPeriod: BudgetPeriod) {
        viewModelScope.launch {
            // Editing an existing budget for the same category rather than stacking a second one.
            val existing = categoryId?.let { budgetRepository.forCategory(it) }
            budgetRepository.save(
                Budget(
                    id = existing?.id ?: 0,
                    categoryId = categoryId,
                    period = budgetPeriod,
                    limitMinor = limitMinor,
                    currency = currency,
                    startDate = existing?.startDate ?: LocalDate.now(),
                ),
            )
            refreshTrigger.value += 1
        }
    }

    fun deleteBudget(budgetId: Long) {
        viewModelScope.launch {
            budgetRepository.delete(budgetId)
            refreshTrigger.value += 1
        }
    }

    private companion object {
        const val MONTHS_OF_HISTORY = 6
    }
}
