package com.lukas.jarvis.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.glass
import com.lukas.jarvis.ui.theme.TextSecondary

/** The lit pane behind the active segment. */
private val SelectedFill = Color(0x1FFFFFFF)

/**
 * A row of pills, one of which is on.
 *
 * This exists so related screens can share one place in the navigation bar. Seven
 * bottom-bar destinations on a phone is six icons too many: they end up unlabelled
 * and the same width as a fingertip, and picking the wrong one is the norm rather
 * than the accident. Grouping the three list screens behind one tab buys every
 * remaining destination a readable label.
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: List<String?> = emptyList()
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            // The selected segment is a lit pane sliding under the label, the
            // way a physical switch shows its position: nothing is tinted, one
            // thing is simply brighter.
            val background by animateColorAsState(
                targetValue = if (selected) SelectedFill else Color.Transparent,
                label = "segment-background"
            )
            val foreground by animateColorAsState(
                targetValue = if (selected) Accent else TextSecondary,
                label = "segment-foreground"
            )
            val badge = badges.getOrNull(index)
            Text(
                text = if (badge.isNullOrBlank()) option else "$option  $badge",
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(background)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp)
            )
        }
    }
}
