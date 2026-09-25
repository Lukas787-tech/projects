package com.lukas.jarvis

/*
 * The interpreter's bookkeeping: which language each side speaks, and the
 * conversation it keeps.
 */
import com.lukas.jarvis.voice.InterpreterState
import com.lukas.jarvis.voice.InterpreterState.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class InterpreterTest {

    private val start = InterpreterState(mine = "en", theirs = "es", mineName = "English", theirsName = "Español")

    @Test fun eachSideHasItsLanguage() {
        assertEquals("en", start.languageOf(Side.Me))
        assertEquals("es", start.languageOf(Side.Them))
        assertEquals(Side.Them, start.other(Side.Me))
        assertEquals(Side.Me, start.other(Side.Them))
    }

    @Test fun aHeardLineEndsTheListeningAndTheWork() {
        val busy = start.copy(listening = Side.Me, working = true, note = "No translation came back.")
        val after = busy.heard(Side.Me, "Where is the station?", "¿Dónde está la estación?")
        assertNull(after.listening)
        assertFalse(after.working)
        assertNull(after.note)
        assertEquals("¿Dónde está la estación?", after.lines.single().translated)
    }

    @Test fun theConversationKeepsItsLatestLines() {
        var state = start
        repeat(InterpreterState.MAX_LINES + 5) { state = state.heard(Side.Them, "hola $it", "hello $it") }
        assertEquals(InterpreterState.MAX_LINES, state.lines.size)
        assertEquals("hola ${InterpreterState.MAX_LINES + 4}", state.lines.last().said)
    }
}
