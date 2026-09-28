package com.lukas.jarvis.stage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The things Jarvis can put on screen.
 *
 * The map used to be the only one, which made it look like the point of the
 * app rather than one answer shape among several. It is an element now, beside
 * the globe, the notes and the rest, and the assistant chooses between them the
 * same way it chooses a tool: an answer about a place raises the map, an answer
 * about what you owe raises the trackers.
 *
 * [spoken] are the words a person actually uses for each, so the model can be
 * asked for "notes" and land on memory without anyone maintaining a synonym
 * table in the prompt.
 */
enum class Element(
    val title: String,
    val spoken: List<String>
) {
    Today(
        "Today",
        listOf("today", "dashboard", "overview", "brief", "briefing", "morning", "day", "summary")
    ),
    Globe("Globe", listOf("globe", "world", "earth", "planet", "home", "idle")),
    Map("Map", listOf("map", "places", "route", "directions", "navigation", "where")),
    Notes("Notes", listOf("notes", "note", "memory", "memories", "brain", "remember")),
    Tasks("Tasks", listOf("tasks", "task", "todo", "to-do", "reminders", "reminder")),
    Money("Trackers", listOf("trackers", "tracker", "money", "budget", "spending", "expenses")),
    Lists("Lists", listOf("lists", "list", "shopping list", "shopping", "packing list", "einkaufsliste")),
    Music("Music", listOf("music", "songs", "song", "player", "playing", "audio")),
    Devices("Devices", listOf("devices", "device", "bluetooth", "headphones", "speaker")),

    /**
     * Everything the assistant can do, with sentences to try. It is an element
     * rather than a page in Settings because "what can you do" is a question
     * asked of the assistant, and the answer should be something it can raise.
     */
    Skills(
        "Skills",
        listOf("skills", "abilities", "capabilities", "help", "what can you do", "tools")
    ),
    Settings("Settings", listOf("settings", "setup", "config", "preferences", "keys"));

    companion object {
        /** Loose matching, because this is fed by whatever the model said. */
        fun match(raw: String?): Element? {
            val text = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (text.isBlank()) return null
            entries.firstOrNull { element ->
                element.spoken.any { it == text } || element.name.lowercase(Locale.ROOT) == text
            }?.let { return it }
            // Then anything contained in the phrase, longest word first so
            // "show me the music player" does not match on a shorter accident.
            return entries
                .flatMap { element -> element.spoken.map { element to it } }
                .sortedByDescending { it.second.length }
                .firstOrNull { text.contains(it.second) }
                ?.first
        }

        fun names(): String = entries.joinToString(", ") { it.title.lowercase(Locale.ROOT) }
    }
}

/**
 * Which element is showing, and why.
 *
 * The assistant writes here through a tool and the user writes here by tapping
 * the bar; both are the same state, so the two never disagree about what is in
 * front of you. [revision] ticks on every change, which is what lets the screen
 * react to a change it did not itself cause.
 */
class StageStore {

    data class State(
        val element: Element = Element.Globe,
        val revision: Long = 0,
        /** What the assistant said it was doing, shown under the element. */
        val note: String = "",
        /** When it changed, so a screen that opens a moment later can still act on it. */
        val at: Long = 0L
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val current: Element get() = _state.value.element

    companion object {
        /** A note that opens the conversation history over the assistant's screen. */
        const val HISTORY = "history"

        /** "interpreter:es" opens the interpreter with Spanish; [INTERPRETER_STOP] closes it. */
        const val INTERPRETER_PREFIX = "interpreter:"
        const val INTERPRETER_STOP = "interpreter:stop"
    }

    fun show(element: Element, note: String = "") {
        _state.value = State(
            element = element,
            revision = _state.value.revision + 1,
            note = note,
            at = System.currentTimeMillis()
        )
    }
}
