package com.lukas.jarvis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Hairline
import com.lukas.jarvis.ui.theme.Ink
import com.lukas.jarvis.ui.theme.InkCard
import com.lukas.jarvis.ui.theme.InkRaised
import com.lukas.jarvis.ui.theme.Negative
import com.lukas.jarvis.ui.theme.Positive
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary

/**
 * A titled group of settings. Grouping the long settings list into panels is
 * the difference between a wall of fields and something you can scan.
 */
@Composable
fun Panel(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    // Saveable so a rotation does not fold everything the user opened back up.
    var expanded by rememberSaveable(title) { mutableStateOf(!collapsible || initiallyExpanded) }

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (collapsible) {
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { expanded = !expanded }
                    } else {
                        Modifier
                    }
                )
                .padding(start = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint,
                modifier = Modifier.weight(1f)
            )
            if (collapsible) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = TextFaint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(listOf(InkRaised, InkCard))
                    )
                    .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                content()
            }
        }
    }
}

enum class BannerTone { Good, Bad, Neutral }

/** Inline result panel — used for the connection test and model refresh. */
@Composable
fun Banner(
    tone: BannerTone,
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier
) {
    val color = when (tone) {
        BannerTone.Good -> Positive
        BannerTone.Bad -> Negative
        BannerTone.Neutral -> Accent
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = color)
        body?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
}

/** Compact filled button used for Refresh / Test, with a busy state. */
@Composable
fun ChipButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
    prominent: Boolean = false
) {
    val background = if (prominent) Accent else InkCard
    val content = if (prominent) Ink else TextPrimary
    val alpha = if (enabled && !busy) 1f else 0.45f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background.copy(alpha = alpha))
            .border(
                1.dp,
                if (prominent) Color.Transparent else Hairline,
                RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled && !busy) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = content.copy(alpha = alpha)
            )
        } else if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = content.copy(alpha = alpha),
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = content.copy(alpha = alpha)
        )
    }
}

/** Small round status dot, e.g. next to a connection state. */
@Composable
fun StatusDot(tone: BannerTone, modifier: Modifier = Modifier) {
    val color = when (tone) {
        BannerTone.Good -> Positive
        BannerTone.Bad -> Negative
        BannerTone.Neutral -> TextFaint
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}
