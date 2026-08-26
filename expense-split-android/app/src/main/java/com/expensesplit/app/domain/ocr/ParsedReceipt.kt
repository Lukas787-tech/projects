package com.expensesplit.app.domain.ocr

import java.time.LocalDate

/** The structured result of running [ReceiptParser] over an OCR text block. */
data class ParsedReceipt(
    val merchant: String?,
    val purchasedAt: LocalDate?,
    val totalMinor: Long?,
    val taxMinor: Long?,
    val currency: String?,
    val items: List<ParsedItem>,
    val rawText: String,
    /** 0..1 — how much of the receipt the parser is confident it understood. */
    val confidence: Float,
) {
    val needsReview: Boolean get() = confidence < 0.6f || totalMinor == null
}

data class ParsedItem(
    val name: String,
    val normalizedName: String,
    val quantity: Double,
    val unitPriceMinor: Long,
    val totalPriceMinor: Long,
)
