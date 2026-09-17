package com.lukas.jarvis.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.lukas.jarvis.maps.GeoPoint
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Ink
import com.lukas.jarvis.ui.theme.InkRaised
import com.lukas.jarvis.ui.theme.Positive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

/**
 * A slippy map drawn straight onto a Compose canvas: OSM raster tiles, the route
 * polyline over them, a numbered pin per result.
 *
 * Rolling it by hand rather than pulling in a map SDK keeps the app free of a
 * Play Services dependency and an API key, and the whole of it is the tile maths
 * below — which is a fixed, well-documented projection, not a moving target.
 */
@Composable
fun MapCanvas(
    state: MapState,
    tiles: TileCache,
    modifier: Modifier = Modifier,
    onSelectPlace: (Int) -> Unit = {}
) {
    var center by remember {
        mutableStateOf(state.focusPoints.firstOrNull() ?: DEFAULT_CENTER)
    }
    var zoom by remember { mutableFloatStateOf(15f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val loaded = remember { mutableStateMapOf<String, ImageBitmap>() }
    val measurer = rememberTextMeasurer()

    // A new answer reframes the map; panning afterwards is left alone until the
    // next one arrives.
    LaunchedEffect(state.revision, size) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        val points = state.focusPoints
        if (points.isEmpty()) return@LaunchedEffect
        val fitted = fit(points, size)
        center = fitted.first
        zoom = fitted.second
    }

    val visible = remember(center, zoom, size) { visibleTiles(center, zoom, size) }

    LaunchedEffect(visible) {
        if (loaded.size > MAX_HELD_TILES) {
            val keep = visible.map { it.key }.toSet()
            loaded.keys.filterNot { it in keep }.forEach { loaded.remove(it) }
        }
        visible.forEach { tile ->
            if (loaded.containsKey(tile.key)) return@forEach
            launch {
                tiles.tile(tile.z, tile.x, tile.y)?.let { loaded[tile.key] = it }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(state.places, center, zoom, size) {
                detectTapGestures { tap ->
                    val world = worldSize(zoom)
                    val origin = topLeft(center, world, size)
                    val hit = state.places.withIndex().minByOrNull { (_, place) ->
                        val point = Offset(
                            (worldX(place.point.lon, world) - origin.first).toFloat(),
                            (worldY(place.point.lat, world) - origin.second).toFloat()
                        )
                        (point - tap).getDistance()
                    }
                    if (hit != null) {
                        val point = Offset(
                            (worldX(hit.value.point.lon, world) - origin.first).toFloat(),
                            (worldY(hit.value.point.lat, world) - origin.second).toFloat()
                        )
                        if ((point - tap).getDistance() < TAP_SLOP) onSelectPlace(hit.index)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val world = worldSize(zoom)
                    val x = worldX(center.lon, world) - pan.x
                    val y = worldY(center.lat, world) - pan.y
                    center = GeoPoint(latAt(y, world), lonAt(x, world))
                    zoom = (zoom + log2(gestureZoom)).coerceIn(MIN_ZOOM, MAX_ZOOM)
                }
            }
    ) {
        drawRect(color = InkRaised)

        val world = worldSize(zoom)
        val origin = topLeft(center, world, size)
        val tilePx = tilePixels(zoom)

        visible.forEach { tile ->
            // Only already-decoded tiles are drawn; the effect above is what
            // fetches the rest, because touching state from a draw pass would
            // invalidate the frame it is in the middle of.
            val image = loaded[tile.key] ?: tiles.cached(tile.z, tile.x, tile.y) ?: return@forEach
            drawImage(
                image = image,
                dstOffset = IntOffset(
                    (tile.column * tilePx - origin.first).roundToInt(),
                    (tile.row * tilePx - origin.second).roundToInt()
                ),
                // One pixel of overlap, otherwise rounding leaves hairline seams
                // between tiles.
                dstSize = IntSize(tilePx.roundToInt() + 1, tilePx.roundToInt() + 1),
                colorFilter = DARK_TILES
            )
        }

        fun project(point: GeoPoint) = Offset(
            (worldX(point.lon, world) - origin.first).toFloat(),
            (worldY(point.lat, world) - origin.second).toFloat()
        )

        state.route?.points?.takeIf { it.size > 1 }?.let { points ->
            val path = Path()
            points.forEachIndexed { index, point ->
                val screen = project(point)
                if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
            }
            // A dark casing under the line keeps it readable over pale streets.
            drawPath(path, color = Ink.copy(alpha = 0.7f), style = routeStroke(11f))
            drawPath(path, color = Accent, style = routeStroke(6f))
        }

        state.here?.let { here ->
            val screen = project(here)
            drawCircle(color = Accent.copy(alpha = 0.18f), radius = 26f, center = screen)
            drawCircle(color = Ink, radius = 11f, center = screen)
            drawCircle(color = Accent, radius = 7f, center = screen)
        }

        state.places.forEachIndexed { index, place ->
            val screen = project(place.point)
            val selected = index == state.selected
            val radius = if (selected) 22f else 17f
            drawCircle(color = Ink.copy(alpha = 0.85f), radius = radius + 3f, center = screen)
            drawCircle(
                color = if (selected) Positive else Accent,
                radius = radius,
                center = screen
            )
            val label = measurer.measure(
                text = "${index + 1}",
                style = TextStyle(
                    // Both marker fills are light, so the number on them is dark.
                    color = Ink,
                    fontSize = if (selected) 14.sp else 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            drawText(
                textLayoutResult = label,
                topLeft = Offset(
                    screen.x - label.size.width / 2f,
                    screen.y - label.size.height / 2f
                )
            )
        }
    }
}

private fun routeStroke(width: Float) = Stroke(
    width = width,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round
)

// --------------------------------------------------------------- tile geometry

/** One tile on screen: [column]/[row] are unwrapped, [x]/[y] are the server's. */
internal data class VisibleTile(
    val z: Int,
    val x: Int,
    val y: Int,
    val column: Int,
    val row: Int
) {
    val key: String get() = "${z}_${x}_$y"
}

private const val TILE_SIZE = 256.0
private const val MIN_ZOOM = 3f
private const val MAX_ZOOM = 19f
private const val TAP_SLOP = 60f
private const val MAX_HELD_TILES = 160
private val DEFAULT_CENTER = GeoPoint(52.520008, 13.404954)

/** Invert plus a 180 degree hue rotation: the standard trick for a dark basemap. */
/**
 * OpenStreetMap's tiles are printed on pale paper, which is a lamp in the middle
 * of a black app. This turns them into graphite and silver to match it.
 *
 * All three output channels are the same row, which is what makes it neutral:
 * the tile's luminance is measured, inverted — so the paper goes dark and the
 * ink goes light — then scaled back to 88% and lifted off pure black, so the
 * landmass reads as graphite rather than as a hole in the screen. The previous
 * matrix inverted per channel, which also rotated the hue and left the map
 * tinted blue.
 */
private val DARK_TILES = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.188f, -0.629f, -0.063f, 0f, 234.4f,
            -0.188f, -0.629f, -0.063f, 0f, 234.4f,
            -0.188f, -0.629f, -0.063f, 0f, 234.4f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

private fun worldSize(zoom: Float): Double = TILE_SIZE * 2.0.pow(zoom.toDouble())

private fun tilePixels(zoom: Float): Double =
    TILE_SIZE * 2.0.pow((zoom - floor(zoom)).toDouble())

private fun worldX(lon: Double, world: Double): Double = (lon + 180.0) / 360.0 * world

private fun worldY(lat: Double, world: Double): Double {
    val clamped = lat.coerceIn(-85.05112878, 85.05112878)
    val s = sin(Math.toRadians(clamped))
    return (0.5 - ln((1 + s) / (1 - s)) / (4 * Math.PI)) * world
}

private fun lonAt(x: Double, world: Double): Double = x / world * 360.0 - 180.0

private fun latAt(y: Double, world: Double): Double {
    val n = Math.PI - 2.0 * Math.PI * y / world
    return Math.toDegrees(atan(sinh(n)))
}

/** Top-left corner of the viewport, in world pixels. */
private fun topLeft(center: GeoPoint, world: Double, size: IntSize): Pair<Double, Double> =
    worldX(center.lon, world) - size.width / 2.0 to worldY(center.lat, world) - size.height / 2.0

private fun visibleTiles(center: GeoPoint, zoom: Float, size: IntSize): List<VisibleTile> {
    if (size.width == 0 || size.height == 0) return emptyList()
    val z = floor(zoom).toInt().coerceIn(0, 19)
    val world = worldSize(zoom)
    val tilePx = tilePixels(zoom)
    val (left, top) = topLeft(center, world, size)
    val span = 1 shl z

    val firstColumn = floor(left / tilePx).toInt()
    val lastColumn = floor((left + size.width) / tilePx).toInt()
    val firstRow = floor(top / tilePx).toInt()
    val lastRow = floor((top + size.height) / tilePx).toInt()

    val out = ArrayList<VisibleTile>((lastColumn - firstColumn + 1) * (lastRow - firstRow + 1))
    for (row in firstRow..lastRow) {
        if (row < 0 || row >= span) continue
        for (column in firstColumn..lastColumn) {
            // Longitude wraps; latitude does not.
            val x = ((column % span) + span) % span
            out.add(VisibleTile(z = z, x = x, y = row, column = column, row = row))
        }
    }
    return out
}

/** Centre and zoom that put everything in [points] comfortably on screen. */
private fun fit(points: List<GeoPoint>, size: IntSize): Pair<GeoPoint, Float> {
    val bounds = com.lukas.jarvis.maps.Geo.bounds(points)
        ?: return DEFAULT_CENTER to 15f
    val (southWest, northEast) = bounds
    val center = GeoPoint(
        (southWest.lat + northEast.lat) / 2,
        (southWest.lon + northEast.lon) / 2
    )
    if (points.size == 1) return center to 16f

    var best = MIN_ZOOM
    for (candidate in 3..18) {
        val world = worldSize(candidate.toFloat())
        val width = abs(worldX(northEast.lon, world) - worldX(southWest.lon, world))
        val height = abs(worldY(southWest.lat, world) - worldY(northEast.lat, world))
        if (width <= size.width * FIT_FRACTION && height <= size.height * FIT_FRACTION) {
            best = candidate.toFloat()
        }
    }
    return center to best
}

/** Leaves room for the pins, which are drawn outside the bounding box. */
private const val FIT_FRACTION = 0.78
