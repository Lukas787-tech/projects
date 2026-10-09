package com.lukas.jarvis.moment

import com.lukas.jarvis.llm.Risk
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolOutput
import com.lukas.jarvis.ui.character.Win

/**
 * The cards on the canvas and what the last turn left behind.
 *
 * [fresh] is the turn just answered (or in progress); when the next turn
 * starts they settle onto the [shelf] — nothing is lost when the moment
 * changes — unless they were [pinned], which stay put.
 */
data class CanvasState(
    val fresh: List<CanvasCard> = emptyList(),
    val pinned: List<CanvasCard> = emptyList(),
    val shelf: List<CanvasCard> = emptyList(),
    val problem: CanvasCard? = null,
    /** The last reply asked something back. */
    val clarifying: Boolean = false,
    val win: Win? = null,
    val winAt: Long = 0L,
    val lastActivityAt: Long = 0L,
    /** Directions were handed to the map app and are under way. */
    val navigating: Boolean = false
)

/** How the canvas changes as a turn runs. Pure, and tested. */
object CanvasRules {

    const val SHELF_MAX = 40

    /** A new turn: last turn's cards settle onto the shelf. */
    fun beginTurn(s: CanvasState, now: Long): CanvasState = s.copy(
        fresh = emptyList(),
        shelf = (s.fresh.filterNot { card -> s.pinned.any { it.id == card.id } }.reversed() + s.shelf)
            .distinctBy { it.id }.take(SHELF_MAX),
        problem = null,
        clarifying = false,
        win = null,
        lastActivityAt = now,
        navigating = false
    )

    /**
     * A card arriving while the turn runs. Confirmations are left to the gate,
     * which shows them for as long as they wait; a card with the same id
     * replaces the old one.
     */
    fun add(s: CanvasState, card: CanvasCard): CanvasState {
        if (card.kind == CardKind.Confirm) return s
        val replaced = s.fresh.any { it.id == card.id }
        return s.copy(fresh = if (replaced) s.fresh.map { if (it.id == card.id) card else it } else s.fresh + card)
    }

    /** The turn is answered: note a win, a question back, or directions under way. */
    fun finish(s: CanvasState, reply: String, outputs: List<ToolOutput>, now: Long): CanvasState {
        val win = winFor(outputs)
        return s.copy(
            clarifying = outputs.isEmpty() && reply.trim().endsWith("?"),
            win = win,
            winAt = if (win != null) now else s.winAt,
            lastActivityAt = now,
            navigating = outputs.any { it.tool == "start_navigation" && !it.failed }
        )
    }

    fun fail(s: CanvasState, problem: CanvasCard, now: Long): CanvasState =
        s.copy(problem = problem, win = null, lastActivityAt = now)

    fun touch(s: CanvasState, now: Long): CanvasState = s.copy(lastActivityAt = now)

    fun pin(s: CanvasState, id: String): CanvasState {
        val card = (s.fresh + s.shelf).firstOrNull { it.id == id } ?: return s
        if (s.pinned.any { it.id == id }) return s
        return s.copy(pinned = s.pinned + card.copy(pinned = true), shelf = s.shelf.filterNot { it.id == id })
    }

    fun unpin(s: CanvasState, id: String): CanvasState {
        val card = s.pinned.firstOrNull { it.id == id } ?: return s
        return s.copy(pinned = s.pinned.filterNot { it.id == id }, shelf = (listOf(card.copy(pinned = false)) + s.shelf).take(SHELF_MAX))
    }

    /** Put away: off the canvas and onto the shelf, never gone. */
    fun dismiss(s: CanvasState, id: String): CanvasState {
        val card = (s.fresh + s.pinned).firstOrNull { it.id == id }
        val problem = s.problem?.takeIf { it.id != id }
        if (card == null) return s.copy(problem = problem)
        return s.copy(
            fresh = s.fresh.filterNot { it.id == id },
            pinned = s.pinned.filterNot { it.id == id },
            shelf = (listOf(card.copy(pinned = false)) + s.shelf).distinctBy { it.id }.take(SHELF_MAX),
            problem = problem
        )
    }

    /** Takes a card off the shelf and back onto the canvas. */
    fun bringBack(s: CanvasState, id: String): CanvasState {
        val card = s.shelf.firstOrNull { it.id == id } ?: return s
        return s.copy(fresh = s.fresh.filterNot { it.id == id } + card, shelf = s.shelf.filterNot { it.id == id })
    }

    /** Whether a turn earned a moment of pleasure on Mochi's face, and which. */
    fun winFor(outputs: List<ToolOutput>): Win? {
        val done = outputs.filter { !it.failed && it.waiting == null }
        return when {
            done.any { it.tool == "complete_task" } -> Win.Delight
            done.any { it.tool == "remember" } -> Win.Noted
            done.any { ToolCatalog.risk(it.tool) != Risk.Read && !ToolCatalog.isReadOnly(it.tool) } -> Win.Done
            else -> null
        }
    }
}
