package com.lukas.jarvis.ui.kit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Cafe

/**
 * An empty place that still goes somewhere: what could be here, and one tap
 * that starts it — "Want me to start a shopping list?". [art] is where Mochi
 * stands when there is room for it.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    art: (@Composable () -> Unit)? = null,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Cafe.space.xl, horizontal = Cafe.space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Cafe.space.s)
    ) {
        art?.invoke()
        Text(title, style = Cafe.type.title, color = Cafe.colors.espresso, textAlign = TextAlign.Center)
        Text(body, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa, textAlign = TextAlign.Center)
        VSpace(Cafe.space.xs)
        CafeButton(action, onAction)
        if (secondary != null && onSecondary != null) QuietButton(secondary, onSecondary)
    }
}

/**
 * Something went wrong, said plainly, with a way forward: always a retry, and
 * an alternative — another model, offline, or doing it by hand. Apologetic,
 * never alarming: a soft berry wash, no red siren.
 */
@Composable
fun ProblemCard(
    title: String,
    body: String,
    onRetry: () -> Unit,
    alternative: String,
    onAlternative: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "Try again"
) {
    PaperCard(
        modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        tone = Tone.Berry
    ) {
        Text(title, style = Cafe.type.title, color = Cafe.colors.espresso)
        VSpace(Cafe.space.xs)
        Text(body, style = Cafe.type.bodySmall, color = Cafe.colors.espresso)
        VSpace(Cafe.space.m)
        Wrap {
            CafeButton(retryLabel, onRetry, icon = Icons.Rounded.Refresh)
            CafeButton(alternative, onAlternative, kind = ButtonKind.Secondary)
        }
    }
}

/** A tiny pixel-font tag: a timer's remaining time, the character's "zz", a status. */
@Composable
fun PixelTag(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Cafe.colors.espresso,
    color: Color = Cafe.colors.paper
) {
    Box(
        modifier
            .clip(Cafe.shape.small)
            .background(background)
            .padding(horizontal = Cafe.space.s, vertical = Cafe.space.xxs)
    ) {
        Text(text, style = Cafe.type.pixel, color = color, maxLines = 1)
    }
}

/**
 * Lines of paper while something loads. They breathe slowly; with reduce
 * motion they simply sit there.
 */
@Composable
fun Skeleton(lines: Int = 3, modifier: Modifier = Modifier, label: String = "Loading") {
    val reduce = Cafe.reduceMotion
    val pulse = if (reduce) {
        0.6f
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val a by transition.animateFloat(0.45f, 0.85f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse")
        a
    }
    Column(
        modifier.semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(Cafe.space.s)
    ) {
        repeat(lines) { index ->
            Box(
                Modifier
                    .fillMaxWidth(if (index == lines - 1) 0.6f else 1f)
                    .height(14.dp)
                    .alpha(pulse)
                    .clip(Cafe.shape.pill)
                    .background(Cafe.colors.latte)
            )
        }
    }
}

/** A small dot that says how something is: ready, busy, off. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(Cafe.shape.pill).background(color))
}

/** A labelled status line: a dot and a few words. */
@Composable
fun StatusLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Cafe.space.s)) {
        StatusDot(color)
        Text(text, style = Cafe.type.labelSmall, color = Cafe.colors.cocoa)
    }
}
