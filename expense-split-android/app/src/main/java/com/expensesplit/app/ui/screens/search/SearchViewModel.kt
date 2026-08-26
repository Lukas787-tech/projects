package com.expensesplit.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.core.DateRange
import com.expensesplit.app.core.Money
import com.expensesplit.app.data.local.dao.ReceiptItemSearchRow
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.repository.CategoryRepository
import com.expensesplit.app.data.repository.GroupRepository
import com.expensesplit.app.data.repository.SearchRepository
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.ExpenseGroup
import com.expensesplit.app.domain.model.PaymentMethod
import com.expensesplit.app.domain.model.SearchFilters
import com.expensesplit.app.domain.model.SearchSort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class SearchUiState(
    val filters: SearchFilters = SearchFilters(),
    val results: List<Expense> = emptyList(),
    val itemResults: List<ReceiptItemSearchRow> = emptyList(),
    val categories: List<Category> = emptyList(),
    val categoriesById: Map<Long, Category> = emptyMap(),
    val groups: List<ExpenseGroup> = emptyList(),
    val baseCurrency: String = "USD",
    val searchingItems: Boolean = false,
) {
    val totalMinor: Long get() = results.sumOf { it.baseAmountMinor }
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val categoryRepository: CategoryRepository,
    private val groupRepository: GroupRepository,
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(SearchFilters())

    private val _itemResults = MutableStateFlow<List<ReceiptItemSearchRow>>(emptyList())
    val itemResults: StateFlow<List<ReceiptItemSearchRow>> = _itemResults.asStateFlow()

    private val groups = MutableStateFlow<List<ExpenseGroup>>(emptyList())

    /** Free-text input is debounced; the structured filters apply immediately. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val debouncedFilters: StateFlow<SearchFilters> = filters
        .debounce { current -> if (current.keyword.isBlank()) 0L else KEYWORD_DEBOUNCE_MS }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchFilters())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SearchUiState> = combine(
        debouncedFilters,
        categoryRepository.categories,
        preferencesRepository.preferences,
        groups,
        _itemResults,
    ) { activeFilters, categories, preferences, groupList, items ->
        SearchState(activeFilters, categories, preferences.baseCurrency, groupList, items)
    }.flatMapLatest { snapshot ->
        searchRepository.search(snapshot.filters).map { results ->
            SearchUiState(
                filters = snapshot.filters,
                results = results,
                itemResults = snapshot.items,
                categories = snapshot.categories,
                categoriesById = snapshot.categories.associateBy { it.id },
                groups = snapshot.groups,
                baseCurrency = snapshot.baseCurrency,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SearchUiState(),
    )

    private data class SearchState(
        val filters: SearchFilters,
        val categories: List<Category>,
        val baseCurrency: String,
        val groups: List<ExpenseGroup>,
        val items: List<ReceiptItemSearchRow>,
    )

    init {
        viewModelScope.launch { groups.value = groupRepository.getAllGroups() }
    }

    fun onKeywordChanged(value: String) {
        filters.update { it.copy(keyword = value) }
        searchReceiptItems(value)
    }

    fun onRangeChanged(range: DateRange?) = filters.update { it.copy(range = range) }

    fun onQuickRange(range: QuickSearchRange) =
        filters.update { it.copy(range = range.toRange()) }

    fun toggleCategory(categoryId: Long) = filters.update { current ->
        current.copy(
            categoryIds = if (categoryId in current.categoryIds) {
                current.categoryIds - categoryId
            } else {
                current.categoryIds + categoryId
            },
        )
    }

    fun togglePaymentMethod(method: PaymentMethod) = filters.update { current ->
        current.copy(
            paymentMethods = if (method in current.paymentMethods) {
                current.paymentMethods - method
            } else {
                current.paymentMethods + method
            },
        )
    }

    fun toggleGroup(groupId: Long) = filters.update { current ->
        current.copy(
            groupIds = if (groupId in current.groupIds) {
                current.groupIds - groupId
            } else {
                current.groupIds + groupId
            },
        )
    }

    fun onAmountRangeChanged(minText: String, maxText: String, currency: String) {
        filters.update { current ->
            current.copy(
                minAmountMinor = Money.parseToMinor(minText, currency),
                maxAmountMinor = Money.parseToMinor(maxText, currency),
            )
        }
    }

    fun onSettledFilterChanged(value: Boolean?) = filters.update { it.copy(settledOnly = value) }

    fun onReceiptOnlyChanged(value: Boolean) = filters.update { it.copy(withReceiptOnly = value) }

    fun onSortChanged(sort: SearchSort) = filters.update { it.copy(sort = sort) }

    fun clearAll() {
        filters.value = SearchFilters()
        _itemResults.value = emptyList()
    }

    fun categoryName(category: Category?): String =
        category?.let { categoryRepository.displayName(it) }.orEmpty()

    /** "When did I last buy X?" — searches the line items of every scanned receipt. */
    private fun searchReceiptItems(query: String) {
        viewModelScope.launch {
            _itemResults.value = if (query.length < MIN_ITEM_QUERY) {
                emptyList()
            } else {
                searchRepository.searchReceiptItems(query)
            }
        }
    }

    private companion object {
        const val KEYWORD_DEBOUNCE_MS = 280L
        const val MIN_ITEM_QUERY = 2
    }
}

/** Shortcut ranges offered as chips on the search screen. */
enum class QuickSearchRange(val labelRes: Int) {
    THIS_WEEK(com.expensesplit.app.R.string.filter_this_week),
    THIS_MONTH(com.expensesplit.app.R.string.filter_this_month),
    THIS_YEAR(com.expensesplit.app.R.string.filter_this_year),
    LAST_90_DAYS(com.expensesplit.app.R.string.filter_last_90_days);

    fun toRange(today: LocalDate = LocalDate.now()): DateRange = when (this) {
        THIS_WEEK -> DateRange.thisWeek(today)
        THIS_MONTH -> DateRange.thisMonth(today)
        THIS_YEAR -> DateRange.thisYear(today)
        LAST_90_DAYS -> DateRange.lastDays(90, today)
    }
}
