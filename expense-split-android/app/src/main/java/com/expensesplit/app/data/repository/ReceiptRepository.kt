package com.expensesplit.app.data.repository

import com.expensesplit.app.data.local.dao.ReceiptDao
import com.expensesplit.app.data.local.dao.ReceiptItemSearchRow
import com.expensesplit.app.data.local.entity.PricePointEntity
import com.expensesplit.app.data.local.dao.PriceDao
import com.expensesplit.app.domain.model.DuplicatePurchase
import com.expensesplit.app.domain.model.PriceSource
import com.expensesplit.app.domain.model.Receipt
import com.expensesplit.app.domain.model.ReceiptItem
import com.expensesplit.app.domain.model.ReceiptWithItems
import com.expensesplit.app.domain.ocr.ParsedReceipt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptRepository @Inject constructor(
    private val receiptDao: ReceiptDao,
    private val priceDao: PriceDao,
) {

    val allReceipts: Flow<List<Receipt>> =
        receiptDao.observeAllReceipts().map { list -> list.map { it.toDomain() } }

    val gallery: Flow<List<Receipt>> =
        receiptDao.observeGallery().map { list -> list.map { it.toDomain() } }

    fun observeReceipt(id: Long): Flow<Receipt?> = receiptDao.observeReceipt(id).map { it?.toDomain() }

    fun observeItems(receiptId: Long): Flow<List<ReceiptItem>> =
        receiptDao.observeItems(receiptId).map { list -> list.map { it.toDomain() } }

    suspend fun getReceiptWithItems(id: Long): ReceiptWithItems? {
        val receipt = receiptDao.getReceipt(id)?.toDomain() ?: return null
        return ReceiptWithItems(receipt, receiptDao.getItems(id).map { it.toDomain() })
    }

    suspend fun getAllReceipts(): List<Receipt> = receiptDao.getAllReceipts().map { it.toDomain() }

    suspend fun getAllItems(): List<ReceiptItem> = receiptDao.getAllItems().map { it.toDomain() }

    /**
     * Persists a receipt with its line items and, in the same pass, records each item as a price
     * observation. Those observations are what make price history and "cheaper elsewhere" possible.
     */
    suspend fun save(receipt: Receipt, items: List<ReceiptItem>): Long {
        val receiptId = receiptDao.insertReceipt(receipt.toEntity())
        val effectiveId = if (receipt.id == 0L) receiptId else receipt.id

        receiptDao.deleteItemsForReceipt(effectiveId)
        val itemIds = receiptDao.insertItems(
            items.map { it.copy(receiptId = effectiveId).toEntity() },
        )

        recordPriceObservations(
            storeName = receipt.merchant?.takeIf { it.isNotBlank() } ?: UNKNOWN_STORE,
            observedOn = receipt.purchasedAt,
            items = items,
            itemIds = itemIds,
        )
        return effectiveId
    }

    suspend fun saveParsed(
        parsed: ParsedReceipt,
        imageUri: String?,
        fallbackCurrency: String,
        expenseId: Long? = null,
    ): Long {
        val currency = parsed.currency ?: fallbackCurrency
        val receipt = Receipt(
            expenseId = expenseId,
            imageUri = imageUri,
            merchant = parsed.merchant,
            purchasedAt = parsed.purchasedAt ?: LocalDate.now(),
            totalMinor = parsed.totalMinor ?: parsed.items.sumOf { it.totalPriceMinor },
            taxMinor = parsed.taxMinor ?: 0,
            currency = currency,
            rawText = parsed.rawText,
            scanConfidence = parsed.confidence,
        )
        val items = parsed.items.map { item ->
            ReceiptItem(
                receiptId = 0,
                name = item.name,
                normalizedName = item.normalizedName,
                quantity = item.quantity,
                unitPriceMinor = item.unitPriceMinor,
                totalPriceMinor = item.totalPriceMinor,
                currency = currency,
            )
        }
        return save(receipt, items)
    }

    suspend fun linkToExpense(receiptId: Long, expenseId: Long) {
        val receipt = receiptDao.getReceipt(receiptId) ?: return
        receiptDao.updateReceipt(receipt.copy(expenseId = expenseId))
    }

    suspend fun delete(id: Long) = receiptDao.deleteReceiptById(id)

    suspend fun searchItems(query: String): List<ReceiptItemSearchRow> =
        receiptDao.searchItems(query.lowercase().trim())

    /** Items bought more than once, ranked by how often — the "you buy this a lot" list. */
    suspend fun repeatPurchases(minOccurrences: Int = 2): List<DuplicatePurchase> =
        receiptDao.getRepeatPurchases(minOccurrences).map { stat ->
            val span = ChronoUnit.DAYS.between(stat.firstSeen, stat.lastSeen).toDouble()
            DuplicatePurchase(
                normalizedItemName = stat.normalizedName,
                displayName = stat.displayName,
                occurrences = stat.occurrences,
                totalSpentMinor = stat.totalSpentMinor,
                currency = stat.currency,
                firstSeen = stat.firstSeen,
                lastSeen = stat.lastSeen,
                averageDaysBetween = if (stat.occurrences <= 1) 0.0 else span / (stat.occurrences - 1),
            )
        }

    private suspend fun recordPriceObservations(
        storeName: String,
        observedOn: LocalDate,
        items: List<ReceiptItem>,
        itemIds: List<Long>,
    ) {
        if (items.isEmpty()) return
        priceDao.insertPricePoints(
            items.mapIndexed { index, item ->
                PricePointEntity(
                    normalizedItemName = item.normalizedName,
                    displayName = item.name,
                    storeName = storeName,
                    unitPriceMinor = item.unitPriceMinor,
                    currency = item.currency,
                    observedOn = observedOn,
                    source = PriceSource.OWN_RECEIPT.name,
                    receiptItemId = itemIds.getOrNull(index),
                )
            },
        )
    }

    private companion object {
        const val UNKNOWN_STORE = "—"
    }
}
