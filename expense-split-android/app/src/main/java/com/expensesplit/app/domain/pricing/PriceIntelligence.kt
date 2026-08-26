package com.expensesplit.app.domain.pricing

import com.expensesplit.app.domain.model.DuplicatePurchase
import com.expensesplit.app.domain.model.PriceHistory
import com.expensesplit.app.domain.model.PricePoint
import com.expensesplit.app.domain.model.SavingOpportunity
import com.expensesplit.app.domain.ocr.ItemNameNormalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Everything the app knows about what things cost.
 *
 * The primary data source is the user's own receipts: each scanned line item becomes a price
 * observation tied to a store and a date. Partner price feeds, when configured, are merged into the
 * same series so "cheaper elsewhere" comparisons work the same way whether the evidence came from
 * the user's own shopping history or a live feed.
 */
object PriceIntelligence {

    /** Ignore comparisons older than this — last winter's price proves nothing today. */
    private const val COMPARISON_WINDOW_DAYS = 90L

    /** Below this, a "saving" is noise rather than a reason to shop somewhere else. */
    private const val MIN_SAVING_PERCENT = 8f

    fun buildHistory(points: List<PricePoint>): List<PriceHistory> =
        points.groupBy { it.normalizedItemName }
            .map { (normalized, group) ->
                PriceHistory(
                    normalizedItemName = normalized,
                    displayName = group.maxByOrNull { it.observedOn }?.displayName ?: normalized,
                    points = group.sortedBy { it.observedOn },
                )
            }
            .sortedByDescending { it.points.size }

    /**
     * Finds items the user paid more for than the cheapest recent observation at another store.
     * Only compares like with like: same currency, same normalized item, different store.
     */
    fun findSavingOpportunities(
        points: List<PricePoint>,
        today: LocalDate = LocalDate.now(),
        minSavingPercent: Float = MIN_SAVING_PERCENT,
    ): List<SavingOpportunity> {
        val cutoff = today.minusDays(COMPARISON_WINDOW_DAYS)
        val recent = points.filter { !it.observedOn.isBefore(cutoff) }

        return recent.groupBy { it.normalizedItemName }
            .mapNotNull { (normalized, group) ->
                val byCurrency = group.groupBy { it.currency }
                    .maxByOrNull { (_, entries) -> entries.size }
                    ?.value
                    ?: return@mapNotNull null
                if (byCurrency.size < 2) return@mapNotNull null

                val mostRecent = byCurrency.maxByOrNull { it.observedOn } ?: return@mapNotNull null
                val cheapest = byCurrency
                    .filter { !it.storeName.equals(mostRecent.storeName, ignoreCase = true) }
                    .minByOrNull { it.unitPriceMinor }
                    ?: return@mapNotNull null

                if (cheapest.unitPriceMinor >= mostRecent.unitPriceMinor) return@mapNotNull null

                val opportunity = SavingOpportunity(
                    normalizedItemName = normalized,
                    displayName = mostRecent.displayName,
                    paidMinor = mostRecent.unitPriceMinor,
                    paidAtStore = mostRecent.storeName,
                    bestMinor = cheapest.unitPriceMinor,
                    bestStore = cheapest.storeName,
                    currency = mostRecent.currency,
                    observedOn = cheapest.observedOn,
                )
                opportunity.takeIf { it.savingPercent >= minSavingPercent }
            }
            .sortedByDescending { it.savingMinor }
    }

    /**
     * Items bought repeatedly in a short window. Frequent staples (milk, bread) are expected;
     * the caller filters on [DuplicatePurchase.averageDaysBetween] to decide what is worth showing.
     */
    fun findDuplicatePurchases(
        points: List<PricePoint>,
        minOccurrences: Int = 2,
    ): List<DuplicatePurchase> =
        points.groupBy { it.normalizedItemName }
            .filter { (_, group) -> group.size >= minOccurrences }
            .map { (normalized, group) ->
                val sorted = group.sortedBy { it.observedOn }
                val first = sorted.first().observedOn
                val last = sorted.last().observedOn
                val gaps = sorted.zipWithNext { a, b ->
                    ChronoUnit.DAYS.between(a.observedOn, b.observedOn).toDouble()
                }
                DuplicatePurchase(
                    normalizedItemName = normalized,
                    displayName = sorted.last().displayName,
                    occurrences = sorted.size,
                    totalSpentMinor = sorted.sumOf { it.unitPriceMinor },
                    currency = sorted.last().currency,
                    firstSeen = first,
                    lastSeen = last,
                    averageDaysBetween = if (gaps.isEmpty()) 0.0 else gaps.average(),
                )
            }
            .sortedByDescending { it.occurrences }

    /**
     * Groups near-identical item names that normalization alone did not unify, so "whole milk 1l"
     * and "milk whole" end up in one price series.
     */
    fun clusterSimilarItems(
        histories: List<PriceHistory>,
        similarityThreshold: Float = 0.6f,
    ): List<List<PriceHistory>> {
        val remaining = histories.toMutableList()
        val clusters = mutableListOf<List<PriceHistory>>()

        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val cluster = mutableListOf(seed)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val similarity = ItemNameNormalizer.similarity(
                    seed.normalizedItemName,
                    candidate.normalizedItemName,
                )
                if (similarity >= similarityThreshold) {
                    cluster += candidate
                    iterator.remove()
                }
            }
            clusters += cluster
        }
        return clusters
    }

    /**
     * Detects a price the user should be told about: the item is now meaningfully cheaper than
     * what they last paid. Drives the "on sale elsewhere" notification.
     */
    fun detectSaleAlerts(
        histories: List<PriceHistory>,
        today: LocalDate = LocalDate.now(),
        minDropPercent: Float = 10f,
    ): List<SavingOpportunity> = histories.mapNotNull { history ->
        val latest = history.latest ?: return@mapNotNull null
        val cheapest = history.cheapest ?: return@mapNotNull null
        if (cheapest.id == latest.id) return@mapNotNull null
        if (latest.observedOn.isBefore(today.minusDays(COMPARISON_WINDOW_DAYS))) return@mapNotNull null

        val opportunity = SavingOpportunity(
            normalizedItemName = history.normalizedItemName,
            displayName = history.displayName,
            paidMinor = latest.unitPriceMinor,
            paidAtStore = latest.storeName,
            bestMinor = cheapest.unitPriceMinor,
            bestStore = cheapest.storeName,
            currency = history.currency,
            observedOn = cheapest.observedOn,
        )
        opportunity.takeIf { it.savingPercent >= minDropPercent }
    }
}
