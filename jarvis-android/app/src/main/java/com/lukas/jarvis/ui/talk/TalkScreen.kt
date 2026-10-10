package com.lukas.jarvis.ui.talk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.maps.GeoPoint
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.moment.ActionIntent
import com.lukas.jarvis.moment.CanvasCard
import com.lukas.jarvis.moment.CharacterSize
import com.lukas.jarvis.moment.Control
import com.lukas.jarvis.moment.ControlIcon
import com.lukas.jarvis.moment.Layout
import com.lukas.jarvis.moment.Room
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Mochi
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.kit.ButtonKind
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.kit.ComposerBar
import com.lukas.jarvis.ui.kit.FollowChip
import com.lukas.jarvis.ui.kit.IconCircle
import com.lukas.jarvis.ui.kit.MicState
import com.lukas.jarvis.ui.kit.StatusLine
import com.lukas.jarvis.ui.kit.ToolTrail
import com.lukas.jarvis.ui.kit.VSpace
import com.lukas.jarvis.ui.kit.Wrap
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.CafeMotion

/** Everything the Talk canvas shows, gathered by the view model. */
data class TalkState(
    val layout: Layout,
    val character: CharacterState,
    val name: String,
    val greeting: String,
    /** A line about how things are, when it matters: offline, keys to restore. */
    val status: String? = null,
    val speech: String = "",
    val speechLive: Boolean = false,
    val trail: List<String> = emptyList(),
    val working: Boolean = false,
    val mic: MicState = MicState.Ready,
    val partial: String = "",
    val globeMood: GlobeMood = GlobeMood.Resting,
    val focus: GeoPoint? = null
)

/**
 * The one canvas: create, control and see, all in the same place.
 *
 * It has no fixed layout. The [Layout] the composer made from this moment
 * decides how big Mochi is, which cards are up (three at most, the primary one
 * first), what the background does and which controls are offered; this only
 * draws it, and animates from one moment to the next. Typing, talking and
 * touch are all in reach at every moment.
 */
@Composable
fun TalkScreen(
    state: TalkState,
    cards: CardContext,
    map: MapState,
    tiles: TileCache?,
    mapStyle: MapStyle,
    level: () -> Float,
    onAction: (ActionIntent) -> Unit,
    onSend: (String) -> Unit,
    onCamera: () -> Unit,
    onCharacter: () -> Unit,
    modifier: Modifier = Modifier,
    prefill: String? = null,
    onPrefillTaken: () -> Unit = {}
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    var typing by remember { mutableIntStateOf(0) }
    // Typing is a real step: the words go in and the keyboard comes up.
    val act: (ActionIntent) -> Unit = { intent ->
        when (intent) {
            is ActionIntent.Type -> {
                text = intent.prefill
                typing++
            }
            else -> onAction(intent)
        }
    }
    LaunchedEffect(prefill) {
        if (prefill != null) {
            act(ActionIntent.Type(prefill))
            onPrefillTaken()
        }
    }
    LaunchedEffect(typing) {
        if (typing > 0) runCatching { focus.requestFocus() }
    }
    val layout = state.layout

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 640.dp
        CanvasBackdrop(
            backdrop = layout.backdrop,
            mood = state.globeMood,
            level = if (state.mic == MicState.Listening) level() else 0f,
            map = map,
            tiles = tiles,
            mapStyle = mapStyle,
            focus = state.focus,
            onGlobeTap = onCharacter
        )
        Column(Modifier.fillMaxSize()) {
            TopBar(state, act)
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Cafe.space.gutter)) {
                    Column(Modifier.weight(0.42f).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        CharacterBlock(state, level, onCharacter, act, hero = true)
                    }
                    Spacer(Modifier.size(Cafe.space.l))
                    CardColumn(layout.cards, layout.shelf, cards, act, Modifier.weight(0.58f).fillMaxSize())
                }
            } else {
                Middle(state, cards, level, onCharacter, act, Modifier.weight(1f).fillMaxWidth())
            }
            Controls(layout.controls, layout.primary.intent, act)
            ComposerBar(
                text = text,
                onTextChange = { text = it },
                onSend = {
                    val said = text.trim()
                    if (said.isNotEmpty()) {
                        text = ""
                        onSend(said)
                    }
                },
                onMic = { act(micIntent(state.mic)) },
                onCamera = onCamera,
                mic = state.mic,
                partial = state.partial,
                placeholder = "Ask ${state.name} anything",
                focus = focus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Cafe.space.m, vertical = Cafe.space.s)
            )
        }
    }
}

private fun micIntent(mic: MicState): ActionIntent = when (mic) {
    MicState.Listening, MicState.Busy -> ActionIntent.Stop
    MicState.Unavailable -> ActionIntent.Type()
    MicState.Ready -> ActionIntent.Listen
}

@Composable
private fun TopBar(state: TalkState, act: (ActionIntent) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Cafe.space.gutter, end = Cafe.space.s, top = Cafe.space.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                state.greeting,
                style = Cafe.type.headline,
                color = Cafe.colors.espresso,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
            state.status?.let { StatusLine(it, Cafe.colors.honey) }
        }
        IconCircle(Icons.Rounded.WbSunny, "Today", { act(ActionIntent.Open(Room.Today)) }, size = 44.dp)
        // The map had a place in the old dock; without it here, it vanished from the
        // canvas as soon as an answer was showing.
        IconCircle(Icons.Rounded.Map, "Map", { act(ActionIntent.Open(Room.Map)) }, size = 44.dp)
        IconCircle(Icons.AutoMirrored.Rounded.MenuBook, "Library: memories, lists, money and tasks", { act(ActionIntent.Open(Room.Library)) }, size = 44.dp)
        IconCircle(Icons.Rounded.AccountCircle, "You and settings", { act(ActionIntent.Open(Room.Settings)) }, size = 44.dp)
    }
}

@Composable
private fun Middle(
    state: TalkState,
    cards: CardContext,
    level: () -> Float,
    onCharacter: () -> Unit,
    act: (ActionIntent) -> Unit,
    modifier: Modifier
) {
    val layout = state.layout
    val reduce = Cafe.reduceMotion
    val hero = layout.character == CharacterSize.Hero
    Column(modifier.padding(horizontal = Cafe.space.gutter)) {
        if (hero) {
            // Room for the globe above, Mochi below it, the words under Mochi.
            Spacer(Modifier.weight(if (layout.cards.isEmpty()) 1f else 0.25f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CharacterBlock(state, level, onCharacter, act, hero = true)
            }
            if (layout.cards.isNotEmpty() || layout.shelf.isNotEmpty()) {
                VSpace(Cafe.space.m)
                CardColumn(layout.cards, layout.shelf, cards, act, Modifier.weight(1f).fillMaxWidth())
            } else {
                Spacer(Modifier.weight(0.35f))
            }
        } else {
            val size by animateDpAsState(
                if (layout.character == CharacterSize.Corner) 52.dp else 96.dp,
                CafeMotion.settle(reduce),
                label = "mochi-size"
            )
            Row(Modifier.fillMaxWidth().padding(top = Cafe.space.s), verticalAlignment = Alignment.Bottom) {
                Mochi(state.character, size = size, level = level, onClick = onCharacter, clickLabel = "Talk to ${state.name}")
                Spacer(Modifier.size(Cafe.space.m))
                Column(Modifier.weight(1f)) {
                    Speech(state, small = true, act = act)
                    PrimaryStep(layout, act)
                }
            }
            VSpace(Cafe.space.s)
            CardColumn(layout.cards, layout.shelf, cards, act, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Mochi at hero size, its words, what it is working with, and the moment's step forward. */
@Composable
private fun CharacterBlock(
    state: TalkState,
    level: () -> Float,
    onCharacter: () -> Unit,
    act: (ActionIntent) -> Unit,
    hero: Boolean
) {
    val layout = state.layout
    Mochi(
        state.character,
        size = if (hero) 168.dp else 96.dp,
        level = level,
        onClick = onCharacter,
        clickLabel = if (state.mic == MicState.Listening) "Stop listening" else "Talk to ${state.name}"
    )
    VSpace(Cafe.space.s)
    Speech(state, small = false, act = act, centered = true)
    PrimaryStep(layout, act)
    if (layout.prompts.isNotEmpty() && layout.cards.isEmpty()) {
        VSpace(Cafe.space.m)
        Wrap(Modifier.widthIn(max = 520.dp)) {
            layout.prompts.forEach { prompt -> FollowChip(prompt.label, { act(com.lukas.jarvis.moment.Cards.intentFor(prompt)) }) }
        }
    }
}

/**
 * The moment's own step forward, drawn whenever no card already carries it —
 * except at rest, where it is the microphone in the composer.
 */
@Composable
private fun PrimaryStep(layout: Layout, act: (ActionIntent) -> Unit) {
    val primary = layout.primary
    val onCard = layout.cards.any { card -> card.actions.any { it.intent == primary.intent } }
    if (onCard || layout.moment == com.lukas.jarvis.moment.Moment.Resting) return
    VSpace(Cafe.space.s)
    CafeButton(primary.label, { act(primary.intent) }, kind = if (primary.intent == ActionIntent.Stop) ButtonKind.Secondary else ButtonKind.Primary)
}

/** What Mochi is saying, in its own serif voice, with the tools it reached for underneath. */
@Composable
private fun Speech(state: TalkState, small: Boolean, act: (ActionIntent) -> Unit, centered: Boolean = false) {
    val listening = state.mic == MicState.Listening
    val words = when {
        listening && state.partial.isNotBlank() -> state.partial
        listening -> "I'm listening…"
        state.speech.isNotBlank() -> state.speech
        else -> state.layout.headline
    }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Text(
            words,
            style = (if (small) Cafe.type.voiceSmall else Cafe.type.voice).let { if (listening) it.copy(fontStyle = FontStyle.Italic) else it },
            color = if (listening) Cafe.colors.cocoa else Cafe.colors.espresso,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            maxLines = if (small) 4 else 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(Cafe.shape.medium)
                .clickable(onClickLabel = "Read the whole conversation", role = Role.Button) { act(ActionIntent.Open(Room.History)) }
                // A short line is still a finger's height to tap.
                .defaultMinSize(minHeight = Cafe.space.touch)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(vertical = Cafe.space.xs)
        )
        if (state.trail.isNotEmpty()) {
            VSpace(Cafe.space.xs)
            ToolTrail(state.trail, working = state.working)
        }
    }
}

/** The cards up now, and under them the quiet strip of everything earlier. */
@Composable
private fun CardColumn(
    visible: List<CanvasCard>,
    shelf: List<CanvasCard>,
    context: CardContext,
    act: (ActionIntent) -> Unit,
    modifier: Modifier
) {
    Column(modifier) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Cafe.space.m),
            contentPadding = PaddingValues(vertical = Cafe.space.xs)
        ) {
            items(visible, key = { it.id }) { card ->
                CanvasCardView(card, context, act, Modifier.animateItem())
            }
        }
        AnimatedVisibility(shelf.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Shelf(shelf, act)
        }
    }
}

/** Earlier cards, small and quiet. A tap brings one back. */
@Composable
private fun Shelf(shelf: List<CanvasCard>, act: (ActionIntent) -> Unit) {
    LazyRow(
        Modifier
            .fillMaxWidth()
            .padding(top = Cafe.space.s)
            .semantics { contentDescription = "Earlier: ${shelf.size} cards" },
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.s)
    ) {
        items(shelf.take(20), key = { "shelf:" + it.id }) { card ->
            Row(
                Modifier
                    .defaultMinSize(minHeight = Cafe.space.touch)
                    .clip(Cafe.shape.pill)
                    .background(Cafe.colors.paper.copy(alpha = 0.92f))
                    .clickable(onClickLabel = "Bring back ${card.title}", role = Role.Button) { act(ActionIntent.BringBack(card.id)) }
                    .padding(horizontal = Cafe.space.m, vertical = Cafe.space.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(card.title.ifBlank { card.kind.label }, style = Cafe.type.labelSmall, color = Cafe.colors.cocoa, maxLines = 1)
            }
        }
    }
}

/** The controls that make sense right now. */
@Composable
private fun Controls(controls: List<Control>, primary: ActionIntent, act: (ActionIntent) -> Unit) {
    if (controls.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth().padding(top = Cafe.space.xs),
        contentPadding = PaddingValues(horizontal = Cafe.space.m),
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.s)
    ) {
        items(controls, key = { it.label }) { control ->
            Row(
                Modifier
                    .defaultMinSize(minHeight = Cafe.space.touch)
                    .clip(Cafe.shape.pill)
                    .background(if (control.intent == primary) Cafe.colors.accentSoft else Cafe.colors.paper.copy(alpha = 0.9f))
                    .clickable(role = Role.Button) { act(control.intent) }
                    .padding(horizontal = Cafe.space.m, vertical = Cafe.space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Cafe.space.xs)
            ) {
                Icon(control.icon.vector(), contentDescription = null, tint = Cafe.colors.accentText, modifier = Modifier.size(18.dp))
                Text(control.label, style = Cafe.type.labelSmall, color = Cafe.colors.espresso, maxLines = 1)
            }
        }
    }
}

private fun ControlIcon.vector(): ImageVector = when (this) {
    ControlIcon.Mic -> Icons.Rounded.Mic
    ControlIcon.Keyboard -> Icons.Rounded.Keyboard
    ControlIcon.Stop -> Icons.Rounded.Stop
    ControlIcon.Retry -> Icons.Rounded.Refresh
    ControlIcon.Today -> Icons.Rounded.WbSunny
    ControlIcon.Library -> Icons.AutoMirrored.Rounded.MenuBook
    ControlIcon.Map -> Icons.Rounded.Map
    ControlIcon.History -> Icons.Rounded.History
    ControlIcon.Alarm -> Icons.Rounded.Alarm
    ControlIcon.Directions -> Icons.Rounded.Directions
    ControlIcon.Check -> Icons.Rounded.Check
    ControlIcon.Undo -> Icons.Rounded.Refresh
}
