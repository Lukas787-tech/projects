package com.expensesplit.app.data.repository

import com.expensesplit.app.core.Money
import com.expensesplit.app.data.local.dao.PriceDao
import com.expensesplit.app.data.local.entity.PricePointEntity
import com.expensesplit.app.data.remote.api.StorePriceApi
import com.expensesplit.app.domain.model.DuplicatePurchase
import com.expensesplit.app.domain.model.PriceHistory
import com.expensesplit.app.domain.model.PricePoint
import com.expensesplit.app.domain.model.PriceSource
import com.expensesplit.app.domain.model.SavingOpportunity
import com.expensesplit.app.domain.ocr.ItemNameNormalizer
import com.expensesplit.app.domain.pricing.PriceIntelligence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Price history and comparison shopping.
 *
 * Works entirely from the user's own receipts by default. When an optional store-price feed is
 * configured, its offers are merged in as additional observations tagged [PriceSource.PARTNER_FEED],
 * so the comparison logic stays identical either way.
 */
@Singleton
class PriceRepository @Inject constructor(
    private val priceDao: PriceDao,
    private val storePriceApi: StorePriceApi?,
) {

    fun observeHistory(normalizedName: String): Flow<PriceHistory> =
        priceDao.observeHistory(normalizedName).map { points ->
            PriceHistory(
                normalizedItemName = normalizedName,
                displayName = points.maxByOrNull { it.observedOn }?.displayName ?: normalizedName,
                points = points.map { it.toDomain() },
            )
        }

    suspend fun history(itemName: String): PriceHistory {
        val normalized = ItemNameNormalizer.normalize(itemName)
        val points = priceDao.getHistory(normalized).map { it.toDomain() }
        return PriceHistory(normalized, points.lastOrNull()?.displayName ?: itemName, points)
    }

    suspend fun allPricePoints(): List<PricePoint> =
        priceDao.getAllPricePoints().map { it.toDomain() }

    suspend fun allHistories(): List<PriceHistory> = PriceIntelligence.buildHistory(allPricePoints())

    suspend fun savingOpportunities(today: LocalDate = LocalDate.now()): List<SavingOpportunity> =
        PriceIntelligence.findSavingOpportunities(allPricePoints(), today)

    suspend fun duplicatePurchases(): List<DuplicatePurchase> =
        PriceIntelligence.findDuplicatePurchases(allPricePoints())

    suspend fun saleAlerts(today: LocalDate = LocalDate.now()): List<SavingOpportunity> =
        PriceIntelligence.detectSaleAlerts(allHistories(), today)

    suspend fun knownStores(): List<String> = priceDao.getKnownStores()

    suspend fun recordManualPrice(
        itemName: String,
        storeName: String,
        unitPriceMinor: Long,
        currency: String,
        observedOn: LocalDate = LocalDate.now(),
    ): Long = priceDao.insertPricePoint(
        PricePointEntity(
            normalizedItemName = ItemNameNormalizer.normalize(itemName),
            displayName = itemName,
            storeName = storeName,
            unitPriceMinor = unitPriceMinor,
            currency = currency,
            observedOn = observedOn,
            source = PriceSource.MANUAL_ENTRY.name,
        ),
    )

    /**
     * Pulls nearby offers for [itemName] from the configured feed and stores them as observations.
     * Returns an empty list (never throws) when no feed is configured or the call fails, because a
     * price feed being down must not break receipt viewing.
     */
    suspend fun refreshOffers(
        itemName: String,
        currency: String,
        latitude: Double? = null,
        longitude: Double? = null,
    ): List<PricePoint> = withContext(Dispatchers.IO) {
        val api = storePriceApi ?: return@withContext emptyList()
        val normalized = ItemNameNormalizer.normalize(itemName)

        val response = runCatching {
            api.search(query = itemName, currency = currency, latitude = latitude, longitude = longitude)
        }.getOrNull() ?: return@withContext emptyList()

        val entities = response.offers.mapNotNull { offer ->
            if (offer.storeName.isBlank() || offer.price <= 0.0) return@mapNotNull null
            val offerCurrency = offer.currency ?: response.currency
            PricePointEntity(
                normalizedItemName = normalized,
                displayName = offer.itemName.ifBlank { itemName },
                storeName = offer.storeName,
                unitPriceMinor = Money.toMinor(offer.price.toBigDecimal(), offerCurrency),
                currency = offerCurrency,
                observedOn = offer.observedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: LocalDate.now(),
                source = PriceSource.PARTNER_FEED.name,
            )
        }
        if (entities.isNotEmpty()) priceDao.insertPricePoints(entities)
        entities.map { it.toDomain() }
    }
}
