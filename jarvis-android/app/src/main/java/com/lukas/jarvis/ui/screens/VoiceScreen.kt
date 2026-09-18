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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.ModeSwitch
import com.lukas.jarvis.ui.globe.Globe
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.map.MapCanvas
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Hairline
import com.lukas.jarvis.ui.theme.Negative
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.vm.AssistantUiState
import com.lukas.jarvis.vm.Stage

// The two states of the text field, as films of white rather than as greys.
private val FieldResting = Color(0x12FFFFFF)
private val FieldFocused = Color(0x1FFFFFFF)

/**
 * The assistant, in whichever of its two forms is wanted.
 *
 * Voice and text want opposite screens. Talking wants a planet, one sentence
 * and nothing to read; typing wants the whole transcript and a keyboard. The
 * old screen tried to be both and was cluttered in both directions, so the two
 * are separate layouts behind a switch now, and each gets the full screen.
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
    onSend: (String) -> Unit,
    onDismissError: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = assistantName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint,
                modifier = Modifier.weight(1f)
            )
            ModeSwitch(voice = voiceMode, onChange = onModeChange)
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
                onOpenMap = onOpenMap,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.weight(1f)
            )
        } else {
            TextBody(
                state = state,
                configured = configured,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.weight(1f)
            )
        }

        ErrorStrip(error = state.error, onDismiss = onDismissError)

        if (!voiceMode) {
            Composer(onSend = onSend, onOpenHistory = onOpenHistory)
        } else {
            Text(
                text = statusText(state, configured),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.stage == Stage.Idle) TextFaint else Accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp)
            )
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
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
                    MapCanvas(state = map, tiles = tiles, onSelectPlace = {})
                }
            }
        }

        Readout(
            state = state,
            configured = configured,
            map = map,
            onOpenSettings = onOpenSettings
        )
    }
}

/** One line: what was found, or what was said, or what is missing. */
@Composable
private fun Readout(
    state: AssistantUiState,
    configured: Boolean,
    map: MapState,
    onOpenSettings: () -> Unit
) {
    val lastAssistant = state.messages.lastOrNull { it.role == ChatMessage.ROLE_ASSISTANT }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
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

            lastAssistant != null -> Text(
                text = lastAssistant.content,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 4
            )

            else -> Text(
                text = "Tap the dot and talk.",
                style = MaterialTheme.typography.headlineMedium,
                color = TextFaint,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------------------------------------------------------------------- text

/** The whole conversation, which is the point of being in text mode. */
@Composable
private fun TextBody(
    state: AssistantUiState,
    configured: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
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
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(state.messages, key = { it.id }) { message ->
            MessageBubble(message)
        }
        item {
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

// -------------------------------------------------------------------- shared

@Composable
private fun ErrorStrip(error: String?, onDismiss: () -> Unit) {
    AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Negative.copy(alpha = 0.12f))
                .border(1.dp, Negative.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Negative,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Negative)
            }
        }
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit, onOpenHistory: () -> Unit) {
    var draft by remember { mutableStateOf("") }

    fun send() {
        if (draft.isNotBlank()) {
            onSend(draft)
            draft = ""
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onOpenHistory, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.History,
                contentDescription = "Conversation history",
                tint = TextFaint,
                modifier = Modifier.size(19.dp)
            )
        }
        TextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = {
                Text("Message", style = MaterialTheme.typography.bodyMedium, color = TextFaint)
            },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, Hairline, RoundedCornerShape(24.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = FieldFocused,
                unfocusedContainerColor = FieldResting,
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
                .background(if (draft.isBlank()) FieldResting else Accent)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (draft.isBlank()) TextFaint else MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

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
