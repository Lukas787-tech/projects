package com.expensesplit.app.domain.model

import java.time.LocalDate

data class Expense(
    val id: Long = 0,
    val title: String,
    val note: String? = null,
    val categoryId: Long,
    /** Amount in the currency it was actually paid in. */
    val amountMinor: Long,
    val currency: String,
    /** Amount converted into the user's base currency at the rate captured when it was saved. */
    val baseAmountMinor: Long,
    val fxRate: Double = 1.0,
    val date: LocalDate,
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val merchant: String? = null,
    val receiptId: Long? = null,
    val groupId: Long? = null,
    val recurringRuleId: Long? = null,
    val attachmentUri: String? = null,
    val isSettled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** An [Expense] joined with the pieces the UI needs to render a card without extra queries. */
data class ExpenseWithDetails(
    val expense: Expense,
    val category: Category?,
    val hasReceipt: Boolean,
    val groupName: String?,
)
