package com.lukas.jarvis.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.Motion
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.glass

/** The lit pane behind the active segment. */
private val SelectedFill = Film.selected

/**
 * A row of pills, one of which is on.
 *
 * This exists so related screens can share one place in the navigation bar. Seven
 * bottom-bar destinations on a phone is six icons too many: they end up unlabelled
 * and the same width as a fingertip, and picking the wrong one is the norm rather
 * than the accident. Grouping the three list screens behind one tab buys every
 * remaining destination a readable label.
 *
 * The lit pane travels between segments rather than appearing under the new one.
 * A switch that slides tells you which way you just moved; one that blinks makes
 * you check the labels again.
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: List<String?> = emptyList()
) {
    if (options.isEmpty()) return
    val index = selectedIndex.coerceIn(0, options.lastIndex)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(Corner.medium))
            .padding(Space.hair)
            .height(44.dp)
    ) {
        val slot = maxWidth / options.size
        val travel by animateDpAsState(
            targetValue = slot * index,
            animationSpec = Motion.glide(),
            label = "segment-travel"
        )

        Box(
            modifier = Modifier
                .offset(x = travel)
                .width(slot)
                .fillMaxHeight()
                .clip(RoundedCornerShape(Corner.small + 2.dp))
                .background(SelectedFill)
        )

        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { position, option ->
                val selected = position == index
                val foreground by animateColorAsState(
                    targetValue = if (selected) Accent else TextSecondary,
                    label = "segment-foreground"
                )
                val badge = badges.getOrNull(position)
                Text(
                    text = if (badge.isNullOrBlank()) option else "$option  $badge",
                    style = MaterialTheme.typography.titleMedium,
                    color = foreground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .width(slot)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(Corner.small + 2.dp))
                        .clickable { onSelect(position) }
                        .wrapContentHeight(Alignment.CenterVertically)
                )
            }
        }
    }
}
