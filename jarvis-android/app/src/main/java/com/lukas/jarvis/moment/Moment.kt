package com.lukas.jarvis.moment

import com.lukas.jarvis.llm.PendingAction
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolGroup

enum class ShowKind { Map, Globe, List, Chart, Image, Text, Camera, Screen, Controls }
enum class CreateKind { Note, Task, List, Routine, Entry, Message, Event, Image, Plan, Place }
enum class AskKind { Confirmation, Clarification }
enum class AlertKind { Timer, Reminder, Message, Arrival }

/**
 * What is happening right now, as one value. The canvas has no fixed layout:
 * it composes itself from this.
 */
sealed interface Moment {
    data object Resting : Moment
    data object Listening : Moment
    data object Thinking : Moment
    data class Working(val group: ToolGroup) : Moment
    data class Showing(val kind: ShowKind) : Moment
    data class Creating(val kind: CreateKind) : Moment
    data class Asking(val kind: AskKind) : Moment
    data class Alerting(val kind: AlertKind) : Moment
    data object Navigating : Moment
    data class Recovering(val offline: Boolean) : Moment

    companion object {
        /** One of every kind of moment, for the dead-end test and the screenshots. */
        val ALL: List<Moment> = listOf(Resting, Listening, Thinking) +
            ToolGroup.values().map { Working(it) } +
            ShowKind.values().map { Showing(it) } +
            CreateKind.values().map { Creating(it) } +
            AskKind.values().map { Asking(it) } +
            AlertKind.values().map { Alerting(it) } +
            listOf(Navigating, Recovering(offline = false), Recovering(offline = true))
    }
}

/** Something ringing or arriving that wants attention now. */
data class Alert(
    val id: String,
    val kind: AlertKind,
    val title: String,
    val detail: String = "",
    /** The running timer behind it, when it is one. */
    val timerId: Int? = null
)

/** Everything the canvas is composed from, gathered by the view model. */
data class MomentInputs(
    val listening: Boolean = false,
    val thinking: Boolean = false,
    val speaking: Boolean = false,
    /** The tool running right now. */
    val tool: String? = null,
    val pending: List<PendingAction> = emptyList(),
    /** The last answer asked something back. */
    val clarifying: Boolean = false,
    val alerts: List<Alert> = emptyList(),
    val problem: CanvasCard? = null,
    /** Cards from the turn just answered (or in progress), oldest first. */
    val fresh: List<CanvasCard> = emptyList(),
    val pinned: List<CanvasCard> = emptyList(),
    /** Earlier cards, newest first: the quiet history strip. */
    val shelf: List<CanvasCard> = emptyList(),
    val navigating: Boolean = false,
    val online: Boolean = true,
    val micAvailable: Boolean = true,
    /** Things to offer when nothing is going on: "How does my day look?". */
    val suggestions: List<FollowUp> = emptyList()
)

/** Picks the moment from the inputs. What matters most wins. */
object MomentResolver {

    fun resolve(i: MomentInputs): Moment {
        i.alerts.firstOrNull()?.let { return Moment.Alerting(it.kind) }
        if (i.pending.isNotEmpty()) return Moment.Asking(AskKind.Confirmation)
        if (i.listening) return Moment.Listening
        if (i.thinking && i.tool != null) {
            return Moment.Working(ToolCatalog.info(i.tool)?.group ?: ToolGroup.Thinking)
        }
        if (i.thinking) return Moment.Thinking
        i.problem?.let { return Moment.Recovering(offline = !i.online) }
        if (i.navigating) return Moment.Navigating
        i.fresh.lastOrNull { it.kind != CardKind.Problem }?.let { return momentFor(it.kind) }
        if (i.clarifying) return Moment.Asking(AskKind.Clarification)
        return Moment.Resting
    }

    /** The moment a card of [kind] puts the canvas in. */
    fun momentFor(kind: CardKind): Moment = when (kind) {
        CardKind.Memory, CardKind.Note -> Moment.Creating(CreateKind.Note)
        CardKind.Task -> Moment.Creating(CreateKind.Task)
        CardKind.List -> Moment.Creating(CreateKind.List)
        CardKind.Entry -> Moment.Creating(CreateKind.Entry)
        CardKind.Routine -> Moment.Creating(CreateKind.Routine)
        CardKind.Event -> Moment.Creating(CreateKind.Event)
        CardKind.Picture -> Moment.Creating(CreateKind.Image)
        CardKind.Plan -> Moment.Creating(CreateKind.Plan)
        CardKind.Place -> Moment.Creating(CreateKind.Place)
        CardKind.Message, CardKind.Call -> Moment.Creating(CreateKind.Message)
        CardKind.Places, CardKind.Route, CardKind.Map -> Moment.Showing(ShowKind.Map)
        CardKind.Globe -> Moment.Showing(ShowKind.Globe)
        CardKind.Chart -> Moment.Showing(ShowKind.Chart)
        CardKind.Camera -> Moment.Showing(ShowKind.Camera)
        CardKind.Photo -> Moment.Showing(ShowKind.Image)
        CardKind.Screen -> Moment.Showing(ShowKind.Screen)
        CardKind.Calendar, CardKind.Inbox -> Moment.Showing(ShowKind.List)
        CardKind.Timer, CardKind.Stopwatch, CardKind.Device, CardKind.Media, CardKind.Home, CardKind.Settings ->
            Moment.Showing(ShowKind.Controls)
        CardKind.Confirm -> Moment.Asking(AskKind.Confirmation)
        CardKind.Problem -> Moment.Recovering(offline = false)
        CardKind.Answer, CardKind.Exact, CardKind.Brief, CardKind.Weather, CardKind.Web,
        CardKind.Contact, CardKind.Translation -> Moment.Showing(ShowKind.Text)
    }
}
