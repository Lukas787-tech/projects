package com.lukas.jarvis.maps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Places and routes from the OpenStreetMap stack: Overpass for "what is around
 * me", Nominatim for "where is this thing called", OSRM for the line between
 * them. No API key, no billing account, no quota to run out of mid-sentence.
 *
 * All three ask for a real User-Agent and light use, which is what a personal
 * assistant making one query per question is.
 */
class PlacesClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ----------------------------------------------------------------- search

    /**
     * Things of a kind near a point: "restaurants", "coffee", "pharmacy".
     *
     * Everything this returns is inside the radius asked for. That is the whole
     * contract: a wrong-continent result is worse than no result, because the
     * user acts on it.
     */
    suspend fun nearby(
        query: String,
        center: GeoPoint,
        radiusMeters: Int,
        limit: Int
    ): List<Place> = withContext(Dispatchers.IO) {
        val filters = Categories.filtersFor(query)
        val raw = if (filters.isNotEmpty()) {
            // A kind of place. Overpass searches by radius, so it cannot wander.
            overpass(filters, center, radiusMeters, limit)
        } else {
            // A name rather than a kind ("Aldi", "Cafe Central"). Nominatim is
            // the only thing that can find those, and it must be held to the
            // box: an unbounded viewbox is a hint, not a limit, and a search
            // for a common word cheerfully answers with the other side of the
            // planet. Asking for a restaurant in Germany is not answered by one
            // in Tunisia.
            geocode(query, center, radiusMeters, limit, bounded = true)
        }

        // Second line of defence, whatever the source: a result further out than
        // twice what was asked for is not an answer to "near me".
        val ceiling = radiusMeters * 2.0
        raw
            .map { it.copy(distanceMeters = Geo.distance(center, it.point)) }
            .filter { (it.distanceMeters ?: Double.MAX_VALUE) <= ceiling }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
            .take(limit)
    }

    /** Free-text lookup: an address, a business name, a town. */
    suspend fun geocode(
        query: String,
        near: GeoPoint? = null,
        radiusMeters: Int = 0,
        limit: Int = 5,
        bounded: Boolean = false
    ): List<Place> = withContext(Dispatchers.IO) {
        val url = buildString {
            append("https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1")
            append("&limit=").append(limit.coerceIn(1, 20))
            append("&q=").append(encode(query))
            if (near != null && radiusMeters > 0) {
                // Bounded is a hard box and is what "near me" means. Unbounded
                // is only a bias, for when the user named a place and the good
                // answer may legitimately be the next town over.
                val reach = if (bounded) radiusMeters.toDouble() else radiusMeters * 3.0
                if (bounded) append("&bounded=1")
                val latPad = Geo.latSpan(reach)
                val lonPad = Geo.lonSpan(reach, near.lat)
                append("&viewbox=")
                append(fmt(near.lon - lonPad)).append(',').append(fmt(near.lat + latPad))
                append(',')
                append(fmt(near.lon + lonPad)).append(',').append(fmt(near.lat - latPad))
            }
        }
        val body = get(url)
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val lat = item.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = item.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            val display = item.optString("display_name")
            val name = item.optString("name").ifBlank { display.substringBefore(',') }
            Place(
                name = name.ifBlank { "Unnamed place" },
                point = GeoPoint(lat, lon),
                category = item.optString("type").takeIf { it.isNotBlank() }?.replace('_', ' '),
                address = display.takeIf { it.isNotBlank() },
                distanceMeters = near?.let { Geo.distance(it, GeoPoint(lat, lon)) }
            )
        }
    }

    /** Street and area for a point, for "where am I". */
    suspend fun describe(point: GeoPoint): String? = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2" +
            "&lat=${fmt(point.lat)}&lon=${fmt(point.lon)}&zoom=17"
        val body = runCatching { get(url) }.getOrNull() ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        json.optString("display_name").takeIf { it.isNotBlank() }
    }

    private fun overpass(
        filters: List<String>,
        center: GeoPoint,
        radiusMeters: Int,
        limit: Int
    ): List<Place> {
        val around = "(around:$radiusMeters,${fmt(center.lat)},${fmt(center.lon)})"
        val clauses = filters.joinToString("") { "nwr$it$around;" }
        val query = "[out:json][timeout:20];($clauses);out center ${(limit * 3).coerceAtMost(60)};"

        // An endpoint that answers with nothing is an answer: there is nothing
        // of that kind nearby. Only when every endpoint refuses is this a
        // failure, and then it has to be said out loud rather than quietly
        // turning into a search of the whole world.
        var lastError: Exception? = null
        var answered = false
        for (endpoint in OVERPASS_ENDPOINTS) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", USER_AGENT)
                    .post(("data=" + encode(query)).toRequestBody(FORM))
                    .build()
                val body = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                val elements = JSONObject(body).optJSONArray("elements") ?: JSONArray()
                val places = (0 until elements.length()).mapNotNull { index ->
                    toPlace(elements.optJSONObject(index) ?: return@mapNotNull null)
                }
                answered = true
                if (places.isNotEmpty()) return places
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (!answered) lastError?.let { throw it }
        return emptyList()
    }

    private fun toPlace(element: JSONObject): Place? {
        val lat = element.optDouble("lat").takeIf { !it.isNaN() }
            ?: element.optJSONObject("center")?.optDouble("lat")?.takeIf { !it.isNaN() }
            ?: return null
        val lon = element.optDouble("lon").takeIf { !it.isNaN() }
            ?: element.optJSONObject("center")?.optDouble("lon")?.takeIf { !it.isNaN() }
            ?: return null

        val tags = element.optJSONObject("tags") ?: JSONObject()
        val name = tags.optString("name").takeIf { it.isNotBlank() } ?: return null

        val street = listOf(tags.optString("addr:street"), tags.optString("addr:housenumber"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val detail = listOf(
            tags.optString("cuisine").replace('_', ' ').replace(";", ", "),
            tags.optString("opening_hours")
        ).filter { it.isNotBlank() }.joinToString(" · ")

        return Place(
            name = name,
            point = GeoPoint(lat, lon),
            category = Categories.labelFor(tags),
            address = street.takeIf { it.isNotBlank() },
            detail = detail.takeIf { it.isNotBlank() }
        )
    }

    // ----------------------------------------------------------------- routes

    /** The actual road or footpath line between two points, plus the turns. */
    suspend fun route(from: GeoPoint, to: GeoPoint, mode: String): Route? =
        withContext(Dispatchers.IO) {
            val profile = when (mode) {
                Geo.MODE_CYCLE -> "bike"
                Geo.MODE_DRIVE -> "car"
                else -> "foot"
            }
            val coordinates = "${fmt(from.lon)},${fmt(from.lat)};${fmt(to.lon)},${fmt(to.lat)}"
            val suffix = "$coordinates?overview=full&geometries=polyline&steps=true"
            val endpoints = listOfNotNull(
                "https://routing.openstreetmap.de/routed-$profile/route/v1/$profile/$suffix",
                // The demo server only knows cars, so it is a fallback for
                // driving alone rather than for every mode.
                if (profile == "car") {
                    "https://router.project-osrm.org/route/v1/driving/$suffix"
                } else {
                    null
                }
            )

            for (endpoint in endpoints) {
                val body = runCatching { get(endpoint) }.getOrNull() ?: continue
                val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
                if (json.optString("code") != "Ok") continue
                val leg = json.optJSONArray("routes")?.optJSONObject(0) ?: continue
                return@withContext Route(
                    from = from,
                    to = to,
                    destination = "",
                    mode = mode,
                    distanceMeters = leg.optDouble("distance", 0.0),
                    durationSeconds = leg.optDouble("duration", 0.0),
                    points = Geo.decodePolyline(leg.optString("geometry")),
                    steps = parseSteps(leg)
                )
            }
            null
        }

    private fun parseSteps(route: JSONObject): List<RouteStep> {
        val steps = route.optJSONArray("legs")?.optJSONObject(0)?.optJSONArray("steps")
            ?: return emptyList()
        return (0 until steps.length()).mapNotNull { index ->
            val step = steps.optJSONObject(index) ?: return@mapNotNull null
            val maneuver = step.optJSONObject("maneuver") ?: JSONObject()
            val road = step.optString("name").takeIf { it.isNotBlank() }
            val instruction = phrase(
                type = maneuver.optString("type"),
                modifier = maneuver.optString("modifier"),
                road = road
            )
            RouteStep(instruction, step.optDouble("distance", 0.0))
        }
    }

    /** OSRM hands back maneuver codes; this is the human sentence for each. */
    private fun phrase(type: String, modifier: String, road: String?): String {
        val onto = road?.let { " onto $it" }.orEmpty()
        val along = road?.let { " along $it" }.orEmpty()
        val direction = modifier.replace("slight ", "slightly ").ifBlank { "ahead" }
        return when (type) {
            "depart" -> "Set off${along.ifBlank { "" }}"
            "arrive" -> "Arrive at your destination"
            "turn" -> when (modifier) {
                "straight" -> "Continue straight$onto"
                "uturn" -> "Make a U-turn$onto"
                else -> "Turn $direction$onto"
            }
            "new name", "continue" -> "Continue$onto"
            "merge" -> "Merge $direction$onto"
            "fork" -> "Keep $direction$onto"
            "end of road" -> "At the end of the road, turn $direction$onto"
            "roundabout", "rotary" -> "Take the roundabout$onto"
            "roundabout turn" -> "At the roundabout, go $direction$onto"
            "exit roundabout", "exit rotary" -> "Leave the roundabout$onto"
            else -> "Continue $direction$onto"
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", Locale.getDefault().toLanguageTag())
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun fmt(value: Double): String = String.format(Locale.US, "%.6f", value)

    private companion object {
        /**
         * Both OSM services identify callers by User-Agent and block the default
         * library one, so this names the app and how to reach its author.
         */
        const val USER_AGENT = "JarvisAssistant/1.0 (personal Android assistant; +https://github.com/lukas787-tech/projects)"

        val FORM = "application/x-www-form-urlencoded".toMediaType()

        val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
    }
}
