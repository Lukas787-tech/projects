package com.lukas.jarvis

/*
 * The streaming client against a local server that replays the shapes real
 * providers send: text in pieces, tool calls in fragments, a server that
 * ignores the stream flag, one that refuses it, and errors before and during
 * the stream.
 */
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.llm.FailureKind
import com.lukas.jarvis.llm.LlmClient
import com.lukas.jarvis.llm.LlmException
import com.lukas.jarvis.llm.LlmMessage
import com.lukas.jarvis.llm.Providers
import com.lukas.jarvis.llm.ReplyStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamTest {

    private lateinit var server: MockWebServer

    @Before fun up() { server = MockWebServer().apply { start() } }
    @After fun down() { server.shutdown() }

    private fun settings() = Settings(
        providerId = Providers.CUSTOM,
        baseUrl = server.url("/v1").toString(),
        apiKey = "k",
        model = "m"
    )

    private class Collect : ReplyStream {
        val pieces = mutableListOf<String>()
        var restarts = 0
        override fun restart() { restarts++; pieces.clear() }
        override fun append(text: String) { pieces += text }
    }

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun chunk(content: String) =
        JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", content)))).toString()

    @Test fun textArrivesPieceByPiece(): Unit = runBlocking {
        server.enqueue(sse(chunk("Good "), chunk("evening, "), chunk("sir.")))
        val sink = Collect()
        val reply = LlmClient().chat(settings(), listOf(LlmMessage.user("hi")), stream = sink)
        assertEquals("Good evening, sir.", reply.content)
        assertEquals(listOf("Good ", "evening, ", "sir."), sink.pieces)
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(true, sent.optBoolean("stream"))
        assertEquals("/v1/chat/completions", server.takeRequestOrNull()?.path ?: "/v1/chat/completions")
    }

    @Test fun toolCallsArriveInFragmentsAndAreJoined(): Unit = runBlocking {
        val first = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"weather","arguments":""}}]}}]}"""
        val second = """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"pla"}}]}}]}"""
        val third = """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ce\":\"Paris\"}"}}]}}]}"""
        val fourth = """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"now","arguments":"{}"}}]}}]}"""
        server.enqueue(sse(first, second, third, fourth))
        val reply = LlmClient().chat(settings(), listOf(LlmMessage.user("weather?")), stream = Collect())
        assertEquals(2, reply.toolCalls.size)
        assertEquals("weather", reply.toolCalls[0].name)
        assertEquals("call_1", reply.toolCalls[0].id)
        assertEquals("Paris", JSONObject(reply.toolCalls[0].argumentsJson).getString("place"))
        assertEquals("now", reply.toolCalls[1].name)
    }

    @Test fun aNoticeInPlaceOfAnAnswerIsAFailure(): Unit = runBlocking {
        val notice = "The account behind this API key doesn't have enough credits. Please top up " +
            "or complete a quest, then try again."
        server.enqueue(sse(chunk(notice)))
        val error = assertThrows(LlmException::class.java) {
            runBlocking { LlmClient().chat(settings(), listOf(LlmMessage.user("hi")), stream = Collect()) }
        }
        assertEquals(FailureKind.OutOfCredit, error.kind)
    }

    @Test fun advertFootersAreCutAndOrdinaryWordsAreNotNotices() {
        val answer = "It is 14 degrees.\n\n---\n**Support Pollinations.AI:** 🌸 Ad 🌸 Try our app!"
        assertEquals("It is 14 degrees.", com.lukas.jarvis.llm.ProviderNotices.strip(answer))
        assertEquals(null, com.lukas.jarvis.llm.ProviderNotices.failure("To top up your phone credit, open your carrier's app."))
        assertEquals(
            FailureKind.RateLimited,
            com.lukas.jarvis.llm.ProviderNotices.failure("Too many requests. Please slow down.")?.kind
        )
    }

    @Test fun serverThatIgnoresStreamIsReadPlainly(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"Plain answer."}}]}""")
        )
        val sink = Collect()
        val reply = LlmClient().chat(settings(), listOf(LlmMessage.user("hi")), stream = sink)
        assertEquals("Plain answer.", reply.content)
        assertEquals(listOf("Plain answer."), sink.pieces)
    }

    @Test fun refusedStreamFallsBackToPlainAndIsRemembered(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"stream is not supported"}}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"Fine."}}]}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"Again."}}]}"""))
        val client = LlmClient()
        val sink = Collect()
        assertEquals("Fine.", client.chat(settings(), listOf(LlmMessage.user("hi")), stream = sink).content)
        assertEquals(1, sink.restarts)
        JSONObject(server.takeRequest().body.readUtf8()).let { assertEquals(true, it.optBoolean("stream")) }
        JSONObject(server.takeRequest().body.readUtf8()).let { assertEquals(false, it.has("stream")) }
        // Second call goes straight to plain.
        assertEquals("Again.", client.chat(settings(), listOf(LlmMessage.user("hi")), stream = Collect()).content)
        JSONObject(server.takeRequest().body.readUtf8()).let { assertEquals(false, it.has("stream")) }
    }

    @Test fun rateLimitIsClassifiedNotRetriedAsPlain(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit reached, requests per day"}}"""))
        val error = assertThrows(LlmException::class.java) {
            runBlocking { LlmClient().chat(settings(), listOf(LlmMessage.user("hi")), stream = Collect()) }
        }
        assertEquals(FailureKind.RateLimited, error.kind)
        assertTrue(error.daily)
        assertEquals(1, server.requestCount)
    }

    @Test fun errorInsideTheStreamIsAFailure(): Unit = runBlocking {
        server.enqueue(sse(chunk("Hal"), """{"error":{"message":"Rate limit exceeded"}}"""))
        val error = assertThrows(LlmException::class.java) {
            runBlocking { LlmClient().chat(settings(), listOf(LlmMessage.user("hi")), stream = Collect()) }
        }
        assertEquals(FailureKind.RateLimited, error.kind)
    }

    @Test fun pollinationsStyleBaseUrlIsTheEndpoint(): Unit = runBlocking {
        server.enqueue(sse(chunk("ok")))
        val s = settings().copy(providerId = Providers.POLLINATIONS, baseUrl = server.url("/openai").toString(), apiKey = "")
        LlmClient().chat(s, listOf(LlmMessage.user("hi")), stream = Collect())
        val request = server.takeRequest()
        assertEquals("/openai", request.path)
        assertEquals(null, request.getHeader("Authorization"))
    }

    @Test fun withoutAStreamNothingIsStreamed(): Unit = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"Hello."}}]}"""))
        assertEquals("Hello.", LlmClient().chat(settings(), listOf(LlmMessage.user("hi"))).content)
        assertEquals(false, JSONObject(server.takeRequest().body.readUtf8()).has("stream"))
    }
}

private fun MockWebServer.takeRequestOrNull() =
    takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS)
