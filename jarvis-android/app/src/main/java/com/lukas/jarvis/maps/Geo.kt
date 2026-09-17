package com.lukas.jarvis.maps

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** One point on the planet, in degrees. */
data class GeoPoint(val lat: Double, val lon: Double)

/** A place the user could actually walk into. */
data class Place(
    val name: String,
    val point: GeoPoint,
    val category: String? = null,
    val address: String? = null,
    val detail: String? = null,
    val distanceMeters: Double? = null
)

data class RouteStep(val instruction: String, val distanceMeters: Double)

data class Route(
    val from: GeoPoint,
    val to: GeoPoint,
    val destination: String,
    val mode: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val points: List<GeoPoint>,
    val steps: List<RouteStep>
)

/**
 * Distances, bearings and the polyline codec, kept apart from the network calls
 * so both the tools and the map canvas can use them.
 */
object Geo {

    const val MODE_WALK = "walking"
    const val MODE_CYCLE = "cycling"
    const val MODE_DRIVE = "driving"

    val ALL_MODES = listOf(MODE_WALK, MODE_CYCLE, MODE_DRIVE)

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun normalizeMode(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "walk", "walking", "foot", "on foot", "wander" -> MODE_WALK
        "bike", "cycle", "cycling", "bicycle" -> MODE_CYCLE
        "drive", "driving", "car", "taxi", "bus" -> MODE_DRIVE
        else -> MODE_WALK
    }

    fun modeVerb(mode: String): String = when (mode) {
        MODE_CYCLE -> "cycling"
        MODE_DRIVE -> "driving"
        else -> "walking"
    }

    /** Great-circle metres between two points. */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** "north-east" and friends — spoken directions beat compass degrees. */
    fun bearingWord(from: GeoPoint, to: GeoPoint): String {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val degrees = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        val names = listOf(
            "north", "north-east", "east", "south-east",
            "south", "south-west", "west", "north-west"
        )
        return names[(((degrees + 22.5) % 360.0) / 45.0).toInt()]
    }

    fun formatDistance(meters: Double): String = when {
        meters < 1000 -> "${(meters / 10).roundToInt() * 10} m"
        meters < 10_000 -> String.format(Locale.US, "%.1f km", meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }

    fun formatDuration(seconds: Double): String {
        val minutes = (seconds / 60.0).roundToLong()
        if (minutes < 1) return "under a minute"
        if (minutes < 60) return "$minutes min"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) "$hours h" else "$hours h $rest min"
    }

    /** South-west and north-east corners of everything passed in. */
    fun bounds(points: List<GeoPoint>): Pair<GeoPoint, GeoPoint>? {
        if (points.isEmpty()) return null
        var minLat = points[0].lat
        var maxLat = points[0].lat
        var minLon = points[0].lon
        var maxLon = points[0].lon
        points.forEach {
            if (it.lat < minLat) minLat = it.lat
            if (it.lat > maxLat) maxLat = it.lat
            if (it.lon < minLon) minLon = it.lon
            if (it.lon > maxLon) maxLon = it.lon
        }
        return GeoPoint(minLat, minLon) to GeoPoint(maxLat, maxLon)
    }

    /** Degrees of longitude that cover [meters] at this latitude. */
    fun lonSpan(meters: Double, atLat: Double): Double {
        val metersPerDegree = 111_320.0 * cos(Math.toRadians(atLat)).coerceAtLeast(0.01)
        return abs(meters / metersPerDegree)
    }

    fun latSpan(meters: Double): Double = abs(meters / 110_540.0)

    /**
     * Google's encoded polyline, which is what OSRM hands back by default.
     * Five decimal places of precision, which is about a metre.
     */
    fun decodePolyline(encoded: String, precision: Int = 5): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var byte: Int
            do {
                if (index >= encoded.length) return out
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1f) shl shift)
                shift += 5
            } while (byte >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            result = 0
            shift = 0
            do {
                if (index >= encoded.length) return out
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1f) shl shift)
                shift += 5
            } while (byte >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            out.add(GeoPoint(lat / factor, lon / factor))
        }
        return out
    }
}
