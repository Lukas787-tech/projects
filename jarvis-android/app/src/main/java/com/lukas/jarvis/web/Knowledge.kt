package com.lukas.jarvis.web

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * Everything Jarvis looks up that is not a web search: headlines, words in
 * other languages, prices, holidays, recipes, scores, shows, books and the odd
 * joke. Every source here answers without a key or an account, and each was
 * checked from a clean machine before being relied on.
 *
 * Every answer is plain prose, short enough to read aloud, because that is what
 * a small model repeats most faithfully.
 */
/** One news story, as the dashboard lists it. */
data class Headline(val title: String, val source: String?, val age: String?)

class Knowledge {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // -------------------------------------------------------------------- news

    /**
     * Headlines from Google News' public feed, in the phone's language, with
     * the BBC's world feed behind it if Google does not answer.
     */
    suspend fun news(topic: String?, limit: Int, locale: Locale = Locale.getDefault()): String =
        withContext(Dispatchers.IO) {
            val lang = locale.language.ifBlank { "en" }
            val country = locale.country.ifBlank { if (lang == "en") "US" else lang.uppercase(Locale.ROOT) }
            val hl = if (lang == "en") "en-$country" else lang
            val suffix = "hl=$hl&gl=$country&ceid=$country:$lang"
            val url = if (topic.isNullOrBlank()) {
                "https://news.google.com/rss?$suffix"
            } else {
                "https://news.google.com/rss/search?q=${enc(topic)}+when:3d&$suffix"
            }
            val items = runCatching { rss(get(url)) }.getOrDefault(emptyList())
                .ifEmpty {
                    if (topic.isNullOrBlank()) {
                        runCatching { rss(get("https://feeds.bbci.co.uk/news/world/rss.xml")) }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                }
            if (items.isEmpty()) {
                return@withContext if (topic.isNullOrBlank()) "No headlines came back just now."
                else "Nothing in the news about '$topic' in the last few days."
            }
            buildString {
                appendLine(if (topic.isNullOrBlank()) "Top headlines:" else "Latest on $topic:")
                items.take(limit.coerceIn(1, 10)).forEachIndexed { index, item ->
                    append("${index + 1}. ${item.title}")
                    item.source?.let { append(" — $it") }
                    item.age?.let { append(" ($it)") }
                    appendLine()
                }
            }.trim()
        }

    /**
     * Today's top stories as items rather than prose, for the dashboard and
     * the morning brief. Empty when the feeds do not answer.
     */
    suspend fun headlines(limit: Int = 3, locale: Locale = Locale.getDefault()): List<Headline> =
        withContext(Dispatchers.IO) {
            val lang = locale.language.ifBlank { "en" }
            val country = locale.country.ifBlank { if (lang == "en") "US" else lang.uppercase(Locale.ROOT) }
            val hl = if (lang == "en") "en-$country" else lang
            val items = runCatching { rss(get("https://news.google.com/rss?hl=$hl&gl=$country&ceid=$country:$lang")) }
                .getOrDefault(emptyList())
                .ifEmpty { runCatching { rss(get("https://feeds.bbci.co.uk/news/world/rss.xml")) }.getOrDefault(emptyList()) }
            items.take(limit).map { Headline(it.title, it.source, it.age) }
        }

    private data class RssItem(val title: String, val source: String?, val age: String?)

    private fun rss(xml: String): List<RssItem> {
        val out = ArrayList<RssItem>()
        for (match in ITEM.findAll(xml)) {
            val body = match.groupValues[1]
            var title = tag(body, "title") ?: continue
            var source = tag(body, "source")
            // Google appends " - Source" to every title; say it once.
            if (source != null && title.endsWith(" - $source")) {
                title = title.removeSuffix(" - $source")
            } else if (source == null) {
                val dash = title.lastIndexOf(" - ")
                if (dash > 20) {
                    source = title.substring(dash + 3)
                    title = title.substring(0, dash)
                }
            }
            val age = tag(body, "pubDate")?.let { ago(it) }
            out += RssItem(title.trim(), source?.trim(), age)
            if (out.size >= 15) break
        }
        return out
    }

    private fun tag(body: String, name: String): String? {
        val raw = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: return null
        return text(raw.replace("<![CDATA[", "").replace("]]>", "")).ifBlank { null }
    }

    private fun ago(pubDate: String): String? {
        val parsed = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(pubDate.trim())
        }.getOrNull() ?: return null
        val minutes = (System.currentTimeMillis() - parsed.time) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 48 * 60 -> "${minutes / 60}h ago"
            else -> "${minutes / (60 * 24)}d ago"
        }
    }

    // --------------------------------------------------------------- language

    /** MyMemory: free, keyless, and good at short sentences. */
    suspend fun translate(text: String, from: String, to: String): String = withContext(Dispatchers.IO) {
        val source = languageCode(from) ?: "en"
        val target = languageCode(to) ?: return@withContext "Which language should that go into?"
        if (source == target) return@withContext "That is already in ${languageName(target)}: $text"
        val translated = translateText(text, source, target)
            ?: return@withContext "The translation service could not do ${languageName(source)} to " +
                "${languageName(target)} just now. Translate it yourself instead."
        "${languageName(target)}: $translated"
    }

    /**
     * The translation alone, or null when the service had none to give.
     *
     * MyMemory answers a spent daily allowance or a language pair it does not
     * know with a 200 and the complaint where the translation should be, so
     * both the status and the text are checked; otherwise the complaint would
     * be read out as if it were Spanish.
     */
    suspend fun translateText(text: String, from: String, to: String): String? = withContext(Dispatchers.IO) {
        val source = languageCode(from) ?: return@withContext null
        val target = languageCode(to) ?: return@withContext null
        if (source == target) return@withContext text
        runCatching {
            val json = JSONObject(
                get("https://api.mymemory.translated.net/get?q=${enc(text.take(480))}&langpair=$source|$target")
            )
            val status = json.optString("responseStatus").toIntOrNull() ?: 200
            val translated = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
            translated.takeIf { status == 200 && it.isNotBlank() && !looksLikeServiceNotice(it) }?.let(::text)
        }.getOrNull()
    }

    /** A language name or code, as a two-letter code; null when it is not one. */
    fun codeFor(raw: String?): String? = raw?.let(::languageCode)

    /** "Spanish" for "es". */
    fun nameOf(code: String): String = languageName(code)

    /** "Español" for "es": what the other person reads on their button. */
    fun nativeNameOf(code: String): String =
        Locale(code).let { it.getDisplayLanguage(it) }.ifBlank { languageName(code) }
            .replaceFirstChar { it.titlecase(Locale(code)) }

    private fun looksLikeServiceNotice(text: String): Boolean {
        val upper = text.uppercase(Locale.ROOT)
        return "MYMEMORY WARNING" in upper || "INVALID LANGUAGE PAIR" in upper ||
            "QUERY LENGTH LIMIT" in upper || "PLEASE SELECT TWO DISTINCT LANGUAGES" in upper ||
            "YOU USED ALL AVAILABLE FREE TRANSLATIONS" in upper
    }

    /** Wiktionary's definitions, which cover words from nearly every language in English. */
    suspend fun define(word: String): String = withContext(Dispatchers.IO) {
        val clean = word.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) return@withContext "Which word?"
        val body = runCatching {
            get("https://en.wiktionary.org/api/rest_v1/page/definition/${enc(clean).replace("+", "%20")}")
        }.getOrNull() ?: return@withContext "No dictionary entry for '$word'."
        val json = JSONObject(body)
        val languages = json.keys().asSequence().toList()
            .sortedBy { if (it == "en") 0 else 1 }
        val out = StringBuilder()
        var shown = 0
        for (lang in languages) {
            val entries = json.optJSONArray(lang) ?: continue
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val part = entry.optString("partOfSpeech")
                val language = entry.optString("language")
                val defs = entry.optJSONArray("definitions") ?: continue
                for (j in 0 until minOf(defs.length(), 2)) {
                    val def = text(defs.optJSONObject(j)?.optString("definition").orEmpty())
                    if (def.isBlank()) continue
                    out.appendLine("- ($language ${part.lowercase(Locale.ROOT)}) $def")
                    if (++shown >= 5) break
                }
                if (shown >= 5) break
            }
            if (shown >= 5) break
        }
        if (out.isBlank()) "No dictionary entry for '$word'." else "$word:\n${out.toString().trim()}"
    }

    // ---------------------------------------------------------------- markets

    /** A share price from Yahoo's public chart feed, or a coin's from CoinGecko. */
    suspend fun price(query: String, kind: String, currency: String): String = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext "Which stock or coin?"
        val wantCrypto = kind == "crypto" || CRYPTO_WORDS.any { q.lowercase(Locale.ROOT).contains(it) }
        val first = if (wantCrypto) runCatching { crypto(q, currency) } else runCatching { stock(q) }
        first.getOrNull()?.let { return@withContext it }
        val second = if (wantCrypto) runCatching { stock(q) } else runCatching { crypto(q, currency) }
        second.getOrNull() ?: "Could not find a price for '$q'."
    }

    private fun stock(query: String): String? {
        val symbol = if (TICKER.matches(query.trim())) {
            query.trim().uppercase(Locale.ROOT)
        } else {
            val search = JSONObject(
                get("https://query1.finance.yahoo.com/v1/finance/search?q=${enc(query)}&quotesCount=3&newsCount=0")
            )
            search.optJSONArray("quotes")?.let { quotes ->
                (0 until quotes.length()).mapNotNull { quotes.optJSONObject(it) }
                    .firstOrNull { it.optString("quoteType") in setOf("EQUITY", "ETF", "INDEX", "MUTUALFUND") }
                    ?.optString("symbol")
            } ?: return null
        }
        val chart = JSONObject(
            get("https://query1.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?range=1d&interval=1d")
        )
        val meta = chart.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)
            ?.optJSONObject("meta") ?: return null
        val price = meta.optDouble("regularMarketPrice", Double.NaN)
        if (price.isNaN()) return null
        val previous = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", Double.NaN))
        val name = meta.optString("longName").ifBlank { meta.optString("shortName") }.ifBlank { symbol }
        val change = if (!previous.isNaN() && previous > 0) (price - previous) / previous * 100 else null
        return buildString {
            append("$name ($symbol): ${money(price)} ${meta.optString("currency")}")
            change?.let { append(", ${signed(it)}% today") }
            meta.optString("fullExchangeName").takeIf { it.isNotBlank() }?.let { append(" on $it") }
            append(".")
        }
    }

    private fun crypto(query: String, currency: String): String? {
        val search = JSONObject(get("https://api.coingecko.com/api/v3/search?query=${enc(query)}"))
        val coin = search.optJSONArray("coins")?.optJSONObject(0) ?: return null
        val id = coin.optString("id").ifBlank { return null }
        val cur = currency.lowercase(Locale.ROOT).ifBlank { "eur" }
        val prices = JSONObject(
            get("https://api.coingecko.com/api/v3/simple/price?ids=$id&vs_currencies=$cur&include_24hr_change=true")
        ).optJSONObject(id) ?: return null
        val price = prices.optDouble(cur, Double.NaN)
        if (price.isNaN()) return null
        val change = prices.optDouble("${cur}_24h_change", Double.NaN)
        return buildString {
            append("${coin.optString("name")} (${coin.optString("symbol").uppercase(Locale.ROOT)}): ")
            append("${money(price)} ${cur.uppercase(Locale.ROOT)}")
            if (!change.isNaN()) append(", ${signed(change)}% in 24 hours")
            append(".")
        }
    }

    // --------------------------------------------------------------- calendar

    /** Public holidays from Nager.Date, upcoming first. */
    suspend fun holidays(country: String, year: Int?): String = withContext(Dispatchers.IO) {
        val code = country.trim().uppercase(Locale.ROOT).take(2).ifBlank {
            Locale.getDefault().country.ifBlank { "US" }
        }
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        val body = runCatching {
            get("https://date.nager.at/api/v3/PublicHolidays/${year ?: thisYear}/$code")
        }.getOrNull() ?: return@withContext "No holiday calendar for '$code'."
        val list = JSONArray(body)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val all = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        val shown = if (year == null) all.filter { it.optString("date") >= today }.ifEmpty { all } else all
        if (shown.isEmpty()) return@withContext "No public holidays listed for $code."
        buildString {
            appendLine("Public holidays in $code${if (year == null) " still to come this year" else " in $year"}:")
            shown.take(12).forEach { holiday ->
                val national = holiday.optBoolean("global", true)
                append("- ${holiday.optString("date")}: ${holiday.optString("localName")}")
                val english = holiday.optString("name")
                if (english.isNotBlank() && english != holiday.optString("localName")) append(" ($english)")
                if (!national) append(" — only in some regions")
                appendLine()
            }
        }.trim()
    }

    // ---------------------------------------------------------------- the rest

    /** TheMealDB: a dish, its ingredients and the method. */
    suspend fun recipe(dish: String): String = withContext(Dispatchers.IO) {
        val json = JSONObject(get("https://www.themealdb.com/api/json/v1/1/search.php?s=${enc(dish)}"))
        val meal = json.optJSONArray("meals")?.optJSONObject(0)
            ?: return@withContext "No recipe found for '$dish'."
        val ingredients = (1..20).mapNotNull { i ->
            val item = meal.optString("strIngredient$i").trim()
            if (item.isBlank() || item == "null") return@mapNotNull null
            val measure = meal.optString("strMeasure$i").trim().takeIf { it.isNotBlank() && it != "null" }
            listOfNotNull(measure, item).joinToString(" ")
        }
        buildString {
            appendLine("${meal.optString("strMeal")} (${meal.optString("strArea")} ${meal.optString("strCategory")})")
            appendLine("Ingredients: ${ingredients.joinToString(", ")}")
            append("Method: ${meal.optString("strInstructions").replace(Regex("\\s+"), " ").take(1400)}")
        }
    }

    /** TheSportsDB's public test key: a team's last result and next fixture. */
    suspend fun sports(team: String): String = withContext(Dispatchers.IO) {
        val search = JSONObject(get("https://www.thesportsdb.com/api/v1/json/3/searchteams.php?t=${enc(team)}"))
        val found = search.optJSONArray("teams")?.optJSONObject(0)
            ?: return@withContext "No team called '$team' found."
        val id = found.optString("idTeam")
        val name = found.optString("strTeam")
        val last = runCatching {
            JSONObject(get("https://www.thesportsdb.com/api/v1/json/3/eventslast.php?id=$id"))
                .optJSONArray("results")?.optJSONObject(0)
        }.getOrNull()
        val next = runCatching {
            JSONObject(get("https://www.thesportsdb.com/api/v1/json/3/eventsnext.php?id=$id"))
                .optJSONArray("events")?.optJSONObject(0)
        }.getOrNull()
        buildString {
            append("$name (${found.optString("strLeague")}).")
            last?.let {
                append(" Last: ${it.optString("strHomeTeam")} ${it.optString("intHomeScore")}–")
                append("${it.optString("intAwayScore")} ${it.optString("strAwayTeam")} on ${it.optString("dateEvent")}.")
            }
            next?.let {
                append(" Next: ${it.optString("strEvent")} on ${it.optString("dateEvent")}")
                it.optString("strTime").takeIf { t -> t.isNotBlank() && t != "null" }?.let { t -> append(" at ${t.take(5)} UTC") }
                append(".")
            }
            if (last == null && next == null) append(" No recent or upcoming fixtures listed.")
        }
    }

    /** TVmaze: what a show is, whether it is still running, and when it is next on. */
    suspend fun tvShow(name: String): String = withContext(Dispatchers.IO) {
        val show = runCatching {
            JSONObject(get("https://api.tvmaze.com/singlesearch/shows?q=${enc(name)}&embed=nextepisode"))
        }.getOrNull() ?: return@withContext "No show called '$name' found."
        val next = show.optJSONObject("_embedded")?.optJSONObject("nextepisode")
        val network = show.optJSONObject("network")?.optString("name")
            ?: show.optJSONObject("webChannel")?.optString("name")
        val rating = show.optJSONObject("rating")?.optDouble("average", Double.NaN)
        buildString {
            append("${show.optString("name")}: ${show.optString("status")}")
            show.optString("premiered").takeIf { it.isNotBlank() && it != "null" }?.let { append(", since ${it.take(4)}") }
            network?.takeIf { it.isNotBlank() }?.let { append(", on $it") }
            if (rating != null && !rating.isNaN()) append(", rated $rating/10")
            append(". ")
            append(text(show.optString("summary")).take(500))
            next?.let {
                append(" Next episode: \"${it.optString("name")}\" (S${it.optInt("season")}E${it.optInt("number")}) on ${it.optString("airdate")}.")
            }
        }
    }

    /** Open Library: who wrote it, when, and how long it is. */
    suspend fun book(query: String): String = withContext(Dispatchers.IO) {
        val json = JSONObject(get("https://openlibrary.org/search.json?q=${enc(query)}&limit=3"))
        val docs = json.optJSONArray("docs") ?: return@withContext "No book found for '$query'."
        if (docs.length() == 0) return@withContext "No book found for '$query'."
        (0 until minOf(docs.length(), 3)).joinToString("\n") { i ->
            val doc = docs.optJSONObject(i) ?: JSONObject()
            val authors = doc.optJSONArray("author_name")?.let { a ->
                (0 until minOf(a.length(), 2)).joinToString(" and ") { a.optString(it) }
            }
            buildString {
                append("- ${doc.optString("title")}")
                authors?.let { append(" by $it") }
                doc.optInt("first_publish_year").takeIf { it > 0 }?.let { append(" ($it)") }
                doc.optInt("number_of_pages_median").takeIf { it > 0 }?.let { append(", about $it pages") }
                doc.optDouble("ratings_average", Double.NaN).takeIf { !it.isNaN() }
                    ?.let { append(", rated ${"%.1f".format(Locale.US, it)}/5") }
            }
        }
    }

    /** A joke, a fact or a quote from somewhere real, rather than the model's tenth retelling of the same one. */
    suspend fun amuse(kind: String): String = withContext(Dispatchers.IO) {
        when (kind) {
            "fact" -> runCatching {
                JSONObject(get("https://uselessfacts.jsph.pl/api/v2/facts/random?language=en")).optString("text")
            }.getOrNull()?.let { "Fact: $it" }
            "quote" -> runCatching {
                val q = JSONArray(get("https://zenquotes.io/api/random")).optJSONObject(0)
                "\"${q.optString("q")}\" — ${q.optString("a")}"
            }.getOrNull()
            else -> runCatching {
                JSONObject(get("https://icanhazdadjoke.com/")).optString("joke")
            }.getOrNull() ?: runCatching {
                val j = JSONObject(get("https://v2.jokeapi.dev/joke/Any?safe-mode"))
                if (j.optString("type") == "twopart") "${j.optString("setup")} … ${j.optString("delivery")}"
                else j.optString("joke")
            }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: "The ${kind.ifBlank { "joke" }} service did not answer; make one up yourself."
    }

    /** Coins, dice and picks, from a real random source rather than a model's favourite number. */
    fun random(kind: String, min: Int?, max: Int?, count: Int?, options: List<String>): String {
        val n = (count ?: 1).coerceIn(1, 10)
        return when (kind) {
            "coin" -> (1..n).joinToString(", ") { if (Random.nextBoolean()) "heads" else "tails" }
                .let { "Coin: $it." }
            "dice" -> {
                val sides = (max ?: 6).coerceIn(2, 1000)
                val rolls = (1..n).map { Random.nextInt(1, sides + 1) }
                "Rolled ${rolls.joinToString(", ")} on a d$sides" + if (n > 1) ", total ${rolls.sum()}." else "."
            }
            "pick" -> if (options.isEmpty()) "Nothing to pick from." else "Picked: ${options.random()}."
            "password" -> password((max ?: 20).coerceIn(8, 64))
            else -> {
                val lo = min ?: 1
                val hi = (max ?: 100).coerceAtLeast(lo)
                "Random number between $lo and $hi: ${(1..n).joinToString(", ") { Random.nextInt(lo, hi + 1).toString() }}."
            }
        }
    }

    /**
     * A password from a cryptographic generator, never the everyday one: at
     * least one capital, small letter, digit and symbol, and none of the
     * letters that are misread for each other (O and 0, l and 1).
     */
    private fun password(length: Int): String {
        val secure = java.security.SecureRandom()
        val sets = listOf(
            "ABCDEFGHJKLMNPQRSTUVWXYZ",
            "abcdefghijkmnopqrstuvwxyz",
            "23456789",
            "!@#$%&*-_+=?"
        )
        val all = sets.joinToString("")
        val chars = sets.map { it[secure.nextInt(it.length)] }.toMutableList()
        while (chars.size < length) chars += all[secure.nextInt(all.length)]
        // Shuffled with the same generator, so the guaranteed four are not
        // always at the front.
        for (i in chars.indices.reversed()) {
            val j = secure.nextInt(i + 1)
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        return "Password ($length characters): ${chars.joinToString("")}"
    }

    // ---------------------------------------------------------------- helpers

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, application/rss+xml, text/xml, */*")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value.trim(), "UTF-8")

    @Suppress("DEPRECATION")
    private fun text(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\s+"), " ").trim()

    private fun money(value: Double): String = when {
        abs(value) >= 1000 -> String.format(Locale.US, "%,.2f", value)
        abs(value) >= 1 -> String.format(Locale.US, "%.2f", value)
        else -> String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
    }

    private fun signed(value: Double) = String.format(Locale.US, "%+.2f", value)

    /** A language name or code, as the ISO 639-1 code MyMemory wants. */
    private fun languageCode(raw: String): String? {
        val key = raw.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return null
        if (key.length == 2) return key
        if (key.length == 5 && key[2] == '-') return key.take(2)
        LANGUAGES[key]?.let { return it }
        return Locale.getAvailableLocales().firstOrNull {
            it.getDisplayLanguage(Locale.ENGLISH).equals(key, true) ||
                it.getDisplayLanguage(it).equals(key, true)
        }?.language
    }

    private fun languageName(code: String): String =
        Locale(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) Jarvis/5.0"
        val ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        val TICKER = Regex("^[A-Za-z]{1,5}([.-][A-Za-z]{1,3})?$")
        val CRYPTO_WORDS = listOf(
            "bitcoin", "btc", "ethereum", "eth", "solana", "dogecoin", "doge", "crypto", "coin",
            "xrp", "cardano", "litecoin", "tether", "bnb"
        )
        val LANGUAGES = mapOf(
            "german" to "de", "deutsch" to "de", "english" to "en", "englisch" to "en",
            "spanish" to "es", "spanisch" to "es", "french" to "fr", "französisch" to "fr",
            "italian" to "it", "italienisch" to "it", "portuguese" to "pt", "dutch" to "nl",
            "polish" to "pl", "turkish" to "tr", "türkisch" to "tr", "russian" to "ru",
            "japanese" to "ja", "chinese" to "zh", "korean" to "ko", "arabic" to "ar",
            "greek" to "el", "swedish" to "sv", "norwegian" to "no", "danish" to "da",
            "finnish" to "fi", "czech" to "cs", "ukrainian" to "uk", "hindi" to "hi",
            "croatian" to "hr", "hungarian" to "hu", "romanian" to "ro"
        )
    }
}
