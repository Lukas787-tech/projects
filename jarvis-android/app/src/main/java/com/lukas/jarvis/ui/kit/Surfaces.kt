package com.lukas.jarvis.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.Elevation
import com.lukas.jarvis.ui.theme.paper

/** What a card is tinted for: plain paper, or a soft wash that says something. */
enum class Tone { Paper, Accent, Sage, Berry, Honey, Latte }

@Composable
internal fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Paper -> Cafe.colors.paper
    Tone.Accent -> Cafe.colors.accentSoft
    Tone.Sage -> Cafe.colors.sageSoft
    Tone.Berry -> Cafe.colors.berrySoft
    Tone.Honey -> Cafe.colors.honeySoft
    Tone.Latte -> Cafe.colors.latte
}

/**
 * A sheet of paper on the page: the one card every surface of the app is made
 * of. It rests with a soft warm shadow; with [onClick] it is a button too, and
 * [clickLabel] tells TalkBack what a tap does.
 */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Paper,
    elevation: Elevation = Elevation.Resting,
    shape: Shape = Cafe.shape.large,
    padding: Dp = Cafe.space.l,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = Cafe.colors
    Column(
        modifier
            .paper(elevation, shape, colors)
            .clip(shape)
            .background(toneColor(tone))
            .then(
                if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
                else Modifier
            )
            .padding(padding),
        content = content
    )
}

/** A quieter surface inside a card: a field, a tile, a group of chips. */
@Composable
fun LatteSurface(
    modifier: Modifier = Modifier,
    shape: Shape = Cafe.shape.medium,
    padding: Dp = Cafe.space.m,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(shape)
            .background(Cafe.colors.latte)
            .then(
                if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
                else Modifier
            )
            .padding(padding),
        content = content
    )
}

/** A heading over a group of things, with an optional action on the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(top = Cafe.space.s, bottom = Cafe.space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = Cafe.type.title,
            color = Cafe.colors.espresso,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (action != null && onAction != null) {
            QuietButton(action, onClick = onAction)
        }
    }
}

/** A caption over a card's content: "REMINDER", "FROM THE WEB". */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Cafe.colors.cocoa) {
    Text(text.uppercase(), style = Cafe.type.caption, color = color, modifier = modifier, maxLines = 1)
}

/** A soft line between things. Never a hard border. */
@Composable
fun SoftDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Cafe.colors.latteDeep.copy(alpha = 0.7f))
    )
}

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))

/** A row of things with the café's gap between them. */
@Composable
fun SpacedRow(
    modifier: Modifier = Modifier,
    gap: Dp = Cafe.space.s,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) = Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically, content = content)
