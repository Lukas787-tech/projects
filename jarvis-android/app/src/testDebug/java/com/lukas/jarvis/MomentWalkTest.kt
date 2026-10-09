package com.lukas.jarvis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.lukas.jarvis.moment.ActionIntent
import com.lukas.jarvis.moment.Composer
import com.lukas.jarvis.moment.Moment
import com.lukas.jarvis.moment.Samples
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
 * The dead-end walk, on the real canvas: every moment is composed, its primary
 * step is found on screen as something that can be tapped, and tapping it does
 * what the layout promised. A moment whose way forward is missing from the
 * screen fails the build.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MomentWalkTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyMomentHasAReachablePrimaryStep() {
        var moment by mutableStateOf<Moment>(Moment.Resting)
        val tapped = mutableListOf<ActionIntent>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MochiTheme(themeMode = "light", reduceMotion = true) {
                MomentPreview(moment, onAction = { tapped += it })
            }
        }
        val missing = mutableListOf<String>()
        Moment.ALL.forEach { next ->
            // The change has to be seen before the clock moves, or the frame
            // that draws it never comes and the screen is one moment behind.
            moment = next
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(800)
            rule.waitForIdle()
            val primary = Composer.compose(Samples.inputsFor(next)).primary
            val matcher = hasClickAction() and (hasText(primary.label) or hasContentDescription(primary.label))
            val nodes = rule.onAllNodes(matcher, useUnmergedTree = false).fetchSemanticsNodes()
            if (nodes.isEmpty()) {
                missing += "$next: '${primary.label}' is not on screen to tap"
                return@forEach
            }
            tapped.clear()
            rule.onAllNodes(matcher)[0].performClick()
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
            if (primary.intent !is ActionIntent.Type && primary.intent !in tapped) {
                missing += "$next: tapping '${primary.label}' did ${tapped.joinToString()} instead of ${primary.intent}"
            }
        }
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }
}
