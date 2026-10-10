package com.lukas.jarvis.moment

import org.json.JSONArray
import org.json.JSONObject

/**
 * The numbers behind a money or habit card, handed over by the tool that
 * worked them out, so the card draws real values rather than reading them
 * back out of a sentence.
 */
data class Chart(
    val bars: List<Bar> = emptyList(),
    /** A habit's last seven days, oldest first; empty when the card is not a habit's. */
    val week: List<Boolean> = emptyList(),
    /** One quiet line under the bars: "Last month by now: 212.00 EUR". */
    val caption: String = ""
) {
    val isEmpty: Boolean get() = bars.isEmpty() && week.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("bars", JSONArray(bars.map { it.toJson() }))
        .put("week", JSONArray(week))
        .put("caption", caption)

    companion object {
        fun fromJson(json: JSONObject?): Chart? {
            json ?: return null
            val bars = json.optJSONArray("bars")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Bar::fromJson) }
            } ?: emptyList()
            val week = json.optJSONArray("week")?.let { array -> (0 until array.length()).map { array.optBoolean(it) } } ?: emptyList()
            return Chart(bars, week, json.optString("caption")).takeUnless { it.isEmpty }
        }
    }
}

/**
 * One row: a label, how much, and how it reads. With a [limit] the row is a
 * meter against a budget; with a [compare] it carries a mark for last time.
 */
data class Bar(
    val label: String,
    val value: Double,
    /** The value as it should be read: "45.20 EUR". */
    val shown: String,
    val limit: Double? = null,
    /** "of 300.00 EUR" or "84%": what sits beside the value. */
    val note: String = "",
    val compare: Double? = null
) {
    val over: Boolean get() = limit != null && value > limit

    fun toJson(): JSONObject = JSONObject()
        .put("label", label)
        .put("value", value)
        .put("shown", shown)
        .put("note", note)
        .apply {
            limit?.let { put("limit", it) }
            compare?.let { put("compare", it) }
        }

    companion object {
        fun fromJson(json: JSONObject): Bar = Bar(
            label = json.optString("label"),
            value = json.optDouble("value", 0.0),
            shown = json.optString("shown"),
            limit = if (json.has("limit")) json.optDouble("limit") else null,
            note = json.optString("note"),
            compare = if (json.has("compare")) json.optDouble("compare") else null
        )
    }
}
