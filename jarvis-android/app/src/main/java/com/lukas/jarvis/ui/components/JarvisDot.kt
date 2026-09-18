package com.lukas.jarvis.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentSoft
import com.lukas.jarvis.vm.Stage
import kotlin.math.sin

/**
 * The one control: tap to talk, tap again to stop.
 *
 * It used to be the globe, which made turning the planet and speaking to it the
 * same gesture and tied the microphone to whichever screen happened to show a
 * planet. The dot is small, has one meaning, and follows you everywhere — the
 * elements behind it change, this does not.
 *
 * It says what it is doing without words: resting is a dim pearl, listening
 * swells with the voice, working carries a turning arc, speaking is lit
 * through. Those are the same four states the globe used, because they are the
 * assistant's states rather than any one screen's.
 */
@Composable
fun JarvisDot(
    stage: Stage,
    level: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp
) {
    val transition = rememberInfiniteTransition(label = "dot")

    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "breath"
    )

    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )

    val smoothed by animateFloatAsState(
        targetValue = level,
        animationSpec = tween(130, easing = FastOutSlowInEasing),
        label = "level"
    )

    val glow by animateFloatAsState(
        targetValue = when (stage) {
            Stage.Idle -> 0.30f
            Stage.Listening -> 0.85f
            Stage.Thinking -> 0.55f
            Stage.Speaking -> 1f
        },
        animationSpec = tween(300),
        label = "glow"
    )

    val tint = if (stage == Stage.Thinking) AccentSoft else Accent

    // No ripple: a silver pearl with a grey rectangle flashing behind it is the
    // one thing that would give away that this is a button on a screen.
    val interactions = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = onTap
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val centre = Offset(this.size.width / 2f, this.size.height / 2f)
            val outer = this.size.minDimension / 2f

            val pulse = 1f + 0.05f * sin(breath)
            val voice = if (stage == Stage.Listening) smoothed * 0.30f else 0f
            val core = outer * (0.34f * pulse + voice)

            // A soft halo so the dot sits over any element without a hard edge
            // between it and whatever is behind.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        tint.copy(alpha = 0.22f * glow),
                        tint.copy(alpha = 0.07f * glow),
                        Color.Transparent
                    ),
                    center = centre,
                    radius = outer
                ),
                radius = outer,
                center = centre
            )

            drawCircle(
                color = tint.copy(alpha = 0.22f + 0.30f * glow),
                radius = outer * 0.70f,
                center = centre,
                style = Stroke(width = 1.2f)
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.90f * glow),
                        tint,
                        tint.copy(alpha = 0.65f)
                    ),
                    center = centre.copy(
                        x = centre.x - core * 0.20f,
                        y = centre.y - core * 0.24f
                    ),
                    radius = core * 1.7f
                ),
                radius = core,
                center = centre
            )

            if (stage == Stage.Thinking) {
                val arc = outer * 0.70f
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            tint.copy(alpha = 0.06f),
                            tint.copy(alpha = 0.95f),
                            Color.Transparent
                        ),
                        center = centre
                    ),
                    startAngle = sweep,
                    sweepAngle = 105f,
                    useCenter = false,
                    topLeft = Offset(centre.x - arc, centre.y - arc),
                    size = Size(arc * 2, arc * 2),
                    style = Stroke(width = 2.2f)
                )
            }
        }
    }
}
