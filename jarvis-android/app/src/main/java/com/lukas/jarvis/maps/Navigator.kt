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
    private val store: MapStore
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
            ?: return if (destination.isNullOrBlank()) {
                "Tell me where to, or search for somewhere first."
            } else {
                "I could not find '$destination' anywhere near you."
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
            ?: return "I do not have a destination yet. Search for somewhere first."
        val travel = Geo.normalizeMode(mode ?: settings.travelMode)
        val opened = store.openExternalNavigation(target.point, target.name, travel)
        return if (opened) {
            "Opening ${Geo.modeVerb(travel)} directions to ${target.name} in your maps app."
        } else {
            "No maps app on this phone would take the directions. " +
                "The route is on the Jarvis map instead."
        }
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
        return ORDINALS.indexOfFirst { cleaned.startsWith(it) }.takeIf { it >= 0 }
    }

    private fun String.looksLikeHere(): Boolean {
        val text = lowercase(Locale.ROOT).trim()
        return text in setOf("me", "here", "my area", "nearby", "around me", "my location", "us")
    }

    private fun describe(
        query: String,
        anchor: Anchor,
        found: List<Place>,
        wide: Boolean
    ): String = buildString {
        val where = if (anchor.label == "you") "near you" else "near ${anchor.label}"
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
