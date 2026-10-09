package com.lukas.jarvis.ui.talk

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.maps.GeoPoint
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.moment.Backdrop
import com.lukas.jarvis.ui.globe.Globe
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.map.MapCanvas
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.CafeMotion

/**
 * What lies behind the canvas. Resting, the globe is the hero: where you are,
 * turning slowly. When a card needs the room it recedes; when the answer is a
 * place, the map leads. Decorative to TalkBack — the cards say what it shows.
 */
@Composable
fun CanvasBackdrop(
    backdrop: Backdrop,
    mood: GlobeMood,
    level: Float,
    map: MapState,
    tiles: TileCache?,
    mapStyle: MapStyle,
    modifier: Modifier = Modifier,
    focus: GeoPoint? = null,
    onGlobeTap: () -> Unit = {}
) {
    val colors = Cafe.colors
    val reduce = Cafe.reduceMotion
    Box(
        modifier
            .fillMaxSize()
            .background(colors.foam)
            .clearAndSetSemantics { }
    ) {
        Crossfade(targetState = backdrop == Backdrop.MapLeads && tiles != null, animationSpec = CafeMotion.fade(reduce, 420), label = "backdrop") { mapLeads ->
            if (mapLeads && tiles != null) {
                Box(Modifier.fillMaxSize()) {
                    MapCanvas(state = map, tiles = tiles, style = mapStyle, modifier = Modifier.fillMaxSize())
                    // A soft foam fade at the bottom, so the cards over it stay readable.
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(320.dp)
                            .background(Brush.verticalGradient(listOf(colors.foam.copy(alpha = 0f), colors.foam.copy(alpha = 0.92f), colors.foam)))
                    )
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val leads = backdrop == Backdrop.GlobeLeads
                    val scale by animateFloatAsState(if (leads) 1f else 0.62f, CafeMotion.gentle(reduce), label = "globe-scale")
                    val fade by animateFloatAsState(
                        when (backdrop) {
                            Backdrop.GlobeLeads -> 1f
                            Backdrop.GlobeRecedes -> 0.55f
                            else -> 0.22f
                        },
                        CafeMotion.gentle(reduce),
                        label = "globe-fade"
                    )
                    val side = minOf(maxWidth, maxHeight * 0.62f) * 0.92f * scale
                    // Leading or receding, the globe sits up top in the middle; behind
                    // cards it slips into the corner, out of the words' way.
                    val corner = !leads && backdrop != Backdrop.GlobeRecedes
                    Globe(
                        mood = mood,
                        level = level,
                        here = map.here,
                        marks = map.places.map { it.point },
                        focus = focus,
                        approach = if (focus != null && backdrop == Backdrop.GlobeLeads) 0.55f else 0f,
                        onTap = onGlobeTap,
                        modifier = Modifier
                            .align(if (corner) Alignment.TopEnd else Alignment.TopCenter)
                            .offset(x = if (corner) side * 0.38f else 0.dp, y = if (leads) maxHeight * 0.09f else if (corner) -side * 0.18f else 8.dp)
                            .size(side)
                            .alpha(fade)
                    )
                }
            }
        }
    }
}
