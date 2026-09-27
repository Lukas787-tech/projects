package com.lukas.jarvis

/*
 * The HUD's arrangement: which panel is in front, which have stepped back to
 * glances, and how the screen settles back to the core by itself.
 */
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.stage.Holo
import com.lukas.jarvis.stage.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTest {

    @Test fun raisingPutsThePreviousPanelOnTheShelf() {
        val scene = Scene().raise(Holo.Map, 0).raise(Holo.Weather, 10)
        assertEquals(Holo.Weather, scene.focus?.holo)
        assertEquals(listOf(Holo.Map), scene.shelf.map { it.holo })
    }

    @Test fun raisingTheSamePanelRefreshesItWithoutReplayingIt() {
        val first = Scene().raise(Holo.Lists, 0, note = "shopping")
        val again = first.raise(Holo.Lists, 50)
        assertEquals(first.focus?.serial, again.focus?.serial)
        assertEquals("shopping", again.focus?.note)
        assertEquals(50L, again.focus?.raisedAt)
    }

    @Test fun aShelvedPanelComesBackWithItsNote() {
        val scene = Scene().raise(Holo.Lists, 0, note = "packing").raise(Holo.Map, 10).promote(Holo.Lists, 20)
        assertEquals(Holo.Lists, scene.focus?.holo)
        assertEquals("packing", scene.focus?.note)
        assertEquals(listOf(Holo.Map), scene.shelf.map { it.holo })
    }

    @Test fun theShelfKeepsOnlyTheMostRecent() {
        var scene = Scene()
        listOf(Holo.Map, Holo.Weather, Holo.Tasks, Holo.Money, Holo.Lists).forEachIndexed { i, holo ->
            scene = scene.raise(holo, i.toLong())
        }
        assertEquals(Holo.Lists, scene.focus?.holo)
        assertEquals(listOf(Holo.Money, Holo.Tasks, Holo.Weather), scene.shelf.map { it.holo })
    }

    @Test fun anUntouchedPanelRecedesOnceTheAssistantIsQuiet() {
        val raised = Scene().raise(Holo.Weather, 0)
        assertSame(raised, raised.expire(Holo.Weather.holdMs - 1, busy = false))
        // Still talking about it: it stays however long that takes.
        assertEquals(Holo.Weather, raised.expire(10 * Holo.Weather.holdMs, busy = true).focus?.holo)
        val settled = raised.expire(Holo.Weather.holdMs, busy = false)
        assertNull(settled.focus)
        assertEquals(listOf(Holo.Weather), settled.shelf.map { it.holo })
    }

    @Test fun aTouchedPanelStaysLonger() {
        val touched = Scene().raise(Holo.Map, 0).touch(1_000)
        assertEquals(Holo.Map, touched.expire(1_000 + Holo.Map.holdMs, busy = false).focus?.holo)
        assertNull(touched.expire(1_000 + Scene.TOUCHED_HOLD_MS, busy = false).focus)
    }

    @Test fun glancesFadeFromTheShelf() {
        val scene = Scene().raise(Holo.Tasks, 0).recede(100)
        assertEquals(1, scene.expire(100 + Scene.SHELF_MS - 1, busy = false).shelf.size)
        assertTrue(scene.expire(100 + Scene.SHELF_MS, busy = false).shelf.isEmpty())
    }

    @Test fun dismissingIsNotShelving() {
        val scene = Scene().raise(Holo.Music, 0).dismiss()
        assertNull(scene.focus)
        assertTrue(scene.shelf.isEmpty())
    }

    @Test fun toolsAndElementsFindTheirPanels() {
        assertEquals(Holo.Money, Holo.forTool("log_entry"))
        assertEquals(Holo.Tasks, Holo.forTool("add_task"))
        assertEquals(Holo.Lists, Holo.forTool("list"))
        assertNull(Holo.forTool("calculate"))
        assertEquals(Holo.Map, Holo.of(Element.Map))
        assertNull(Holo.of(Element.Settings))
    }
}
