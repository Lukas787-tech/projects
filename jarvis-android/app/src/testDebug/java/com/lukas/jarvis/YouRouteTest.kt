package com.lukas.jarvis

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.lifecycle.ViewModelProvider
import com.lukas.jarvis.ui.rooms.YouRoute
import com.lukas.jarvis.ui.rooms.YouTab
import com.lukas.jarvis.ui.theme.MochiTheme
import com.lukas.jarvis.vm.AssistantViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * The You room as the phone opens it: the real route, with the app's real
 * view model and its flows, the launchers and the system checks, not the
 * sample state the pictures use. Every shelf is opened and scrolled to its
 * end, so each card on it is composed at least once.
 *
 * A tall window, so a whole shelf is laid out at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h4000dp-mdpi")
class YouRouteTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun everyShelfOfTheRealRouteOpens() {
        val app = rule.activity.application as JarvisApp
        val viewModel = ViewModelProvider(rule.activity, AssistantViewModel.Factory)[AssistantViewModel::class.java]
        var tab by mutableStateOf(YouTab.You)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MochiTheme(themeMode = "light", reduceMotion = true) { YouRoute(viewModel, tab) }
        }
        listOf(false, true).forEach { dot ->
            // The floating dot on as well: the room starts and stops its service.
            app.container.settings.update { it.copy(onboarded = true, floatingDot = dot) }
            YouTab.entries.forEach { shelf ->
                tab = shelf
                settle()
                rule.onNode(hasScrollToKeyAction()).performScrollToKey("version")
                settle()
                rule.onNodeWithText("build ${BuildConfig.VERSION_CODE}", substring = true).assertExists()
            }
        }
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(900)
        rule.waitForIdle()
    }
}
