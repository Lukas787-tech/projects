package com.lukas.jarvis.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one frosted surface every panel, card, field and bar is cut from.
 *
 * Android cannot blur what is behind a view cheaply on every device it runs on,
 * so the material is built the way glass actually looks rather than by blurring:
 * a film of light that is denser at the top than the bottom, and an edge that is
 * lit along its top and dark along its bottom. Both carry a trace of the accent,
 * so a panel reads as lit by the reactor rather than as a grey rectangle.
 */
fun Modifier.glass(
    shape: Shape,
    /** A touch brighter, for the surface that is currently in hand. */
    raised: Boolean = false
): Modifier {
    val lift = if (raised) 0.55f else 0f
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    GlassTop.lighten(lift),
                    GlassMid.lighten(lift),
                    GlassBottom.lighten(lift)
                )
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(GlassEdgeBright.lighten(lift), GlassEdgeDim)
            ),
            shape = shape
        )
}

/** A card of glass at the card radius. */
fun Modifier.glassCard(
    radius: Dp = Corner.card,
    raised: Boolean = false
): Modifier = this.glass(RoundedCornerShape(radius), raised)

/**
 * A pane lit from one corner rather than evenly, for the few surfaces that are
 * the subject of the screen — the card behind a spoken answer, the hero card.
 */
fun Modifier.sheen(shape: Shape): Modifier = this
    .clip(shape)
    .background(
        Brush.linearGradient(
            colors = listOf(
                Accent.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.06f),
                Color.White.copy(alpha = 0.02f)
            ),
            start = Offset.Zero,
            end = Offset(900f, 700f)
        )
    )
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(Accent.copy(alpha = 0.45f), Color.White.copy(alpha = 0.05f)),
            start = Offset.Zero,
            end = Offset(600f, 600f)
        ),
        shape = shape
    )

/**
 * A soft bloom of the accent behind whatever this is applied to, the way a
 * lit display bleeds a little light into the dark around it.
 */
fun Modifier.glow(color: Color = Accent, strength: Float = 1f): Modifier = drawBehind {
    val radius = size.maxDimension * 0.75f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = 0.28f * strength),
                color.copy(alpha = 0.08f * strength),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/**
 * Corner brackets, the mark of a heads-up display: four short L-shapes that
 * frame a panel without boxing it in.
 */
fun Modifier.hudFrame(
    color: Color = Accent,
    arm: Dp = 14.dp,
    inset: Dp = 0.dp,
    alpha: Float = 0.7f
): Modifier = drawWithContent {
    drawContent()
    val a = arm.toPx()
    val i = inset.toPx()
    val w = size.width
    val h = size.height
    val stroke = 1.5.dp.toPx()
    val c = color.copy(alpha = alpha)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(c, Offset(x1, y1), Offset(x2, y2), strokeWidth = stroke, cap = StrokeCap.Round)
    line(i, i, i + a, i); line(i, i, i, i + a)
    line(w - i, i, w - i - a, i); line(w - i, i, w - i, i + a)
    line(i, h - i, i + a, h - i); line(i, h - i, i, h - i - a)
    line(w - i, h - i, w - i - a, h - i); line(w - i, h - i, w - i, h - i - a)
}

/**
 * The field behind the whole display: a faint grid of crosshair marks, the
 * reference lines a heads-up display is measured against, darkening towards
 * the edges. Built once per size and colour, then drawn as one path.
 */
fun Modifier.hudBackdrop(): Modifier = drawWithCache {
    val step = 44.dp.toPx()
    val arm = 3.dp.toPx()
    val marks = Path().apply {
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                moveTo(x - arm, y)
                lineTo(x + arm, y)
                moveTo(x, y - arm)
                lineTo(x, y + arm)
                x += step
            }
            y += step
        }
    }
    val tone = Accent.copy(alpha = 0.10f)
    val vignette = Brush.radialGradient(
        0.45f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.55f),
        center = Offset(size.width / 2f, size.height * 0.42f),
        radius = size.maxDimension * 0.75f
    )
    val stroke = Stroke(1.dp.toPx())
    onDrawBehind {
        drawPath(marks, tone, style = stroke)
        drawRect(vignette)
    }
}

/** Raises a translucent colour towards opaque without changing its hue. */
private fun Color.lighten(amount: Float) =
    if (amount <= 0f) this else copy(alpha = (alpha + amount * alpha).coerceAtMost(1f))
