package com.lukas.jarvis.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Cafe

/**
 * A live control: the torch, the ringer, Do Not Disturb. It shows how things
 * are now — "On", "Vibrate", "Until 15:00" — not just a button, and a tap
 * changes it.
 */
@Composable
fun ControlTile(
    icon: ImageVector,
    label: String,
    state: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Cafe.colors
    Column(
        modifier
            .defaultMinSize(minHeight = 92.dp, minWidth = 104.dp)
            .clip(Cafe.shape.large)
            .background(if (on) colors.accentSoft else colors.latte)
            .clickable(role = Role.Switch, onClickLabel = "Change $label", onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = state }
            .padding(Cafe.space.m),
        verticalArrangement = Arrangement.spacedBy(Cafe.space.xs)
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(Cafe.shape.pill)
                .background(if (on) colors.accentFill else colors.paper),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (on) colors.onAccent else colors.espresso, modifier = Modifier.size(18.dp))
        }
        Text(label, style = Cafe.type.labelSmall, color = colors.espresso, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(state, style = Cafe.type.caption, color = colors.cocoa, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A countdown in progress: its name, the time left in pixel digits, and the
 * two things you might want — more time, or stop.
 */
@Composable
fun TimerTile(
    label: String,
    remaining: String,
    progress: Float,
    ringing: Boolean,
    onAddMinute: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Cafe.colors
    PaperCard(modifier.fillMaxWidth(), tone = if (ringing) Tone.Honey else Tone.Paper, padding = Cafe.space.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow(if (ringing) "Time's up" else label)
                Text(
                    if (ringing) label else remaining,
                    style = if (ringing) Cafe.type.title else Cafe.type.pixelLarge,
                    color = colors.espresso,
                    maxLines = 1
                )
            }
            if (!ringing) IconCircle(Icons.Rounded.Add, "One more minute on $label", onAddMinute)
            HSpace(Cafe.space.s)
            IconCircle(Icons.Rounded.Close, if (ringing) "Stop the alarm" else "Cancel $label", onStop, filled = ringing)
        }
        if (!ringing) {
            VSpace(Cafe.space.s)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(Cafe.shape.pill).background(colors.latte)) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(Cafe.shape.pill)
                        .background(colors.accent)
                )
            }
        }
    }
}
