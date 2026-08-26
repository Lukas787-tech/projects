package com.expensesplit.app.ui.screens.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.R
import com.expensesplit.app.core.DateRange
import com.expensesplit.app.data.export.CsvExporter
import com.expensesplit.app.data.export.FileSharer
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.repository.CategoryRepository
import com.expensesplit.app.data.repository.ExpenseRepository
import com.expensesplit.app.data.repository.SearchRepository
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.SearchFilters
import com.expensesplit.app.domain.model.SearchSort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/** The one-tap ranges offered above the list. */
enum class QuickRange(val labelRes: Int) {
    ALL(R.string.filter_all_time),
    THIS_WEEK(R.string.filter_this_week),
    THIS_MONTH(R.string.filter_this_month),
    THIS_YEAR(R.string.filter_this_year);

    fun toRange(today: LocalDate = LocalDate.now()): DateRange? = when (this) {
        ALL -> null
        THIS_WEEK -> DateRange.thisWeek(today)
        THIS_MONTH -> DateRange.thisMonth(today)
        THIS_YEAR -> DateRange.thisYear(today)
    }
}

data class ExpenseListUiState(
    val expenses: List<Expense> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
    val allCategories: List<Category> = emptyList(),
    val baseCurrency: String = "USD",
    val quickRange: QuickRange = QuickRange.THIS_MONTH,
    val keyword: String = "",
    val selectedCategoryIds: Set<Long> = emptySet(),
    val sort: SearchSort = SearchSort.DATE_DESC,
    val isLoading: Boolean = true,
) {
    val totalMinor: Long get() = expenses.sumOf { it.baseAmountMinor }
}

@HiltViewModel
class ExpenseListViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val searchRepository: SearchRepository,
    private val csvExporter: CsvExporter,
    private val fileSharer: FileSharer,
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val filterState = MutableStateFlow(FilterState())

    private data class FilterState(
        val quickRange: QuickRange = QuickRange.THIS_MONTH,
        val keyword: String = "",
        val categoryIds: Set<Long> = emptySet(),
        val sort: SearchSort = SearchSort.DATE_DESC,
    ) {
        fun toSearchFilters(): SearchFilters = SearchFilters(
            keyword = keyword,
            range = quickRange.toRange(),
            categoryIds = categoryIds,
            sort = sort,
        )
    }

    private val _exportedFile = MutableStateFlow<File?>(null)
    val exportedFile: StateFlow<File?> = _exportedFile.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ExpenseListUiState> = combine(
        filterState,
        categoryRepository.categories,
        preferencesRepository.preferences,
    ) { filters, categories, preferences ->
        Triple(filters, categories, preferences.baseCurrency)
    }.flatMapLatest { (filters, categories, baseCurrency) ->
        searchRepository.search(filters.toSearchFilters()).map { expenses ->
            ExpenseListUiState(
                expenses = expenses,
                categories = categories.associateBy { it.id },
                allCategories = categories,
                baseCurrency = baseCurrency,
                quickRange = filters.quickRange,
                keyword = filters.keyword,
                selectedCategoryIds = filters.categoryIds,
                sort = filters.sort,
                isLoading = false,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExpenseListUiState(),
    )

    fun onKeywordChanged(value: String) = filterState.update { it.copy(keyword = value) }

    fun onQuickRangeChanged(range: QuickRange) = filterState.update { it.copy(quickRange = range) }

    fun onSortChanged(sort: SearchSort) = filterState.update { it.copy(sort = sort) }

    fun toggleCategory(categoryId: Long) = filterState.update { state ->
        state.copy(
            categoryIds = if (categoryId in state.categoryIds) {
                state.categoryIds - categoryId
            } else {
                state.categoryIds + categoryId
            },
        )
    }

    fun clearFilters() = filterState.update { FilterState(quickRange = it.quickRange) }

    fun delete(expenseId: Long) {
        viewModelScope.launch { expenseRepository.delete(expenseId) }
    }

    fun categoryName(category: Category?): String =
        category?.let { categoryRepository.displayName(it) }.orEmpty()

    /** Exports exactly what the current filters show, not the whole database. */
    fun exportVisibleAsCsv() {
        viewModelScope.launch {
            val state = uiState.value
            _exportedFile.value = csvExporter.exportExpenses(state.expenses, state.baseCurrency)
        }
    }

    fun shareIntentFor(file: File) = fileSharer.shareIntent(file)

    fun onExportHandled() {
        _exportedFile.value = null
    }
}
