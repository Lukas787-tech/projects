package com.lukas.jarvis.moment

import com.lukas.jarvis.llm.ToolGroup

/** How big Mochi is in a moment: the hero at the centre, a companion beside the cards, or a corner. */
enum class CharacterSize { Hero, Companion, Corner }

/** What the background does: the globe leads or recedes, the map leads, or plain paper. */
enum class Backdrop { GlobeLeads, GlobeRecedes, MapLeads, Paper }

/** A control that makes sense right now, beside the composer. */
enum class ControlIcon { Mic, Keyboard, Stop, Retry, Today, Library, Map, History, Alarm, Directions, Check, Undo }

data class Control(val label: String, val intent: ActionIntent, val icon: ControlIcon)

/** The canvas for one moment. */
data class Layout(
    val moment: Moment,
    val character: CharacterSize,
    /** At most [Composer.MAX_CARDS], the one with the primary action first. */
    val cards: List<CanvasCard>,
    /** Everything else, newest first; nothing is lost when the moment changes. */
    val shelf: List<CanvasCard>,
    /** The one clear next step. */
    val primary: CardAction,
    val controls: List<Control>,
    val backdrop: Backdrop,
    /** Offered when there is room: tappable things to say. */
    val prompts: List<FollowUp>,
    /** A few words for the moment, shown and read to TalkBack: "Listening". */
    val headline: String
)

/**
 * Turns a moment into a layout. Pure, so every moment is walked by a test that
 * fails on a dead end: no layout without a primary step forward, no card
 * without a way on, no problem without a retry and another way.
 */
object Composer {

    const val MAX_CARDS = 3

    fun compose(i: MomentInputs, moment: Moment = MomentResolver.resolve(i)): Layout {
        val urgent = buildList {
            i.alerts.forEach { add(alertCard(it)) }
            i.pending.forEach { add(Cards.confirm(it)) }
            i.problem?.let { add(it) }
        }
        val candidates = (urgent + i.fresh.reversed() + i.pinned).distinctBy { it.id }
        val visible = candidates.take(MAX_CARDS)
        val shelf = (candidates.drop(MAX_CARDS) + i.shelf).distinctBy { it.id }.filterNot { card -> visible.any { it.id == card.id } }

        val primary = primaryFor(moment, i, visible)
        return Layout(
            moment = moment,
            character = characterFor(moment),
            cards = visible.sortedByDescending { it.primary?.let { p -> p == primary } ?: false },
            shelf = shelf,
            primary = primary,
            controls = controlsFor(moment, i),
            backdrop = backdropFor(moment),
            prompts = promptsFor(moment, i, visible),
            headline = headlineFor(moment)
        )
    }

    private fun alertCard(alert: Alert): CanvasCard = CanvasCard(
        id = "alert:${alert.id}",
        kind = when (alert.kind) {
            AlertKind.Timer -> CardKind.Timer
            AlertKind.Reminder -> CardKind.Task
            AlertKind.Message -> CardKind.Inbox
            AlertKind.Arrival -> CardKind.Place
        },
        title = alert.title,
        body = alert.detail,
        actions = buildList {
            when {
                alert.timerId != null -> {
                    add(CardAction("Stop", ActionIntent.StopTimer(alert.timerId), primary = true))
                    add(CardAction("One more minute", ActionIntent.AddMinute(alert.timerId)))
                }
                alert.kind == AlertKind.Message -> {
                    add(CardAction("Reply", ActionIntent.Say("Help me reply to ${alert.title}"), primary = true))
                    add(CardAction("Read it to me", ActionIntent.ReadAloud(alert.detail)))
                }
                else -> {
                    add(CardAction("Done", ActionIntent.Dismiss("alert:${alert.id}"), primary = true))
                    add(CardAction("Remind me later", ActionIntent.Say("Remind me about ${alert.title} again in 10 minutes")))
                }
            }
        },
        status = CardStatus.Waiting
    )

    private fun primaryFor(moment: Moment, i: MomentInputs, visible: List<CanvasCard>): CardAction {
        fun cardPrimary() = visible.firstNotNullOfOrNull { it.primary?.takeIf { p -> p.forward } }
        return when (moment) {
            Moment.Resting -> if (i.micAvailable) CardAction("Talk to Mochi", ActionIntent.Listen, true)
                else CardAction("Type to Mochi", ActionIntent.Type(), true)
            Moment.Listening -> CardAction("Stop listening", ActionIntent.Stop, true)
            Moment.Thinking, is Moment.Working -> CardAction("Stop", ActionIntent.Stop, true)
            is Moment.Recovering -> CardAction("Try again", ActionIntent.Retry, true)
            Moment.Navigating -> CardAction("Open directions", ActionIntent.Open(Room.Map), true)
            is Moment.Asking -> if (moment.kind == AskKind.Clarification) {
                CardAction(if (i.micAvailable) "Answer" else "Type an answer", if (i.micAvailable) ActionIntent.Listen else ActionIntent.Type(), true)
            } else {
                cardPrimary() ?: CardAction("Talk to Mochi", ActionIntent.Listen, true)
            }
            is Moment.Alerting, is Moment.Showing, is Moment.Creating ->
                cardPrimary() ?: CardAction("Ask about this", ActionIntent.Say("Tell me more about that"), true)
        }
    }

    private fun characterFor(moment: Moment): CharacterSize = when (moment) {
        // Mochi does the work in plain sight: big, holding the tool's prop.
        Moment.Resting, Moment.Listening, Moment.Thinking, is Moment.Working, is Moment.Alerting -> CharacterSize.Hero
        is Moment.Recovering, is Moment.Asking, is Moment.Showing, is Moment.Creating -> CharacterSize.Companion
        Moment.Navigating -> CharacterSize.Corner
    }

    private fun backdropFor(moment: Moment): Backdrop = when (moment) {
        Moment.Resting -> Backdrop.GlobeLeads
        is Moment.Showing -> when (moment.kind) {
            ShowKind.Map -> Backdrop.MapLeads
            ShowKind.Globe -> Backdrop.GlobeLeads
            else -> Backdrop.Paper
        }
        is Moment.Working -> if (moment.group == ToolGroup.Places) Backdrop.MapLeads else Backdrop.GlobeRecedes
        Moment.Navigating -> Backdrop.MapLeads
        is Moment.Creating -> if (moment.kind == CreateKind.Place) Backdrop.MapLeads else Backdrop.Paper
        Moment.Listening, Moment.Thinking -> Backdrop.GlobeRecedes
        is Moment.Asking, is Moment.Alerting, is Moment.Recovering -> Backdrop.Paper
    }

    private fun controlsFor(moment: Moment, i: MomentInputs): List<Control> {
        val type = Control("Type", ActionIntent.Type(), ControlIcon.Keyboard)
        val talk = Control("Talk", ActionIntent.Listen, ControlIcon.Mic)
        val rooms = listOf(
            Control("Today", ActionIntent.Open(Room.Today), ControlIcon.Today),
            Control("Library", ActionIntent.Open(Room.Library), ControlIcon.Library),
            Control("Map", ActionIntent.Open(Room.Map), ControlIcon.Map)
        )
        return when (moment) {
            Moment.Resting -> rooms
            // Stopping is already the moment's own step and the composer's button.
            Moment.Listening -> listOf(type)
            Moment.Thinking, is Moment.Working -> emptyList()
            // Trying again and the other way round are on the problem card itself.
            is Moment.Recovering -> listOf(type, Control("History", ActionIntent.Open(Room.History), ControlIcon.History))
            is Moment.Alerting -> i.alerts.firstOrNull()?.timerId?.let {
                listOf(Control("Stop", ActionIntent.StopTimer(it), ControlIcon.Alarm), Control("+1 min", ActionIntent.AddMinute(it), ControlIcon.Alarm))
            } ?: listOf(talk, type)
            Moment.Navigating -> listOf(Control("Directions", ActionIntent.Open(Room.Map), ControlIcon.Directions), talk)
            is Moment.Showing -> if (moment.kind == ShowKind.Map) {
                listOf(Control("Open the map", ActionIntent.Open(Room.Map), ControlIcon.Map), Control("History", ActionIntent.Open(Room.History), ControlIcon.History))
            } else {
                listOf(Control("History", ActionIntent.Open(Room.History), ControlIcon.History)) + rooms.take(2)
            }
            is Moment.Creating, is Moment.Asking -> listOf(Control("History", ActionIntent.Open(Room.History), ControlIcon.History), rooms[1])
        }
    }

    private fun promptsFor(moment: Moment, i: MomentInputs, visible: List<CanvasCard>): List<FollowUp> = when {
        moment == Moment.Resting && visible.isEmpty() -> i.suggestions.ifEmpty { DEFAULT_PROMPTS }.take(3)
        moment is Moment.Showing || moment is Moment.Creating -> visible.flatMap { it.followUps }.distinctBy { it.say }.take(3)
        else -> emptyList()
    }

    private fun headlineFor(moment: Moment): String = when (moment) {
        Moment.Resting -> "Here when you need me"
        Moment.Listening -> "Listening"
        Moment.Thinking -> "Thinking"
        is Moment.Working -> "Working on it"
        is Moment.Showing -> "Here's what I found"
        is Moment.Creating -> "Made this for you"
        is Moment.Asking -> if (moment.kind == AskKind.Confirmation) "Waiting for your OK" else "Just checking"
        is Moment.Alerting -> "Time's up"
        Moment.Navigating -> "On the way"
        is Moment.Recovering -> "That didn't work"
    }

    /** What anyone can start with, before Mochi knows them. */
    val DEFAULT_PROMPTS = listOf(
        FollowUp("How does my day look?", "How does my day look?"),
        FollowUp("Start a shopping list", "Start a shopping list"),
        FollowUp("What's around me?", "What's around here?")
    )
}
