package com.lukas.jarvis.ui.talk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lukas.jarvis.core.Secrets
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.llm.Personas
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.moment.Alert
import com.lukas.jarvis.moment.AlertKind
import com.lukas.jarvis.moment.Backdrop
import com.lukas.jarvis.moment.Composer
import com.lukas.jarvis.moment.FollowUp
import com.lukas.jarvis.moment.Moment
import com.lukas.jarvis.moment.MomentInputs
import com.lukas.jarvis.moment.ShowKind
import com.lukas.jarvis.ui.character.Director
import com.lukas.jarvis.ui.character.Signals
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.kit.MicState
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.vm.AssistantViewModel
import com.lukas.jarvis.vm.Stage
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * The Talk canvas, fed from the view model: every flow it shows is gathered
 * here into one [MomentInputs], composed into a layout, and drawn.
 */
@Composable
fun TalkRoute(
    viewModel: AssistantViewModel,
    settings: Settings,
    onCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val canvas by viewModel.canvas.collectAsStateWithLifecycle()
    val pending by viewModel.pendingActions.collectAsStateWithLifecycle()
    val ringing by viewModel.ringingTimers.collectAsStateWithLifecycle()
    val timers by viewModel.timers.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val map by viewModel.map.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val levelState: State<Float> = viewModel.voiceLevel.collectAsStateWithLifecycle()

    // Time moves Mochi on by itself only in two ways: a win wears off after a
    // moment, and a long quiet makes it sleepy. Nothing else needs a clock.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(canvas.winAt) {
        delay(Director.WIN_MS + 100)
        now = System.currentTimeMillis()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)

    val inputs = MomentInputs(
        listening = ui.stage == Stage.Listening,
        thinking = ui.stage == Stage.Thinking,
        speaking = ui.stage == Stage.Speaking,
        tool = ui.activity.lastOrNull()?.takeIf { ui.stage == Stage.Thinking },
        pending = pending,
        clarifying = canvas.clarifying,
        alerts = ringing.map { Alert("timer:${it.id}", AlertKind.Timer, it.label.ifBlank { "Timer" }, "Time's up", timerId = it.id) },
        problem = canvas.problem,
        fresh = canvas.fresh,
        pinned = canvas.pinned,
        shelf = canvas.shelf,
        navigating = canvas.navigating,
        online = online,
        micAvailable = ui.micAvailable,
        suggestions = suggestions(hour)
    )
    val layout = remember(inputs) { Composer.compose(inputs) }
    val name = settings.assistantName.ifBlank { "Mochi" }
    val character = Director.direct(
        Signals(
            name = name,
            listening = inputs.listening,
            thinking = inputs.thinking,
            speaking = inputs.speaking,
            tool = inputs.tool,
            waitingForYes = pending.isNotEmpty(),
            alerting = ringing.isNotEmpty(),
            problem = canvas.problem != null,
            unsure = canvas.clarifying,
            win = canvas.win,
            winAt = canvas.winAt,
            now = now,
            hour = hour,
            lastActivityAt = maxOf(canvas.lastActivityAt, ui.messages.lastOrNull()?.createdAt ?: 0L)
        )
    )
    val reply = ui.messages.lastOrNull { it.role == ChatMessage.ROLE_ASSISTANT }?.content.orEmpty()
    val state = TalkState(
        layout = layout,
        character = character,
        name = name,
        greeting = greeting(Personas.address(settings), hour),
        status = when {
            !online -> "Offline — timers, lists, sums and memory still work"
            Secrets.unreadable -> "Your saved keys need restoring — the free models answer meanwhile"
            else -> null
        },
        speech = plain(ui.draft.ifBlank { if (ui.stage == Stage.Thinking) ui.stageLabel.replaceFirstChar { it.titlecase() } + "…" else reply }),
        speechLive = ui.draft.isNotBlank(),
        trail = ui.activity.ifEmpty { ui.messages.lastOrNull()?.takeIf { it.role == ChatMessage.ROLE_ASSISTANT }?.tools.orEmpty() },
        working = ui.stage == Stage.Thinking,
        mic = when {
            !ui.micAvailable -> MicState.Unavailable
            ui.stage == Stage.Listening -> MicState.Listening
            ui.stage == Stage.Thinking || ui.stage == Stage.Speaking -> MicState.Busy
            else -> MicState.Ready
        },
        partial = ui.partial,
        globeMood = when (ui.stage) {
            Stage.Listening -> GlobeMood.Listening
            Stage.Thinking -> GlobeMood.Working
            Stage.Speaking -> GlobeMood.Speaking
            Stage.Idle -> GlobeMood.Resting
        },
        focus = map.focusPoints.firstOrNull()?.takeIf { layout.moment == Moment.Showing(ShowKind.Globe) && layout.backdrop == Backdrop.GlobeLeads }
    )
    TalkScreen(
        state = state,
        cards = CardContext(lists = lists, timers = timers, ringing = ringing, photos = ui.photos, now = now),
        map = map,
        tiles = viewModel.tiles,
        mapStyle = MapStyle.of(settings.mapStyle, Cafe.colors.isDark),
        level = { levelState.value },
        onAction = viewModel::onCanvasAction,
        onSend = viewModel::sendTyped,
        onCamera = onCamera,
        onCharacter = viewModel::toggleListening,
        modifier = modifier
    )
}

/** "Good evening, Lukas." */
fun greeting(address: String, hour: Int): String {
    val part = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Hello, night owl"
    }
    return if (address.isBlank()) part else "$part, $address"
}

/** Things worth offering at this time of day, when nothing else is going on. */
fun suggestions(hour: Int): List<FollowUp> = when (hour) {
    in 5..11 -> listOf(
        FollowUp("How does my day look?", "How does my day look?"),
        FollowUp("Do I need an umbrella?", "Do I need an umbrella today?"),
        FollowUp("Focus for 25 minutes", "Focus for 25 minutes")
    )
    in 12..17 -> listOf(
        FollowUp("What's still open?", "What's still open?"),
        FollowUp("What's around me?", "What's around here?"),
        FollowUp("Remind me to…", "Remind me to ")
    )
    in 18..22 -> listOf(
        FollowUp("How did today go?", "How did today go?"),
        FollowUp("Set an alarm for tomorrow", "Set an alarm for tomorrow at "),
        FollowUp("Start a shopping list", "Start a shopping list")
    )
    else -> listOf(
        FollowUp("Set an alarm", "Set an alarm for "),
        FollowUp("What's tomorrow like?", "What does tomorrow look like?"),
        Composer.DEFAULT_PROMPTS[1]
    )
}

/** A reply without markdown's marks, for Mochi's spoken line. */
fun plain(text: String): String = text
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("(?m)^#{1,6}\\s*"), "")
    .replace(Regex("`([^`]*)`"), "$1")
    .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
    .trim()
