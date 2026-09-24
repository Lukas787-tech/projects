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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
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
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {

    private val dir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }
    private val report = StringBuilder()

    @Test
    fun renderEveryScreen() {
        val app = RuntimeEnvironment.getApplication() as JarvisApp
        val container = app.container
        runCatching { seed(container) }.onFailure { note("seed", it) }

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        shot(activity, "01-onboarding")

        container.settings.update { it.copy(onboarded = true, voiceMode = true, userName = "Lukas") }
        settle()
        shot(activity, "02-voice")

        container.settings.update { it.copy(voiceMode = false) }
        settle()
        shot(activity, "03-chat")

        val vm = ViewModelProvider(activity, AssistantViewModel.Factory)[AssistantViewModel::class.java]
        listOf(
            Element.Today to "04-today",
            Element.Notes to "05-memory",
            Element.Tasks to "06-tasks",
            Element.Money to "07-trackers",
            Element.Skills to "08-skills",
            Element.Settings to "09-settings",
            Element.Devices to "10-devices",
            Element.Music to "11-music"
        ).forEach { (element, name) ->
            runCatching { vm.showElement(element) }.onFailure { note(name, it) }
            settle()
            shot(activity, name)
        }

        runCatching { vm.showElement(Element.Globe) }
        container.settings.update {
            it.copy(voiceMode = true, accent = "crimson", backdrop = "oled", coreStyle = "orb")
        }
        settle()
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
    }

    /** Lets the frames, the database threads and the main thread catch up with each other. */
    private fun settle(millis: Long = 1600) {
        repeat(10) {
            Thread.sleep(80)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis / 10))
        }
    }

    private fun shot(activity: Activity, name: String) {
        runCatching {
            val view = activity.window.decorView
            check(view.width > 0 && view.height > 0) { "the window was never laid out" }
            val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(full))
            val small = Bitmap.createScaledBitmap(full, view.width / 2, view.height / 2, true)
            FileOutputStream(File(dir, "$name.png")).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { note(name, it) }
    }

    private fun note(what: String, error: Throwable) {
        report.appendLine("$what: ${error::class.java.simpleName}: ${error.message}")
        error.stackTrace.take(12).forEach { report.appendLine("    at $it") }
    }
}
