package com.expensesplit.app.ui.screens.receipt

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.data.export.FileSharer
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.repository.ExpenseRepository
import com.expensesplit.app.data.repository.PriceRepository
import com.expensesplit.app.data.repository.ReceiptRepository
import com.expensesplit.app.domain.model.DuplicatePurchase
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.PriceHistory
import com.expensesplit.app.domain.model.Receipt
import com.expensesplit.app.domain.model.ReceiptItem
import com.expensesplit.app.domain.model.SavingOpportunity
import com.expensesplit.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Grid of every stored receipt image. */
@HiltViewModel
class ReceiptGalleryViewModel @Inject constructor(
    receiptRepository: ReceiptRepository,
) : ViewModel() {

    val receipts: StateFlow<List<Receipt>> = receiptRepository.allReceipts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasImages: StateFlow<Boolean> = receipts
        .map { list -> list.any { !it.imageUri.isNullOrBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

data class ReceiptDetailUiState(
    val receipt: Receipt? = null,
    val items: List<ReceiptItem> = emptyList(),
    val linkedExpense: Expense? = null,
    /** Cheaper prices for items on this receipt, seen at other stores. */
    val savings: List<SavingOpportunity> = emptyList(),
    val repeatPurchases: List<DuplicatePurchase> = emptyList(),
    val baseCurrency: String = "USD",
    val isLoading: Boolean = true,
) {
    val totalSavingMinor: Long get() = savings.sumOf { it.savingMinor }
}

@HiltViewModel
class ReceiptDetailViewModel @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val priceRepository: PriceRepository,
    private val expenseRepository: ExpenseRepository,
    private val preferencesRepository: PreferencesRepository,
    private val fileSharer: FileSharer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val receiptId: Long =
        savedStateHandle.get<String>(Routes.ARG_RECEIPT_ID)?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(ReceiptDetailUiState())
    val uiState: StateFlow<ReceiptDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val bundle = receiptRepository.getReceiptWithItems(receiptId)
            val itemNames = bundle?.items?.map { it.normalizedName }?.toSet().orEmpty()

            // Every read happens once, before the state update: `update` runs its lambda in a
            // compare-and-set loop and would otherwise repeat these queries on contention.
            val linkedExpense = expenseRepository.getByReceipt(receiptId)
            val baseCurrency = preferencesRepository.preferences.first().baseCurrency
            // Only surface comparisons for what is actually on this receipt.
            val savings = priceRepository.savingOpportunities()
                .filter { saving -> saving.normalizedItemName in itemNames }
            val repeats = receiptRepository.repeatPurchases()
                .filter { duplicate -> duplicate.normalizedItemName in itemNames }

            _uiState.update {
                it.copy(
                    receipt = bundle?.receipt,
                    items = bundle?.items.orEmpty(),
                    linkedExpense = linkedExpense,
                    savings = savings,
                    repeatPurchases = repeats,
                    baseCurrency = baseCurrency,
                    isLoading = false,
                )
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            receiptRepository.delete(receiptId)
            onDone()
        }
    }

    fun shareImageIntent(uri: String, caption: String) =
        fileSharer.shareImageIntent(Uri.parse(uri), caption)

    /** Refreshes offers from the configured price feed, if one is set up. */
    fun refreshOffers() {
        viewModelScope.launch {
            val state = _uiState.value
            val currency = state.receipt?.currency ?: return@launch
            state.items.forEach { item ->
                priceRepository.refreshOffers(item.name, currency)
            }
            load()
        }
    }
}

data class PriceHistoryUiState(
    val history: PriceHistory? = null,
    val savings: SavingOpportunity? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class PriceHistoryViewModel @Inject constructor(
    private val priceRepository: PriceRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemName: String =
        savedStateHandle.get<String>(Routes.ARG_ITEM_NAME).orEmpty()

    private val _uiState = MutableStateFlow(PriceHistoryUiState())
    val uiState: StateFlow<PriceHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val history = priceRepository.history(itemName)
            val saving = priceRepository.savingOpportunities()
                .firstOrNull { it.normalizedItemName == history.normalizedItemName }

            _uiState.update { it.copy(history = history, savings = saving, isLoading = false) }
        }
    }
}
