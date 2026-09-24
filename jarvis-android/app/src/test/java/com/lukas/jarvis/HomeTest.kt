package com.lukas.jarvis

/*
 * The house, against a local stand-in for Home Assistant: names find the
 * right devices, "all lights" reaches the domain, a room reaches everything
 * in it, each kind of device gets the right service — and a name that matches
 * nothing does nothing, rather than acting on the nearest guess.
 */
import com.lukas.jarvis.web.Home
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeTest {

    private lateinit var server: MockWebServer
    private val calls = mutableListOf<Pair<String, JSONObject>>()

    private val states = """
        [
          {"entity_id":"light.living_room_ceiling","state":"on","attributes":{"friendly_name":"Living Room Ceiling","brightness":128}},
          {"entity_id":"light.living_room_lamp","state":"off","attributes":{"friendly_name":"Living Room Lamp"}},
          {"entity_id":"light.bedroom","state":"on","attributes":{"friendly_name":"Bedroom Light"}},
          {"entity_id":"lock.front_door","state":"locked","attributes":{"friendly_name":"Front Door"}},
          {"entity_id":"climate.hallway","state":"heat","attributes":{"friendly_name":"Hallway Thermostat","current_temperature":19.5,"temperature":20}},
          {"entity_id":"scene.movie_night","state":"scening","attributes":{"friendly_name":"Movie Night"}},
          {"entity_id":"sensor.outside_temp","state":"12.3","attributes":{"friendly_name":"Outside","device_class":"temperature","unit_of_measurement":"°C"}}
        ]
    """.trimIndent()

    @Before fun up() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer token") return MockResponse().setResponseCode(401)
                val path = request.path.orEmpty()
                return when {
                    path == "/api/states" -> MockResponse().setBody(states)
                    path.startsWith("/api/services/") -> {
                        calls += path.removePrefix("/api/services/") to JSONObject(request.body.readUtf8())
                        MockResponse().setBody("[]")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun down() { server.shutdown() }

    private val base get() = server.url("/").toString()

    @Test fun switchesOneLightByItsName(): Unit = runBlocking {
        val reply = Home().control(base, "token", "living room lamp", "on", null)
        assertEquals(listOf("light/turn_on"), calls.map { it.first })
        assertEquals("light.living_room_lamp", calls.single().second.getString("entity_id"))
        assertTrue(reply, reply.startsWith("Done"))
    }

    @Test fun allLightsReachesTheWholeDomain(): Unit = runBlocking {
        Home().control(base, "token", "all lights", "off", null)
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.first == "light/turn_off" })
    }

    @Test fun roomWordsReachEveryDeviceInIt(): Unit = runBlocking {
        Home().control(base, "token", "living room", "off", null)
        assertEquals(setOf("light.living_room_ceiling", "light.living_room_lamp"), calls.map { it.second.getString("entity_id") }.toSet())
    }

    @Test fun dimmingThermostatsLocksAndScenes(): Unit = runBlocking {
        Home().control(base, "token", "bedroom light", "set_brightness", 30.0)
        Home().control(base, "token", "hallway thermostat", "set_temperature", 21.0)
        Home().control(base, "token", "front door", "unlock", null)
        Home().control(base, "token", "movie night", "activate", null)
        assertEquals(listOf("light/turn_on", "climate/set_temperature", "lock/unlock", "scene/turn_on"), calls.map { it.first })
        assertEquals(30, calls[0].second.getInt("brightness_pct").toLong())
        assertEquals(21.0, calls[1].second.getDouble("temperature"), 0.001)
    }

    @Test fun unknownNameSuggestsTheClosest(): Unit = runBlocking {
        val reply = Home().control(base, "token", "garage door", "open", null)
        assertTrue(calls.isEmpty())
        assertTrue(reply, reply.startsWith("Nothing called"))
    }

    @Test fun statusOverviewAndBadToken(): Unit = runBlocking {
        val overview = Home().status(base, "token", null)
        assertTrue(overview, overview.contains("Living Room Ceiling") && overview.contains("Bedroom Light"))
        assertTrue(overview, overview.contains("Hallway Thermostat"))
        assertTrue(Home().check(base, "wrong").contains("401"))
        assertTrue(Home().check(base, "token").startsWith("Connected"))
    }
}
