package com.lukas.jarvis

import com.lukas.jarvis.llm.Need
import com.lukas.jarvis.llm.Routing
import com.lukas.jarvis.llm.Routing.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {

    @Test fun aCommandIsQuickAPlanIsDeep() {
        assertEquals(Need.Quick, Routing.need("set a timer for ten minutes"))
        assertEquals(Need.Quick, Routing.need("turn off the lights"))
        assertEquals(Need.Quick, Routing.need("I spent 12 euros on lunch"))
        assertEquals(Need.Deep, Routing.need("plan my Saturday"))
        assertEquals(Need.Deep, Routing.need("explain how a heat pump works"))
        assertEquals(Need.Deep, Routing.need("help me decide between the two flats"))
        assertEquals(Need.Deep, Routing.need("x ".repeat(200)))
        assertEquals(Need.Normal, Routing.need("who won the match last night"))
        // A long sentence that happens to mention a timer is not a quick one.
        assertEquals(Need.Normal, Routing.need("when the pasta timer goes off later tonight what else should I have ready besides the sauce"))
    }

    @Test fun aModelsSizeComesFromItsName() {
        assertEquals(Size.Large, Routing.size("llama-3.3-70b-versatile"))
        assertEquals(Size.Small, Routing.size("llama-3.1-8b-instant"))
        assertEquals(Size.Medium, Routing.size("mixtral-8x7b-32768"))
        assertEquals(Size.Large, Routing.size("qwen3-235b-a22b"))
        assertEquals(Size.Large, Routing.size("openai/gpt-oss-120b"))
        assertEquals(Size.Small, Routing.size("gemini-2.5-flash-lite"))
        assertEquals(Size.Medium, Routing.size("gemini-2.5-flash"))
        assertEquals(Size.Large, Routing.size("gemini-2.5-pro"))
        assertEquals(Size.Small, Routing.size("gpt-4o-mini"))
        assertEquals(Size.Medium, Routing.size("openai"))
        // "3.3" is a version, not a size.
        assertEquals(Size.Medium, Routing.size("llama-3.3"))
    }

    @Test fun theLeanNeverCrossesATier() {
        Size.entries.forEach { size ->
            Need.entries.forEach { need -> assertTrue(kotlin.math.abs(Routing.bias(size, need)) < 10.0) }
        }
        assertTrue(Routing.bias(Size.Large, Need.Deep) < Routing.bias(Size.Small, Need.Deep))
        assertTrue(Routing.bias(Size.Small, Need.Quick) < Routing.bias(Size.Large, Need.Quick))
        assertEquals(0.0, Routing.bias(Size.Small, Need.Normal), 0.0)
    }
}
