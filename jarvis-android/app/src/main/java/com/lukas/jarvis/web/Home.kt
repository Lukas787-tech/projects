package com.lukas.jarvis.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One thing in the house, as Home Assistant describes it. */
data class Device(
    val id: String,
    val name: String,
    val state: String,
    val attributes: JSONObject
) {
    val domain: String get() = id.substringBefore('.')
}

/**
 * The house, through Home Assistant's REST API.
 *
 * Home Assistant is free and runs on the user's own hardware, and it already
 * speaks to nearly every light, plug, thermostat, lock and blind there is — so
 * one integration here reaches all of them, with no cloud account and no key
 * but the user's own long-lived token. Devices are found by the names the
 * user gave them, the way they would say them out loud.
 */
class Home {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun devices(base: String, token: String): List<Device> = withContext(Dispatchers.IO) {
        val body = request(base, token, "/api/states", null)
        val array = JSONArray(body)
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("entity_id").ifBlank { return@mapNotNull null }
            val attributes = item.optJSONObject("attributes") ?: JSONObject()
            Device(
                id = id,
                name = attributes.optString("friendly_name").ifBlank { id.substringAfter('.').replace('_', ' ') },
                state = item.optString("state"),
                attributes = attributes
            )
        }
    }

    /** What the house looks like right now, or the devices that match [query]. */
    suspend fun status(base: String, token: String, query: String?): String {
        val all = devices(base, token).filter { it.domain in INTERESTING }
        if (all.isEmpty()) return "Home Assistant answered but reported no devices."
        val q = query?.trim().orEmpty()
        if (q.isNotBlank()) {
            val hits = match(all, q, controllable = false)
            if (hits.isEmpty()) return "Nothing in the house is called '$q'."
            return hits.take(12).joinToString("\n") { describe(it) }
        }
        val lightsOn = all.filter { it.domain == "light" && it.state == "on" }
        val open = all.filter { it.domain == "cover" && it.state == "open" }
        val unlocked = all.filter { it.domain == "lock" && it.state == "unlocked" }
        val climate = all.filter { it.domain == "climate" }
        val temps = all.filter {
            it.domain == "sensor" && it.attributes.optString("device_class") == "temperature"
        }.take(4)
        return buildString {
            appendLine("Lights on: ${lightsOn.joinToString { it.name }.ifBlank { "none" }}.")
            if (open.isNotEmpty()) appendLine("Open: ${open.joinToString { it.name }}.")
            if (unlocked.isNotEmpty()) appendLine("Unlocked: ${unlocked.joinToString { it.name }}.")
            climate.forEach { appendLine(describe(it)) }
            temps.forEach { appendLine(describe(it)) }
            append("${all.size} devices in all.")
        }.trim()
    }

    /**
     * Does one thing to whichever devices [target] names. "All lights" and
     * "every light" reach the whole domain; anything else reaches the best
     * match, or every device whose name holds all the words said.
     */
    suspend fun control(
        base: String,
        token: String,
        target: String,
        action: String,
        value: Double?
    ): String {
        val all = devices(base, token)
        val lower = target.lowercase(Locale.ROOT)
        val wantsAll = lower.startsWith("all ") || lower.startsWith("every ") || lower.contains(" all ")
        val domainWord = DOMAIN_WORDS.entries.firstOrNull { (word, _) -> lower.contains(word) }?.value
        val targets = when {
            wantsAll && domainWord != null -> {
                // "All lights in the kitchen" is the kitchen's lights: any
                // words beyond "all" and the device kind narrow it down.
                val kind = all.filter { it.domain == domainWord }
                val place = lower.split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.length > 1 && it !in ROOM_FILLER && DOMAIN_WORDS.keys.none { word -> it.startsWith(word) } }
                if (place.isEmpty()) kind
                else kind.filter { device ->
                    val words = (device.name + " " + device.id.replace('_', ' ').replace('.', ' ')).lowercase(Locale.ROOT)
                    place.all { words.contains(it) }
                }
            }
            else -> match(all, target, controllable = true)
        }
        if (targets.isEmpty()) {
            val near = all.filter { it.domain in CONTROLLABLE }.map { it.name }
                .sortedBy { distance(it.lowercase(Locale.ROOT), lower) }.take(4)
            return "Nothing called '$target' to control. Closest: ${near.joinToString()}."
        }

        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (device in targets.take(MAX_AT_ONCE)) {
            val (service, data) = serviceFor(device, action, value)
                ?: run {
                    failed += "${device.name} cannot '$action'"
                    null to null
                }
            if (service == null) continue
            val payload = (data ?: JSONObject()).put("entity_id", device.id)
            runCatching {
                withContext(Dispatchers.IO) {
                    request(base, token, "/api/services/${service.first}/${service.second}", payload)
                }
            }.onSuccess { done += device.name }
                .onFailure { failed += "${device.name} (${it.message})" }
        }
        return buildString {
            if (done.isNotEmpty()) append("Done — ${describeAction(action, value)}: ${done.joinToString()}.")
            if (failed.isNotEmpty()) append(" Could not: ${failed.joinToString()}.")
        }.trim()
    }

    /** Checks the address and token, for the settings screen. */
    suspend fun check(base: String, token: String): String = runCatching {
        val count = devices(base, token).size
        "Connected — $count devices."
    }.getOrElse { "Could not connect: ${it.message}" }

    // ---------------------------------------------------------------- details

    private fun serviceFor(device: Device, action: String, value: Double?): Pair<Pair<String, String>, JSONObject?>? {
        val d = device.domain
        val a = action.lowercase(Locale.ROOT)
        return when {
            d == "scene" || d == "script" -> (d to "turn_on") to null
            d == "button" || d == "input_button" -> (d to "press") to null
            d == "lock" && a in setOf("lock", "on", "close") -> (d to "lock") to null
            d == "lock" && a in setOf("unlock", "off", "open") -> (d to "unlock") to null
            d == "cover" && a in setOf("open", "on", "up") -> (d to "open_cover") to null
            d == "cover" && a in setOf("close", "off", "down") -> (d to "close_cover") to null
            d == "cover" && a == "stop" -> (d to "stop_cover") to null
            d == "cover" && a == "set_position" && value != null ->
                (d to "set_cover_position") to JSONObject().put("position", value.toInt().coerceIn(0, 100))
            d == "climate" && a == "set_temperature" && value != null ->
                (d to "set_temperature") to JSONObject().put("temperature", value)
            d == "climate" && a in setOf("on", "off") -> (d to "turn_$a") to null
            d == "light" && a == "set_brightness" && value != null ->
                ("light" to "turn_on") to JSONObject().put("brightness_pct", value.toInt().coerceIn(1, 100))
            d == "media_player" && a == "set_volume" && value != null ->
                (d to "volume_set") to JSONObject().put("volume_level", (value / 100.0).coerceIn(0.0, 1.0))
            d == "fan" && a == "set_speed" && value != null ->
                (d to "set_percentage") to JSONObject().put("percentage", value.toInt().coerceIn(0, 100))
            d in TOGGLEABLE && a in setOf("on", "off", "toggle") -> (d to "turn_$a".replace("turn_toggle", "toggle")) to null
            else -> null
        }
    }

    private fun match(all: List<Device>, query: String, controllable: Boolean): List<Device> {
        val pool = if (controllable) all.filter { it.domain in CONTROLLABLE } else all
        val q = normalize(query)
        pool.filter { normalize(it.name) == q || it.id == query.trim() }.let { if (it.isNotEmpty()) return it }
        val words = q.split(" ").filter { it.length > 1 && it !in FILLER }
        if (words.isEmpty()) return emptyList()
        val holding = pool.filter { device ->
            val name = normalize(device.name) + " " + device.id.replace('_', ' ').replace('.', ' ')
            words.all { name.contains(it) }
        }
        if (holding.isNotEmpty()) return holding
        // Doing something to a device on a partial guess is how "open the
        // garage door" unlocks the front door. Only reading may guess.
        if (controllable) return emptyList()
        // Fall back to the device whose name shares the most words.
        return pool.map { it to words.count { w -> normalize(it.name).contains(w) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(1)
            .map { it.first }
    }

    private fun describe(device: Device): String {
        val unit = device.attributes.optString("unit_of_measurement")
        return when (device.domain) {
            "climate" -> "${device.name}: ${device.state}, " +
                "now ${device.attributes.opt("current_temperature") ?: "?"}°, " +
                "set to ${device.attributes.opt("temperature") ?: "?"}°"
            "light" -> if (device.state == "on") {
                val pct = device.attributes.optInt("brightness", -1).takeIf { it >= 0 }?.let { it * 100 / 255 }
                "${device.name}: on" + (pct?.let { " at $it%" } ?: "")
            } else {
                "${device.name}: off"
            }
            else -> "${device.name}: ${device.state}${if (unit.isNotBlank()) " $unit" else ""}"
        }
    }

    private fun describeAction(action: String, value: Double?): String = when (action) {
        "on" -> "switched on"
        "off" -> "switched off"
        "toggle" -> "toggled"
        "set_brightness" -> "brightness ${value?.toInt()}%"
        "set_temperature" -> "set to ${value}°"
        "set_position" -> "position ${value?.toInt()}%"
        "set_volume" -> "volume ${value?.toInt()}%"
        else -> action.replace('_', ' ')
    }

    private fun request(base: String, token: String, path: String, body: JSONObject?): String {
        val url = base.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" } + path
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Content-Type", "application/json")
        if (body != null) {
            builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when (response.code) {
                in 200..299 -> return text
                401 -> error("the token was refused (401)")
                404 -> error("Home Assistant does not know that (404)")
                else -> error("HTTP ${response.code} ${text.take(120)}")
            }
        }
    }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private companion object {
        const val MAX_AT_ONCE = 20
        val TOGGLEABLE = setOf("light", "switch", "fan", "input_boolean", "media_player", "humidifier", "siren")
        val CONTROLLABLE = TOGGLEABLE + setOf("cover", "lock", "climate", "scene", "script", "button", "input_button")
        val INTERESTING = CONTROLLABLE + setOf("sensor", "binary_sensor", "weather", "person", "alarm_control_panel")
        val FILLER = setOf("the", "my", "all", "every", "in", "on", "off", "der", "die", "das", "im", "alle")
        val ROOM_FILLER = setOf(
            "all", "every", "the", "in", "on", "of", "at", "my", "alle", "alles", "im",
            "der", "die", "das", "den", "dem", "und", "and"
        )
        val DOMAIN_WORDS = mapOf(
            "light" to "light", "lamp" to "light", "licht" to "light", "lampe" to "light",
            "switch" to "switch", "plug" to "switch", "steckdose" to "switch",
            "blind" to "cover", "shutter" to "cover", "curtain" to "cover", "rollo" to "cover",
            "lock" to "lock", "schloss" to "lock", "fan" to "fan", "ventilator" to "fan"
        )
    }
}
