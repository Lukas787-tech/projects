package com.lukas.jarvis.ui.talk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.moment.ActionIntent
import com.lukas.jarvis.moment.Composer
import com.lukas.jarvis.moment.Moment
import com.lukas.jarvis.moment.MomentInputs
import com.lukas.jarvis.moment.Samples
import com.lukas.jarvis.ui.character.Director
import com.lukas.jarvis.ui.character.Signals
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.kit.MicState
import com.lukas.jarvis.ui.theme.MochiTheme

/**
 * The real Talk canvas for any moment, fed from [Samples] instead of the view
 * model: what the previews, the screenshots and the dead-end walk all look at.
 */
@Composable
fun MomentPreview(
    moment: Moment,
    modifier: Modifier = Modifier,
    inputs: MomentInputs = Samples.inputsFor(moment),
    onAction: (ActionIntent) -> Unit = {},
    onSend: (String) -> Unit = {}
) {
    val layout = Composer.compose(inputs)
    val character = Director.direct(
        Signals(
            listening = inputs.listening,
            thinking = inputs.thinking,
            speaking = inputs.speaking,
            tool = inputs.tool,
            waitingForYes = inputs.pending.isNotEmpty(),
            alerting = inputs.alerts.isNotEmpty(),
            problem = inputs.problem != null,
            unsure = inputs.clarifying,
            now = 1_000L,
            lastActivityAt = 1_000L
        )
    )
    val state = TalkState(
        layout = layout,
        character = character,
        name = "Mochi",
        greeting = "Good afternoon, Lukas",
        status = if (!inputs.online) "Offline — timers, lists, sums and memory still work" else null,
        speech = when (moment) {
            Moment.Resting -> ""
            Moment.Listening -> ""
            Moment.Thinking, is Moment.Working -> "Looking into it…"
            is Moment.Asking -> "Ready to text Anna — it'll go when you say yes."
            is Moment.Recovering -> "Sorry, that didn't work. Want me to try again?"
            else -> "Here you go."
        },
        trail = inputs.tool?.let { listOf(it) } ?: inputs.fresh.mapNotNull { it.tool },
        working = inputs.thinking,
        mic = when {
            inputs.listening -> MicState.Listening
            inputs.thinking -> MicState.Busy
            else -> MicState.Ready
        },
        partial = if (inputs.listening) "remind me to call mum at" else "",
        globeMood = when {
            inputs.listening -> GlobeMood.Listening
            inputs.thinking -> GlobeMood.Working
            else -> GlobeMood.Resting
        }
    )
    TalkScreen(
        state = state,
        cards = CardContext(now = 1_000L),
        map = MapState(),
        tiles = null,
        mapStyle = MapStyle.Light,
        level = { 0f },
        onAction = onAction,
        onSend = onSend,
        onCamera = {},
        onCharacter = { onAction(ActionIntent.Listen) },
        modifier = modifier
    )
}

@Preview(name = "Resting", widthDp = 411, heightDp = 891)
@Composable
private fun RestingPreview() = MochiTheme(reduceMotion = true) { MomentPreview(Moment.Resting) }

@Preview(name = "Asking, night café", widthDp = 411, heightDp = 891)
@Composable
private fun AskingPreview() = MochiTheme(themeMode = "dark", reduceMotion = true) {
    MomentPreview(Moment.Asking(com.lukas.jarvis.moment.AskKind.Confirmation))
}

@Preview(name = "Creating a list, large text", widthDp = 411, heightDp = 891)
@Composable
private fun CreatingPreview() = MochiTheme(textScale = 1.3f, reduceMotion = true) {
    MomentPreview(Moment.Creating(com.lukas.jarvis.moment.CreateKind.List))
}

@Preview(name = "Working, landscape", widthDp = 891, heightDp = 411)
@Composable
private fun WorkingPreview() = MochiTheme(reduceMotion = true) {
    MomentPreview(Moment.Working(com.lukas.jarvis.llm.ToolGroup.Weather))
}
