package com.expensesplit.app.ui.screens.addexpense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.core.Money
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.repository.CategoryRepository
import com.expensesplit.app.data.repository.CurrencyRepository
import com.expensesplit.app.data.repository.ExpenseRepository
import com.expensesplit.app.data.repository.GroupRepository
import com.expensesplit.app.data.repository.ReceiptRepository
import com.expensesplit.app.data.repository.RecurringRepository
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.ExpenseGroup
import com.expensesplit.app.domain.model.PaymentMethod
import com.expensesplit.app.domain.model.RecurrenceFrequency
import com.expensesplit.app.domain.model.RecurringRule
import com.expensesplit.app.domain.model.advance
import com.expensesplit.app.domain.ocr.AutoCategorizer
import com.expensesplit.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Validation problems the form surfaces inline rather than through a toast. */
enum class ExpenseFormError { AMOUNT_MISSING, AMOUNT_INVALID, AMOUNT_ZERO, TITLE_MISSING }

data class AddExpenseUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val expenseId: Long = 0,
    val amountText: String = "",
    val title: String = "",
    val merchant: String = "",
    val note: String = "",
    val currency: String = "USD",
    val baseCurrency: String = "USD",
    val categoryId: Long = 0,
    val date: LocalDate = LocalDate.now(),
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val groupId: Long? = null,
    val receiptId: Long? = null,
    val attachmentUri: String? = null,
    val isRecurring: Boolean = false,
    val recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    val recurrenceInterval: Int = 1,
    val categories: List<Category> = emptyList(),
    val groups: List<ExpenseGroup> = emptyList(),
    val currencies: List<String> = emptyList(),
    val knownMerchants: List<String> = emptyList(),
    val errors: Set<ExpenseFormError> = emptySet(),
    val isSaving: Boolean = false,
    val savedExpenseId: Long? = null,
    /** Set when the amount was entered in a non-base currency and a rate was applied. */
    val convertedPreview: String? = null,
    /** True when the category was chosen by the auto-categorizer rather than the user. */
    val categoryWasSuggested: Boolean = false,
)

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val currencyRepository: CurrencyRepository,
    private val groupRepository: GroupRepository,
    private val receiptRepository: ReceiptRepository,
    private val recurringRepository: RecurringRepository,
    private val preferencesRepository: PreferencesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editingExpenseId: Long =
        savedStateHandle.get<String>(Routes.ARG_EXPENSE_ID)?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(AddExpenseUiState())
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val preferences = preferencesRepository.preferences.first()
        val categories = categoryRepository.getAll()
        val groups = groupRepository.getAllGroups().filterNot { it.archived }
        val fallbackCategoryId = categoryRepository.uncategorizedId()

        val existing = editingExpenseId.takeIf { it != 0L }?.let { expenseRepository.getExpense(it) }
        val knownMerchants = expenseRepository.knownMerchants()

        _uiState.update {
            it.copy(
                isLoading = false,
                isEditing = existing != null,
                expenseId = existing?.id ?: 0L,
                amountText = existing?.let { expense ->
                    Money.toEditableString(expense.amountMinor, expense.currency)
                }.orEmpty(),
                title = existing?.title.orEmpty(),
                merchant = existing?.merchant.orEmpty(),
                note = existing?.note.orEmpty(),
                currency = existing?.currency ?: preferences.baseCurrency,
                baseCurrency = preferences.baseCurrency,
                categoryId = existing?.categoryId ?: fallbackCategoryId,
                date = existing?.date ?: LocalDate.now(),
                paymentMethod = existing?.paymentMethod ?: PaymentMethod.CARD,
                groupId = existing?.groupId,
                receiptId = existing?.receiptId,
                attachmentUri = existing?.attachmentUri,
                categories = categories,
                groups = groups,
                currencies = currencyRepository.supportedCurrencies,
                knownMerchants = knownMerchants,
            )
        }
        refreshConversionPreview()
    }

    /** Prefills the form from a receipt the scanner just saved. */
    fun applyScannedReceipt(receiptId: Long) {
        viewModelScope.launch {
            val bundle = receiptRepository.getReceiptWithItems(receiptId) ?: return@launch
            val receipt = bundle.receipt
            val categories = _uiState.value.categories.ifEmpty { categoryRepository.getAll() }

            val suggestion = AutoCategorizer.categorize(
                categories = categories,
                merchant = receipt.merchant,
                title = receipt.merchant,
                itemNames = bundle.items.map { it.name },
                fallbackCategoryId = categoryRepository.uncategorizedId(),
            )

            _uiState.update { state ->
                state.copy(
                    amountText = Money.toEditableString(receipt.totalMinor, receipt.currency),
                    title = state.title.ifBlank {
                        receipt.merchant ?: state.title
                    },
                    merchant = receipt.merchant.orEmpty(),
                    currency = receipt.currency,
                    date = receipt.purchasedAt,
                    receiptId = receiptId,
                    attachmentUri = receipt.imageUri,
                    categoryId = suggestion.categoryId,
                    categoryWasSuggested = suggestion.confidence > 0.3f,
                    errors = emptySet(),
                )
            }
            refreshConversionPreview()
        }
    }

    fun onAmountChanged(value: String) {
        // Keep the raw text so the user's caret and separators survive; parse only on save.
        _uiState.update { it.copy(amountText = value, errors = it.errors - AMOUNT_ERRORS) }
        refreshConversionPreview()
    }

    fun onTitleChanged(value: String) {
        _uiState.update { it.copy(title = value, errors = it.errors - ExpenseFormError.TITLE_MISSING) }
    }

    fun onMerchantChanged(value: String) {
        _uiState.update { it.copy(merchant = value) }
        suggestCategoryFromText()
    }

    fun onNoteChanged(value: String) = _uiState.update { it.copy(note = value) }

    fun onCurrencyChanged(code: String) {
        _uiState.update { it.copy(currency = code) }
        refreshConversionPreview()
    }

    fun onCategorySelected(categoryId: Long) =
        _uiState.update { it.copy(categoryId = categoryId, categoryWasSuggested = false) }

    fun onDateChanged(date: LocalDate) = _uiState.update { it.copy(date = date) }

    fun onPaymentMethodChanged(method: PaymentMethod) =
        _uiState.update { it.copy(paymentMethod = method) }

    fun onGroupChanged(groupId: Long?) = _uiState.update { it.copy(groupId = groupId) }

    fun onAttachmentChanged(uri: String?) = _uiState.update { it.copy(attachmentUri = uri) }

    fun onRecurringToggled(enabled: Boolean) = _uiState.update { it.copy(isRecurring = enabled) }

    fun onRecurrenceChanged(frequency: RecurrenceFrequency, interval: Int) =
        _uiState.update { it.copy(recurrenceFrequency = frequency, recurrenceInterval = interval.coerceAtLeast(1)) }

    fun save() {
        val state = _uiState.value
        val errors = validate(state)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }

        val amountMinor = Money.parseToMinor(state.amountText, state.currency) ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }

            val expense = Expense(
                id = state.expenseId,
                title = state.title.trim(),
                note = state.note.trim().takeIf { it.isNotBlank() },
                categoryId = state.categoryId,
                amountMinor = amountMinor,
                currency = state.currency,
                // Filled in by the repository from the live rate; a placeholder until then.
                baseAmountMinor = amountMinor,
                date = state.date,
                paymentMethod = state.paymentMethod,
                merchant = state.merchant.trim().takeIf { it.isNotBlank() },
                receiptId = state.receiptId,
                groupId = state.groupId,
                attachmentUri = state.attachmentUri,
            )

            val savedId = expenseRepository.save(expense, state.baseCurrency)
            state.receiptId?.let { receiptRepository.linkToExpense(it, savedId) }

            if (state.isRecurring && !state.isEditing) {
                createRecurringRule(state, amountMinor)
            }

            _uiState.update { it.copy(isSaving = false, savedExpenseId = savedId) }
        }
    }

    private suspend fun createRecurringRule(state: AddExpenseUiState, amountMinor: Long) {
        val rule = RecurringRule(
            title = state.title.trim(),
            categoryId = state.categoryId,
            amountMinor = amountMinor,
            currency = state.currency,
            paymentMethod = state.paymentMethod,
            merchant = state.merchant.trim().takeIf { it.isNotBlank() },
            frequency = state.recurrenceFrequency,
            interval = state.recurrenceInterval,
            // The expense just saved covers this period; schedule the next one.
            nextRunDate = state.recurrenceFrequency.advance(state.date, state.recurrenceInterval),
            lastRunDate = state.date,
        )
        recurringRepository.save(rule)
    }

    fun delete() {
        val id = _uiState.value.expenseId
        if (id == 0L) return
        viewModelScope.launch {
            expenseRepository.delete(id)
            _uiState.update { it.copy(savedExpenseId = id) }
        }
    }

    fun categoryName(category: Category): String = categoryRepository.displayName(category)

    private fun validate(state: AddExpenseUiState): Set<ExpenseFormError> {
        val errors = mutableSetOf<ExpenseFormError>()
        when {
            state.amountText.isBlank() -> errors += ExpenseFormError.AMOUNT_MISSING
            else -> {
                val parsed = Money.parseToMinor(state.amountText, state.currency)
                when {
                    parsed == null -> errors += ExpenseFormError.AMOUNT_INVALID
                    parsed <= 0L -> errors += ExpenseFormError.AMOUNT_ZERO
                }
            }
        }
        if (state.title.isBlank()) errors += ExpenseFormError.TITLE_MISSING
        return errors
    }

    /** Shows what a foreign-currency amount comes to in the base currency before saving. */
    private fun refreshConversionPreview() {
        val state = _uiState.value
        if (state.currency.equals(state.baseCurrency, ignoreCase = true)) {
            _uiState.update { it.copy(convertedPreview = null) }
            return
        }
        val amountMinor = Money.parseToMinor(state.amountText, state.currency)
        if (amountMinor == null || amountMinor <= 0) {
            _uiState.update { it.copy(convertedPreview = null) }
            return
        }

        viewModelScope.launch {
            val converted = currencyRepository.convert(amountMinor, state.currency, state.baseCurrency)
            _uiState.update {
                it.copy(convertedPreview = Money.format(converted, state.baseCurrency))
            }
        }
    }

    /** Re-runs auto-categorization when the merchant changes, unless the user picked a category. */
    private fun suggestCategoryFromText() {
        val state = _uiState.value
        if (!state.categoryWasSuggested && state.categoryId != 0L && state.isEditing) return
        if (state.merchant.isBlank()) return

        viewModelScope.launch {
            val suggestion = AutoCategorizer.categorize(
                categories = state.categories,
                merchant = state.merchant,
                title = state.title,
                fallbackCategoryId = categoryRepository.uncategorizedId(),
            )
            if (suggestion.confidence > 0.4f) {
                _uiState.update { it.copy(categoryId = suggestion.categoryId, categoryWasSuggested = true) }
            }
        }
    }

    private companion object {
        val AMOUNT_ERRORS = setOf(
            ExpenseFormError.AMOUNT_MISSING,
            ExpenseFormError.AMOUNT_INVALID,
            ExpenseFormError.AMOUNT_ZERO,
        )
    }
}
