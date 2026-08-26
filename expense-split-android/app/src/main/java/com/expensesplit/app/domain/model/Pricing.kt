package com.expensesplit.app.domain.model

import java.time.LocalDate

/** One observation of what a specific item cost at a specific store on a specific day. */
data class PricePoint(
    val id: Long = 0,
    val normalizedItemName: String,
    val displayName: String,
    val storeName: String,
    val unitPriceMinor: Long,
    val currency: String,
    val observedOn: LocalDate,
    val source: PriceSource = PriceSource.OWN_RECEIPT,
    val receiptItemId: Long? = null,
)

/** Aggregated price history for one item, used by the price-history chart and sale alerts. */
data class PriceHistory(
    val normalizedItemName: String,
    val displayName: String,
    val points: List<PricePoint>,
) {
    val currency: String get() = points.firstOrNull()?.currency ?: "USD"
    val latest: PricePoint? get() = points.maxByOrNull { it.observedOn }
    val cheapest: PricePoint? get() = points.minByOrNull { it.unitPriceMinor }
    val dearest: PricePoint? get() = points.maxByOrNull { it.unitPriceMinor }
    val averageMinor: Long get() = if (points.isEmpty()) 0 else points.sumOf { it.unitPriceMinor } / points.size

    /** Positive means the latest price is above the running average — a nudge to shop around. */
    val deltaFromAveragePercent: Float
        get() {
            val average = averageMinor
            val current = latest?.unitPriceMinor ?: return 0f
            if (average <= 0) return 0f
            return (current - average).toFloat() / average * 100f
        }
}

/** A concrete "you could pay less here" suggestion surfaced on receipt and item screens. */
data class SavingOpportunity(
    val normalizedItemName: String,
    val displayName: String,
    val paidMinor: Long,
    val paidAtStore: String,
    val bestMinor: Long,
    val bestStore: String,
    val currency: String,
    val observedOn: LocalDate,
) {
    val savingMinor: Long get() = (paidMinor - bestMinor).coerceAtLeast(0)
    val savingPercent: Float get() = if (paidMinor <= 0) 0f else savingMinor.toFloat() / paidMinor * 100f
}

/** Two purchases of the same item close together — often an accidental double buy. */
data class DuplicatePurchase(
    val normalizedItemName: String,
    val displayName: String,
    val occurrences: Int,
    val totalSpentMinor: Long,
    val currency: String,
    val firstSeen: LocalDate,
    val lastSeen: LocalDate,
    val averageDaysBetween: Double,
)
