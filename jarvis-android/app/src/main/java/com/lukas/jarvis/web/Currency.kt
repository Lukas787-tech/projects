package com.lukas.jarvis.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Exchange rates, keyless.
 *
 * The first source is Frankfurter, which republishes the European Central
 * Bank's reference rates — about thirty currencies, updated each working day,
 * no account. The ECB does not quote everything, so the open ExchangeRate-API
 * endpoint is the fallback for the currencies it leaves out and for the days
 * Frankfurter does not answer.
 *
 * A model asked what fifty dollars is in euros will answer from whatever rate
 * it saw in training, which is exactly the number that looks right and is not.
 * Rates are cached for an hour because they barely move inside one and every
 * request here is somebody's free quota.
 */
class Currency {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private data class Quote(val rate: Double, val date: String, val source: String, val at: Long)

    private val cache = HashMap<String, Quote>()

    suspend fun convert(amount: Double, fromRaw: String, toRaw: String): String {
        val from = code(fromRaw) ?: return "I do not know the currency '$fromRaw'."
        val to = code(toRaw) ?: return "I do not know the currency '$toRaw'."
        if (from == to) return "${money(amount)} $from is ${money(amount)} $to."

        val quote = rate(from, to)
            ?: return "No exchange-rate service answered just now, so I cannot convert " +
                "$from to $to without guessing."
        val result = amount * quote.rate
        return "${money(amount)} $from is ${money(result)} $to, at ${rateText(quote.rate)} " +
            "$to per $from (${quote.source}, ${quote.date})."
    }

    private suspend fun rate(from: String, to: String): Quote? = withContext(Dispatchers.IO) {
        val key = "$from>$to"
        synchronized(cache) {
            cache[key]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }
        }?.let { return@withContext it }

        val quote = frankfurter("https://api.frankfurter.dev/v1/latest?base=$from&symbols=$to", to)
            ?: frankfurter("https://api.frankfurter.app/latest?from=$from&to=$to", to)
            ?: openRates(from, to)
        if (quote != null) synchronized(cache) { cache[key] = quote }
        quote
    }

    private fun frankfurter(url: String, to: String): Quote? = runCatching {
        val json = JSONObject(fetch(url))
        val rate = json.getJSONObject("rates").getDouble(to)
        Quote(rate, json.optString("date").ifBlank { "today" }, "ECB reference rate", now())
    }.getOrNull()

    private fun openRates(from: String, to: String): Quote? = runCatching {
        val json = JSONObject(fetch("https://open.er-api.com/v6/latest/$from"))
        check(json.optString("result") == "success")
        val rate = json.getJSONObject("rates").getDouble(to)
        val date = json.optString("time_last_update_utc").take(16).ifBlank { "today" }
        Quote(rate, date, "open market rate", now())
    }.getOrNull()

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Jarvis/3.0").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun money(value: Double): String = String.format(Locale.US, "%,.2f", value)

    /** Enough places that a weak currency against a strong one is not rounded to zero. */
    private fun rateText(rate: Double): String = when {
        rate >= 100 -> String.format(Locale.US, "%,.2f", rate)
        rate >= 1 -> String.format(Locale.US, "%.4f", rate)
        else -> String.format(Locale.US, "%.6f", rate)
    }

    companion object {
        private const val CACHE_MS = 60 * 60 * 1000L

        /** Words people say, mapped to the codes the services want. */
        private val NAMES = mapOf(
            "euro" to "EUR", "euros" to "EUR", "€" to "EUR",
            "dollar" to "USD", "dollars" to "USD", "us dollar" to "USD", "\$" to "USD",
            "buck" to "USD", "bucks" to "USD",
            "pound" to "GBP", "pounds" to "GBP", "sterling" to "GBP", "£" to "GBP",
            "franc" to "CHF", "francs" to "CHF", "swiss franc" to "CHF",
            "yen" to "JPY", "¥" to "JPY", "yuan" to "CNY", "renminbi" to "CNY",
            "zloty" to "PLN", "koruna" to "CZK", "forint" to "HUF", "lira" to "TRY",
            "rupee" to "INR", "rupees" to "INR", "real" to "BRL", "reais" to "BRL",
            "rand" to "ZAR", "won" to "KRW", "peso" to "MXN", "pesos" to "MXN",
            "canadian dollar" to "CAD", "australian dollar" to "AUD",
            "swedish krona" to "SEK", "norwegian krone" to "NOK", "danish krone" to "DKK",
            "ruble" to "RUB", "rouble" to "RUB", "hryvnia" to "UAH", "leu" to "RON",
            "lev" to "BGN", "shekel" to "ILS", "dirham" to "AED", "baht" to "THB"
        )

        fun code(raw: String): String? {
            val text = raw.trim().lowercase(Locale.ROOT)
            if (text.isBlank()) return null
            NAMES[text]?.let { return it }
            val upper = text.uppercase(Locale.ROOT)
            return upper.takeIf { it.length == 3 && it.all { c -> c in 'A'..'Z' } }
        }
    }
}
