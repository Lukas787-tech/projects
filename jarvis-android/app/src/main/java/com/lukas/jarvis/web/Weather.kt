package com.lukas.jarvis.web

import com.lukas.jarvis.maps.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    val isDay: Boolean,
    val uvIndex: Double = Double.NaN
)

data class WeatherDay(
    val label: String,
    val high: Double,
    val low: Double,
    val description: String,
    val precipitationChance: Int,
    val sunrise: String = "",
    val sunset: String = "",
    val uvMax: Double = Double.NaN
)

/** What is in the air: the European index, and pollen where it is measured. */
data class Air(val index: Int, val pollen: Map<String, Double> = emptyMap())

data class Forecast(
    val now: WeatherNow,
    val days: List<WeatherDay>,
    /** When rain becomes likely in the next twelve hours, as "15:00", or null. */
    val rainFrom: String? = null,
    val air: Air? = null
)

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
                append("precipitation_probability,weather_code,wind_speed_10m,is_day,uv_index")
                append("&daily=weather_code,temperature_2m_max,temperature_2m_min,")
                append("precipitation_probability_max,sunrise,sunset,uv_index_max")
                append("&hourly=precipitation_probability&forecast_hours=12")
                append("&timezone=auto&forecast_days=").append(wanted)
            }

            // The air is a second service; asked at the same time, and the
            // forecast does not wait on it failing.
            val air = async { runCatching { air(point) }.getOrNull() }
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
                isDay = current.optInt("is_day", 1) == 1,
                uvIndex = current.optDouble("uv_index", Double.NaN)
            )
            if (now.temperature.isNaN()) return@withContext null

            val daily = json.optJSONObject("daily")
            val out = ArrayList<WeatherDay>()
            if (daily != null) {
                val codes = daily.optJSONArray("weather_code")
                val highs = daily.optJSONArray("temperature_2m_max")
                val lows = daily.optJSONArray("temperature_2m_min")
                val rain = daily.optJSONArray("precipitation_probability_max")
                val rises = daily.optJSONArray("sunrise")
                val sets = daily.optJSONArray("sunset")
                val uv = daily.optJSONArray("uv_index_max")
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
                            precipitationChance = rain?.optInt(i, 0) ?: 0,
                            sunrise = rises?.optString(i).orEmpty().substringAfter('T', ""),
                            sunset = sets?.optString(i).orEmpty().substringAfter('T', ""),
                            uvMax = uv?.optDouble(i, Double.NaN) ?: Double.NaN
                        )
                    )
                }
            }
            // The first hour worth taking an umbrella for, unless it is
            // already raining — then the sky has said so itself.
            val hourly = json.optJSONObject("hourly")
            val chances = hourly?.optJSONArray("precipitation_probability")
            val times = hourly?.optJSONArray("time")
            val rainFrom = if (chances == null || times == null || now.precipitationChance >= RAIN_LIKELY) {
                null
            } else {
                (1 until chances.length()).firstOrNull { chances.optInt(it, 0) >= RAIN_LIKELY }
                    ?.let { times.optString(it).substringAfter('T', "").ifBlank { null } }
            }

            Forecast(now, out, rainFrom, air.await())
        }

    /** Open-Meteo's air-quality service: the European AQI and, in Europe, pollen. */
    private fun air(point: GeoPoint): Air? {
        val url = buildString {
            append("https://air-quality-api.open-meteo.com/v1/air-quality")
            append("?latitude=").append(String.format(Locale.US, "%.4f", point.lat))
            append("&longitude=").append(String.format(Locale.US, "%.4f", point.lon))
            append("&current=european_aqi,birch_pollen,grass_pollen,ragweed_pollen,alder_pollen")
        }
        val current = JSONObject(fetch(url)).optJSONObject("current") ?: return null
        if (current.isNull("european_aqi")) return null
        val pollen = POLLEN.mapNotNull { (key, name) ->
            if (current.isNull(key)) null else current.optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.let { name to it }
        }.toMap()
        return Air(current.optInt("european_aqi"), pollen)
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
        forecast.rainFrom?.let { append(" Rain likely from about $it.") }
        val today = forecast.days.firstOrNull()
        if (today != null) {
            if (now.isDay && today.sunset.isNotBlank()) append(" Sunset at ${today.sunset}.")
            if (!now.isDay && today.sunrise.isNotBlank()) append(" Sunrise at ${today.sunrise}.")
            if (!today.uvMax.isNaN() && today.uvMax >= 3) {
                append(" UV up to ${today.uvMax.roundToInt()} (${uvWord(today.uvMax)})")
                append(if (today.uvMax >= 6) " — sunscreen." else ".")
            }
        }
        forecast.air?.let { air ->
            append(" Air quality ${airWord(air.index)} (${air.index}).")
            val high = air.pollen.filter { it.value >= POLLEN_HIGH }.keys
            if (high.isNotEmpty()) append(" High ${high.joinToString(" and ")} pollen.")
        }
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
        private const val RAIN_LIKELY = 50
        /** Grains per cubic metre above which allergy sufferers notice. */
        private const val POLLEN_HIGH = 50.0
        private val POLLEN = listOf(
            "birch_pollen" to "birch",
            "grass_pollen" to "grass",
            "ragweed_pollen" to "ragweed",
            "alder_pollen" to "alder"
        )

        fun uvWord(uv: Double): String = when {
            uv < 3 -> "low"
            uv < 6 -> "moderate"
            uv < 8 -> "high"
            uv < 11 -> "very high"
            else -> "extreme"
        }

        /** The European AQI bands. */
        fun airWord(index: Int): String = when {
            index <= 20 -> "good"
            index <= 40 -> "fair"
            index <= 60 -> "moderate"
            index <= 80 -> "poor"
            index <= 100 -> "very poor"
            else -> "extremely poor"
        }

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
