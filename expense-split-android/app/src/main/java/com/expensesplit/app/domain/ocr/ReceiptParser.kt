package com.expensesplit.app.domain.ocr

import com.expensesplit.app.core.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Extracts merchant, date, total, tax and line items from the flat text ML Kit returns for a
 * receipt photo.
 *
 * Receipts have no schema, so this is deliberately heuristic and deliberately conservative: it
 * reports a [ParsedReceipt.confidence] and leaves anything it is unsure about null, letting the
 * Add Expense screen fall back to manual entry rather than silently saving a wrong number.
 */
class ReceiptParser(private val defaultCurrency: String = "USD") {

    private companion object {
        // Matches 12.34 / 12,34 / 1,234.56 / 1.234,56 with an optional leading currency symbol.
        val AMOUNT = Regex("(?<![\\d.,])(\\d{1,3}(?:[.,\\s]\\d{3})*(?:[.,]\\d{1,2})|\\d+[.,]\\d{1,2}|\\d+)(?![\\d])")

        val TOTAL_KEYWORDS = listOf(
            "total", "amount due", "balance due", "grand total", "to pay", "importe", "totale",
            "gesamt", "summe", "somme", "montant", "合計", "总计", "合计", "المجموع", "الإجمالي",
        )
        // Lines that look like a total but are not the one we want.
        val TOTAL_EXCLUSIONS = listOf(
            "subtotal", "sub total", "sub-total", "total items", "total qty", "total savings",
            "zwischensumme", "subtotale", "sous-total", "total discount",
        )
        val TAX_KEYWORDS = listOf("tax", "vat", "iva", "tva", "mwst", "gst", "hst", "ust", "消費税", "税")
        val SKIP_LINE_KEYWORDS = listOf(
            "change", "cash", "card", "visa", "mastercard", "debit", "credit", "tender", "balance",
            "auth", "approval", "terminal", "merchant id", "receipt", "thank", "welcome", "vat no",
            "tel", "phone", "www", "http", "invoice", "order", "cashier", "store #", "reg #",
            "subtotal", "sub total", "loyalty", "points", "aid:", "ref:",
        )

        val CURRENCY_SYMBOLS = mapOf(
            "$" to "USD", "€" to "EUR", "£" to "GBP", "¥" to "JPY", "₹" to "INR",
            "R$" to "BRL", "CHF" to "CHF", "kr" to "SEK", "zł" to "PLN", "₩" to "KRW",
            "₺" to "TRY", "﷼" to "SAR", "د.إ" to "AED", "元" to "CNY",
        )

        val DATE_PATTERNS = listOf(
            "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "dd-MM-yyyy", "MM-dd-yyyy",
            "dd.MM.yyyy", "yyyy/MM/dd", "dd/MM/yy", "MM/dd/yy", "dd.MM.yy",
            "d MMM yyyy", "MMM d yyyy", "d MMMM yyyy",
        )
        val DATE_CANDIDATE = Regex("\\d{1,4}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{1,2}\\s+\\p{L}{3,9}\\s+\\d{4}")
    }

    fun parse(rawText: String, today: LocalDate = LocalDate.now()): ParsedReceipt {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return ParsedReceipt(null, null, null, null, null, emptyList(), rawText, 0f)
        }

        val currency = detectCurrency(rawText) ?: defaultCurrency
        val merchant = detectMerchant(lines)
        val date = detectDate(lines, today)
        val total = detectTotal(lines, currency)
        val tax = detectTax(lines, currency)
        val items = detectItems(lines, currency, total)

        var score = 0f
        if (merchant != null) score += 0.2f
        if (date != null) score += 0.2f
        if (total != null) score += 0.4f
        if (items.isNotEmpty()) score += 0.2f
        // Items that add up close to the stated total is the strongest signal the parse is sound.
        if (total != null && items.isNotEmpty()) {
            val itemSum = items.sumOf { it.totalPriceMinor }
            val drift = kotlin.math.abs(itemSum - total).toDouble() / total.coerceAtLeast(1)
            if (drift < 0.15) score = (score + 0.15f).coerceAtMost(1f)
        }

        return ParsedReceipt(
            merchant = merchant,
            purchasedAt = date,
            totalMinor = total,
            taxMinor = tax,
            currency = currency,
            items = items,
            rawText = rawText,
            confidence = score.coerceIn(0f, 1f),
        )
    }

    private fun detectCurrency(text: String): String? =
        CURRENCY_SYMBOLS.entries.firstOrNull { text.contains(it.key) }?.value
            ?: Regex("\\b(USD|EUR|GBP|JPY|CHF|CAD|AUD|CNY|INR|SEK|NOK|DKK|PLN|MXN|BRL|AED|SAR)\\b")
                .find(text.uppercase(Locale.ROOT))?.value

    /**
     * The merchant is almost always in the first few lines. Address and phone lines are skipped,
     * and lines that are mostly digits (VAT numbers, store codes) are rejected.
     */
    private fun detectMerchant(lines: List<String>): String? {
        for (line in lines.take(6)) {
            val cleaned = line.trim().trim('*', '-', '=', '_')
            if (cleaned.length < 3 || cleaned.length > 40) continue
            val lower = cleaned.lowercase(Locale.ROOT)
            if (SKIP_LINE_KEYWORDS.any { lower.contains(it) }) continue
            val letters = cleaned.count { it.isLetter() }
            if (letters < cleaned.length / 2) continue
            if (AMOUNT.containsMatchIn(cleaned) && cleaned.count { it.isDigit() } > 4) continue
            return cleaned.split(" ").joinToString(" ") { word ->
                word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }
        return null
    }

    private fun detectDate(lines: List<String>, today: LocalDate): LocalDate? {
        for (line in lines) {
            val match = DATE_CANDIDATE.find(line) ?: continue
            val candidate = match.value.trim()
            for (pattern in DATE_PATTERNS) {
                val parsed = tryParseDate(candidate, pattern) ?: continue
                // Receipts are never from the future and rarely older than a few years.
                if (parsed.isAfter(today) || parsed.isBefore(today.minusYears(5))) continue
                return parsed
            }
        }
        return null
    }

    private fun tryParseDate(value: String, pattern: String): LocalDate? = try {
        LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
    } catch (_: DateTimeParseException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Scans bottom-up for a "total" line, since the real total sits near the end of a receipt and
     * anything above it is more likely to be a subtotal or a per-item line.
     */
    private fun detectTotal(lines: List<String>, currency: String): Long? {
        val candidates = mutableListOf<Long>()
        for (line in lines.asReversed()) {
            val lower = line.lowercase(Locale.ROOT)
            if (TOTAL_EXCLUSIONS.any { lower.contains(it) }) continue
            if (TOTAL_KEYWORDS.none { lower.contains(it) }) continue
            val amount = lastAmountIn(line, currency) ?: continue
            candidates += amount
            if (candidates.size >= 3) break
        }
        if (candidates.isNotEmpty()) return candidates.max()

        // No keyword matched (cropped or badly lit receipt): fall back to the largest amount seen.
        return lines.mapNotNull { lastAmountIn(it, currency) }.maxOrNull()
    }

    private fun detectTax(lines: List<String>, currency: String): Long? {
        for (line in lines.asReversed()) {
            val lower = line.lowercase(Locale.ROOT)
            if (TAX_KEYWORDS.none { lower.contains(it) }) continue
            if (lower.contains("no") && lower.contains("vat no")) continue
            return lastAmountIn(line, currency) ?: continue
        }
        return null
    }

    /**
     * A line is treated as an item when it has some text on the left and exactly one price on the
     * right. Quantity prefixes ("2 x Coffee", "3 Coffee") are pulled out so unit price is real.
     */
    private fun detectItems(lines: List<String>, currency: String, total: Long?): List<ParsedItem> {
        val items = mutableListOf<ParsedItem>()
        val quantityPrefix = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(?:x|×|@)?\\s+(.+)$", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val lower = line.lowercase(Locale.ROOT)
            if (SKIP_LINE_KEYWORDS.any { lower.contains(it) }) continue
            if (TOTAL_KEYWORDS.any { lower.contains(it) }) continue
            if (TAX_KEYWORDS.any { lower.contains(it) }) continue

            val priceMatch = AMOUNT.findAll(line).lastOrNull() ?: continue
            val price = parseAmount(priceMatch.value, currency) ?: continue
            if (price <= 0) continue

            var name = line.substring(0, priceMatch.range.first).trim()
            name = name.trim('*', '-', ':', '.', ' ', '\t')
            // Strip a trailing currency symbol left behind by the split.
            CURRENCY_SYMBOLS.keys.forEach { symbol -> name = name.removeSuffix(symbol).trim() }
            if (name.length < 2) continue
            if (name.count { it.isLetter() } < 2) continue

            var quantity = 1.0
            quantityPrefix.find(name)?.let { match ->
                val parsedQuantity = match.groupValues[1].replace(',', '.').toDoubleOrNull()
                val remainder = match.groupValues[2].trim()
                if (parsedQuantity != null && parsedQuantity in 1.0..99.0 && remainder.length >= 2) {
                    quantity = parsedQuantity
                    name = remainder
                }
            }

            // A single line cannot legitimately exceed the receipt total.
            if (total != null && price > total) continue

            val unitPrice = if (quantity > 0) (price / quantity).toLong() else price
            items += ParsedItem(
                name = name,
                normalizedName = ItemNameNormalizer.normalize(name),
                quantity = quantity,
                unitPriceMinor = unitPrice,
                totalPriceMinor = price,
            )
        }
        return items
    }

    private fun lastAmountIn(line: String, currency: String): Long? =
        AMOUNT.findAll(line).lastOrNull()?.let { parseAmount(it.value, currency) }

    /**
     * Disambiguates `1.234,56` (European) from `1,234.56` (US): whichever separator comes last and
     * is followed by exactly two digits is the decimal point.
     */
    private fun parseAmount(raw: String, currency: String): Long? {
        val trimmed = raw.trim().replace(" ", "").replace(" ", "")
        val lastComma = trimmed.lastIndexOf(',')
        val lastDot = trimmed.lastIndexOf('.')

        val normalized = when {
            lastComma > lastDot -> trimmed.replace(".", "").replace(',', '.')
            lastDot > lastComma -> trimmed.replace(",", "")
            else -> trimmed
        }
        val value = normalized.toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        return Money.toMinor(value, currency)
    }
}
