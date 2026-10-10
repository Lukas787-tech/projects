package com.lukas.jarvis.data

import org.json.JSONArray
import org.json.JSONObject

/** One memory put away, why, and the one kept in its place when it was a repeat. */
data class Tidied(
    val memoryId: Long,
    val reason: Upkeep.Reason,
    val keptId: Long? = null,
    val at: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", memoryId)
        .put("reason", reason.name)
        .put("at", at)
        .apply { keptId?.let { put("kept", it) } }

    companion object {
        fun fromJson(obj: JSONObject): Tidied? {
            val reason = runCatching { Upkeep.Reason.valueOf(obj.optString("reason")) }.getOrNull() ?: return null
            return Tidied(obj.optLong("id"), reason, if (obj.has("kept")) obj.optLong("kept") else null, obj.optLong("at"))
        }

        fun listFromJson(raw: String?): List<Tidied> = runCatching {
            val array = JSONArray(raw ?: "[]")
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())

        fun listToJson(all: List<Tidied>): String = JSONArray().apply { all.forEach { put(it.toJson()) } }.toString()
    }
}

/**
 * Memory upkeep: overnight, the memories that only get in the way are put
 * away, never deleted. A thing said twice keeps its newest, most important
 * telling; a plan for a day long gone is put away a month after. Nothing
 * pinned is touched, and nothing written in someone's own words (notes, the
 * journal) ever is. Everything put away is listed in the Library with a way
 * to bring it back.
 *
 * Summing old conversations up into memories would need a model, and is not
 * done here.
 */
object Upkeep {

    enum class Reason(val said: String) {
        Repeat("said twice"),
        Past("about a day that has gone")
    }

    /** A plan or appointment this long after its day is history. */
    private const val PAST_AFTER_MS = 30L * 86_400_000L

    /** No more than this in one night, so a surprise is small. */
    const val MOST_A_NIGHT = 20

    private val UNTOUCHED = setOf(Memory.KIND_NOTE, Memory.KIND_JOURNAL)

    fun plan(memories: List<Memory>, now: Long): List<Tidied> {
        val candidates = memories.filter { !it.archived && it.kind !in UNTOUCHED }
        val away = linkedMapOf<Long, Tidied>()

        // Repeats: the same kind, saying the same thing.
        val byKind = candidates.groupBy { it.kind }
        byKind.values.forEach { group ->
            val tokens = group.associate { it.id to Tokenizer.tokenize(it.content).toSet() }
            val sorted = group.sortedWith(compareByDescending<Memory> { it.pinned }.thenByDescending { it.importance }.thenByDescending { it.updatedAt })
            sorted.forEachIndexed { index, keep ->
                if (keep.id in away) return@forEachIndexed
                sorted.drop(index + 1).forEach { other ->
                    if (other.pinned || other.id in away) return@forEach
                    if (same(keep.content, other.content, tokens[keep.id].orEmpty(), tokens[other.id].orEmpty())) {
                        away[other.id] = Tidied(other.id, Reason.Repeat, keptId = keep.id, at = now)
                    }
                }
            }
        }

        // Past: an event whose day is a month gone, unless it was marked as mattering.
        candidates.filter { memory ->
            memory.kind == Memory.KIND_EVENT && !memory.pinned && memory.importance <= 3 &&
                memory.occurredAt?.let { now - it > PAST_AFTER_MS } == true && memory.id !in away
        }.forEach { away[it.id] = Tidied(it.id, Reason.Past, at = now) }

        return away.values.take(MOST_A_NIGHT)
    }

    /**
     * The same thing said twice: the same words once case and punctuation go,
     * the same meaningful words, or for longer ones nine tenths of them.
     * "Anna likes tea" and "Anna likes coffee" are not one memory, and nor are
     * two locker codes: which one is right is not for upkeep to guess.
     */
    fun same(a: String, b: String, ta: Set<String> = Tokenizer.tokenize(a).toSet(), tb: Set<String> = Tokenizer.tokenize(b).toSet()): Boolean {
        val plainA = a.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val plainB = b.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        if (plainA.isNotEmpty() && plainA == plainB) return true
        // The same meaningful words, whatever was around them: "my locker code is 3917", "Locker code: 3917".
        if (ta.size >= 2 && ta == tb) return true
        if (ta.size < 4 || tb.size < 4) return false
        val shared = ta.intersect(tb).size.toDouble()
        val all = ta.union(tb).size.toDouble()
        return shared / all >= 0.9
    }
}
