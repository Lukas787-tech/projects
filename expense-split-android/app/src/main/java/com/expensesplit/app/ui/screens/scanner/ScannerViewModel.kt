package com.expensesplit.app.ui.screens.scanner

import android.content.Context
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.core.Money
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.repository.ReceiptRepository
import com.expensesplit.app.domain.ocr.ItemNameNormalizer
import com.expensesplit.app.domain.ocr.ParsedItem
import com.expensesplit.app.domain.ocr.ParsedReceipt
import com.expensesplit.app.domain.ocr.ReceiptParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

enum class ScanStage { CAMERA, PROCESSING, REVIEW, ERROR }

data class ScannerUiState(
    val stage: ScanStage = ScanStage.CAMERA,
    val imageUri: String? = null,
    val parsed: ParsedReceipt? = null,
    val editableMerchant: String = "",
    val editableTotal: String = "",
    val editableDate: LocalDate = LocalDate.now(),
    val currency: String = "USD",
    val items: List<ParsedItem> = emptyList(),
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
    val savedReceiptId: Long? = null,
) {
    /** Prompts a "check these numbers" banner when the parse was shaky. */
    val needsReview: Boolean get() = parsed?.needsReview ?: false
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val receiptParser: ReceiptParser,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val preferences = preferencesRepository.preferences.first()
            _uiState.update { it.copy(currency = preferences.baseCurrency) }
        }
    }

    /** Captures from the live camera, then runs OCR over the result. */
    fun captureAndScan(context: Context, imageCapture: ImageCapture) {
        viewModelScope.launch {
            _uiState.update { it.copy(stage = ScanStage.PROCESSING, errorMessage = null) }
            runCatching {
                val target = ReceiptScanner.receiptImageFile(context)
                val captured = ReceiptScanner.capture(imageCapture, target)
                ReceiptScanner.compressForStorage(captured)
                captured
            }.onSuccess { file ->
                processImage(context, Uri.fromFile(file), file.toURI().toString())
            }.onFailure { error ->
                _uiState.update {
                    it.copy(stage = ScanStage.ERROR, errorMessage = error.message)
                }
            }
        }
    }

    /** Scans an image the user picked from the gallery instead of shooting a new one. */
    fun scanFromGallery(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(stage = ScanStage.PROCESSING, errorMessage = null) }
            processImage(context, uri, uri.toString())
        }
    }

    private suspend fun processImage(context: Context, uri: Uri, storedUri: String) {
        runCatching {
            val text = ReceiptScanner.recognizeText(context, uri)
            receiptParser.parse(text)
        }.onSuccess { parsed ->
            val currency = parsed.currency ?: _uiState.value.currency
            _uiState.update { state ->
                state.copy(
                    stage = ScanStage.REVIEW,
                    imageUri = storedUri,
                    parsed = parsed,
                    editableMerchant = parsed.merchant.orEmpty(),
                    editableTotal = parsed.totalMinor
                        ?.let { Money.toEditableString(it, currency) }
                        .orEmpty(),
                    editableDate = parsed.purchasedAt ?: LocalDate.now(),
                    currency = currency,
                    items = parsed.items,
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(stage = ScanStage.ERROR, errorMessage = error.message)
            }
        }
    }

    fun onMerchantChanged(value: String) = _uiState.update { it.copy(editableMerchant = value) }

    fun onTotalChanged(value: String) = _uiState.update { it.copy(editableTotal = value) }

    fun onDateChanged(value: LocalDate) = _uiState.update { it.copy(editableDate = value) }

    fun onCurrencyChanged(code: String) = _uiState.update { it.copy(currency = code) }

    fun onItemRemoved(index: Int) = _uiState.update { state ->
        state.copy(items = state.items.filterIndexed { position, _ -> position != index })
    }

    fun onItemEdited(index: Int, name: String, priceText: String) = _uiState.update { state ->
        val price = Money.parseToMinor(priceText, state.currency) ?: return@update state
        val updated = state.items.toMutableList()
        val existing = updated.getOrNull(index) ?: return@update state
        updated[index] = existing.copy(
            name = name,
            normalizedName = ItemNameNormalizer.normalize(name),
            totalPriceMinor = price,
            unitPriceMinor = if (existing.quantity > 0) (price / existing.quantity).toLong() else price,
        )
        state.copy(items = updated)
    }

    fun addManualItem(name: String, priceText: String) = _uiState.update { state ->
        val price = Money.parseToMinor(priceText, state.currency) ?: return@update state
        state.copy(
            items = state.items + ParsedItem(
                name = name,
                normalizedName = ItemNameNormalizer.normalize(name),
                quantity = 1.0,
                unitPriceMinor = price,
                totalPriceMinor = price,
            ),
        )
    }

    fun retake() {
        _uiState.update {
            ScannerUiState(currency = it.currency)
        }
    }

    fun save() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }

            val totalMinor = Money.parseToMinor(state.editableTotal, state.currency)
                ?: state.items.sumOf { it.totalPriceMinor }

            // Persist whatever the user actually confirmed on the review screen, not the raw parse.
            val confirmed = ParsedReceipt(
                merchant = state.editableMerchant.trim().takeIf { it.isNotBlank() },
                purchasedAt = state.editableDate,
                totalMinor = totalMinor,
                taxMinor = state.parsed?.taxMinor,
                currency = state.currency,
                items = state.items,
                rawText = state.parsed?.rawText.orEmpty(),
                confidence = state.parsed?.confidence ?: 0f,
            )

            val receiptId = receiptRepository.saveParsed(
                parsed = confirmed,
                imageUri = state.imageUri,
                fallbackCurrency = state.currency,
            )
            _uiState.update { it.copy(isSaving = false, savedReceiptId = receiptId) }
        }
    }

    /** Sum of the line items, so the review screen can flag a mismatch against the stated total. */
    fun itemsTotalMinor(): Long = _uiState.value.items.sumOf { it.totalPriceMinor }

    fun deleteCapturedImage() {
        val uriString = _uiState.value.imageUri ?: return
        runCatching {
            val path = Uri.parse(uriString).path ?: return
            File(path).takeIf { it.exists() }?.delete()
        }
    }
}
