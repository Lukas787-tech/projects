package com.lukas.jarvis.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.InkRaised
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.glass
import com.lukas.jarvis.ui.theme.glow
import com.lukas.jarvis.vm.Stage

/** One destination in the dock. */
data class NavEntry(val id: String, val label: String, val icon: ImageVector)

/**
 * The dock along the bottom: two destinations either side of the core.
 *
 * The core is the assistant. One tap talks, from wherever you are — it brings
 * the assistant forward and starts listening, so asking something never takes
 * two taps. Tapping it again stops listening, or stops a turn that is taking
 * too long. A long press opens the assistant ready to type instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JarvisDock(
    left: List<NavEntry>,
    right: List<NavEntry>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    stage: Stage,
    level: Float,
    coreStyle: CoreStyle,
    coreSelected: Boolean,
    onCoreTap: () -> Unit,
    onCoreLongPress: () -> Unit,
    haptics: Boolean,
    modifier: Modifier = Modifier
) {
    val feel = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.snug, vertical = Space.tight)
                .height(64.dp)
                .glass(RoundedCornerShape(28.dp))
                .background(InkRaised.copy(alpha = 0.55f), RoundedCornerShape(28.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            left.forEach { entry ->
                DockItem(entry, entry.id == selectedId, Modifier.weight(1f)) {
                    if (haptics) feel.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(entry.id)
                }
            }
            // Room for the core, which sits above the bar.
            Spacer(Modifier.width(84.dp))
            right.forEach { entry ->
                DockItem(entry, entry.id == selectedId, Modifier.weight(1f)) {
                    if (haptics) feel.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(entry.id)
                }
            }
        }

        val mood = when (stage) {
            Stage.Idle -> GlobeMood.Resting
            Stage.Listening -> GlobeMood.Listening
            Stage.Thinking -> GlobeMood.Working
            Stage.Speaking -> GlobeMood.Speaking
        }
        val lift by animateFloatAsState(if (coreSelected || stage != Stage.Idle) 1f else 0.6f, label = "core-lift")
        val interaction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 2.dp)
                .size(80.dp)
                .glow(strength = lift)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(InkRaised, InkRaised.copy(alpha = 0.92f), InkRaised.copy(alpha = 0f))
                    )
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        if (haptics) feel.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCoreTap()
                    },
                    onLongClick = {
                        if (haptics) feel.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCoreLongPress()
                    },
                    onClickLabel = "Talk",
                    onLongClickLabel = "Type instead"
                ),
            contentAlignment = Alignment.Center
        ) {
            Reactor(
                mood = mood,
                level = level,
                compact = true,
                style = if (coreStyle == CoreStyle.Orb) CoreStyle.Orb else CoreStyle.Reactor,
                modifier = Modifier.size(76.dp)
            )
        }
    }
}

@Composable
private fun DockItem(entry: NavEntry, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint by animateColorAsState(if (selected) Accent else TextFaint, label = "dock-tint")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Film.selected else Film.faint.copy(alpha = 0f))
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Icon(entry.icon, contentDescription = entry.label, tint = tint, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            entry.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.4.sp),
            color = tint,
            maxLines = 1
        )
    }
}
