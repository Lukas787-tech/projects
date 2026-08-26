package com.expensesplit.app.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.expensesplit.app.ui.theme.LocalFinanceColors

/**
 * Spending-over-time line with an optional dashed projection tail.
 *
 * The curve is drawn as a smoothed path (a cubic through midpoints) so a noisy daily series reads
 * as a trend rather than a saw blade, and it is filled with a fading gradient to keep the area
 * legible against both light and dark surfaces.
 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: Int = 160,
    lineColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    /** Appended after [values] and drawn dashed — the forecast, visibly distinct from actuals. */
    projection: List<Float> = emptyList(),
    showArea: Boolean = true,
) {
    if (values.size < 2) return

    val gridColor = LocalFinanceColors.current.chartGrid
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 700),
        label = "line-draw",
    )

    val allValues = values + projection
    val maxValue = allValues.maxOrNull()?.coerceAtLeast(0.0001f) ?: 1f
    val minValue = 0f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp),
    ) {
        repeat(3) { index ->
            val y = size.height * (index + 1) / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        val totalPoints = allValues.size
        val stepX = size.width / (totalPoints - 1).coerceAtLeast(1)

        fun pointAt(index: Int, value: Float): Offset {
            val normalized = (value - minValue) / (maxValue - minValue)
            return Offset(index * stepX, size.height - normalized * size.height * progress)
        }

        val actualPoints = values.mapIndexed { index, value -> pointAt(index, value) }
        val linePath = smoothPath(actualPoints)

        if (showArea) {
            val areaPath = Path().apply {
                addPath(linePath)
                lineTo(actualPoints.last().x, size.height)
                lineTo(actualPoints.first().x, size.height)
                close()
            }
            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f)),
                ),
            )
        }

        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 3.dp.toPx()),
        )

        if (projection.isNotEmpty()) {
            val projectionPoints = buildList {
                add(actualPoints.last())
                projection.forEachIndexed { offset, value ->
                    add(pointAt(values.size + offset, value))
                }
            }
            drawPath(
                path = smoothPath(projectionPoints),
                color = lineColor.copy(alpha = 0.65f),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                ),
            )
        }

        // Mark the latest actual reading so the "you are here" point is unambiguous.
        actualPoints.lastOrNull()?.let { last ->
            drawCircle(color = lineColor, radius = 4.dp.toPx(), center = last)
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = 1.8f.dp.toPx(),
                center = last,
            )
        }
    }
}

/**
 * Builds a rounded path through [points] using quadratic segments anchored at midpoints.
 * Cheaper than a full spline and, unlike one, it cannot overshoot below zero on a spiky series.
 */
private fun DrawScope.smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path

    path.moveTo(points.first().x, points.first().y)
    if (points.size == 1) return path

    for (index in 0 until points.size - 1) {
        val current = points[index]
        val next = points[index + 1]
        val midX = (current.x + next.x) / 2f
        val midY = (current.y + next.y) / 2f
        path.quadraticTo(current.x, current.y, midX, midY)
    }
    path.lineTo(points.last().x, points.last().y)
    return path
}
