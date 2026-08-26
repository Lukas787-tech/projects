package com.expensesplit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Bulk deletes used by backup restore.
 *
 * Room's own `clearAllTables()` cannot be called from inside a suspending transaction — it asserts
 * against exactly that — so restore clears the tables itself and keeps the whole wipe-and-reinsert
 * inside one transaction that can roll back cleanly.
 */
@Dao
interface MaintenanceDao {

    @Query("DELETE FROM expenses")
    suspend fun clearExpenses()

    @Query("DELETE FROM receipt_items")
    suspend fun clearReceiptItems()

    @Query("DELETE FROM receipts")
    suspend fun clearReceipts()

    @Query("DELETE FROM bill_shares")
    suspend fun clearBillShares()

    @Query("DELETE FROM bills")
    suspend fun clearBills()

    @Query("DELETE FROM settlements")
    suspend fun clearSettlements()

    @Query("DELETE FROM members")
    suspend fun clearMembers()

    @Query("DELETE FROM groups")
    suspend fun clearGroups()

    @Query("DELETE FROM budgets")
    suspend fun clearBudgets()

    @Query("DELETE FROM recurring_rules")
    suspend fun clearRecurringRules()

    @Query("DELETE FROM price_points")
    suspend fun clearPricePoints()

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    /** Child rows first so foreign keys are never left dangling mid-wipe. */
    @Transaction
    suspend fun clearUserData() {
        clearExpenses()
        clearReceiptItems()
        clearReceipts()
        clearBillShares()
        clearBills()
        clearSettlements()
        clearMembers()
        clearGroups()
        clearBudgets()
        clearRecurringRules()
        clearPricePoints()
        clearCategories()
    }
}
