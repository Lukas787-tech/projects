package com.lukas.jarvis.ui.map

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.maps.GeoPoint
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.maps.SavedPlace
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Ink
import com.lukas.jarvis.ui.theme.InkRaised
import com.lukas.jarvis.ui.theme.Positive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Where the map is looking, held outside the canvas so the buttons around it
 * — locate me, zoom, the globe's flight — can move it too.
 *
 * Every move is a flight rather than a jump: centre and zoom travel together,
 * and a long way out dips back first, the way a camera would pull up to cross
 * a city instead of sliding across it at street level.
 */
@Stable
class MapCamera(initial: GeoPoint, initialZoom: Float) {
    var center by mutableStateOf(initial)
    var zoom by mutableFloatStateOf(initialZoom)

    /** True while the camera stays on the user's dot as it moves. */
    var following by mutableStateOf(false)

    /** Whether the first framing has happened; before it, a move is a snap. */
    internal var framed = false
    internal var size: IntSize = IntSize.Zero
    internal var density: Float = 1f
    private var flight: Job? = null

    fun stop() {
        flight?.cancel()
        flight = null
    }

    fun flyTo(scope: CoroutineScope, target: GeoPoint, targetZoom: Float, durationMillis: Int = 900) {
        stop()
        val fromCenter = center
        val fromZoom = zoom
        val toZoom = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val dip = dipFor(fromCenter, target, min(fromZoom, toZoom))
        flight = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)
            ) { t, _ ->
                center = GeoPoint(
                    fromCenter.lat + (target.lat - fromCenter.lat) * t,
                    fromCenter.lon + shortLon(fromCenter.lon, target.lon) * t
                )
                zoom = (fromZoom + (toZoom - fromZoom) * t - dip * sin(PI * t).toFloat())
                    .coerceIn(MIN_ZOOM, MAX_ZOOM)
            }
        }
    }

    fun zoomBy(scope: CoroutineScope, delta: Float) =
        flyTo(scope, center, (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM), 320)

    /** How far to pull out mid-flight so both ends are briefly in the same frame. */
    private fun dipFor(from: GeoPoint, to: GeoPoint, lowest: Float): Float {
        if (size.width == 0) return 0f
        val base = TILE * density.toDouble()
        val dx = abs(worldX(from.lon, base) - worldX(to.lon, base))
        val dy = abs(worldY(from.lat, base) - worldY(to.lat, base))
        val span = max(dx, dy)
        if (span <= 0.0) return 0f
        val fits = log2(min(size.width, size.height) * 0.8 / span).toFloat()
        return (lowest - fits).coerceIn(0f, 6f)
    }
}

@Composable
fun rememberMapCamera(state: MapState): MapCamera = remember {
    MapCamera(state.focusPoints.firstOrNull() ?: state.here ?: DEFAULT_CENTER, 15f)
}

/**
 * A slippy map drawn straight onto a Compose canvas: raster tiles, the route
 * over them, a pin per result, the saved places, and you — with the circle
 * your position is probably inside and the way you are facing.
 *
 * Rolling it by hand rather than pulling in a map SDK keeps the app free of a
 * Play Services dependency and an API key; the whole of it is the tile maths
 * below, which is a fixed, well-documented projection, not a moving target.
 */
@Composable
fun MapCanvas(
    state: MapState,
    tiles: TileCache,
    modifier: Modifier = Modifier,
    style: MapStyle = MapStyle.Dark,
    camera: MapCamera = rememberMapCamera(state),
    /** Degrees from north the phone is facing, from the compass. */
    heading: Float? = null,
    saved: List<SavedPlace> = emptyList(),
    /**
     * 0..1 for the arrival from the globe. At 0 the map is as far out as the
     * globe was close in, so the two meet at the same scale; at 1 it has
     * settled. Gestures wait until it has.
     */
    intro: Float = 1f,
    introFromZoom: Float = 5f,
    interactive: Boolean = true,
    onSelectPlace: (Int) -> Unit = {}
) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Ticks whenever a tile lands, which is what redraws the canvas for it.
    var arrivals by remember { mutableIntStateOf(0) }
    val inFlight = remember { HashSet<String>() }

    camera.density = density
    camera.size = size

    // A new answer reframes the map; panning afterwards is left alone until
    // the next one arrives. The first framing snaps; later ones fly.
    LaunchedEffect(state.revision, size) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        val points = state.focusPoints
        if (points.isEmpty()) {
            if (!camera.framed) {
                state.here?.let { camera.center = it; camera.zoom = 15.5f }
                camera.framed = true
            }
            return@LaunchedEffect
        }
        val (center, zoom) = fit(points, size, density)
        if (!camera.framed) {
            camera.center = center
            camera.zoom = zoom
            camera.framed = true
        } else {
            camera.following = false
            camera.flyTo(scope, center, zoom, 1100)
        }
    }

    // Following keeps the dot centred as fixes arrive.
    LaunchedEffect(camera.following, state.here) {
        val here = state.here ?: return@LaunchedEffect
        if (camera.following) camera.flyTo(scope, here, max(camera.zoom, 16.5f), 600)
    }

    val eased = intro * intro * (3f - 2f * intro)
    val zoom = if (intro >= 1f) camera.zoom else introFromZoom + (camera.zoom - introFromZoom) * eased
    val center = camera.center
    val visible = remember(center, zoom, size, style, density) {
        visibleTiles(center, zoom, size, style, density)
    }

    LaunchedEffect(visible) {
        visible.forEach { tile ->
            val key = tile.key(style)
            if (tiles.cached(style, tile.z, tile.x, tile.y) != null || key in inFlight) return@forEach
            if (inFlight.size >= MAX_IN_FLIGHT) return@forEach
            inFlight += key
            // Launched outside this effect, so a tile half-downloaded when the
            // view moves on still lands in the cache instead of being thrown away.
            scope.launch {
                tiles.tile(style, tile.z, tile.x, tile.y)
                inFlight -= key
                arrivals++
            }
        }
    }

    val pulse by rememberInfiniteTransition(label = "here").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )

    val latestState by rememberUpdatedState(state)
    val latestSelect by rememberUpdatedState(onSelectPlace)
    val gesturesOn = interactive && intro >= 1f

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(gesturesOn) {
                if (!gesturesOn) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { at ->
                        camera.following = false
                        val target = pointAt(camera, at, camera.size, density)
                        val halfway = GeoPoint(
                            (camera.center.lat + target.lat) / 2,
                            (camera.center.lon + target.lon) / 2
                        )
                        camera.flyTo(scope, halfway, camera.zoom + 1f, 320)
                    },
                    onTap = { tap ->
                        val world = worldSize(camera.zoom, density)
                        val origin = topLeft(camera.center, world, camera.size)
                        val hit = latestState.places.withIndex().minByOrNull { (_, place) ->
                            (screenOf(place.point, world, origin) - tap).getDistance()
                        }
                        if (hit != null &&
                            (screenOf(hit.value.point, world, origin) - tap).getDistance() < TAP_SLOP * density
                        ) {
                            latestSelect(hit.index)
                        }
                    }
                )
            }
            .pointerInput(gesturesOn) {
                if (!gesturesOn) return@pointerInput
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    camera.stop()
                    camera.following = false
                    val oldWorld = worldSize(camera.zoom, density)
                    val origin = topLeft(camera.center, oldWorld, camera.size)
                    // The point under the fingers stays under the fingers.
                    val anchorX = (origin.first + centroid.x) / oldWorld
                    val anchorY = (origin.second + centroid.y) / oldWorld
                    val newZoom = (camera.zoom + log2(gestureZoom)).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val newWorld = worldSize(newZoom, density)
                    val left = anchorX * newWorld - centroid.x - pan.x
                    val top = anchorY * newWorld - centroid.y - pan.y
                    val cx = left + camera.size.width / 2.0
                    val cy = (top + camera.size.height / 2.0).coerceIn(0.0, newWorld)
                    camera.zoom = newZoom
                    camera.center = GeoPoint(latAt(cy, newWorld), wrapLon(lonAt(cx, newWorld)))
                }
            }
    ) {
        // Read so a landed tile invalidates this frame.
        if (arrivals < 0) return@Canvas
        drawRect(color = if (style == MapStyle.Light) LIGHT_PAPER else InkRaised)

        val world = worldSize(zoom, density)
        val origin = topLeft(center, world, size)

        visible.forEach { tile -> drawTile(tiles, style, tile, origin) }

        fun project(point: GeoPoint) = screenOf(point, world, origin)

        val dark = style != MapStyle.Light
        state.route?.points?.takeIf { it.size > 1 }?.let { points ->
            val path = Path()
            points.forEachIndexed { index, point ->
                val screen = project(point)
                if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
            }
            val line = if (dark) Accent else ROUTE_ON_LIGHT
            val casing = if (dark) Ink.copy(alpha = 0.75f) else Color.White
            drawPath(path, color = line.copy(alpha = 0.18f), style = stroke(18f * density / 2.6f))
            drawPath(path, color = casing, style = stroke(11f * density / 2.6f))
            drawPath(path, color = line, style = stroke(6.5f * density / 2.6f))
        }

        saved.forEach { place -> drawSaved(place, project(place.point), density, measurer, zoom, dark) }

        state.here?.let { here ->
            val screen = project(here)
            val metersPerPixel = cos(Math.toRadians(here.lat)) * EARTH_CIRCUMFERENCE / world
            val accuracy = state.accuracy?.let { (it / metersPerPixel).toFloat() }
            drawHere(screen, accuracy, heading ?: state.bearing, pulse, density)
        }

        state.places.forEachIndexed { index, place ->
            drawPin(
                at = project(place.point),
                number = index + 1,
                selected = index == state.selected,
                label = if (index == state.selected) place.name else null,
                density = density,
                measurer = measurer
            )
        }

        drawScaleBar(center, world, density, measurer, dark)
    }
}

// ---------------------------------------------------------------- drawing

private fun DrawScope.drawTile(tiles: TileCache, style: MapStyle, tile: VisibleTile, origin: Pair<Double, Double>) {
    val px = tile.screenSize
    val left = (tile.column * px - origin.first).roundToInt()
    val top = (tile.row * px - origin.second).roundToInt()
    // One pixel of overlap, otherwise rounding leaves hairline seams.
    val dst = IntSize(px.roundToInt() + 1, px.roundToInt() + 1)

    tiles.cached(style, tile.z, tile.x, tile.y)?.let { image ->
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left, top),
            dstSize = dst,
            filterQuality = FilterQuality.Low
        )
        return
    }

    // Not here yet: stretch the nearest ancestor that is, so zooming in shows
    // a soft version of the right place instead of black squares.
    for (up in 1..4) {
        val z = tile.z - up
        if (z < 0) break
        val parent = tiles.cached(style, z, tile.x shr up, tile.y shr up) ?: continue
        val span = parent.width shr up
        if (span < 1) break
        val mask = (1 shl up) - 1
        drawImage(
            image = parent,
            srcOffset = IntOffset((tile.x and mask) * span, (tile.y and mask) * span),
            srcSize = IntSize(span, span),
            dstOffset = IntOffset(left, top),
            dstSize = dst,
            filterQuality = FilterQuality.Low
        )
        return
    }
}

private fun stroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** You: the likely-inside circle, the way you face, and a dot that breathes. */
private fun DrawScope.drawHere(at: Offset, accuracy: Float?, heading: Float?, pulse: Float, density: Float) {
    val unit = density
    accuracy?.takeIf { it > 8f * unit }?.let { radius ->
        drawCircle(Accent.copy(alpha = 0.10f), radius = radius, center = at)
        drawCircle(Accent.copy(alpha = 0.35f), radius = radius, center = at, style = Stroke(width = unit))
    }
    heading?.let { degrees ->
        val length = 46f * unit
        val spread = 32.0
        val rad = Math.toRadians(degrees.toDouble() - 90.0)
        val a = Math.toRadians(degrees - spread - 90.0)
        val b = Math.toRadians(degrees + spread - 90.0)
        val cone = Path().apply {
            moveTo(at.x, at.y)
            lineTo(at.x + (cos(a) * length).toFloat(), at.y + (sin(a) * length).toFloat())
            lineTo(at.x + (cos(b) * length).toFloat(), at.y + (sin(b) * length).toFloat())
            close()
        }
        drawPath(
            cone,
            brush = Brush.radialGradient(
                colors = listOf(Accent.copy(alpha = 0.55f), Color.Transparent),
                center = at,
                radius = length
            )
        )
        // A thin tick straight ahead, so the cone has a direction you can read.
        drawLine(
            Accent.copy(alpha = 0.7f),
            start = at,
            end = Offset(at.x + (cos(rad) * length * 0.55).toFloat(), at.y + (sin(rad) * length * 0.55).toFloat()),
            strokeWidth = 1.2f * unit,
            cap = StrokeCap.Round
        )
    }
    drawCircle(
        Accent.copy(alpha = (1f - pulse) * 0.35f),
        radius = (9f + pulse * 18f) * unit,
        center = at
    )
    drawCircle(Ink.copy(alpha = 0.55f), radius = 10f * unit, center = at)
    drawCircle(Color.White, radius = 8f * unit, center = at)
    drawCircle(Ink, radius = 5.5f * unit, center = at)
    drawCircle(Accent, radius = 4.5f * unit, center = at)
}

/** A numbered pin: a head, a point, and the name when it is the chosen one. */
private fun DrawScope.drawPin(
    at: Offset,
    number: Int,
    selected: Boolean,
    label: String?,
    density: Float,
    measurer: TextMeasurer
) {
    val radius = (if (selected) 14f else 11f) * density
    val head = Offset(at.x, at.y - radius * 1.55f)
    val pin = Path().apply {
        moveTo(at.x, at.y)
        lineTo(head.x - radius * 0.62f, head.y + radius * 0.72f)
        lineTo(head.x + radius * 0.62f, head.y + radius * 0.72f)
        close()
    }
    val fill = if (selected) Positive else Accent
    drawCircle(Ink.copy(alpha = 0.35f), radius = radius * 0.45f, center = Offset(at.x, at.y + 1.5f * density))
    drawPath(pin, fill)
    drawCircle(Ink.copy(alpha = 0.85f), radius = radius + 2f * density, center = head)
    drawCircle(fill, radius = radius, center = head)
    val text = measurer.measure(
        text = "$number",
        style = TextStyle(color = Ink, fontSize = if (selected) 13.sp else 11.sp, fontWeight = FontWeight.Bold)
    )
    drawText(text, topLeft = Offset(head.x - text.size.width / 2f, head.y - text.size.height / 2f))

    if (label != null) {
        val name = measurer.measure(
            text = label.take(28),
            style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        )
        val padX = 7f * density
        val padY = 3f * density
        val boxLeft = head.x - name.size.width / 2f - padX
        val boxTop = head.y - radius - name.size.height - padY * 2 - 6f * density
        drawRoundRect(
            color = Ink.copy(alpha = 0.82f),
            topLeft = Offset(boxLeft, boxTop),
            size = androidx.compose.ui.geometry.Size(name.size.width + padX * 2, name.size.height + padY * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f * density)
        )
        drawText(name, topLeft = Offset(boxLeft + padX, boxTop + padY))
    }
}

/** Home, work, the car: a diamond with an initial, and the name when close enough to read. */
private fun DrawScope.drawSaved(
    place: SavedPlace,
    at: Offset,
    density: Float,
    measurer: TextMeasurer,
    zoom: Float,
    dark: Boolean
) {
    val r = 10f * density
    val diamond = Path().apply {
        moveTo(at.x, at.y - r)
        lineTo(at.x + r, at.y)
        lineTo(at.x, at.y + r)
        lineTo(at.x - r, at.y)
        close()
    }
    drawPath(diamond, Ink.copy(alpha = 0.9f))
    drawPath(diamond, Caution, style = Stroke(width = 1.6f * density))
    val letter = when {
        place.isCar -> "P"
        place.name.equals("home", true) -> "H"
        place.name.equals("work", true) -> "W"
        else -> place.name.take(1).uppercase()
    }
    val text = measurer.measure(
        letter,
        TextStyle(color = Caution, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    )
    drawText(text, topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f))
    if (zoom >= 12.5f) {
        val name = measurer.measure(
            place.name.replaceFirstChar { it.uppercase() },
            TextStyle(
                color = if (dark) Color.White.copy(alpha = 0.85f) else Ink,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
        drawText(name, topLeft = Offset(at.x - name.size.width / 2f, at.y + r + 2f * density))
    }
}

/** A bar of a round length — 50 m, 200 m, 1 km — so distances can be judged at a glance. */
private fun DrawScope.drawScaleBar(
    center: GeoPoint,
    world: Double,
    density: Float,
    measurer: TextMeasurer,
    dark: Boolean
) {
    val metersPerPixel = cos(Math.toRadians(center.lat)) * EARTH_CIRCUMFERENCE / world
    val maxBar = 90f * density
    val maxMeters = metersPerPixel * maxBar
    val nice = NICE_LENGTHS.lastOrNull { it <= maxMeters } ?: return
    val barPx = (nice / metersPerPixel).toFloat()
    val left = 14f * density
    val y = size.height - 16f * density
    val ink = if (dark) Color.White.copy(alpha = 0.8f) else Ink.copy(alpha = 0.8f)
    val shadow = if (dark) Ink.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.8f)
    listOf(shadow to 3.5f, ink to 1.6f).forEach { (colour, width) ->
        drawLine(colour, Offset(left, y), Offset(left + barPx, y), strokeWidth = width * density, cap = StrokeCap.Round)
        drawLine(colour, Offset(left, y - 5f * density), Offset(left, y), strokeWidth = width * density)
        drawLine(colour, Offset(left + barPx, y - 5f * density), Offset(left + barPx, y), strokeWidth = width * density)
    }
    val label = measurer.measure(
        Geo.formatDistance(nice),
        TextStyle(color = ink, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    )
    drawText(label, topLeft = Offset(left + 4f * density, y - label.size.height - 5f * density))
}

// --------------------------------------------------------------- tile geometry

/** One tile on screen: [column]/[row] are unwrapped, [x]/[y] are the server's. */
internal data class VisibleTile(
    val z: Int,
    val x: Int,
    val y: Int,
    val column: Int,
    val row: Int,
    /** Edge length on screen in pixels. */
    val screenSize: Double
) {
    fun key(style: MapStyle): String = "${style.id}_${z}_${x}_$y"
}

/** Map points per tile edge; multiplied by the screen density to get pixels. */
private const val TILE = 256.0
internal const val MIN_ZOOM = 2f
internal const val MAX_ZOOM = 20f
private const val TAP_SLOP = 26f
private const val MAX_IN_FLIGHT = 24
private const val EARTH_CIRCUMFERENCE = 40_075_016.686
private val DEFAULT_CENTER = GeoPoint(52.520008, 13.404954)
private val LIGHT_PAPER = Color(0xFFE9E6E0)
private val ROUTE_ON_LIGHT = Color(0xFF1C1C22)
private val Caution = Color(0xFFFFD60A)
private val NICE_LENGTHS = listOf(
    5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0,
    10_000.0, 20_000.0, 50_000.0, 100_000.0, 200_000.0, 500_000.0, 1_000_000.0
)

private fun worldSize(zoom: Float, density: Float): Double = TILE * density * 2.0.pow(zoom.toDouble())

private fun worldX(lon: Double, world: Double): Double = (lon + 180.0) / 360.0 * world

private fun worldY(lat: Double, world: Double): Double {
    val clamped = lat.coerceIn(-85.05112878, 85.05112878)
    val s = sin(Math.toRadians(clamped))
    return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * world
}

private fun lonAt(x: Double, world: Double): Double = x / world * 360.0 - 180.0

private fun latAt(y: Double, world: Double): Double {
    val n = PI - 2.0 * PI * y / world
    return Math.toDegrees(atan(sinh(n)))
}

private fun wrapLon(lon: Double): Double = ((lon + 540.0) % 360.0) - 180.0

/** The signed longitude step from [from] to [to] the short way round. */
private fun shortLon(from: Double, to: Double): Double {
    var delta = (to - from) % 360.0
    if (delta > 180) delta -= 360
    if (delta < -180) delta += 360
    return delta
}

/** Top-left corner of the viewport, in world pixels. */
private fun topLeft(center: GeoPoint, world: Double, size: IntSize): Pair<Double, Double> =
    worldX(center.lon, world) - size.width / 2.0 to worldY(center.lat, world) - size.height / 2.0

private fun screenOf(point: GeoPoint, world: Double, origin: Pair<Double, Double>): Offset = Offset(
    (worldX(point.lon, world) - origin.first).toFloat(),
    (worldY(point.lat, world) - origin.second).toFloat()
)

private fun pointAt(camera: MapCamera, at: Offset, size: IntSize, density: Float): GeoPoint {
    val world = worldSize(camera.zoom, density)
    val origin = topLeft(camera.center, world, size)
    return GeoPoint(latAt(origin.second + at.y, world), wrapLon(lonAt(origin.first + at.x, world)))
}

private fun visibleTiles(
    center: GeoPoint,
    zoom: Float,
    size: IntSize,
    style: MapStyle,
    density: Float
): List<VisibleTile> {
    if (size.width == 0 || size.height == 0) return emptyList()
    // Past the source's deepest level the deepest tiles are stretched instead.
    val z = floor(zoom).toInt().coerceIn(0, style.maxZoom)
    val world = worldSize(zoom, density)
    val tilePx = world / (1 shl z)
    val (left, top) = topLeft(center, world, size)
    val span = 1 shl z

    val firstColumn = floor(left / tilePx).toInt()
    val lastColumn = floor((left + size.width) / tilePx).toInt()
    val firstRow = floor(top / tilePx).toInt()
    val lastRow = floor((top + size.height) / tilePx).toInt()

    val out = ArrayList<VisibleTile>()
    for (row in firstRow..lastRow) {
        if (row < 0 || row >= span) continue
        for (column in firstColumn..lastColumn) {
            // Longitude wraps; latitude does not.
            val x = ((column % span) + span) % span
            out.add(VisibleTile(z = z, x = x, y = row, column = column, row = row, screenSize = tilePx))
        }
    }
    return out
}

/** Centre and zoom that put everything in [points] comfortably on screen. */
internal fun fit(points: List<GeoPoint>, size: IntSize, density: Float): Pair<GeoPoint, Float> {
    val bounds = Geo.bounds(points) ?: return DEFAULT_CENTER to 15f
    val (southWest, northEast) = bounds
    val center = GeoPoint(
        (southWest.lat + northEast.lat) / 2,
        (southWest.lon + northEast.lon) / 2
    )
    if (points.size == 1) return center to 16f

    val base = TILE * density
    val width = abs(worldX(northEast.lon, base) - worldX(southWest.lon, base))
    val height = abs(worldY(southWest.lat, base) - worldY(northEast.lat, base))
    val zx = if (width > 0) log2(size.width * FIT_FRACTION / width) else 18.0
    val zy = if (height > 0) log2(size.height * FIT_FRACTION / height) else 18.0
    return center to min(zx, zy).toFloat().coerceIn(MIN_ZOOM, 17.5f)
}

/**
 * The map zoom at which the whole world is [worldWidthPx] wide — which is how
 * the globe hands over: a sphere of radius r is a world 2πr around, so the map
 * opens at exactly the scale the globe closed in to.
 */
fun zoomForWorldWidth(worldWidthPx: Float, density: Float): Float =
    log2(worldWidthPx / (TILE * density)).toFloat().coerceIn(MIN_ZOOM, MAX_ZOOM)

/** Leaves room for the pins, which are drawn outside the bounding box. */
private const val FIT_FRACTION = 0.72
