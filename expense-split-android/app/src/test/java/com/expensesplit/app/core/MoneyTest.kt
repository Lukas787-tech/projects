package com.expensesplit.app.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class MoneyTest {

    @Test
    fun `fraction digits follow the currency`() {
        assertThat(Money.fractionDigits("USD")).isEqualTo(2)
        assertThat(Money.fractionDigits("EUR")).isEqualTo(2)
        assertThat(Money.fractionDigits("JPY")).isEqualTo(0)
        assertThat(Money.fractionDigits("KWD")).isEqualTo(3)
    }

    @Test
    fun `unknown currency codes fall back to two digits rather than throwing`() {
        assertThat(Money.fractionDigits("XYZ")).isEqualTo(2)
    }

    @Test
    fun `parses plain decimal input`() {
        assertThat(Money.parseToMinor("12.34", "USD")).isEqualTo(1234)
        assertThat(Money.parseToMinor("0.05", "USD")).isEqualTo(5)
        assertThat(Money.parseToMinor("7", "USD")).isEqualTo(700)
    }

    @Test
    fun `parses comma decimal separators`() {
        assertThat(Money.parseToMinor("12,34", "EUR")).isEqualTo(1234)
    }

    @Test
    fun `parses grouped input by treating the last separator as the decimal point`() {
        assertThat(Money.parseToMinor("1.234.50", "USD")).isEqualTo(123450)
        assertThat(Money.parseToMinor("1 234.50", "USD")).isEqualTo(123450)
    }

    @Test
    fun `parses zero-decimal currencies without inventing cents`() {
        assertThat(Money.parseToMinor("1200", "JPY")).isEqualTo(1200)
    }

    @Test
    fun `rejects input that is not a number`() {
        assertThat(Money.parseToMinor("", "USD")).isNull()
        assertThat(Money.parseToMinor("abc", "USD")).isNull()
        assertThat(Money.parseToMinor(".", "USD")).isNull()
    }

    @Test
    fun `rounds half up when converting to minor units`() {
        assertThat(Money.toMinor(BigDecimal("1.005"), "USD")).isEqualTo(101)
        assertThat(Money.toMinor(BigDecimal("1.004"), "USD")).isEqualTo(100)
    }

    @Test
    fun `splitting evenly always sums back to the original total`() {
        val shares = Money.splitEvenly(1000, 3)
        assertThat(shares).containsExactly(334L, 333L, 333L).inOrder()
        assertThat(shares.sum()).isEqualTo(1000)
    }

    @Test
    fun `splitting evenly handles totals smaller than the party size`() {
        val shares = Money.splitEvenly(2, 5)
        assertThat(shares.sum()).isEqualTo(2)
        assertThat(shares).containsExactly(1L, 1L, 0L, 0L, 0L).inOrder()
    }

    @Test
    fun `splitting evenly handles negative totals`() {
        val shares = Money.splitEvenly(-1000, 3)
        assertThat(shares.sum()).isEqualTo(-1000)
    }

    @Test
    fun `weighted allocation preserves the exact total`() {
        val shares = Money.allocateByWeights(1000, listOf(1.0, 1.0, 1.0))
        assertThat(shares.sum()).isEqualTo(1000)

        val uneven = Money.allocateByWeights(10_000, listOf(33.3, 33.3, 33.4))
        assertThat(uneven.sum()).isEqualTo(10_000)
    }

    @Test
    fun `weighted allocation gives more to larger weights`() {
        val shares = Money.allocateByWeights(1000, listOf(3.0, 1.0))
        assertThat(shares).containsExactly(750L, 250L).inOrder()
    }

    @Test
    fun `weighted allocation falls back to an even split when all weights are invalid`() {
        val shares = Money.allocateByWeights(900, listOf(0.0, 0.0, 0.0))
        assertThat(shares).containsExactly(300L, 300L, 300L).inOrder()
    }

    @Test
    fun `conversion is a no-op for matching currencies`() {
        assertThat(Money.convert(1234, 1.17, "USD", "USD")).isEqualTo(1234)
    }

    @Test
    fun `conversion crosses currencies with different exponents`() {
        // 10.00 USD at 150 JPY per USD is 1500 yen, not 150000 minor units.
        assertThat(Money.convert(1000, 150.0, "USD", "JPY")).isEqualTo(1500)
    }

    @Test
    fun `formatting round-trips through the editable representation`() {
        val minor = Money.parseToMinor("42.50", "USD")!!
        assertThat(Money.toEditableString(minor, "USD")).isEqualTo("42.50")
    }

    @Test
    fun `compact formatting shortens large amounts`() {
        assertThat(Money.formatCompact(150_000_00, "USD", Locale.US)).contains("150.0K")
        assertThat(Money.formatCompact(2_500_000_00, "USD", Locale.US)).contains("2.5M")
    }
}
