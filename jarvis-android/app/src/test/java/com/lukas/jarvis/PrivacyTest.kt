package com.lukas.jarvis

import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.llm.Agent
import com.lukas.jarvis.llm.AgentMemory
import com.lukas.jarvis.llm.ChatModel
import com.lukas.jarvis.llm.LlmMessage
import com.lukas.jarvis.llm.LlmReply
import com.lukas.jarvis.llm.Privacy
import com.lukas.jarvis.llm.ReplyStream
import com.lukas.jarvis.llm.ToolBox
import com.lukas.jarvis.llm.ToolCall
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolEffects
import com.lukas.jarvis.llm.ToolGroup
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local only: with nothing but the free keyless models, what is personal
 * never reaches them — memories, what you wrote about yourself, where the
 * phone is, and the tools that would say who you know, what you have
 * planned or where you are.
 */
class PrivacyTest {

    /** A keyless-only brain that writes down everything it is sent. */
    private class Recording(private val private: Boolean) : ChatModel {
        val sent = mutableListOf<String>()
        val offered = mutableListOf<String>()
        override suspend fun chat(settings: Settings, messages: List<LlmMessage>, tools: List<JSONObject>, stream: ReplyStream?, onEndpointChange: (String) -> Unit): LlmReply {
            messages.forEach { sent += it.content.orEmpty() }
            tools.forEach { offered += it.getJSONObject("function").getString("name") }
            return LlmReply("Okay.", emptyList())
        }
        override suspend fun look(settings: Settings, prompt: String, imageDataUrl: String) = ""
        override fun answersPrivately(settings: Settings) = private
    }

    private object Everything : ToolBox {
        override val routinesRunning = AtomicInteger(0)
        override fun schemas(settings: Settings) = ToolCatalog.ALL.map {
            JSONObject().put("type", "function").put("function", JSONObject().put("name", it.name).put("parameters", JSONObject()))
        }
        override suspend fun execute(call: ToolCall, settings: Settings, effects: ToolEffects) = "Done."
    }

    /** Hands back a memory unless the turn is sealed, and remembers which it was asked for. */
    private class Memories : AgentMemory {
        var sealedAsked: Boolean? = null
        override fun context(utterance: String, settings: Settings, extra: List<String>) = context(utterance, settings, extra, false)
        override fun context(utterance: String, settings: Settings, extra: List<String>, sealed: Boolean): String {
            sealedAsked = sealed
            return (if (sealed) Privacy.NOTE else "Pinned: Anna is vegetarian") + "\n" + extra.joinToString("\n")
        }
        override fun search(query: String, limit: Int) = emptyList<Memory>()
    }

    private val settings = Settings(userName = "Lukas", aboutMe = "I live on Hauptstraße 5 and work at the hospital")

    private fun ask(localOnly: Boolean, private: Boolean, sentence: String = "What's on today and who is Anna?"): Triple<Recording, Memories, Unit> = runBlocking {
        val model = Recording(private)
        val memory = Memories()
        val agent = Agent(model, Everything, memory).apply { whereabouts = { "Kreuzberg, Berlin" } }
        agent.respond(sentence, settings.copy(localOnly = localOnly), emptyList())
        Triple(model, memory, Unit)
    }

    @Test
    fun sealedTurnSendsNothingPersonal() {
        val (model, memory) = ask(localOnly = true, private = false)
        val all = model.sent.joinToString("\n")
        assertTrue(memory.sealedAsked == true)
        assertFalse("about-you text went out", all.contains("Hauptstraße"))
        assertFalse("a memory went out", all.contains("vegetarian"))
        assertFalse("where the phone is went out", all.contains("Kreuzberg"))
        assertTrue("Mochi is told why", all.contains("LOCAL ONLY"))
        model.offered.forEach { name ->
            val group = ToolCatalog.info(name)?.group
            assertFalse("$name was offered", group in setOf(ToolGroup.People, ToolGroup.Calendar, ToolGroup.Places, ToolGroup.Weather))
            assertFalse("$name reads memories", group == ToolGroup.Memory && ToolCatalog.info(name)?.readOnly == true)
        }
    }

    @Test
    fun withAKeyOfTheirOwnNothingIsHeldBack() {
        // The pool skips the keyless models in that case; the turn itself is whole.
        val (model, memory) = ask(localOnly = true, private = true)
        val all = model.sent.joinToString("\n")
        assertTrue(memory.sealedAsked == false)
        assertTrue(all.contains("Hauptstraße"))
        assertTrue(all.contains("Kreuzberg"))
    }

    @Test
    fun offByDefaultEverythingGoes() {
        val (model, memory) = ask(localOnly = false, private = false)
        assertTrue(memory.sealedAsked == false)
        assertTrue(model.sent.joinToString("\n").contains("vegetarian"))
    }

    @Test
    fun personalToolsAreTheOnesThatSayWhoWhatWhere() {
        assertTrue(Privacy.isPersonal("calendar"))
        assertTrue(Privacy.isPersonal("find_contact"))
        assertTrue(Privacy.isPersonal("find_places"))
        assertFalse(Privacy.isPersonal("remember"))
        assertFalse(Privacy.isPersonal("set_timer"))
    }
}
