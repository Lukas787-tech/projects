package com.expensesplit.app.ui.charts

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** One wedge of a [DonutChart]. */
data class DonutSlice(val value: Float, val color: Color, val label: String)

/**
 * Category breakdown as a donut.
 *
 * Drawn on a Canvas rather than pulled from a chart library: it is a handful of arcs, and doing it
 * directly keeps the animation on Compose's own clock and the APK free of another dependency.
 * The centre is a slot, so callers put the total (or anything else) inside the ring.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    diameter: Int = 180,
    strokeWidth: Float = 42f,
    emptyColor: Color = Color.Gray.copy(alpha = 0.2f),
    center: (@Composable () -> Unit)? = null,
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    val progress by animateFloatAsState(
        targetValue = if (total > 0f) 1f else 0f,
        animationSpec = tween(durationMillis = 650),
        label = "donut-sweep",
    )

    Box(modifier = modifier.size(diameter.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter.dp)) {
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)

            if (total <= 0f) {
                drawArc(
                    color = emptyColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth),
                )
                return@Canvas
            }

            // Start at 12 o'clock and sweep clockwise, which is how people read a pie.
            var startAngle = -90f
            slices.forEach { slice ->
                val sweep = slice.value / total * 360f * progress
                if (sweep <= 0f) return@forEach
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    // A hairline gap between wedges keeps adjacent colours from bleeding together.
                    sweepAngle = (sweep - GAP_DEGREES).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth),
                )
                startAngle += sweep
            }
        }
        center?.invoke()
    }
}

private const val GAP_DEGREES = 1.5f
