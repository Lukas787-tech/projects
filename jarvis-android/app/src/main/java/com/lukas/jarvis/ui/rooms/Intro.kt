package com.lukas.jarvis.ui.rooms

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.llm.Persona
import com.lukas.jarvis.llm.Personas
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Mochi
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.character.Prop
import com.lukas.jarvis.ui.kit.ButtonKind
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.kit.CafeTextField
import com.lukas.jarvis.ui.kit.ChoiceChip
import com.lukas.jarvis.ui.kit.Eyebrow
import com.lukas.jarvis.ui.kit.PaperCard
import com.lukas.jarvis.ui.kit.QuietButton
import com.lukas.jarvis.ui.kit.Tone
import com.lukas.jarvis.ui.kit.VSpace
import com.lukas.jarvis.ui.kit.Wrap
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.CafeMotion
import com.lukas.jarvis.ui.theme.Palette

/**
 * The first minute: Mochi says hello and that it already works — no account,
 * no key, no cost — then learns your name, its personality, the café's look
 * and whether you talk or type, and says plainly what leaves the phone before
 * the first word. Every choice is also in You; this is only the short way in,
 * and it can be skipped from any step.
 */
@Composable
fun Intro(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onPreviewVoice: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    /** Where to open; 0 is the hello. Used to come back to one question. */
    initialStep: Int = 0
) {
    var step by rememberSaveable(initialStep) { mutableIntStateOf(initialStep.coerceIn(0, LAST)) }
    val reduce = Cafe.reduceMotion
    val name = settings.assistantName.ifBlank { "Mochi" }
    Box(modifier.fillMaxSize().background(Cafe.colors.foam), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = Cafe.space.gutter)) {
            Row(Modifier.fillMaxWidth().padding(top = Cafe.space.m), verticalAlignment = Alignment.CenterVertically) {
                Steps(step, Modifier.weight(1f))
                QuietButton("Skip", onFinish)
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(CafeMotion.fade(reduce, 260)) togetherWith fadeOut(CafeMotion.fade(reduce, 160)) },
                label = "intro",
                modifier = Modifier.weight(1f)
            ) { shown ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Cafe.space.l),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (shown) {
                        0 -> Hello(name)
                        1 -> Names(settings, onUpdate)
                        2 -> Character(settings, onUpdate, onPreviewVoice)
                        3 -> Look(settings, onUpdate)
                        4 -> TalkOrType(settings, onUpdate)
                        else -> WhatLeaves(name)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = Cafe.space.l, top = Cafe.space.s),
                horizontalArrangement = Arrangement.spacedBy(Cafe.space.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) CafeButton("Back", { step-- }, kind = ButtonKind.Secondary)
                CafeButton(
                    when (step) {
                        0 -> "Let's begin"
                        LAST -> "Start talking"
                        else -> "Next"
                    },
                    { if (step == LAST) onFinish() else step++ },
                    modifier = Modifier.weight(1f),
                    fill = true
                )
            }
        }
    }
}

private const val LAST = 5

/** How far along, as six small beans. */
@Composable
private fun Steps(step: Int, modifier: Modifier) {
    Row(
        modifier.semantics {
            progressBarRangeInfo = ProgressBarRangeInfo((step + 1).toFloat(), 1f..(LAST + 1).toFloat(), LAST + 1)
            stateDescription = "Step ${step + 1} of ${LAST + 1}"
        },
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.xs)
    ) {
        (0..LAST).forEach { i ->
            Box(
                Modifier
                    .height(8.dp)
                    .width(if (i == step) 28.dp else 12.dp)
                    .clip(CircleShape)
                    .background(if (i <= step) Cafe.colors.accentFill else Cafe.colors.latteDeep)
            )
        }
    }
}

@Composable
private fun Title(text: String, detail: String? = null) {
    Text(
        text,
        style = Cafe.type.display,
        color = Cafe.colors.espresso,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { heading() }
    )
    if (detail != null) {
        VSpace(Cafe.space.xs)
        Text(detail, style = Cafe.type.body, color = Cafe.colors.cocoa, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    VSpace(Cafe.space.l)
}

@Composable
private fun Hello(name: String) {
    VSpace(Cafe.space.xl)
    Mochi(CharacterState(Mood.Delighted, tag = "hi!", description = "$name, waving hello"), size = 200.dp)
    VSpace(Cafe.space.l)
    Title(
        "Hi, I'm $name.",
        "A little helper for your day: I remember things, keep your lists and money, set timers, find places and work your phone. " +
            "I already work — no account, no key, no cost."
    )
}

@Composable
private fun Names(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    Mochi(CharacterState(Mood.Listening, description = "Mochi, listening"), size = 96.dp)
    VSpace(Cafe.space.m)
    Title("What should I call you?")
    CafeTextField(settings.userName, { v -> onUpdate { it.copy(userName = v) } }, label = "Your name", modifier = Modifier.fillMaxWidth())
    VSpace(Cafe.space.l)
    Eyebrow("And how should I address you?", Modifier.fillMaxWidth())
    VSpace(Cafe.space.xs)
    Wrap(Modifier.fillMaxWidth()) {
        Personas.ADDRESSES.forEach { address ->
            ChoiceChip(
                if (address.isBlank()) settings.userName.ifBlank { "By name" } else address.replaceFirstChar { it.uppercase() },
                settings.honorific.trim().equals(address, ignoreCase = true),
                { onUpdate { it.copy(honorific = address) } }
            )
        }
    }
    VSpace(Cafe.space.l)
    CafeTextField(settings.assistantName, { v -> onUpdate { it.copy(assistantName = v) } }, label = "And my name", placeholder = "Mochi", modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Character(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit, onPreviewVoice: () -> Unit) {
    Title("Pick my personality", "Tap one to hear it. You can write your own later, in You.")
    Personas.ALL.filter { it.id != Personas.CUSTOM }.forEach { persona ->
        PersonaChoice(persona, persona.id == settings.personality) {
            onUpdate { Personas.choose(it, persona.id) }
            onPreviewVoice()
        }
        VSpace(Cafe.space.s)
    }
}

@Composable
private fun PersonaChoice(persona: Persona, selected: Boolean, onClick: () -> Unit) {
    PaperCard(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(2.dp, Cafe.colors.accentFill, Cafe.shape.large) else Modifier)
            .semantics { this.selected = selected },
        tone = if (selected) Tone.Accent else Tone.Paper,
        padding = Cafe.space.m,
        onClick = onClick,
        clickLabel = "Choose ${persona.label}"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(persona.label, style = Cafe.type.title, color = Cafe.colors.espresso)
                Text(persona.tagline, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
                if (selected) Text("“${persona.sample}”", style = Cafe.type.voiceSmall.copy(fontStyle = FontStyle.Italic), color = Cafe.colors.espresso, modifier = Modifier.padding(top = Cafe.space.xs))
            }
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Cafe.colors.accentText, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Look(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    Mochi(CharacterState(Mood.Working, prop = Prop.Brush, description = "Mochi with a paint brush"), size = 120.dp)
    VSpace(Cafe.space.m)
    Title("Pick the café", "It changes as you tap.")
    Wrap(Modifier.fillMaxWidth()) {
        listOf("system" to "Follow the phone", "light" to "Latte", "dark" to "Night café").forEach { (id, label) ->
            ChoiceChip(label, settings.themeMode == id, { onUpdate { it.copy(themeMode = id) } })
        }
    }
    VSpace(Cafe.space.l)
    Eyebrow("And my colour", Modifier.fillMaxWidth())
    VSpace(Cafe.space.s)
    Wrap(Modifier.fillMaxWidth()) {
        Palette.ACCENTS.forEach { accent ->
            val selected = settings.accent == accent.id
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.RadioButton, onClickLabel = accent.label) { onUpdate { it.copy(accent = accent.id) } }
                    .semantics { this.selected = selected; stateDescription = accent.label },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(accent.color))
                        .then(if (selected) Modifier.border(3.dp, Cafe.colors.espresso, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Cafe.colors.espresso, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun TalkOrType(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    Title("Talk or type?", "Both always work; this only decides whether I speak up by myself.")
    ModeCard(
        Icons.Rounded.Mic,
        "Mostly talk",
        "Tap me or the microphone to talk. I answer out loud and keep listening after I reply.",
        settings.voiceMode
    ) { onUpdate { it.copy(voiceMode = true, speakReplies = true, handsFree = true) } }
    VSpace(Cafe.space.m)
    ModeCard(
        Icons.Rounded.Keyboard,
        "Mostly type",
        "Type into the bar at the bottom. I stay quiet unless you ask me to speak.",
        !settings.voiceMode
    ) { onUpdate { it.copy(voiceMode = false, speakReplies = false) } }
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    PaperCard(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(2.dp, Cafe.colors.accentFill, Cafe.shape.large) else Modifier)
            .semantics { this.selected = selected },
        tone = if (selected) Tone.Accent else Tone.Paper,
        onClick = onClick,
        clickLabel = "Choose $title"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Cafe.colors.accentText, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(start = Cafe.space.l)) {
                Text(title, style = Cafe.type.title, color = Cafe.colors.espresso)
                Text(body, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            }
        }
    }
}

/** The honest page: what stays, what goes where, and what always waits for a yes. */
@Composable
private fun WhatLeaves(name: String) {
    Mochi(CharacterState(Mood.Idle, prop = Prop.House, description = "$name, keeping things at home"), size = 112.dp)
    VSpace(Cafe.space.m)
    Title("What leaves your phone")
    Point(
        "Stays here",
        "Your memories, notes, lists, money, tasks, routines and places live on this phone, and your keys are locked in its keystore. No account, nothing sold."
    )
    Point(
        "Goes to answer you",
        "What you ask goes to the free AI models that answer it — or to your own key's provider, if you add one — with what $name needs to answer well: your name, what you wrote about yourself, your money totals and open tasks, the memories you pinned and the ones that touch the question, and whatever it looked up for you."
    )
    Point(
        "Goes to look things up",
        "Weather, maps and searches ask Open-Meteo, OpenStreetMap and the web, without your name. A Fish Audio voice sends the words it speaks."
    )
    Point(
        "Always waits for your yes",
        "A text, a call, an email, a share or anything that can't be undone shows a card first, and nothing happens until you say yes."
    )
    VSpace(Cafe.space.s)
    Text("You can change all of this in You, any time.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Point(title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Cafe.space.s), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(10.dp).clip(CircleShape).background(Cafe.colors.accentFill))
        Column(Modifier.padding(start = Cafe.space.m)) {
            Text(title, style = Cafe.type.title, color = Cafe.colors.espresso)
            Text(body, style = Cafe.type.body, color = Cafe.colors.cocoa)
        }
    }
}
