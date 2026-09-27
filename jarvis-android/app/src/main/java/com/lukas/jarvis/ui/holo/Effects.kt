package com.lukas.jarvis.ui.holo

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentBright
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The light a panel is made of.
 *
 * Nothing here blurs or composites offscreen on every frame: a panel is its
 * content, revealed by a line of light sweeping down it, with a flicker as the
 * projection catches, a fine texture of scan lines over it and a band of
 * brighter light that rolls down it now and then. The same drawing run
 * backwards is the panel switching off.
 */

/** Share of the entrance spent sweeping; the rest is the flicker settling. */
private const val SWEEP_END = 0.62f

/**
 * Reveals the content behind a scan line: nothing at 0, all of it at 1. The
 * progress is read in the draw phase, so an animation drives this without
 * recomposing what it draws.
 */
fun Modifier.materialize(progress: () -> Float): Modifier = this
    .graphicsLayer {
        val p = progress().coerceIn(0f, 1f)
        alpha = if (p >= 1f) 1f else flicker(p)
    }
    .drawWithContent {
        val p = progress().coerceIn(0f, 1f)
        if (p <= 0f) return@drawWithContent
        if (p >= 1f) {
            drawContent()
            return@drawWithContent
        }
        val sweep = (p / SWEEP_END).coerceIn(0f, 1f)
        val eased = 1f - (1f - sweep) * (1f - sweep) * (1f - sweep)
        val line = size.height * eased

        // The outline arrives first, the way a projector finds its edges.
        drawOutline(alpha = min(1f, p / 0.18f) * (1f - sweep * 0.6f))

        clipRect(bottom = line) { this@drawWithContent.drawContent() }

        if (sweep < 1f) {
            val trail = 48.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Accent.copy(alpha = 0.28f),
                    startY = line - trail,
                    endY = line
                ),
                topLeft = Offset(0f, max(0f, line - trail)),
                size = Size(size.width, min(trail, line))
            )
            drawLine(
                color = AccentBright.copy(alpha = 0.95f),
                start = Offset(0f, line),
                end = Offset(size.width, line),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = Accent.copy(alpha = 0.35f),
                start = Offset(0f, line),
                end = Offset(size.width, line),
                strokeWidth = 7.dp.toPx()
            )
        }
    }

/**
 * How bright the projection is while it catches: a shimmer during the sweep,
 * then a dip and a double blink before it holds steady.
 */
internal fun flicker(p: Float): Float = when {
    p < SWEEP_END -> 0.8f + 0.2f * ((sin(p * 97f) + 1f) / 2f)
    p < 0.70f -> 0.42f
    p < 0.76f -> 1f
    p < 0.81f -> 0.66f
    else -> 1f
}

private fun DrawScope.drawOutline(alpha: Float) {
    if (alpha <= 0f) return
    val cut = 16.dp.toPx()
    val path = chamferPath(size, cut, 3.dp.toPx())
    drawPath(path, color = Accent.copy(alpha = 0.55f * alpha), style = Stroke(1.dp.toPx()))
}

/**
 * The texture of projected light: fine dark scan lines over everything and,
 * unless motion is calmed, a soft band that rolls down the panel. [phase] is
 * 0..1 through one roll.
 */
fun Modifier.hologram(phase: () -> Float, calm: Boolean): Modifier = drawWithContent {
    drawContent()
    val gap = 3.dp.toPx()
    val dark = Color.Black.copy(alpha = 0.10f)
    var y = 0f
    while (y < size.height) {
        drawLine(dark, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += gap
    }
    if (!calm) {
        val band = 90.dp.toPx()
        val at = -band + (size.height + 2 * band) * phase()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to Accent.copy(alpha = 0.07f),
                1f to Color.Transparent,
                startY = at - band / 2,
                endY = at + band / 2
            ),
            topLeft = Offset(0f, max(0f, at - band / 2)),
            size = Size(size.width, band)
        )
    }
}

/** Two cut corners — top left and bottom right — and the other two barely softened. */
class ChamferShape(private val cut: Dp = 16.dp, private val soft: Dp = 3.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(chamferPath(size, with(density) { cut.toPx() }, with(density) { soft.toPx() }))
}

internal fun chamferPath(size: Size, cut: Float, soft: Float): Path = Path().apply {
    val w = size.width
    val h = size.height
    moveTo(cut, 0f)
    lineTo(w - soft, 0f)
    lineTo(w, soft)
    lineTo(w, h - cut)
    lineTo(w - cut, h)
    lineTo(soft, h)
    lineTo(0f, h - soft)
    lineTo(0f, cut)
    close()
}

/**
 * The panel's lit details: a bright stroke along each cut corner and a short
 * ruler of ticks along the top edge, the marks that make a rectangle read as
 * an instrument.
 */
fun DrawScope.drawHoloTrim(accent: Color, bright: Color, cut: Float) {
    val stroke = 2.dp.toPx()
    val w = size.width
    val h = size.height
    drawLine(bright.copy(alpha = 0.9f), Offset(0f, cut), Offset(cut, 0f), stroke, StrokeCap.Round)
    drawLine(bright.copy(alpha = 0.9f), Offset(w - cut, h), Offset(w, h - cut), stroke, StrokeCap.Round)
    val tick = 5.dp.toPx()
    val step = 7.dp.toPx()
    val start = w - 28.dp.toPx() - step * 6
    for (i in 0 until 7) {
        val x = start + i * step
        val long = i == 0 || i == 6
        drawLine(
            accent.copy(alpha = if (long) 0.7f else 0.4f),
            Offset(x, 0f),
            Offset(x, if (long) tick * 1.6f else tick),
            1.dp.toPx()
        )
    }
    drawLine(accent.copy(alpha = 0.5f), Offset(cut + 10.dp.toPx(), h), Offset(cut + 70.dp.toPx(), h), stroke)
}

/**
 * The cone of light from the core down to the panel it is projecting.
 *
 * [from] is the core's centre and [radius] its size; the cone opens from its
 * lower rim to the panel's top edge, [left] to [right] at [top]. [pulse] is a
 * packet of light travelling down it, 0 at the core and 1 at the panel, drawn
 * only while it is under 1. [strength] scales the whole thing.
 */
fun DrawScope.drawBeam(
    from: Offset,
    radius: Float,
    left: Float,
    right: Float,
    top: Float,
    strength: Float,
    pulse: Float,
    accent: Color,
    bright: Color
) {
    if (strength <= 0.01f || top <= from.y) return
    val throatY = from.y + radius * 0.55f
    val throat = radius * 0.42f
    val inset = 22.dp.toPx()
    val a = Offset(from.x - throat, throatY)
    val b = Offset(from.x + throat, throatY)
    val c = Offset(right - inset, top)
    val d = Offset(left + inset, top)
    val cone = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        lineTo(d.x, d.y)
        close()
    }
    drawPath(
        cone,
        brush = Brush.verticalGradient(
            0f to accent.copy(alpha = 0.30f * strength),
            0.55f to accent.copy(alpha = 0.10f * strength),
            1f to accent.copy(alpha = 0.18f * strength),
            startY = throatY,
            endY = top
        )
    )
    val edge = accent.copy(alpha = 0.55f * strength)
    drawLine(edge, a, d, 1.dp.toPx())
    drawLine(edge, b, c, 1.dp.toPx())
    // A few rays inside the cone, so it reads as light rather than a shape.
    for (i in 1..4) {
        val t = i / 5f
        val top2 = Offset(d.x + (c.x - d.x) * t, top)
        val bottom2 = Offset(a.x + (b.x - a.x) * t, throatY)
        drawLine(accent.copy(alpha = 0.10f * strength), bottom2, top2, 1f)
    }
    if (pulse in 0f..0.999f) {
        val y = throatY + (top - throatY) * pulse
        val t = (y - throatY) / (top - throatY)
        val halfW = throat + ((c.x - d.x) / 2f - throat) * t
        drawLine(
            bright.copy(alpha = 0.95f * strength * (1f - pulse * 0.4f)),
            Offset(from.x - halfW, y),
            Offset(from.x + halfW, y),
            3.dp.toPx(),
            StrokeCap.Round
        )
        drawCircle(bright.copy(alpha = 0.5f * strength), radius = 6.dp.toPx(), center = Offset(from.x, y))
    }
    // Where the light lands: a lit edge along the panel's top.
    drawLine(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.5f to bright.copy(alpha = 0.8f * strength),
            1f to Color.Transparent,
            startX = d.x,
            endX = c.x
        ),
        start = Offset(d.x, top),
        end = Offset(c.x, top),
        strokeWidth = 2.dp.toPx()
    )
}
