package com.lukas.jarvis.ui.screens

import androidx.compose.animation.AnimatedVisibility
import com.lukas.jarvis.ui.components.rememberStored
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Close
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.maps.Compass
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.maps.Place
import com.lukas.jarvis.maps.GeoPoint
import com.lukas.jarvis.maps.SavedPlace
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.GlassDialog
import com.lukas.jarvis.ui.components.GlassField
import com.lukas.jarvis.ui.map.MapCamera
import com.lukas.jarvis.ui.map.MapCanvas
import com.lukas.jarvis.ui.map.rememberMapCamera
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.DialogPane
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.Ink
import com.lukas.jarvis.ui.theme.Positive
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.glass

/** Glass over a map needs more body than glass over black, or the streets show through the words. */
private val MapGlass = Color(0xCC0C0C0F)

/**
 * The map, edge to edge.
 *
 * It used to be a 260-point window at the top of a list, which is a thumbnail
 * of a map rather than a map. Now the map is the screen, and everything else
 * floats on it: where you are at the top, the camera controls down the side,
 * and what was found in a sheet that folds away when you want to look.
 */
@Composable
fun MapScreen(
    state: MapState,
    tiles: TileCache,
    style: MapStyle,
    saved: List<SavedPlace>,
    hereLabel: String?,
    travelMode: String,
    routing: Boolean,
    onSelect: (Int) -> Unit,
    onRoute: (Int) -> Unit,
    onNavigate: (Int) -> Unit,
    onModeChange: (String) -> Unit,
    onClear: () -> Unit,
    onStyleChange: (MapStyle) -> Unit,
    onFollow: (Boolean) -> Unit,
    onSaveHere: (String) -> Unit,
    onRouteSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** A finger held on the map: drop a pin there. */
    onDropPin: (GeoPoint) -> Unit = {},
    /** Keep the selected or dropped pin under a name. */
    onSaveSelected: (String) -> Unit = {},
    onRenameSaved: (String, String) -> Unit = { _, _ -> },
    onForgetSaved: (String) -> Unit = {},
    /** A short line to show on the map for a moment, then clear. */
    message: String? = null,
    onMessageShown: () -> Unit = {},
    /** 0..1 while arriving from the globe; the map zooms in from orbit as it rises. */
    intro: Float = 1f,
    introFromZoom: Float = 5f
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val camera: MapCamera = rememberMapCamera(state)

    // The dot is live only while this screen is: GPS and compass both stop
    // the moment the map goes away.
    DisposableEffect(Unit) {
        onFollow(true)
        onDispose { onFollow(false) }
    }
    val heading by produceState<Float?>(null) {
        Compass.headings(context).collect { value = it }
    }

    var sheetOpen by remember { mutableStateOf(true) }
    var stylesOpen by remember { mutableStateOf(false) }
    // A saved place held or tapped: rename it, route to it, or forget it.
    var managing by remember { mutableStateOf<SavedPlace?>(null) }
    // A name being typed for the selected pin.
    var naming by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3500)
            onMessageShown()
        }
    }

    // Everything drawn over the map can be put away with one tap on the map
    // itself, and brought back the same way. A map covered by a card, a row of
    // chips, four buttons and a sheet is a map you mostly cannot see.
    var controls by rememberSaveable { mutableStateOf(true) }
    var hintRead by rememberStored("map.hint.read", false)

    // A new answer is something to look at, so it brings the controls back
    // rather than arriving behind them.
    LaunchedEffect(state.revision) {
        if (state.places.isNotEmpty() || state.route != null) {
            controls = true
            sheetOpen = true
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        MapCanvas(
            state = state,
            tiles = tiles,
            style = style,
            camera = camera,
            heading = heading,
            saved = saved,
            intro = intro,
            introFromZoom = introFromZoom,
            onSelectPlace = { index ->
                onSelect(index)
                controls = true
                sheetOpen = true
            },
            onTapEmpty = {
                stylesOpen = false
                controls = !controls
            },
            onLongPress = { point ->
                onDropPin(point)
                controls = true
                sheetOpen = true
            },
            onTapSaved = { place -> managing = place }
        )

        // ---------------------------------------------------------- top
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.snug, vertical = Space.snug)
        ) {
            WhereCard(
                title = state.title.ifBlank { "Map" },
                here = hereLabel,
                accuracy = state.accuracy,
                canClear = state.places.isNotEmpty() || state.route != null,
                onClear = onClear
            )
            AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier
                        .padding(top = Space.tight)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.medium))
                        .background(MapGlass)
                        .glass(RoundedCornerShape(Corner.medium))
                        .padding(horizontal = Space.snug, vertical = Space.tight)
                )
            }
            Spacer(Modifier.height(Space.tight))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.tight)
            ) {
                FloatingChip(Icons.Default.LocalParking, "Park here") { onSaveHere("car") }
                saved.sortedBy { savedOrder(it) }.forEach { place ->
                    FloatingChip(
                        icon = savedIcon(place),
                        label = if (place.isCar) "My car" else place.name.replaceFirstChar { it.uppercase() },
                        onClick = { onRouteSaved(place.name) },
                        onLongClick = { managing = place }
                    )
                }
                if (saved.none { it.name.equals("home", true) }) {
                    FloatingChip(Icons.Default.Home, "Set home") { onSaveHere("home") }
                }
            }
        }
        }

        // ---------------------------------------------------------- side
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
        Column(
            modifier = Modifier
                .padding(end = Space.snug),
            verticalArrangement = Arrangement.spacedBy(Space.tight),
            horizontalAlignment = Alignment.End
        ) {
            AnimatedVisibility(visible = stylesOpen, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Corner.medium))
                        .background(MapGlass)
                        .padding(Space.hair),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    MapStyle.entries.forEach { option ->
                        Text(
                            text = option.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (option == style) Ink else TextPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Corner.small))
                                .background(if (option == style) Accent else Color.Transparent)
                                .clickable {
                                    onStyleChange(option)
                                    stylesOpen = false
                                }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        )
                    }
                }
            }
            MapButton(Icons.Default.Layers, "Map style", active = stylesOpen) { stylesOpen = !stylesOpen }
            MapButton(Icons.Default.Add, "Zoom in") { camera.zoomBy(scope, 1f) }
            MapButton(Icons.Default.Remove, "Zoom out") { camera.zoomBy(scope, -1f) }
            MapButton(
                icon = if (camera.following) Icons.Default.NearMe else Icons.Default.MyLocation,
                label = "Show where I am",
                active = camera.following
            ) {
                val here = state.here
                if (here != null) {
                    camera.following = true
                    camera.flyTo(scope, here, maxOf(camera.zoom, 16.5f), 700)
                }
            }
        }
        }

        // ---------------------------------------------------------- bottom
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.snug, vertical = Space.snug)
        ) {
            val sheetMax = maxHeight * 0.5f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Corner.large))
                    .background(MapGlass)
                    .glass(RoundedCornerShape(Corner.large))
                    .animateContentSize()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Space.step, end = Space.tight, top = Space.tight, bottom = Space.tight),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Geo.ALL_MODES.forEach { mode ->
                        ModePill(mode = mode, selected = mode == travelMode) { onModeChange(mode) }
                        Spacer(Modifier.width(Space.hair))
                    }
                    Spacer(Modifier.weight(1f))
                    val count = state.places.size + if (state.route != null) 1 else 0
                    if (count > 0) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Corner.small))
                                .clickable { sheetOpen = !sheetOpen }
                                .padding(horizontal = Space.tight, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (state.route != null) "Route" else "${state.places.size} found",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextSecondary
                            )
                            Icon(
                                if (sheetOpen) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                contentDescription = if (sheetOpen) "Fold results" else "Show results",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (sheetOpen && (state.places.isNotEmpty() || state.route != null)) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = sheetMax)
                            .padding(horizontal = Space.tight),
                        verticalArrangement = Arrangement.spacedBy(Space.hair)
                    ) {
                        state.route?.let { route ->
                            item {
                                RouteSummary(
                                    destination = route.destination.ifBlank { "your destination" },
                                    summary = "${Geo.formatDistance(route.distanceMeters)} · " +
                                        "${Geo.formatDuration(route.durationSeconds)} " +
                                        Geo.modeVerb(route.mode),
                                    steps = route.steps
                                        .filter { it.distanceMeters > 15 }
                                        .take(6)
                                        .map { "${it.instruction} — ${Geo.formatDistance(it.distanceMeters)}" }
                                )
                            }
                        }
                        itemsIndexed(state.places) { index, place ->
                            PlaceRow(
                                index = index,
                                place = place,
                                selected = index == state.selected,
                                routing = routing && index == state.selected,
                                onSelect = { onSelect(index) },
                                onRoute = { onRoute(index) },
                                onNavigate = { onNavigate(index) },
                                onSaveAs = { name ->
                                    if (name == null) naming = true else onSaveSelected(name)
                                }
                            )
                        }
                        item { Spacer(Modifier.height(Space.tight)) }
                    }
                } else if (state.places.isEmpty() && state.route == null && !hintRead) {
                    // Read once, gone for good: a tip that is still there on the
                    // fiftieth visit has stopped being a tip.
                    Row(
                        modifier = Modifier.padding(start = Space.step, end = Space.hair, bottom = Space.tight),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hold a finger on the map to drop a pin and save it as home. " +
                                "Ask \"what's around here\" or \"take me home\". Tap the map to hide everything on it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextFaint,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { hintRead = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss tip",
                                tint = TextFaint,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
        }

        if (naming) {
            var name by remember { mutableStateOf("") }
            GlassDialog(
                title = "Save this place as…",
                onDismiss = { naming = false },
                confirmLabel = "Save",
                confirmEnabled = name.isNotBlank(),
                onConfirm = {
                    onSaveSelected(name.trim())
                    naming = false
                }
            ) {
                GlassField(value = name, onValueChange = { name = it.take(30) }, placeholder = "e.g. Mum's, gym, the lake")
            }
        }
        managing?.let { place ->
            var name by remember(place.name) { mutableStateOf(place.name) }
            var forgetting by remember(place.name) { mutableStateOf(false) }
            GlassDialog(
                title = if (forgetting) "Forget '${place.name}'?" else place.name.replaceFirstChar { it.uppercase() },
                onDismiss = { managing = null },
                confirmLabel = if (forgetting) "Forget" else "Save",
                confirmEnabled = forgetting || name.isNotBlank(),
                onConfirm = {
                    if (forgetting) onForgetSaved(place.name)
                    else if (!name.trim().equals(place.name, ignoreCase = true)) onRenameSaved(place.name, name.trim())
                    managing = null
                }
            ) {
                if (forgetting) {
                    Text("It goes from the map and from \"take me there\".", color = TextSecondary)
                } else {
                    place.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
                    Spacer(Modifier.height(Space.tight))
                    GlassField(value = name, onValueChange = { name = it.take(30) }, label = "Name")
                    Spacer(Modifier.height(Space.snug))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
                        ChipButton(label = "Route", icon = Icons.Default.Directions, onClick = {
                            onRouteSaved(place.name)
                            managing = null
                        })
                        ChipButton(label = "Forget", icon = Icons.Default.Close, onClick = { forgetting = true })
                    }
                }
            }
        }

        // Every tile source asks for its credit to be visible on the map.
        Text(
            text = tiles.source(style).credit,
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = Space.snug, bottom = 2.dp)
        )
    }
}

@Composable
private fun WhereCard(
    title: String,
    here: String?,
    accuracy: Float?,
    canClear: Boolean,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.large))
            .background(MapGlass)
            .glass(RoundedCornerShape(Corner.large))
            .padding(start = Space.step, end = Space.hair, top = Space.tight, bottom = Space.tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (here != null) Positive else TextFaint)
        )
        Spacer(Modifier.width(Space.snug))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = here ?: "Finding you…",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(title)
                    accuracy?.let { append(" · ±${it.toInt()} m") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canClear) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onClear() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Clear, contentDescription = "Clear the map", tint = TextSecondary)
            }
        } else {
            Spacer(Modifier.width(Space.snug))
        }
    }
}

@Composable
private fun MapButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (active) Accent else MapGlass)
            .glass(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = if (active) Ink else TextPrimary, modifier = Modifier.size(21.dp))
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun FloatingChip(icon: ImageVector, label: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Corner.small))
            .background(MapGlass)
            .glass(RoundedCornerShape(Corner.small))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
    }
}

@Composable
private fun ModePill(mode: String, selected: Boolean, onClick: () -> Unit) {
    val icon = when (mode) {
        Geo.MODE_CYCLE -> Icons.AutoMirrored.Filled.DirectionsBike
        Geo.MODE_DRIVE -> Icons.Default.DirectionsCar
        else -> Icons.AutoMirrored.Filled.DirectionsWalk
    }
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 36.dp)
            .clip(RoundedCornerShape(Corner.small))
            .background(if (selected) Accent else Film.faint)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = mode.replaceFirstChar { it.uppercase() },
            tint = if (selected) Ink else TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun PlaceRow(
    index: Int,
    place: Place,
    selected: Boolean,
    routing: Boolean,
    onSelect: () -> Unit,
    onRoute: () -> Unit,
    onNavigate: () -> Unit,
    /** Save this place: a name, or null to ask for one. */
    onSaveAs: (String?) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.medium))
            .background(if (selected) Film.lifted else Color.Transparent)
            .clickable { onSelect() }
            .padding(horizontal = Space.snug, vertical = Space.tight + 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) Positive else Accent),
                contentAlignment = Alignment.Center
            ) {
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = Ink)
            }
            Text(
                place.name,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f)
            )
            place.distanceMeters?.let {
                Text(Geo.formatDistance(it), style = MaterialTheme.typography.bodyMedium, color = Accent)
            }
        }
        listOfNotNull(place.category, place.detail, place.address)
            .takeIf { it.isNotEmpty() }
            ?.let { lines ->
                Text(
                    lines.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, start = 34.dp)
                )
            }
        if (selected) {
            Row(
                modifier = Modifier.padding(top = 10.dp, start = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChipButton(label = "Route", icon = Icons.Default.Directions, busy = routing, onClick = onRoute)
                ChipButton(label = "Navigate", icon = Icons.Default.Navigation, prominent = true, onClick = onNavigate)
            }
            // Any place on the map can become one of yours: "this is my house".
            Row(
                modifier = Modifier
                    .padding(top = 8.dp, start = 34.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChipButton(label = "My home", icon = Icons.Default.Home, onClick = { onSaveAs("home") })
                ChipButton(label = "Work", icon = Icons.Default.Work, onClick = { onSaveAs("work") })
                ChipButton(label = "Save as…", icon = Icons.Default.Add, onClick = { onSaveAs(null) })
            }
        }
    }
}

@Composable
private fun RouteSummary(destination: String, summary: String, steps: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.medium))
            .background(DialogPane.copy(alpha = 0.6f))
            .padding(Space.snug)
    ) {
        Text(destination, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = Accent)
        steps.forEachIndexed { index, step ->
            Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextFaint,
                    modifier = Modifier.width(18.dp)
                )
                Text(step, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

private fun savedOrder(place: SavedPlace): Int = when {
    place.isCar -> 0
    place.name.equals("home", true) -> 1
    place.name.equals("work", true) -> 2
    else -> 3
}

private fun savedIcon(place: SavedPlace): ImageVector = when {
    place.isCar -> Icons.Default.DirectionsCar
    place.name.equals("home", true) -> Icons.Default.Home
    place.name.equals("work", true) -> Icons.Default.Work
    else -> Icons.Default.Navigation
}
