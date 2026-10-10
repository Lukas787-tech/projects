package com.lukas.jarvis.llm

import com.lukas.jarvis.auto.Routine
import com.lukas.jarvis.auto.Routines
import com.lukas.jarvis.brief.Briefer
import com.lukas.jarvis.control.Agenda
import com.lukas.jarvis.control.Caller
import com.lukas.jarvis.control.Chat
import com.lukas.jarvis.control.Chats
import com.lukas.jarvis.control.Device
import com.lukas.jarvis.control.Launcher
import com.lukas.jarvis.control.Messenger
import com.lukas.jarvis.control.People
import com.lukas.jarvis.control.Phone
import com.lukas.jarvis.control.ScreenReader
import com.lukas.jarvis.core.Calculator
import com.lukas.jarvis.core.DateMath
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.core.Units
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.data.Money
import com.lukas.jarvis.auto.Trigger
import com.lukas.jarvis.data.Recurring
import com.lukas.jarvis.moment.Bar
import com.lukas.jarvis.moment.Chart
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.maps.Locator
import com.lukas.jarvis.maps.Navigator
import com.lukas.jarvis.maps.PlacesClient
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.stage.StageStore
import com.lukas.jarvis.notify.ReplyListener
import com.lukas.jarvis.notify.Reminders
import com.lukas.jarvis.web.Currency
import com.lukas.jarvis.web.Home
import com.lukas.jarvis.web.Imagine
import com.lukas.jarvis.web.Knowledge
import com.lukas.jarvis.web.Weather
import com.lukas.jarvis.vision.CameraBus
import com.lukas.jarvis.web.WebTools
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// The web: search, pages, news, markets, knowledge, translation.

internal fun Tools.webTools(): List<JSONObject> = listOf(
    tool(
        "web_search",
        "Search the live internet. Use for news, prices, opening hours, facts you are " +
            "unsure about, or anything after your training cutoff.",
        props(
            "query" to str("Search terms."),
            "limit" to int("How many results. Default 5.")
        ),
        listOf("query")
    ),
    tool(
        "open_url",
        "Fetch a web page and read its text. Use to follow up on a search result.",
        props("url" to str("Full URL to open.")),
        listOf("url")
    ),
    tool(
        "wikipedia",
        "The opening of the Wikipedia article on a person, place, thing or idea. Use for " +
            "'who was', 'what is', 'tell me about' — facts that do not change by the day.",
        props(
            "topic" to str("What to look up, e.g. 'Brandenburg Gate'."),
            "language" to str("Two-letter language code to prefer, e.g. 'de'. Omit for the phone's.")
        ),
        listOf("topic")
    ),
    tool(
        "convert_currency",
        "Convert money at today's real exchange rate. ALWAYS use this for any amount in " +
            "one currency asked for in another; never use a rate you remember.",
        props(
            "amount" to num("How much."),
            "from" to str("Currency it is in: a code like 'USD' or a word like 'dollars'."),
            "to" to str("Currency wanted, e.g. 'EUR'.")
        ),
        listOf("amount", "from", "to")
    )
)

internal fun Tools.worldTools(): List<JSONObject> = listOf(
    tool(
        "news",
        "Current headlines, or the latest news about a topic. Use for 'what's in the " +
            "news', 'what happened with', 'any news about'.",
        props(
            "topic" to str("What the news should be about. Omit for top headlines."),
            "limit" to int("How many headlines. Default 5.")
        ),
        emptyList()
    ),
    tool(
        "translate",
        "Translate text into another language exactly. Use for 'how do you say', " +
            "'translate', 'what is this in German'.",
        props(
            "text" to str("The words to translate."),
            "to" to str("Target language, e.g. 'Spanish' or 'es'."),
            "from" to str("Source language, e.g. 'English' or 'en'.")
        ),
        listOf("text", "to", "from")
    ),
    tool(
        "interpreter",
        "Open live interpreter mode on screen: the user and someone who speaks another " +
            "language talk through the phone, each tapping their side, and every line is " +
            "translated and spoken aloud. Use for 'be my interpreter', 'help me talk to this " +
            "person in Spanish', 'interpret for me'. Not for translating one phrase.",
        props(
            "language" to str("The other person's language, e.g. 'Spanish'."),
            "action" to str("start (default) or stop.", listOf("start", "stop"))
        ),
        emptyList()
    ),
    tool(
        "define_word",
        "Dictionary definition of a word, in any language.",
        props("word" to str("The word.")),
        listOf("word")
    ),
    tool(
        "market_price",
        "Live price of a share, fund, index or cryptocurrency, with today's change. " +
            "Never quote a price from memory.",
        props(
            "query" to str("Company name, ticker or coin, e.g. 'Apple', 'TSLA', 'bitcoin'."),
            "kind" to str("What it is.", listOf("auto", "stock", "crypto")),
            "currency" to str("For crypto: the currency to price it in, e.g. 'EUR'.")
        ),
        listOf("query")
    ),
    tool(
        "holidays",
        "Public holidays in a country: the next ones, or a whole year.",
        props(
            "country" to str("Two-letter country code, e.g. 'DE'. Omit for the phone's country."),
            "year" to int("A year, to list all of it. Omit for the ones still to come.")
        ),
        emptyList()
    ),
    tool(
        "recipe",
        "A real recipe for a dish: ingredients and method.",
        props("dish" to str("The dish, in English, e.g. 'lasagne', 'pad thai'.")),
        listOf("dish")
    ),
    tool(
        "sports",
        "A sports team's last result and next fixture.",
        props("team" to str("The team, e.g. 'Arsenal', 'Bayern Munich', 'Lakers'.")),
        listOf("team")
    ),
    tool(
        "tv_show",
        "About a TV series: whether it is still running, where, and when the next episode airs.",
        props("name" to str("The show.")),
        listOf("name")
    ),
    tool(
        "book",
        "Look a book up: author, year, length, rating.",
        props("query" to str("Title and/or author.")),
        listOf("query")
    ),
    tool(
        "fun",
        "A real joke, an odd fact or a good quote, when the user asks for one.",
        props("kind" to str("Which.", listOf("joke", "fact", "quote"))),
        listOf("kind")
    ),
    tool(
        "generate_image",
        "Draw a picture from a description and show it in the chat. Use for 'draw', " +
            "'imagine', 'make me a picture/wallpaper/logo of'.",
        props(
            "prompt" to str("A vivid, specific description in English: subject, style, lighting, mood."),
            "shape" to str("The picture's shape.", listOf("square", "portrait", "landscape"))
        ),
        listOf("prompt")
    )
)

internal suspend fun Tools.webSearch(args: JSONObject): String {
    val query = args.optString("query").trim()
    if (query.isBlank()) return "Need something to search for."
    val limit = args.optInt("limit", 5).coerceIn(1, 8)

    // Both at once: they are separate services, and a search is the slowest
    // thing most turns wait on.
    val (direct, results) = kotlinx.coroutines.coroutineScope {
        val answer = async { runCatching { web.instantAnswer(query) }.getOrNull() }
        val found = async { runCatching { web.search(query, limit) }.getOrDefault(emptyList()) }
        answer.await() to found.await()
    }
    if (direct == null && results.isEmpty()) {
        return "No results for '$query'. The search endpoint may be rate limiting."
    }
    return buildString {
        direct?.let { appendLine("Direct answer: $it").appendLine() }
        results.forEachIndexed { index, r ->
            appendLine("${index + 1}. ${r.title}")
            appendLine("   ${r.url}")
            if (r.snippet.isNotBlank()) appendLine("   ${r.snippet}")
        }
    }.trim()
}
