package com.lukas.jarvis

import com.lukas.jarvis.notify.Answerable
import com.lukas.jarvis.notify.QuietDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuietDigestTest {

    private fun m(from: String, text: String, at: Long, app: String = "WhatsApp") = Answerable("$from:$at", "pkg", app, from, text, at)

    @Test fun nobodyWroteIsNothingToSay() {
        assertNull(QuietDigest.of(emptyList(), 1_000))
        // What was already there before the quiet time began is not news.
        assertNull(QuietDigest.of(listOf(m("Anna", "Hi", 500)), 1_000))
    }

    @Test fun whoWroteNewestFirst() {
        val digest = QuietDigest.of(listOf(m("Anna", "Are we still on for tonight?", 2_000), m("Tom", "Call me", 3_000, "Signal")), 1_000)!!
        assertEquals("Tom and Anna wrote while it was quiet", digest.title)
        assertEquals(listOf("Tom on Signal: Call me", "Anna on WhatsApp: Are we still on for tonight?"), digest.lines)
        assertEquals(2, digest.people)
    }

    @Test fun aBusyHourIsCountedNotListedInFull() {
        val many = (1..8).map { m("Person $it", "Message $it " + "x".repeat(100), 1_000L + it) }
        val digest = QuietDigest.of(many, 1_000)!!
        assertEquals("8 people wrote while it was quiet", digest.title)
        assertEquals(QuietDigest.MOST + 1, digest.lines.size)
        assertEquals("and 3 more", digest.lines.last())
        // A long message is cut, not run on.
        assertEquals(true, digest.lines.first().endsWith("…") && digest.lines.first().length < 110)
    }
}
