package com.lukas.jarvis

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.lukas.jarvis.moment.AskKind
import com.lukas.jarvis.moment.CreateKind
import com.lukas.jarvis.moment.Moment
import com.lukas.jarvis.moment.ShowKind
import com.lukas.jarvis.llm.ToolGroup
import com.lukas.jarvis.ui.kit.KitGallery
import com.lukas.jarvis.ui.talk.MomentPreview
import com.lukas.jarvis.ui.theme.MochiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** One look: the theme, the text size, and a short name for the file. */
private data class Look(val name: String, val theme: String, val text: Float = 1f)

private val dir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }

private fun ComponentActivity.shoot(name: String) {
    val view = window.decorView
    if (view.width == 0 || view.height == 0) return
    val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(full))
    val small = Bitmap.createScaledBitmap(full, view.width * 3 / 5, view.height * 3 / 5, true)
    FileOutputStream(File(dir, "$name.png")).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/**
 * Pictures of the café: the component kit and every moment of the canvas, in
 * the latte and night cafés and with large text. Kept as workflow artifacts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class CafeShotsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val looks = listOf(Look("light", "light"), Look("dark", "dark"), Look("large", "light", 1.3f))

    @Test
    fun kitAndMoments() {
        var look by mutableStateOf(looks.first())
        var moment by mutableStateOf<Moment?>(null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MochiTheme(themeMode = look.theme, textScale = look.text, reduceMotion = true) {
                val shown = moment
                if (shown == null) KitGallery() else MomentPreview(shown)
            }
        }
        looks.forEach { next ->
            look = next
            moment = null
            settle()
            rule.activity.shoot("kit-${next.name}")
            val moments = if (next.name == "large") SELECTED else Moment.ALL
            moments.forEach { m ->
                moment = m
                settle()
                rule.activity.shoot("moment-${next.name}-${fileName(m)}")
            }
        }
    }

    /** Lets a change be seen, then gives it the frames to be drawn. */
    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(900)
        rule.waitForIdle()
    }
}

/** The same canvas turned on its side. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xhdpi")
class CafeLandscapeShotsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun moments() {
        var moment by mutableStateOf<Moment>(Moment.Resting)
        rule.mainClock.autoAdvance = false
        rule.setContent { MochiTheme(themeMode = "light", reduceMotion = true) { MomentPreview(moment) } }
        SELECTED.forEach { m ->
            moment = m
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(900)
            rule.waitForIdle()
            rule.activity.shoot("moment-land-${fileName(m)}")
        }
    }
}

private val SELECTED = listOf(
    Moment.Resting,
    Moment.Listening,
    Moment.Working(ToolGroup.Weather),
    Moment.Showing(ShowKind.Map),
    Moment.Creating(CreateKind.List),
    Moment.Asking(AskKind.Confirmation),
    Moment.Recovering(offline = true)
)

private fun fileName(moment: Moment): String =
    moment.toString().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/** Every room, so a room's look is checked like the canvas's. */
private val ROOMS: List<Pair<String, @androidx.compose.runtime.Composable () -> Unit>> = listOf(
    "today" to { com.lukas.jarvis.ui.rooms.TodaySample() },
    "library-memory" to { com.lukas.jarvis.ui.rooms.LibrarySample(com.lukas.jarvis.ui.rooms.Shelf.Memory) },
    "library-lists" to { com.lukas.jarvis.ui.rooms.LibrarySample(com.lukas.jarvis.ui.rooms.Shelf.Lists) },
    "library-money" to { com.lukas.jarvis.ui.rooms.LibrarySample(com.lukas.jarvis.ui.rooms.Shelf.Money) },
    "library-tasks" to { com.lukas.jarvis.ui.rooms.LibrarySample(com.lukas.jarvis.ui.rooms.Shelf.Tasks) },
    "you" to { com.lukas.jarvis.ui.rooms.YouSample(com.lukas.jarvis.ui.rooms.YouTab.You) },
    "you-look" to { com.lukas.jarvis.ui.rooms.YouSample(com.lukas.jarvis.ui.rooms.YouTab.Look) },
    "you-brain" to { com.lukas.jarvis.ui.rooms.YouSample(com.lukas.jarvis.ui.rooms.YouTab.Brain) },
    "you-powers" to { com.lukas.jarvis.ui.rooms.YouSample(com.lukas.jarvis.ui.rooms.YouTab.Powers) },
    "you-data" to { com.lukas.jarvis.ui.rooms.YouSample(com.lukas.jarvis.ui.rooms.YouTab.Data) },
    "intro-hello" to { com.lukas.jarvis.ui.rooms.Intro(com.lukas.jarvis.core.Settings(userName = "Lukas"), {}, {}, {}) },
    "intro-privacy" to { com.lukas.jarvis.ui.rooms.Intro(com.lukas.jarvis.core.Settings(userName = "Lukas"), {}, {}, {}, initialStep = 5) },
    "powers" to { com.lukas.jarvis.ui.rooms.PowersRoom(com.lukas.jarvis.core.Settings(), {}, { _, _ -> }, {}) },
    "music" to {
        com.lukas.jarvis.ui.rooms.MusicRoom(com.lukas.jarvis.control.NowPlaying("Sonne", "Rammstein", "Spotify", true, 60_000, 270_000), true, 60, com.lukas.jarvis.ui.rooms.MusicActions(onBack = {}))
    }
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class CafeRoomShotsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rooms() {
        var look by mutableStateOf(Look("light", "light"))
        var index by mutableStateOf(0)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MochiTheme(themeMode = look.theme, textScale = look.text, reduceMotion = true) { ROOMS[index].second() }
        }
        listOf(Look("light", "light"), Look("dark", "dark"), Look("large", "light", 1.3f)).forEach { next ->
            look = next
            ROOMS.indices.forEach { i ->
                index = i
                rule.waitForIdle()
                rule.mainClock.advanceTimeBy(900)
                rule.waitForIdle()
                rule.activity.shoot("room-${next.name}-${ROOMS[i].first}")
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xhdpi")
class CafeRoomLandscapeShotsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rooms() {
        var index by mutableStateOf(0)
        rule.mainClock.autoAdvance = false
        rule.setContent { MochiTheme(themeMode = "light", reduceMotion = true) { ROOMS[index].second() } }
        listOf(0, 1, 5, 10).forEach { i ->
            index = i
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(900)
            rule.waitForIdle()
            rule.activity.shoot("room-land-${ROOMS[i].first}")
        }
    }
}
