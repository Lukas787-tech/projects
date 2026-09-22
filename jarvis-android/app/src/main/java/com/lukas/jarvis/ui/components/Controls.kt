package com.lukas.jarvis.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.Hairline
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.glass
import com.lukas.jarvis.ui.theme.glassCard

/**
 * The small parts every screen is assembled from.
 *
 * Before this file each screen drew its own version of the same four shapes —
 * a headline with a caption, a number in a box, a row with a switch on the end —
 * and they were all a few pixels and one font weight apart. Nothing here is
 * clever; the value is entirely in there being one of each.
 */

/** A number worth reading from across the room, with a word under it. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    tint: Color = TextPrimary,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val base = modifier.glassCard(Corner.large)
    Column(
        modifier = (if (onClick != null) base.clickable { onClick() } else base)
            .padding(horizontal = Space.step, vertical = Space.snug)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = TextFaint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Space.hair + 2.dp))
            }
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(Space.hair + 2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineLarge,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** An icon over a word, sized for a thumb. The row of these is the shortcut bar. */
@Composable
fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    val tint = if (active) Accent else TextSecondary
    Column(
        modifier = modifier
            .glass(RoundedCornerShape(Corner.medium), raised = active)
            .clickable { onClick() }
            .padding(vertical = Space.snug, horizontal = Space.tight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.hair + 2.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A line of text with a switch at the end — the shape every setting takes. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.small))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = Space.tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Space.snug)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) TextPrimary else TextFaint
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = Accent,
                checkedBorderColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = Hairline
            )
        )
    }
}

/** A row in a list: a title, a supporting line, and something on each end. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: ImageVector? = null,
    trailing: String? = null,
    trailingTint: Color = TextSecondary,
    onClick: (() -> Unit)? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null
) {
    val base = modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.medium))
    Row(
        modifier = (if (onClick != null) base.clickable { onClick() } else base)
            .padding(vertical = Space.snug, horizontal = Space.hair),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                tint = TextFaint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Space.snug))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            content?.invoke(this)
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.tight))
            Text(trailing, style = MaterialTheme.typography.labelLarge, color = trailingTint)
        }
    }
}

/**
 * A horizontal bar showing how much of something is used.
 *
 * Deliberately not a Material ProgressIndicator: this is a budget, and it has to
 * be able to show more than full without either clamping silently or drawing
 * past its own track.
 */
@Composable
fun MeterBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    tint: Color = Accent,
    height: androidx.compose.ui.unit.Dp = 6.dp
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 650),
        label = "meter"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(Color(0x14FFFFFF))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(CircleShape)
                .background(tint)
        )
    }
}

/** A small capsule of text: a category, a state, a count. */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = TextSecondary,
    onClick: (() -> Unit)? = null
) {
    val base = modifier
        .clip(CircleShape)
        .background(Color(0x12FFFFFF))
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        maxLines = 1,
        modifier = (if (onClick != null) base.clickable { onClick() } else base)
            .padding(horizontal = Space.snug, vertical = 5.dp)
    )
}

/** The heading over a group, with an optional count on the right. */
@Composable
fun GroupHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Space.hair, top = Space.step, bottom = Space.tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = TextFaint)
        }
    }
}
