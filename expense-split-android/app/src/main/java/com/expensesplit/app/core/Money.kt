package com.expensesplit.app.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Money is stored throughout the app as a [Long] count of minor units (cents, yen, fils, ...)
 * together with an ISO-4217 currency code. Doubles are never persisted: every arithmetic
 * operation that could lose a fraction of a unit routes through the helpers here, so a split
 * of 10.00 across 3 people always adds back up to exactly 10.00.
 */
object Money {

    /** Minor-unit exponent for [currencyCode] — 2 for USD/EUR, 0 for JPY, 3 for KWD. */
    fun fractionDigits(currencyCode: String): Int = runCatching {
        Currency.getInstance(currencyCode).defaultFractionDigits.coerceAtLeast(0)
    }.getOrDefault(2)

    fun minorUnitsPerMajor(currencyCode: String): Long {
        var factor = 1L
        repeat(fractionDigits(currencyCode)) { factor *= 10 }
        return factor
    }

    /** Parses user input ("12.34", "12,34", "1 234.50") into minor units, or null if unparseable. */
    fun parseToMinor(input: String, currencyCode: String): Long? {
        val cleaned = input.trim()
            .replace(" ", "")
            .replace(" ", "")
            .replace(",", ".")
            .filter { it.isDigit() || it == '.' || it == '-' }
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "-") return null
        // Keep only the last dot as the decimal separator ("1.234.50" -> "1234.50").
        val lastDot = cleaned.lastIndexOf('.')
        val normalized = if (lastDot >= 0) {
            cleaned.substring(0, lastDot).replace(".", "") + "." + cleaned.substring(lastDot + 1)
        } else {
            cleaned
        }
        val decimal = normalized.toBigDecimalOrNull() ?: return null
        return toMinor(decimal, currencyCode)
    }

    fun toMinor(amount: BigDecimal, currencyCode: String): Long =
        amount.setScale(fractionDigits(currencyCode), RoundingMode.HALF_UP)
            .movePointRight(fractionDigits(currencyCode))
            .toLong()

    fun toMajor(minor: Long, currencyCode: String): BigDecimal =
        BigDecimal.valueOf(minor).movePointLeft(fractionDigits(currencyCode))

    /** Formats for display, e.g. `$1,234.50` / `1.234,50 €` depending on [locale]. */
    fun format(minor: Long, currencyCode: String, locale: Locale = Locale.getDefault()): String {
        val formatter = NumberFormat.getCurrencyInstance(locale)
        runCatching { formatter.currency = Currency.getInstance(currencyCode) }
        val digits = fractionDigits(currencyCode)
        formatter.minimumFractionDigits = digits
        formatter.maximumFractionDigits = digits
        return formatter.format(toMajor(minor, currencyCode))
    }

    /** Compact form for chart labels and tight cards: `$1.2K`, `$3.4M`. */
    fun formatCompact(minor: Long, currencyCode: String, locale: Locale = Locale.getDefault()): String {
        val major = toMajor(minor, currencyCode).toDouble()
        val symbol = runCatching { Currency.getInstance(currencyCode).getSymbol(locale) }.getOrDefault(currencyCode)
        val absolute = kotlin.math.abs(major)
        val sign = if (major < 0) "-" else ""
        return when {
            absolute >= 1_000_000 -> String.format(locale, "%s%s%.1fM", sign, symbol, absolute / 1_000_000)
            absolute >= 1_000 -> String.format(locale, "%s%s%.1fK", sign, symbol, absolute / 1_000)
            else -> String.format(locale, "%s%s%.0f", sign, symbol, absolute)
        }
    }

    /** Plain editable text for a text field — no symbol, no grouping. */
    fun toEditableString(minor: Long, currencyCode: String): String =
        toMajor(minor, currencyCode).toPlainString()

    fun convert(minor: Long, rate: Double, fromCurrency: String, toCurrency: String): Long {
        if (fromCurrency == toCurrency) return minor
        val major = toMajor(minor, fromCurrency).multiply(BigDecimal.valueOf(rate))
        return toMinor(major, toCurrency)
    }

    /**
     * Splits [totalMinor] into [parts] shares that sum back to exactly [totalMinor].
     * The remainder is distributed one minor unit at a time to the earliest shares, which is the
     * convention every bill-splitting app uses (someone has to absorb the extra cent).
     */
    fun splitEvenly(totalMinor: Long, parts: Int): List<Long> {
        require(parts > 0) { "Cannot split across $parts parts" }
        val base = totalMinor / parts
        val remainder = (totalMinor - base * parts).toInt()
        val step = if (totalMinor < 0) -1L else 1L
        val absRemainder = kotlin.math.abs(remainder)
        return List(parts) { index -> base + if (index < absRemainder) step else 0L }
    }

    /**
     * Allocates [totalMinor] proportionally to [weights], preserving the exact total.
     * Uses the largest-remainder method so the distribution is stable and fair.
     */
    fun allocateByWeights(totalMinor: Long, weights: List<Double>): List<Long> {
        require(weights.isNotEmpty()) { "No weights to allocate across" }
        val sanitized = weights.map { if (it.isFinite() && it > 0) it else 0.0 }
        val weightSum = sanitized.sum()
        if (weightSum <= 0.0) return splitEvenly(totalMinor, weights.size)

        val exact = sanitized.map { totalMinor * (it / weightSum) }
        val floors = exact.map { kotlin.math.floor(it).toLong() }
        var remaining = totalMinor - floors.sum()
        val result = floors.toMutableList()
        val order = exact.indices.sortedByDescending { exact[it] - floors[it] }
        var cursor = 0
        val step = if (remaining < 0) -1L else 1L
        while (remaining != 0L && order.isNotEmpty()) {
            val index = order[cursor % order.size]
            result[index] = result[index] + step
            remaining -= step
            cursor++
        }
        return result
    }
}
