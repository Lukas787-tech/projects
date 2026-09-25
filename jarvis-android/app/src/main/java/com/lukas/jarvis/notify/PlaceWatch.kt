package com.lukas.jarvis.notify

import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.maps.GeoPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Remind me to buy milk when I get home": a reminder tied to a place rather
 * than a time.
 *
 * The phone reports crossings of a circle, in and out, and nothing else. That
 * is not quite what the sentence means: said at home, "when I get home" is
 * about the next arrival, not the one the phone notices a second after the
 * circle is drawn. So an arrival starts [armed] only when the phone was
 * outside at the time, and otherwise arms itself on the way out.
 */
data class PlaceWatch(
    val id: Long,
    val text: String,
    val place: String,
    val point: GeoPoint,
    val radius: Int = DEFAULT_RADIUS,
    /** Fires on the way out instead of on the way in. */
    val leaving: Boolean = false,
    /** Stays after firing, for "every time I get to work". */
    val every: Boolean = false,
    /** An arrival that may fire: the phone has been outside since it was set. */
    val armed: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * A routine to run on the spot instead of only a note: "when I get home,
     * run my evening routine". It runs in the background, like a quiet one,
     * and its answer arrives as the notification.
     */
    val routine: String? = null
) {

    enum class Crossing { Fire, Arm, Ignore }

    /** What one crossing of the circle means for this reminder. */
    fun onCrossing(entering: Boolean): Crossing = when {
        leaving -> if (entering) Crossing.Ignore else Crossing.Fire
        !entering -> if (armed) Crossing.Ignore else Crossing.Arm
        armed -> Crossing.Fire
        else -> Crossing.Ignore
    }

    /** The reminder as it stands after it fired, or null when it is used up. */
    fun afterFiring(): PlaceWatch? = if (every) copy(armed = leaving) else null

    /** "when you get home", "when you leave work", "when you get back here". */
    val trigger: String
        get() = when {
            leaving -> "when you leave $place"
            place == "home" -> "when you get home"
            place == "here" -> "when you get back here"
            else -> "when you get to $place"
        }

    fun describe(): String = "$text, $trigger" + if (every) " (every time)" else ""

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("place", place)
        .put("lat", point.lat)
        .put("lon", point.lon)
        .put("radius", radius)
        .put("leaving", leaving)
        .put("every", every)
        .put("armed", armed)
        .put("at", createdAt)
        .apply { routine?.let { put("routine", it) } }

    companion object {
        /**
         * Big enough to be noticed by network location, which is all a phone
         * in a pocket gets most of the time, and small enough that "home" does
         * not also mean the bakery two streets away.
         */
        const val DEFAULT_RADIUS = 150

        /** A new reminder, armed or not by where the phone is now. */
        fun create(
            id: Long,
            text: String,
            place: String,
            point: GeoPoint,
            leaving: Boolean,
            every: Boolean,
            here: GeoPoint?,
            radius: Int = DEFAULT_RADIUS,
            routine: String? = null
        ): PlaceWatch {
            val inside = here != null && Geo.distance(here, point) <= radius
            return PlaceWatch(
                id = id,
                text = text.trim(),
                place = place.trim(),
                point = point,
                radius = radius,
                leaving = leaving,
                every = every,
                armed = leaving || !inside,
                routine = routine?.trim()?.takeIf { it.isNotBlank() }
            )
        }

        fun fromJson(obj: JSONObject): PlaceWatch? {
            val text = obj.optString("text").trim()
            if (text.isBlank() || !obj.has("lat") || !obj.has("lon")) return null
            return PlaceWatch(
                id = obj.optLong("id"),
                text = text,
                place = obj.optString("place").ifBlank { "there" },
                point = GeoPoint(obj.optDouble("lat"), obj.optDouble("lon")),
                radius = obj.optInt("radius", DEFAULT_RADIUS),
                leaving = obj.optBoolean("leaving", false),
                every = obj.optBoolean("every", false),
                armed = obj.optBoolean("armed", true),
                createdAt = obj.optLong("at", 0L),
                routine = obj.optString("routine").takeIf { it.isNotBlank() }
            )
        }

        fun listFromJson(raw: String?): List<PlaceWatch> = runCatching {
            val array = JSONArray(raw ?: "[]")
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::fromJson) }
        }.getOrDefault(emptyList())

        fun listToJson(all: List<PlaceWatch>): String =
            JSONArray().apply { all.forEach { put(it.toJson()) } }.toString()

        private val FILLER = setOf(
            "the", "one", "that", "this", "reminder", "remind", "cancel", "delete", "remove",
            "when", "get", "leave", "arrive", "for", "and", "about"
        )

        /**
         * Picks one reminder out of a spoken description: by its words, or by
         * its place, so "cancel the milk one" and "cancel the home reminder"
         * both land.
         */
        fun match(all: List<PlaceWatch>, wanted: String): PlaceWatch? {
            val text = wanted.trim().lowercase()
            if (text.isBlank()) return null
            text.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id }?.let { return it } }
            all.firstOrNull { it.text.lowercase() == text }?.let { return it }
            all.firstOrNull { text.contains(it.text.lowercase()) || it.text.lowercase().contains(text) }
                ?.let { return it }
            val words = text.split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 3 && it !in FILLER }
            return all.firstOrNull { watch ->
                val hay = "${watch.text} ${watch.place}".lowercase()
                words.any { hay.contains(it) }
            }
        }
    }
}
