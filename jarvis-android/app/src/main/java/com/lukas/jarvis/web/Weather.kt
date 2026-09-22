package com.lukas.jarvis.web

import com.lukas.jarvis.maps.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** What the sky is doing, once, at one place. */
data class WeatherNow(
    val place: String,
    val temperature: Double,
    val feelsLike: Double,
    val description: String,
    val windKph: Double,
    val humidity: Int,
    val precipitationChance: Int,
    val isDay: Boolean
)

data class WeatherDay(
    val label: String,
    val high: Double,
    val low: Double,
    val description: String,
    val precipitationChance: Int
)

data class Forecast(val now: WeatherNow, val days: List<WeatherDay>)

/**
 * Weather from Open-Meteo.
 *
 * No key, no account, no quota worth worrying about, and no terms that require
 * a logo on screen — which is why it is here rather than one of the services
 * that would have made this a setup step instead of a feature. The response is
 * turned into prose at the edge, because a model handed a WMO code will make up
 * a word for it.
 */
class Weather {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun at(point: GeoPoint, placeName: String, days: Int = 3): Forecast? =
        withContext(Dispatchers.IO) {
            val wanted = days.coerceIn(1, 7)
            val url = buildString {
                append("https://api.open-meteo.com/v1/forecast")
                append("?latitude=").append(String.format(Locale.US, "%.4f", point.lat))
                append("&longitude=").append(String.format(Locale.US, "%.4f", point.lon))
                append("&current=temperature_2m,apparent_temperature,relative_humidity_2m,")
                append("precipitation_probability,weather_code,wind_speed_10m,is_day")
                append("&daily=weather_code,temperature_2m_max,temperature_2m_min,")
                append("precipitation_probability_max")
                append("&timezone=auto&forecast_days=").append(wanted)
            }

            val body = runCatching { fetch(url) }.getOrNull() ?: return@withContext null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
            val current = json.optJSONObject("current") ?: return@withContext null

            val now = WeatherNow(
                place = placeName,
                temperature = current.optDouble("temperature_2m", Double.NaN),
                feelsLike = current.optDouble("apparent_temperature", Double.NaN),
                description = describe(current.optInt("weather_code", -1)),
                windKph = current.optDouble("wind_speed_10m", 0.0),
                humidity = current.optInt("relative_humidity_2m", 0),
                precipitationChance = current.optInt("precipitation_probability", 0),
                isDay = current.optInt("is_day", 1) == 1
            )
            if (now.temperature.isNaN()) return@withContext null

            val daily = json.optJSONObject("daily")
            val out = ArrayList<WeatherDay>()
            if (daily != null) {
                val codes = daily.optJSONArray("weather_code")
                val highs = daily.optJSONArray("temperature_2m_max")
                val lows = daily.optJSONArray("temperature_2m_min")
                val rain = daily.optJSONArray("precipitation_probability_max")
                val count = highs?.length() ?: 0
                for (i in 0 until count) {
                    out.add(
                        WeatherDay(
                            label = when (i) {
                                0 -> "Today"
                                1 -> "Tomorrow"
                                else -> daily.optJSONArray("time")?.optString(i).orEmpty()
                                    .ifBlank { "In $i days" }
                            },
                            high = highs?.optDouble(i, Double.NaN) ?: Double.NaN,
                            low = lows?.optDouble(i, Double.NaN) ?: Double.NaN,
                            description = describe(codes?.optInt(i, -1) ?: -1),
                            precipitationChance = rain?.optInt(i, 0) ?: 0
                        )
                    )
                }
            }
            Forecast(now, out)
        }

    /** The one-paragraph version the assistant reads out. */
    fun speak(forecast: Forecast): String = buildString {
        val now = forecast.now
        append("${now.place}: ${now.description.lowercase(Locale.ROOT)}, ")
        append("${now.temperature.roundToInt()}°")
        if (kotlin.math.abs(now.feelsLike - now.temperature) >= 2) {
            append(", feels like ${now.feelsLike.roundToInt()}°")
        }
        append(". Wind ${now.windKph.roundToInt()} km/h")
        if (now.precipitationChance >= 20) append(", ${now.precipitationChance}% chance of rain")
        append(".")
        forecast.days.drop(1).take(3).forEach { day ->
            append(" ${day.label}: ${day.description.lowercase(Locale.ROOT)}, ")
            append("${day.low.roundToInt()}° to ${day.high.roundToInt()}°")
            if (day.precipitationChance >= 30) append(" (${day.precipitationChance}% rain)")
            append(".")
        }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Jarvis/2.2").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        /** WMO weather interpretation codes, in words a person would use. */
        fun describe(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61 -> "Light rain"
            63 -> "Rain"
            65 -> "Heavy rain"
            66, 67 -> "Freezing rain"
            71 -> "Light snow"
            73 -> "Snow"
            75 -> "Heavy snow"
            77 -> "Snow grains"
            80, 81 -> "Rain showers"
            82 -> "Violent rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Unsettled"
        }
    }
}
