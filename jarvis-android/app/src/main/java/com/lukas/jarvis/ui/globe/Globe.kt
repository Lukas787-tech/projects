package com.lukas.jarvis.ui.globe

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.lukas.jarvis.maps.GeoPoint
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentSoft
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** What the globe is doing, which is also what the assistant is doing. */
enum class GlobeMood { Resting, Listening, Working, Speaking }

/**
 * A wireframe Earth, drawn in one canvas pass.
 *
 * The projection is orthographic — the view from infinitely far away, which is
 * the one that actually looks like a planet rather than like a map bent into a
 * ball. A point is visible when the angle between it and the point facing the
 * camera is under ninety degrees, so the far side of the world is simply not
 * drawn, and the sphere reads as solid without anything being filled in.
 *
 * It turns by itself, and when an answer has a place in it the globe turns that
 * place to the front and closes in on it. That flight is the handover to the
 * map: by the time it finishes, the camera is over the right coordinates and
 * real tiles can take the frame.
 */
@Composable
fun Globe(
    mood: GlobeMood,
    modifier: Modifier = Modifier,
    /** Voice loudness, 0..1, which the globe swells with while listening. */
    level: Float = 0f,
    /** Where the user is, marked with a pulsing ring. */
    here: GeoPoint? = null,
    /** Anything the last answer turned up, marked with a dot. */
    marks: List<GeoPoint> = emptyList(),
    /** Fly here and close in. Null lets it drift. */
    focus: GeoPoint? = null,
    /** 0 = whole planet, 1 = fully closed in on [focus]. */
    approach: Float = 0f,
    /** A tap anywhere on the planet, which is how you start talking. */
    onTap: () -> Unit = {}
) {
    val context = LocalContext.current
    val world = remember(context) { World.load(context) }

    // Spin is integrated per frame rather than animated between values, so a
    // change of speed picks up from wherever the globe currently is instead of
    // snapping back to the start of an animation.
    var spin by remember { mutableFloatStateOf(0f) }
    var tilt by remember { mutableFloatStateOf(TILT) }

    // A dragged globe has to keep the position it was left in, so the drag
    // writes straight into the same spin the idle drift integrates, and the
    // drift is paused while a finger is down. Letting go hands the throw over
    // as a velocity that decays, which is why a flick keeps turning.
    var dragging by remember { mutableStateOf(false) }
    var fling by remember { mutableFloatStateOf(0f) }

    val speed = when (mood) {
        GlobeMood.Resting -> 3.5f
        GlobeMood.Listening -> 6f
        GlobeMood.Working -> 26f
        GlobeMood.Speaking -> 8f
    }
    val turning = approach < 0.02f
    LaunchedEffect(turning, speed) {
        if (!turning) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                if (seconds <= 0f) return@withFrameNanos
                if (!dragging) {
                    spin = (spin + (speed + fling) * seconds) % 360f
                    // Roughly halves the throw every quarter second, which stops
                    // a flick well before it becomes a fairground ride.
                    fling *= FLING_DECAY.pow(seconds)
                    if (abs(fling) < 1f) fling = 0f
                }
            }
        }
    }

    val sweep by rememberInfiniteTransition(label = "globe").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    val glow by animateFloatAsState(
        targetValue = when (mood) {
            GlobeMood.Resting -> 0.34f
            GlobeMood.Listening -> 0.72f
            GlobeMood.Working -> 0.58f
            GlobeMood.Speaking -> 1f
        },
        animationSpec = tween(420),
        label = "globe-glow"
    )

    val swell by animateFloatAsState(
        targetValue = if (mood == GlobeMood.Listening) level else 0f,
        animationSpec = tween(120),
        label = "globe-swell"
    )

    // Tap and drag share one pointer handler. Wrapping the globe in a clickable
    // instead would have the click consume the gesture before a drag could
    // start, so a spin would only ever register as a tap.
    val gestures = Modifier.pointerInput(turning) {
        // While the camera is flying to a place, it is not the user's to turn.
        // A drag cut off by an answer arriving is not always reported as
        // cancelled; a globe left thinking it is held would never drift again.
        dragging = false
        if (!turning) return@pointerInput
        coroutineScope {
            launch { detectTapGestures { onTap() } }
            launch {
                var lastMoveNanos = 0L
                try {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        fling = 0f
                        lastMoveNanos = 0L
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false; fling = 0f }
                ) { change, delta ->
                    change.consume()
                    val degrees = delta.x * DEGREES_PER_PIXEL
                    spin = (spin - degrees) % 360f
                    // Clamped short of the poles: at ninety degrees the globe is
                    // seen down its own axis and the graticule collapses.
                    tilt = (tilt + delta.y * DEGREES_PER_PIXEL).coerceIn(-78f, 78f)

                    val now = change.uptimeMillis * 1_000_000L
                    if (lastMoveNanos != 0L && now > lastMoveNanos) {
                        val seconds = (now - lastMoveNanos) / 1_000_000_000f
                        // Smoothed, so one jittery sample cannot become the throw.
                        fling = fling * 0.6f + (-degrees / seconds) * 0.4f
                    }
                    lastMoveNanos = now
                }
                } finally {
                    dragging = false
                }
            }
        }
    }

    Canvas(modifier = modifier.then(gestures)) {
        // Everything that changes per frame is read here rather than in the
        // composable body, so a turning globe redraws without recomposing the
        // screen around it.
        //
        // Once flying, the camera is the destination; before that, it drifts.
        val eased = approach * approach * (3f - 2f * approach)
        val cameraLon = focus?.lon?.toFloat()?.let { lerpAngle(spin, it, eased) } ?: spin
        val cameraLat = lerp(tilt, focus?.lat?.toFloat() ?: tilt, eased)
        val zoom = 1f + eased * 5.5f

        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f * (0.86f + swell * 0.06f) * zoom
        val camera = Camera(cameraLon, cameraLat, centre, radius)

        // Everything past the sphere's edge is clipped away, so closing in
        // crops rather than overflowing the frame.
        clipRound(centre, size.minDimension / 2f * 0.94f) {
            drawAtmosphere(centre, radius, glow, zoom)
            drawGraticule(camera, glow)
            drawCoastlines(world, camera, glow)
            marks.forEach { drawMark(camera, it, Accent, glow) }
            here?.let { drawHere(camera, it, glow, sweep) }
            if (mood == GlobeMood.Working && approach < 0.5f) {
                drawScanArc(centre, size.minDimension / 2f * 0.9f, sweep, glow)
            }
        }

        // The limb is drawn outside the clip so it stays a crisp full circle.
        if (zoom < 1.4f) {
            drawCircle(
                color = Accent.copy(alpha = 0.30f + 0.35f * glow),
                radius = radius,
                center = centre,
                style = Stroke(width = 1.4f)
            )
        }
    }
}

// ------------------------------------------------------------------ projection

/** Orthographic camera: which way the planet is facing, and how big it is. */
private class Camera(
    lonDegrees: Float,
    latDegrees: Float,
    val centre: Offset,
    val radius: Float
) {
    private val lon0 = lonDegrees.toRadians()
    private val sinLat0 = sin(latDegrees.toRadians())
    private val cosLat0 = cos(latDegrees.toRadians())

    /** Screen position, or null when the point is round the back. */
    fun project(lonDegrees: Float, latDegrees: Float): Offset? {
        val lon = lonDegrees.toRadians() - lon0
        val lat = latDegrees.toRadians()
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        // The cosine of the angular distance from the point facing the camera.
        // Negative means the far hemisphere, which is hidden by the planet.
        val facing = sinLat0 * sinLat + cosLat0 * cosLat * cos(lon)
        if (facing <= 0f) return null
        return Offset(
            centre.x + radius * (cosLat * sin(lon)),
            centre.y - radius * (cosLat0 * sinLat - sinLat0 * cosLat * cos(lon))
        )
    }
}

private fun DrawScope.drawAtmosphere(centre: Offset, radius: Float, glow: Float, zoom: Float) {
    if (zoom > 2f) return
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.05f * glow),
                Accent.copy(alpha = 0.03f * glow),
                Color.Transparent
            ),
            center = centre,
            radius = radius * 1.25f
        ),
        radius = radius * 1.25f,
        center = centre
    )
}

/** Meridians and parallels: the wireframe that makes it read as a sphere. */
private fun DrawScope.drawGraticule(camera: Camera, glow: Float) {
    val colour = AccentSoft.copy(alpha = 0.12f + 0.16f * glow)
    var meridian = -180
    while (meridian < 180) {
        strokeArcOf(camera, colour) { step ->
            val lat = -90f + step * 180f
            meridian.toFloat() to lat
        }
        meridian += 30
    }
    var parallel = -60
    while (parallel <= 60) {
        val lat = parallel.toFloat()
        // The equator is the one line that says which way up the planet is.
        val weight = if (parallel == 0) 0.26f + 0.3f * glow else 0.12f + 0.16f * glow
        strokeArcOf(camera, AccentSoft.copy(alpha = weight)) { step ->
            (-180f + step * 360f) to lat
        }
        parallel += 30
    }
}

/**
 * Walks a line of the graticule, breaking the path wherever it passes behind
 * the planet so the hidden half is not joined back up across the face.
 */
private inline fun DrawScope.strokeArcOf(
    camera: Camera,
    colour: Color,
    steps: Int = 48,
    point: (Float) -> Pair<Float, Float>
) {
    val path = Path()
    var drawing = false
    for (index in 0..steps) {
        val (lon, lat) = point(index / steps.toFloat())
        val screen = camera.project(lon, lat)
        if (screen == null) {
            drawing = false
        } else if (!drawing) {
            path.moveTo(screen.x, screen.y)
            drawing = true
        } else {
            path.lineTo(screen.x, screen.y)
        }
    }
    drawPath(path, colour, style = Stroke(width = 1f, cap = StrokeCap.Round))
}

private fun DrawScope.drawCoastlines(world: World, camera: Camera, glow: Float) {
    val colour = Accent.copy(alpha = 0.45f + 0.45f * glow)
    val pen = Stroke(width = 1.5f, cap = StrokeCap.Round)
    world.strokes.forEach { line ->
        val path = Path()
        var drawing = false
        var previous: Offset? = null
        var index = 0
        while (index < line.size) {
            val screen = camera.project(line[index], line[index + 1])
            if (screen == null) {
                drawing = false
                previous = null
            } else {
                // A coastline that crosses the date line would otherwise be
                // drawn straight across the face of the globe.
                val jumped = previous?.let { (it - screen).getDistance() > camera.radius }
                if (!drawing || jumped == true) {
                    path.moveTo(screen.x, screen.y)
                    drawing = true
                } else {
                    path.lineTo(screen.x, screen.y)
                }
                previous = screen
            }
            index += 2
        }
        drawPath(path, colour, style = pen)
    }
}

private fun DrawScope.drawMark(camera: Camera, point: GeoPoint, colour: Color, glow: Float) {
    val screen = camera.project(point.lon.toFloat(), point.lat.toFloat()) ?: return
    drawCircle(colour.copy(alpha = 0.20f * glow + 0.15f), radius = 9f, center = screen)
    drawCircle(colour, radius = 3.2f, center = screen)
}

/** The user's own position: a dot inside a ring that keeps expanding. */
private fun DrawScope.drawHere(camera: Camera, point: GeoPoint, glow: Float, sweep: Float) {
    val screen = camera.project(point.lon.toFloat(), point.lat.toFloat()) ?: return
    val phase = (sweep / 360f)
    drawCircle(
        color = Accent.copy(alpha = (1f - phase) * 0.55f * (0.4f + glow)),
        radius = 6f + phase * 20f,
        center = screen,
        style = Stroke(width = 1.2f)
    )
    drawCircle(Accent, radius = 4f, center = screen)
}

/** The sweep that says work is happening, borrowed from a radar screen. */
private fun DrawScope.drawScanArc(centre: Offset, radius: Float, sweep: Float, glow: Float) {
    drawArc(
        brush = Brush.sweepGradient(
            colors = listOf(
                Color.Transparent,
                Accent.copy(alpha = 0.04f * glow),
                Accent.copy(alpha = 0.85f * glow),
                Color.Transparent
            ),
            center = centre
        ),
        startAngle = sweep,
        sweepAngle = 100f,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = 2f)
    )
}

/**
 * Clips to a circle without needing a graphics layer: the round shape is added
 * to a path and used as the clip for the block.
 */
private fun DrawScope.clipRound(centre: Offset, radius: Float, block: DrawScope.() -> Unit) {
    val path = Path().apply {
        addOval(
            Rect(
                left = centre.x - radius,
                top = centre.y - radius,
                right = centre.x + radius,
                bottom = centre.y + radius
            )
        )
    }
    clipPath(path) { block() }
}

// ----------------------------------------------------------------------- maths

private const val TILT = 18f

/** A drag across the whole width turns the planet most of the way round. */
private const val DEGREES_PER_PIXEL = 0.22f

/** Per second: a throw keeps about a twentieth of its speed after one second. */
private const val FLING_DECAY = 0.05f

private fun Float.toRadians(): Float = (this * Math.PI / 180.0).toFloat()

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

/** Interpolates degrees the short way round, so the globe never spins 350°. */
private fun lerpAngle(from: Float, to: Float, t: Float): Float {
    var delta = (to - from) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return from + delta * t
}
