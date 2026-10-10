package com.lukas.jarvis

import com.lukas.jarvis.data.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The database a 5.5 phone already has must come through 6.0 whole: every
 * memory, entry, task and line of conversation, upgraded in place by the same
 * statements the phone runs.
 */
class MigrationTest {

    private fun open(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun Connection.run(statements: List<String>) =
        statements.forEach { sql -> createStatement().use { it.execute(sql) } }

    private fun Connection.tables(): Set<String> =
        createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }

    private fun Connection.columns(table: String): List<String> =
        createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
        }

    private fun Connection.count(table: String): Int =
        createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } }

    private fun Connection.text(sql: String): String =
        createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getString(1) } }

    @Test fun everyVersionHasItsStep() {
        (2..Schema.VERSION).forEach { assertNotNull("step $it", Schema.STEPS[it]) }
    }

    @Test fun stepsOnlyEverAdd() {
        Schema.STEPS.values.flatten().forEach { sql ->
            val upper = sql.uppercase()
            assertFalse(sql, "DROP " in upper || "DELETE " in upper || "UPDATE " in upper)
        }
    }

    @Test fun anOldDatabaseEndsUpShapedLikeAFreshOne() {
        val fresh = open().apply { run(Schema.create()) }
        (1 until Schema.VERSION).forEach { from ->
            val old = open().apply { run(Schema.V1 + Schema.upgrade(1, from)) }
            old.run(Schema.upgrade(from, Schema.VERSION))
            assertEquals("tables from version $from", fresh.tables(), old.tables())
            fresh.tables().forEach { table ->
                assertEquals("$table from version $from", fresh.columns(table), old.columns(table))
            }
        }
    }

    @Test fun aFiveFiveDatabaseKeepsEveryRow() {
        // 5.5 shipped database version 3.
        val db = open().apply { run(Schema.V1 + Schema.upgrade(1, 3)) }
        db.run(
            listOf(
                "INSERT INTO memories (kind, content, created_at, updated_at) VALUES ('fact', 'Locker code is 3917', 1, 1)",
                "INSERT INTO memory_tokens (token, memory_id, tf) VALUES ('locker', 1, 1)",
                "INSERT INTO trackers (name, label, created_at) VALUES ('money', 'Money', 1)",
                "INSERT INTO entries (tracker_id, amount, occurred_at, created_at) VALUES (1, 2.5, 1, 1)",
                "INSERT INTO tasks (title, created_at) VALUES ('Call mum', 1)",
                "INSERT INTO messages (role, content, created_at, tools, image) VALUES ('assistant', 'Noted.', 1, 'remember', NULL)"
            )
        )
        db.run(Schema.upgrade(3, Schema.VERSION))
        listOf("memories", "memory_tokens", "trackers", "entries", "tasks", "messages").forEach {
            assertEquals(it, 1, db.count(it))
        }
        assertEquals("Locker code is 3917", db.text("SELECT content FROM memories"))
        assertEquals("remember", db.text("SELECT tools FROM messages"))
        assertTrue("action_log" in db.tables())
        assertTrue("pins" in db.tables())
        assertTrue("recurring" in db.tables())
        assertEquals(0, db.count("action_log"))
        assertEquals(0, db.count("recurring"))
    }
}
