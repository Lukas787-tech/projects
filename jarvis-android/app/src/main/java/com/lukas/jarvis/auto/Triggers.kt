package com.lukas.jarvis.auto

import java.util.Locale

/**
 * Something the phone notices that can start a routine, besides a time or a
 * place: it starts charging, a Bluetooth device (the car, the headphones)
 * connects or goes, a calendar event begins.
 *
 * Kept as plain words so a routine stores it as one short string, and so the
 * matching ("car" is "VW Car Audio", "my AirPods" is "Lukas's AirPods Pro")
 * can be tested without a phone.
 */
data class Trigger(val kind: Kind, val device: String? = null) {

    enum class Kind(val word: String) {
        Charging("charging"),
        Connected("connected"),
        Disconnected("disconnected"),
        EventStarts("event")
    }

    /** "when the phone starts charging", "when the car connects". */
    val describe: String
        get() = when (kind) {
            Kind.Charging -> "when the phone starts charging"
            Kind.Connected -> device?.let { "when ${the(it)} connects" } ?: "when a Bluetooth device connects"
            Kind.Disconnected -> device?.let { "when ${the(it)} disconnects" } ?: "when a Bluetooth device disconnects"
            Kind.EventStarts -> "when a calendar event starts"
        }

    fun encode(): String = kind.word + (device?.let { ":$it" } ?: "")

    /** Whether what just happened, with [deviceName] for Bluetooth, is this trigger. */
    fun matches(happened: Kind, deviceName: String? = null): Boolean {
        if (happened != kind) return false
        val wanted = device ?: return true
        val name = deviceName?.lowercase(Locale.ROOT) ?: return false
        val words = words(wanted)
        return words.isNotEmpty() && words.all { name.contains(it) }
    }

    companion object {
        fun decode(raw: String?): Trigger? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val kind = Kind.entries.firstOrNull { it.word == text.substringBefore(':') } ?: return null
            return Trigger(kind, text.substringAfter(':', "").trim().takeIf { it.isNotEmpty() })
        }

        /**
         * From what was said: "charging", "plugged in", "when the car connects",
         * "bluetooth disconnects", "a meeting starts". Null when it is none of them.
         */
        fun parse(raw: String?, device: String? = null): Trigger? {
            val text = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            val named = device?.trim()?.takeIf { it.isNotEmpty() }
            val kind = when {
                Regex("charg|plug|lade|strom").containsMatchIn(text) -> Kind.Charging
                Regex("disconnect|leave|leaves|lost|off|getrennt|trenn").containsMatchIn(text) -> Kind.Disconnected
                Regex("connect|bluetooth|pair|verbunden|verbind|headphone|earbud|car|auto").containsMatchIn(text) -> Kind.Connected
                Regex("meeting|event|appointment|calendar|termin|besprechung").containsMatchIn(text) -> Kind.EventStarts
                else -> return null
            }
            val bluetooth = kind == Kind.Connected || kind == Kind.Disconnected
            return Trigger(kind, if (bluetooth) named?.let(::clean) else null)
        }

        /** "my AirPods" -> "airpods"; "the car" -> "car". */
        private fun clean(name: String): String? =
            words(name).joinToString(" ").takeIf { it.isNotEmpty() }

        private fun words(name: String): List<String> =
            name.lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotBlank() && it !in FILLER }

        private fun the(device: String): String = if (device.first().isUpperCase()) device else "the $device"

        private val FILLER = setOf("my", "the", "a", "an", "mein", "meine", "die", "der", "das", "bluetooth", "device")
    }
}
