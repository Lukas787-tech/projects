package com.lukas.jarvis.llm

import java.util.Locale

/** How much thinking a turn asks for. */
enum class Need { Quick, Normal, Deep }

/**
 * Model routing: a quick, small model for "set a timer", a stronger one for
 * "plan my Saturday". It only leans the pool's order, within the same tier, so
 * a key of your own still goes before the keyless models, health and quota
 * still count for more, and nothing is ever refused for being the "wrong" size.
 * With no connection at all, the reflexes answer on the phone as before.
 *
 * A model's size is read from its name, which is all a provider says about it
 * in common: "llama-3.3-70b", "mixtral-8x7b", "gemini-2.5-flash-lite".
 */
object Routing {

    enum class Size { Small, Medium, Large }

    private val DEEP = Regex(
        "\\b(plan|planning|explain|why|compare|versus|vs\\.?|pros and cons|write|draft|essay|story|poem|" +
            "summari[sz]e|summary|help me (decide|choose|think)|what should i|step by step|in detail|" +
            "erkläre|erklär|warum|vergleich|schreib|zusammenfass|plane|planen)\\b"
    )

    private val QUICK = Regex(
        "\\b(timer|alarm|remind|turn (on|off)|switch (on|off)|volume|louder|quieter|torch|flashlight|" +
            "play|pause|skip|next song|stop|call|text|add .+ to|what time|weather|i spent|i bought|log|" +
            "open|lights?|wecker|erinner|lauter|leiser|taschenlampe|spiel|anruf|wetter)\\b"
    )

    /** How much thinking [utterance] asks for, given the tools this turn offers. */
    fun need(utterance: String, offered: Set<String> = emptySet()): Need {
        val text = utterance.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return Need.Normal
        if (text.length > 280 || DEEP.containsMatchIn(text) || ("make_plan" in offered && text.contains("plan"))) return Need.Deep
        val words = text.split(Regex("\\s+")).size
        if (words <= 10 && QUICK.containsMatchIn(text)) return Need.Quick
        return Need.Normal
    }

    /** A model's size, from its name. Unknown names are taken as medium, never guessed small. */
    fun size(model: String): Size {
        val name = model.lowercase(Locale.ROOT)
        Regex("(\\d+)x(\\d+(?:\\.\\d+)?)b\\b").find(name)?.let { m ->
            return byBillions(m.groupValues[1].toDouble() * m.groupValues[2].toDouble())
        }
        Regex("(?<![a-z\\d.])(\\d+(?:\\.\\d+)?)b\\b").find(name)?.let { m -> return byBillions(m.groupValues[1].toDouble()) }
        // Whole parts of the name only: "gemini" is not "mini".
        val parts = name.split(Regex("[^a-z0-9]+")).toSet()
        return when {
            parts.any { it in SMALL } -> Size.Small
            parts.any { it in LARGE } || Regex("deepseek-(r1|v3|chat)").containsMatchIn(name) -> Size.Large
            else -> Size.Medium
        }
    }

    private val SMALL = setOf("mini", "nano", "tiny", "small", "lite", "instant", "haiku")
    private val LARGE = setOf("pro", "large", "opus", "ultra", "maverick", "max", "sonnet", "kimi")

    private fun byBillions(b: Double): Size = when {
        b < 15 -> Size.Small
        b >= 60 -> Size.Large
        else -> Size.Medium
    }

    /**
     * How much a model of [size] is leaned towards (negative) or away (positive)
     * for [need], in the pool's own score units. Smaller than one tier (10), so it
     * only ever reorders within one.
     */
    fun bias(size: Size, need: Need): Double = when (need) {
        Need.Deep -> when (size) {
            Size.Small -> 3.0
            Size.Medium -> 0.0
            Size.Large -> -2.0
        }
        Need.Quick -> when (size) {
            Size.Small -> -1.0
            Size.Medium -> 0.0
            Size.Large -> 1.5
        }
        Need.Normal -> 0.0
    }
}
