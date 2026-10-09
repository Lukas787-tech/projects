package com.lukas.jarvis

import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.llm.Agent
import com.lukas.jarvis.llm.AgentMemory
import com.lukas.jarvis.llm.ChatModel
import com.lukas.jarvis.llm.ConfirmationGate
import com.lukas.jarvis.llm.LlmMessage
import com.lukas.jarvis.llm.LlmReply
import com.lukas.jarvis.llm.ReplyStream
import com.lukas.jarvis.llm.ToolBox
import com.lukas.jarvis.llm.ToolCall
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolEffects
import com.lukas.jarvis.llm.ToolOutput
import com.lukas.jarvis.moment.ActionIntent
import com.lukas.jarvis.moment.AskKind
import com.lukas.jarvis.moment.CanvasRules
import com.lukas.jarvis.moment.CanvasState
import com.lukas.jarvis.moment.Cards
import com.lukas.jarvis.moment.Composer
import com.lukas.jarvis.moment.CreateKind
import com.lukas.jarvis.moment.Moment
import com.lukas.jarvis.moment.MomentInputs
import com.lukas.jarvis.moment.MomentResolver
import com.lukas.jarvis.moment.ShowKind
import com.lukas.jarvis.ui.character.Director
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.character.Signals
import com.lukas.jarvis.ui.character.Win
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ask, watch it work, see the result, take the follow-up: the whole loop the
 * canvas exists for, from a sentence to the next tap, without a phone.
 */
class FlowTest {

    /** A model that follows a script: one round of tool calls per sentence, then words. */
    private class ScriptedModel(private val script: Map<String, Pair<ToolCall, String>>) : ChatModel {
        private val seen = mutableMapOf<String, Int>()
        override suspend fun chat(settings: Settings, messages: List<LlmMessage>, tools: List<JSONObject>, stream: ReplyStream?, onEndpointChange: (String) -> Unit): LlmReply {
            val asked = messages.last { it.role == LlmMessage.USER }.content.orEmpty()
            val key = script.keys.firstOrNull { asked.contains(it, ignoreCase = true) }
                ?: return LlmReply("Here you go.", emptyList())
            val round = seen.merge(key, 1, Int::plus)!!
            val (call, words) = script.getValue(key)
            return if (round == 1) LlmReply(null, listOf(call)) else LlmReply(words, emptyList())
        }
        override suspend fun look(settings: Settings, prompt: String, imageDataUrl: String) = ""
    }

    private class Tools : ToolBox {
        val ran = mutableListOf<String>()
        override val routinesRunning = AtomicInteger(0)
        override fun schemas(settings: Settings) = ToolCatalog.ALL.map {
            JSONObject().put("type", "function").put("function", JSONObject().put("name", it.name).put("parameters", JSONObject()))
        }
        override suspend fun execute(call: ToolCall, settings: Settings, effects: ToolEffects): String {
            ran += call.name
            return when (call.name) {
                "find_places" -> "- Café Mitte, 200 m\n- Bäckerei Hansen, 350 m"
                "route_to" -> "Café Mitte: 200 m, 3 minutes on foot."
                "add_task" -> "Reminder set: Call mum, tomorrow 18:00 (id 12)"
                "delete_task" -> "Removed."
                else -> "Done."
            }
        }
    }

    private object NoMemory : AgentMemory {
        override fun context(utterance: String, settings: Settings, extra: List<String>) = ""
        override fun search(query: String, limit: Int) = emptyList<Memory>()
    }

    private val tools = Tools()
    private val gate = ConfirmationGate()
    private val agent = Agent(
        ScriptedModel(
            mapOf(
                "coffee" to (ToolCall("1", "find_places", """{"query":"Café Mitte"}""") to "Café Mitte is closest."),
                "how do i get" to (ToolCall("2", "route_to", """{"destination":"Café Mitte"}""") to "Three minutes on foot."),
                "remind me" to (ToolCall("3", "add_task", """{"title":"Call mum"}""") to "I'll remind you tomorrow at six."),
                "text anna" to (ToolCall("4", "send_message", """{"number":"+4912345","text":"Running late"}""") to "Ready to send — say yes.")
            )
        ),
        tools, NoMemory, gate
    )

    /** One turn as the view model runs it: cards arrive as tools finish, then the turn is noted. */
    private fun turn(canvas: CanvasState, sentence: String): Pair<CanvasState, String> = runBlocking {
        var state = CanvasRules.beginTurn(canvas, 1)
        var index = 0
        val result = agent.respond(sentence, Settings(), emptyList(), onResult = { out: ToolOutput ->
            state = CanvasRules.add(state, Cards.fromOutput(out, index++))
        })
        CanvasRules.finish(state, result.reply, result.effects.outputs, 2) to result.reply
    }

    private fun inputs(canvas: CanvasState) = MomentInputs(
        fresh = canvas.fresh, shelf = canvas.shelf, pinned = canvas.pinned, pending = gate.pending.value,
        clarifying = canvas.clarifying
    )

    @Test fun askWorkResultFollowUp() {
        // Ask.
        var (canvas, reply) = turn(CanvasState(), "I want coffee, what's around?")
        assertEquals("Café Mitte is closest.", reply)
        // The result is a place card on the map, with "Route there" as the next step.
        var layout = Composer.compose(inputs(canvas))
        assertEquals(Moment.Showing(ShowKind.Map), layout.moment)
        val follow = layout.primary
        assertEquals("Route there", follow.label)
        // Take the follow-up: it is said to Mochi like any sentence.
        val said = (follow.intent as ActionIntent.Say).text
        assertEquals("How do I get to Café Mitte?", said)
        canvas = turn(canvas, said).first
        layout = Composer.compose(inputs(canvas))
        assertEquals(Moment.Showing(ShowKind.Map), layout.moment)
        assertEquals(listOf("find_places", "route_to"), tools.ran)
        // The first answer is not lost: it is on the shelf.
        assertTrue(layout.shelf.any { it.tool == "find_places" })
    }

    @Test fun madeSomethingThenUndoIt() {
        val canvas = turn(CanvasState(), "Remind me to call mum tomorrow").first
        val layout = Composer.compose(inputs(canvas))
        assertEquals(Moment.Creating(CreateKind.Task), layout.moment)
        assertEquals(Win.Done, canvas.win)
        assertEquals(Mood.Success, Director.direct(Signals(win = canvas.win, winAt = canvas.winAt, now = canvas.winAt + 100, lastActivityAt = canvas.winAt)).mood)
        val undo = layout.cards.single().actions.first { it.intent is ActionIntent.Undo }.intent as ActionIntent.Undo
        assertEquals("delete_task", undo.tool)
        runBlocking { agent.runDirect(ToolCall("u", undo.tool, undo.argumentsJson), Settings()) }
        assertEquals(listOf("add_task", "delete_task"), tools.ran)
    }

    @Test fun anOutwardAskWaitsOnTheCanvas() {
        val canvas = turn(CanvasState(), "Text Anna I'm running late").first
        assertTrue("nothing sent before the yes", tools.ran.isEmpty())
        val layout = Composer.compose(inputs(canvas))
        assertEquals(Moment.Asking(AskKind.Confirmation), MomentResolver.resolve(inputs(canvas)))
        assertEquals("Send", layout.primary.label)
        val id = (layout.primary.intent as ActionIntent.Confirm).id
        runBlocking { agent.carryOut(gate.take(id)!!, Settings()) }
        assertEquals(listOf("send_message"), tools.ran)
    }
}
