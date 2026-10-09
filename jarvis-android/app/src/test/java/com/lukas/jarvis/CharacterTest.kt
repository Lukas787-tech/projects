package com.lukas.jarvis

import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolGroup
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Choreo
import com.lukas.jarvis.ui.character.Director
import com.lukas.jarvis.ui.character.Ink
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.character.Mouth
import com.lukas.jarvis.ui.character.Prop
import com.lukas.jarvis.ui.character.Signals
import com.lukas.jarvis.ui.character.Sprites
import com.lukas.jarvis.ui.character.Win
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mochi is driven by what is really happening, and every state of it can be drawn. */
class CharacterTest {

    private fun frames(state: CharacterState, reduce: Boolean = false) =
        (0 until Choreo.loopLength(state)).map { Sprites.compose(Choreo.pose(state, it, reduce)).toList() }

    @Test fun everyMoodAndPropDraws() {
        val states = Mood.values().map { CharacterState(it) } + Prop.values().map { CharacterState(Mood.Working, it) }
        states.forEach { state ->
            val frame = Sprites.compose(Choreo.pose(state, 3))
            assertEquals(Sprites.W * Sprites.H, frame.size)
            assertTrue("$state has a body", frame.count { it.toInt() == Ink.BODY } > 150)
            assertTrue("$state has eyes", frame.count { it.toInt() == Ink.EYE } >= 4)
        }
        assertEquals(Sprites.TINY * Sprites.TINY, Sprites.tiny().size)
    }

    @Test fun itMovesUnlessMotionIsReduced() {
        Mood.values().forEach { mood ->
            val state = CharacterState(mood)
            assertTrue("$mood is animated", frames(state).toSet().size >= 2)
            assertEquals("$mood holds still with reduce motion", 1, (0..20).map { Sprites.compose(Choreo.pose(state, it, reduceMotion = true)).toList() }.toSet().size)
        }
    }

    @Test fun theMouthFollowsTheVoice() {
        assertEquals(Mouth.Smile, Choreo.mouthFor(0f))
        assertEquals(Mouth.Small, Choreo.mouthFor(0.3f))
        assertEquals(Mouth.Open, Choreo.mouthFor(0.9f))
        val talking = CharacterState(Mood.Speaking, talking = true)
        assertNotEquals(
            Sprites.compose(Choreo.pose(talking, 5, talkMouth = Mouth.Smile)).toList(),
            Sprites.compose(Choreo.pose(talking, 5, talkMouth = Mouth.Open)).toList()
        )
    }

    @Test fun whatMattersMostWins() {
        val busy = Signals(thinking = true, tool = "weather", listening = false, now = 10_000, lastActivityAt = 10_000)
        assertEquals(Mood.Working, Director.direct(busy).mood)
        assertEquals(Prop.Umbrella, Director.direct(busy).prop)
        assertEquals(Mood.Waiting, Director.direct(busy.copy(waitingForYes = true)).mood)
        assertEquals(Mood.Alert, Director.direct(busy.copy(waitingForYes = true, alerting = true)).mood)
        assertEquals(Mood.Listening, Director.direct(Signals(listening = true)).mood)
        assertEquals(Mood.Thinking, Director.direct(Signals(thinking = true)).mood)
        assertEquals(Mood.Sorry, Director.direct(Signals(problem = true)).mood)
        assertEquals(Mood.Confused, Director.direct(Signals(unsure = true)).mood)
    }

    @Test fun winsAreBriefAndSleepIsEarned() {
        val won = Signals(win = Win.Noted, winAt = 1_000, now = 2_000, lastActivityAt = 2_000)
        assertEquals(Mood.Success, Director.direct(won).mood)
        assertEquals(Mood.Idle, Director.direct(won.copy(now = 1_000 + Director.WIN_MS + 1, lastActivityAt = 3_000)).mood)
        val lateNight = Signals(hour = 23, now = 10 * 60_000L, lastActivityAt = 0)
        assertEquals(Mood.Sleepy, Director.direct(lateNight).mood)
        assertEquals(Mood.Idle, Director.direct(lateNight.copy(hour = 15)).mood)
        assertEquals(Mood.Sleepy, Director.direct(lateNight.copy(hour = 15, now = Director.SLEEPY_ANY_TIME_MS + 1)).mood)
    }

    @Test fun everyFamilyHasSomethingToHold() {
        ToolGroup.values().forEach { group ->
            val tool = ToolCatalog.ALL.first { it.group == group }.name
            Director.propFor(tool)
        }
        assertEquals(Prop.Coins, Director.propFor("log_entry"))
        assertEquals(Prop.Map, Director.propFor("route_to"))
        assertEquals(Prop.Magnifier, Director.propFor("web_search"))
    }

    @Test fun talkBackHearsWhatMochiIsDoing() {
        val said = Director.direct(Signals(name = "Bao", thinking = true, tool = "web_search")).description
        assertTrue(said, said.startsWith("Bao is searching the web"))
    }
}
