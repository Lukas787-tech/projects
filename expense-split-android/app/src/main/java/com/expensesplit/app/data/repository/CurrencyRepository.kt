package com.expensesplit.app.data.repository

import com.expensesplit.app.core.Money
import com.expensesplit.app.data.local.dao.PriceDao
import com.expensesplit.app.data.local.entity.FxRateEntity
import com.expensesplit.app.data.remote.api.CurrencyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Currency
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-currency support.
 *
 * Rates are fetched on demand and cached in Room, so conversion keeps working offline with the last
 * known rate. Every expense also stores the rate it was converted at, which means historical
 * reports never silently change when today's rate moves.
 */
@Singleton
class CurrencyRepository @Inject constructor(
    private val currencyApi: CurrencyApi,
    private val priceDao: PriceDao,
) {

    private val refreshInterval = TimeUnit.HOURS.toMillis(12)

    /** Currencies offered in the picker: the common ones first, then everything the JVM knows. */
    val supportedCurrencies: List<String> by lazy {
        val common = listOf(
            "USD", "EUR", "GBP", "JPY", "CNY", "CHF", "CAD", "AUD", "INR", "SEK", "NOK", "DKK",
            "PLN", "CZK", "HUF", "RON", "TRY", "RUB", "BRL", "MXN", "ARS", "CLP", "COP", "ZAR",
            "AED", "SAR", "QAR", "EGP", "MAD", "NGN", "KES", "KRW", "SGD", "HKD", "TWD", "THB",
            "MYR", "IDR", "PHP", "VND", "NZD", "ILS", "UAH",
        )
        val all = runCatching {
            Currency.getAvailableCurrencies().map { it.currencyCode }.sorted()
        }.getOrDefault(emptyList())
        (common + all.filterNot { it in common }).distinct()
    }

    fun displayName(code: String, locale: Locale = Locale.getDefault()): String = runCatching {
        val currency = Currency.getInstance(code)
        "${currency.currencyCode} — ${currency.getDisplayName(locale)}"
    }.getOrDefault(code)

    fun symbol(code: String, locale: Locale = Locale.getDefault()): String = runCatching {
        Currency.getInstance(code).getSymbol(locale)
    }.getOrDefault(code)

    /**
     * Conversion rate from [from] to [to], preferring a fresh network rate and falling back to the
     * cache. Returns 1.0 when the currencies match or nothing is known, which keeps the app usable
     * offline at the cost of an un-converted figure the UI flags.
     */
    suspend fun rate(from: String, to: String): Double = withContext(Dispatchers.IO) {
        if (from.equals(to, ignoreCase = true)) return@withContext 1.0

        val cached = priceDao.getRate(from, to)
        val isFresh = cached != null && System.currentTimeMillis() - cached.fetchedAt < refreshInterval
        if (isFresh) return@withContext cached.rate

        val refreshed = runCatching { refreshRates(from) }.getOrNull()
        refreshed?.get(to) ?: cached?.rate ?: 1.0
    }

    suspend fun convert(amountMinor: Long, from: String, to: String): Long {
        if (from.equals(to, ignoreCase = true)) return amountMinor
        return Money.convert(amountMinor, rate(from, to), from, to)
    }

    /** Fetches and caches every rate for [base]; returns null-safe map of quote -> rate. */
    suspend fun refreshRates(base: String): Map<String, Double> = withContext(Dispatchers.IO) {
        val response = currencyApi.latestRates(base)
        if (response.rates.isEmpty()) return@withContext emptyMap()

        val now = System.currentTimeMillis()
        priceDao.upsertRates(
            response.rates.map { (quote, rate) ->
                FxRateEntity(base = base, quote = quote, rate = rate, fetchedAt = now)
            },
        )
        response.rates
    }

    suspend fun cachedRates(base: String): Map<String, Double> =
        priceDao.getRatesFor(base).associate { it.quote to it.rate }

    suspend fun lastUpdatedAt(base: String): Long? =
        priceDao.getRatesFor(base).maxOfOrNull { it.fetchedAt }
}
