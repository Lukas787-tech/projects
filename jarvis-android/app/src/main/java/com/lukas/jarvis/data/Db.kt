package com.lukas.jarvis.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain framework SQLite. No ORM and no annotation processor on purpose: the
 * schema is small, and this keeps the build free of code-generation plugins.
 */
class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
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
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_memories_created ON memories(created_at DESC)")
        db.execSQL("CREATE INDEX idx_memories_kind ON memories(kind)")

        // Hand-rolled inverted index so retrieval does not depend on the FTS
        // extension being compiled into a given device's SQLite build.
        db.execSQL(
            """
            CREATE TABLE memory_tokens (
                token TEXT NOT NULL,
                memory_id INTEGER NOT NULL,
                tf INTEGER NOT NULL,
                PRIMARY KEY (token, memory_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_tokens_token ON memory_tokens(token)")
        db.execSQL("CREATE INDEX idx_tokens_memory ON memory_tokens(memory_id)")

        db.execSQL(
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
            """.trimIndent()
        )

        db.execSQL(
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
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_entries_tracker ON entries(tracker_id, occurred_at DESC)")

        db.execSQL(
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
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_tasks_due ON tasks(done, due_at)")

        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                tools TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_created ON messages(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Each step adds to what is there and drops nothing: this file is the
        // user's memory, and an upgrade that cost it would cost everything.
        if (oldVersion < 2) {
            // Which tools answered each reply. Old replies simply have none.
            db.execSQL("ALTER TABLE messages ADD COLUMN tools TEXT NOT NULL DEFAULT ''")
        }
    }

    companion object {
        const val NAME = "jarvis.db"
        const val VERSION = 2
    }
}
