package com.lukas.jarvis.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A trip: where, from which day, to which day. */
data class Trip(val destination: String, val start: LocalDate, val end: LocalDate) {
    val nights: Int get() = ChronoUnit.DAYS.between(start, end).toInt().coerceAtLeast(0)

    /** "14 Oct to 19 Oct", "14 to 19 Oct" in one month, or one day for a day trip. */
    val dates: String
        get() {
            val day = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
            return when {
                start == end -> start.format(day)
                start.month == end.month && start.year == end.year -> "${start.dayOfMonth} to ${end.format(day)}"
                else -> "${start.format(day)} to ${end.format(day)}"
            }
        }

    /** The packing list's own name, so it never mixes with another trip's. */
    val listName: String get() = "packing for ${destination.lowercase(Locale.ROOT)}"
}

/** The weather at the destination over the trip, when it is close enough to know. */
data class TripWeather(val high: Double, val low: Double, val rainyDays: Int)

/**
 * The travel helper's rules: when the trip is, and what to pack for that many
 * nights in that weather. Plain values in and out, so they can be tested.
 */
object Trips {

    /** A trip from its first day and either its last day or its length; null when the dates make no sense. */
    fun of(destination: String, start: LocalDate?, end: LocalDate?, nights: Int?): Trip? {
        val place = destination.trim().takeIf { it.isNotEmpty() } ?: return null
        val first = start ?: return null
        val last = end ?: nights?.takeIf { it in 0..120 }?.let { first.plusDays(it.toLong()) } ?: first
        if (last.isBefore(first)) return null
        return Trip(place.replaceFirstChar { it.titlecase(Locale.ROOT) }, first, last)
    }

    /**
     * What to pack: the things everyone forgets, clothes for the length, and
     * what the weather there asks for. With no forecast yet, a little for rain.
     */
    fun packing(trip: Trip, weather: TripWeather?): List<String> {
        val days = trip.nights + 1
        val clothes = minOf(days, 7)
        return buildList {
            add("Passport or ID")
            add("Phone charger")
            add("Toothbrush and toothpaste")
            add("Any medicine")
            if (trip.nights > 0) {
                add("Underwear ×$clothes")
                add("Socks ×$clothes")
                add("T-shirts ×${minOf(days, 6)}")
                add("Something to sleep in")
            }
            if (trip.nights >= 4) add("A bag for laundry")
            when {
                weather == null -> add("Something for rain, just in case")
                weather.rainyDays > 0 -> add("Umbrella or a rain jacket")
            }
            if (weather != null && weather.low < 10) {
                add("A warm jacket")
                add("A jumper")
            }
            if (weather != null && weather.high >= 24) {
                add("Sunscreen")
                add("Sunglasses")
            }
            if (weather != null && weather.high >= 27) add("Swimwear")
        }
    }
}
