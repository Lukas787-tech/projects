package com.expensesplit.app.data.local.dao

import java.time.LocalDate

/** Aggregate row shapes returned by the DAO layer. */

data class CategoryTotal(
    val categoryId: Long,
    val totalMinor: Long,
    val transactionCount: Int,
)

data class DayTotal(
    val date: LocalDate,
    val totalMinor: Long,
)

/** `monthKey` is `YYYY-MM`, produced by SQLite's strftime over the epoch-day column. */
data class MonthTotal(
    val monthKey: String,
    val totalMinor: Long,
    val transactionCount: Int,
)

data class MerchantTotal(
    val merchant: String,
    val totalMinor: Long,
    val visits: Int,
)

data class ItemPurchaseStat(
    val normalizedName: String,
    val displayName: String,
    val occurrences: Int,
    val totalSpentMinor: Long,
    val currency: String,
    val firstSeen: LocalDate,
    val lastSeen: LocalDate,
)

/** A receipt item joined with the receipt it belongs to, for item search results. */
data class ReceiptItemSearchRow(
    val itemId: Long,
    val receiptId: Long,
    val name: String,
    val normalizedName: String,
    val quantity: Double,
    val unitPriceMinor: Long,
    val totalPriceMinor: Long,
    val currency: String,
    val merchant: String?,
    val purchasedAt: LocalDate,
)
