package com.expensesplit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.RawQuery
import com.expensesplit.app.data.local.entity.ExpenseEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ExpenseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ExpenseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(expenses: List<ExpenseEntity>): List<Long>

    @Update
    suspend fun update(expense: ExpenseEntity)

    @Delete
    suspend fun delete(expense: ExpenseEntity)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: Long): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE id = :id")
    fun observeById(id: Long): Flow<ExpenseEntity?>

    @Query("SELECT * FROM expenses ORDER BY date DESC, createdAt DESC")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses ORDER BY date DESC, createdAt DESC")
    suspend fun getAll(): List<ExpenseEntity>

    @Query("SELECT * FROM expenses ORDER BY date DESC, createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExpenseEntity>>

    @Query(
        """
        SELECT * FROM expenses
        WHERE date >= :start AND date <= :end
        ORDER BY date DESC, createdAt DESC
        """
    )
    fun observeInRange(start: LocalDate, end: LocalDate): Flow<List<ExpenseEntity>>

    @Query(
        """
        SELECT * FROM expenses
        WHERE date >= :start AND date <= :end
        ORDER BY date DESC, createdAt DESC
        """
    )
    suspend fun getInRange(start: LocalDate, end: LocalDate): List<ExpenseEntity>

    @Query("SELECT COALESCE(SUM(baseAmountMinor), 0) FROM expenses WHERE date >= :start AND date <= :end")
    suspend fun sumInRange(start: LocalDate, end: LocalDate): Long

    @Query("SELECT COALESCE(SUM(baseAmountMinor), 0) FROM expenses WHERE date >= :start AND date <= :end")
    fun observeSumInRange(start: LocalDate, end: LocalDate): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(baseAmountMinor), 0) FROM expenses
        WHERE categoryId = :categoryId AND date >= :start AND date <= :end
        """
    )
    suspend fun sumForCategoryInRange(categoryId: Long, start: LocalDate, end: LocalDate): Long

    @Query(
        """
        SELECT categoryId AS categoryId,
               COALESCE(SUM(baseAmountMinor), 0) AS totalMinor,
               COUNT(*) AS transactionCount
        FROM expenses
        WHERE date >= :start AND date <= :end
        GROUP BY categoryId
        ORDER BY totalMinor DESC
        """
    )
    fun observeCategoryTotals(start: LocalDate, end: LocalDate): Flow<List<CategoryTotal>>

    @Query(
        """
        SELECT categoryId AS categoryId,
               COALESCE(SUM(baseAmountMinor), 0) AS totalMinor,
               COUNT(*) AS transactionCount
        FROM expenses
        WHERE date >= :start AND date <= :end
        GROUP BY categoryId
        ORDER BY totalMinor DESC
        """
    )
    suspend fun getCategoryTotals(start: LocalDate, end: LocalDate): List<CategoryTotal>

    @Query(
        """
        SELECT date AS date,
               COALESCE(SUM(baseAmountMinor), 0) AS totalMinor
        FROM expenses
        WHERE date >= :start AND date <= :end
        GROUP BY date
        ORDER BY date ASC
        """
    )
    suspend fun getDailyTotals(start: LocalDate, end: LocalDate): List<DayTotal>

    /**
     * `date` holds epoch days, so it is multiplied back into seconds before strftime sees it.
     * 'unixepoch' tells SQLite the value is a UTC timestamp rather than a Julian day.
     */
    @Query(
        """
        SELECT strftime('%Y-%m', date * 86400, 'unixepoch') AS monthKey,
               COALESCE(SUM(baseAmountMinor), 0) AS totalMinor,
               COUNT(*) AS transactionCount
        FROM expenses
        WHERE date >= :start AND date <= :end
        GROUP BY monthKey
        ORDER BY monthKey ASC
        """
    )
    suspend fun getMonthlyTotals(start: LocalDate, end: LocalDate): List<MonthTotal>

    @Query(
        """
        SELECT merchant AS merchant,
               COALESCE(SUM(baseAmountMinor), 0) AS totalMinor,
               COUNT(*) AS visits
        FROM expenses
        WHERE merchant IS NOT NULL AND TRIM(merchant) != ''
          AND date >= :start AND date <= :end
        GROUP BY LOWER(merchant)
        ORDER BY totalMinor DESC
        LIMIT :limit
        """
    )
    suspend fun getTopMerchants(start: LocalDate, end: LocalDate, limit: Int): List<MerchantTotal>

    @Query("SELECT * FROM expenses WHERE date >= :start AND date <= :end ORDER BY baseAmountMinor DESC LIMIT 1")
    suspend fun getLargestInRange(start: LocalDate, end: LocalDate): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE receiptId = :receiptId LIMIT 1")
    suspend fun getByReceiptId(receiptId: Long): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE recurringRuleId = :ruleId ORDER BY date DESC")
    suspend fun getByRecurringRule(ruleId: Long): List<ExpenseEntity>

    @Query("SELECT DISTINCT merchant FROM expenses WHERE merchant IS NOT NULL AND TRIM(merchant) != '' ORDER BY merchant")
    suspend fun getKnownMerchants(): List<String>

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun count(): Int

    /** Backing query for the advanced-search screen, assembled by SearchQueryBuilder. */
    @Transaction
    @RawQuery(observedEntities = [ExpenseEntity::class])
    fun searchRaw(query: SupportSQLiteQuery): Flow<List<ExpenseEntity>>
}
