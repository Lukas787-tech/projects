package com.lukas.jarvis.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.llm.Personas
import com.lukas.jarvis.ui.components.CoreStyle
import com.lukas.jarvis.ui.components.GlassField
import com.lukas.jarvis.ui.components.Reactor
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentBright
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.OnAccent
import com.lukas.jarvis.ui.theme.PageBackground
import com.lukas.jarvis.ui.theme.Palettes
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.glass

/**
 * The first minute with the assistant: it introduces itself, learns your name
 * and how you like to be addressed, lets you choose its character and its
 * colour — both change live as you tap — and asks whether you mostly talk or
 * type. Every choice here is also in Settings; this is only the short way in.
 */
@Composable
fun Onboarding(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onPreviewVoice: () -> Unit,
    onFinish: () -> Unit
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val last = 4

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground)
            .padding(horizontal = Space.gutter)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.step),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Progress, as a row of lit segments.
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..last).forEach { i ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(if (i <= step) Accent else Film.lifted)
                    )
                }
            }
            Spacer(Modifier.width(Space.step))
            Text(
                "Skip",
                style = MaterialTheme.typography.labelLarge,
                color = TextFaint,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onFinish).padding(8.dp)
            )
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
            },
            label = "onboarding",
            modifier = Modifier.weight(1f)
        ) { shown ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = Space.step),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (shown) {
                    0 -> Welcome(settings)
                    1 -> Names(settings, onUpdate)
                    2 -> Character(settings, onUpdate, onPreviewVoice)
                    3 -> Colour(settings, onUpdate)
                    else -> Mode(settings, onUpdate)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.snug)
        ) {
            if (step > 0) {
                Text(
                    "Back",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .glass(RoundedCornerShape(Corner.medium))
                        .clickable { step-- }
                        .padding(vertical = 16.dp)
                )
            }
            Text(
                when (step) {
                    0 -> "Let's begin"
                    last -> "Start"
                    else -> "Next"
                },
                style = MaterialTheme.typography.titleMedium,
                color = OnAccent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(2f)
                    .clip(RoundedCornerShape(Corner.medium))
                    .background(Brush.linearGradient(listOf(AccentBright, Accent)))
                    .clickable { if (step == last) onFinish() else step++ }
                    .padding(vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun Welcome(settings: Settings) {
    Spacer(Modifier.height(Space.loose))
    Reactor(
        mood = GlobeMood.Speaking,
        level = 0.35f,
        style = CoreStyle.of(settings.coreStyle).let { if (it == CoreStyle.Globe) CoreStyle.Reactor else it },
        modifier = Modifier.size(240.dp)
    )
    Spacer(Modifier.height(Space.loose))
    Text(
        "Hello. I'm ${settings.assistantName.ifBlank { "Jarvis" }}.",
        style = MaterialTheme.typography.displayMedium,
        color = TextPrimary,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(Space.snug))
    Text(
        "Your own AI assistant — a second brain, your hands on the phone and a window on the " +
            "world. I work right now, with no account, no key and no cost. Let's make me yours.",
        style = MaterialTheme.typography.bodyLarge,
        color = TextSecondary,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun Names(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    Title("What should I call you?")
    GlassField(
        value = settings.userName,
        onValueChange = { value -> onUpdate { it.copy(userName = value) } },
        label = "Your name",
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(Space.gutter))
    Caption("And how should I address you?")
    Spacer(Modifier.height(Space.tight))
    ChoiceChips(
        options = Personas.ADDRESSES,
        selected = settings.honorific.trim().lowercase(),
        display = { if (it.isBlank()) settings.userName.ifBlank { "By name" } else it.replaceFirstChar { c -> c.uppercase() } },
        onSelect = { value -> onUpdate { it.copy(honorific = value) } }
    )
    Spacer(Modifier.height(Space.gutter))
    Caption("And what's my name?")
    Spacer(Modifier.height(Space.tight))
    GlassField(
        value = settings.assistantName,
        onValueChange = { value -> onUpdate { it.copy(assistantName = value) } },
        label = "Assistant's name",
        placeholder = "Jarvis",
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Character(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onPreviewVoice: () -> Unit
) {
    Title("Pick my personality")
    Caption("You can write your own later, in Settings.")
    Spacer(Modifier.height(Space.snug))
    Personas.ALL.filter { it.id != Personas.CUSTOM }.forEach { persona ->
        PersonaCard(
            persona = persona,
            selected = persona.id == settings.personality,
            onClick = {
                onUpdate { it.copy(personality = persona.id) }
                onPreviewVoice()
            }
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Colour(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    Title("Pick my colour")
    Reactor(
        mood = GlobeMood.Resting,
        level = 0f,
        style = CoreStyle.Reactor,
        modifier = Modifier.size(150.dp)
    )
    Spacer(Modifier.height(Space.step))
    Swatches(
        items = Palettes.accents,
        selectedId = settings.accent,
        onSelect = { id -> onUpdate { it.copy(accent = id) } }
    )
    Spacer(Modifier.height(Space.gutter))
    Caption("And the backdrop")
    Spacer(Modifier.height(Space.tight))
    Backdrops(
        items = Palettes.backdrops,
        selectedId = settings.backdrop,
        onSelect = { id -> onUpdate { it.copy(backdrop = id) } }
    )
}

@Composable
private fun Mode(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    Title("Talk or type?")
    Caption("You can switch any time from the top of the assistant.")
    Spacer(Modifier.height(Space.gutter))
    ModeCard(
        icon = Icons.Default.GraphicEq,
        title = "Mostly talk",
        body = "I listen when you tap the core, answer out loud, and keep listening after I reply.",
        selected = settings.voiceMode,
        onClick = { onUpdate { it.copy(voiceMode = true, speakReplies = true, handsFree = true) } }
    )
    Spacer(Modifier.height(Space.snug))
    ModeCard(
        icon = Icons.Default.Keyboard,
        title = "Mostly type",
        body = "A chat thread with formatting and pictures. I stay quiet unless you ask me to speak.",
        selected = !settings.voiceMode,
        onClick = { onUpdate { it.copy(voiceMode = false, speakReplies = false) } }
    )
    Spacer(Modifier.height(Space.gutter))
    Text(
        "Tip: tap the core in the dock to talk from any screen. Long-press it to type. " +
            "Ask \"what can you do?\" to see everything.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextFaint,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Corner.large)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(shape, raised = selected)
            .then(if (selected) Modifier.border(1.5.dp, Accent.copy(alpha = 0.7f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(Space.step),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Accent else TextSecondary, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(Space.step))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = if (selected) Accent else TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
}

@Composable
private fun Title(text: String) {
    Spacer(Modifier.height(Space.gutter))
    Text(
        text,
        style = MaterialTheme.typography.displayMedium,
        color = TextPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(Space.tight))
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
