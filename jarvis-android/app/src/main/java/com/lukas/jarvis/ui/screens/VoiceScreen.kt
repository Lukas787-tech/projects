package com.lukas.jarvis.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lukas.jarvis.brief.DayBrief
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.llm.ToolGroup
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.CoreStyle
import com.lukas.jarvis.ui.components.ImageViewer
import com.lukas.jarvis.ui.components.MessageActions
import com.lukas.jarvis.ui.components.MessageBubble
import com.lukas.jarvis.ui.components.ModeSwitch
import com.lukas.jarvis.ui.components.Reactor
import com.lukas.jarvis.ui.components.SayChip
import com.lukas.jarvis.ui.components.StoredImage
import com.lukas.jarvis.ui.components.ToolTrail
import com.lukas.jarvis.ui.components.TypingDots
import com.lukas.jarvis.ui.components.icon
import com.lukas.jarvis.ui.globe.Globe
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.map.MapCanvas
import com.lukas.jarvis.ui.map.zoomForWorldWidth
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentBright
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.GlassEdgeBright
import com.lukas.jarvis.ui.theme.GlassEdgeDim
import com.lukas.jarvis.ui.theme.Negative
import com.lukas.jarvis.ui.theme.OnAccent
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.glass
import com.lukas.jarvis.ui.theme.hudFrame
import com.lukas.jarvis.ui.theme.sheen
import com.lukas.jarvis.vm.AssistantUiState
import com.lukas.jarvis.vm.Stage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The assistant, in whichever of its two forms is wanted.
 *
 * Voice is a heads-up display: the time and the sky along the top, the
 * reactor in the middle breathing, listening, working or speaking, and one
 * answer under it. When an answer found places, the reactor's centre opens
 * and the map shows inside its rings. Text is the whole conversation, drawn
 * as a thread with Markdown, pictures and a menu on every message.
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
    mapStyle: MapStyle = MapStyle.Dark,
    /** Puts the found places away and turns the core back to rest. */
    onClearMap: () -> Unit = {},
    coreStyle: CoreStyle = CoreStyle.Reactor,
    showHud: Boolean = true,
    brief: DayBrief? = null,
    /** How the user is addressed, for the greeting. */
    address: String = "",
    actions: MessageActions = MessageActions(),
    onStop: () -> Unit = {},
    /** The endpoint that answered last, shown small under the name. */
    brainLabel: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.gutter)
    ) {
        Header(
            name = assistantName,
            state = state,
            brainLabel = brainLabel,
            voiceMode = voiceMode,
            onModeChange = onModeChange,
            onCamera = onCamera,
            onOpenSkills = onOpenSkills,
            onOpenSettings = onOpenSettings
        )

        if (voiceMode) {
            if (showHud) Hud(brief = brief)
            VoiceBody(
                state = state,
                configured = configured,
                map = map,
                tiles = tiles,
                mapStyle = mapStyle,
                starters = starters,
                address = address,
                coreStyle = coreStyle,
                onSend = onSend,
                onOpenMap = onOpenMap,
                onOpenSettings = onOpenSettings,
                onClearMap = onClearMap,
                modifier = Modifier.weight(1f)
            )
        } else {
            TextBody(
                state = state,
                configured = configured,
                starters = starters,
                address = address,
                actions = actions,
                onOpenSettings = onOpenSettings,
                onOpenSkills = onOpenSkills,
                onSend = onSend,
                onStop = onStop,
                modifier = Modifier.weight(1f)
            )
        }

        ErrorStrip(error = state.error, onDismiss = onDismissError)

        if (!voiceMode) {
            Composer(
                busy = state.stage == Stage.Thinking,
                onSend = onSend,
                onStop = onStop,
                onOpenHistory = onOpenHistory,
                onCamera = onCamera,
                onGallery = onGallery
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.snug),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundAction(Icons.Default.PhotoLibrary, "Pick a photo to show $assistantName", onGallery)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    StatusLine(state = state, configured = configured, onStop = onStop)
                }
                RoundAction(Icons.Default.PhotoCamera, "Show $assistantName something", onCamera)
            }
        }
    }
}

// -------------------------------------------------------------------- header

@Composable
private fun Header(
    name: String,
    state: AssistantUiState,
    brainLabel: String?,
    voiceMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onCamera: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.snug, bottom = Space.hair),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val lit = state.stage != Stage.Idle
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (lit) Accent else Accent.copy(alpha = 0.45f))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = name.uppercase().toCharArray().joinToString("."),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                    color = Accent,
                    maxLines = 1
                )
            }
            Text(
                text = (brainLabel?.let { "ONLINE · ${it.substringBefore(" · ").uppercase()}" } ?: "ONLINE"),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp),
                color = TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 15.dp, top = 2.dp)
            )
        }
        ModeSwitch(voice = voiceMode, onChange = onModeChange)
        HeaderButton(Icons.Default.AutoAwesome, "What $name can do", onOpenSkills)
        HeaderButton(Icons.Default.Settings, "Settings", onOpenSettings)
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = TextSecondary, modifier = Modifier.size(19.dp))
    }
}

/**
 * The readouts along the top: the time, large and thin, the date, and the sky
 * and the battery if the day has been gathered.
 */
@Composable
private fun Hud(brief: DayBrief?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // Wake on the minute rather than polling every second.
            val cal = Calendar.getInstance()
            delay((60 - cal.get(Calendar.SECOND)) * 1000L + 50)
        }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now) {
        SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(now)).uppercase()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.tight)
            .hudFrame(arm = 10.dp, alpha = 0.45f)
            .padding(horizontal = Space.snug, vertical = Space.tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                time,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 40.sp, lineHeight = 42.sp),
                color = TextPrimary
            )
            Text(date, style = MaterialTheme.typography.labelMedium, color = Accent)
        }
        Column(horizontalAlignment = Alignment.End) {
            brief?.forecast?.let { f ->
                Text(
                    "${f.now.temperature.roundToInt()}°",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary
                )
                Text(
                    listOfNotNull(f.now.description, brief.placeName).joinToString(" · ").uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
            }
            brief?.battery?.takeIf { it.isNotBlank() }?.let { battery ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.BatteryStd,
                        contentDescription = null,
                        tint = TextFaint,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        battery.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp),
                        color = TextFaint,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------- voice

/**
 * The core, and one line about what it found.
 *
 * For the reactor and the orb, an answer with places in it opens the centre
 * and the map shows inside the rings. The globe keeps its own flight: it
 * turns the place to the front and closes in, and the map fades up underneath.
 */
@Composable
private fun VoiceBody(
    state: AssistantUiState,
    configured: Boolean,
    map: MapState,
    tiles: TileCache,
    mapStyle: MapStyle,
    starters: List<Pair<ToolGroup, String>>,
    address: String,
    coreStyle: CoreStyle,
    onSend: (String) -> Unit,
    onOpenMap: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearMap: () -> Unit,
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

            if (coreStyle == CoreStyle.Globe) {
                val globeZoom = zoomForWorldWidth(
                    (2 * Math.PI * side / 2f * 0.86f * 6.5f).toFloat(),
                    density
                )
                Globe(
                    mood = mood,
                    level = state.level,
                    here = map.here,
                    marks = map.places.map { it.point },
                    focus = target,
                    approach = approach,
                    modifier = Modifier.fillMaxSize()
                )
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
            } else {
                Box(
                    modifier = Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true),
                    contentAlignment = Alignment.Center
                ) {
                    Reactor(
                        mood = mood,
                        level = state.level,
                        aperture = approach,
                        style = coreStyle,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (approach > 0.05f) {
                        // The map inside the rings, growing as the centre opens.
                        Box(
                            modifier = Modifier
                                .fillMaxSize(0.52f * approach.coerceAtLeast(0.2f))
                                .alpha(approach)
                                .clip(CircleShape)
                                .clickable { onOpenMap() }
                        ) {
                            MapCanvas(
                                state = map,
                                tiles = tiles,
                                style = mapStyle,
                                interactive = false
                            )
                        }
                    }
                }
            }
        }

        Readout(
            state = state,
            configured = configured,
            map = map,
            starters = starters,
            address = address,
            onSend = onSend,
            onOpenSettings = onOpenSettings,
            onClearMap = onClearMap
        )
    }
}

/** One line: what was heard, what is being done, what was said, or what to try. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Readout(
    state: AssistantUiState,
    configured: Boolean,
    map: MapState,
    starters: List<Pair<ToolGroup, String>>,
    address: String,
    onSend: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onClearMap: () -> Unit
) {
    val lastAssistant = state.messages.lastOrNull { it.role == ChatMessage.ROLE_ASSISTANT }
    val centred = Arrangement.spacedBy(Space.hair + 2.dp, Alignment.CenterHorizontally)
    var viewing by remember { mutableStateOf<String?>(null) }

    // An answer that has been read does not need to sit under the core until
    // the next one. Putting it away lasts for that answer only.
    var putAwayId by rememberSaveable { mutableStateOf<Long?>(null) }
    val showingPlaces = state.stage != Stage.Thinking && state.partial.isBlank() && map.places.isNotEmpty()
    val showingAnswer = state.stage != Stage.Thinking && state.partial.isBlank() &&
        map.places.isEmpty() && lastAssistant != null
    if (showingAnswer && putAwayId == lastAssistant?.createdAt) {
        Starters(starters.take(3), onSend, compact = true)
        return
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Space.snug)
                .sheen(RoundedCornerShape(Corner.card))
                .padding(horizontal = Space.gutter, vertical = Space.step)
                .heightIn(max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                state.partial.isNotBlank() -> Text(
                    text = "“${state.partial}”",
                    style = MaterialTheme.typography.headlineMedium.copy(fontStyle = FontStyle.Italic),
                    color = AccentBright,
                    textAlign = TextAlign.Center,
                    maxLines = 3
                )

                !configured -> {
                    Text(
                        text = "No brain connected. Open setup to restore the free one.",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    ChipButton(label = "Open setup", prominent = true, onClick = onOpenSettings)
                }

                // The work in progress, named: "searching the web" is not the
                // same as a spinner that could mean stuck.
                state.stage == Stage.Thinking -> {
                    TypingDots()
                    Spacer(Modifier.height(Space.tight))
                    Text(
                        text = doingLine(state.stageLabel),
                        style = MaterialTheme.typography.titleLarge,
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
                        text = map.title.ifBlank { "Found nearby" }.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Accent,
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
                    lastAssistant.image?.let { path ->
                        StoredImage(
                            path = path,
                            maxSide = 720,
                            modifier = Modifier
                                .heightIn(max = 170.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { viewing = path }
                        )
                        Spacer(Modifier.height(Space.snug))
                    }
                    Text(
                        text = com.lukas.jarvis.ui.components.Markdown.plain(lastAssistant.content),
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = if (lastAssistant.image != null) 3 else 5,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (lastAssistant.tools.isNotEmpty()) {
                        Spacer(Modifier.height(Space.snug))
                        ToolTrail(tools = lastAssistant.tools, horizontalArrangement = centred)
                    }
                }

                else -> {
                    Text(
                        text = greeting(address),
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(Space.hair))
                    Text(
                        text = "Tap the core below and talk — or try one of these.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextFaint,
                        textAlign = TextAlign.Center
                    )
                    if (starters.isNotEmpty()) {
                        Spacer(Modifier.height(Space.snug))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.tight, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(Space.tight)
                        ) {
                            starters.take(3).forEach { (group, phrase) ->
                                SayChip(text = phrase, icon = group.icon(), onClick = { onSend(phrase) })
                            }
                        }
                    }
                }
            }
        }

        if (showingPlaces || showingAnswer) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = Space.snug + 2.dp, end = 2.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable {
                        if (showingPlaces) onClearMap() else putAwayId = lastAssistant?.createdAt
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = if (showingPlaces) "Close the map" else "Put the answer away",
                    tint = TextFaint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    viewing?.let { ImageViewer(path = it, onClose = { viewing = null }) }
}

// ---------------------------------------------------------------------- text

/** The whole conversation, which is the point of being in text mode. */
@Composable
private fun TextBody(
    state: AssistantUiState,
    configured: Boolean,
    starters: List<Pair<ToolGroup, String>>,
    address: String,
    actions: MessageActions,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val working = state.stage == Stage.Thinking

    // Follows the thread and the trail alike, so a tool chip appearing below
    // the fold is scrolled to rather than missed.
    LaunchedEffect(state.messages.size, state.activity.size, working) {
        val last = state.messages.size + (if (working) 1 else 0)
        if (last > 0) listState.animateScrollToItem(last)
    }

    if (state.messages.isEmpty() && !working) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = greeting(address),
                style = MaterialTheme.typography.displayMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Space.hair))
            Text(
                text = if (configured) "What can I do for you?" else "No brain connected yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
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
                    label = "Everything I can do",
                    icon = Icons.Default.AutoAwesome,
                    onClick = onOpenSkills
                )
            }
        }
        return
    }

    val lastId = state.messages.lastOrNull()?.let { messageKey(it) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "top") { Spacer(Modifier.height(Space.tight)) }
        items(state.messages, key = { messageKey(it) }) { message ->
            MessageBubble(
                message = message,
                photo = state.photos[message.createdAt],
                actions = actions,
                isLast = messageKey(message) == lastId
            )
        }
        if (working) {
            item(key = "working") {
                WorkingBubble(label = state.stageLabel, activity = state.activity, onStop = onStop)
            }
        }
        item(key = "tail") {
            // The partial transcript belongs at the bottom of the thread, where
            // the reply to it will appear.
            if (state.partial.isNotBlank()) {
                Text(
                    text = state.partial,
                    style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                    color = AccentBright,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    textAlign = TextAlign.End
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The assistant's side of the thread while it is still working: dots, what it
 * is doing in words, the tools it has reached for, and a way to stop it.
 */
@Composable
private fun WorkingBubble(label: String, activity: List<String>, onStop: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .background(Film.faint)
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(GlassEdgeBright, GlassEdgeDim)),
                    RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypingDots()
                Spacer(Modifier.width(10.dp))
                Text(
                    text = doingLine(label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop",
                    tint = TextFaint,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onStop)
                        .padding(2.dp)
                )
            }
            if (activity.isNotEmpty()) {
                Spacer(Modifier.height(Space.tight))
                ToolTrail(tools = activity, live = true)
            }
        }
    }
}

/**
 * The first things worth asking, drawn from whichever abilities are switched
 * on — so every tap reaches a tool rather than a refusal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Starters(
    starters: List<Pair<ToolGroup, String>>,
    onSend: (String) -> Unit,
    compact: Boolean = false
) {
    if (starters.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) Space.snug else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.tight, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Space.tight)
    ) {
        starters.forEach { (group, phrase) ->
            SayChip(text = phrase, icon = group.icon(), onClick = { onSend(phrase) })
        }
    }
}

// -------------------------------------------------------------------- shared

/** The failure line, as glass with the faintest wash of red. */
@Composable
private fun ErrorStrip(error: String?, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(Corner.medium)
    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut()
    ) {
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
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
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

/** What the assistant is doing, in capitals, with a stop button while it works. */
@Composable
private fun StatusLine(state: AssistantUiState, configured: Boolean, onStop: () -> Unit) {
    val working = state.stage == Stage.Thinking
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (state.stage == Stage.Idle) Film.faint else Accent.copy(alpha = 0.14f))
            .then(if (working) Modifier.clickable(onClick = onStop) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (working) {
            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = statusText(state, configured),
            style = MaterialTheme.typography.labelMedium,
            color = if (state.stage == Stage.Idle) TextFaint else Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A round glass button beside the status line, sized for a thumb. */
@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
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
    busy: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onOpenHistory: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val shape = RoundedCornerShape(26.dp)

    fun send() {
        if (draft.isNotBlank() && !busy) {
            onSend(draft)
            draft = ""
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.step),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Space.hair + 2.dp)
    ) {
        IconButton(onClick = onOpenHistory, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Default.History,
                contentDescription = "Conversation history",
                tint = TextFaint,
                modifier = Modifier.size(20.dp)
            )
        }
        TextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = {
                Text("Message", style = MaterialTheme.typography.bodyMedium, color = TextFaint)
            },
            maxLines = 5,
            leadingIcon = {
                IconButton(onClick = onCamera) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = "Take a photo",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
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
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                capitalization = KeyboardCapitalization.Sentences
            ),
            keyboardActions = KeyboardActions(onSend = { send() })
        )
        val ready = draft.isNotBlank() && !busy
        IconButton(
            onClick = { if (busy) onStop() else send() },
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    if (ready || busy) {
                        Brush.linearGradient(listOf(AccentBright, Accent))
                    } else {
                        Brush.linearGradient(listOf(Film.resting, Film.resting))
                    }
                )
        ) {
            Icon(
                if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (busy) "Stop" else "Send",
                tint = if (ready || busy) OnAccent else TextFaint
            )
        }
    }
}

/** "searching the web" -> "Searching the web…", and a word when there is none. */
private fun doingLine(label: String): String =
    label.ifBlank { "thinking" }.replaceFirstChar { it.uppercase() } + "…"

private fun statusText(state: AssistantUiState, configured: Boolean): String = when (state.stage) {
    Stage.Idle -> when {
        !configured -> "NOT SET UP YET"
        !state.micAvailable -> "NO MIC — SWITCH TO TEXT"
        else -> "STANDING BY"
    }
    Stage.Listening -> "LISTENING"
    Stage.Thinking -> state.stageLabel.uppercase().ifBlank { "WORKING" }
    Stage.Speaking -> "SPEAKING — TAP CORE TO STOP"
}

/** "Good evening, sir." — the time of day, and however the user likes to be addressed. */
internal fun greeting(address: String): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val part = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Burning the midnight oil"
    }
    return if (address.isBlank()) "$part." else "$part, $address."
}
