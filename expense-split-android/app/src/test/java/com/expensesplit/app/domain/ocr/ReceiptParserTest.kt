package com.expensesplit.app.domain.ocr

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {

    private val parser = ReceiptParser(defaultCurrency = "USD")
    private val today = LocalDate.of(2026, 4, 20)

    private val usReceipt = """
        WHOLE FOODS MARKET
        123 Main Street
        Tel: 555-0100

        ORGANIC MILK 1L        3.49
        SOURDOUGH BREAD        4.25
        2 x FREE RANGE EGGS    7.98
        BANANAS                1.87

        SUBTOTAL              17.59
        TAX                    1.41
        TOTAL                 19.00

        VISA ************1234
        CHANGE                 0.00
        04/18/2026
        THANK YOU
    """.trimIndent()

    @Test
    fun `extracts the merchant from the top of the receipt`() {
        val parsed = parser.parse(usReceipt, today)
        assertThat(parsed.merchant).isEqualTo("Whole Foods Market")
    }

    @Test
    fun `prefers the total line over the subtotal`() {
        val parsed = parser.parse(usReceipt, today)
        assertThat(parsed.totalMinor).isEqualTo(1900)
    }

    @Test
    fun `extracts the tax line`() {
        val parsed = parser.parse(usReceipt, today)
        assertThat(parsed.taxMinor).isEqualTo(141)
    }

    @Test
    fun `extracts the purchase date`() {
        val parsed = parser.parse(usReceipt, today)
        assertThat(parsed.purchasedAt).isEqualTo(LocalDate.of(2026, 4, 18))
    }

    @Test
    fun `extracts line items and skips payment and footer lines`() {
        val parsed = parser.parse(usReceipt, today)
        val names = parsed.items.map { it.name.lowercase() }

        assertThat(names).contains("organic milk 1l")
        assertThat(names).contains("sourdough bread")
        assertThat(names.none { it.contains("visa") }).isTrue()
        assertThat(names.none { it.contains("change") }).isTrue()
        assertThat(names.none { it.contains("subtotal") }).isTrue()
    }

    @Test
    fun `splits a quantity prefix out of the item name`() {
        val parsed = parser.parse(usReceipt, today)
        val eggs = parsed.items.first { it.name.contains("EGGS", ignoreCase = true) }

        assertThat(eggs.quantity).isEqualTo(2.0)
        assertThat(eggs.totalPriceMinor).isEqualTo(798)
        assertThat(eggs.unitPriceMinor).isEqualTo(399)
    }

    @Test
    fun `a clean receipt parses with high confidence`() {
        val parsed = parser.parse(usReceipt, today)

        assertThat(parsed.confidence).isGreaterThan(0.7f)
        assertThat(parsed.needsReview).isFalse()
    }

    @Test
    fun `reads European number and date formats`() {
        val european = """
            REWE MARKT
            Milch 1L               1,29
            Brot                   2,49
            SUMME                  3,78
            MwSt                   0,25
            18.04.2026
        """.trimIndent()

        val parsed = parser.parse(european, today)

        assertThat(parsed.totalMinor).isEqualTo(378)
        assertThat(parsed.purchasedAt).isEqualTo(LocalDate.of(2026, 4, 18))
    }

    @Test
    fun `detects the currency from a symbol`() {
        val parsed = parser.parse("TESCO\nMilk £1.29\nTOTAL £1.29", today)
        assertThat(parsed.currency).isEqualTo("GBP")
    }

    @Test
    fun `falls back to the default currency when no symbol appears`() {
        val parsed = parser.parse("CORNER SHOP\nCoffee 2.50\nTOTAL 2.50", today)
        assertThat(parsed.currency).isEqualTo("USD")
    }

    @Test
    fun `empty text yields an empty low-confidence result rather than throwing`() {
        val parsed = parser.parse("", today)

        assertThat(parsed.totalMinor).isNull()
        assertThat(parsed.items).isEmpty()
        assertThat(parsed.confidence).isEqualTo(0f)
        assertThat(parsed.needsReview).isTrue()
    }

    @Test
    fun `garbled text does not invent a total larger than any number present`() {
        val parsed = parser.parse("#### ???? ####\n@@@ 4.50 @@@", today)
        assertThat(parsed.totalMinor).isAnyOf(null, 450L)
    }

    @Test
    fun `ignores dates in the future`() {
        val parsed = parser.parse("SHOP\n01/01/2030\nTOTAL 5.00", today)
        assertThat(parsed.purchasedAt).isNull()
    }

    @Test
    fun `a receipt with no keyword total still guesses the largest amount`() {
        val parsed = parser.parse("KIOSK\nWater 1.20\nSnack 2.30\n3.50", today)
        assertThat(parsed.totalMinor).isEqualTo(350)
    }
}
