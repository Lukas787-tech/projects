package com.lukas.jarvis.maps

import com.lukas.jarvis.core.Settings
import java.util.Locale

/**
 * The map half of the assistant, phrased for speech.
 *
 * Every method here does two things at once: it returns a sentence the model can
 * read out, and it leaves the same result on the map so the user can look at it.
 * That pairing is the whole point — "there are three places near you" is only
 * half an answer without the pins.
 */
class Navigator(
    private val locator: Locator,
    private val places: PlacesClient,
    private val store: MapStore,
    private val saved: SavedPlaces
) {

    suspend fun findPlaces(
        query: String,
        near: String?,
        radiusMeters: Int?,
        limit: Int,
        settings: Settings
    ): String {
        if (query.isBlank()) return "Need something to look for, like 'restaurants' or 'pharmacy'."

        val anchor = resolveAnchor(near) ?: return noLocation()
        val radius = (radiusMeters ?: settings.searchRadiusMeters).coerceIn(100, 20_000)
        val wanted = limit.coerceIn(1, 10)

        val found = runCatching { places.nearby(query, anchor.point, radius, wanted) }
            .getOrElse { return "The places lookup failed: ${it.message ?: "no response"}." }

        if (found.isEmpty()) {
            // A short radius is the usual reason for nothing, so try once wider
            // before telling the user there is nothing around.
            val wider = runCatching {
                places.nearby(query, anchor.point, (radius * 3).coerceAtMost(20_000), wanted)
            }.getOrDefault(emptyList())
            if (wider.isEmpty()) {
                return "Nothing matching '$query' within ${Geo.formatDistance(radius.toDouble())} " +
                    "of ${anchor.label}."
            }
            store.showPlaces("$query near ${anchor.label}", anchor.point, wider)
            return describe(query, anchor, wider, wide = true)
        }

        store.showPlaces("$query near ${anchor.label}", anchor.point, found)
        return describe(query, anchor, found, wide = false)
    }

    suspend fun routeTo(destination: String?, mode: String?, settings: Settings): String {
        val here = locator.current() ?: return noLocation()
        store.setHere(here)

        val target = resolveDestination(destination, here)
            ?: return when {
                destination.isNullOrBlank() -> "Tell me where to, or search for somewhere first."
                saved.isPersonal(destination) -> unknownPersonal(destination.orEmpty())
                else -> "I could not find '$destination' anywhere near you."
            }

        val travel = Geo.normalizeMode(mode ?: settings.travelMode)
        val route = runCatching { places.route(here, target.point, travel) }
            .getOrElse { return "The routing service failed: ${it.message ?: "no response"}." }
            ?: return "No ${Geo.modeVerb(travel)} route to ${target.name} came back. " +
                "It is ${Geo.formatDistance(Geo.distance(here, target.point))} away in a straight line."

        val named = route.copy(destination = target.name)
        store.showRoute(named, here, target)

        val head = "${target.name} is ${Geo.formatDistance(named.distanceMeters)} away, " +
            "about ${Geo.formatDuration(named.durationSeconds)} ${Geo.modeVerb(travel)}."
        val turns = named.steps
            .filter { it.distanceMeters > 15 || it.instruction.startsWith("Arrive") }
            .take(5)
            .joinToString(", then ") { step ->
                if (step.distanceMeters >= 20) {
                    "${step.instruction.replaceFirstChar { it.lowercase(Locale.ROOT) }} " +
                        "for ${Geo.formatDistance(step.distanceMeters)}"
                } else {
                    step.instruction.replaceFirstChar { it.lowercase(Locale.ROOT) }
                }
            }
        val body = if (turns.isBlank()) "" else " The way there: ${turns}."
        return "$head$body The route is drawn on the map."
    }

    /**
     * How long the way from here to [destination] takes in the usual travel
     * mode, without drawing anything; null when either end is unknown.
     */
    suspend fun travelSeconds(destination: String, settings: Settings): Double? {
        if (destination.isBlank()) return null
        val here = locator.current() ?: return null
        val target = resolveDestination(destination, here) ?: return null
        val mode = Geo.normalizeMode(settings.travelMode)
        return runCatching { places.route(here, target.point, mode) }.getOrNull()?.durationSeconds
    }

    suspend fun whereAmI(): String {
        val here = locator.current() ?: return noLocation()
        store.setHere(here)
        val description = places.describe(here)
        return description?.let { "You are around $it." }
            ?: String.format(
                Locale.US,
                "You are at %.4f, %.4f. No street name came back for that spot.",
                here.lat,
                here.lon
            )
    }

    suspend fun startNavigation(destination: String?, mode: String?, settings: Settings): String {
        val here = locator.current()
        val target = resolveDestination(destination, here)
            ?: return if (saved.isPersonal(destination)) {
                unknownPersonal(destination.orEmpty())
            } else {
                "I do not have a destination yet. Search for somewhere first."
            }
        val travel = Geo.normalizeMode(mode ?: settings.travelMode)
        val opened = store.openExternalNavigation(target.point, target.name, travel)
        return if (opened) {
            "Opening ${Geo.modeVerb(travel)} directions to ${target.name} in your maps app."
        } else {
            "No maps app on this phone would take the directions. " +
                "The route is on the Jarvis map instead."
        }
    }

    /**
     * Where a spoken place is, for things that are not a route: a saved name,
     * a pin on the map, an address, or — said as "here" or not said at all —
     * where the phone is now.
     */
    suspend fun locate(text: String?): Place? {
        val here = locator.current()
        if (text.isNullOrBlank() || text.looksLikeHere()) {
            return here?.let { Place(name = "here", point = it, category = "your location", distanceMeters = 0.0) }
        }
        return resolveDestination(text, here)
    }

    /** "home", "work" or "the car", said before any of them was saved. */
    fun isUnsavedPersonal(text: String): Boolean = saved.isPersonal(text) && saved.find(text) == null

    /** What to say when "home" or "work" is asked for before it was ever saved. */
    fun unknownPersonal(name: String): String {
        val word = saved.canonicalName(name)
        return if (word == "car") {
            "I don't know where the car is — nothing was saved when you parked. " +
                "Next time, say 'I parked here' as you leave it."
        } else {
            "I don't know where $word is yet. When you're there, say 'this is $word' and I'll keep it."
        }
    }

    /**
     * Pins where the phone is now — or the selected result — under a name.
     * "I parked here" and "this is home" are the same call.
     */
    suspend fun savePlace(name: String, note: String?, useSelectedPin: Boolean): String {
        if (name.isBlank()) return "What should I call this place?"
        val point = if (useSelectedPin) {
            store.current.selectedPlace?.point
                ?: return "No place is selected on the map to save."
        } else {
            locator.current(maxAgeMillis = 60_000L)
                ?: return noLocation()
        }
        val place = saved.save(name, point, note)
        store.setHere(if (useSelectedPin) null else point)
        val street = runCatching { places.describe(point) }.getOrNull()
            ?.split(",")?.take(2)?.joinToString(",")?.trim()
        val where = street?.let { " at $it" }.orEmpty()
        return if (place.isCar) {
            "Saved where you parked$where. Ask me for the way back to the car any time."
        } else {
            "Saved '${place.name}'$where. Say 'take me to ${place.name}' whenever you want the way."
        }
    }

    fun savedPlaces(here: GeoPoint?): String {
        val all = saved.all
        if (all.isEmpty()) return "No saved places yet. Say 'save this as home' or 'I parked here'."
        return all.joinToString("\n") { place ->
            val away = here?.let { " — ${Geo.formatDistance(Geo.distance(it, place.point))} away" }.orEmpty()
            "- ${place.name}$away" + (place.note?.let { " ($it)" } ?: "")
        }
    }

    fun forgetPlace(name: String): String =
        if (saved.remove(name)) "Forgot the saved place '$name'." else "No saved place called '$name'."

    /** A link anyone can open, for "send Anna my location". */
    suspend fun locationLink(): Pair<GeoPoint, String>? {
        val here = locator.current(maxAgeMillis = 60_000L) ?: return null
        store.setHere(here)
        val link = String.format(
            Locale.US,
            "https://maps.google.com/?q=%.6f,%.6f",
            here.lat,
            here.lon
        )
        return here to link
    }

    // ----------------------------------------------------------------- helpers

    private data class Anchor(val point: GeoPoint, val label: String)

    /** Search around a named place when one is given, otherwise around the user. */
    private suspend fun resolveAnchor(near: String?): Anchor? {
        if (!near.isNullOrBlank() && !near.looksLikeHere()) {
            val hit = runCatching { places.geocode(near, locator.remembered(), 0, 1) }
                .getOrDefault(emptyList())
                .firstOrNull()
            if (hit != null) return Anchor(hit.point, hit.name)
        }
        val here = locator.current() ?: return null
        store.setHere(here)
        return Anchor(here, "you")
    }

    /**
     * "the second one", "Trattoria Roma", "Bahnhofstrasse 12" — all three have to
     * land on the same kind of answer, because all three are things people say.
     */
    private suspend fun resolveDestination(destination: String?, here: GeoPoint?): Place? {
        val current = store.current
        val text = destination?.trim().orEmpty()

        if (text.isBlank()) return current.selectedPlace ?: current.places.firstOrNull()

        // "Home", "the car", "work": a name the user gave a place beats any search.
        saved.find(text)?.let { place ->
            return Place(
                name = if (place.isCar) "your parked car" else place.name,
                point = place.point,
                category = "saved place",
                distanceMeters = here?.let { Geo.distance(it, place.point) }
            )
        }

        // An unsaved "home" is not something to search the map for.
        if (saved.isPersonal(text)) return null

        ordinal(text)?.let { index -> current.places.getOrNull(index)?.let { return it } }

        current.places.firstOrNull { it.name.equals(text, ignoreCase = true) }?.let { return it }
        current.places.firstOrNull {
            it.name.contains(text, ignoreCase = true) || text.contains(it.name, ignoreCase = true)
        }?.let { return it }

        return runCatching { places.geocode(text, here, 5_000, 1) }
            .getOrDefault(emptyList())
            .firstOrNull()
    }

    private fun ordinal(text: String): Int? {
        val cleaned = text.lowercase(Locale.ROOT).removePrefix("the ").trim()
        cleaned.toIntOrNull()?.let { return it - 1 }
        // Only "the second one", "second", "2nd result": "Fifth Avenue" is a street.
        Regex("^(\\d+)(st|nd|rd|th)( (one|place|result|pin|option))?$").find(cleaned)?.let {
            return it.groupValues[1].toInt() - 1
        }
        return ORDINALS.indexOfFirst { word ->
            cleaned == word || Regex("^$word (one|place|result|pin|option)$").matches(cleaned)
        }.takeIf { it >= 0 }
    }

    private fun String.looksLikeHere(): Boolean {
        val text = lowercase(Locale.ROOT).trim()
        return text in setOf(
            "me", "here", "my area", "nearby", "around me", "my location", "us",
            "this place", "where i am", "current location", "hier"
        )
    }

    /**
     * A warning when the answer rests on an old fix. Without it "restaurants
     * near you" sounds equally confident whether the fix is ten seconds or two
     * days old, and the second one quietly means a different city.
     */
    private fun staleness(anchor: Anchor): String {
        if (anchor.label != "you") return ""
        val age = locator.lastFixAgeMillis() ?: return ""
        if (age < 30 * 60_000L) return ""
        val hours = age / 3_600_000.0
        val howOld = if (hours >= 24) {
            "${(hours / 24).toInt()} day(s)"
        } else if (hours >= 1) {
            "${hours.toInt()} hour(s)"
        } else {
            "${age / 60_000} minutes"
        }
        return " (working from a location fix $howOld old — say the town if that is wrong)"
    }

    private fun describe(
        query: String,
        anchor: Anchor,
        found: List<Place>,
        wide: Boolean
    ): String = buildString {
        val where = if (anchor.label == "you") "near you${staleness(anchor)}" else "near ${anchor.label}"
        append("${found.size} option(s) for '$query' $where")
        if (wide) append(" (had to widen the search)")
        appendLine(":")
        found.forEachIndexed { index, place ->
            val distance = place.distanceMeters?.let { meters ->
                "${Geo.formatDistance(meters)} ${Geo.bearingWord(anchor.point, place.point)}"
            } ?: "distance unknown"
            append("${index + 1}. ${place.name} — $distance")
            place.category?.let { append(" ($it)") }
            place.detail?.let { append(" — $it") }
            place.address?.let { append(" — $it") }
            appendLine()
        }
        append("They are pinned on the map. Call route_to with a name or a number for directions.")
    }

    private fun noLocation(): String =
        if (locator.hasPermission) {
            "No location fix yet. Step somewhere with signal, or tell me the area to look in."
        } else {
            "Location permission is off, so I cannot tell where you are. " +
                "Turn it on in Android settings, or name the area to search in."
        }

    private companion object {
        val ORDINALS = listOf(
            "first", "second", "third", "fourth", "fifth",
            "sixth", "seventh", "eighth", "ninth", "tenth"
        )
    }
}
