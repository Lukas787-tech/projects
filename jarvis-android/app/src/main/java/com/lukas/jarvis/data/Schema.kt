package com.lukas.jarvis.data

/**
 * The database's shape, as plain SQL, with a numbered step for every version.
 *
 * Kept apart from [Db] so the exact statements the phone runs can also be run
 * by a test against a real SQLite file: a 5.5 database is built, upgraded step
 * by step, and checked to hold everything it held before.
 *
 * Every step adds and never drops. This file is the person's memory; an
 * upgrade that cost any of it would cost everything.
 */
object Schema {

    const val VERSION = 5

    /** Version 1, as the first release created it. */
    val V1: List<String> = listOf(
        """
        CREATE TABLE memories (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            kind TEXT NOT NULL,
            content TEXT NOT NULL,
            detail TEXT,
            tags TEXT NOT NULL DEFAULT '',
            importance INTEGER NOT NULL DEFAULT 3,
            pinned INTEGER NOT NULL DEFAULT 0,
            archived INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            occurred_at INTEGER,
            source TEXT NOT NULL DEFAULT 'voice',
            token_count INTEGER NOT NULL DEFAULT 0
        )
        """,
        "CREATE INDEX idx_memories_created ON memories(created_at DESC)",
        "CREATE INDEX idx_memories_kind ON memories(kind)",
        // Hand-rolled inverted index so retrieval does not depend on the FTS
        // extension being compiled into a given device's SQLite build.
        """
        CREATE TABLE memory_tokens (
            token TEXT NOT NULL,
            memory_id INTEGER NOT NULL,
            tf INTEGER NOT NULL,
            PRIMARY KEY (token, memory_id)
        )
        """,
        "CREATE INDEX idx_tokens_token ON memory_tokens(token)",
        "CREATE INDEX idx_tokens_memory ON memory_tokens(memory_id)",
        """
        CREATE TABLE trackers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            label TEXT NOT NULL,
            kind TEXT NOT NULL DEFAULT 'money',
            unit TEXT NOT NULL DEFAULT 'EUR',
            budget REAL,
            period TEXT NOT NULL DEFAULT 'monthly',
            starting_balance REAL,
            created_at INTEGER NOT NULL,
            archived INTEGER NOT NULL DEFAULT 0
        )
        """,
        """
        CREATE TABLE entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            tracker_id INTEGER NOT NULL,
            amount REAL NOT NULL,
            direction TEXT NOT NULL DEFAULT 'out',
            note TEXT,
            category TEXT,
            occurred_at INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            FOREIGN KEY (tracker_id) REFERENCES trackers(id) ON DELETE CASCADE
        )
        """,
        "CREATE INDEX idx_entries_tracker ON entries(tracker_id, occurred_at DESC)",
        """
        CREATE TABLE tasks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            notes TEXT,
            due_at INTEGER,
            repeat_rule TEXT NOT NULL DEFAULT 'none',
            done INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            completed_at INTEGER,
            notify INTEGER NOT NULL DEFAULT 1
        )
        """,
        "CREATE INDEX idx_tasks_due ON tasks(done, due_at)",
        """
        CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX idx_messages_created ON messages(created_at DESC)"
    ).map { it.trimIndent().trim() }

    /** Step N takes a database from version N - 1 to N. */
    val STEPS: Map<Int, List<String>> = mapOf(
        // Which tools answered each reply. Old replies simply have none.
        2 to listOf("ALTER TABLE messages ADD COLUMN tools TEXT NOT NULL DEFAULT ''"),
        // A picture drawn for a reply, as a path in the app's own files.
        3 to listOf("ALTER TABLE messages ADD COLUMN image TEXT"),
        4 to listOf(
            // Every outward or irreversible action: what, when, and how it was
            // confirmed — the record behind "ask before anything outward".
            """
            CREATE TABLE action_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tool TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '',
                risk TEXT NOT NULL,
                outcome TEXT NOT NULL DEFAULT '',
                confirmed_by TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX idx_action_log_created ON action_log(created_at DESC)",
            // Cards pinned to the canvas, so they stay put across moments and restarts.
            """
            CREATE TABLE pins (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                tool TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL,
                body TEXT NOT NULL DEFAULT '',
                payload TEXT NOT NULL DEFAULT '{}',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        ),
        // Entries that log themselves on a schedule: the rent, a subscription.
        5 to listOf(
            """
            CREATE TABLE recurring (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tracker_id INTEGER NOT NULL,
                amount REAL NOT NULL,
                direction TEXT NOT NULL DEFAULT 'out',
                note TEXT,
                every TEXT NOT NULL DEFAULT 'monthly',
                anchor INTEGER NOT NULL,
                logged INTEGER NOT NULL DEFAULT 0,
                next_at INTEGER NOT NULL,
                active INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            // No cascade from the tracker: Brain removes a tracker's schedules
            // with it, and steps here never carry a delete of any kind.
            "CREATE INDEX idx_recurring_next ON recurring(active, next_at)"
        )
    )

    /** What a fresh install runs: version 1, then every step. */
    fun create(): List<String> = V1 + upgrade(1, VERSION)

    /** The statements that take a database from [from] to [to], in order. */
    fun upgrade(from: Int, to: Int): List<String> = ((from + 1)..to).flatMap { version ->
        STEPS[version] ?: error("No migration to version $version")
    }
}
