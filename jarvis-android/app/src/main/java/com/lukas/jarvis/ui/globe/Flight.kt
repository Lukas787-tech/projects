package com.lukas.jarvis.ui.globe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.maps.GeoPoint
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.maps.SavedPlace
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.ui.map.MapCanvas
import com.lukas.jarvis.ui.map.rememberMapCamera
import com.lukas.jarvis.ui.map.zoomForWorldWidth
import com.lukas.jarvis.ui.theme.PageBackground
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min

/**
 * The trip between the globe and the map, as one continuous camera move.
 *
 * Switching tabs used to cut from a planet to a street plan. Here the planet
 * turns the place to the front and closes in; at the bottom of its approach a
 * circle of real map opens where the globe was — at the very scale the globe
 * reached, because a sphere of radius r unrolls into a world 2πr wide — and the
 * map keeps zooming as the circle widens to fill the screen. Run backwards, the
 * same frames are the map pulling out into orbit.
 *
 * [progress] is 0 at the globe and 1 at the settled map, whichever way it runs.
 */
@Composable
fun GlobeMapFlight(
    progress: Float,
    map: MapState,
    tiles: TileCache,
    style: MapStyle,
    saved: List<SavedPlace>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(PageBackground)) {
        val density = LocalDensity.current.density
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val side = min(width, height)

        // Where to land: the middle of whatever the map will frame, or you.
        val target = Geo.bounds(map.focusPoints)?.let { (sw, ne) ->
            GeoPoint((sw.lat + ne.lat) / 2, (sw.lon + ne.lon) / 2)
        } ?: map.here ?: FALLBACK

        val approach = (progress / 0.62f).coerceIn(0f, 1f)
        val reveal = ((progress - 0.46f) / 0.54f).coerceIn(0f, 1f)
        val globeRadius = side / 2f * 0.86f * 6.5f
        val fromZoom = zoomForWorldWidth((2 * PI * globeRadius).toFloat(), density)

        Globe(
            mood = GlobeMood.Resting,
            here = map.here,
            marks = map.places.map { it.point },
            focus = target,
            approach = approach,
            modifier = Modifier.fillMaxSize().alpha(1f - reveal * reveal)
        )

        if (reveal > 0f) {
            // The circle starts exactly where the globe is clipped and grows
            // past the corners, so by the end there is no edge left to see.
            val opened = 1f - (1f - reveal) * (1f - reveal)
            val startRadius = side / 2f * 0.94f
            val endRadius = hypot(width, height) / 2f + 2f
            val radius = startRadius + (endRadius - startRadius) * opened
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha((reveal * 2.4f).coerceAtMost(1f))
                    .drawWithContent {
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        val circle = Path().apply {
                            addOval(
                                Rect(
                                    centre.x - radius,
                                    centre.y - radius,
                                    centre.x + radius,
                                    centre.y + radius
                                )
                            )
                        }
                        clipPath(circle) { this@drawWithContent.drawContent() }
                    }
            ) {
                MapCanvas(
                    state = map,
                    tiles = tiles,
                    style = style,
                    camera = rememberMapCamera(map),
                    saved = saved,
                    intro = reveal,
                    introFromZoom = fromZoom,
                    interactive = false
                )
            }
        }
    }
}

private val FALLBACK = GeoPoint(52.520008, 13.404954)
