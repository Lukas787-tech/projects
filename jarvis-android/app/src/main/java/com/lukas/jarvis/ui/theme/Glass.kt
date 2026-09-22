package com.lukas.jarvis.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The one frosted surface every panel, card, field and bar is cut from.
 *
 * Android cannot blur what is behind a view cheaply on every device it runs on,
 * so the material is built the way glass actually looks rather than by blurring:
 * a film of white that is denser at the top than the bottom, and an edge that is
 * lit along its top and dark along its bottom. Together those two gradients
 * carry the impression of a pane catching light from above, and they cost one
 * draw each.
 *
 * Because the film is translucent it takes its colour from whatever it is laid
 * over, so a panel high on the screen sits a shade lighter than one near the
 * bottom without either of them being told to.
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

/**
 * A card of glass: the same material, at the card radius, with the padding a
 * card always wanted.
 *
 * Every screen used to spell this out as `.glass(RoundedCornerShape(18.dp))
 * .padding(16.dp)`, which is three chances per call site to be a couple of
 * pixels off the one next to it. One name, one radius, one inset.
 */
fun Modifier.glassCard(
    radius: androidx.compose.ui.unit.Dp = Corner.card,
    raised: Boolean = false
): Modifier = this.glass(RoundedCornerShape(radius), raised)

/**
 * A pane lit from one corner rather than evenly.
 *
 * Used for the few surfaces that should read as the subject of the screen — the
 * hero card at the top of the dashboard, the card behind a spoken answer. The
 * diagonal is what separates it from the panels underneath: same material,
 * caught at a different angle.
 */
fun Modifier.sheen(shape: Shape): Modifier = this
    .clip(shape)
    .background(
        Brush.linearGradient(
            colors = listOf(
                Color(0x26FFFFFF),
                Color(0x14FFFFFF),
                Color(0x08FFFFFF)
            ),
            start = Offset.Zero,
            end = Offset(900f, 700f)
        )
    )
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(Color(0x3DFFFFFF), Color(0x0FFFFFFF)),
            start = Offset.Zero,
            end = Offset(600f, 600f)
        ),
        shape = shape
    )

/** Raises a translucent white towards opaque without changing its hue. */
private fun Color.lighten(amount: Float) =
    if (amount <= 0f) this else copy(alpha = (alpha + amount * alpha).coerceAtMost(1f))
