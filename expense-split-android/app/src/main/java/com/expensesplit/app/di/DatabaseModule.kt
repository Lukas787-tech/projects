package com.expensesplit.app.di

import android.content.Context
import androidx.room.Room
import com.expensesplit.app.data.local.AppDatabase
import com.expensesplit.app.data.local.dao.BudgetDao
import com.expensesplit.app.data.local.dao.CategoryDao
import com.expensesplit.app.data.local.dao.ExpenseDao
import com.expensesplit.app.data.local.dao.GroupDao
import com.expensesplit.app.data.local.dao.MaintenanceDao
import com.expensesplit.app.data.local.dao.PriceDao
import com.expensesplit.app.data.local.dao.ReceiptDao
import com.expensesplit.app.data.local.dao.RecurringDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // Foreign keys are declared on the entities; SQLite still needs them switched on.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    fun provideExpenseDao(database: AppDatabase): ExpenseDao = database.expenseDao()

    @Provides
    fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

    @Provides
    fun provideReceiptDao(database: AppDatabase): ReceiptDao = database.receiptDao()

    @Provides
    fun provideGroupDao(database: AppDatabase): GroupDao = database.groupDao()

    @Provides
    fun provideBudgetDao(database: AppDatabase): BudgetDao = database.budgetDao()

    @Provides
    fun provideRecurringDao(database: AppDatabase): RecurringDao = database.recurringDao()

    @Provides
    fun providePriceDao(database: AppDatabase): PriceDao = database.priceDao()

    @Provides
    fun provideMaintenanceDao(database: AppDatabase): MaintenanceDao = database.maintenanceDao()
}
