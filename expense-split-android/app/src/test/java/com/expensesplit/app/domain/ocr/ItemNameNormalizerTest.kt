package com.expensesplit.app.domain.ocr

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ItemNameNormalizerTest {

    @Test
    fun `lower-cases and strips punctuation`() {
        assertThat(ItemNameNormalizer.normalize("*ORGANIC MILK, 1L*")).isEqualTo("organic milk")
    }

    @Test
    fun `drops leading product codes`() {
        assertThat(ItemNameNormalizer.normalize("0012345 SOURDOUGH BREAD"))
            .isEqualTo("sourdough bread")
    }

    @Test
    fun `drops trailing barcodes`() {
        assertThat(ItemNameNormalizer.normalize("BANANAS 401234567"))
            .isEqualTo("bananas")
    }

    @Test
    fun `removes unit noise tokens`() {
        assertThat(ItemNameNormalizer.normalize("COFFEE BEANS 500 g")).isEqualTo("coffee beans")
    }

    @Test
    fun `the same product printed differently normalises to the same key`() {
        val a = ItemNameNormalizer.normalize("MILK 2% 1L")
        val b = ItemNameNormalizer.normalize("milk 2 %  1 l")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `never returns an empty key for a non-empty name`() {
        assertThat(ItemNameNormalizer.normalize("123")).isNotEmpty()
    }

    @Test
    fun `similarity recognises reordered words`() {
        val similarity = ItemNameNormalizer.similarity("whole milk", "milk whole")
        assertThat(similarity).isEqualTo(1f)
    }

    @Test
    fun `similarity is low for unrelated items`() {
        val similarity = ItemNameNormalizer.similarity("sourdough bread", "laundry detergent")
        assertThat(similarity).isLessThan(0.2f)
    }
}
