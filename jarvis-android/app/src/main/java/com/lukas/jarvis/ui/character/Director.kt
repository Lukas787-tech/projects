package com.lukas.jarvis.ui.character

import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolGroup

/** A small win worth showing for a moment. */
enum class Win {
    /** Something made or done: a reminder set, a text sent. */
    Done,
    /** Something kept: "I'll remember that", with a small nod. */
    Noted,
    /** A bigger small win: a task ticked off, a timer finished, a streak kept. */
    Delight
}

/**
 * What is really going on, as the agent and the phone report it. Mochi's
 * face is never animated from anything else: no loop runs regardless of what
 * is happening.
 */
data class Signals(
    val name: String = "Mochi",
    val listening: Boolean = false,
    /** A turn is being worked on. */
    val thinking: Boolean = false,
    val speaking: Boolean = false,
    /** The tool running right now, if any. */
    val tool: String? = null,
    val waitingForYes: Boolean = false,
    /** A timer or reminder is ringing. */
    val alerting: Boolean = false,
    /** The last turn went wrong. */
    val problem: Boolean = false,
    /** The last answer was a question back: Mochi was not sure what was meant. */
    val unsure: Boolean = false,
    val win: Win? = null,
    val winAt: Long = 0L,
    val now: Long = 0L,
    /** 0..23 on the phone's clock. */
    val hour: Int = 12,
    /** When the person last did anything at all. */
    val lastActivityAt: Long = 0L
)

/**
 * Decides what Mochi is doing from [Signals]. Pure, so the rules are tested:
 * what matters most wins — a ringing timer over everything, then a question
 * waiting on your yes, then whatever the agent is doing right now.
 */
object Director {

    const val WIN_MS = 2_600L
    const val SLEEPY_AT_NIGHT_MS = 2 * 60_000L
    const val SLEEPY_ANY_TIME_MS = 20 * 60_000L

    fun direct(s: Signals): CharacterState {
        val name = s.name.ifBlank { "Mochi" }
        val freshWin = s.win != null && s.now - s.winAt in 0..WIN_MS
        val idleFor = s.now - s.lastActivityAt
        val night = s.hour >= 23 || s.hour < 6
        return when {
            s.alerting -> CharacterState(Mood.Alert, Prop.Bell, tag = "ring!", talking = s.speaking, description = "$name is ringing — time's up")
            s.waitingForYes -> CharacterState(Mood.Waiting, Prop.Card, tag = "ok?", talking = s.speaking, description = "$name is waiting for your OK")
            s.listening -> CharacterState(Mood.Listening, description = "$name is listening")
            s.tool != null && s.thinking -> {
                val doing = ToolCatalog.doing(s.tool)
                CharacterState(Mood.Working, propFor(s.tool), talking = s.speaking, description = "$name is $doing")
            }
            s.speaking -> CharacterState(
                if (freshWin && s.win == Win.Delight) Mood.Delighted else Mood.Speaking,
                talking = true,
                description = "$name is talking"
            )
            s.thinking -> CharacterState(Mood.Thinking, tag = "hmm", description = "$name is thinking")
            s.problem -> CharacterState(Mood.Sorry, description = "$name is sorry — something didn't work, and it says what to try next")
            freshWin -> when (s.win) {
                Win.Delight -> CharacterState(Mood.Delighted, description = "$name is delighted")
                Win.Noted -> CharacterState(Mood.Success, tag = "noted", description = "$name nods: it'll remember that")
                else -> CharacterState(Mood.Success, tag = "done", description = "$name is pleased: done")
            }
            s.unsure -> CharacterState(Mood.Confused, description = "$name isn't sure what you meant and asks")
            (night && idleFor > SLEEPY_AT_NIGHT_MS) || idleFor > SLEEPY_ANY_TIME_MS ->
                CharacterState(Mood.Sleepy, tag = "zz", description = "$name is dozing; tap or talk to wake it")
            else -> CharacterState(Mood.Idle, description = "$name is here, ready when you are")
        }
    }

    /** The thing Mochi holds while a tool of this family runs. */
    fun propFor(tool: String): Prop = when (ToolCatalog.info(tool)?.group) {
        ToolGroup.Memory, ToolGroup.Tasks, ToolGroup.Thinking -> Prop.Notepad
        ToolGroup.Money, ToolGroup.Markets -> Prop.Coins
        ToolGroup.Web, ToolGroup.News, ToolGroup.Knowledge -> Prop.Magnifier
        ToolGroup.Weather -> Prop.Umbrella
        ToolGroup.Places -> Prop.Map
        ToolGroup.Calendar -> Prop.Calendar
        ToolGroup.People, ToolGroup.Messages -> Prop.Envelope
        ToolGroup.Phone, ToolGroup.Screen -> Prop.Phone
        ToolGroup.Media -> Prop.Note
        ToolGroup.Vision -> Prop.Camera
        ToolGroup.Automation -> Prop.Gear
        ToolGroup.Language -> Prop.Bubble
        ToolGroup.Create -> Prop.Brush
        ToolGroup.Fun -> Prop.Die
        ToolGroup.Home -> Prop.House
        null -> Prop.Notepad
    }
}
