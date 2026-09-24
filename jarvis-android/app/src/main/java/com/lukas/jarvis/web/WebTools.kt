package com.lukas.jarvis.web

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResult(val title: String, val url: String, val snippet: String)

/**
 * Internet access with no API key and no bill: DuckDuckGo's HTML endpoints for
 * results, their instant-answer API for direct facts, and a plain fetch-and-strip
 * for reading a page.
 */
class WebTools {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun search(query: String, limit: Int = 5): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            // lite/ is the smallest page and is tried first; html/ is the fallback
            // because it occasionally answers when lite/ rate-limits.
            val endpoints = listOf(
                "https://lite.duckduckgo.com/lite/?q=$encoded",
                "https://html.duckduckgo.com/html/?q=$encoded"
            )
            for (endpoint in endpoints) {
                val body = runCatching { fetch(endpoint) }.getOrNull() ?: continue
                val results = parseResults(body).take(limit)
                if (results.isNotEmpty()) return@withContext results
            }
            emptyList()
        }

    /** DuckDuckGo's own one-line answer, when the question has one. */
    suspend fun instantAnswer(query: String): String? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = runCatching {
            fetch("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
        }.getOrNull() ?: return@withContext null

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val abstract = json.optString("AbstractText")
        if (abstract.isNotBlank()) {
            val source = json.optString("AbstractSource")
            return@withContext if (source.isNotBlank()) "$abstract (via $source)" else abstract
        }
        val answer = json.optString("Answer")
        if (answer.isNotBlank()) return@withContext answer

        json.optJSONArray("RelatedTopics")?.let { topics ->
            for (i in 0 until minOf(topics.length(), 3)) {
                val text = topics.optJSONObject(i)?.optString("Text").orEmpty()
                if (text.isNotBlank()) return@withContext text
            }
        }
        null
    }

    /**
     * The opening of a Wikipedia article, in the phone's language first.
     *
     * Search then summary: the summary endpoint wants an exact title, and
     * nobody says one. Keyless, and the one source here that is written to be
     * read out loud.
     */
    suspend fun wikipedia(query: String, language: String): String? = withContext(Dispatchers.IO) {
        val languages = listOf(language.lowercase(), "en").distinct()
        for (lang in languages) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val search = runCatching {
                JSONObject(fetch("https://$lang.wikipedia.org/w/rest.php/v1/search/title?q=$encoded&limit=1"))
            }.getOrNull() ?: continue
            val key = search.optJSONArray("pages")?.optJSONObject(0)?.optString("key")
                ?.takeIf { it.isNotBlank() } ?: continue
            val summary = runCatching {
                JSONObject(
                    fetch("https://$lang.wikipedia.org/api/rest_v1/page/summary/" +
                        URLEncoder.encode(key, "UTF-8").replace("+", "%20"))
                )
            }.getOrNull() ?: continue
            val extract = summary.optString("extract").trim()
            if (extract.isBlank()) continue
            val title = summary.optString("title").ifBlank { key }
            return@withContext "$title (Wikipedia, $lang): $extract"
        }
        null
    }

    /** Fetches a URL and flattens it to readable text. */
    suspend fun readPage(url: String, maxChars: Int = 6000): String = withContext(Dispatchers.IO) {
        val normalized = if (url.startsWith("http")) url else "https://$url"
        val html = runCatching { fetch(normalized) }.getOrElse {
            return@withContext "Could not open $url: ${it.message}"
        }
        val text = htmlToText(html)
        if (text.length > maxChars) text.take(maxChars) + "\n…[truncated]" else text
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun parseResults(html: String): List<SearchResult> {
        // Links and snippets are collected separately with their document
        // positions, then paired up, because the two DuckDuckGo layouts differ
        // in attribute order and quote style but both keep the snippet after
        // the link it belongs to.
        val links = ArrayList<Triple<Int, String, String>>()
        for (match in ANCHOR.findAll(html)) {
            val attrs = match.groupValues[1]
            if (!attrs.contains("result-link", true) && !attrs.contains("result__a", true)) continue
            val href = ATTR_HREF.find(attrs)?.groupValues?.get(1) ?: continue
            val title = decode(match.groupValues[2]).trim()
            if (title.isBlank()) continue
            links.add(Triple(match.range.first, title, unwrap(href)))
        }

        val snippets = SNIPPET.findAll(html)
            .map { it.range.first to decode(it.groupValues[2]).trim() }
            .toList()

        val out = ArrayList<SearchResult>()
        val seen = HashSet<String>()
        for ((position, title, url) in links) {
            if (!url.startsWith("http")) continue
            if (!seen.add(url)) continue
            val snippet = snippets.firstOrNull { it.first > position }?.second.orEmpty()
            out.add(SearchResult(title, url, snippet.take(320)))
            if (out.size >= 12) break
        }
        return out
    }

    /** DuckDuckGo wraps outbound links in /l/?uddg=<encoded target>. */
    private fun unwrap(href: String): String {
        val raw = if (href.startsWith("//")) "https:$href" else href
        val uddg = Regex("[?&]uddg=([^&]+)").find(raw)?.groupValues?.get(1) ?: return raw
        return runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(raw)
    }

    private fun decode(raw: String): String = htmlToText(raw)

    @Suppress("DEPRECATION")
    private fun htmlToText(html: String): String {
        val stripped = html
            .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|tr|h[1-6])>"), "\n")
        val text = Html.fromHtml(stripped, Html.FROM_HTML_MODE_COMPACT).toString()
        return text
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"

        // Both DuckDuckGo layouts are handled: lite/ uses single-quoted
        // class='result-link', html/ uses double-quoted class="result__a".
        val ANCHOR = Regex(
            "<a\\s+([^>]*)>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val ATTR_HREF = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val SNIPPET = Regex(
            "class\\s*=\\s*[\"'][^\"']*(result-snippet|result__snippet)[^\"']*[\"'][^>]*>(.*?)</",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }
}
