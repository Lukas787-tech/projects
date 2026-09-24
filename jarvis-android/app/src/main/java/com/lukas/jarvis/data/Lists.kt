package com.lukas.jarvis.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class ListItem(
    val text: String,
    val done: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

/** A named list — shopping, packing, films to watch — in the order things were added. */
data class NamedList(val name: String, val items: List<ListItem> = emptyList()) {
    val open: List<ListItem> get() = items.filter { !it.done }
}

/**
 * The lists themselves, as plain values: every change returns a new book.
 *
 * Kept apart from where they are stored so the rules — one "milk" per list,
 * "the shopping list" and "Einkaufsliste" being the same list, ticking off
 * "eggs" when the list says "free-range eggs" — can be tested without a phone.
 */
data class ListBook(val lists: List<NamedList> = emptyList()) {

    fun find(name: String): NamedList? {
        val key = canonical(name)
        if (key.isBlank()) return null
        return lists.firstOrNull { it.name == key }
    }

    /** Adds what is not there yet; an item already ticked off comes back unticked. */
    fun add(name: String, texts: List<String>): ListBook {
        val key = canonical(name).ifBlank { return this }
        val clean = texts.map { it.trim().trimEnd('.', ',') }.filter { it.isNotBlank() }
        val current = find(key) ?: NamedList(key)
        var items = current.items
        clean.forEach { text ->
            val existing = items.indexOfFirst { it.text.equals(text, ignoreCase = true) }
            items = if (existing >= 0) {
                items.toMutableList().also { it[existing] = it[existing].copy(done = false) }
            } else {
                items + ListItem(text)
            }
        }
        return withList(current.copy(items = items))
    }

    fun remove(name: String, texts: List<String>): Pair<ListBook, List<String>> {
        val list = find(name) ?: return this to emptyList()
        val gone = texts.mapNotNull { match(list, it) }.distinct()
        return withList(list.copy(items = list.items - gone.toSet())) to gone.map { it.text }
    }

    fun check(name: String, texts: List<String>, done: Boolean): Pair<ListBook, List<String>> {
        val list = find(name) ?: return this to emptyList()
        val hits = texts.mapNotNull { match(list, it) }.toSet()
        val items = list.items.map { if (it in hits) it.copy(done = done) else it }
        return withList(list.copy(items = items)) to hits.map { it.text }
    }

    fun clear(name: String, onlyDone: Boolean): ListBook {
        val list = find(name) ?: return this
        return withList(list.copy(items = if (onlyDone) list.items.filter { !it.done } else emptyList()))
    }

    fun delete(name: String): ListBook {
        val key = canonical(name)
        return copy(lists = lists.filterNot { it.name == key })
    }

    private fun withList(list: NamedList): ListBook {
        val index = lists.indexOfFirst { it.name == list.name }
        return copy(lists = if (index >= 0) lists.toMutableList().also { it[index] = list } else lists + list)
    }

    /** Exactly, then a word match either way: "eggs" finds "free-range eggs". */
    private fun match(list: NamedList, text: String): ListItem? {
        val wanted = text.trim().lowercase(Locale.ROOT)
        if (wanted.isBlank()) return null
        return list.items.firstOrNull { it.text.lowercase(Locale.ROOT) == wanted }
            ?: list.items.firstOrNull {
                val have = it.text.lowercase(Locale.ROOT)
                have.contains(wanted) || wanted.contains(have)
            }
    }

    fun toJson(): String = JSONArray().apply {
        lists.forEach { list ->
            put(JSONObject().put("name", list.name).put("items", JSONArray().apply {
                list.items.forEach { put(JSONObject().put("t", it.text).put("d", it.done).put("a", it.addedAt)) }
            }))
        }
    }.toString()

    companion object {
        fun fromJson(text: String?): ListBook = runCatching {
            val array = JSONArray(text ?: "[]")
            ListBook((0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val items = obj.optJSONArray("items") ?: JSONArray()
                NamedList(
                    obj.optString("name"),
                    (0 until items.length()).mapNotNull { j ->
                        val item = items.optJSONObject(j) ?: return@mapNotNull null
                        ListItem(item.optString("t"), item.optBoolean("d"), item.optLong("a"))
                    }.filter { it.text.isNotBlank() }
                )
            }.filter { it.name.isNotBlank() })
        }.getOrDefault(ListBook())

        /**
         * One name per list however it is said: "my shopping list",
         * "groceries" and "Einkaufsliste" are all "shopping".
         */
        fun canonical(raw: String): String {
            var text = raw.trim().lowercase(Locale.ROOT)
            listOf("my ", "the ", "our ", "meine ", "die ", "unsere ").forEach { text = text.removePrefix(it) }
            text = text.removeSuffix(" list").removeSuffix("liste").removeSuffix("-").trim()
            return when (text) {
                "shopping", "grocery", "groceries", "einkauf", "einkaufs", "einkaufen", "supermarket" -> "shopping"
                "to do", "todo", "to-do", "aufgaben" -> "to-do"
                "pack", "packing" -> "packing"
                else -> text
            }
        }
    }
}

/** The lists, kept on the phone and watched by the screen. */
class Lists(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private val _book = MutableStateFlow(ListBook.fromJson(prefs.getString(KEY, null)))
    val book: StateFlow<ListBook> = _book.asStateFlow()

    val current: ListBook get() = _book.value

    /** Applies a change and stores the result; one writer at a time. */
    @Synchronized
    fun change(transform: (ListBook) -> ListBook): ListBook {
        val next = transform(_book.value)
        prefs.edit().putString(KEY, next.toJson()).apply()
        _book.value = next
        return next
    }

    /** Reads the store again, after a restore wrote it. */
    fun reload() {
        _book.value = ListBook.fromJson(prefs.getString(KEY, null))
    }

    companion object {
        const val STORE = "jarvis_lists"
        private const val KEY = "lists"
    }
}
