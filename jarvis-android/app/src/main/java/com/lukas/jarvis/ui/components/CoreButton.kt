package com.lukas.jarvis.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.theme.InkRaised
import com.lukas.jarvis.ui.theme.glow
import com.lukas.jarvis.vm.Stage

/**
 * The core, small and floating, over a screen the assistant opened. It is the
 * way back as much as the way to talk: one tap sends the screen away and
 * listens, a long press sends it away ready to type.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoreButton(
    stage: Stage,
    level: Float,
    coreStyle: CoreStyle,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    haptics: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp
) {
    val feel = LocalHapticFeedback.current
    val mood = when (stage) {
        Stage.Idle -> GlobeMood.Resting
        Stage.Listening -> GlobeMood.Listening
        Stage.Thinking -> GlobeMood.Working
        Stage.Speaking -> GlobeMood.Speaking
    }
    val lift by animateFloatAsState(if (stage != Stage.Idle) 1f else 0.6f, label = "core-lift")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .glow(strength = lift)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(listOf(InkRaised, InkRaised.copy(alpha = 0.92f), InkRaised.copy(alpha = 0f)))
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    if (haptics) feel.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTap()
                },
                onLongClick = {
                    if (haptics) feel.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
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
            modifier = Modifier.size(size - 4.dp)
        )
    }
}
