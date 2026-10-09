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
import com.lukas.jarvis.llm.Risk
import com.lukas.jarvis.llm.ToolBox
import com.lukas.jarvis.llm.ToolCall
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolEffects
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The promise behind every text, call and delete: nothing that leaves the phone
 * or cannot be taken back happens until the person says yes.
 */
class ConfirmationTest {

    /** Records every call that really ran. */
    private class FakeTools : ToolBox {
        val ran = mutableListOf<ToolCall>()
        override val routinesRunning = AtomicInteger(0)
        override fun schemas(settings: Settings) = ToolCatalog.ALL.map { info ->
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject().put("name", info.name).put("description", info.doing)
                        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
                )
        }

        override suspend fun execute(call: ToolCall, settings: Settings, effects: ToolEffects): String {
            ran += call
            return when (call.name) {
                "call" -> "Ready to ring Anna on +4912345. Ask them to confirm before you place it."
                "place_call" -> "Ringing Anna."
                else -> "Done: ${call.name}"
            }
        }
    }

    /** A model that asks for one tool, then answers in words. */
    private class OneCallModel(private val call: ToolCall) : ChatModel {
        var round = 0
        override suspend fun chat(
            settings: Settings,
            messages: List<LlmMessage>,
            tools: List<JSONObject>,
            stream: ReplyStream?,
            onEndpointChange: (String) -> Unit
        ): LlmReply {
            round++
            return if (round == 1) LlmReply(null, listOf(call)) else LlmReply("It's ready for your OK.", emptyList())
        }

        override suspend fun look(settings: Settings, prompt: String, imageDataUrl: String) = ""
    }

    private object NoMemory : AgentMemory {
        override fun context(utterance: String, settings: Settings, extra: List<String>) = "CONTEXT"
        override fun search(query: String, limit: Int) = emptyList<Memory>()
    }

    private val args = JSONObject()
        .put("number", "+4912345").put("who", "Anna").put("text", "Running ten minutes late")
        .put("id", 3).put("name", "Gym").put("action", "lock").put("to", "anna@example.com")
        .put("title", "Dentist").put("target", "front door")
        .toString()

    private fun agentFor(call: ToolCall, tools: FakeTools, gate: ConfirmationGate = ConfirmationGate()) =
        Agent(OneCallModel(call), tools, NoMemory, gate)

    @Test fun everyOutwardOrSensitiveToolWaitsForAYes() = runBlocking {
        val gated = ToolCatalog.ALL.filter { it.risk.asksFirst }
        assertTrue("texts, calls and deletes are all gated", gated.size >= 15)
        gated.forEach { info ->
            val tools = FakeTools()
            val gate = ConfirmationGate()
            val result = agentFor(ToolCall("c1", info.name, args), tools, gate)
                .respond("please do it", Settings(), emptyList())
            assertTrue("${info.name} ran before anyone said yes", tools.ran.isEmpty())
            assertEquals(info.name, gate.pending.value.single().tool)
            assertEquals(info.name, result.effects.waiting.single().tool)
        }
    }

    @Test fun theOutwardOnesAreTheOnesThatLeaveThePhone() {
        listOf("send_message", "send_chat_message", "reply_to_message", "call", "send_email", "share", "share_location")
            .forEach { assertEquals(it, Risk.Outward, ToolCatalog.risk(it)) }
        listOf("forget", "delete_task", "delete_entry", "delete_routine", "forget_place", "system_action")
            .forEach { assertEquals(it, Risk.Sensitive, ToolCatalog.risk(it)) }
        assertEquals(Risk.Read, ToolCatalog.risk("weather"))
        assertEquals(Risk.Local, ToolCatalog.risk("add_task"))
        // A light is a light; the front door is not.
        assertEquals(Risk.Local, ToolCatalog.riskFor("home_control", """{"target":"kitchen light","action":"on"}"""))
        assertEquals(Risk.Sensitive, ToolCatalog.riskFor("home_control", """{"target":"front door","action":"unlock"}"""))
    }

    @Test fun localToolsRunAtOnce() = runBlocking {
        val tools = FakeTools()
        val gate = ConfirmationGate()
        agentFor(ToolCall("c1", "add_task", """{"title":"Call mum"}"""), tools, gate)
            .respond("remind me to call mum", Settings(), emptyList())
        assertEquals(listOf("add_task"), tools.ran.map { it.name })
        assertTrue(gate.pending.value.isEmpty())
    }

    @Test fun yesRunsExactlyTheEditedCallOnce() = runBlocking {
        val tools = FakeTools()
        val gate = ConfirmationGate()
        val agent = agentFor(ToolCall("c1", "send_message", args), tools, gate)
        agent.respond("text Anna I'm running late", Settings(), emptyList())
        val waiting = gate.pending.value.single()
        gate.edit(waiting.id, "text", "Running fifteen minutes late")
        val taken = gate.take(waiting.id)!!
        agent.carryOut(taken, Settings())
        assertEquals(1, tools.ran.size)
        assertEquals("Running fifteen minutes late", JSONObject(tools.ran.single().argumentsJson).getString("text"))
        // Taken once; a second tap finds nothing to send.
        assertNull(gate.take(waiting.id))
    }

    @Test fun cancellingSendsNothing() = runBlocking {
        val tools = FakeTools()
        val gate = ConfirmationGate()
        agentFor(ToolCall("c1", "send_message", args), tools, gate).respond("text Anna", Settings(), emptyList())
        gate.cancel(gate.pending.value.single().id)
        assertTrue(gate.pending.value.isEmpty())
        assertTrue(tools.ran.isEmpty())
    }

    @Test fun aCallRingsOnlyAfterTheYes() = runBlocking {
        val tools = FakeTools()
        val gate = ConfirmationGate()
        val agent = agentFor(ToolCall("c1", "call", args), tools, gate)
        agent.respond("call Anna", Settings(), emptyList())
        assertTrue(tools.ran.isEmpty())
        val output = agent.carryOut(gate.take(gate.pending.value.single().id)!!, Settings())
        assertEquals(listOf("call", "place_call"), tools.ran.map { it.name })
        assertEquals("Ringing Anna.", output.result)
    }

    @Test fun aRepeatedRequestReplacesTheWaitingOne() {
        val gate = ConfirmationGate()
        gate.propose(ToolCall("a", "send_message", """{"number":"1","text":"one"}"""))
        gate.propose(ToolCall("b", "send_message", """{"number":"1","text":"two"}"""))
        assertEquals("two", gate.pending.value.single().detail)
    }

    @Test fun aSpokenYesOnlyAnswersAFreshQuestion() {
        var now = 1_000_000L
        val gate = ConfirmationGate { now }
        gate.propose(ToolCall("a", "send_message", """{"number":"1","text":"hi"}"""))
        assertNotNull(gate.awaitingVoice())
        now += ConfirmationGate.VOICE_WINDOW_MS + 1
        assertNull(gate.awaitingVoice())
        // Still on screen for a tap, though.
        assertEquals(1, gate.pending.value.size)
    }

    @Test fun yesAndNoInPlainWords() {
        listOf("yes", "Yes please", "send it", "go ahead", "OK!", "do it", "Mochi, yes", "ja bitte").forEach {
            assertTrue(it, ConfirmationGate.isYes(it))
        }
        listOf("no", "cancel", "never mind", "wait", "nein").forEach { assertTrue(it, ConfirmationGate.isNo(it)) }
        listOf("yes but say fifteen minutes", "send it to Ben instead", "what time is it").forEach {
            assertFalse(it, ConfirmationGate.isYes(it))
        }
    }

    @Test fun theOfflineReflexesAskFirstToo() = runBlocking {
        val tools = FakeTools()
        val gate = ConfirmationGate()
        val agent = Agent(OneCallModel(ToolCall("x", "now", "{}")), tools, NoMemory, gate)
        val result = agent.offline("lock the phone", Settings())
        assertNotNull(result)
        assertTrue(tools.ran.isEmpty())
        assertEquals("system_action", gate.pending.value.single().tool)
    }
}
