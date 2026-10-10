package com.lukas.jarvis.notify

/** What came in while it was quiet, as one notification's title and lines. */
data class Digest(val title: String, val lines: List<String>, val people: Int)

/**
 * The digest after a quiet time Mochi set ("no calls for an hour", a focus
 * session): who wrote while it was quiet, newest first, from the messages the
 * reply listener already holds in memory. Nothing is read that was not already
 * in the notification shade, and nothing is kept.
 */
object QuietDigest {

    /** The most messages listed; the rest are counted. */
    const val MOST = 5

    /** Null when nobody wrote, so a quiet time with nothing in it ends quietly too. */
    fun of(waiting: List<Answerable>, since: Long): Digest? {
        val during = waiting.filter { it.postedAt >= since }.sortedByDescending { it.postedAt }
        if (during.isEmpty()) return null
        val people = during.map { it.from.ifBlank { it.appLabel } }.distinct()
        val title = when (people.size) {
            1 -> "${people[0]} wrote while it was quiet"
            2 -> "${people[0]} and ${people[1]} wrote while it was quiet"
            else -> "${people.size} people wrote while it was quiet"
        }
        val lines = during.take(MOST).map { m ->
            val who = m.from.ifBlank { m.appLabel }
            val text = m.text.trim().replace(Regex("\\s+"), " ")
            "$who on ${m.appLabel}: " + if (text.length > 80) text.take(79).trimEnd() + "…" else text
        } + listOfNotNull((during.size - MOST).takeIf { it > 0 }?.let { "and $it more" })
        return Digest(title, lines, people.size)
    }
}
