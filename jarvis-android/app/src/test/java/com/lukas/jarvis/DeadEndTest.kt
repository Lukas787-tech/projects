package com.lukas.jarvis

import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolOutput
import com.lukas.jarvis.moment.ActionIntent
import com.lukas.jarvis.moment.AskKind
import com.lukas.jarvis.moment.Cards
import com.lukas.jarvis.moment.Composer
import com.lukas.jarvis.moment.Moment
import com.lukas.jarvis.moment.MomentResolver
import com.lukas.jarvis.moment.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zero dead ends, enforced: every moment the canvas can be in offers a clear
 * step forward, every card leads on, and every problem has a retry and
 * another way. A dead end anywhere fails the build.
 */
class DeadEndTest {

    @Test fun everyMomentIsReachableFromItsInputs() {
        Moment.ALL.forEach { moment ->
            assertEquals(moment, MomentResolver.resolve(Samples.inputsFor(moment)))
        }
    }

    @Test fun everyMomentHasAPrimaryStepForward() {
        Moment.ALL.forEach { moment ->
            val layout = Composer.compose(Samples.inputsFor(moment))
            assertTrue("$moment: primary '${layout.primary.label}' does not lead anywhere", layout.primary.forward)
            assertTrue("$moment: blank primary", layout.primary.label.isNotBlank())
            assertTrue("$moment: more than ${Composer.MAX_CARDS} cards", layout.cards.size <= Composer.MAX_CARDS)
            layout.cards.forEach { card ->
                assertTrue("$moment: card '${card.title}' has no way forward", card.actions.any { it.forward })
            }
            assertTrue("$moment: no headline for TalkBack", layout.headline.isNotBlank())
        }
    }

    @Test fun nothingIsEmptyWithoutSomethingToDo() {
        val resting = Composer.compose(Samples.inputsFor(Moment.Resting))
        assertTrue(resting.cards.isEmpty())
        assertTrue("an empty canvas offers prompts", resting.prompts.isNotEmpty())
    }

    @Test fun everyProblemHasARetryAndAnotherWay() {
        listOf(true, false).forEach { offline ->
            val layout = Composer.compose(Samples.inputsFor(Moment.Recovering(offline)))
            val problem = layout.cards.single()
            assertTrue(problem.actions.any { it.intent == ActionIntent.Retry })
            assertTrue("offline=$offline: no alternative", problem.actions.count { it.forward && it.intent != ActionIntent.Retry } >= 1)
        }
    }

    @Test fun everyConfirmationCanBeSentEditedOrCancelled() {
        val card = Composer.compose(Samples.inputsFor(Moment.Asking(AskKind.Confirmation))).cards.single()
        assertTrue(card.actions.any { it.intent is ActionIntent.Confirm && it.primary })
        assertTrue(card.actions.any { it.intent is ActionIntent.EditPending })
        assertTrue(card.actions.any { it.intent is ActionIntent.Cancel })
    }

    @Test fun everyToolsResultLeadsOnWithFollowUps() {
        ToolCatalog.ALL.forEach { info ->
            assertTrue("${info.name} offers no follow-ups", info.next.size in 1..3)
            assertTrue("${info.name}: every follow-up needs a slot", info.next.any { it.fill(emptyMap()) != null })
            val card = Cards.fromOutput(ToolOutput(info.name, Samples.argsFor(info.name), Samples.resultFor(info.name)))
            assertTrue("${info.name}'s card has no way forward", card.actions.any { it.forward })
            val failed = Cards.fromOutput(ToolOutput(info.name, "{}", "Tool '${info.name}' failed: timeout"))
            assertTrue("${info.name}: a failure has no retry", failed.actions.any { it.intent == ActionIntent.Retry })
        }
    }

    @Test fun whatWasJustMadeCanBeUndone() {
        listOf("add_task", "log_entry", "remember", "list", "create_routine", "save_place").forEach { tool ->
            val card = Samples.card(tool)
            assertTrue("$tool has no undo", card.actions.any { it.intent is ActionIntent.Undo })
        }
    }

    @Test fun nothingIsLostWhenTheMomentChanges() {
        val first = Samples.card("weather")
        val second = Samples.card("add_task")
        val pinned = Samples.card("list").copy(pinned = true)
        val layout = Composer.compose(
            Samples.inputsFor(Moment.Thinking).copy(fresh = listOf(second), shelf = listOf(first), pinned = listOf(pinned))
        )
        val everything = (layout.cards + layout.shelf).map { it.id }.toSet()
        assertTrue(first.id in everything && second.id in everything && pinned.id in everything)
    }
}
