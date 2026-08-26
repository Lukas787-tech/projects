package com.expensesplit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.expensesplit.app.data.local.entity.ReceiptEntity
import com.expensesplit.app.data.local.entity.ReceiptItemEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ReceiptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: ReceiptEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ReceiptItemEntity>): List<Long>

    @Update
    suspend fun updateReceipt(receipt: ReceiptEntity)

    @Delete
    suspend fun deleteReceipt(receipt: ReceiptEntity)

    @Query("DELETE FROM receipts WHERE id = :id")
    suspend fun deleteReceiptById(id: Long)

    @Query("DELETE FROM receipt_items WHERE receiptId = :receiptId")
    suspend fun deleteItemsForReceipt(receiptId: Long)

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun getReceipt(id: Long): ReceiptEntity?

    @Query("SELECT * FROM receipts WHERE id = :id")
    fun observeReceipt(id: Long): Flow<ReceiptEntity?>

    @Query("SELECT * FROM receipts ORDER BY purchasedAt DESC, createdAt DESC")
    fun observeAllReceipts(): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts ORDER BY purchasedAt DESC, createdAt DESC")
    suspend fun getAllReceipts(): List<ReceiptEntity>

    @Query("SELECT * FROM receipts WHERE imageUri IS NOT NULL ORDER BY purchasedAt DESC")
    fun observeGallery(): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipt_items WHERE receiptId = :receiptId ORDER BY id ASC")
    suspend fun getItems(receiptId: Long): List<ReceiptItemEntity>

    @Query("SELECT * FROM receipt_items WHERE receiptId = :receiptId ORDER BY id ASC")
    fun observeItems(receiptId: Long): Flow<List<ReceiptItemEntity>>

    @Query("SELECT * FROM receipt_items ORDER BY id DESC")
    suspend fun getAllItems(): List<ReceiptItemEntity>

    @Query("SELECT * FROM receipt_items WHERE normalizedName = :normalizedName ORDER BY id DESC")
    suspend fun getItemsByNormalizedName(normalizedName: String): List<ReceiptItemEntity>

    @Query(
        """
        SELECT i.id AS itemId,
               i.receiptId AS receiptId,
               i.name AS name,
               i.normalizedName AS normalizedName,
               i.quantity AS quantity,
               i.unitPriceMinor AS unitPriceMinor,
               i.totalPriceMinor AS totalPriceMinor,
               i.currency AS currency,
               r.merchant AS merchant,
               r.purchasedAt AS purchasedAt
        FROM receipt_items i
        INNER JOIN receipts r ON r.id = i.receiptId
        WHERE i.normalizedName LIKE '%' || :query || '%' OR LOWER(i.name) LIKE '%' || :query || '%'
        ORDER BY r.purchasedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchItems(query: String, limit: Int = 200): List<ReceiptItemSearchRow>

    @Query(
        """
        SELECT i.normalizedName AS normalizedName,
               MAX(i.name) AS displayName,
               COUNT(*) AS occurrences,
               COALESCE(SUM(i.totalPriceMinor), 0) AS totalSpentMinor,
               MAX(i.currency) AS currency,
               MIN(r.purchasedAt) AS firstSeen,
               MAX(r.purchasedAt) AS lastSeen
        FROM receipt_items i
        INNER JOIN receipts r ON r.id = i.receiptId
        GROUP BY i.normalizedName
        HAVING occurrences >= :minOccurrences
        ORDER BY occurrences DESC, totalSpentMinor DESC
        LIMIT :limit
        """
    )
    suspend fun getRepeatPurchases(minOccurrences: Int = 2, limit: Int = 50): List<ItemPurchaseStat>

    @Query("SELECT COUNT(*) FROM receipts WHERE purchasedAt >= :start AND purchasedAt <= :end")
    suspend fun countInRange(start: LocalDate, end: LocalDate): Int
}
