package com.lukas.jarvis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.moment.Moment
import com.lukas.jarvis.ui.rooms.Shelf
import com.lukas.jarvis.ui.rooms.YouTab
import com.lukas.jarvis.ui.talk.MomentPreview
import com.lukas.jarvis.ui.theme.MochiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every tappable thing on every moment and in every room says what it is to
 * TalkBack and is at least a finger (48 dp) in both directions — at normal
 * and at large text. A button with no words or too small to hit fails CI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AccessibilityTest {

    @get:Rule
    val rule = createComposeRule()

    private val screens: List<Pair<String, @Composable () -> Unit>> =
        Moment.ALL.map { m -> "moment $m" to @Composable { MomentPreview(m) } } +
            listOf("today" to @Composable { com.lukas.jarvis.ui.rooms.TodaySample() }) +
            Shelf.entries.map { s -> "library $s" to @Composable { com.lukas.jarvis.ui.rooms.LibrarySample(s) } } +
            YouTab.entries.map { t -> "you $t" to @Composable { com.lukas.jarvis.ui.rooms.YouSample(t) } } +
            (0..5).map { i -> "intro $i" to @Composable { com.lukas.jarvis.ui.rooms.Intro(Settings(userName = "Lukas"), {}, {}, {}, initialStep = i) } } +
            listOf(
                "powers" to @Composable { com.lukas.jarvis.ui.rooms.PowersRoom(Settings(), {}, { _, _ -> }, {}) },
                "music" to @Composable {
                    com.lukas.jarvis.ui.rooms.MusicRoom(com.lukas.jarvis.control.NowPlaying("Sonne", "Rammstein", "Spotify", true, 1, 2), true, 50, com.lukas.jarvis.ui.rooms.MusicActions(onBack = {}))
                },
                "history" to @Composable {
                    com.lukas.jarvis.ui.rooms.HistoryRoom(
                        listOf(com.lukas.jarvis.data.ChatMessage(1, "user", "Hi", 1L), com.lukas.jarvis.data.ChatMessage(2, "assistant", "Hello!", 2L, tools = listOf("weather"))),
                        "Mochi",
                        com.lukas.jarvis.ui.rooms.HistoryActions(onBack = {})
                    )
                }
            )

    private fun words(node: SemanticsNode): String {
        val c = node.config
        return listOf(
            c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty(),
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty(),
            c.getOrNull(SemanticsActions.OnClick)?.label.orEmpty(),
            c.getOrNull(SemanticsProperties.StateDescription).orEmpty()
        ).joinToString(" ").trim()
    }

    @Test
    fun everyTapHasWordsAndRoomForAFinger() {
        var index by mutableIntStateOf(0)
        var text by mutableFloatStateOf(1f)
        rule.mainClock.autoAdvance = false
        rule.setContent { MochiTheme(themeMode = "light", textScale = text, reduceMotion = true) { screens[index].second() } }
        val problems = mutableListOf<String>()
        val finger = with(rule.density) { 48f * density } - 1f
        listOf(1f, 1.4f).forEach { scale ->
            text = scale
            screens.indices.forEach { i ->
                index = i
                rule.waitForIdle()
                rule.mainClock.advanceTimeBy(800)
                rule.waitForIdle()
                rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
                    val name = words(node)
                    val where = "${screens[i].first} at ${(scale * 100).toInt()}% text"
                    if (name.isBlank()) problems += "$where: a tappable thing with no words"
                    if (node.size.width < finger || node.size.height < finger) {
                        problems += "$where: '${name.take(40)}' is ${node.size.width}x${node.size.height}px"
                    }
                }
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }
}
