package com.lukas.jarvis.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything worth keeping, as one file the user owns.
 *
 * API keys are the only thing in this app that cannot be recreated by pressing
 * buttons: losing them means going back to five provider dashboards. Android's
 * own cloud backup usually restores them on reinstall, but "usually" depends on
 * a Google account, on backup being switched on, and on a restore window that
 * only opens during first setup — none of which the app can see or promise. So
 * there is also a file, written wherever the user points it, which works with
 * none of that.
 *
 * Since version 2 it also carries the database — every memory, task, tracker,
 * entry and the conversation — because those are what make Jarvis the user's
 * own, and a reinstall that brings back the keys but forgets who they are is
 * only half a restore.
 *
 * It copies the preference stores wholesale rather than a hand-listed set of
 * fields. Keys and endpoints are stored namespaced per provider, so a list of
 * fields would silently miss every provider nobody thought to enumerate; the
 * whole store cannot.
 *
 * The file is plain text. Anything else would need a passphrase to unlock, and
 * a backup that cannot be opened because a passphrase was forgotten is not a
 * backup. It holds working API keys, so it belongs somewhere private.
 */
object Vault {

    private const val VERSION = 2
    private val STORES = listOf("jarvis_settings", "jarvis_pool", "jarvis_places", "jarvis_routines", "jarvis_lists")

    /** The current state of every store, as text to write to a file. */
    fun export(context: Context, brain: com.lukas.jarvis.data.Brain? = null): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("app", "jarvis")

        val stores = JSONObject()
        STORES.forEach { name ->
            val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = JSONObject()
            prefs.all.forEach { (key, value) -> entries.put(key, encode(value)) }
            stores.put(name, entries)
        }
        root.put("stores", stores)
        brain?.let { root.put("tables", it.exportTables()) }
        return root.toString(2)
    }

    /** How much came back, or why nothing did. */
    sealed interface Result {
        data class Restored(val keys: Int, val rows: Int, val exportedAt: Long) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Writes a backup back into the preference stores.
     *
     * Restoring replaces a store rather than merging into it: a half-merged set
     * of provider keys, some from the backup and some from whatever was typed
     * since, is a state nobody asked for and nobody can reason about.
     */
    fun import(context: Context, text: String, brain: com.lukas.jarvis.data.Brain? = null): Result {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return Result.Failed("That file is not a Jarvis backup.")
        if (root.optString("app") != "jarvis") {
            return Result.Failed("That file is not a Jarvis backup.")
        }
        if (root.optInt("version") > VERSION) {
            return Result.Failed("That backup was written by a newer version of Jarvis.")
        }
        val stores = root.optJSONObject("stores")
            ?: return Result.Failed("That backup has nothing in it.")

        // The database first: it is the part that can fail halfway, and it is
        // all or nothing, so a bad file leaves the settings untouched too.
        val tables = root.optJSONObject("tables")
        val rows = if (tables != null && brain != null) {
            runCatching { brain.importTables(tables) }.getOrElse {
                return Result.Failed("The backup's memories could not be read back: ${it.message}")
            }
        } else {
            0
        }

        var restored = 0
        STORES.forEach store@{ name ->
            val entries = stores.optJSONObject(name) ?: return@store
            val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.clear()
            entries.keys().forEach cell@{ key ->
                val cell = entries.optJSONObject(key) ?: return@cell
                if (write(editor, key, cell)) restored++
            }
            editor.apply()
        }

        if (restored == 0 && rows == 0) return Result.Failed("That backup has nothing in it.")
        return Result.Restored(restored, rows, root.optLong("exportedAt"))
    }

    /** A suggested filename, dated so successive backups do not overwrite. */
    fun fileName(): String {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        return "jarvis-backup-$day.json"
    }

    // Preferences are typed, and a round trip through JSON loses that unless the
    // type travels with the value: putString where putInt was expected throws on
    // the next read, long after the restore that caused it.
    private fun encode(value: Any?): JSONObject = JSONObject().apply {
        when (value) {
            is String -> { put("t", "s"); put("v", value) }
            is Boolean -> { put("t", "b"); put("v", value) }
            is Int -> { put("t", "i"); put("v", value) }
            is Long -> { put("t", "l"); put("v", value) }
            is Float -> { put("t", "f"); put("v", value.toDouble()) }
            is Set<*> -> {
                put("t", "ss")
                put("v", JSONArray().apply { value.forEach { put(it.toString()) } })
            }
            else -> { put("t", "s"); put("v", value?.toString().orEmpty()) }
        }
    }

    private fun write(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        cell: JSONObject
    ): Boolean {
        when (cell.optString("t")) {
            "s" -> editor.putString(key, cell.optString("v"))
            "b" -> editor.putBoolean(key, cell.optBoolean("v"))
            "i" -> editor.putInt(key, cell.optInt("v"))
            "l" -> editor.putLong(key, cell.optLong("v"))
            "f" -> editor.putFloat(key, cell.optDouble("v").toFloat())
            "ss" -> {
                val array = cell.optJSONArray("v") ?: JSONArray()
                val set = (0 until array.length()).map { array.optString(it) }.toSet()
                editor.putStringSet(key, set)
            }
            else -> return false
        }
        return true
    }
}
