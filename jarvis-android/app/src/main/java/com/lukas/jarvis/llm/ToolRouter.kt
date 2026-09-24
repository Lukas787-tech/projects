package com.lukas.jarvis.llm

import com.lukas.jarvis.data.ChatMessage
import org.json.JSONObject
import java.util.Locale

/**
 * Chooses which tools a turn gets to see.
 *
 * Handing a free-tier model all seventy tool definitions on every turn costs
 * thousands of tokens a request — tokens per minute are metered as strictly as
 * requests — and a small model offered seventy tools reaches for the wrong one
 * noticeably more often than one offered fifteen. So each turn is offered the
 * families its words point at, plus a core that is always there.
 *
 * Missing a family is cheap to recover from, which is why this can afford to
 * be simple: the agent still runs any switched-on tool a model asks for by
 * name, and a follow-up turn inherits the families the previous one used, so
 * "call her" after a conversation about Anna, or "yes" after "shall I ring
 * her?", still has the calling tools in hand.
 *
 * The cues cover English and German, the two languages this app is spoken to
 * in most; anything else falls back to the core plus the web.
 */
object ToolRouter {

    /** Always offered: memory and exact answers are the assistant's backbone. */
    private val CORE = setOf(ToolGroup.Memory, ToolGroup.Thinking, ToolGroup.Screen)

    private val CUES: Map<ToolGroup, List<String>> = mapOf(
        ToolGroup.Money to listOf(
            "spent", "spend", "bought", "buy", "paid", "pay ", "cost", "price", "euro", "eur ",
            "dollar", "usd", "pound", "€", "$", "£", "budget", "balance", "money", "left this",
            "tracker", "track", "log ", "calorie", "kcal", "km ", "kilomet", "income", "salary",
            "earned", "expense", "receipt", "afford", "undo", "owe", "cash", "wallet", "card",
            "ausgegeben", "gekauft", "bezahlt", "kostet", "kosten", "geld", "übrig", "kontostand",
            "kalorien", "verdient", "gehalt", "rechnung", "quittung", "rückgängig"
        ),
        ToolGroup.Tasks to listOf(
            "remind", "reminder", "task", "todo", "to-do", "to do", "tomorrow", "tonight",
            "later", "deadline", "due", "o'clock", "snooze", "don't forget", "dont forget",
            "next week", "on monday", "on tuesday", "on wednesday", "on thursday", "on friday",
            "on saturday", "on sunday", " at ", "i did it", "done with", "finished", "open",
            "erinner", "aufgabe", "morgen", "später", "heute abend", "nicht vergessen",
            "erledigt", "verschieb", "nächste woche", " um ", "list", "shopping", "groceries",
            "packing", "i got the", "einkauf", "liste", "pack"
        ),
        ToolGroup.Web to listOf(
            "search", "google", "look up", "lookup", "latest", "who is", "who was", "what is",
            "what's", "when did", "when is", "current", "price of", "score", "won ", "happened",
            "wikipedia", "tell me about", "exchange", "rate", " in euro", " in dollar",
            "open the page", "website", "http", "www.", ".com", "how much is", "how old",
            "suche", "such ", "wer ist", "wer war", "was ist", "wann ", "aktuell", "wechselkurs",
            "erzähl mir", "wie alt", "wie viel kostet"
        ),
        ToolGroup.News to listOf(
            "news", "headline", "happening", "going on in", "today's", "nachrichten",
            "schlagzeile", "was gibt's neues", "was gibt es neues", "neuigkeiten"
        ),
        ToolGroup.Weather to listOf(
            "weather", "rain", "sunny", "sun ", "temperature", "forecast", "coat", "umbrella",
            "cold", "hot ", "warm", "wind", "snow", "storm", "degrees", "sunrise", "sunset",
            "air quality", "pollen", "wetter", "regen", "temperatur", "sonne", "kalt", "schnee",
            "jacke", "schirm", "grad", "gewitter", "sonnenaufgang", "sonnenuntergang", "uv",
            "sunscreen", "allerg", "hay fever", "smog", "luftqualität", "pollen", "sonnencreme",
            "heuschnupfen"
        ),
        ToolGroup.Places to listOf(
            "near", "nearby", "around here", "where", "restaurant", "food", "hungry", "eat",
            "coffee", "cafe", "café", "bar ", "pub", "shop", "store", "pharmacy", "supermarket",
            "atm", "station", "route", "directions", "way to", "get to", "navigate", "map",
            "walk", "drive", "parked", "park ", "my car", "take me", "home", "work", "location",
            "address", "how far", "hier", "in der nähe", "hunger", "essen", "kaffee", "apotheke",
            "weg ", "navigation", "navi", "karte", "geparkt", "wo ist", "wo bin", "adresse",
            "standort", "nach hause", "wie weit", "supermarkt", "tankstelle", "gas station"
        ),
        ToolGroup.Calendar to listOf(
            "calendar", "meeting", "appointment", "event", "schedule", "agenda", "busy",
            "free on", "free at", "kalender", "termin", "besprechung", "treffen", "verabredung"
        ),
        ToolGroup.People to listOf(
            "number", "contact", "phone number", "nummer", "kontakt", "telefonnummer"
        ),
        ToolGroup.Phone to listOf(
            "alarm", "wake me", "timer", "torch", "flashlight", "light", "battery", "silent",
            "ringer", "vibrat", "open ", "launch", "app", "settings", "wifi", "wi-fi",
            "clipboard", "copy", "storage", "brightness", "airplane", "wecker", "weck mich",
            "taschenlampe", "licht", "akku", "lautlos", "öffne", "starte", "einstellung",
            "kopier", "speicher", "flugmodus", "helligkeit", "volume", "louder", "quieter",
            "turn it up", "turn it down", "mute", "disturb", "dnd", "focus", "dimmer",
            "brighter", "screen", "lauter", "leiser", "stumm", "nicht stören", "bildschirm",
            "how long", "left on", "more minutes", "noch übrig", "wie lange",
            "dunkler", "heller", "lautstärke"
        ),
        ToolGroup.Messages to listOf(
            "text ", "message", "sms", "whatsapp", "telegram", "signal", "send", "reply",
            "call ", "ring ", "dial", "email", "e-mail", "mail", "share", "tell ", "let ",
            "know that", "inbox", "came in", "unread", "nachricht", "schreib", "schick",
            "sende", "antwort", "anruf", "ruf ", "teilen", "sag ", "posteingang"
        ),
        ToolGroup.Media to listOf(
            "play", "music", "song", "pause", "skip", "next track", "previous", "volume",
            "spotify", "podcast", "radio", "bluetooth", "headphone", "speaker", "louder",
            "quieter", "spiel", "musik", "lied", "weiter", "lauter", "leiser", "kopfhörer",
            "playing", "what song", "which song", "track", "läuft"
        ),
        ToolGroup.Vision to listOf(
            "photo", "picture", "camera", "look at", "see this", "scan", "read this",
            "what is this", "what's this", "receipt", "identify", "this sign", "menu",
            "foto", "bild", "kamera", "schau", "scann", "was ist das", "lies das", "erkenn",
            "screen", "this page", "this article", "summarise this", "summarize this",
            "what does it say", "what does this say", "reply to this", "bildschirm", "diese seite",
            "diesen artikel", "fasse das zusammen", "fass das zusammen"
        ),
        ToolGroup.Automation to listOf(
            "routine", "every morning", "every evening", "every day", "every night",
            "morgenroutine", "jeden morgen", "jeden abend", "jeden tag"
        ),
        ToolGroup.Language to listOf(
            "translate", "translation", "in german", "in english", "in spanish", "in french",
            "in italian", "how do you say", "what does", "mean", "definition", "define",
            "synonym", "spell", "übersetz", "auf deutsch", "auf englisch", "was heißt",
            "was bedeutet", "bedeutung"
        ),
        ToolGroup.Create to listOf(
            "draw", "imagine", "picture of", "image of", "generate", "create an image",
            "make me a picture", "illustrat", "wallpaper", "logo", "zeichne", "mal mir",
            "bild von", "erstelle ein bild", "generier"
        ),
        ToolGroup.Markets to listOf(
            "stock", "share price", "shares", "crypto", "bitcoin", "ethereum", "btc", "eth",
            "market", "nasdaq", "dax", "s&p", "dow", "coin", "aktie", "börse", "kurs"
        ),
        ToolGroup.Knowledge to listOf(
            "holiday", "bank holiday", "recipe", "cook", "how to make", "tv show", "series",
            "episode", "book", "author", "team", "match", "game ", "football", "soccer",
            "basketball", "league", "feiertag", "rezept", "kochen", "serie", "buch", "autor",
            "spiel ", "fußball", "mannschaft", "verein"
        ),
        ToolGroup.Home to listOf(
            "light", "lamp", "heating", "thermostat", "radiator", "degrees", "door", "lock",
            "blind", "shutter", "curtain", "plug", "socket", "fan", "scene", "turn on", "turn off",
            "switch on", "switch off", "dim", "brighter", "house", "home", "living room", "bedroom",
            "kitchen", "bathroom", "garage", "garden", "licht", "lampe", "heizung", "thermostat",
            "tür", "schloss", "rollo", "jalousie", "steckdose", "schalte", "mach das", "dimm",
            "wohnzimmer", "schlafzimmer", "küche", "bad ", "flur"
        ),
        ToolGroup.Fun to listOf(
            "joke", "fun fact", "random fact", "quote", "inspire", "motivat", "bored",
            "flip a coin", "coin", "dice", "roll", "random", "pick one", "choose", "witz",
            "zitat", "langweilig", "würfel", "münze", "zufall", "entscheide", "password",
            "passwort"
        )
    )

    /**
     * The tool schemas to offer this turn.
     *
     * [all] is every switched-on schema; the result is a subset of it, never a
     * tool the user has switched off.
     */
    fun select(
        all: List<JSONObject>,
        utterance: String,
        history: List<ChatMessage>
    ): List<JSONObject> {
        val groups = groupsFor(utterance, history)
        val chosen = all.filter { schema ->
            val name = schema.optJSONObject("function")?.optString("name").orEmpty()
            val group = ToolCatalog.info(name)?.group
            group == null || group in groups
        }
        // A handful of tools is not worth narrowing; neither is a set that the
        // cues left almost whole.
        return if (chosen.size >= all.size - 2) all else chosen
    }

    fun groupsFor(utterance: String, history: List<ChatMessage>): Set<ToolGroup> {
        val text = " " + utterance.lowercase(Locale.ROOT) + " "
        val groups = CORE.toMutableSet()

        CUES.forEach { (group, cues) ->
            if (cues.any { text.contains(it) }) groups += group
        }

        // A number and a unit of money is spending even without a verb.
        if (MONEY_AMOUNT.containsMatchIn(text)) groups += ToolGroup.Money
        // A clock time is almost always a reminder or an alarm.
        if (CLOCK_TIME.containsMatchIn(text)) {
            groups += ToolGroup.Tasks
            groups += ToolGroup.Phone
        }

        // Follow the thread: whatever the last two replies used is still in
        // play, so "yes", "the second one" and "call her" keep their tools.
        history.asReversed()
            .filter { it.role == ChatMessage.ROLE_ASSISTANT }
            .take(2)
            .flatMap { it.tools }
            .mapNotNull { ToolCatalog.info(it)?.group }
            .forEach { groups += it }

        // Nothing pointed anywhere in particular: a general question, most
        // likely, and the web is what answers those.
        if (groups == CORE) {
            groups += ToolGroup.Web
            groups += ToolGroup.Tasks
        }
        // Money is logged in passing, so it rides along with tasks and memory
        // whenever a number appears at all.
        if (text.any { it.isDigit() }) groups += ToolGroup.Money
        return groups
    }

    private val MONEY_AMOUNT = Regex(
        "\\d+([.,]\\d+)?\\s*(€|\\$|£|eur|euro|euros|dollars?|bucks|pounds?|chf|franken)|(€|\\$|£)\\s*\\d"
    )
    private val CLOCK_TIME = Regex("\\b\\d{1,2}([:.]\\d{2})?\\s*(am|pm|uhr|h\\b)|\\b\\d{1,2}:\\d{2}\\b")
}
