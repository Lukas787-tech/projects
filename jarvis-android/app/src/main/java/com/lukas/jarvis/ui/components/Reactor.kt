package com.lukas.jarvis.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentBright
import com.lukas.jarvis.ui.theme.AccentDeep
import com.lukas.jarvis.ui.theme.ThemeState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What sits at the centre of the assistant. */
enum class CoreStyle(val id: String, val label: String) {
    Reactor("reactor", "Arc reactor"),
    Orb("orb", "Orb"),
    Globe("globe", "Globe");

    companion object {
        fun of(id: String): CoreStyle = entries.firstOrNull { it.id == id } ?: Reactor
    }
}

/**
 * The assistant's face: an arc reactor drawn as a heads-up display.
 *
 * Every ring means something. The outer scale turns slowly whatever happens,
 * so a still screen is never mistaken for a frozen one. While listening a ring
 * of bars rises and falls with the voice; while working the segmented ring
 * spins up and two sparks run round it; while speaking the core pulses with
 * the words. Resting, it breathes.
 *
 * [aperture] opens the centre — coils and core fade out and leave a hole of
 * about half the diameter — so whatever the answer found, a map say, can be
 * shown inside the rings. [compact] drops the fine detail for small sizes.
 */
@Composable
fun Reactor(
    mood: GlobeMood,
    level: Float,
    modifier: Modifier = Modifier,
    aperture: Float = 0f,
    compact: Boolean = false,
    style: CoreStyle = CoreStyle.Reactor
) {
    val calm = ThemeState.reduceMotion
    val transition = rememberInfiniteTransition(label = "reactor")
    val slowMs = if (calm) 90_000 else 36_000
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(slowMs, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val whirl by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (calm) 6000 else 1600, easing = LinearEasing), RepeatMode.Restart),
        label = "whirl"
    )
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "breath"
    )

    val energyTarget by animateFloatAsState(
        targetValue = when (mood) {
            GlobeMood.Resting -> 0.45f
            GlobeMood.Listening -> 0.9f
            GlobeMood.Working -> 0.72f
            GlobeMood.Speaking -> 1f
        },
        animationSpec = tween(420),
        label = "energy"
    )
    val voice by animateFloatAsState(
        targetValue = if (mood == GlobeMood.Listening || mood == GlobeMood.Speaking) level else 0f,
        animationSpec = tween(110, easing = FastOutSlowInEasing),
        label = "voice"
    )
    val listening by animateFloatAsState(
        targetValue = if (mood == GlobeMood.Listening || mood == GlobeMood.Speaking) 1f else 0f,
        animationSpec = tween(360),
        label = "listening"
    )
    val working by animateFloatAsState(
        targetValue = if (mood == GlobeMood.Working) 1f else 0f,
        animationSpec = tween(360),
        label = "working"
    )

    val accent = Accent
    val bright = AccentBright
    val deep = AccentDeep

    // Powering up: the first time the core appears its light comes up from
    // nothing and the rings arrive from the outside in, the way a display
    // wakes rather than simply being there.
    val boot = remember { Animatable(if (calm) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (boot.value < 1f) boot.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
    }
    val power = boot.value

    Canvas(modifier = modifier.graphicsLayer { alpha = (0.25f + 0.75f * power).coerceIn(0f, 1f) }) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f * (0.86f + 0.14f * power)
        val px = r / 160f
        val open = aperture.coerceIn(0f, 1f)
        val energy = energyTarget * power

        halo(c, r, accent, energy)

        if (style == CoreStyle.Orb) {
            orb(c, r, accent, bright, energy, breath, voice, working, whirl, open)
            return@Canvas
        }

        if (!compact) ticks(c, r * 0.95f, accent, spin * 0.35f, 0.18f + 0.22f * energy, px)
        ring(c, r * 0.885f, accent.copy(alpha = 0.10f + 0.16f * energy), 1.2f * px)

        // The segmented ring: three arcs that spin up while working.
        val segmentAngle = spin * 1.4f + working * whirl * 0.6f
        rotate(segmentAngle, c) {
            val rr = r * 0.80f
            val stroke = Stroke(width = (if (compact) 5f else 3.2f) * px, cap = StrokeCap.Round)
            val arcs = listOf(0f to 78f, 96f to 44f, 158f to 112f, 288f to 50f)
            arcs.forEach { (start, sweep) ->
                drawArc(
                    color = accent.copy(alpha = 0.35f + 0.5f * energy),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(c.x - rr, c.y - rr),
                    size = Size(rr * 2, rr * 2),
                    style = stroke
                )
            }
        }

        // Sparks running round the segmented ring while it works.
        if (working > 0.01f) {
            val rr = r * 0.80f
            listOf(0f, 180f).forEach { offset ->
                val a = Math.toRadians((whirl + offset).toDouble())
                val p = Offset(c.x + rr * cos(a).toFloat(), c.y + rr * sin(a).toFloat())
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = working), bright.copy(alpha = 0.6f * working), Color.Transparent),
                        center = p,
                        radius = 10f * px
                    ),
                    radius = 10f * px,
                    center = p
                )
            }
            // A scanning sweep inside the ring.
            val sr = r * 0.66f
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, accent.copy(alpha = 0.0f), accent.copy(alpha = 0.55f * working), Color.Transparent),
                    center = c
                ),
                startAngle = -whirl * 1.3f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(c.x - sr, c.y - sr),
                size = Size(sr * 2, sr * 2),
                style = Stroke(width = 2f * px)
            )
        }

        if (!compact) {
            // A counter-turning dashed ring.
            rotate(-spin * 2.2f, c) {
                val rr = r * 0.715f
                for (i in 0 until 36) {
                    drawArc(
                        color = accent.copy(alpha = 0.16f + 0.24f * energy),
                        startAngle = i * 10f,
                        sweepAngle = 4.5f,
                        useCenter = false,
                        topLeft = Offset(c.x - rr, c.y - rr),
                        size = Size(rr * 2, rr * 2),
                        style = Stroke(width = 1.6f * px)
                    )
                }
            }
        }

        // The voice ring: bars that stand up with the voice.
        if (listening > 0.01f && !compact) {
            voiceBars(c, r * 0.575f, r * 0.13f, accent, bright, listening, voice, breath, px)
        }

        // What is inside the rings fades when the centre opens.
        val inner = 1f - open
        if (inner > 0.01f) {
            coils(c, r, accent, deep, energy * inner, -spin * 0.8f, compact)
            ring(c, r * 0.335f, bright.copy(alpha = (0.35f + 0.5f * energy) * inner), 2f * px)
            core(c, r, bright, accent, energy * inner, breath, voice, compact)
        } else {
            // A lit rim around whatever the centre shows.
            ring(c, r * 0.53f, bright.copy(alpha = 0.55f * energy), 2.2f * px)
        }
    }
}

// --------------------------------------------------------------------- parts

private fun DrawScope.halo(c: Offset, r: Float, accent: Color, energy: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                accent.copy(alpha = 0.20f * energy),
                accent.copy(alpha = 0.07f * energy),
                Color.Transparent
            ),
            center = c,
            radius = r
        ),
        radius = r,
        center = c
    )
}

private fun DrawScope.ring(c: Offset, radius: Float, color: Color, width: Float) {
    drawCircle(color = color, radius = radius, center = c, style = Stroke(width = width))
}

/** The instrument scale around the edge: a long tick every ten. */
private fun DrawScope.ticks(c: Offset, radius: Float, accent: Color, angle: Float, alpha: Float, px: Float) {
    rotate(angle, c) {
        for (i in 0 until 120) {
            val long = i % 10 == 0
            val a = (i * 3.0 * PI / 180.0)
            val inner = radius - (if (long) 9f else 4f) * px
            val cosA = cos(a).toFloat()
            val sinA = sin(a).toFloat()
            drawLine(
                color = accent.copy(alpha = if (long) alpha * 1.6f else alpha),
                start = Offset(c.x + inner * cosA, c.y + inner * sinA),
                end = Offset(c.x + radius * cosA, c.y + radius * sinA),
                strokeWidth = (if (long) 1.6f else 1f) * px
            )
        }
    }
}

/** Sixty-four bars around the core that rise with the voice. */
private fun DrawScope.voiceBars(
    c: Offset,
    base: Float,
    reach: Float,
    accent: Color,
    bright: Color,
    presence: Float,
    voice: Float,
    breath: Float,
    px: Float
) {
    val count = 64
    for (i in 0 until count) {
        val a = i * 2.0 * PI / count
        // A cheap, stable ripple so the bars do not all move as one.
        val ripple = 0.5f + 0.5f * sin(i * 1.7f + breath * 3f) * cos(i * 0.6f - breath * 2f)
        val length = reach * (0.12f + voice.coerceIn(0f, 1f) * (0.35f + 0.65f * ripple)) * presence
        val cosA = cos(a).toFloat()
        val sinA = sin(a).toFloat()
        drawLine(
            brush = Brush.linearGradient(
                listOf(accent.copy(alpha = 0.35f * presence), bright.copy(alpha = 0.95f * presence)),
                start = Offset(c.x + base * cosA, c.y + base * sinA),
                end = Offset(c.x + (base + length) * cosA, c.y + (base + length) * sinA)
            ),
            start = Offset(c.x + base * cosA, c.y + base * sinA),
            end = Offset(c.x + (base + length) * cosA, c.y + (base + length) * sinA),
            strokeWidth = 2.2f * px,
            cap = StrokeCap.Round
        )
    }
}

/** The ten coils of the reactor, each a lit trapezoid of the ring. */
private fun DrawScope.coils(
    c: Offset,
    r: Float,
    accent: Color,
    deep: Color,
    energy: Float,
    angle: Float,
    compact: Boolean
) {
    val outer = r * (if (compact) 0.60f else 0.505f)
    val inner = r * 0.37f
    val count = 10
    val gap = 7f
    val sweep = 360f / count - gap
    rotate(angle, c) {
        for (i in 0 until count) {
            val start = i * 360f / count
            val path = Path().apply {
                val a0 = Math.toRadians(start.toDouble())
                val a1 = Math.toRadians((start + sweep).toDouble())
                moveTo(c.x + inner * cos(a0).toFloat(), c.y + inner * sin(a0).toFloat())
                lineTo(c.x + outer * cos(a0).toFloat(), c.y + outer * sin(a0).toFloat())
                arcTo(
                    rect = androidx.compose.ui.geometry.Rect(c, outer),
                    startAngleDegrees = start,
                    sweepAngleDegrees = sweep,
                    forceMoveTo = false
                )
                lineTo(c.x + inner * cos(a1).toFloat(), c.y + inner * sin(a1).toFloat())
                arcTo(
                    rect = androidx.compose.ui.geometry.Rect(c, inner),
                    startAngleDegrees = start + sweep,
                    sweepAngleDegrees = -sweep,
                    forceMoveTo = false
                )
                close()
            }
            drawPath(
                path = path,
                brush = Brush.radialGradient(
                    listOf(
                        accent.copy(alpha = 0.55f * energy + 0.1f),
                        deep.copy(alpha = 0.35f * energy + 0.05f)
                    ),
                    center = c,
                    radius = outer
                )
            )
            drawPath(
                path = path,
                color = accent.copy(alpha = 0.35f + 0.45f * energy),
                style = Stroke(width = 1.1f)
            )
        }
    }
}

/** The white-hot centre, which breathes at rest and swells with speech. */
private fun DrawScope.core(
    c: Offset,
    r: Float,
    bright: Color,
    accent: Color,
    energy: Float,
    breath: Float,
    voice: Float,
    compact: Boolean
) {
    val swell = 1f + 0.05f * sin(breath) + 0.28f * voice
    val coreR = r * (if (compact) 0.30f else 0.27f) * swell
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                Color.White.copy(alpha = 0.55f + 0.45f * energy),
                bright.copy(alpha = 0.85f * energy + 0.1f),
                accent.copy(alpha = 0.55f * energy),
                Color.Transparent
            ),
            center = c,
            radius = coreR * 1.55f
        ),
        radius = coreR * 1.55f,
        center = c
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.35f + 0.5f * energy),
        radius = coreR * 0.42f,
        center = c
    )
}

/** The plain style: a glowing pearl with one ring, for anyone who wants it quieter. */
private fun DrawScope.orb(
    c: Offset,
    r: Float,
    accent: Color,
    bright: Color,
    energy: Float,
    breath: Float,
    voice: Float,
    working: Float,
    whirl: Float,
    open: Float
) {
    ring(c, r * 0.72f, accent.copy(alpha = 0.2f + 0.3f * energy), 1.4f)
    if (working > 0.01f) {
        val rr = r * 0.72f
        drawArc(
            brush = Brush.sweepGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.9f * working), Color.Transparent),
                center = c
            ),
            startAngle = whirl,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(c.x - rr, c.y - rr),
            size = Size(rr * 2, rr * 2),
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )
    }
    val inner = 1f - open
    if (inner <= 0.01f) return
    val coreR = r * 0.42f * (1f + 0.05f * sin(breath) + 0.22f * voice)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                Color.White.copy(alpha = (0.6f + 0.4f * energy) * inner),
                bright.copy(alpha = 0.9f * inner),
                accent.copy(alpha = 0.7f * inner),
                accent.copy(alpha = 0.05f)
            ),
            center = Offset(c.x - coreR * 0.25f, c.y - coreR * 0.3f),
            radius = coreR * 1.6f
        ),
        radius = coreR,
        center = c
    )
}
