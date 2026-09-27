package com.lukas.jarvis

/*
 * The Fish Audio voice, against a local stand-in for api.fish.audio: the key
 * and model travel as headers, the voice and speed in the body, a sentence
 * said twice is fetched once, a paid model refused on a free account falls
 * back to the free S2.1 tier, and a refused key is an error, not silence.
 */
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.voice.FishConfig
import com.lukas.jarvis.voice.FishVoice
import com.lukas.jarvis.voice.Sentences
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class FishVoiceTest {

    private lateinit var server: MockWebServer
    private lateinit var voice: FishVoice
    private val sound = Buffer().write(ByteArray(4_000) { (it % 251).toByte() })

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        voice = FishVoice(Files.createTempDirectory("fish").toFile(), server.url("").toString().trimEnd('/'))
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun audio() = MockResponse().setBody(sound.clone()).setHeader("Content-Type", "audio/mpeg")

    @Test
    fun sendsKeyModelVoiceAndSpeed() {
        server.enqueue(audio())
        val file = voice.synthesize("Guten Morgen.", FishConfig("sk-test", "802e3bc2b27e49c2995d23ef70e6ac89", "s2.1-pro", 1.25f))
        assertEquals(4_000L, file.length())
        val request = server.takeRequest()
        assertEquals("/v1/tts", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        assertEquals("s2.1-pro", request.getHeader("model"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("Guten Morgen.", body.getString("text"))
        assertEquals("802e3bc2b27e49c2995d23ef70e6ac89", body.getString("reference_id"))
        assertEquals("mp3", body.getString("format"))
        assertEquals(1.25, body.getJSONObject("prosody").getDouble("speed"), 0.001)
    }

    @Test
    fun noVoiceLetsFishChoose() {
        server.enqueue(audio())
        voice.synthesize("Hello.", FishConfig("sk-test"))
        assertTrue(!JSONObject(server.takeRequest().body.readUtf8()).has("reference_id"))
    }

    @Test
    fun aSentenceSaidTwiceIsFetchedOnce() {
        server.enqueue(audio())
        val config = FishConfig("sk-test")
        val first = voice.synthesize("Done.", config)
        val second = voice.synthesize("Done.", config)
        assertEquals(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aPaidModelRefusedFallsBackToTheFreeTier() {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"message":"Insufficient balance"}"""))
        server.enqueue(audio())
        server.enqueue(audio())
        val config = FishConfig("sk-test", model = "s2.1-pro")
        voice.synthesize("One.", config)
        assertEquals("s2.1-pro", server.takeRequest().getHeader("model"))
        assertEquals(FishVoice.FREE_MODEL, server.takeRequest().getHeader("model"))
        // Remembered: the next sentence goes straight to the free tier.
        voice.synthesize("Two.", config)
        assertEquals(FishVoice.FREE_MODEL, server.takeRequest().getHeader("model"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aRefusedKeyIsAnError() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Invalid token"}"""))
        try {
            voice.synthesize("Hello.", FishConfig("sk-wrong"))
            fail("a refused key must not pass as audio")
        } catch (e: FishVoice.FishError) {
            assertEquals(401, e.code)
            assertEquals("Fish Audio did not accept the key", e.message)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anEmptyAnswerIsNotAudio() {
        server.enqueue(MockResponse().setBody("{}"))
        try {
            voice.synthesize("Hello.", FishConfig("sk-test"))
            fail("an empty answer must not be played")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("no audio"))
        }
    }

    @Test
    fun listsVoicesFromTheLibrary() {
        server.enqueue(
            MockResponse().setBody(
                """{"total":3,"items":[
                  {"_id":"aaaabbbbccccddddeeeeffff00001111","type":"tts","title":"Deep Narrator","languages":["en","de"],"task_count":1200},
                  {"_id":"22223333444455556666777788889999","type":"svc","title":"Singer","languages":["en"]},
                  {"_id":"99998888777766665555444433332222","type":"tts","title":" Anna ","languages":["de"]}
                ]}"""
            )
        )
        val found = voice.voices("sk-test", query = "narrator", language = "de")
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/model?"))
        assertEquals("narrator", request.requestUrl!!.queryParameter("title"))
        assertEquals("de", request.requestUrl!!.queryParameter("language"))
        assertNull(request.requestUrl!!.queryParameter("self"))
        assertEquals(listOf("Deep Narrator", "Anna"), found.map { it.title })
        assertEquals(listOf("en", "de"), found[0].languages)
        assertEquals(1200, found[0].uses)
    }

    @Test
    fun aPastedLinkBecomesAVoiceId() {
        assertEquals(
            "802e3bc2b27e49c2995d23ef70e6ac89",
            FishVoice.voiceIdOf("https://fish.audio/m/802e3bc2b27e49c2995d23ef70e6ac89/")
        )
        assertEquals("narrator", FishVoice.voiceIdOf(" narrator "))
    }

    @Test
    fun onlyTheFishEngineWithAKeyUsesTheCloud() {
        assertNull(FishConfig.from(Settings(voiceEngine = "device", fishKey = "sk-1")))
        assertNull(FishConfig.from(Settings(voiceEngine = "fish", fishKey = " ")))
        val config = FishConfig.from(Settings(voiceEngine = "fish", fishKey = " sk-1 ", fishVoiceId = "abc", speechRate = 1.3f))!!
        assertEquals("sk-1", config.key)
        assertEquals("abc", config.voiceId)
        assertEquals("s2.1-pro", config.model)
        assertEquals(1.3f, config.speed, 0.001f)
    }

    @Test
    fun theFirstSentenceStartsAlone() {
        val pieces = Sentences.forCloud("Sure. The weather in Paris is mild today. Expect rain after six. Take an umbrella.")
        assertEquals("Sure.", pieces.first())
        assertEquals(2, pieces.size)
        assertEquals("The weather in Paris is mild today. Expect rain after six. Take an umbrella.", pieces[1])
        val long = (1..40).joinToString(" ") { "Sentence number $it is here." }
        assertTrue(Sentences.forCloud(long).all { it.length <= 280 })
        assertEquals(listOf("Hello"), Sentences.forCloud("Hello"))
    }
}
