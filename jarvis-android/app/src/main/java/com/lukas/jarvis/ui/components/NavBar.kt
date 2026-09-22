package com.lukas.jarvis.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Hairline
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint

/** One destination in the bar. */
data class NavEntry(val id: String, val label: String, val icon: ImageVector)

/**
 * The bar along the bottom, drawn by hand.
 *
 * Material's NavigationBar was doing three things this design did not want: a
 * pill that pops in and out rather than travelling, a fixed 80dp height that
 * left the labels stranded, and a container colour that had to be fought back
 * to transparent on every use. The replacement is a lit pane that *slides* to
 * the selected item — the same idea as a physical switch, where the indicator
 * moves and nothing lights up in two places at once — over a pane of glass.
 *
 * BoxWithConstraints is what makes the slide possible: the indicator's offset is
 * a share of the measured width rather than a guess at it, so it lands correctly
 * on a small phone and on a tablet without a breakpoint anywhere.
 */
@Composable
fun JarvisNavBar(
    entries: List<NavEntry>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    val index = entries.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color(0x1AFFFFFF), Color(0x0BFFFFFF)))
            )
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = Space.tight, vertical = Space.hair + 2.dp)
        ) {
            val slotWidth = maxWidth / entries.size
            val indicator by animateDpAsState(
                targetValue = slotWidth * index,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "nav-indicator"
            )

            Box(
                modifier = Modifier
                    .offset(x = indicator)
                    .width(slotWidth)
                    .fillMaxSize()
                    .padding(horizontal = Space.hair)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x1FFFFFFF))
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                entries.forEachIndexed { position, entry ->
                    val selected = position == index
                    val tint by animateColorAsState(
                        targetValue = if (selected) Accent else TextFaint,
                        label = "nav-tint"
                    )
                    // No ripple: the indicator already answers the tap, and a
                    // rectangular ripple under a rounded pill reads as a bug.
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        modifier = Modifier
                            .width(slotWidth)
                            .fillMaxSize()
                            .selectable(
                                selected = selected,
                                interactionSource = interaction,
                                indication = null,
                                onClick = { onSelect(entry.id) }
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            entry.icon,
                            contentDescription = entry.label,
                            tint = tint,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
