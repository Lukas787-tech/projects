package com.lukas.jarvis.stage

import com.lukas.jarvis.web.Forecast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the assistant can project onto its own screen, over the core, without
 * the user going anywhere. Each has a full-screen [element] behind it for when
 * a panel is not enough.
 */
enum class Holo(val title: String, val element: Element) {
    Map("Location", Element.Map),
    Day("Today", Element.Today),
    Weather("Weather", Element.Today),
    Tasks("Tasks", Element.Tasks),
    Money("Trackers", Element.Money),
    Lists("Lists", Element.Lists),
    Music("Now playing", Element.Music);

    /** How long it stays up untouched once the assistant has gone quiet. */
    val holdMs: Long get() = if (this == Map) 90_000L else 45_000L

    companion object {
        fun of(element: Element): Holo? = when (element) {
            Element.Map -> Map
            Element.Today -> Day
            Element.Tasks -> Tasks
            Element.Money -> Money
            Element.Lists -> Lists
            Element.Music -> Music
            else -> null
        }

        /** The panel a finished tool call is worth showing, if any. Maps are raised by the map itself. */
        fun forTool(name: String): Holo? = when (name) {
            "log_entry", "tracker_status", "configure_tracker", "list_entries",
            "spending_report", "delete_entry" -> Money
            "add_task", "list_tasks", "complete_task", "update_task", "delete_task" -> Tasks
            "list" -> Lists
            "briefing" -> Day
            "play_music", "control_playback", "now_playing" -> Music
            else -> null
        }
    }
}

/** One panel, raised at a moment, for a reason. [serial] is what the screen keys its entrance on. */
data class Projection(
    val holo: Holo,
    val serial: Long,
    val raisedAt: Long,
    val touchedAt: Long = raisedAt,
    /** A list name, a place — whatever narrows the panel down. */
    val note: String = "",
    val forecast: Forecast? = null
) {
    val touched: Boolean get() = touchedAt > raisedAt
}

/**
 * The HUD's arrangement: at most one panel in [focus], projected large, and a
 * short [shelf] of the ones that receded, shown as glances at the edge.
 *
 * Every change is a new value, and a change that changes nothing returns the
 * same instance, so the flow it lives in only speaks when something moved.
 */
data class Scene(
    val focus: Projection? = null,
    val shelf: List<Projection> = emptyList(),
    val serial: Long = 0
) {

    /**
     * Puts [holo] in front. The one already there steps back to the shelf; the
     * same one raised again is refreshed where it stands rather than replayed.
     */
    fun raise(holo: Holo, now: Long, note: String = "", forecast: Forecast? = null): Scene {
        val current = focus
        if (current?.holo == holo) {
            return copy(
                focus = current.copy(
                    raisedAt = now,
                    touchedAt = now,
                    note = note.ifBlank { current.note },
                    forecast = forecast ?: current.forecast
                )
            )
        }
        val kept = shelf.firstOrNull { it.holo == holo }
        val next = serial + 1
        val stepped = listOfNotNull(current?.copy(touchedAt = now)) + shelf.filter { it.holo != holo }
        return Scene(
            focus = Projection(
                holo = holo,
                serial = next,
                raisedAt = now,
                note = note.ifBlank { kept?.note.orEmpty() },
                forecast = forecast ?: kept?.forecast
            ),
            shelf = stepped.take(SHELF_MAX),
            serial = next
        )
    }

    /** A glance tapped: its panel comes back as it was. */
    fun promote(holo: Holo, now: Long): Scene = raise(holo, now)

    /** The panel in front steps back to a glance. */
    fun recede(now: Long): Scene {
        val current = focus ?: return this
        return copy(
            focus = null,
            shelf = (listOf(current.copy(touchedAt = now)) + shelf.filter { it.holo != current.holo }).take(SHELF_MAX)
        )
    }

    /** Put away on purpose: gone, not shelved. */
    fun dismiss(): Scene = if (focus == null) this else copy(focus = null)

    fun forget(holo: Holo): Scene =
        if (shelf.none { it.holo == holo }) this else copy(shelf = shelf.filter { it.holo != holo })

    /** A finger on the panel: it is being used, so it stays longer. */
    fun touch(now: Long): Scene {
        val current = focus ?: return this
        return copy(focus = current.copy(touchedAt = now))
    }

    /**
     * Time passing. The panel in front recedes once it has been left alone
     * long enough — never while the assistant is still [busy] with it — and
     * glances fade from the shelf after a while, so the screen settles back to
     * the core by itself.
     */
    fun expire(now: Long, busy: Boolean): Scene {
        var scene = this
        val current = focus
        if (current != null && !busy) {
            val hold = if (current.touched) TOUCHED_HOLD_MS else current.holo.holdMs
            if (now - current.touchedAt >= hold) scene = scene.recede(now)
        }
        val fresh = scene.shelf.filter { now - it.touchedAt < SHELF_MS }
        if (fresh.size != scene.shelf.size) scene = scene.copy(shelf = fresh)
        return scene
    }

    companion object {
        const val SHELF_MAX = 3
        const val TOUCHED_HOLD_MS = 150_000L
        const val SHELF_MS = 8 * 60_000L
    }
}

/** The one scene, written by tools on any thread and by the screen. */
class SceneStore(private val clock: () -> Long = System::currentTimeMillis) {

    private val _state = MutableStateFlow(Scene())
    val state: StateFlow<Scene> = _state.asStateFlow()

    val current: Scene get() = _state.value

    fun raise(holo: Holo, note: String = "", forecast: Forecast? = null) =
        _state.update { it.raise(holo, clock(), note, forecast) }

    fun promote(holo: Holo) = _state.update { it.promote(holo, clock()) }
    fun recede() = _state.update { it.recede(clock()) }
    fun dismiss() = _state.update { it.dismiss() }
    fun forget(holo: Holo) = _state.update { it.forget(holo) }
    fun touch() = _state.update { it.touch(clock()) }
    fun expire(busy: Boolean) = _state.update { it.expire(clock(), busy) }
}
