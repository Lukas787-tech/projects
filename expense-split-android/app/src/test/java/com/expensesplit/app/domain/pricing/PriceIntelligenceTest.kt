package com.expensesplit.app.domain.pricing

import com.expensesplit.app.domain.model.PricePoint
import com.expensesplit.app.domain.model.PriceSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class PriceIntelligenceTest {

    private val today = LocalDate.of(2026, 4, 20)

    private fun point(
        id: Long,
        name: String = "milk",
        store: String,
        minor: Long,
        daysAgo: Long,
        currency: String = "USD",
    ) = PricePoint(
        id = id,
        normalizedItemName = name,
        displayName = name.replaceFirstChar { it.uppercase() },
        storeName = store,
        unitPriceMinor = minor,
        currency = currency,
        observedOn = today.minusDays(daysAgo),
        source = PriceSource.OWN_RECEIPT,
    )

    @Test
    fun `history groups observations per item and sorts them by date`() {
        val histories = PriceIntelligence.buildHistory(
            listOf(
                point(1, store = "Aldi", minor = 129, daysAgo = 30),
                point(2, store = "Tesco", minor = 149, daysAgo = 10),
                point(3, name = "bread", store = "Aldi", minor = 199, daysAgo = 5),
            ),
        )

        assertThat(histories).hasSize(2)
        val milk = histories.first { it.normalizedItemName == "milk" }
        assertThat(milk.points.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(milk.latest?.id).isEqualTo(2L)
        assertThat(milk.cheapest?.id).isEqualTo(1L)
        assertThat(milk.averageMinor).isEqualTo(139)
    }

    @Test
    fun `finds a cheaper price at a different store`() {
        val savings = PriceIntelligence.findSavingOpportunities(
            listOf(
                point(1, store = "Aldi", minor = 100, daysAgo = 20),
                point(2, store = "Tesco", minor = 150, daysAgo = 2),
            ),
            today,
        )

        assertThat(savings).hasSize(1)
        assertThat(savings.first().bestStore).isEqualTo("Aldi")
        assertThat(savings.first().savingMinor).isEqualTo(50)
    }

    @Test
    fun `ignores a cheaper price at the same store`() {
        val savings = PriceIntelligence.findSavingOpportunities(
            listOf(
                point(1, store = "Aldi", minor = 100, daysAgo = 20),
                point(2, store = "Aldi", minor = 150, daysAgo = 2),
            ),
            today,
        )

        assertThat(savings).isEmpty()
    }

    @Test
    fun `ignores stale observations outside the comparison window`() {
        val savings = PriceIntelligence.findSavingOpportunities(
            listOf(
                point(1, store = "Aldi", minor = 100, daysAgo = 200),
                point(2, store = "Tesco", minor = 150, daysAgo = 2),
            ),
            today,
        )

        assertThat(savings).isEmpty()
    }

    @Test
    fun `ignores differences below the noise threshold`() {
        val savings = PriceIntelligence.findSavingOpportunities(
            listOf(
                point(1, store = "Aldi", minor = 148, daysAgo = 20),
                point(2, store = "Tesco", minor = 150, daysAgo = 2),
            ),
            today,
        )

        assertThat(savings).isEmpty()
    }

    @Test
    fun `does not compare across currencies`() {
        val savings = PriceIntelligence.findSavingOpportunities(
            listOf(
                point(1, store = "Aldi", minor = 100, daysAgo = 20, currency = "EUR"),
                point(2, store = "Tesco", minor = 150, daysAgo = 2, currency = "USD"),
            ),
            today,
        )

        assertThat(savings).isEmpty()
    }

    @Test
    fun `duplicate detection reports frequency and average gap`() {
        val duplicates = PriceIntelligence.findDuplicatePurchases(
            listOf(
                point(1, store = "Aldi", minor = 129, daysAgo = 20),
                point(2, store = "Aldi", minor = 129, daysAgo = 14),
                point(3, store = "Aldi", minor = 129, daysAgo = 8),
            ),
        )

        assertThat(duplicates).hasSize(1)
        val milk = duplicates.first()
        assertThat(milk.occurrences).isEqualTo(3)
        assertThat(milk.totalSpentMinor).isEqualTo(387)
        assertThat(milk.averageDaysBetween).isWithin(0.01).of(6.0)
    }

    @Test
    fun `a single purchase is not a duplicate`() {
        val duplicates = PriceIntelligence.findDuplicatePurchases(
            listOf(point(1, store = "Aldi", minor = 129, daysAgo = 3)),
        )

        assertThat(duplicates).isEmpty()
    }

    @Test
    fun `sale alerts fire when the latest price is well above the cheapest seen`() {
        val histories = PriceIntelligence.buildHistory(
            listOf(
                point(1, store = "Aldi", minor = 100, daysAgo = 30),
                point(2, store = "Tesco", minor = 150, daysAgo = 3),
            ),
        )

        val alerts = PriceIntelligence.detectSaleAlerts(histories, today)

        assertThat(alerts).hasSize(1)
        assertThat(alerts.first().savingMinor).isEqualTo(50)
    }

    @Test
    fun `clustering merges near-identical item names`() {
        val histories = PriceIntelligence.buildHistory(
            listOf(
                point(1, name = "whole milk", store = "Aldi", minor = 100, daysAgo = 5),
                point(2, name = "milk whole", store = "Tesco", minor = 110, daysAgo = 4),
                point(3, name = "laundry detergent", store = "Aldi", minor = 500, daysAgo = 3),
            ),
        )

        val clusters = PriceIntelligence.clusterSimilarItems(histories)

        assertThat(clusters).hasSize(2)
        assertThat(clusters.first { it.size == 2 }.map { it.normalizedItemName })
            .containsExactly("whole milk", "milk whole")
    }
}
