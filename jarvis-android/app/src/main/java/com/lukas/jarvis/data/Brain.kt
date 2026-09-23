package com.lukas.jarvis.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.Calendar
import kotlin.math.ln
import kotlin.math.max

/**
 * Everything Jarvis knows, and the only class that touches SQLite.
 *
 * All calls are synchronous and are expected to be made off the main thread;
 * the view model wraps them in `withContext(Dispatchers.IO)`.
 */
class Brain(context: Context) {

    private val helper = Db(context.applicationContext)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---------------------------------------------------------------- memories

    fun addMemory(memory: Memory): Long {
        val tf = Tokenizer.termFrequencies(indexableText(memory))
        val values = ContentValues().apply {
            put("kind", memory.kind)
            put("content", memory.content)
            put("detail", memory.detail)
            put("tags", memory.tags.joinToString(","))
            put("importance", memory.importance.coerceIn(1, 5))
            put("pinned", if (memory.pinned) 1 else 0)
            put("archived", if (memory.archived) 1 else 0)
            put("created_at", memory.createdAt)
            put("updated_at", memory.updatedAt)
            put("occurred_at", memory.occurredAt)
            put("source", memory.source)
            put("token_count", tf.values.sum())
        }
        val id = db.insert("memories", null, values)
        if (id > 0) writeTokens(id, tf)
        return id
    }

    fun updateMemory(memory: Memory): Boolean {
        if (memory.id <= 0) return false
        val tf = Tokenizer.termFrequencies(indexableText(memory))
        val values = ContentValues().apply {
            put("kind", memory.kind)
            put("content", memory.content)
            put("detail", memory.detail)
            put("tags", memory.tags.joinToString(","))
            put("importance", memory.importance.coerceIn(1, 5))
            put("pinned", if (memory.pinned) 1 else 0)
            put("archived", if (memory.archived) 1 else 0)
            put("updated_at", System.currentTimeMillis())
            put("occurred_at", memory.occurredAt)
            put("token_count", tf.values.sum())
        }
        val rows = db.update("memories", values, "id = ?", arrayOf(memory.id.toString()))
        if (rows > 0) {
            db.delete("memory_tokens", "memory_id = ?", arrayOf(memory.id.toString()))
            writeTokens(memory.id, tf)
        }
        return rows > 0
    }

    fun deleteMemory(id: Long): Boolean {
        db.delete("memory_tokens", "memory_id = ?", arrayOf(id.toString()))
        return db.delete("memories", "id = ?", arrayOf(id.toString())) > 0
    }

    fun getMemory(id: Long): Memory? =
        db.rawQuery("SELECT * FROM memories WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toMemory() else null }

    fun recentMemories(limit: Int = 50, kind: String? = null): List<Memory> {
        val (where, args) = if (kind.isNullOrBlank()) {
            "archived = 0" to emptyArray<String>()
        } else {
            "archived = 0 AND kind = ?" to arrayOf(kind)
        }
        return db.rawQuery(
            "SELECT * FROM memories WHERE $where ORDER BY pinned DESC, created_at DESC LIMIT ?",
            args + limit.toString()
        ).use { it.readAll { c -> c.toMemory() } }
    }

    fun memoryCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM memories WHERE archived = 0", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * BM25 over the hand-rolled index, then nudged by importance, pinning and
     * recency so that "what did I say about the car" surfaces the recent,
     * important note rather than an old throwaway one.
     */
    fun searchMemories(query: String, limit: Int = 8, kind: String? = null): List<Memory> {
        val terms = Tokenizer.tokenize(query).distinct()
        if (terms.isEmpty()) return recentMemories(limit, kind)

        val totalDocs = memoryCount()
        if (totalDocs == 0) return emptyList()
        val avgLen = db.rawQuery("SELECT AVG(token_count) FROM memories WHERE archived = 0", null)
            .use { if (it.moveToFirst()) it.getDouble(0) else 1.0 }
            .coerceAtLeast(1.0)

        val placeholders = terms.joinToString(",") { "?" }
        // One pass gives both the per-document term frequency and, by counting
        // rows per token, that token's document frequency.
        val postings = ArrayList<Triple<String, Long, Int>>()
        db.rawQuery(
            "SELECT token, memory_id, tf FROM memory_tokens WHERE token IN ($placeholders)",
            terms.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                postings.add(Triple(c.getString(0), c.getLong(1), c.getInt(2)))
            }
        }
        if (postings.isEmpty()) return emptyList()

        val docFreq = postings.groupingBy { it.first }.eachCount()
        val docLengths = HashMap<Long, Int>()
        val candidateIds = postings.map { it.second }.distinct()
        db.rawQuery(
            "SELECT id, token_count FROM memories " +
                "WHERE archived = 0 AND id IN (${candidateIds.joinToString(",")})",
            null
        ).use { c ->
            while (c.moveToNext()) docLengths[c.getLong(0)] = max(1, c.getInt(1))
        }

        val k1 = 1.5
        val b = 0.75
        val scores = HashMap<Long, Double>()
        for ((token, memoryId, tf) in postings) {
            val len = docLengths[memoryId] ?: continue
            val df = docFreq[token] ?: 1
            val idf = ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5))
            val norm = tf * (k1 + 1) / (tf + k1 * (1 - b + b * len / avgLen))
            scores[memoryId] = (scores[memoryId] ?: 0.0) + idf * norm
        }
        if (scores.isEmpty()) return emptyList()

        val rows = db.rawQuery(
            "SELECT * FROM memories WHERE id IN (${scores.keys.joinToString(",")})" +
                if (kind.isNullOrBlank()) "" else " AND kind = '${kind.replace("'", "''")}'",
            null
        ).use { it.readAll { c -> c.toMemory() } }

        val now = System.currentTimeMillis()
        return rows.sortedByDescending { m ->
            val base = scores[m.id] ?: 0.0
            val ageDays = (now - m.createdAt).toDouble() / 86_400_000.0
            val recency = 1.0 / (1.0 + ageDays / 45.0)
            base * (1.0 + 0.12 * (m.importance - 3)) + recency * 0.8 + if (m.pinned) 2.0 else 0.0
        }.take(limit)
    }

    private fun indexableText(memory: Memory): String =
        listOfNotNull(memory.content, memory.detail, memory.tags.joinToString(" "), memory.kind)
            .joinToString(" ")

    private fun writeTokens(memoryId: Long, tf: Map<String, Int>) {
        val statement = db.compileStatement(
            "INSERT OR REPLACE INTO memory_tokens (token, memory_id, tf) VALUES (?, ?, ?)"
        )
        db.beginTransaction()
        try {
            for ((token, count) in tf) {
                statement.clearBindings()
                statement.bindString(1, token)
                statement.bindLong(2, memoryId)
                statement.bindLong(3, count.toLong())
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            statement.close()
        }
    }

    // ---------------------------------------------------------------- trackers

    fun upsertTracker(tracker: Tracker): Tracker {
        val key = normalizeName(tracker.name)
        val existing = findTracker(key)
        val values = ContentValues().apply {
            put("name", key)
            put("label", tracker.label.ifBlank { tracker.name })
            put("kind", tracker.kind)
            put("unit", tracker.unit)
            if (tracker.budget != null) put("budget", tracker.budget) else putNull("budget")
            put("period", tracker.period)
            if (tracker.startingBalance != null) {
                put("starting_balance", tracker.startingBalance)
            } else {
                putNull("starting_balance")
            }
            put("archived", if (tracker.archived) 1 else 0)
        }
        return if (existing == null) {
            values.put("created_at", System.currentTimeMillis())
            val id = db.insert("trackers", null, values)
            tracker.copy(id = id, name = key)
        } else {
            db.update("trackers", values, "id = ?", arrayOf(existing.id.toString()))
            tracker.copy(id = existing.id, name = key)
        }
    }

    fun findTracker(name: String): Tracker? {
        val key = normalizeName(name)
        db.rawQuery("SELECT * FROM trackers WHERE name = ?", arrayOf(key)).use {
            if (it.moveToFirst()) return it.toTracker()
        }
        // Spoken input rarely matches the stored key exactly ("groceries" vs
        // "grocery"), so fall back to a stem-level match before giving up.
        val stem = Tokenizer.tokenize(key).joinToString(" ")
        if (stem.isBlank()) return null
        return allTrackers().firstOrNull { Tokenizer.tokenize(it.name).joinToString(" ") == stem }
    }

    fun allTrackers(includeArchived: Boolean = false): List<Tracker> =
        db.rawQuery(
            "SELECT * FROM trackers" + (if (includeArchived) "" else " WHERE archived = 0") +
                " ORDER BY name ASC",
            null
        ).use { it.readAll { c -> c.toTracker() } }

    fun deleteTracker(id: Long): Boolean =
        db.delete("trackers", "id = ?", arrayOf(id.toString())) > 0

    fun addEntry(entry: Entry): Long {
        val values = ContentValues().apply {
            put("tracker_id", entry.trackerId)
            put("amount", entry.amount)
            put("direction", entry.direction)
            put("note", entry.note)
            put("category", entry.category)
            put("occurred_at", entry.occurredAt)
            put("created_at", entry.createdAt)
        }
        return db.insert("entries", null, values)
    }

    fun deleteEntry(id: Long): Boolean =
        db.delete("entries", "id = ?", arrayOf(id.toString())) > 0

    fun recentEntries(trackerId: Long? = null, limit: Int = 30): List<Entry> {
        val (where, args) = if (trackerId == null) {
            "1 = 1" to emptyArray<String>()
        } else {
            "tracker_id = ?" to arrayOf(trackerId.toString())
        }
        return db.rawQuery(
            "SELECT * FROM entries WHERE $where ORDER BY occurred_at DESC LIMIT ?",
            args + limit.toString()
        ).use { it.readAll { c -> c.toEntry() } }
    }

    /**
     * Every movement in a window, for the reports.
     *
     * Kept apart from [recentEntries] because a report is bounded by dates and
     * a list is bounded by a row count, and conflating the two is how a "this
     * month" answer quietly ends up including last month's tail.
     */
    fun entriesBetween(from: Long, to: Long, trackerId: Long? = null): List<Entry> {
        val where = StringBuilder("occurred_at >= ? AND occurred_at <= ?")
        val args = mutableListOf(from.toString(), to.toString())
        if (trackerId != null) {
            where.append(" AND tracker_id = ?")
            args.add(trackerId.toString())
        }
        return db.rawQuery(
            "SELECT * FROM entries WHERE $where ORDER BY occurred_at DESC",
            args.toTypedArray()
        ).use { it.readAll { c -> c.toEntry() } }
    }

    fun trackerStatus(tracker: Tracker): TrackerStatus {
        val periodStart = periodStart(tracker.period)
        var periodSpent = 0.0
        var periodReceived = 0.0
        var count = 0
        var allSpent = 0.0
        var allReceived = 0.0

        db.rawQuery(
            "SELECT direction, amount, occurred_at FROM entries WHERE tracker_id = ?",
            arrayOf(tracker.id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val outgoing = c.getString(0) == Entry.DIR_OUT
                val amount = c.getDouble(1)
                val at = c.getLong(2)
                if (outgoing) allSpent += amount else allReceived += amount
                if (at >= periodStart) {
                    count++
                    if (outgoing) periodSpent += amount else periodReceived += amount
                }
            }
        }
        return TrackerStatus(
            tracker = tracker,
            periodSpent = periodSpent,
            periodReceived = periodReceived,
            periodStart = periodStart,
            entryCount = count,
            allTimeSpent = allSpent,
            allTimeReceived = allReceived
        )
    }

    fun allTrackerStatus(): List<TrackerStatus> = allTrackers().map { trackerStatus(it) }

    /** Start of the tracker's current window, or epoch when it does not reset. */
    private fun periodStart(period: String): Long {
        if (period == Tracker.PERIOD_NONE) return 0L
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (period) {
            Tracker.PERIOD_WEEKLY -> cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
            Tracker.PERIOD_MONTHLY -> cal.set(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    private fun normalizeName(raw: String): String =
        raw.trim().lowercase().replace(Regex("\\s+"), " ")

    // ------------------------------------------------------------------- tasks

    fun addTask(task: Task): Long {
        val values = ContentValues().apply {
            put("title", task.title)
            put("notes", task.notes)
            put("due_at", task.dueAt)
            put("repeat_rule", task.repeatRule)
            put("done", if (task.done) 1 else 0)
            put("created_at", task.createdAt)
            put("completed_at", task.completedAt)
            put("notify", if (task.notify) 1 else 0)
        }
        return db.insert("tasks", null, values)
    }

    fun getTask(id: Long): Task? =
        db.rawQuery("SELECT * FROM tasks WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toTask() else null }

    fun updateTask(task: Task): Boolean {
        val values = ContentValues().apply {
            put("title", task.title)
            put("notes", task.notes)
            put("due_at", task.dueAt)
            put("repeat_rule", task.repeatRule)
            put("done", if (task.done) 1 else 0)
            put("completed_at", task.completedAt)
            put("notify", if (task.notify) 1 else 0)
        }
        return db.update("tasks", values, "id = ?", arrayOf(task.id.toString())) > 0
    }

    fun completeTask(id: Long): Task? {
        val task = getTask(id) ?: return null
        val done = task.copy(done = true, completedAt = System.currentTimeMillis())
        updateTask(done)
        return done
    }

    fun deleteTask(id: Long): Boolean =
        db.delete("tasks", "id = ?", arrayOf(id.toString())) > 0

    fun tasks(includeDone: Boolean = false, limit: Int = 100): List<Task> =
        db.rawQuery(
            "SELECT * FROM tasks" + (if (includeDone) "" else " WHERE done = 0") +
                " ORDER BY done ASC, (due_at IS NULL) ASC, due_at ASC, created_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { it.readAll { c -> c.toTask() } }

    /** Open tasks with an alarm still in the future — used to rebuild alarms after a reboot. */
    fun pendingReminders(): List<Task> =
        db.rawQuery(
            "SELECT * FROM tasks WHERE done = 0 AND notify = 1 AND due_at IS NOT NULL",
            null
        ).use { it.readAll { c -> c.toTask() } }

    // ---------------------------------------------------------------- messages

    fun addMessage(message: ChatMessage): Long {
        val values = ContentValues().apply {
            put("role", message.role)
            put("content", message.content)
            put("created_at", message.createdAt)
            put("tools", message.tools.joinToString(","))
        }
        return db.insert("messages", null, values)
    }

    /** Oldest-first so it can be fed straight back to the model. */
    fun recentMessages(limit: Int = 40): List<ChatMessage> =
        db.rawQuery(
            "SELECT * FROM messages ORDER BY created_at DESC, id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { it.readAll { c -> c.toChatMessage() } }.reversed()

    /**
     * Past turns containing [query].
     *
     * A plain LIKE rather than the BM25 index: the index is built for memories,
     * and what this answers — "what did we say about the landlord last week" —
     * is a literal search through a transcript, where a substring match is both
     * what the user means and cheap enough to run on every call.
     */
    fun searchMessages(query: String, limit: Int = 10): List<ChatMessage> {
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val escaped = "%" + needle.replace("%", "\\%").replace("_", "\\_") + "%"
        return db.rawQuery(
            "SELECT * FROM messages WHERE content LIKE ? ESCAPE '\\' " +
                "ORDER BY created_at DESC LIMIT ?",
            arrayOf(escaped, limit.toString())
        ).use { it.readAll { c -> c.toChatMessage() } }
    }

    fun clearMessages() {
        db.delete("messages", null, null)
    }

    // ----------------------------------------------------------------- cursors

    private fun <T> Cursor.readAll(map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count)
        while (moveToNext()) out.add(map(this))
        return out
    }

    private fun Cursor.stringOrNull(name: String): String? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getString(i)
    }

    private fun Cursor.longOrNull(name: String): Long? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getLong(i)
    }

    private fun Cursor.doubleOrNull(name: String): Double? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getDouble(i)
    }

    private fun Cursor.toMemory() = Memory(
        id = getLong(getColumnIndexOrThrow("id")),
        kind = getString(getColumnIndexOrThrow("kind")),
        content = getString(getColumnIndexOrThrow("content")),
        detail = stringOrNull("detail"),
        tags = stringOrNull("tags").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() },
        importance = getInt(getColumnIndexOrThrow("importance")),
        pinned = getInt(getColumnIndexOrThrow("pinned")) == 1,
        archived = getInt(getColumnIndexOrThrow("archived")) == 1,
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        occurredAt = longOrNull("occurred_at"),
        source = getString(getColumnIndexOrThrow("source"))
    )

    private fun Cursor.toTracker() = Tracker(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        label = getString(getColumnIndexOrThrow("label")),
        kind = getString(getColumnIndexOrThrow("kind")),
        unit = getString(getColumnIndexOrThrow("unit")),
        budget = doubleOrNull("budget"),
        period = getString(getColumnIndexOrThrow("period")),
        startingBalance = doubleOrNull("starting_balance"),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        archived = getInt(getColumnIndexOrThrow("archived")) == 1
    )

    private fun Cursor.toEntry() = Entry(
        id = getLong(getColumnIndexOrThrow("id")),
        trackerId = getLong(getColumnIndexOrThrow("tracker_id")),
        amount = getDouble(getColumnIndexOrThrow("amount")),
        direction = getString(getColumnIndexOrThrow("direction")),
        note = stringOrNull("note"),
        category = stringOrNull("category"),
        occurredAt = getLong(getColumnIndexOrThrow("occurred_at")),
        createdAt = getLong(getColumnIndexOrThrow("created_at"))
    )

    private fun Cursor.toTask() = Task(
        id = getLong(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        notes = stringOrNull("notes"),
        dueAt = longOrNull("due_at"),
        repeatRule = getString(getColumnIndexOrThrow("repeat_rule")),
        done = getInt(getColumnIndexOrThrow("done")) == 1,
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        completedAt = longOrNull("completed_at"),
        notify = getInt(getColumnIndexOrThrow("notify")) == 1
    )

    private fun Cursor.toChatMessage() = ChatMessage(
        id = getLong(getColumnIndexOrThrow("id")),
        role = getString(getColumnIndexOrThrow("role")),
        content = getString(getColumnIndexOrThrow("content")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        tools = stringOrNull("tools").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    )
}
