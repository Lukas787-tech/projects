package com.expensesplit.app.domain.ocr

import com.expensesplit.app.domain.model.Category
import java.util.Locale

/**
 * Picks a category from the merchant name, the title and any scanned item names.
 *
 * Scoring is intentionally simple and explainable: a keyword hit on the merchant is worth more
 * than one on an item line, and longer keywords beat shorter ones so "gas station" wins over "gas".
 */
object AutoCategorizer {

    data class Suggestion(val categoryId: Long, val confidence: Float, val matchedKeyword: String?)

    fun categorize(
        categories: List<Category>,
        merchant: String? = null,
        title: String? = null,
        itemNames: List<String> = emptyList(),
        fallbackCategoryId: Long,
    ): Suggestion {
        val merchantText = merchant?.lowercase(Locale.ROOT).orEmpty()
        val titleText = title?.lowercase(Locale.ROOT).orEmpty()
        val itemsText = itemNames.joinToString(" ") { it.lowercase(Locale.ROOT) }

        var bestCategoryId = fallbackCategoryId
        var bestScore = 0.0
        var bestKeyword: String? = null

        for (category in categories) {
            for (keyword in category.keywords) {
                if (keyword.isBlank()) continue
                // Longer keywords are more specific, so they carry more weight.
                val specificity = 1.0 + keyword.length / 20.0
                val score = when {
                    merchantText.contains(keyword) -> 3.0 * specificity
                    titleText.contains(keyword) -> 2.0 * specificity
                    itemsText.contains(keyword) -> 1.0 * specificity
                    else -> 0.0
                }
                if (score > bestScore) {
                    bestScore = score
                    bestCategoryId = category.id
                    bestKeyword = keyword
                }
            }
        }

        // 4.5 is roughly "a solid merchant match on a reasonably specific keyword".
        val confidence = (bestScore / 4.5).coerceIn(0.0, 1.0).toFloat()
        return Suggestion(bestCategoryId, confidence, bestKeyword)
    }
}
