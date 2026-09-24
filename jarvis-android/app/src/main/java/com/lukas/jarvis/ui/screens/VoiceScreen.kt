package com.lukas.jarvis.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.llm.ToolGroup
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.ModeSwitch
import com.lukas.jarvis.ui.components.SayChip
import com.lukas.jarvis.ui.components.Tag
import com.lukas.jarvis.ui.components.ToolTrail
import com.lukas.jarvis.ui.components.icon
import com.lukas.jarvis.ui.globe.Globe
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.map.MapCanvas
import com.lukas.jarvis.ui.map.zoomForWorldWidth
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.GlassEdgeBright
import com.lukas.jarvis.ui.theme.GlassEdgeDim
import com.lukas.jarvis.ui.theme.Negative
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.glass
import com.lukas.jarvis.ui.theme.sheen
import com.lukas.jarvis.vm.AssistantUiState
import com.lukas.jarvis.vm.Stage

/**
 * The assistant, in whichever of its two forms is wanted.
 *
 * Voice and text want opposite screens. Talking wants a planet, one sentence
 * and nothing to read; typing wants the whole transcript and a keyboard. The
 * old screen tried to be both and was cluttered in both directions, so the two
 * are separate layouts behind a switch now, and each gets the full screen.
 *
 * Both now show the work as well as the answer. While a turn runs, the tools it
 * reaches for appear one by one as a trail; once it has answered, the same
 * chips stay under the reply as a receipt for where each number came from.
 */
@Composable
fun VoiceScreen(
    state: AssistantUiState,
    assistantName: String,
    configured: Boolean,
    voiceMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    map: MapState,
    tiles: TileCache,
    starters: List<Pair<ToolGroup, String>>,
    onSend: (String) -> Unit,
    onDismissError: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMap: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    modifier: Modifier = Modifier,
    mapStyle: MapStyle = MapStyle.Dark
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.gutter)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.snug, bottom = Space.hair),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = assistantName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint,
                modifier = Modifier.weight(1f)
            )
            ModeSwitch(voice = voiceMode, onChange = onModeChange)
            IconButton(onClick = onCamera, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.PhotoCamera,
                    contentDescription = "Show Jarvis something",
                    tint = TextFaint,
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(onClick = onOpenSkills, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = "What Jarvis can do",
                    tint = TextFaint,
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = TextFaint,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        if (voiceMode) {
            VoiceBody(
                state = state,
                configured = configured,
                map = map,
                tiles = tiles,
                mapStyle = mapStyle,
                starters = starters,
                onSend = onSend,
                onOpenMap = onOpenMap,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.weight(1f)
            )
        } else {
            TextBody(
                state = state,
                configured = configured,
                starters = starters,
                onOpenSettings = onOpenSettings,
                onOpenSkills = onOpenSkills,
                onSend = onSend,
                modifier = Modifier.weight(1f)
            )
        }

        ErrorStrip(error = state.error, onDismiss = onDismissError)

        if (!voiceMode) {
            Composer(
                onSend = onSend,
                onOpenHistory = onOpenHistory,
                onCamera = onCamera,
                onGallery = onGallery
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.snug),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundAction(Icons.Default.PhotoLibrary, "Pick a photo to show Jarvis", onGallery)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Tag(
                        text = statusText(state, configured),
                        tint = if (state.stage == Stage.Idle) TextFaint else Accent
                    )
                }
                RoundAction(Icons.Default.PhotoCamera, "Show Jarvis something", onCamera)
            }
        }
    }
}

// --------------------------------------------------------------------- voice

/**
 * The planet, and one line about what it found.
 *
 * When an answer has coordinates in it the globe turns them to the front and
 * closes in, and the real map fades up underneath at the end of the flight. The
 * handover works because both are looking at the same place by then: the globe
 * finishes its approach centred on the coordinates the map opens at.
 */
@Composable
private fun VoiceBody(
    state: AssistantUiState,
    configured: Boolean,
    map: MapState,
    tiles: TileCache,
    mapStyle: MapStyle,
    starters: List<Pair<ToolGroup, String>>,
    onSend: (String) -> Unit,
    onOpenMap: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val target = map.focusPoints.firstOrNull()
    val hasResult = target != null

    val approach by animateFloatAsState(
        targetValue = if (hasResult) 1f else 0f,
        animationSpec = tween(durationMillis = if (hasResult) 1500 else 700),
        label = "approach"
    )

    val mood = when (state.stage) {
        Stage.Idle -> GlobeMood.Resting
        Stage.Listening -> GlobeMood.Listening
        Stage.Thinking -> GlobeMood.Working
        Stage.Speaking -> GlobeMood.Speaking
    }

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current.density
            val side = minOf(constraints.maxWidth, constraints.maxHeight).toFloat()
            // The globe's closest approach, as a flat world: where the map opens.
            val globeZoom = zoomForWorldWidth(
                (2 * Math.PI * side / 2f * 0.86f * 6.5f).toFloat(),
                density
            )
            // The globe turns and nothing else. Speaking moved to the dot
            // below, which is on every element rather than only this one, so
            // the microphone is no longer tied to whichever screen happens to
            // be showing a planet.
            Globe(
                mood = mood,
                level = state.level,
                here = map.here,
                marks = map.places.map { it.point },
                focus = target,
                approach = approach,
                modifier = Modifier.fillMaxSize()
            )

            // The map only exists once the flight is essentially over, so the
            // two are never both legible at the same time.
            if (approach > 0.55f) {
                val reveal = ((approach - 0.55f) / 0.45f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(reveal)
                        .clip(CircleShape)
                        .clickable { onOpenMap() }
                ) {
                    MapCanvas(
                        state = map,
                        tiles = tiles,
                        style = mapStyle,
                        intro = reveal,
                        introFromZoom = globeZoom,
                        interactive = false
                    )
                }
            }
        }

        Readout(
            state = state,
            configured = configured,
            map = map,
            starters = starters,
            onSend = onSend,
            onOpenSettings = onOpenSettings
        )
    }
}

/** One line: what was found, what is being done, what was said, or what is missing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Readout(
    state: AssistantUiState,
    configured: Boolean,
    map: MapState,
    starters: List<Pair<ToolGroup, String>>,
    onSend: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val lastAssistant = state.messages.lastOrNull { it.role == ChatMessage.ROLE_ASSISTANT }
    val centred = Arrangement.spacedBy(Space.hair + 2.dp, Alignment.CenterHorizontally)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.snug)
            .sheen(RoundedCornerShape(Corner.card))
            .padding(horizontal = Space.gutter, vertical = Space.step),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            state.partial.isNotBlank() -> Text(
                text = state.partial,
                style = MaterialTheme.typography.headlineMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 3
            )

            !configured -> {
                Text(
                    text = "Pick a model to get started.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                ChipButton(label = "Open setup", prominent = true, onClick = onOpenSettings)
            }

            // The work in progress, named. A silent planet for eight seconds is
            // indistinguishable from a hung one; "searching the web" is not.
            state.stage == Stage.Thinking -> {
                Text(
                    text = doingLine(state.stageLabel),
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
                if (state.activity.isNotEmpty()) {
                    Spacer(Modifier.height(Space.snug))
                    ToolTrail(tools = state.activity, live = true, horizontalArrangement = centred)
                }
            }

            map.places.isNotEmpty() -> {
                Text(
                    text = map.title.ifBlank { "Found nearby" },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextFaint,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                map.places.take(3).forEachIndexed { index, place ->
                    Text(
                        text = "${index + 1}  ${place.name}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (index == 0) TextPrimary else TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }

            lastAssistant != null -> {
                Text(
                    text = lastAssistant.content,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 4
                )
                if (lastAssistant.tools.isNotEmpty()) {
                    Spacer(Modifier.height(Space.snug))
                    ToolTrail(tools = lastAssistant.tools, horizontalArrangement = centred)
                }
            }

            else -> {
                Text(
                    text = "Tap the dot and talk.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextFaint,
                    textAlign = TextAlign.Center
                )
                if (starters.isNotEmpty()) {
                    Spacer(Modifier.height(Space.snug))
                    Text(
                        text = "OR TRY",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextFaint
                    )
                    Spacer(Modifier.height(Space.tight))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Space.tight, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(Space.tight)
                    ) {
                        starters.take(3).forEach { (_, phrase) ->
                            SayChip(text = phrase, onClick = { onSend(phrase) })
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- text

/** The whole conversation, which is the point of being in text mode. */
@Composable
private fun TextBody(
    state: AssistantUiState,
    configured: Boolean,
    starters: List<Pair<ToolGroup, String>>,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val working = state.stage == Stage.Thinking

    // Follows the thread and the trail alike, so a tool chip appearing below the
    // fold is scrolled to rather than missed.
    LaunchedEffect(state.messages.size, state.activity.size, working) {
        val last = state.messages.size + (if (working) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    if (state.messages.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (configured) "Type anything." else "Pick a model to get started.",
                style = MaterialTheme.typography.headlineMedium,
                color = TextFaint,
                textAlign = TextAlign.Center
            )
            if (!configured) {
                Spacer(Modifier.height(14.dp))
                ChipButton(label = "Open setup", prominent = true, onClick = onOpenSettings)
            } else {
                Spacer(Modifier.height(Space.gutter))
                Starters(starters = starters, onSend = onSend)
                Spacer(Modifier.height(Space.step))
                ChipButton(
                    label = "Everything Jarvis can do",
                    icon = Icons.Default.AutoAwesome,
                    onClick = onOpenSkills
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(state.messages, key = { messageKey(it) }) { message ->
            MessageBubble(message, state.photos[message.createdAt])
        }
        if (working) {
            item(key = "working") {
                WorkingBubble(label = state.stageLabel, activity = state.activity)
            }
        }
        item(key = "tail") {
            // The partial transcript belongs at the bottom of the thread, where
            // the reply to it will appear.
            if (state.partial.isNotBlank()) {
                Text(
                    text = state.partial,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextFaint,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Jarvis's side of the thread while it is still working: what it is doing in
 * words, and the tools it has reached for so far.
 */
@Composable
private fun WorkingBubble(label: String, activity: List<String>) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 18.dp
                    )
                )
                .background(Film.faint)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = doingLine(label),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            if (activity.isNotEmpty()) {
                Spacer(Modifier.height(Space.tight))
                ToolTrail(tools = activity, live = true)
            }
        }
    }
}

/**
 * The first things worth asking, drawn from whichever abilities are switched
 * on — so the calendar is never suggested to someone who turned it off, and
 * every tap reaches a tool rather than a refusal.
 */
@Composable
private fun Starters(starters: List<Pair<ToolGroup, String>>, onSend: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.tight)
    ) {
        starters.forEach { (group, phrase) ->
            SayChip(text = phrase, icon = group.icon(), onClick = { onSend(phrase) })
        }
    }
}

// -------------------------------------------------------------------- shared

/**
 * The failure line, as the same glass as everything else with the faintest
 * wash of red — an error is news, not an alarm going off.
 */
@Composable
private fun ErrorStrip(error: String?, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(Corner.medium)
    AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Space.snug)
                .glass(shape)
                .background(Negative.copy(alpha = 0.10f), shape)
                .padding(start = 14.dp, end = Space.hair, top = Space.hair, bottom = Space.hair),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Negative,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Space.tight + 2.dp))
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f).padding(vertical = Space.tight)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** A round glass button beside the status line, sized for a thumb. */
@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .glass(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = TextSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Composer(
    onSend: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(24.dp)

    fun send() {
        if (draft.isNotBlank()) {
            onSend(draft)
            draft = ""
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.step),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.tight)
    ) {
        IconButton(onClick = onOpenHistory, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.History,
                contentDescription = "Conversation history",
                tint = TextFaint,
                modifier = Modifier.size(19.dp)
            )
        }
        IconButton(onClick = onCamera, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = "Take a photo for Jarvis",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        TextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = {
                Text("Message", style = MaterialTheme.typography.bodyMedium, color = TextFaint)
            },
            singleLine = true,
            trailingIcon = {
                if (draft.isBlank()) {
                    IconButton(onClick = onGallery) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = "Pick a photo",
                            tint = TextFaint,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(listOf(GlassEdgeBright, GlassEdgeDim)),
                    shape = shape
                ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Film.lifted,
                unfocusedContainerColor = Film.resting,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Accent,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() })
        )
        IconButton(
            onClick = { send() },
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (draft.isBlank()) Film.resting else Accent)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (draft.isBlank()) TextFaint else MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/** "searching the web" -> "Searching the web…", and a word when there is none. */
private fun doingLine(label: String): String =
    label.ifBlank { "working" }.replaceFirstChar { it.uppercase() } + "…"

private fun statusText(state: AssistantUiState, configured: Boolean): String = when (state.stage) {
    Stage.Idle -> when {
        !configured -> "NOT SET UP YET"
        !state.micAvailable -> "NO MIC — SWITCH TO TEXT"
        else -> "TAP THE DOT TO SPEAK"
    }
    Stage.Listening -> "LISTENING"
    Stage.Thinking -> state.stageLabel.uppercase().ifBlank { "WORKING" }
    Stage.Speaking -> "SPEAKING — TAP TO STOP"
}
