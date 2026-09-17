package com.lukas.jarvis.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.Orb
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
 * Deliberately sparse: an orb, whatever was just said, and a way to say more.
 * Everything else lives on the other tabs.
 */
@Composable
fun VoiceScreen(
    state: AssistantUiState,
    assistantName: String,
    configured: Boolean,
    onOrbTap: () -> Unit,
    onSend: (String) -> Unit,
    onDismissError: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    val lastAssistant = state.messages.lastOrNull { it.role == ChatMessage.ROLE_ASSISTANT }
    val lastUser = state.messages.lastOrNull { it.role == ChatMessage.ROLE_USER }

    LaunchedEffect(lastAssistant?.id) {
        scrollState.animateScrollTo(0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        // The chat log and the settings used to be tabs; they are reached from
        // here now, which is where you are when you want either of them.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = assistantName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenHistory, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.History,
                    contentDescription = "Conversation history",
                    tint = TextFaint,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = TextFaint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.weight(0.6f))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Orb(
                stage = state.stage,
                level = state.level,
                modifier = Modifier.clip(CircleShape).clickable { onOrbTap() }
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = statusText(state, configured),
            style = MaterialTheme.typography.labelSmall,
            color = if (state.stage == Stage.Idle) TextFaint else Accent,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        // The transcript area holds one exchange. Full history is a tap away.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .weight(1f),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    state.partial.isNotBlank() -> Text(
                        text = state.partial,
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    lastAssistant != null -> {
                        if (lastUser != null && lastUser.createdAt <= lastAssistant.createdAt) {
                            Text(
                                text = lastUser.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextFaint,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(
                            text = lastAssistant.content,
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clickable { onOpenHistory() }
                        )
                    }

                    !configured -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Pick a model to get started.",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Takes a minute, and every provider on the list is free.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextFaint,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(18.dp))
                        ChipButton(
                            label = "Open setup",
                            prominent = true,
                            onClick = onOpenSettings
                        )
                    }

                    else -> Text(
                        text = "Tap the orb and just talk.\nI remember everything you tell me.",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextFaint,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state.error != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
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
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Negative,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismissError, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Negative)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = {
                    Text(
                        "or type it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextFaint
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, Hairline, RoundedCornerShape(24.dp)),
                colors = TextFieldDefaults.colors(
                    // Glass, and a touch brighter once it has focus — the same
                    // film as every other surface rather than a grey box.
                    focusedContainerColor = FieldFocused,
                    unfocusedContainerColor = FieldResting,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Accent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (draft.isNotBlank()) {
                            onSend(draft)
                            draft = ""
                        }
                    }
                )
            )
            IconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        onSend(draft)
                        draft = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (draft.isBlank()) FieldResting else Accent)
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (draft.isBlank()) TextFaint else MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

private fun statusText(state: AssistantUiState, configured: Boolean): String = when (state.stage) {
    Stage.Idle -> when {
        !configured -> "NOT SET UP YET"
        !state.micAvailable -> "NO MIC — TYPE INSTEAD"
        else -> "TAP TO SPEAK"
    }
    Stage.Listening -> "LISTENING"
    Stage.Thinking -> state.stageLabel.uppercase().ifBlank { "THINKING" }
    Stage.Speaking -> "SPEAKING — TAP TO STOP"
}
