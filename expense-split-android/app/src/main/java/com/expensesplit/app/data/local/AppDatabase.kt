package com.expensesplit.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.expensesplit.app.data.local.converter.Converters
import com.expensesplit.app.data.local.dao.BudgetDao
import com.expensesplit.app.data.local.dao.CategoryDao
import com.expensesplit.app.data.local.dao.ExpenseDao
import com.expensesplit.app.data.local.dao.GroupDao
import com.expensesplit.app.data.local.dao.MaintenanceDao
import com.expensesplit.app.data.local.dao.PriceDao
import com.expensesplit.app.data.local.dao.ReceiptDao
import com.expensesplit.app.data.local.dao.RecurringDao
import com.expensesplit.app.data.local.entity.BillEntity
import com.expensesplit.app.data.local.entity.BillShareEntity
import com.expensesplit.app.data.local.entity.BudgetEntity
import com.expensesplit.app.data.local.entity.CategoryEntity
import com.expensesplit.app.data.local.entity.ExpenseEntity
import com.expensesplit.app.data.local.entity.FxRateEntity
import com.expensesplit.app.data.local.entity.GroupEntity
import com.expensesplit.app.data.local.entity.MemberEntity
import com.expensesplit.app.data.local.entity.PricePointEntity
import com.expensesplit.app.data.local.entity.RecurringRuleEntity
import com.expensesplit.app.data.local.entity.ReceiptEntity
import com.expensesplit.app.data.local.entity.ReceiptItemEntity
import com.expensesplit.app.data.local.entity.SettlementEntity

@Database(
    entities = [
        CategoryEntity::class,
        ExpenseEntity::class,
        ReceiptEntity::class,
        ReceiptItemEntity::class,
        GroupEntity::class,
        MemberEntity::class,
        BillEntity::class,
        BillShareEntity::class,
        SettlementEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
        PricePointEntity::class,
        FxRateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun groupDao(): GroupDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun priceDao(): PriceDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val NAME = "expense_split.db"
    }
}
