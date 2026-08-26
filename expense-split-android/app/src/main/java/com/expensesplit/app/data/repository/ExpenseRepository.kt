package com.expensesplit.app.data.repository

import com.expensesplit.app.core.DateRange
import com.expensesplit.app.data.local.dao.ExpenseDao
import com.expensesplit.app.data.local.dao.MerchantTotal
import com.expensesplit.app.data.local.dao.MonthTotal
import com.expensesplit.app.domain.model.Expense
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpenseRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val currencyRepository: CurrencyRepository,
) {

    val allExpenses: Flow<List<Expense>> =
        expenseDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun recentExpenses(limit: Int = 10): Flow<List<Expense>> =
        expenseDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun expensesIn(range: DateRange): Flow<List<Expense>> =
        expenseDao.observeInRange(range.start, range.endInclusive)
            .map { list -> list.map { it.toDomain() } }

    fun observeExpense(id: Long): Flow<Expense?> =
        expenseDao.observeById(id).map { it?.toDomain() }

    suspend fun getExpense(id: Long): Expense? = expenseDao.getById(id)?.toDomain()

    suspend fun getExpensesIn(range: DateRange): List<Expense> =
        expenseDao.getInRange(range.start, range.endInclusive).map { it.toDomain() }

    suspend fun getAll(): List<Expense> = expenseDao.getAll().map { it.toDomain() }

    /**
     * Saves an expense, filling in the base-currency figure from the live FX rate when the expense
     * was recorded in a foreign currency. The rate is stored alongside so past reports stay stable.
     */
    suspend fun save(expense: Expense, baseCurrency: String): Long {
        val prepared = withBaseAmount(expense, baseCurrency)
        return if (prepared.id == 0L) {
            expenseDao.insert(prepared.toEntity())
        } else {
            expenseDao.update(prepared.copy(updatedAt = System.currentTimeMillis()).toEntity())
            prepared.id
        }
    }

    suspend fun saveAll(expenses: List<Expense>, baseCurrency: String): List<Long> {
        val prepared = expenses.map { withBaseAmount(it, baseCurrency) }
        return expenseDao.insertAll(prepared.map { it.toEntity() })
    }

    private suspend fun withBaseAmount(expense: Expense, baseCurrency: String): Expense {
        if (expense.currency.equals(baseCurrency, ignoreCase = true)) {
            return expense.copy(baseAmountMinor = expense.amountMinor, fxRate = 1.0)
        }
        val rate = currencyRepository.rate(expense.currency, baseCurrency)
        return expense.copy(
            fxRate = rate,
            baseAmountMinor = com.expensesplit.app.core.Money.convert(
                expense.amountMinor,
                rate,
                expense.currency,
                baseCurrency,
            ),
        )
    }

    suspend fun delete(id: Long) = expenseDao.deleteById(id)

    suspend fun deleteAll() = expenseDao.deleteAll()

    suspend fun sumIn(range: DateRange): Long = expenseDao.sumInRange(range.start, range.endInclusive)

    fun observeSumIn(range: DateRange): Flow<Long> =
        expenseDao.observeSumInRange(range.start, range.endInclusive)

    suspend fun monthlyTotals(range: DateRange): List<MonthTotal> =
        expenseDao.getMonthlyTotals(range.start, range.endInclusive)

    suspend fun topMerchants(range: DateRange, limit: Int = 5): List<MerchantTotal> =
        expenseDao.getTopMerchants(range.start, range.endInclusive, limit)

    suspend fun knownMerchants(): List<String> = expenseDao.getKnownMerchants()

    suspend fun count(): Int = expenseDao.count()

    suspend fun expensesForMonth(month: YearMonth): List<Expense> =
        getExpensesIn(DateRange.ofMonth(month))

    suspend fun getByReceipt(receiptId: Long): Expense? =
        expenseDao.getByReceiptId(receiptId)?.toDomain()
}
