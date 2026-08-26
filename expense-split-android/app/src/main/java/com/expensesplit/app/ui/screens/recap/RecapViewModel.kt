package com.expensesplit.app.ui.screens.recap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.core.DateRange
import com.expensesplit.app.data.export.CsvExporter
import com.expensesplit.app.data.export.FileSharer
import com.expensesplit.app.data.export.PdfExporter
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.repository.AnalyticsRepository
import com.expensesplit.app.data.repository.CategoryRepository
import com.expensesplit.app.data.repository.ExpenseRepository
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.MonthlyRecap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class RecapUiState(
    val month: YearMonth = YearMonth.from(LocalDate.now()),
    val recap: MonthlyRecap? = null,
    val isLoading: Boolean = true,
    val isExporting: Boolean = false,
    val baseCurrency: String = "USD",
) {
    /** Guard against paging into months where the app has no data at all. */
    val canGoForward: Boolean get() = month < YearMonth.from(LocalDate.now())
}

@HiltViewModel
class RecapViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val pdfExporter: PdfExporter,
    private val csvExporter: CsvExporter,
    private val fileSharer: FileSharer,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecapUiState())
    val uiState: StateFlow<RecapUiState> = _uiState.asStateFlow()

    private val _exportedFile = MutableStateFlow<File?>(null)
    val exportedFile: StateFlow<File?> = _exportedFile.asStateFlow()

    init {
        load(YearMonth.from(LocalDate.now()))
    }

    private fun load(month: YearMonth) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, month = month) }
            val baseCurrency = preferencesRepository.preferences.first().baseCurrency
            val recap = analyticsRepository.monthlyRecap(month, baseCurrency)

            _uiState.update {
                it.copy(recap = recap, baseCurrency = baseCurrency, isLoading = false)
            }
        }
    }

    fun previousMonth() = load(_uiState.value.month.minusMonths(1))

    fun nextMonth() {
        if (_uiState.value.canGoForward) load(_uiState.value.month.plusMonths(1))
    }

    fun categoryName(category: Category?): String =
        category?.let { categoryRepository.displayName(it) }.orEmpty()

    fun exportPdf() {
        val state = _uiState.value
        val recap = state.recap ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            val expenses = expenseRepository.getExpensesIn(DateRange.ofMonth(state.month))
            _exportedFile.value = pdfExporter.exportRecap(recap, expenses)
            _uiState.update { it.copy(isExporting = false) }
        }
    }

    fun exportCsv() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            val expenses = expenseRepository.getExpensesIn(DateRange.ofMonth(state.month))
            _exportedFile.value = csvExporter.exportExpenses(expenses, state.baseCurrency)
            _uiState.update { it.copy(isExporting = false) }
        }
    }

    fun shareIntentFor(file: File) = fileSharer.shareIntent(file)

    fun onExportHandled() {
        _exportedFile.value = null
    }
}
