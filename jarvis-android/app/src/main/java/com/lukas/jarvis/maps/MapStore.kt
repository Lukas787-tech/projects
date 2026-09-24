package com.lukas.jarvis.maps

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * What the map is currently showing. [revision] only ever goes up, which is how
 * the UI knows a spoken answer just changed the map and it should surface it.
 */
data class MapState(
    val revision: Long = 0,
    val title: String = "",
    val here: GeoPoint? = null,
    val places: List<Place> = emptyList(),
    val selected: Int = -1,
    val route: Route? = null,
    /** How far off [here] may be, drawn as the pale disc around the dot. */
    val accuracy: Float? = null,
    /** Direction of travel from GPS while moving; the compass covers standing still. */
    val bearing: Float? = null
) {
    val selectedPlace: Place? get() = places.getOrNull(selected)

    /** Everything that has to stay in frame. */
    val focusPoints: List<GeoPoint>
        get() = when {
            route != null -> route.points.ifEmpty { listOfNotNull(route.from, route.to) }
            places.isNotEmpty() -> places.map { it.point } + listOfNotNull(here)
            else -> listOfNotNull(here)
        }
}

/**
 * The single place the map lives. Tools write to it while a turn is running and
 * the map screen reads it, so the answer you hear and the pins you see are the
 * same result.
 */
class MapStore(context: Context) {

    private val app = context.applicationContext

    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state.asStateFlow()

    val current: MapState get() = _state.value

    fun showPlaces(title: String, here: GeoPoint?, places: List<Place>) {
        _state.value = MapState(
            revision = _state.value.revision + 1,
            title = title,
            here = here ?: _state.value.here,
            places = places,
            selected = if (places.isEmpty()) -1 else 0,
            route = null
        )
    }

    fun showRoute(route: Route, here: GeoPoint?, destination: Place?) {
        val existing = _state.value
        val places = when {
            destination == null -> existing.places
            existing.places.any { it.samePlace(destination) } -> existing.places
            else -> existing.places + destination
        }
        _state.value = existing.copy(
            revision = existing.revision + 1,
            title = "Route to ${route.destination}",
            here = here ?: existing.here,
            places = places,
            selected = destination?.let { target -> places.indexOfFirst { it.samePlace(target) } }
                ?: existing.selected,
            route = route
        )
    }

    fun setHere(point: GeoPoint?) {
        if (point == null) return
        _state.value = _state.value.copy(here = point)
    }

    /** A live reading. It moves the dot but never reframes the map. */
    fun setFix(fix: Fix) {
        val state = _state.value
        val moved = state.here?.let { Geo.distance(it, fix.point) } ?: Double.MAX_VALUE
        if (moved < 1.5 && state.accuracy == fix.accuracyMeters) return
        _state.value = state.copy(here = fix.point, accuracy = fix.accuracyMeters, bearing = fix.bearing)
    }

    fun select(index: Int) {
        val state = _state.value
        if (index !in state.places.indices) return
        // Selecting a different pin invalidates a route drawn to the old one.
        val keepRoute = state.route?.takeIf { route ->
            state.places.getOrNull(index)?.let { place ->
                Geo.distance(place.point, route.to) < 40
            } == true
        }
        _state.value = state.copy(selected = index, route = keepRoute)
    }

    fun clear() {
        _state.value = MapState(
            revision = _state.value.revision,
            here = _state.value.here,
            accuracy = _state.value.accuracy
        )
    }

    /**
     * Hands a destination to whichever maps app is installed, for actual
     * turn-by-turn. Drawing the line is this app's job; talking someone through
     * a motorway junction is not.
     */
    fun openExternalNavigation(destination: GeoPoint, label: String?, mode: String): Boolean {
        val travel = when (mode) {
            Geo.MODE_CYCLE -> "b"
            Geo.MODE_DRIVE -> "d"
            else -> "w"
        }
        val coordinates = String.format(
            Locale.US,
            "%.6f,%.6f",
            destination.lat,
            destination.lon
        )
        val candidates = listOf(
            Uri.parse("google.navigation:q=$coordinates&mode=$travel"),
            Uri.parse("geo:$coordinates?q=$coordinates(${Uri.encode(label ?: "Destination")})"),
            Uri.parse("https://www.openstreetmap.org/directions?to=$coordinates")
        )
        for (uri in candidates) {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { app.startActivity(intent); true }.getOrDefault(false)) return true
        }
        return false
    }
}

/** Two results are the same place if they are the same name within a few metres. */
private fun Place.samePlace(other: Place): Boolean =
    name.equals(other.name, ignoreCase = true) && Geo.distance(point, other.point) < 40
