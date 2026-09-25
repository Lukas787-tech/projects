package com.lukas.jarvis.llm

import org.json.JSONObject
import java.util.Locale

/**
 * What Jarvis can still do with no model at all.
 *
 * No signal, or every free quota spent for the hour: the language model is out
 * of reach, but the clock, the calculator, the torch, the volume, Do Not
 * Disturb, the music, timers, alarms, quick reminders, a coin or a die, the
 * battery and opening an app never needed one. These are the sentences that mean one of those
 * plainly enough to be read without a model — in English and German — and each
 * becomes the same tool call a model would have made, so the work is done by
 * the same code either way.
 */
object Reflexes {

    fun parse(raw: String): ToolCall? {
        val text = spokenDurations(raw.trim().lowercase(Locale.ROOT).trimEnd('?', '.', '!'))
        if (text.isBlank()) return null

        remember(raw)?.let { return it }
        com.lukas.jarvis.control.SystemAction.heard(text)?.let { action ->
            return call("system_action", JSONObject().put("action", action.id))
        }
        profile(text)?.let { return it }
        stopwatch(text)?.let { return it }
        timerQuestion(text)?.let { return it }
        sleepTimer(text)?.let { return it }
        focus(text)?.let { return it }
        timer(text)?.let { return it }
        remind(text)?.let { return it }
        remindAfter(text)?.let { return it }
        alarm(text)?.let { return it }
        torch(text)?.let { return it }
        list(text)?.let { return it }
        quiet(text)?.let { return it }
        volume(text)?.let { return it }
        playback(text)?.let { return it }
        chance(text)?.let { return it }
        if (BATTERY.containsMatchIn(text)) return call("device_status", JSONObject().put("what", "battery"))
        openApp(text)?.let { return it }
        if (TIME.containsMatchIn(text) || DATE.containsMatchIn(text)) return call("now", JSONObject())
        sum(text)?.let { return it }
        return null
    }

    /**
     * "Remember that my locker code is 3917": kept word for word, since with no
     * model there is nobody to rewrite it, and the words are the user's own.
     * "Remember to…" is a task and "remember when…?" a question, so neither is
     * taken as something to store.
     */
    private fun remember(raw: String): ToolCall? {
        val trimmed = raw.trim()
        JOURNAL.find(trimmed)?.let { match ->
            val entry = match.groupValues[1].trim()
            if (entry.split(Regex("\\s+")).size < 2) return null
            return call(
                "remember",
                JSONObject()
                    .put("content", entry.replaceFirstChar { it.titlecase(Locale.ROOT) })
                    .put("kind", "journal")
            )
        }
        if (trimmed.endsWith("?")) return null
        val match = REMEMBER.find(trimmed) ?: return null
        val content = match.groupValues[1].trim().trimEnd('.', '!').trim()
        if (content.split(Regex("\\s+")).size < 3) return null
        if (Regex("^(to|when|zu|wann)\\b", RegexOption.IGNORE_CASE).containsMatchIn(content)) return null
        return call(
            "remember",
            JSONObject().put("content", content.replaceFirstChar { it.titlecase(Locale.ROOT) })
        )
    }

    /**
     * The user's own words said back to them: "my code is 3917" becomes "your
     * code is 3917". English only; a German memory reads fine either way.
     */
    fun secondPerson(text: String): String {
        val verbs = text
            .replace(Regex("\\bI am\\b"), "you are")
            .replace(Regex("\\bI was\\b"), "you were")
        val swapped = verbs.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { token ->
            val bare = token.trimEnd(',', '.', '!', '?', ';', ':')
            val tail = token.substring(bare.length)
            val replacement = PERSON[bare.lowercase(Locale.ROOT)] ?: return@joinToString token
            val cased = if (bare.first().isUpperCase() && bare != "I") {
                replacement.replaceFirstChar { it.titlecase(Locale.ROOT) }
            } else {
                replacement
            }
            cased + tail
        }
        return swapped.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    private fun timerQuestion(text: String): ToolCall? = when {
        TIMER_LEFT.containsMatchIn(text) -> call("timers", JSONObject().put("action", "list"))
        TIMER_STOP.matches(text) -> call("timers", JSONObject().put("action", "cancel"))
        else -> null
    }

    /** "Stop the music in 30 minutes", "sleep timer 20 minutes". */
    private fun sleepTimer(text: String): ToolCall? {
        if (!SLEEP.containsMatchIn(text)) return null
        val match = DURATION.find(text) ?: return null
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val minutes = amount * unitMinutes(match.groupValues[2])
        if (minutes <= 0) return null
        return call("set_timer", JSONObject().put("minutes", minutes).put("stop_music", true))
    }

    /** "Focus for 25 minutes", "start a pomodoro", "Fokus 50 Minuten". */
    private fun focus(text: String): ToolCall? {
        if (!FOCUS.matches(text)) return null
        if (Regex("\\b(off|aus|stop|stopp|end|beenden|cancel)\\b").containsMatchIn(text)) {
            return call("focus_session", JSONObject().put("action", "stop"))
        }
        val args = JSONObject()
        DURATION.find(text)?.let { span ->
            val amount = span.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            val minutes = amount * unitMinutes(span.groupValues[2])
            if (minutes > 0) args.put("minutes", minutes)
        }
        return call("focus_session", args)
    }

    private fun timer(text: String): ToolCall? {
        if (!TIMER_WORDS.any { text.contains(it) }) return null
        val match = DURATION.find(text) ?: return null
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val minutes = amount * unitMinutes(match.groupValues[2])
        if (minutes <= 0) return null
        return call("set_timer", JSONObject().put("minutes", minutes))
    }

    private fun remind(text: String): ToolCall? {
        val match = REMIND.find(text) ?: return null
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val minutes = (amount * unitMinutes(match.groupValues[2])).toLong()
        val what = match.groupValues[3].trim().removePrefix("to ").removePrefix("zu ").trim()
        if (minutes <= 0 || what.isBlank()) return null
        return call(
            "add_task",
            JSONObject().put("title", what.replaceFirstChar { it.titlecase(Locale.ROOT) }).put("due", "+${minutes}m")
        )
    }

    /** "Switch to night mode", "Arbeitsprofil laden", "wechsle zum Nacht-Modus". */
    private fun profile(text: String): ToolCall? {
        val name = PROFILE_SWITCH.matchEntire(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim()
            ?: return null
        // The phone's own modes are not profiles.
        if (name.split(' ').size > 3 || DEVICE_MODES.containsMatchIn(name)) return null
        return call("profile", JSONObject().put("action", "apply").put("name", name))
    }

    /** "Start the stopwatch", "lap", "Stoppuhr anhalten". */
    private fun stopwatch(text: String): ToolCall? {
        val action = when {
            LAP.matches(text) -> "lap"
            !STOPWATCH.containsMatchIn(text) -> return null
            Regex("\\b(reset|clear|zurücksetzen|löschen|nullen)\\b").containsMatchIn(text) -> "reset"
            Regex("\\b(lap|split|runde|zwischenzeit)\\b").containsMatchIn(text) -> "lap"
            Regex("\\b(stop|pause|halt|anhalten|stoppen|stopp)\\b").containsMatchIn(text) -> "pause"
            Regex("\\b(start|begin|resume|go|starten|starte|weiter|los)\\b").containsMatchIn(text) -> "start"
            else -> "status"
        }
        return call("stopwatch", JSONObject().put("action", action))
    }

    /** "Remind me to call mum in 10 minutes": the time said last. */
    private fun remindAfter(text: String): ToolCall? {
        val match = REMIND_AFTER.find(text) ?: return null
        val what = match.groupValues[1].trim()
        val amount = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        val minutes = (amount * unitMinutes(match.groupValues[3])).toLong()
        if (minutes <= 0 || what.isBlank()) return null
        return call(
            "add_task",
            JSONObject().put("title", what.replaceFirstChar { it.titlecase(Locale.ROOT) }).put("due", "+${minutes}m")
        )
    }

    /**
     * Lengths of time said in words, as speech recognition often leaves them:
     * "half an hour" -> "30 minutes", "five minutes" -> "5 minutes",
     * "eine halbe Stunde" -> "30 minuten", "a minute" -> "1 minute".
     * Only a number word right before a unit is touched.
     */
    fun spokenDurations(text: String): String {
        var out = text
        HALVES.forEach { (phrase, replacement) -> out = out.replace(phrase, replacement) }
        return DURATION_WORD.replace(out) { m ->
            val value = NUMBER_WORDS[m.groupValues[1]] ?: return@replace m.value
            val unit = m.groupValues[2]
            (if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()) + " " + unit
        }
    }

    private fun alarm(text: String): ToolCall? {
        if (!ALARM_WORDS.any { text.contains(it) }) return null
        // "Wake me in eight hours" is a length of time, not eight o'clock.
        if (Regex("\\b(in|nach)\\s+\\d").containsMatchIn(text)) {
            val span = DURATION.find(text) ?: return null
            val amount = span.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val minutes = (amount * unitMinutes(span.groupValues[2])).toLong()
            return if (minutes > 0) call("set_alarm", JSONObject().put("time", "+${minutes}m")) else null
        }
        val match = CLOCK.find(text) ?: return null
        var hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifBlank { "0" }.toInt()
        val half = match.groupValues[3]
        if (half == "pm" && hour < 12) hour += 12
        if (half == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return call("set_alarm", JSONObject().put("time", String.format(Locale.US, "%02d:%02d", hour, minute)))
    }

    private fun torch(text: String): ToolCall? {
        if (!TORCH_WORDS.any { text.contains(it) }) return null
        val off = Regex("\\b(off|aus|ausschalten)\\b").containsMatchIn(text)
        return call("torch", JSONObject().put("on", !off))
    }

    private fun quiet(text: String): ToolCall? {
        if (!QUIET_WORDS.any { text.contains(it) }) return null
        if (Regex("\\b(off|aus|ausschalten|beenden|end|stop)\\b").containsMatchIn(text)) {
            return call("do_not_disturb", JSONObject().put("mode", "off"))
        }
        val args = JSONObject().put("mode", "priority")
        DURATION.find(text)?.let { span ->
            val amount = span.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            val minutes = (amount * unitMinutes(span.groupValues[2])).toInt()
            if (minutes > 0) args.put("minutes", minutes)
        }
        return call("do_not_disturb", args)
    }

    private fun volume(text: String): ToolCall? {
        val args = JSONObject()
        val level = Regex("\\b(?:volume|lautstärke)\\b.*?\\b(\\d{1,3})\\s*(?:%|percent|prozent)?").find(text)
        when {
            level != null -> args.put("action", "set").put("level", level.groupValues[1].toInt().coerceIn(0, 100))
            UP.any { text.contains(it) } -> args.put("action", "up")
            DOWN.any { text.contains(it) } -> args.put("action", "down")
            MUTE.matches(text) -> args.put("action", "mute")
            else -> return null
        }
        return call("volume", args)
    }

    private fun list(text: String): ToolCall? {
        LIST_ADD.matchEntire(text)?.let { m ->
            val items = m.groupValues[1].trim()
            val name = m.groupValues[2].trim()
            if (items.isBlank() || name.isBlank()) return null
            return call("list", JSONObject().put("action", "add").put("list", name).put("items", org.json.JSONArray().put(items)))
        }
        LIST_ADD_DE.matchEntire(text)?.let { m ->
            val items = m.groupValues[1].trim()
            val name = m.groupValues[2].trim().ifBlank { "shopping" }
            if (items.isBlank()) return null
            return call("list", JSONObject().put("action", "add").put("list", name).put("items", org.json.JSONArray().put(items)))
        }
        LIST_SHOW.matchEntire(text)?.let { m ->
            val name = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim() ?: return null
            return call("list", JSONObject().put("action", "show").put("list", name))
        }
        return null
    }

    private fun playback(text: String): ToolCall? {
        val action = when {
            PAUSE.matches(text) -> "pause"
            RESUME.matches(text) -> "resume"
            NEXT.matches(text) -> "next"
            PREVIOUS.matches(text) -> "previous"
            else -> return null
        }
        return call("control_playback", JSONObject().put("action", action))
    }

    private fun chance(text: String): ToolCall? = when {
        COIN.containsMatchIn(text) -> call("random", JSONObject().put("kind", "coin"))
        DICE.containsMatchIn(text) -> call("random", JSONObject().put("kind", "dice"))
        else -> null
    }

    private fun openApp(text: String): ToolCall? {
        val match = OPEN.matchEntire(text) ?: return null
        val name = match.groupValues[2].trim().removeSuffix(" app").removePrefix("the ").removePrefix("die ")
            .removePrefix("den ").removePrefix("das ").trim()
        // Doors, windows and blinds are opened by the house, not the launcher,
        // and that needs the model to know which one.
        if (name.isBlank() || NOT_APPS.containsMatchIn(name) || name.split(' ').size > 3) return null
        return call("open_app", JSONObject().put("name", name))
    }

    private fun sum(text: String): ToolCall? {
        var expression = text
            .replace(Regex("^(what('s| is)|how much is|calculate|compute|was ist|was sind|wie viel ist|rechne|berechne)\\s+"), "")
        WORDS.forEach { (word, symbol) -> expression = expression.replace(word, symbol) }
        expression = expression.replace('×', '*').replace('÷', '/').replace(" x ", " * ").trim()
        if (!SUM.matches(expression)) return null
        if (!expression.any { it in "+-*/^%" }) return null
        if (!expression.any { it.isDigit() }) return null
        return call("calculate", JSONObject().put("expression", expression))
    }

    // Hours first: German "Stunden" also begins with an s.
    private fun unitMinutes(unit: String): Double = when {
        unit.startsWith("h") || unit.startsWith("stund") -> 60.0
        unit.startsWith("sec") || unit.startsWith("sek") -> 1.0 / 60.0
        else -> 1.0
    }

    private fun call(name: String, args: JSONObject) =
        ToolCall(id = "reflex_$name", name = name, argumentsJson = args.toString())

    private val TIME = Regex("\\b(what time is it|what's the time|what is the time|wie spät|uhrzeit|wie viel uhr)")
    private val DATE = Regex("\\b(what's the date|what is the date|what day is it|today's date|welches datum|welcher tag ist)")
    private val TIMER_WORDS = listOf("timer", "countdown", "wecker für", "stoppuhr")
    private val ALARM_WORDS = listOf("alarm", "wake me", "wecker", "weck mich")
    private val TORCH_WORDS = listOf("torch", "flashlight", "taschenlampe")
    private val QUIET_WORDS = listOf("do not disturb", "don't disturb", "dnd", "nicht stören", "bitte nicht stören")
    private val UP = listOf("volume up", "turn it up", "turn the volume up", "louder", "lauter")
    private val DOWN = listOf("volume down", "turn it down", "turn the volume down", "quieter", "leiser")
    private val MUTE = Regex("^(mute|stumm|mute (the )?(sound|music|media|volume|phone)|(schalte? )?(den ton|die musik) (stumm|aus))$")
    private val LIST_ADD = Regex("^(?:please )?(?:add|put)\\s+(.+?)\\s+(?:to|on|onto)\\s+(?:my |the |our )?(.+?)\\s*list$")
    private val LIST_ADD_DE = Regex("^(?:setz(?:e)?|schreib(?:e)?|pack(?:e)?)?\\s*(.+?)\\s+auf\\s+(?:die |meine |unsere )?(.*?)liste$")
    private val LIST_SHOW = Regex("^what'?s on (?:my |the )?(.+?) list$|^(?:show|read)(?: me)? (?:my |the )?(.+?) list$|^was steht auf (?:der |meiner )?(.+?)liste$")
    private val TIMER_LEFT = Regex("how (much time|long) (is )?(left|remaining)|time left on|wie lange (noch|läuft)|wie viel zeit (ist )?noch")
    private val TIMER_STOP = Regex("^(cancel|stop|end|kill) (the |my |all )?(\\w+ )?timers?$|^timer (stoppen|abbrechen|aus)$")
    private val PAUSE = Regex("^(pause|stop)( (the )?(music|song|playback|it))?( please)?$|^(musik )?(pausieren|pause|stopp)( die musik)?$")
    private val RESUME = Regex("^(resume|play|continue|unpause)( (the )?(music|song|playback|it))?( please)?$|^(musik )?(weiter|fortsetzen|weiterspielen)$")
    private val NEXT = Regex("^(next|skip)( (the )?(song|track|one|this))?( please)?$|^(nächstes lied|nächster song|überspringen)$")
    private val PREVIOUS = Regex("^(previous|last|go back to the previous)( (song|track|one))?$|^(vorheriges lied|zurück)$")
    private val COIN = Regex("\\b(flip|toss) a coin\\b|\\bheads or tails\\b|\\bmünze werfen\\b|\\bwirf eine münze\\b|\\bkopf oder zahl\\b")
    private val DICE = Regex("\\broll (a|the|some) (die|dice)\\b|\\bwürfel(n|e)?\\b")
    private val REMEMBER = Regex(
        "^(?:hey jarvis,?\\s+|jarvis,?\\s+)?(?:please\\s+)?(?:remember|note|merk dir|merke dir|notier dir)" +
            "(?:\\s+that|,?\\s+dass)?[,:]?\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    /** The whole sentence is the command: "focus on the road" is not one. */
    private val FOCUS = Regex(
        "^(start (a )?|(end|stop) (the )?)?(focus( session| mode| time)?|pomodoro|fokus( ?zeit| ?modus)?)" +
            "( (on|an|off|aus|stop|beenden))?( (for|für) )?( ?\\d+([.,]\\d+)? ?\\p{L}+)?$"
    )
    private val SLEEP = Regex(
        "sleep timer|schlaftimer|(stop|pause|turn off) (the )?(music|playback|audio)( playing)? (in|after)|" +
            "musik (aus|stoppen|anhalten) (in|nach)"
    )
    private val JOURNAL = Regex(
        "^(?:journal|dear diary|diary|for my journal|tagebuch|liebes tagebuch)[,:.]?\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val PERSON = mapOf(
        "my" to "your", "i" to "you", "i'm" to "you're", "me" to "you", "mine" to "yours",
        "myself" to "yourself", "i've" to "you've", "i'll" to "you'll", "i'd" to "you'd"
    )
    private val BATTERY = Regex("^(how much battery|battery( level| status)?|what'?s my battery|how'?s (my|the) battery|wie viel akku|akku(stand)?)( do i have| left| is left)?$")
    private val OPEN = Regex("^(open|launch|start|öffne|starte)\\s+(.+?)(\\s+app)?$")
    private val NOT_APPS = Regex("\\b(door|window|garage|blind|blinds|shutter|gate|tür|fenster|tor|rollo|curtain|timer|alarm|a |an )")
    private val DURATION = Regex("(\\d+(?:[.,]\\d+)?)\\s*(seconds?|secs?|sekunden?|minutes?|mins?|minuten?|hours?|hrs?|stunden?)\\b")
    private val REMIND = Regex(
        "(?:remind me|erinnere mich)\\s+(?:in|in)\\s+(\\d+(?:[.,]\\d+)?)\\s*(seconds?|minutes?|mins?|hours?|minuten?|stunden?|sekunden?)\\s+(?:to|zu|an|dass|that)?\\s*(.+)"
    )
    private val STOPWATCH = Regex("stop ?watch|stoppuhr")
    private val LAP = Regex("^(lap|split|runde|zwischenzeit)( please| bitte)?$")
    private val PROFILE_SWITCH = Regex(
        "^(?:switch|change|go) (?:to|into) (?:the |my )?(.+?) (?:mode|profile)$|" +
            "^(?:use|load|apply) (?:the |my )?(.+?) profile$|" +
            "^(?:wechsle|wechsel|schalte?) (?:zu[mr]?|auf|in) (?:den |das |die )?(.+?)(?:-| )?(?:modus|profil)$|" +
            "^(?:lade|aktiviere) (?:den |das |mein |meinen )?(.+?)(?:-| )?(?:modus|profil)$"
    )
    private val DEVICE_MODES = Regex(
        "^(airplane|aeroplane|flight|flug|silent|lautlos|stumm|vibrat|dark|light|hell|dunkel|power|battery|energie|" +
            "strom|do not disturb|nicht stören|driving|auto|guest|gast|landscape|portrait|quer|hoch|reading|lese|" +
            "one-handed|kids|kinder|incognito|inkognito|private|privat|safe|sicher|dnd|focus|fokus)"
    )
    private val REMIND_AFTER = Regex(
        "(?:remind me|erinnere mich)\\s+(?:to|zu|an|dass|that)\\s+(.+?)\\s+(?:in|nach)\\s+(\\d+(?:[.,]\\d+)?)\\s*" +
            "(seconds?|minutes?|mins?|hours?|minuten?|stunden?|sekunden?)$"
    )
    private val HALVES = listOf(
        "an hour and a half" to "90 minutes", "one and a half hours" to "90 minutes",
        "half an hour" to "30 minutes", "half a minute" to "30 seconds", "a quarter of an hour" to "15 minutes",
        "quarter of an hour" to "15 minutes", "quarter hour" to "15 minutes",
        "eineinhalb stunden" to "90 minuten", "anderthalb stunden" to "90 minuten",
        "einer halben stunde" to "30 minuten", "eine halbe stunde" to "30 minuten", "halbe stunde" to "30 minuten",
        "einer viertelstunde" to "15 minuten", "eine viertelstunde" to "15 minuten", "viertelstunde" to "15 minuten",
        "einer dreiviertelstunde" to "45 minuten", "eine dreiviertelstunde" to "45 minuten"
    )
    private val NUMBER_WORDS: Map<String, Double> = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
        "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0,
        "twelve" to 12.0, "fifteen" to 15.0, "twenty" to 20.0, "thirty" to 30.0, "forty" to 40.0,
        "forty-five" to 45.0, "fifty" to 50.0, "sixty" to 60.0, "ninety" to 90.0, "a couple of" to 2.0, "a few" to 3.0,
        "eine" to 1.0, "einer" to 1.0, "einen" to 1.0, "ein" to 1.0, "eins" to 1.0, "zwei" to 2.0, "drei" to 3.0,
        "vier" to 4.0, "fünf" to 5.0, "sechs" to 6.0, "sieben" to 7.0, "acht" to 8.0, "neun" to 9.0, "zehn" to 10.0,
        "elf" to 11.0, "zwölf" to 12.0, "fünfzehn" to 15.0, "zwanzig" to 20.0, "dreißig" to 30.0, "vierzig" to 40.0,
        "fünfundvierzig" to 45.0, "fünfzig" to 50.0, "sechzig" to 60.0, "neunzig" to 90.0, "ein paar" to 3.0
    )
    private val DURATION_WORD = Regex(
        "(?<![\\p{L}\\d-])(" + NUMBER_WORDS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")" +
            "\\s+(seconds?|secs?|sekunden?|minutes?|mins?|minuten?|hours?|hrs?|stunden?)\\b"
    )
    private val CLOCK = Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|uhr)?\\b")
    private val SUM = Regex("^[\\d\\s+\\-*/^().,%]+$")
    private val WORDS = listOf(
        " plus " to " + ", " minus " to " - ", " times " to " * ", " multiplied by " to " * ",
        " divided by " to " / ", " over " to " / ", " mal " to " * ", " geteilt durch " to " / ",
        " durch " to " / ", " hoch " to " ^ ", " to the power of " to " ^ "
    )
}
