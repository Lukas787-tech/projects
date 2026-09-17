package com.lukas.jarvis.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

/** Raises a translucent white towards opaque without changing its hue. */
private fun androidx.compose.ui.graphics.Color.lighten(amount: Float) =
    if (amount <= 0f) this else copy(alpha = (alpha + amount * alpha).coerceAtMost(1f))
