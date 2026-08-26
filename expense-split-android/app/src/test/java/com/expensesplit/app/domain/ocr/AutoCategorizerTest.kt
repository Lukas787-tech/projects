package com.expensesplit.app.domain.ocr

import com.expensesplit.app.domain.model.Category
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoCategorizerTest {

    private val groceries = Category(
        id = 2,
        key = "groceries",
        nameRes = null,
        customName = "Groceries",
        colorArgb = 0,
        iconKey = "cart",
        keywords = listOf("supermarket", "tesco", "whole foods"),
    )
    private val dining = Category(
        id = 3,
        key = "dining",
        nameRes = null,
        customName = "Dining",
        colorArgb = 0,
        iconKey = "restaurant",
        keywords = listOf("cafe", "restaurant", "starbucks"),
    )
    private val transport = Category(
        id = 4,
        key = "transport",
        nameRes = null,
        customName = "Transport",
        colorArgb = 0,
        iconKey = "car",
        keywords = listOf("gas", "gas station", "uber"),
    )

    private val categories = listOf(groceries, dining, transport)

    @Test
    fun `matches a merchant name`() {
        val suggestion = AutoCategorizer.categorize(
            categories = categories,
            merchant = "Whole Foods Market",
            fallbackCategoryId = 1,
        )

        assertThat(suggestion.categoryId).isEqualTo(groceries.id)
        assertThat(suggestion.confidence).isGreaterThan(0.5f)
    }

    @Test
    fun `prefers the more specific keyword when several match`() {
        val suggestion = AutoCategorizer.categorize(
            categories = categories,
            merchant = "Shell Gas Station",
            fallbackCategoryId = 1,
        )

        assertThat(suggestion.categoryId).isEqualTo(transport.id)
        assertThat(suggestion.matchedKeyword).isEqualTo("gas station")
    }

    @Test
    fun `a merchant match outranks an item match`() {
        val suggestion = AutoCategorizer.categorize(
            categories = categories,
            merchant = "Starbucks",
            itemNames = listOf("supermarket own-brand beans"),
            fallbackCategoryId = 1,
        )

        assertThat(suggestion.categoryId).isEqualTo(dining.id)
    }

    @Test
    fun `falls back when nothing matches`() {
        val suggestion = AutoCategorizer.categorize(
            categories = categories,
            merchant = "Zzz Unknown Ltd",
            fallbackCategoryId = 99,
        )

        assertThat(suggestion.categoryId).isEqualTo(99)
        assertThat(suggestion.confidence).isEqualTo(0f)
    }

    @Test
    fun `matches on item names when there is no merchant`() {
        val suggestion = AutoCategorizer.categorize(
            categories = categories,
            itemNames = listOf("tesco finest coffee"),
            fallbackCategoryId = 1,
        )

        assertThat(suggestion.categoryId).isEqualTo(groceries.id)
    }
}
