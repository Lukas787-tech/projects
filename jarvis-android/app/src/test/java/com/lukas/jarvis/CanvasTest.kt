package com.lukas.jarvis

import com.lukas.jarvis.data.PinRecord
import com.lukas.jarvis.llm.ToolOutput
import com.lukas.jarvis.moment.CanvasRules
import com.lukas.jarvis.moment.CanvasState
import com.lukas.jarvis.moment.Cards
import com.lukas.jarvis.moment.Samples
import com.lukas.jarvis.ui.character.Win
import com.lukas.jarvis.vm.PinCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pin and shelve: pinned cards stay, the rest settle onto the shelf, nothing is lost. */
class CanvasTest {

    @Test fun lastTurnsCardsSettleOntoTheShelf() {
        val weather = Samples.card("weather")
        val task = Samples.card("add_task")
        var s = CanvasRules.add(CanvasState(), weather)
        s = CanvasRules.pin(s, weather.id)
        s = CanvasRules.add(s, task)
        s = CanvasRules.beginTurn(s, 10)
        assertTrue(s.fresh.isEmpty())
        assertEquals(listOf(task.id), s.shelf.map { it.id })
        assertEquals(listOf(weather.id), s.pinned.map { it.id })
        s = CanvasRules.bringBack(s, task.id)
        assertEquals(listOf(task.id), s.fresh.map { it.id })
        s = CanvasRules.dismiss(s, task.id)
        assertEquals(task.id, s.shelf.first().id)
    }

    @Test fun confirmationsAreLeftToTheGate() {
        val pending = Samples.inputsFor(com.lukas.jarvis.moment.Moment.Asking(com.lukas.jarvis.moment.AskKind.Confirmation)).pending.single()
        assertTrue(CanvasRules.add(CanvasState(), Cards.confirm(pending)).fresh.isEmpty())
    }

    @Test fun winsAreNoticed() {
        fun out(tool: String, result: String = "ok") = ToolOutput(tool, "{}", result)
        assertEquals(Win.Delight, CanvasRules.winFor(listOf(out("complete_task"))))
        assertEquals(Win.Noted, CanvasRules.winFor(listOf(out("remember"))))
        assertEquals(Win.Done, CanvasRules.winFor(listOf(out("add_task"))))
        assertEquals(null, CanvasRules.winFor(listOf(out("weather"))))
        assertEquals(null, CanvasRules.winFor(listOf(out("add_task", "Tool 'add_task' failed: no"))))
    }

    @Test fun aQuestionBackIsNoticed() {
        assertTrue(CanvasRules.finish(CanvasState(), "Which Anna did you mean?", emptyList(), 5).clarifying)
        assertTrue(!CanvasRules.finish(CanvasState(), "Done.", emptyList(), 5).clarifying)
    }

    @Test fun aPinComesBackWithAWayForward() {
        val card = Samples.card("list")
        val back = PinCodec.card(PinCodec.record(card).copy(id = 3))
        assertEquals(card.id, back.id)
        assertEquals(card.kind, back.kind)
        assertEquals(card.lines, back.lines)
        assertEquals(card.slots, back.slots)
        assertTrue(back.pinned && back.actions.any { it.forward })
        assertEquals("pin:9", PinCodec.cardId(PinRecord(id = 9, kind = "Answer", tool = "", title = "x", body = "", payload = "{}")))
    }
}
