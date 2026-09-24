package com.lukas.jarvis.core

/** Numbers as people and small models write them. */
object Numbers {

    /**
     * The first number written in [text], with either decimal mark: "2,50"
     * and "2.50" are both two and a half, "1,200" and "1.200,50" are over a
     * thousand — the last mark is the decimal one only when two or fewer
     * digits follow it, or when both kinds appear.
     */
    fun first(text: String): Double? {
        val token = Regex("-?\\d[\\d.,]*").find(text)?.value?.trimEnd('.', ',') ?: return null
        val lastDot = token.lastIndexOf('.')
        val lastComma = token.lastIndexOf(',')
        val decimalAt = when {
            lastDot >= 0 && lastComma >= 0 -> maxOf(lastDot, lastComma)
            lastDot >= 0 || lastComma >= 0 -> {
                val at = maxOf(lastDot, lastComma)
                val mark = token[at]
                val once = token.count { it == mark } == 1
                val leadingZero = token.substring(0, at).trimStart('-') == "0"
                if (once && (leadingZero || token.length - at - 1 != 3)) at else -1
            }
            else -> -1
        }
        val digits = token.filterIndexed { i, c -> c.isDigit() || c == '-' || i == decimalAt }
            .replace(',', '.')
        return digits.toDoubleOrNull()
    }
}
