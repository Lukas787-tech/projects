package com.lukas.jarvis.maps

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A place with a name you gave it: home, work, where the car is. */
data class SavedPlace(
    val name: String,
    val point: GeoPoint,
    val note: String? = null,
    val savedAt: Long = System.currentTimeMillis()
) {
    val isCar: Boolean get() = name.lowercase(Locale.ROOT) in CAR_WORDS

    companion object {
        val CAR_WORDS = setOf("car", "auto", "parking", "parked", "parkplatz", "wagen", "bike", "fahrrad")
    }
}

/**
 * The places worth a name.
 *
 * "Take me home" and "where did I park" are the two directions people ask for
 * most, and neither can be answered by a search: home is not a shop and the car
 * is wherever it was left. So those are kept here, on the phone, and the router
 * looks here before it looks anything up.
 */
class SavedPlaces(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jarvis_places", Context.MODE_PRIVATE)

    private val _places = MutableStateFlow(read())
    val places: StateFlow<List<SavedPlace>> = _places.asStateFlow()

    val all: List<SavedPlace> get() = _places.value

    /** Reads the store again, after a restore wrote it behind this class's back. */
    fun reload() {
        _places.value = read()
    }

    /** Saving under a name that exists moves it: there is one "car". */
    fun save(name: String, point: GeoPoint, note: String? = null): SavedPlace {
        val clean = canonical(name)
        val place = SavedPlace(clean, point, note?.takeIf { it.isNotBlank() })
        write(_places.value.filterNot { it.name.equals(clean, ignoreCase = true) } + place)
        return place
    }

    fun remove(name: String): Boolean {
        val target = find(name) ?: return false
        write(_places.value - target)
        return true
    }

    /** Exact name, then a synonym, then a name contained in the phrase. */
    fun find(text: String?): SavedPlace? {
        val wanted = text?.trim()?.lowercase(Locale.ROOT)?.removePrefix("my ")?.removePrefix("the ")
            ?.trim().orEmpty()
        if (wanted.isBlank()) return null
        val key = canonical(wanted)
        _places.value.firstOrNull { it.name.equals(key, ignoreCase = true) }?.let { return it }
        return _places.value.firstOrNull { wanted.contains(it.name.lowercase(Locale.ROOT)) }
    }

    private fun canonical(raw: String): String {
        val text = raw.trim().lowercase(Locale.ROOT).removePrefix("my ").trim()
        return when (text) {
            "zuhause", "daheim", "house", "home address", "my home" -> "home"
            "arbeit", "office", "büro", "buero", "job" -> "work"
            "auto", "wagen", "parking", "parked car", "parkplatz", "my car" -> "car"
            else -> text
        }
    }

    private fun read(): List<SavedPlace> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            SavedPlace(
                name = obj.optString("name"),
                point = GeoPoint(obj.optDouble("lat"), obj.optDouble("lon")),
                note = obj.optString("note").takeIf { it.isNotBlank() },
                savedAt = obj.optLong("at")
            )
        }.filter { it.name.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun write(places: List<SavedPlace>) {
        val array = JSONArray()
        places.forEach { place ->
            array.put(
                JSONObject().apply {
                    put("name", place.name)
                    put("lat", place.point.lat)
                    put("lon", place.point.lon)
                    place.note?.let { put("note", it) }
                    put("at", place.savedAt)
                }
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        _places.value = places
    }

    private companion object {
        const val KEY = "places"
    }
}
