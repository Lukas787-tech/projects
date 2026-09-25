package com.lukas.jarvis.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.Locale

/**
 * The saved profiles, on the phone and in backups — and the alarms that
 * switch to one by itself, "Night" at 22:00 and "Work" on weekdays at 8.
 * Those need no model and no signal: the switch is done on the phone.
 */
class ProfileStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<Profile>> = _all.asStateFlow()

    val current: List<Profile> get() = _all.value

    /**
     * Saving under a name that exists replaces its look, character and voice;
     * the time it switches on by itself stays, unless [profile] sets one.
     */
    @Synchronized
    fun save(profile: Profile) {
        val old = current.firstOrNull { it.name.equals(profile.name, ignoreCase = true) }
        val kept = if (old != null && profile.autoAt.isBlank()) {
            profile.copy(autoAt = old.autoAt, autoDays = old.autoDays)
        } else {
            profile
        }
        write(current.map { if (it === old) kept else it }.let { if (old == null) it + kept else it })
    }

    /** Sets or, with a blank [at], clears when [name] switches on by itself. */
    @Synchronized
    fun schedule(name: String, at: String, days: Set<Int>): Profile? {
        val target = find(name) ?: return null
        val updated = target.copy(autoAt = at, autoDays = if (at.isBlank()) emptySet() else days)
        write(current.map { if (it === target) updated else it })
        return updated
    }

    @Synchronized
    fun remove(name: String): Boolean {
        val target = Profile.match(current, name) ?: return false
        cancel(target)
        write(current - target)
        return true
    }

    fun find(name: String): Profile? = Profile.match(current, name)

    fun reload() {
        current.forEach { cancel(it) }
        _all.value = read()
        rescheduleAll()
    }

    /** Alarms do not survive a reboot or an update; this puts them back. */
    fun rescheduleAll() {
        current.forEach { arm(it) }
    }

    /** Called by the alarm: the profile [name], if it still exists and still has a time. */
    internal fun due(name: String): Profile? = current.firstOrNull { it.name.equals(name, ignoreCase = true) }

    internal fun arm(profile: Profile) {
        cancel(profile)
        val at = profile.nextSwitch(System.currentTimeMillis()) ?: return
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(profile.name)) }
    }

    private fun cancel(profile: Profile) {
        runCatching { app.getSystemService(AlarmManager::class.java)?.cancel(pending(profile.name)) }
    }

    private fun pending(name: String): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_BASE + (name.lowercase(Locale.ROOT).hashCode() and 0xFFFF),
        Intent(app, ProfileSwitchReceiver::class.java)
            .setAction(ACTION_SWITCH)
            .setData(Uri.parse("jarvis://profile/${Uri.encode(name.lowercase(Locale.ROOT))}"))
            .putExtra(EXTRA_NAME, name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun read(): List<Profile> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Profile::fromJson) }
    }.getOrDefault(emptyList())

    private fun write(all: List<Profile>) {
        prefs.edit().putString(KEY, JSONArray().apply { all.forEach { put(it.toJson()) } }.toString()).apply()
        _all.value = all
        all.forEach { arm(it) }
    }

    companion object {
        const val STORE = "jarvis_profiles"
        private const val KEY = "profiles"
        private const val REQUEST_BASE = 300_000
        const val ACTION_SWITCH = "com.lukas.jarvis.PROFILE_SWITCH"
        const val EXTRA_NAME = "name"
    }
}

/** A profile's time has come: it is switched on, and the next day's alarm set. */
class ProfileSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? com.lukas.jarvis.JarvisApp)?.container ?: return
        val name = intent.getStringExtra(ProfileStore.EXTRA_NAME) ?: return
        val profile = container.profiles.due(name) ?: return
        if (profile.autoAt.isBlank()) return
        container.settings.update { profile.applyTo(it) }
        container.profiles.arm(profile)
    }
}
