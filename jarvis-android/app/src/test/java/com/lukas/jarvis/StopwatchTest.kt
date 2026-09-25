package com.lukas.jarvis

import com.lukas.jarvis.llm.Reflexes
import com.lukas.jarvis.notify.StopwatchState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopwatchTest {

    @Test fun countsPausesAndCarriesOn() {
        var watch = StopwatchState().start(1_000)
        assertEquals(4_000, watch.elapsed(5_000))
        watch = watch.pause(5_000)
        assertFalse(watch.running)
        assertEquals(4_000, watch.elapsed(60_000))
        watch = watch.start(10_000)
        assertEquals(6_000, watch.elapsed(12_000))
        // Starting twice does not restart it.
        assertEquals(watch, watch.start(11_000))
        assertTrue(watch.reset().idle)
    }

    @Test fun lapsAreTheirOwnLengths() {
        val watch = StopwatchState().start(0).lap(30_000).lap(75_000)
        assertEquals(listOf(30_000L, 45_000L), watch.lapLengths())
        assertEquals(StopwatchState(), StopwatchState().lap(5))
    }

    @Test fun readsLikeAClock() {
        assertEquals("0:09.8", StopwatchState.clock(9_870))
        assertEquals("4:05.2", StopwatchState.clock(245_200))
        assertEquals("1:02:03.4", StopwatchState.clock(3_723_400))
    }

    @Test fun offline() {
        fun action(text: String) = Reflexes.parse(text)?.takeIf { it.name == "stopwatch" }
            ?.let { JSONObject(it.argumentsJson).getString("action") }
        assertEquals("start", action("Start the stopwatch"))
        assertEquals("pause", action("stop the stopwatch"))
        assertEquals("lap", action("lap"))
        assertEquals("reset", action("Stoppuhr zurücksetzen"))
        assertEquals("start", action("Stoppuhr starten"))
        assertEquals("status", action("how long is the stopwatch at"))
    }
}
