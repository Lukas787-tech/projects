package com.lukas.jarvis.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** The saved profiles, on the phone and in backups. */
class ProfileStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<Profile>> = _all.asStateFlow()

    val current: List<Profile> get() = _all.value

    /** Saving under a name that exists replaces it. */
    fun save(profile: Profile) {
        write(current.filterNot { it.name.equals(profile.name, ignoreCase = true) } + profile)
    }

    fun remove(name: String): Boolean {
        val target = Profile.match(current, name) ?: return false
        write(current - target)
        return true
    }

    fun find(name: String): Profile? = Profile.match(current, name)

    fun reload() {
        _all.value = read()
    }

    private fun read(): List<Profile> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Profile::fromJson) }
    }.getOrDefault(emptyList())

    private fun write(all: List<Profile>) {
        prefs.edit().putString(KEY, JSONArray().apply { all.forEach { put(it.toJson()) } }.toString()).apply()
        _all.value = all
    }

    companion object {
        const val STORE = "jarvis_profiles"
        private const val KEY = "profiles"
    }
}
