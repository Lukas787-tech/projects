package com.lukas.jarvis

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.vm.AssistantViewModel
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/*
 * Pictures of the real app, for looking at the design without a phone.
 *
 * Not an assertion of anything: it starts the actual activity with a believable
 * day's worth of data, walks it through its screens and a few looks, and writes
 * each frame as a PNG. The screenshot workflow commits them to docs/screens.
 * Whatever fails is written beside them rather than stopping the walk.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreensTest {

    private val dir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }
    private val report = StringBuilder()

    @Before
    fun realFrames() {
        // By default Robolectric's choreographer moves the clock on by itself
        // with every frame, so an animation that never ends — the core's —
        // keeps the looper busy forever and the activity never finishes
        // starting. Paused, frames come only as the test moves time on.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))

        // Robolectric's default network has no internet, which the header now
        // reports honestly; the pictures are of a phone that is online.
        runCatching {
            val manager = RuntimeEnvironment.getApplication()
                .getSystemService(android.net.ConnectivityManager::class.java)
            val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
            shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            shadowOf(caps).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, caps)
        }

        // A phone standing in Berlin, so the weather, the HUD and the map have
        // somewhere to be — the forecast itself is fetched live.
        runCatching {
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
            val locations = app.getSystemService(android.location.LocationManager::class.java)
            val here = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
                latitude = 52.5200
                longitude = 13.4050
                accuracy = 12f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            }
            shadowOf(locations).setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
            shadowOf(locations).setLastKnownLocation(android.location.LocationManager.GPS_PROVIDER, here)
        }
    }

    @Test
    fun renderEveryScreen() {
        val app = RuntimeEnvironment.getApplication() as JarvisApp
        val container = app.container
        runCatching { seed(container) }.onFailure { note("seed", it) }

        progress("seeded; starting the activity")
        val watchdog = watchdog()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create()
        progress("created")
        controller.start()
        progress("started")
        controller.postCreate(null)
        controller.resume()
        progress("resumed")
        controller.visible()
        progress("visible")
        controller.topActivityResumed(true)
        val activity = controller.get()
        watchdog.interrupt()
        progress("activity started")
        settle()
        shot(activity, "01-onboarding")

        container.settings.update { it.copy(onboarded = true, voiceMode = true, userName = "Lukas") }
        settle()
        shot(activity, "02-voice")

        container.settings.update { it.copy(voiceMode = false) }
        settle()
        shot(activity, "03-chat")

        val vm = ViewModelProvider(activity, AssistantViewModel.Factory)[AssistantViewModel::class.java]

        // A real turn against the free keyless models, tool call and all: the
        // one picture that shows the assistant actually thinking.
        // A fresh thread, so question and answer sit at the top: the list's
        // scroll to the newest line is an animation the test clock does not run.
        runCatching { vm.newConversation() }
        settle(400)
        runCatching { vm.sendTyped("Add eggs to my shopping list, then tell me what's on it.") }
            .onFailure { note("live turn", it) }
        live(seconds = 50)
        shot(activity, "03b-live-answer")

        // Two tools in one sentence: a timer that should appear in the strip,
        // and a sum the phone works out rather than the model.
        runCatching { vm.sendTyped("Set a 3 minute timer for tea, and what is 17% of 240?") }
            .onFailure { note("second live turn", it) }
        live(seconds = 50)
        shot(activity, "03c-live-two-tools")

        // The web services: a forecast for a named city (geocoding, then
        // Open-Meteo) and a translation, both keyless.
        runCatching { vm.newConversation() }
        settle(400)
        runCatching { vm.sendTyped("What's the weather in Berlin tomorrow? And how do you say thank you in Japanese?") }
            .onFailure { note("third live turn", it) }
        live(seconds = 60)
        shot(activity, "03d-live-web")
        listOf(
            Element.Today to "04-today",
            Element.Notes to "05-memory",
            Element.Tasks to "06-tasks",
            Element.Money to "07-trackers",
            Element.Lists to "07b-lists",
            Element.Skills to "08-skills",
            Element.Devices to "10-devices",
            Element.Music to "11-music"
        ).forEach { (element, name) ->
            runCatching { vm.showElement(element) }.onFailure { note(name, it) }
            settle()
            shot(activity, name)
        }

        listOf("You", "Voice", "Look", "Brain", "Powers", "Data").forEachIndexed { index, tab ->
            runCatching { vm.showElement(Element.Settings, "settings:tab:$index") }
            settle()
            shot(activity, "09-settings-$index-${tab.lowercase()}")
        }

        // One Berlin tile fetched straight through the cache, so a blank map
        // in the picture can be told apart from a cache that cannot load.
        val probe = Thread {
            val result = runCatching {
                kotlinx.coroutines.runBlocking {
                    container.tiles.tile(com.lukas.jarvis.maps.MapStyle.Dark, 15, 17605, 10746)
                }
            }
            progress(
                "tile probe: " + result.fold(
                    { bitmap -> if (bitmap == null) "no tile" else "tile ${bitmap.width}x${bitmap.height}" },
                    { error -> "failed ${error::class.java.simpleName}: ${error.message}" }
                )
            )
        }.apply { start() }
        probe.join(20_000)

        runCatching { vm.showElement(Element.Globe, com.lukas.jarvis.stage.StageStore.HISTORY) }
        settle(1200)
        shot(activity, "16-history")

        runCatching { vm.showElement(Element.Map) }
        settle(2400)
        // The tiles come over the network, which needs real seconds.
        live(seconds = 12)
        shot(activity, "15-map")

        // The core powers up over a second and a half each time it appears,
        // an animation Robolectric's paused frame clock does not carry on a
        // later screen. Calm motion starts it at full power, which is how it
        // looks on a phone a moment later anyway.
        runCatching { vm.showElement(Element.Globe) }
        container.settings.update {
            it.copy(voiceMode = true, accent = "crimson", backdrop = "oled", coreStyle = "orb", reduceMotion = true)
        }
        // Long enough for the core's power-up, which starts again after the map.
        settle(3200)
        shot(activity, "12-voice-crimson-orb")

        container.settings.update {
            it.copy(accent = "stark", backdrop = "nebula", coreStyle = "globe")
        }
        settle()
        shot(activity, "13-voice-stark-globe")

        container.settings.update {
            it.copy(voiceMode = false, accent = "emerald", backdrop = "graphite", coreStyle = "reactor")
        }
        settle()
        shot(activity, "14-chat-emerald")

        File(dir, "report.txt").writeText(report.ifEmpty { "Every screen rendered." }.toString())
    }

    private fun seed(container: AppContainer) {
        val brain = container.brain
        val now = System.currentTimeMillis()
        val minute = 60_000L
        listOf(
            ChatMessage(role = ChatMessage.ROLE_USER, content = "What's on today?", createdAt = now - 9 * minute),
            ChatMessage(
                role = ChatMessage.ROLE_ASSISTANT,
                content = "Morning, Lukas. **14°** and cloudy, rain likely from about 15:00 — take the umbrella.\n\n" +
                    "- Dentist at 10:30\n- Call the landlord\n- Gym after work\n\nYou have **€212** left of this month's food budget.",
                createdAt = now - 9 * minute + 4000,
                tools = listOf("briefing", "weather", "calendar")
            ),
            ChatMessage(role = ChatMessage.ROLE_USER, content = "Remind me to buy oat milk at six", createdAt = now - 3 * minute),
            ChatMessage(
                role = ChatMessage.ROLE_ASSISTANT,
                content = "Done — I'll remind you at 18:00 to buy oat milk.",
                createdAt = now - 3 * minute + 3000,
                tools = listOf("add_task")
            )
        ).forEach { brain.addMessage(it) }

        listOf(
            Memory(kind = Memory.KIND_PREFERENCE, content = "Prefers oat milk in coffee", tags = listOf("food")),
            Memory(kind = Memory.KIND_PERSON, content = "Sister Anna lives in Hamburg, birthday 12 March", tags = listOf("family"), pinned = true),
            Memory(kind = Memory.KIND_FACT, content = "Bike lock code is kept in the password manager"),
            Memory(kind = Memory.KIND_IDEA, content = "Build a weather station on the balcony", tags = listOf("projects")),
            Memory(kind = Memory.KIND_PLACE, content = "Favourite ramen: Takumi on Immermannstraße", tags = listOf("food"))
        ).forEach { brain.addMemory(it) }

        listOf(
            Task(title = "Buy oat milk", dueAt = now + 4 * 60 * minute),
            Task(title = "Call the landlord", dueAt = now + 60 * minute),
            Task(title = "Water the plants", dueAt = now + 26 * 60 * minute, repeatRule = Task.REPEAT_WEEKLY),
            Task(title = "Renew passport", dueAt = now - 24 * 60 * minute),
            Task(title = "Book train to Hamburg", done = true, completedAt = now - 60 * minute)
        ).forEach { brain.addTask(it) }

        val food = brain.upsertTracker(Tracker(name = "food", label = "Food", unit = "EUR", budget = 400.0))
        val gym = brain.upsertTracker(Tracker(name = "gym", label = "Gym", kind = Tracker.KIND_COUNT, unit = "sessions", period = Tracker.PERIOD_WEEKLY))
        listOf(38.5 to "Groceries", 12.9 to "Ramen", 4.2 to "Coffee", 132.4 to "Big shop").forEachIndexed { i, (amount, note) ->
            brain.addEntry(Entry(trackerId = food.id, amount = amount, note = note, occurredAt = now - i * 26 * 60 * minute))
        }
        repeat(3) { i -> brain.addEntry(Entry(trackerId = gym.id, amount = 1.0, occurredAt = now - i * 48 * 60 * minute)) }

        container.timers.start(7 * 60 + 30, "Pasta")
        container.timers.start(42 * 60, "Laundry")

        container.lists.change { book ->
            book.add("shopping", listOf("Oat milk", "Free-range eggs", "Sourdough", "Basil", "Parmesan"))
                .check("shopping", listOf("basil", "sourdough"), true).first
                .add("packing", listOf("Passport", "Charger", "Swimming shorts"))
        }
    }

    /**
     * Real time for a network turn: the model is on the internet, and its
     * reply comes back on other threads, so the clock here is a wall clock.
     */
    private fun live(seconds: Int) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < until) {
            Thread.sleep(400)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
        progress("waited $seconds s for a live turn")
    }

    /** Lets the frames, the database threads and the main thread catch up with each other. */
    private fun settle(millis: Long = 1600) {
        val began = System.currentTimeMillis()
        repeat(10) {
            Thread.sleep(80)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis / 10))
        }
        progress("settled in ${System.currentTimeMillis() - began} ms")
    }

    private fun shot(activity: Activity, name: String) {
        runCatching {
            val view = activity.window.decorView
            check(view.width > 0 && view.height > 0) { "the window was never laid out" }
            val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(full))
            val small = Bitmap.createScaledBitmap(full, view.width * 3 / 5, view.height * 3 / 5, true)
            FileOutputStream(File(dir, "$name.png")).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            progress("rendered $name")
        }.onFailure { note(name, it) }
    }

    private fun note(what: String, error: Throwable) {
        report.appendLine("$what: ${error::class.java.simpleName}: ${error.message}")
        error.stackTrace.take(12).forEach { report.appendLine("    at $it") }
        progress("failed $what: ${error.message}")
    }

    /**
     * If starting the activity stalls, every thread's stack is written out
     * each minute, which says exactly what the main thread is stuck on.
     */
    private fun watchdog(): Thread = Thread {
        try {
            repeat(8) { round ->
                Thread.sleep(45_000)
                val dump = Thread.getAllStackTraces().entries
                    .sortedByDescending { it.key.name == "main" || it.key.name.startsWith("Test worker") }
                    .joinToString("\n\n") { (thread, stack) ->
                        "${thread.name} (${thread.state})\n" + stack.take(45).joinToString("\n") { "    at $it" }
                    }
                File(dir, "stall-$round.txt").writeText(dump)
                println("[screens] stall dump $round written")
            }
        } catch (_: InterruptedException) {
        }
    }.apply {
        isDaemon = true
        start()
    }

    /** Written as it goes, so a run that hangs still says how far it got. */
    private fun progress(line: String) {
        println("[screens] $line")
        runCatching { File(dir, "progress.txt").appendText("${System.currentTimeMillis()} $line\n") }
    }
}
