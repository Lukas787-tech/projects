package com.expensesplit.app.domain.model

import java.time.LocalDate

data class Receipt(
    val id: Long = 0,
    val expenseId: Long? = null,
    val imageUri: String?,
    val merchant: String?,
    val purchasedAt: LocalDate,
    val totalMinor: Long,
    val taxMinor: Long = 0,
    val currency: String,
    val rawText: String? = null,
    /** 0..1 confidence reported by the OCR parser; low values prompt a manual review banner. */
    val scanConfidence: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
)

data class ReceiptItem(
    val id: Long = 0,
    val receiptId: Long,
    val name: String,
    /** Lower-cased, punctuation-stripped name used to match the same product across receipts. */
    val normalizedName: String,
    val quantity: Double = 1.0,
    val unitPriceMinor: Long,
    val totalPriceMinor: Long,
    val currency: String,
    val categoryId: Long? = null,
)

data class ReceiptWithItems(
    val receipt: Receipt,
    val items: List<ReceiptItem>,
)
