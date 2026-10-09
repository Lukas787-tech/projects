package com.lukas.jarvis.ui.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Mochi
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.character.Prop
import com.lukas.jarvis.ui.kit.ButtonKind
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.kit.CafeTextField
import com.lukas.jarvis.ui.kit.ChoiceChip
import com.lukas.jarvis.ui.kit.IconCircle
import com.lukas.jarvis.ui.kit.VSpace
import com.lukas.jarvis.ui.kit.Wrap
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.voice.InterpretedLine
import com.lukas.jarvis.voice.InterpreterState
import com.lukas.jarvis.voice.InterpreterState.Side

/**
 * The interpreter, on the canvas: one button for each person, every line
 * translated and read aloud in the other language, and Mochi holding the
 * speech bubble between you. Typing works too, for a loud room; closing it
 * always takes you back to the canvas.
 */
@Composable
fun InterpreterCanvas(
    state: InterpreterState,
    micAvailable: Boolean,
    onListen: (Side) -> Unit,
    onType: (String, Side) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf("") }
    var typingAs by rememberSaveable { mutableStateOf(Side.Me) }
    val list = rememberLazyListState()
    LaunchedEffect(state.lines.size) { if (state.lines.isNotEmpty()) list.animateScrollToItem(state.lines.lastIndex) }

    Column(modifier.fillMaxSize().background(Cafe.colors.foam).padding(horizontal = Cafe.space.gutter)) {
        Row(Modifier.fillMaxWidth().padding(top = Cafe.space.s), verticalAlignment = Alignment.CenterVertically) {
            Mochi(
                CharacterState(
                    mood = when {
                        state.listening != null -> Mood.Listening
                        state.working -> Mood.Working
                        else -> Mood.Idle
                    },
                    prop = Prop.Bubble,
                    description = "Mochi is interpreting between ${state.mineName} and ${state.theirsName}"
                ),
                size = 64.dp
            )
            Column(Modifier.weight(1f).padding(start = Cafe.space.m)) {
                Text("Interpreting", style = Cafe.type.caption, color = Cafe.colors.cocoa)
                Text(
                    "${state.mineName} ↔ ${state.theirsName}",
                    style = Cafe.type.title,
                    color = Cafe.colors.espresso,
                    modifier = Modifier.semantics { heading() }
                )
            }
            IconCircle(Icons.Rounded.Close, "Close the interpreter", onEnd)
        }
        VSpace(Cafe.space.m)
        if (state.lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Tap your button and speak — I'll say it in ${state.theirsName}. They tap theirs to answer.",
                    style = Cafe.type.voiceSmall,
                    color = Cafe.colors.cocoa
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                state = list,
                verticalArrangement = Arrangement.spacedBy(Cafe.space.s)
            ) {
                items(state.lines) { line -> Bubble(line) }
            }
        }
        state.note?.let { Text(it, style = Cafe.type.bodySmall, color = Cafe.colors.berryText, modifier = Modifier.padding(vertical = Cafe.space.xs)) }
        Row(horizontalArrangement = Arrangement.spacedBy(Cafe.space.s), modifier = Modifier.fillMaxWidth().padding(vertical = Cafe.space.s)) {
            CafeButton(
                if (state.listening == Side.Me) "Listening…" else "Speak ${state.mineName}",
                { onListen(Side.Me) },
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Mic,
                enabled = micAvailable && !state.working
            )
            CafeButton(
                if (state.listening == Side.Them) "…" else state.theirsName,
                { onListen(Side.Them) },
                modifier = Modifier.weight(1f),
                kind = ButtonKind.Secondary,
                icon = Icons.Rounded.Mic,
                enabled = micAvailable && !state.working
            )
        }
        Wrap {
            ChoiceChip("I type", typingAs == Side.Me, { typingAs = Side.Me })
            ChoiceChip("They type", typingAs == Side.Them, { typingAs = Side.Them })
        }
        VSpace(Cafe.space.xs)
        CafeTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = if (typingAs == Side.Me) "Type in ${state.mineName}" else "Type in ${state.theirsName}",
            onDone = {
                if (text.isNotBlank()) {
                    onType(text.trim(), typingAs)
                    text = ""
                }
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = Cafe.space.s)
        )
    }
}

@Composable
private fun Bubble(line: InterpretedLine) {
    val mine = line.side == Side.Me
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(Cafe.shape.large)
                .background(if (mine) Cafe.colors.accentSoft else Cafe.colors.paper)
                .padding(Cafe.space.m)
        ) {
            Text(line.translated, style = Cafe.type.voiceSmall, color = Cafe.colors.espresso)
            Text(line.said, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
        }
    }
}
