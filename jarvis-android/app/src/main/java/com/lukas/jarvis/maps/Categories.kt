package com.lukas.jarvis.maps

import org.json.JSONObject
import java.util.Locale

/**
 * Turns the words people actually say into OpenStreetMap tag filters.
 *
 * "I'm hungry" has to become `amenity=restaurant` somewhere, and doing it here
 * rather than in the prompt means it works the same whichever model is answering.
 */
object Categories {

    /** Selector strings, ready to be pasted into an Overpass query. */
    fun filtersFor(rawQuery: String): List<String> {
        val query = rawQuery.lowercase(Locale.ROOT)
        if (query.isBlank()) return emptyList()

        CUISINES.firstOrNull { cuisine -> says(query, cuisine) }?.let { cuisine ->
            return listOf("[\"amenity\"~\"restaurant|fast_food\"][\"cuisine\"~\"$cuisine\",i]")
        }

        val hit = TABLE.firstOrNull { (words, _) -> words.any { says(query, it) } }
        return hit?.second ?: emptyList()
    }

    /**
     * [word] as a word of [query], plural allowed: "bars" is a bar, but
     * "barber" is not, and "theatre" does not contain a wish to eat.
     */
    internal fun says(query: String, word: String): Boolean =
        Regex("(^|[^\\p{L}])" + Regex.escape(word) + "(s|es)?($|[^\\p{L}])").containsMatchIn(query)

    /** A short human label for a place, from whatever tag carries its kind. */
    fun labelFor(tags: JSONObject): String? {
        val raw = listOf("amenity", "shop", "tourism", "leisure", "healthcare", "office")
            .firstNotNullOfOrNull { key -> tags.optString(key).takeIf { it.isNotBlank() } }
            ?: return null
        return raw.replace('_', ' ')
    }

    private fun amenity(vararg values: String) =
        listOf("[\"amenity\"~\"^(${values.joinToString("|")})$\"]")

    private val TABLE: List<Pair<List<String>, List<String>>> = listOf(
        listOf("restaurant", "hungry", "eat", "food", "dinner", "lunch", "dine", "meal")
            to amenity("restaurant", "fast_food"),
        listOf("fast food", "takeaway", "take away", "takeout") to amenity("fast_food"),
        listOf("coffee", "cafe", "café", "espresso", "breakfast", "brunch") to amenity("cafe"),
        listOf("bar", "pub", "beer", "drink", "cocktail", "nightlife") to amenity("bar", "pub"),
        listOf("bakery", "bread", "pastry") to listOf("[\"shop\"=\"bakery\"]"),
        listOf("ice cream", "gelato") to amenity("ice_cream"),
        listOf("supermarket", "groceries", "grocery", "shopping", "shop for food")
            to listOf("[\"shop\"~\"^(supermarket|convenience|greengrocer)$\"]"),
        listOf("pharmacy", "chemist", "medicine", "painkiller") to amenity("pharmacy"),
        listOf("hospital", "emergency room", "a&e") to amenity("hospital", "clinic"),
        listOf("doctor", "gp") to amenity("doctors"),
        listOf("dentist", "toothache") to amenity("dentist"),
        listOf("atm", "cash machine", "cashpoint") to amenity("atm"),
        listOf("bank") to amenity("bank"),
        listOf("petrol", "gas station", "fuel", "gasoline", "charging", "charger")
            to amenity("fuel", "charging_station"),
        listOf("parking", "park the car") to amenity("parking"),
        listOf("toilet", "bathroom", "restroom", "loo") to amenity("toilets"),
        listOf("hotel", "stay the night", "accommodation", "hostel")
            to listOf("[\"tourism\"~\"^(hotel|hostel|guest_house|motel)$\"]"),
        listOf("gym", "workout", "fitness") to listOf("[\"leisure\"=\"fitness_centre\"]"),
        listOf("park", "green space", "playground")
            to listOf("[\"leisure\"~\"^(park|playground|garden)$\"]"),
        listOf("cinema", "movie") to amenity("cinema"),
        listOf("library") to amenity("library"),
        listOf("museum", "gallery") to listOf("[\"tourism\"~\"^(museum|gallery)$\"]"),
        listOf("post office", "post", "parcel") to amenity("post_office", "post_box"),
        listOf("bus stop", "bus") to listOf("[\"highway\"=\"bus_stop\"]"),
        listOf("train station", "railway station", "train", "metro", "subway", "tram")
            to listOf("[\"railway\"~\"^(station|halt|tram_stop)$\"]"),
        listOf("taxi") to amenity("taxi"),
        listOf("hairdresser", "barber", "haircut") to listOf("[\"shop\"=\"hairdresser\"]"),
        listOf("laundry", "launderette") to listOf("[\"shop\"~\"^(laundry|dry_cleaning)$\"]"),
        listOf("hardware", "diy", "tools") to listOf("[\"shop\"~\"^(hardware|doityourself)$\"]"),
        listOf("vet", "veterinary") to amenity("veterinary"),
        listOf("police") to amenity("police"),
        listOf("water", "drinking water") to amenity("drinking_water")
    )

    /** Checked before the table, so "pizza place" beats the generic restaurant rule. */
    private val CUISINES = listOf(
        "pizza", "sushi", "kebab", "burger", "ramen", "noodle", "curry", "tapas",
        "italian", "chinese", "japanese", "thai", "indian", "mexican", "greek",
        "turkish", "vietnamese", "korean", "spanish", "french", "german", "lebanese",
        "vegan", "vegetarian", "seafood", "steak", "barbecue", "sandwich", "salad"
    )
}
