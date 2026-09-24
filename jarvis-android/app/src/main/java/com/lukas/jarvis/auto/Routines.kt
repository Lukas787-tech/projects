package com.lukas.jarvis.auto

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * A few things said once and done together whenever asked: "good morning" is
 * the weather, the day, and the news on the radio.
 *
 * Each step is a sentence, exactly what would have been said out loud. That is
 * what makes a routine easy to make by voice and impossible to break by an app
 * update: nothing here names a tool, so any step the assistant can do when
 * asked, it can do as part of a routine.
 */
data class Routine(
    val name: String,
    val steps: List<String>,
    /** "07:00" for a daily nudge, or null to run only when asked. */
    val time: String? = null,
    val lastRunAt: Long = 0L
) {
    val hour: Int? get() = time?.substringBefore(':')?.toIntOrNull()?.takeIf { it in 0..23 }
    val minute: Int? get() = time?.substringAfter(':', "")?.toIntOrNull()?.takeIf { it in 0..59 }
}

class Routines(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jarvis_routines", Context.MODE_PRIVATE)

    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<Routine>> = _all.asStateFlow()

    init {
        ensureChannel()
    }

    fun save(routine: Routine): Routine {
        val clean = routine.copy(
            name = routine.name.trim(),
            steps = routine.steps.map { it.trim() }.filter { it.isNotBlank() },
            time = routine.time?.let { normalizeTime(it) }
        )
        write(_all.value.filterNot { it.name.equals(clean.name, ignoreCase = true) } + clean)
        schedule(clean)
        return clean
    }

    fun remove(name: String): Boolean {
        val target = find(name) ?: return false
        cancel(target)
        write(_all.value - target)
        return true
    }

    fun find(text: String?): Routine? {
        val wanted = text?.trim()?.lowercase(Locale.ROOT)?.removePrefix("my ")?.removeSuffix(" routine")
            ?.trim().orEmpty()
        if (wanted.isBlank()) return null
        return _all.value.firstOrNull { it.name.lowercase(Locale.ROOT) == wanted }
            ?: _all.value.firstOrNull {
                wanted.contains(it.name.lowercase(Locale.ROOT)) ||
                    it.name.lowercase(Locale.ROOT).contains(wanted)
            }
    }

    fun markRun(name: String) {
        val target = find(name) ?: return
        write(_all.value.map { if (it === target) it.copy(lastRunAt = System.currentTimeMillis()) else it })
    }

    /** Alarms do not survive a reboot or a reinstall; this puts them back. */
    fun rescheduleAll() {
        _all.value.forEach { schedule(it) }
    }

    // ------------------------------------------------------------- scheduling

    /**
     * A timed routine does not run by itself in the background: a turn needs
     * the network, the microphone and often the screen, and Android gives a
     * background app none of those reliably. What arrives at the time is a
     * notification, and one tap runs the whole routine.
     */
    fun schedule(routine: Routine) {
        cancel(routine)
        val hour = routine.hour ?: return
        val minute = routine.minute ?: 0
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(routine.name))
        }
    }

    private fun cancel(routine: Routine) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { manager.cancel(pending(routine.name)) }
    }

    private fun pending(name: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        name.lowercase(Locale.ROOT).hashCode(),
        Intent(context, RoutineReceiver::class.java).apply {
            action = ACTION_DUE
            data = Uri.parse("jarvis://routine/${Uri.encode(name.lowercase(Locale.ROOT))}")
            putExtra(EXTRA_NAME, name)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Routines", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "A routine's time has come; tap to run it" }
        )
    }

    // ---------------------------------------------------------------- storage

    private fun read(): List<Routine> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val steps = obj.optJSONArray("steps") ?: JSONArray()
            Routine(
                name = obj.optString("name"),
                steps = (0 until steps.length()).map { steps.optString(it) }.filter { it.isNotBlank() },
                time = obj.optString("time").takeIf { it.isNotBlank() },
                lastRunAt = obj.optLong("last")
            )
        }.filter { it.name.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun write(routines: List<Routine>) {
        val array = JSONArray()
        routines.forEach { routine ->
            array.put(
                JSONObject().apply {
                    put("name", routine.name)
                    put("steps", JSONArray(routine.steps))
                    routine.time?.let { put("time", it) }
                    put("last", routine.lastRunAt)
                }
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        _all.value = routines
    }

    companion object {
        const val CHANNEL = "jarvis_routines"
        const val ACTION_DUE = "com.lukas.jarvis.ROUTINE_DUE"
        const val EXTRA_NAME = "routine"
        private const val KEY = "routines"

        /** "7", "7:5", "07.30", "7 am", "19 uhr" -> "HH:MM", or null when it is not a time. */
        fun normalizeTime(raw: String): String? {
            val text = raw.trim().lowercase(Locale.ROOT)
            if (text.isBlank() || text in setOf("none", "never", "no", "off")) return null
            val match = Regex("(\\d{1,2})(?:[:.](\\d{1,2}))?\\s*(am|pm|uhr)?").find(text) ?: return null
            var hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            when (match.groupValues[3]) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            return String.format(Locale.US, "%02d:%02d", hour, minute)
        }
    }
}
