package com.expensesplit.app.data.repository

import com.expensesplit.app.core.DateRange
import com.expensesplit.app.domain.model.PaymentMethod
import com.expensesplit.app.domain.model.SearchFilters
import com.expensesplit.app.domain.model.SearchSort
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class SearchQueryBuilderTest {

    @Test
    fun `no filters produces an unfiltered query`() {
        val query = SearchQueryBuilder.build(SearchFilters())

        assertThat(query.sql).doesNotContain("WHERE")
        assertThat(query.argCount).isEqualTo(0)
    }

    @Test
    fun `keyword search covers title note and merchant`() {
        val query = SearchQueryBuilder.build(SearchFilters(keyword = "coffee"))

        assertThat(query.sql).contains("title LIKE ?")
        assertThat(query.sql).contains("note LIKE ?")
        assertThat(query.sql).contains("merchant LIKE ?")
        assertThat(query.argCount).isEqualTo(3)
    }

    @Test
    fun `keyword wildcards are escaped so they match literally`() {
        val query = SearchQueryBuilder.build(SearchFilters(keyword = "50%_off"))

        assertThat(query.sql).contains("ESCAPE")
        // The pattern itself is bound, never interpolated into the SQL text.
        assertThat(query.sql).doesNotContain("50%_off")
    }

    @Test
    fun `a quote in the keyword never reaches the SQL text`() {
        val query = SearchQueryBuilder.build(SearchFilters(keyword = "'; DROP TABLE expenses; --"))

        assertThat(query.sql).doesNotContain("DROP TABLE")
        assertThat(query.argCount).isEqualTo(3)
    }

    @Test
    fun `date range binds epoch days`() {
        val range = DateRange(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30))
        val query = SearchQueryBuilder.build(SearchFilters(range = range))

        assertThat(query.sql).contains("date >= ?")
        assertThat(query.sql).contains("date <= ?")
        assertThat(query.argCount).isEqualTo(2)
    }

    @Test
    fun `category and payment filters produce one placeholder each`() {
        val query = SearchQueryBuilder.build(
            SearchFilters(
                categoryIds = setOf(1, 2, 3),
                paymentMethods = setOf(PaymentMethod.CASH, PaymentMethod.CARD),
            ),
        )

        assertThat(query.sql).contains("categoryId IN (?, ?, ?)")
        assertThat(query.sql).contains("paymentMethod IN (?, ?)")
        assertThat(query.argCount).isEqualTo(5)
    }

    @Test
    fun `amount bounds are applied against the base-currency column`() {
        val query = SearchQueryBuilder.build(
            SearchFilters(minAmountMinor = 1000, maxAmountMinor = 5000),
        )

        assertThat(query.sql).contains("baseAmountMinor >= ?")
        assertThat(query.sql).contains("baseAmountMinor <= ?")
        assertThat(query.argCount).isEqualTo(2)
    }

    @Test
    fun `receipt-only filter needs no bound argument`() {
        val query = SearchQueryBuilder.build(SearchFilters(withReceiptOnly = true))

        assertThat(query.sql).contains("receiptId IS NOT NULL")
        assertThat(query.argCount).isEqualTo(0)
    }

    @Test
    fun `sort choices map to fixed order-by clauses`() {
        assertThat(SearchQueryBuilder.build(SearchFilters(sort = SearchSort.AMOUNT_DESC)).sql)
            .contains("ORDER BY baseAmountMinor DESC")
        assertThat(SearchQueryBuilder.build(SearchFilters(sort = SearchSort.TITLE_ASC)).sql)
            .contains("ORDER BY title COLLATE NOCASE ASC")
    }

    @Test
    fun `combined filters are joined with AND`() {
        val query = SearchQueryBuilder.build(
            SearchFilters(
                keyword = "taxi",
                categoryIds = setOf(4),
                settledOnly = true,
            ),
        )

        assertThat(query.sql).contains("AND")
        assertThat(query.argCount).isEqualTo(5)
    }
}
