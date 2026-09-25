package com.lukas.jarvis.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.components.GlassField
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentSoft
import com.lukas.jarvis.ui.theme.Caution
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.OnAccent
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.glassCard
import com.lukas.jarvis.ui.theme.sheen
import com.lukas.jarvis.voice.InterpretedLine
import com.lukas.jarvis.voice.InterpreterState
import com.lukas.jarvis.voice.InterpreterState.Side

/**
 * Two people, one phone. The conversation so far reads down the middle —
 * the user's lines on the right, the other person's on the left, each with
 * its translation large and what was actually said small — and each side has
 * one big button that listens in its language.
 */
@Composable
fun InterpreterPanel(
    state: InterpreterState,
    micAvailable: Boolean,
    onListen: (Side) -> Unit,
    onType: (String, Side) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.tight, bottom = Space.tight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Translate, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Space.snug))
            Column(modifier = Modifier.weight(1f)) {
                Text("Interpreter", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    "${state.mineName}  ⇄  ${state.theirsName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
            IconButton(onClick = onEnd) {
                Icon(Icons.Default.Close, contentDescription = "Close the interpreter", tint = TextSecondary)
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(state.lines.size) {
            if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex)
        }
        if (state.lines.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Tap your side and speak — it is said again in ${state.theirsName.lowercase()}.\n" +
                        "The other person taps ${state.theirsName} to answer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Space.gutter)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.tight)
            ) {
                items(state.lines) { line -> LineBubble(line, state) }
            }
        }

        val status = when {
            state.working -> "Translating…"
            state.listening == Side.Me -> "Listening in ${state.mineName}…"
            state.listening == Side.Them -> "Listening in ${state.theirsName}…"
            else -> state.note
        }
        Text(
            status.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = if (state.note != null && status == state.note) Caution else AccentSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.hair),
            textAlign = TextAlign.Center
        )

        var typed by remember { mutableStateOf("") }
        val sendTyped = {
            if (typed.isNotBlank() && !state.working) {
                onType(typed, Side.Me)
                typed = ""
            }
        }
        GlassField(
            value = typed,
            onValueChange = { typed = it },
            placeholder = "Or type in ${state.mineName}",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { sendTyped() }),
            trailing = {
                IconButton(onClick = sendTyped, enabled = typed.isNotBlank() && !state.working) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Translate", tint = Accent)
                }
            }
        )
        Spacer(Modifier.height(Space.snug))

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.snug),
            horizontalArrangement = Arrangement.spacedBy(Space.snug)
        ) {
            SideButton(
                language = state.mineName,
                caption = "You",
                active = state.listening == Side.Me,
                enabled = micAvailable && !state.working,
                onClick = { onListen(Side.Me) },
                modifier = Modifier.weight(1f)
            )
            SideButton(
                language = state.theirsName,
                caption = null,
                active = state.listening == Side.Them,
                enabled = micAvailable && !state.working,
                onClick = { onListen(Side.Them) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LineBubble(line: InterpretedLine, state: InterpreterState) {
    val mine = line.side == Side.Me
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .then(
                    if (mine) Modifier.sheen(RoundedCornerShape(Corner.medium)) else Modifier.glassCard(Corner.medium)
                )
                .padding(horizontal = Space.step, vertical = Space.snug)
        ) {
            Text(
                if (mine) "You · ${state.mineName}" else state.theirsName,
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint
            )
            Spacer(Modifier.height(Space.hair))
            Text(line.translated, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(Space.hair))
            Text(line.said, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

/** One side's button: the language in its own words and a microphone, lit while it listens. */
@Composable
private fun SideButton(
    language: String,
    caption: String?,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulse = if (active) {
        rememberInfiniteTransition(label = "listen").animateFloat(
            initialValue = 0.75f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulse"
        ).value
    } else {
        1f
    }
    val shape = RoundedCornerShape(Corner.large)
    Column(
        modifier = modifier
            .height(96.dp)
            .clip(shape)
            .then(
                if (active) Modifier.background(Accent.copy(alpha = pulse)) else Modifier.glassCard(Corner.large)
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(Space.snug),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val tint = when {
            active -> OnAccent
            enabled -> Accent
            else -> TextFaint
        }
        Icon(Icons.Default.Mic, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(Space.hair))
        Text(
            language,
            style = MaterialTheme.typography.titleMedium,
            color = if (active) OnAccent else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        caption?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = if (active) OnAccent else TextFaint)
        }
    }
}
