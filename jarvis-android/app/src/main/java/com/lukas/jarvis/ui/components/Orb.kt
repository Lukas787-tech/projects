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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The one piece of decoration in the app. It has to say — without any text —
 * whether Jarvis is idle, hearing you, working, or talking.
 */
@Composable
fun Orb(
    stage: Stage,
    level: Float,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp
) {
    val transition = rememberInfiniteTransition(label = "orb")

    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breath"
    )

    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    // Raw RMS is jittery; smoothing it stops the orb from strobing.
    val smoothedLevel by animateFloatAsState(
        targetValue = level,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "level"
    )

    val tint = when (stage) {
        Stage.Idle -> Accent.copy(alpha = 0.55f)
        Stage.Listening -> Accent
        Stage.Thinking -> AccentSoft
        Stage.Speaking -> Color(0xFF5FE3C0)
    }

    val intensity by animateFloatAsState(
        targetValue = when (stage) {
            Stage.Idle -> 0.35f
            Stage.Listening -> 0.75f
            Stage.Thinking -> 0.6f
            Stage.Speaking -> 0.85f
        },
        animationSpec = tween(durationMillis = 400),
        label = "intensity"
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxRadius = this.size.minDimension / 2f

            val pulse = 1f + 0.035f * sin(breath)
            val voice = if (stage == Stage.Listening) smoothedLevel * 0.28f else 0f
            val coreRadius = maxRadius * (0.30f * pulse + voice)

            // Outer atmosphere: a wide, very soft falloff that reads as glow
            // rather than as a hard-edged circle.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        tint.copy(alpha = 0.28f * intensity),
                        tint.copy(alpha = 0.10f * intensity),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxRadius
                ),
                radius = maxRadius,
                center = center
            )

            // Mid halo, breathing slightly out of phase with the core.
            val haloRadius = maxRadius * (0.62f + 0.03f * sin(breath + 1.2f) + voice * 0.5f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, tint.copy(alpha = 0.22f * intensity)),
                    center = center,
                    radius = haloRadius
                ),
                radius = haloRadius,
                center = center
            )

            // Thin definition ring.
            drawCircle(
                color = tint.copy(alpha = 0.35f + 0.25f * intensity),
                radius = haloRadius,
                center = center,
                style = Stroke(width = 1.2f)
            )

            // The solid core.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.85f * intensity),
                        tint,
                        tint.copy(alpha = 0.6f)
                    ),
                    center = center.copy(
                        x = center.x - coreRadius * 0.18f,
                        y = center.y - coreRadius * 0.22f
                    ),
                    radius = coreRadius * 1.6f
                ),
                radius = coreRadius,
                center = center
            )

            // Working state gets a travelling arc so progress is visible even
            // when there is nothing to say yet.
            if (stage == Stage.Thinking) {
                val arcRadius = maxRadius * 0.80f
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            tint.copy(alpha = 0.05f),
                            tint.copy(alpha = 0.9f),
                            Color.Transparent
                        ),
                        center = center
                    ),
                    startAngle = sweep,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(width = 2.5f)
                )
            }
        }
    }
}
