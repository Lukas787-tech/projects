package com.expensesplit.app.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.expensesplit.app.ui.theme.LocalFinanceColors

data class BarEntry(val label: String, val value: Float, val color: Color? = null)

/**
 * Vertical bars with x-axis labels underneath — used for month-over-month and per-day spend.
 *
 * Bars are scaled against the largest value rather than an absolute axis, because spending series
 * vary by orders of magnitude between users and a fixed scale would flatten most of them.
 */
@Composable
fun BarChart(
    entries: List<BarEntry>,
    modifier: Modifier = Modifier,
    height: Int = 160,
    barColor: Color = MaterialTheme.colorScheme.primary,
    highlightIndex: Int? = null,
    showLabels: Boolean = true,
) {
    if (entries.isEmpty()) return

    val gridColor = LocalFinanceColors.current.chartGrid
    val maxValue = entries.maxOf { it.value }.coerceAtLeast(0.0001f)
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 600),
        label = "bar-grow",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp),
        ) {
            // Three horizontal guides give the eye a reference without cluttering the plot.
            repeat(3) { index ->
                val y = size.height * (index + 1) / 4f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }

            val slotWidth = size.width / entries.size
            val barWidth = (slotWidth * 0.55f).coerceAtMost(36.dp.toPx())
            val radius = CornerRadius(barWidth / 3f, barWidth / 3f)

            entries.forEachIndexed { index, entry ->
                val normalized = (entry.value / maxValue).coerceIn(0f, 1f) * progress
                val barHeight = (size.height * normalized).coerceAtLeast(if (entry.value > 0f) 2f else 0f)
                val left = index * slotWidth + (slotWidth - barWidth) / 2f
                val color = entry.color ?: barColor

                drawRoundRect(
                    color = if (highlightIndex == index) color else color.copy(alpha = 0.72f),
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = radius,
                )
            }
        }

        if (showLabels) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                entries.forEach { entry ->
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
