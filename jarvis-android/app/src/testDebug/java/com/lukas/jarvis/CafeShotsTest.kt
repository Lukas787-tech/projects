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

    private fun settle() {
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
