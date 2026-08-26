package com.expensesplit.app.domain.split

import com.expensesplit.app.domain.model.SplitMethod
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SplitCalculatorTest {

    private fun participants(vararg ids: Long) = ids.map { SplitCalculator.Participant(it) }

    @Test
    fun `equal split of an indivisible total still sums to the total`() {
        val result = SplitCalculator.calculate(1000, SplitMethod.EQUAL, participants(1, 2, 3))

        assertThat(result.totalMinor).isEqualTo(1000)
        assertThat(result.shares.values.sorted()).containsExactly(333L, 333L, 334L)
    }

    @Test
    fun `percentage split honours the stated percentages`() {
        val result = SplitCalculator.calculate(
            totalMinor = 10_000,
            method = SplitMethod.PERCENTAGE,
            participants = listOf(
                SplitCalculator.Participant(1, 50.0),
                SplitCalculator.Participant(2, 30.0),
                SplitCalculator.Participant(3, 20.0),
            ),
        )

        assertThat(result.shares[1]).isEqualTo(5000)
        assertThat(result.shares[2]).isEqualTo(3000)
        assertThat(result.shares[3]).isEqualTo(2000)
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `percentages that do not reach 100 are rescaled and flagged`() {
        val result = SplitCalculator.calculate(
            totalMinor = 10_000,
            method = SplitMethod.PERCENTAGE,
            participants = listOf(
                SplitCalculator.Participant(1, 40.0),
                SplitCalculator.Participant(2, 40.0),
            ),
        )

        assertThat(result.totalMinor).isEqualTo(10_000)
        assertThat(result.shares[1]).isEqualTo(5000)
        assertThat(result.warnings).contains(SplitCalculator.Warning.PERCENTAGES_RESCALED)
    }

    @Test
    fun `share split divides in proportion to share counts`() {
        val result = SplitCalculator.calculate(
            totalMinor = 12_000,
            method = SplitMethod.SHARES,
            participants = listOf(
                SplitCalculator.Participant(1, 2.0),
                SplitCalculator.Participant(2, 1.0),
                SplitCalculator.Participant(3, 1.0),
            ),
        )

        assertThat(result.shares[1]).isEqualTo(6000)
        assertThat(result.shares[2]).isEqualTo(3000)
        assertThat(result.shares[3]).isEqualTo(3000)
    }

    @Test
    fun `custom amounts that already match the total are kept exactly`() {
        val result = SplitCalculator.calculate(
            totalMinor = 5000,
            method = SplitMethod.CUSTOM,
            participants = listOf(
                SplitCalculator.Participant(1, 2000.0),
                SplitCalculator.Participant(2, 3000.0),
            ),
        )

        assertThat(result.shares[1]).isEqualTo(2000)
        assertThat(result.shares[2]).isEqualTo(3000)
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `custom amounts short of the total are topped up and flagged`() {
        val result = SplitCalculator.calculate(
            totalMinor = 5000,
            method = SplitMethod.CUSTOM,
            participants = listOf(
                SplitCalculator.Participant(1, 2000.0),
                SplitCalculator.Participant(2, 2500.0),
            ),
        )

        assertThat(result.totalMinor).isEqualTo(5000)
        assertThat(result.warnings).contains(SplitCalculator.Warning.CUSTOM_AMOUNTS_ADJUSTED)
    }

    @Test
    fun `custom amounts over the total are reduced without going negative`() {
        val result = SplitCalculator.calculate(
            totalMinor = 1000,
            method = SplitMethod.CUSTOM,
            participants = listOf(
                SplitCalculator.Participant(1, 900.0),
                SplitCalculator.Participant(2, 900.0),
            ),
        )

        assertThat(result.totalMinor).isEqualTo(1000)
        assertThat(result.shares.values.all { it >= 0 }).isTrue()
    }

    @Test
    fun `an empty participant list produces no shares rather than dividing by zero`() {
        val result = SplitCalculator.calculate(1000, SplitMethod.EQUAL, emptyList())
        assertThat(result.shares).isEmpty()
    }

    @Test
    fun `a zero weight is flagged as invalid`() {
        val result = SplitCalculator.calculate(
            totalMinor = 1000,
            method = SplitMethod.SHARES,
            participants = listOf(
                SplitCalculator.Participant(1, 1.0),
                SplitCalculator.Participant(2, 0.0),
            ),
        )

        assertThat(result.warnings).contains(SplitCalculator.Warning.INVALID_WEIGHT_IGNORED)
        assertThat(result.totalMinor).isEqualTo(1000)
    }

    @Test
    fun `remainder helpers report how far the entered values are from the target`() {
        assertThat(SplitCalculator.customRemainderMinor(5000, listOf(2000L, 2000L))).isEqualTo(1000)
        assertThat(SplitCalculator.percentageRemainder(listOf(40.0, 40.0))).isWithin(0.001).of(20.0)
    }
}
