package com.expensesplit.app.domain.ocr

import java.util.Locale

/**
 * Collapses the many ways a till prints the same product ("MILK 2% 1L", "milk 2 %  1 l",
 * "*MILK 2% 1L 2@") into one comparable key, so price history and duplicate detection can match
 * purchases across stores and months.
 */
object ItemNameNormalizer {

    private val NOISE_TOKENS = setOf(
        "ea", "each", "pc", "pcs", "pk", "pack", "ct", "qty", "x", "lb", "kg", "g", "ml", "l",
        "oz", "fl", "item", "items", "reg", "sale", "disc", "promo",
    )

    private val LEADING_CODE = Regex("^[0-9]{4,}\\s+")
    /** Separates a quantity from the unit glued to it: "1L" -> "1 L", "500g" -> "500 g". */
    private val DIGIT_LETTER_BOUNDARY = Regex("(\\p{N})(\\p{L})")
    private val TRAILING_CODE = Regex("\\s+[0-9]{6,}$")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N} ]+")
    private val MULTI_SPACE = Regex("\\s+")

    fun normalize(rawName: String): String {
        var value = rawName.lowercase(Locale.ROOT).trim()
        value = LEADING_CODE.replace(value, "")
        value = TRAILING_CODE.replace(value, "")
        value = NON_ALNUM.replace(value, " ")
        // Do this before tokenising, so "1L" and "1 L" reduce to the same tokens and the same
        // product scans identically whichever way a till chose to print it.
        value = DIGIT_LETTER_BOUNDARY.replace(value, "$1 $2")
        value = MULTI_SPACE.replace(value, " ").trim()

        val tokens = value.split(" ").filter { token ->
            token.isNotBlank() &&
                token !in NOISE_TOKENS &&
                // Drop bare quantity tokens like "2" or "500" that describe size, not identity.
                !(token.all { it.isDigit() } && token.length <= 4)
        }
        return if (tokens.isEmpty()) value else tokens.joinToString(" ")
    }

    /**
     * Similarity in 0..1 using token overlap (Jaccard). Used to group near-identical item names
     * that normalization alone does not unify, e.g. "whole milk" vs "milk whole 1l".
     */
    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        val tokensA = a.split(" ").filter { it.isNotBlank() }.toSet()
        val tokensB = b.split(" ").filter { it.isNotBlank() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f
        val intersection = tokensA.intersect(tokensB).size.toFloat()
        val union = tokensA.union(tokensB).size.toFloat()
        return intersection / union
    }
}
