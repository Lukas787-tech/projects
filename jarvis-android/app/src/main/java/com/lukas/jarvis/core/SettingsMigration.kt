package com.lukas.jarvis.core

import java.util.Locale

/**
 * Brings settings saved by an older version up to date, once.
 *
 * 6.0 renames the assistant from Jarvis to Mochi. Someone who never touched
 * the name gets the new one; someone who chose their own keeps it. The same
 * goes for the wake phrase and the character. The retired HUD colours map to
 * the nearest café ones, and a colour picked from the rainbow slider stays
 * exactly as it was.
 *
 * Pure: it reads a snapshot of the stored values and returns what to write,
 * so the rules are tested without a phone.
 */
object SettingsMigration {

    const val VERSION = 2
    const val KEY = "settings_version"

    const val OLD_NAME = "Jarvis"
    const val NEW_NAME = "Mochi"
    const val NEW_WAKE = "hey mochi"
    const val NEW_PERSONA = "mochi"

    /** The 5.5 accent ids and the café colour each one becomes. */
    val ACCENTS: Map<String, String> = mapOf(
        "arc" to "caramel",
        "stark" to "honey",
        "crimson" to "berry",
        "emerald" to "sage",
        "violet" to "lavender",
        "solar" to "apricot",
        "rose" to "rose",
        "silver" to "stone"
    )

    /** The writes that bring [stored] up to date; empty when it already is. */
    fun changes(stored: Map<String, Any?>): Map<String, Any?> {
        val version = (stored[KEY] as? Int) ?: 1
        if (version >= VERSION) return emptyMap()
        val out = linkedMapOf<String, Any?>(KEY to VERSION)

        val name = (stored["assistant_name"] as? String)?.trim()
        val nameUntouched = name == null || name.equals(OLD_NAME, ignoreCase = true)
        if (name != null && name.equals(OLD_NAME, ignoreCase = true)) out["assistant_name"] = NEW_NAME

        val phrase = (stored["wake_phrase"] as? String)?.trim()?.lowercase(Locale.ROOT)
        if (phrase == "jarvis" || phrase == "hey jarvis") out["wake_phrase"] = NEW_WAKE

        // The butler was the old default character. Kept by someone who also
        // kept the old name, it was never really chosen.
        if (nameUntouched && stored["personality"] == "jarvis") out["personality"] = NEW_PERSONA

        (stored["accent"] as? String)?.let { accent -> ACCENTS[accent]?.let { out["accent"] = it } }

        // The map followed the dark HUD; now it follows the theme.
        if (stored["map_style"] == "dark") out["map_style"] = "auto"

        return out
    }
}
