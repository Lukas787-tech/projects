package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.llm.Persona
import com.lukas.jarvis.llm.Personas
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.CoreStyle
import com.lukas.jarvis.ui.components.GlassField
import com.lukas.jarvis.ui.components.Panel
import com.lukas.jarvis.ui.components.Reactor
import com.lukas.jarvis.ui.components.ToggleRow
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentTone
import com.lukas.jarvis.ui.theme.Backdrop
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.OnAccent
import com.lukas.jarvis.ui.theme.Palettes
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.glass
import com.lukas.jarvis.ui.theme.sheen
import com.lukas.jarvis.voice.VoiceOption

// ----------------------------------------------------------------- assistant

/**
 * Who the assistant is: its name, its character, how it addresses you, how
 * much it says, and whatever you want it to always keep in mind.
 */
@Composable
fun AssistantSection(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    Hero(settings)

    Panel(title = "Names") {
        GlassField(
            value = settings.assistantName,
            onValueChange = { value -> onUpdate { it.copy(assistantName = value) } },
            label = "Assistant's name",
            placeholder = "Jarvis",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        GlassField(
            value = settings.userName,
            onValueChange = { value -> onUpdate { it.copy(userName = value) } },
            label = "Your name",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Space.snug))
        Label("How it addresses you")
        ChoiceChips(
            options = Personas.ADDRESSES,
            selected = settings.honorific.trim().lowercase(),
            display = { if (it.isBlank()) "My name" else it.replaceFirstChar { c -> c.uppercase() } },
            onSelect = { value -> onUpdate { it.copy(honorific = value) } }
        )
        Spacer(Modifier.height(8.dp))
        GlassField(
            value = settings.honorific,
            onValueChange = { value -> onUpdate { it.copy(honorific = value) } },
            label = "Or type your own",
            placeholder = "Tony, Captain, Doctor…",
            modifier = Modifier.fillMaxWidth()
        )
    }

    Panel(title = "Personality", subtitle = "The same assistant and the same facts — only the voice changes.") {
        Personas.ALL.forEach { persona ->
            PersonaCard(
                persona = persona,
                selected = persona.id == settings.personality,
                onClick = { onUpdate { it.copy(personality = persona.id) } }
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    Panel(title = "How it answers") {
        Label("Length")
        ChoiceChips(
            options = Personas.LENGTHS.map { it.first },
            selected = settings.replyLength,
            display = { id -> Personas.LENGTHS.firstOrNull { it.first == id }?.second ?: id },
            onSelect = { value -> onUpdate { it.copy(replyLength = value) } }
        )
        Spacer(Modifier.height(Space.snug))
        Label("Wit")
        ChoiceChips(
            options = Personas.WIT.indices.map { it.toString() },
            selected = settings.wit.coerceIn(0, 3).toString(),
            display = { Personas.WIT[it.toInt()] },
            onSelect = { value -> onUpdate { it.copy(wit = value.toInt()) } }
        )
        Spacer(Modifier.height(Space.snug))
        GlassField(
            value = settings.replyLanguage,
            onValueChange = { value -> onUpdate { it.copy(replyLanguage = value) } },
            label = "Always answer in (optional)",
            placeholder = "German, English, Español…",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        ToggleRow(
            title = "Suggest a next step",
            subtitle = "Offer the obvious follow-up in a few words",
            checked = settings.proactive,
            onChange = { value -> onUpdate { it.copy(proactive = value) } }
        )
        ToggleRow(
            title = "Emoji in typed replies",
            subtitle = "Never spoken either way",
            checked = settings.emoji,
            onChange = { value -> onUpdate { it.copy(emoji = value) } }
        )
    }

    Panel(
        title = "Quick commands",
        subtitle = "Your own one-tap phrases on the assistant, one per line — anything you'd say."
    ) {
        GlassField(
            value = settings.quickCommands,
            onValueChange = { value -> onUpdate { it.copy(quickCommands = value.take(600)) } },
            label = "One per line",
            placeholder = "Brief me\nTurn off all the lights\nLog a coffee, 3 euros\nWhat's in the news?",
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Panel(
        title = "Make it yours",
        subtitle = "Everything here goes into every conversation. Write it the way you'd brief a new assistant."
    ) {
        GlassField(
            value = settings.aboutMe,
            onValueChange = { value -> onUpdate { it.copy(aboutMe = value.take(1200)) } },
            label = "About you",
            placeholder = "I'm a student in Berlin, vegetarian, I train for a half marathon, my sister is Anna…",
            singleLine = false,
            supportingText = "${settings.aboutMe.length} / 1200",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        GlassField(
            value = settings.customInstructions,
            onValueChange = { value -> onUpdate { it.copy(customInstructions = value.take(1500)) } },
            label = "How it should behave",
            placeholder = "Be blunt. Use metric. Call me out when I overspend. Keep jokes nerdy…",
            singleLine = false,
            supportingText = "${settings.customInstructions.length} / 1500",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The assistant's card: its core, its name and who it is being. */
@Composable
private fun Hero(settings: Settings) {
    val persona = Personas.byId(settings.personality)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Space.step)
            .sheen(RoundedCornerShape(Corner.card))
            .padding(Space.step),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Reactor(
            mood = GlobeMood.Resting,
            level = 0f,
            compact = true,
            style = CoreStyle.of(settings.coreStyle).let { if (it == CoreStyle.Globe) CoreStyle.Reactor else it },
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.width(Space.step))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                settings.assistantName.ifBlank { "Jarvis" },
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary
            )
            Text(
                "${persona.label} · ${Personas.address(settings).ifBlank { "no title" }}",
                style = MaterialTheme.typography.labelMedium,
                color = Accent
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "“${persona.sample}”",
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = TextSecondary,
                maxLines = 2
            )
        }
    }
}

@Composable
internal fun PersonaCard(persona: Persona, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Corner.medium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(shape, raised = selected)
            .then(if (selected) Modifier.border(1.5.dp, Accent.copy(alpha = 0.7f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.step, vertical = Space.snug),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                persona.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) Accent else TextPrimary
            )
            Text(persona.tagline, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            if (selected && persona.id != Personas.CUSTOM) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "“${persona.sample}”",
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = TextFaint
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier.size(24.dp).clip(CircleShape).background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = OnAccent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// --------------------------------------------------------------------- voice

/**
 * How it sounds and listens: whether it speaks, which of the phone's voices,
 * how fast and how high, which language to hear, and the small sounds and
 * ticks around the microphone.
 */
@Composable
fun VoiceSection(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    voices: () -> List<VoiceOption>,
    onPreview: () -> Unit
) {
    Panel(title = "Speaking") {
        ToggleRow(
            title = "Speak replies",
            subtitle = "Read answers out loud",
            checked = settings.speakReplies,
            onChange = { value -> onUpdate { it.copy(speakReplies = value) } }
        )
        ToggleRow(
            title = "Hands free",
            subtitle = "Start listening again after each spoken reply",
            checked = settings.handsFree,
            onChange = { value -> onUpdate { it.copy(handsFree = value) } }
        )
        ToggleRow(
            title = "Morning brief",
            subtitle = "On the first open of a morning, say how the day looks — weather, what's due, the top story",
            checked = settings.morningBrief,
            onChange = { value -> onUpdate { it.copy(morningBrief = value) } }
        )
        ToggleRow(
            title = "Listening tones",
            subtitle = "A short tone when the microphone opens and when it heard you",
            checked = settings.earcons,
            onChange = { value -> onUpdate { it.copy(earcons = value) } }
        )
        ToggleRow(
            title = "Haptics",
            subtitle = "A tick under your finger on the core and the dock",
            checked = settings.haptics,
            onChange = { value -> onUpdate { it.copy(haptics = value) } }
        )
        LabeledSlider(
            label = "Speed",
            valueText = "%.2f×".format(settings.speechRate),
            value = settings.speechRate,
            range = 0.5f..2.0f,
            onChange = { value -> onUpdate { it.copy(speechRate = value) } }
        )
        LabeledSlider(
            label = "Pitch",
            valueText = "%.2f".format(settings.speechPitch),
            value = settings.speechPitch,
            range = 0.5f..2.0f,
            onChange = { value -> onUpdate { it.copy(speechPitch = value) } }
        )
        Spacer(Modifier.height(6.dp))
        ChipButton(
            label = "Hear it",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            prominent = true,
            onClick = onPreview,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Panel(
        title = "Voice",
        subtitle = "The voices installed on this phone for the language it speaks. More can be " +
            "downloaded in Android's text-to-speech settings — free."
    ) {
        var list by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
        LaunchedEffect(settings.speechLanguage) {
            // The engine may still be starting; ask again once or twice.
            repeat(4) {
                list = voices()
                if (list.isNotEmpty()) return@LaunchedEffect
                kotlinx.coroutines.delay(700)
            }
        }
        VoiceRow(
            label = "Engine default",
            detail = "Whatever the phone picks",
            selected = settings.voiceName.isBlank(),
            onClick = { onUpdate { it.copy(voiceName = "") }; onPreview() }
        )
        if (list.isEmpty()) {
            Text(
                "No other voices reported yet.",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
        list.take(24).forEach { voice ->
            VoiceRow(
                label = voice.label,
                detail = voice.language,
                selected = voice.name == settings.voiceName,
                onClick = { onUpdate { it.copy(voiceName = voice.name) }; onPreview() }
            )
        }
    }

    Panel(title = "Language", collapsible = true, initiallyExpanded = settings.speechLanguage.isNotBlank()) {
        Text(
            "Which language to listen for and speak in. Blank follows the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(8.dp))
        ChoiceChips(
            options = listOf("", "en-US", "en-GB", "de-DE", "es-ES", "fr-FR", "it-IT", "tr-TR"),
            selected = settings.speechLanguage,
            display = { if (it.isBlank()) "Phone" else it },
            onSelect = { value -> onUpdate { it.copy(speechLanguage = value, voiceName = "") } }
        )
        Spacer(Modifier.height(8.dp))
        GlassField(
            value = settings.speechLanguage,
            onValueChange = { value -> onUpdate { it.copy(speechLanguage = value.trim(), voiceName = "") } },
            label = "Language tag",
            placeholder = "e.g. pt-BR",
            modifier = Modifier.fillMaxWidth()
        )
    }

    Panel(title = "Wake word", collapsible = true, initiallyExpanded = settings.wakeWordEnabled) {
        ToggleRow(
            title = "Listen for its name",
            subtitle = "Works in the background while the app is closed. Uses noticeably more " +
                "battery, and Android only lets it open the app reliably while it is running.",
            checked = settings.wakeWordEnabled,
            onChange = { value -> onUpdate { it.copy(wakeWordEnabled = value) } }
        )
        if (settings.wakeWordEnabled) {
            Spacer(Modifier.height(8.dp))
            GlassField(
                value = settings.wakePhrase,
                onValueChange = { value -> onUpdate { it.copy(wakePhrase = value.lowercase()) } },
                label = "Wake phrase",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun VoiceRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.small))
            .background(if (selected) Film.selected else Film.faint.copy(alpha = 0f))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.snug, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) Accent else TextPrimary)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = TextFaint)
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = Accent, modifier = Modifier.size(18.dp))
    }
}

// ---------------------------------------------------------------------- look

/** The colour of its light, what it is drawn over, what sits at its centre. */
@Composable
fun LookSection(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    Panel(title = "Accent", subtitle = "The colour of its light. Every surface picks it up.") {
        Swatches(
            items = Palettes.accents,
            selectedId = settings.accent,
            onSelect = { id -> onUpdate { it.copy(accent = id) } }
        )
    }

    Panel(title = "Backdrop") {
        Backdrops(
            items = Palettes.backdrops,
            selectedId = settings.backdrop,
            onSelect = { id -> onUpdate { it.copy(backdrop = id) } }
        )
    }

    Panel(title = "Core", subtitle = "What sits at the centre of the assistant.") {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
            CoreStyle.entries.forEach { style ->
                val selected = style.id == settings.coreStyle
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .glass(RoundedCornerShape(Corner.medium), raised = selected)
                        .then(
                            if (selected) Modifier.border(1.5.dp, Accent.copy(alpha = 0.7f), RoundedCornerShape(Corner.medium))
                            else Modifier
                        )
                        .clickable { onUpdate { it.copy(coreStyle = style.id) } }
                        .padding(vertical = Space.snug),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (style == CoreStyle.Globe) {
                        com.lukas.jarvis.ui.globe.Globe(mood = GlobeMood.Resting, modifier = Modifier.size(56.dp))
                    } else {
                        Reactor(
                            mood = if (selected) GlobeMood.Speaking else GlobeMood.Resting,
                            level = 0f,
                            compact = true,
                            style = style,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        style.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) Accent else TextSecondary
                    )
                }
            }
        }
    }

    Panel(title = "Display") {
        ToggleRow(
            title = "Heads-up readouts",
            subtitle = "The time, date, weather and battery above the core",
            checked = settings.showHud,
            onChange = { value -> onUpdate { it.copy(showHud = value) } }
        )
        ToggleRow(
            title = "Calm motion",
            subtitle = "Slower, fewer moving parts. Easier on the eyes and the battery.",
            checked = settings.reduceMotion,
            onChange = { value -> onUpdate { it.copy(reduceMotion = value) } }
        )
        LabeledSlider(
            label = "Text size",
            valueText = "${(settings.textScale * 100).toInt()}%",
            value = settings.textScale,
            range = 0.85f..1.3f,
            onChange = { value -> onUpdate { it.copy(textScale = (value * 20).toInt() / 20f) } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Swatches(items: List<AccentTone>, selectedId: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.snug),
        verticalArrangement = Arrangement.spacedBy(Space.snug)
    ) {
        items.forEach { tone ->
            val selected = tone.id == selectedId
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(64.dp).clickable { onSelect(tone.id) }
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(tone.bright, tone.main, tone.deep)))
                        .then(if (selected) Modifier.border(2.dp, TextPrimary, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = OnAccent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    tone.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) TextPrimary else TextFaint,
                    maxLines = 2
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Backdrops(items: List<Backdrop>, selectedId: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.snug),
        verticalArrangement = Arrangement.spacedBy(Space.snug)
    ) {
        items.forEach { backdrop ->
            val selected = backdrop.id == selectedId
            val shape = RoundedCornerShape(14.dp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(backdrop.id) }
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 76.dp)
                        .clip(shape)
                        .background(Brush.verticalGradient(listOf(backdrop.top, backdrop.middle, backdrop.bottom)))
                        .border(if (selected) 2.dp else 1.dp, if (selected) Accent else TextFaint.copy(alpha = 0.4f), shape)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    backdrop.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) TextPrimary else TextFaint
                )
            }
        }
    }
}

// -------------------------------------------------------------------- shared

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextFaint,
        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
    )
}

/** A row of choices, one lit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(
    options: List<String>,
    selected: String,
    display: (String) -> String,
    onSelect: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.tight),
        verticalArrangement = Arrangement.spacedBy(Space.tight)
    ) {
        options.forEach { option ->
            val lit = option.equals(selected, ignoreCase = true)
            Text(
                display(option),
                style = MaterialTheme.typography.labelLarge,
                color = if (lit) OnAccent else TextSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (lit) Accent else Film.resting)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = Accent)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Film.lifted
            )
        )
    }
}
