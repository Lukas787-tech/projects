package com.lukas.jarvis

/*
 * The logic that decides what a turn sees and says, tested on the JVM: which
 * tool families a sentence reaches, that switched-off tools are never offered,
 * that the keyless brain is really keyless, how the user is addressed, and
 * where a reply still being written may be cut for speaking.
 */
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.Numbers
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.llm.Personas
import com.lukas.jarvis.llm.Providers
import com.lukas.jarvis.llm.Tier
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolGroup
import com.lukas.jarvis.llm.ToolRouter
import com.lukas.jarvis.voice.Sentences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    private fun groups(text: String, history: List<ChatMessage> = emptyList()) =
        ToolRouter.groupsFor(text, history)

    @Test fun coreAlwaysPresent() {
        val g = groups("hello there")
        assertTrue(ToolGroup.Memory in g && ToolGroup.Thinking in g && ToolGroup.Screen in g)
        // Nothing specific -> the web and tasks join.
        assertTrue(ToolGroup.Web in g)
    }

    @Test fun spendingInEnglishAndGerman() {
        assertTrue(ToolGroup.Money in groups("I bought chips for 2 euros"))
        assertTrue(ToolGroup.Money in groups("Ich habe 5€ für Kaffee ausgegeben"))
        assertTrue(ToolGroup.Money in groups("12.50 EUR lunch"))
    }

    @Test fun weatherPlacesAndPhone() {
        assertTrue(ToolGroup.Weather in groups("Do I need an umbrella today?"))
        assertTrue(ToolGroup.Weather in groups("Wie wird das Wetter morgen?"))
        assertTrue(ToolGroup.Places in groups("I'm hungry, what's around here"))
        assertTrue(ToolGroup.Phone in groups("wake me at 7:30"))
        assertTrue(ToolGroup.Tasks in groups("remind me to call mum tomorrow"))
        assertTrue(ToolGroup.Messages in groups("text Anna that I'm late"))
        assertTrue(ToolGroup.Create in groups("draw a fox in a space suit"))
        assertTrue(ToolGroup.Markets in groups("how is bitcoin doing"))
        assertTrue(ToolGroup.News in groups("what's in the news"))
        assertTrue(ToolGroup.Language in groups("how do you say thank you in japanese"))
        assertTrue(ToolGroup.Fun in groups("flip a coin"))
        assertTrue(ToolGroup.Knowledge in groups("give me a recipe for lasagne"))
        assertTrue(ToolGroup.Home in groups("turn off the living room lights"))
        assertTrue(ToolGroup.Home in groups("Schalte das Licht im Wohnzimmer aus"))
        assertTrue(ToolGroup.Vision in groups("summarise this article for me"))
        assertTrue(ToolGroup.Vision in groups("what does this say"))
    }

    @Test fun followUpKeepsTheThread() {
        val history = listOf(
            ChatMessage(role = ChatMessage.ROLE_USER, content = "call Anna"),
            ChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = "Shall I ring Anna on 0151?", tools = listOf("call"))
        )
        assertTrue(ToolGroup.Messages in groups("yes", history))
    }

    @Test fun selectNeverAddsSwitchedOffTools() {
        fun schema(name: String) = JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name))
        val all = listOf("remember", "recall", "now", "calculate", "show", "weather", "find_places",
            "send_message", "log_entry", "add_task", "news", "generate_image").map(::schema)
        val chosen = ToolRouter.select(all, "what's the weather", emptyList())
        val names = chosen.map { it.getJSONObject("function").getString("name") }.toSet()
        assertTrue("weather" in names)
        assertFalse("send_message" in names)
        assertFalse("generate_image" in names)
        assertTrue(names.all { n -> all.any { it.getJSONObject("function").getString("name") == n } })
    }

    @Test fun everyCatalogToolHasAGroupAndAliasesResolve() {
        val names = ToolCatalog.ALL.map { it.name }
        assertEquals("duplicate tool names", names.size, names.toSet().size)
        assertEquals("weather", ToolCatalog.resolve("get_weather", names.toSet()))
        assertEquals("generate_image", ToolCatalog.resolve("draw", names.toSet()))
        assertEquals("market_price", ToolCatalog.resolve("stock_price", names.toSet()))
        assertEquals("fun", ToolCatalog.resolve("tell_joke", names.toSet()))
    }

    @Test fun keylessProvidersAreBuiltInAndUnknownIdsAreCustom() {
        Providers.BUILT_IN.forEach { (id, model) ->
            val preset = Providers.byId(id)
            assertEquals(Tier.Keyless, preset.tier)
            assertFalse(preset.needsKey)
            assertTrue(model in preset.fallbackModels)
        }
        assertEquals(Providers.CUSTOM, Providers.byId("deepseek").id)
        assertEquals("", Providers.byId(Providers.POLLINATIONS).chatPath)
        assertEquals("/chat/completions", Providers.byId(Providers.GROQ).chatPath)
        assertTrue(Providers.ALL.none { it.label.contains("OpenAI") })
    }

    @Test fun addressPrefersExplicitChoiceThenNameThenPersona() {
        val base = Settings()
        assertEquals("sir", Personas.address(base.copy(personality = "jarvis")))
        assertEquals("Lukas", Personas.address(base.copy(userName = "Lukas")))
        assertEquals("boss", Personas.address(base.copy(userName = "Lukas", honorific = "boss")))
        assertEquals("", Personas.address(base.copy(personality = "friend")))
    }

    @Test fun repeatingTimesSkipToTheNextOneAhead() {
        val day = 86_400_000L
        val start = 1_700_000_000_000L
        // A week off: the next daily slot is the one after now, not tomorrow-a-week-ago.
        val now = start + 7 * day + 1000
        val next = TimeUtil.rollForward(start, "daily", now)!!
        assertTrue(next > now)
        assertTrue(next - now <= day)
        // Still ahead: one step exactly.
        assertEquals(start + 7 * day, TimeUtil.rollForward(start, "weekly", start - 1))
        assertEquals(null, TimeUtil.rollForward(start, "none", now))
    }

    @Test fun choosingACharacterSetsItsVoice() {
        val zen = Personas.choose(Settings(), "zen")
        assertEquals("zen", zen.personality)
        assertTrue(zen.speechRate < 1f)
        val custom = Personas.choose(Settings(speechRate = 1.3f), "custom")
        assertEquals(1.3f, custom.speechRate, 0f)
    }

    @Test fun numbersWithEitherDecimalMark() {
        assertEquals(50.0, Numbers.first("50%")!!, 0.0)
        assertEquals(2.5, Numbers.first("€2.50")!!, 0.0)
        assertEquals(2.5, Numbers.first("2,50 euro")!!, 0.0)
        assertEquals(1200.0, Numbers.first("1,200")!!, 0.0)
        assertEquals(1200.5, Numbers.first("1.200,50")!!, 0.0)
        assertEquals(1234567.89, Numbers.first("1,234,567.89")!!, 0.001)
        assertEquals(0.125, Numbers.first("0.125")!!, 0.0)
        assertEquals(30.0, Numbers.first("30 minutes")!!, 0.0)
        assertEquals(null, Numbers.first("none"))
    }

    @Test fun sentencesCutOnlyAtRealEnds() {
        val text = "It is 3.5 degrees outside today. Take a coat, sir. And gl"
        val first = Sentences.boundary(text, 0)
        assertEquals("It is 3.5 degrees outside today. Take a coat, sir.", text.substring(0, first))
        // Nothing new finished after that point.
        assertEquals(first, Sentences.boundary(text, first))
        // A short greeting waits for company.
        assertEquals(0, Sentences.boundary("Hi. I", 0))
        assertEquals(0, Sentences.boundary("No sentence ends here", 0))
        // Line breaks end a piece too.
        val list = "Here are three things you asked about:\n- one"
        assertEquals(list.indexOf('\n') + 1, Sentences.boundary(list, 0))
    }
}
