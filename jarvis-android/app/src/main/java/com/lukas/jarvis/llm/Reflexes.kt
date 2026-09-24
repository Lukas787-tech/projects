package com.lukas.jarvis.llm

import org.json.JSONObject
import java.util.Locale

/**
 * What Jarvis can still do with no model at all.
 *
 * No signal, or every free quota spent for the hour: the language model is out
 * of reach, but the clock, the calculator, the torch, the volume, Do Not
 * Disturb, timers, alarms and quick reminders never needed one. These are the sentences that mean one of those
 * plainly enough to be read without a model — in English and German — and each
 * becomes the same tool call a model would have made, so the work is done by
 * the same code either way.
 */
object Reflexes {

    fun parse(raw: String): ToolCall? {
        val text = raw.trim().lowercase(Locale.ROOT).trimEnd('?', '.', '!')
        if (text.isBlank()) return null

        timer(text)?.let { return it }
        remind(text)?.let { return it }
        alarm(text)?.let { return it }
        torch(text)?.let { return it }
        quiet(text)?.let { return it }
        volume(text)?.let { return it }
        if (TIME.containsMatchIn(text) || DATE.containsMatchIn(text)) return call("now", JSONObject())
        sum(text)?.let { return it }
        return null
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
    private val DURATION = Regex("(\\d+(?:[.,]\\d+)?)\\s*(seconds?|secs?|sekunden?|minutes?|mins?|minuten?|hours?|hrs?|stunden?)\\b")
    private val REMIND = Regex(
        "(?:remind me|erinnere mich)\\s+(?:in|in)\\s+(\\d+(?:[.,]\\d+)?)\\s*(seconds?|minutes?|mins?|hours?|minuten?|stunden?|sekunden?)\\s+(?:to|zu|an|dass|that)?\\s*(.+)"
    )
    private val CLOCK = Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|uhr)?\\b")
    private val SUM = Regex("^[\\d\\s+\\-*/^().,%]+$")
    private val WORDS = listOf(
        " plus " to " + ", " minus " to " - ", " times " to " * ", " multiplied by " to " * ",
        " divided by " to " / ", " over " to " / ", " mal " to " * ", " geteilt durch " to " / ",
        " durch " to " / ", " hoch " to " ^ ", " to the power of " to " ^ "
    )
}
