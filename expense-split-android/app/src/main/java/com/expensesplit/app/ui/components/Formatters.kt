package com.expensesplit.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.expensesplit.app.core.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Locale-aware formatting helpers for Compose.
 *
 * Each reads the *configuration* locale rather than `Locale.getDefault()`, so switching the app
 * language updates dates and currency symbols on the next recomposition instead of after a restart.
 */

@Composable
fun currentLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) { configuration.locales.get(0) ?: Locale.getDefault() }
}

@Composable
fun rememberMoneyFormatter(): (Long, String) -> String {
    val locale = currentLocale()
    return remember(locale) { { minor, currency -> Money.format(minor, currency, locale) } }
}

@Composable
fun formatMoney(minorUnits: Long, currency: String): String =
    Money.format(minorUnits, currency, currentLocale())

@Composable
fun formatMoneyCompact(minorUnits: Long, currency: String): String =
    Money.formatCompact(minorUnits, currency, currentLocale())

@Composable
fun formatDate(date: LocalDate, style: FormatStyle = FormatStyle.MEDIUM): String {
    val locale = currentLocale()
    val formatter = remember(locale, style) {
        DateTimeFormatter.ofLocalizedDate(style).withLocale(locale)
    }
    return date.format(formatter)
}

@Composable
fun formatDateShort(date: LocalDate): String {
    val locale = currentLocale()
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    return date.format(formatter)
}

@Composable
fun formatMonthYear(month: java.time.YearMonth): String {
    val locale = currentLocale()
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("MMMM yyyy", locale) }
    return month.atDay(1).format(formatter)
}

@Composable
fun formatPercent(value: Float, decimals: Int = 0): String {
    val locale = currentLocale()
    return String.format(locale, "%.${decimals}f%%", value)
}

@Composable
fun formatSignedPercent(value: Float, decimals: Int = 1): String {
    val locale = currentLocale()
    return String.format(locale, "%+.${decimals}f%%", value)
}
