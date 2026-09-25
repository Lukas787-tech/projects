package com.lukas.jarvis.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.time.LocalDate
import java.util.Locale

/** The days being counted down to, on the phone and in backups. */
class CountdownStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<Countdown>> = _all.asStateFlow()

    val current: List<Countdown> get() = _all.value

    /** A name that exists is moved to the new day rather than counted twice. */
    @Synchronized
    fun add(name: String, date: LocalDate, yearly: Boolean, knowsYear: Boolean, birthday: Boolean): Countdown {
        val old = find(name)?.takeIf { it.name.equals(name.trim(), ignoreCase = true) }
        val id = old?.id ?: ((current.maxOfOrNull { it.id } ?: 0L) + 1)
        val made = Countdown(id, name.trim(), date, yearly, knowsYear, birthday)
        write(current.filterNot { it.id == id } + made)
        return made
    }

    @Synchronized
    fun remove(name: String): Countdown? {
        val target = find(name) ?: return null
        write(current - target)
        return target
    }

    @Synchronized
    fun removeId(id: Long) {
        write(current.filterNot { it.id == id })
    }

    /** "mum's birthday", "the holiday", "Holiday" -> the one it means; one loose match at most. */
    fun find(name: String): Countdown? {
        val key = name.trim().lowercase(Locale.ROOT).removePrefix("the ").removePrefix("my ")
            .removeSuffix("'s birthday").removeSuffix("s birthday").removeSuffix(" birthday").trim()
        if (key.isBlank()) return null
        return current.firstOrNull { it.name.lowercase(Locale.ROOT) == key }
            ?: current.filter { it.name.lowercase(Locale.ROOT).contains(key) || key.contains(it.name.lowercase(Locale.ROOT)) }
                .singleOrNull()
    }

    fun reload() {
        _all.value = read()
    }

    private fun read(): List<Countdown> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Countdown::fromJson) }
    }.getOrDefault(emptyList())

    private fun write(all: List<Countdown>) {
        prefs.edit().putString(KEY, JSONArray().apply { all.forEach { put(it.toJson()) } }.toString()).apply()
        _all.value = all
    }

    companion object {
        const val STORE = "jarvis_countdowns"
        private const val KEY = "countdowns"
    }
}
