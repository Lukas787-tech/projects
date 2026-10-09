package com.lukas.jarvis.ui.rooms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.maps.GeoPoint
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.maps.Place
import com.lukas.jarvis.maps.SavedPlace
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.ui.kit.ButtonKind
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.kit.CafeSheet
import com.lukas.jarvis.ui.kit.CafeTextField
import com.lukas.jarvis.ui.kit.ChoiceChip
import com.lukas.jarvis.ui.kit.FollowChip
import com.lukas.jarvis.ui.kit.IconCircle
import com.lukas.jarvis.ui.kit.QuietButton
import com.lukas.jarvis.ui.kit.VSpace
import com.lukas.jarvis.ui.kit.Wrap
import com.lukas.jarvis.ui.map.MapCamera
import com.lukas.jarvis.ui.map.MapCanvas
import com.lukas.jarvis.ui.map.rememberMapCamera
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.Elevation
import com.lukas.jarvis.ui.theme.paper

class MapActions(
    val onBack: () -> Unit,
    val onSelect: (Int) -> Unit = {},
    val onRoute: (Int) -> Unit = {},
    val onNavigate: (Int) -> Unit = {},
    val onModeChange: (String) -> Unit = {},
    val onClear: () -> Unit = {},
    /** A map style by id, "auto" following the café. */
    val onStyleChange: (String) -> Unit = {},
    val onFollow: (Boolean) -> Unit = {},
    val onSaveHere: (String) -> Unit = {},
    val onRouteSaved: (String) -> Unit = {},
    /** A finger held on the map: drop a pin there. */
    val onDropPin: (GeoPoint) -> Unit = {},
    /** Keep the selected or dropped pin under a name. */
    val onSaveSelected: (String) -> Unit = {},
    val onRenameSaved: (String, String) -> Unit = { _, _ -> },
    val onForgetSaved: (String) -> Unit = {},
    val onMessageShown: () -> Unit = {},
    val onHintRead: () -> Unit = {}
)

/**
 * The map, edge to edge: the working view of where things are. Where you are
 * sits at the top with the way back, the camera down the side, and what was
 * found in a sheet that folds away when you want to look. A tap on the map
 * hides everything over it; another brings it back.
 */
@Composable
fun MapRoom(
    state: MapState,
    tiles: TileCache,
    style: MapStyle,
    styleId: String,
    saved: List<SavedPlace>,
    hereLabel: String?,
    travelMode: String,
    routing: Boolean,
    actions: MapActions,
    modifier: Modifier = Modifier,
    heading: Float? = null,
    /** A short line to show on the map for a moment, then clear. */
    message: String? = null,
    hintRead: Boolean = true
) {
    val scope = rememberCoroutineScope()
    val camera: MapCamera = rememberMapCamera(state)

    // The dot is live only while the map is: GPS and compass stop when it goes.
    DisposableEffect(Unit) {
        actions.onFollow(true)
        onDispose { actions.onFollow(false) }
    }
    var sheetOpen by remember { mutableStateOf(true) }
    var stylesOpen by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<SavedPlace?>(null) }
    var naming by remember { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3500)
            actions.onMessageShown()
        }
    }
    // A new answer is something to look at, so it brings the controls back.
    LaunchedEffect(state.revision) {
        if (state.places.isNotEmpty() || state.route != null) {
            controls = true
            sheetOpen = true
        }
    }

    Box(modifier.fillMaxSize().background(Cafe.colors.foam)) {
        MapCanvas(
            state = state,
            tiles = tiles,
            style = style,
            camera = camera,
            heading = heading,
            saved = saved,
            onSelectPlace = { index ->
                actions.onSelect(index)
                controls = true
                sheetOpen = true
            },
            onTapEmpty = {
                stylesOpen = false
                controls = !controls
            },
            onLongPress = { point ->
                actions.onDropPin(point)
                controls = true
                sheetOpen = true
            },
            onTapSaved = { place -> managing = place }
        )

        // The way back is never hidden, even when everything else is.
        if (!controls) {
            IconCircle(
                Icons.AutoMirrored.Rounded.ArrowBack,
                "Back to talking",
                actions.onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(Cafe.space.m).paper(Elevation.Lifted, CircleShape, Cafe.colors),
                filled = false
            )
        }

        // ---------------------------------------------------------------- top
        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.fillMaxWidth().padding(Cafe.space.m)) {
                WhereCard(
                    title = state.title.ifBlank { "Map" },
                    here = hereLabel,
                    accuracy = state.accuracy,
                    canClear = state.places.isNotEmpty() || state.route != null,
                    onBack = actions.onBack,
                    onClear = actions.onClear
                )
                AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        message.orEmpty(),
                        style = Cafe.type.body,
                        color = Cafe.colors.espresso,
                        modifier = Modifier
                            .padding(top = Cafe.space.s)
                            .fillMaxWidth()
                            .floating(Cafe.shape.medium)
                            .padding(horizontal = Cafe.space.l, vertical = Cafe.space.s)
                    )
                }
                VSpace(Cafe.space.s)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Cafe.space.s)) {
                    MapChip(Icons.Rounded.LocalParking, "I parked here") { actions.onSaveHere("car") }
                    saved.sortedBy { savedOrder(it) }.forEach { place ->
                        MapChip(
                            icon = savedIcon(place),
                            label = if (place.isCar) "My car" else place.name.replaceFirstChar { it.uppercase() },
                            onLongClick = { managing = place },
                            onClick = { actions.onRouteSaved(place.name) }
                        )
                    }
                    if (saved.none { it.name.equals("home", true) }) MapChip(Icons.Rounded.Home, "Set home") { actions.onSaveHere("home") }
                }
            }
        }

        // --------------------------------------------------------------- side
        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
            Column(Modifier.padding(end = Cafe.space.m), verticalArrangement = Arrangement.spacedBy(Cafe.space.s), horizontalAlignment = Alignment.End) {
                AnimatedVisibility(visible = stylesOpen, enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.floating(Cafe.shape.medium).padding(Cafe.space.xs), verticalArrangement = Arrangement.spacedBy(Cafe.space.xxs)) {
                        (listOf("auto" to "Like the café") + MapStyle.entries.map { it.id to it.title }).forEach { (id, title) ->
                            val lit = id == styleId
                            Text(
                                title,
                                style = Cafe.type.label,
                                color = if (lit) Cafe.colors.onAccent else Cafe.colors.espresso,
                                modifier = Modifier
                                    .defaultMinSize(minHeight = Cafe.space.touch)
                                    .clip(Cafe.shape.small)
                                    .background(if (lit) Cafe.colors.accentFill else Cafe.colors.paper)
                                    .clickable(role = Role.RadioButton) {
                                        actions.onStyleChange(id)
                                        stylesOpen = false
                                    }
                                    .semantics { selected = lit }
                                    .padding(horizontal = Cafe.space.l, vertical = Cafe.space.m)
                            )
                        }
                    }
                }
                MapButton(Icons.Rounded.Layers, "Map style", active = stylesOpen) { stylesOpen = !stylesOpen }
                MapButton(Icons.Rounded.Add, "Zoom in") { camera.zoomBy(scope, 1f) }
                MapButton(Icons.Rounded.Remove, "Zoom out") { camera.zoomBy(scope, -1f) }
                MapButton(if (camera.following) Icons.Rounded.NearMe else Icons.Rounded.MyLocation, "Show where I am", active = camera.following) {
                    state.here?.let { here ->
                        camera.following = true
                        camera.flyTo(scope, here, maxOf(camera.zoom, 16.5f), 700)
                    }
                }
            }
        }

        // ------------------------------------------------------------- bottom
        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(Cafe.space.m)) {
                val sheetMax = maxHeight * 0.5f
                Column(Modifier.fillMaxWidth().floating(Cafe.shape.large).animateContentSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = Cafe.space.m, end = Cafe.space.xs, top = Cafe.space.xs, bottom = Cafe.space.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Geo.ALL_MODES.forEach { mode -> ModePill(mode, mode == travelMode) { actions.onModeChange(mode) } }
                        Box(Modifier.weight(1f))
                        val count = state.places.size + if (state.route != null) 1 else 0
                        if (count > 0) {
                            QuietButton(
                                if (state.route != null) "Route" else "${state.places.size} found",
                                { sheetOpen = !sheetOpen },
                                icon = if (sheetOpen) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess
                            )
                        }
                    }
                    if (sheetOpen && (state.places.isNotEmpty() || state.route != null)) {
                        LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = sheetMax).padding(horizontal = Cafe.space.s),
                            verticalArrangement = Arrangement.spacedBy(Cafe.space.xxs)
                        ) {
                            state.route?.let { route ->
                                item(key = "route") {
                                    RouteSummary(
                                        destination = route.destination.ifBlank { "your destination" },
                                        summary = "${Geo.formatDistance(route.distanceMeters)} · ${Geo.formatDuration(route.durationSeconds)} ${Geo.modeVerb(route.mode)}",
                                        steps = route.steps.filter { it.distanceMeters > 15 }.take(6).map { "${it.instruction} — ${Geo.formatDistance(it.distanceMeters)}" }
                                    )
                                }
                            }
                            itemsIndexed(state.places) { index, place ->
                                PlaceRow(
                                    index = index,
                                    place = place,
                                    selected = index == state.selected,
                                    routing = routing && index == state.selected,
                                    onSelect = { actions.onSelect(index) },
                                    onRoute = { actions.onRoute(index) },
                                    onNavigate = { actions.onNavigate(index) },
                                    onSaveAs = { name -> if (name == null) naming = true else actions.onSaveSelected(name) }
                                )
                            }
                            item(key = "end") { VSpace(Cafe.space.s) }
                        }
                    } else if (state.places.isEmpty() && state.route == null && !hintRead) {
                        // Read once, gone for good.
                        Row(Modifier.padding(start = Cafe.space.l, end = Cafe.space.xs, bottom = Cafe.space.s), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Hold a finger on the map to drop a pin and keep it. Ask \"what's around here\" or \"take me home\". Tap the map to hide everything on it.",
                                style = Cafe.type.bodySmall,
                                color = Cafe.colors.cocoa,
                                modifier = Modifier.weight(1f)
                            )
                            IconCircle(Icons.Rounded.Close, "Got it, hide the tip", actions.onHintRead, size = 44.dp, tint = Cafe.colors.cocoa)
                        }
                    }
                }
            }
        }

        // Every tile source asks for its credit to be visible on the map.
        Text(
            tiles.source(style).credit,
            style = Cafe.type.caption,
            color = Cafe.colors.cocoa,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = Cafe.space.m, bottom = 2.dp)
        )
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        CafeSheet("Keep this place as…", onDismiss = { naming = false }) {
            CafeTextField(name, { name = it.take(30) }, placeholder = "Mum's, the gym, the lake", onDone = {
                if (name.isNotBlank()) {
                    actions.onSaveSelected(name.trim())
                    naming = false
                }
            })
            VSpace(Cafe.space.l)
            CafeButton("Keep it", {
                actions.onSaveSelected(name.trim())
                naming = false
            }, enabled = name.isNotBlank())
        }
    }
    managing?.let { place ->
        var name by remember(place.name) { mutableStateOf(place.name) }
        var forgetting by remember(place.name) { mutableStateOf(false) }
        CafeSheet(place.name.replaceFirstChar { it.uppercase() }, onDismiss = { managing = null }) {
            place.note?.let { Text(it, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa) }
            VSpace(Cafe.space.s)
            CafeTextField(name, { name = it.take(30) }, label = "Name")
            VSpace(Cafe.space.l)
            Wrap {
                CafeButton("Take me there", {
                    actions.onRouteSaved(place.name)
                    managing = null
                }, icon = Icons.Rounded.Directions)
                if (!name.trim().equals(place.name, ignoreCase = true) && name.isNotBlank()) {
                    CafeButton("Rename", {
                        actions.onRenameSaved(place.name, name.trim())
                        managing = null
                    }, kind = ButtonKind.Secondary)
                }
                if (!forgetting) QuietButton("Forget this place", { forgetting = true }, icon = Icons.Rounded.Close)
            }
            if (forgetting) {
                VSpace(Cafe.space.s)
                Text("It goes from the map and from \"take me there\".", style = Cafe.type.bodySmall, color = Cafe.colors.espresso)
                VSpace(Cafe.space.s)
                Wrap {
                    CafeButton("Forget ${place.name}", {
                        actions.onForgetSaved(place.name)
                        managing = null
                    }, kind = ButtonKind.Danger)
                    QuietButton("Keep it", { forgetting = false })
                }
            }
        }
    }
}

/** Paper floating over the map: opaque enough that streets never show through words. */
@Composable
private fun Modifier.floating(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.paper(Elevation.Lifted, shape, Cafe.colors).clip(shape).background(Cafe.colors.paper.copy(alpha = 0.96f))

@Composable
private fun WhereCard(title: String, here: String?, accuracy: Float?, canClear: Boolean, onBack: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().floating(Cafe.shape.large).padding(Cafe.space.xs), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(Icons.AutoMirrored.Rounded.ArrowBack, "Back to talking", onBack)
        Box(Modifier.padding(start = Cafe.space.s).size(10.dp).clip(CircleShape).background(if (here != null) Cafe.colors.sage else Cafe.colors.latteDeep))
        Column(Modifier.weight(1f).padding(horizontal = Cafe.space.m)) {
            Text(here ?: "Finding you…", style = Cafe.type.title, color = Cafe.colors.espresso, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title + (accuracy?.let { " · ±${it.toInt()} m" } ?: ""), style = Cafe.type.caption, color = Cafe.colors.cocoa, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (canClear) IconCircle(Icons.Rounded.Close, "Clear the map", onClear, tint = Cafe.colors.cocoa)
    }
}

@Composable
private fun MapButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Cafe.space.touch)
            .paper(Elevation.Lifted, CircleShape, Cafe.colors)
            .clip(CircleShape)
            .background(if (active) Cafe.colors.accentFill else Cafe.colors.paper)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (active) Cafe.colors.onAccent else Cafe.colors.espresso, modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MapChip(icon: ImageVector, label: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .defaultMinSize(minHeight = Cafe.space.touch)
            .floating(Cafe.shape.pill)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) "Change or forget" else null)
            .padding(horizontal = Cafe.space.m, vertical = Cafe.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.xs)
    ) {
        Icon(icon, contentDescription = null, tint = Cafe.colors.accentText, modifier = Modifier.size(18.dp))
        Text(label, style = Cafe.type.labelSmall, color = Cafe.colors.espresso)
    }
}

@Composable
private fun ModePill(mode: String, selected: Boolean, onClick: () -> Unit) {
    val icon = when (mode) {
        Geo.MODE_CYCLE -> Icons.AutoMirrored.Rounded.DirectionsBike
        Geo.MODE_DRIVE -> Icons.Rounded.DirectionsCar
        else -> Icons.AutoMirrored.Rounded.DirectionsWalk
    }
    Box(
        Modifier
            .padding(end = Cafe.space.xxs)
            .size(width = 52.dp, height = Cafe.space.touch)
            .clip(Cafe.shape.pill)
            .background(if (selected) Cafe.colors.accentFill else Cafe.colors.latte)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = mode.replaceFirstChar { it.uppercase() }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Cafe.colors.onAccent else Cafe.colors.cocoa, modifier = Modifier.size(22.dp))
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
    onSaveAs: (String?) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Cafe.shape.medium)
            .background(if (selected) Cafe.colors.accentSoft else Cafe.colors.paper)
            .clickable(onClickLabel = "Show ${place.name}", onClick = onSelect)
            .padding(horizontal = Cafe.space.m, vertical = Cafe.space.s)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(if (selected) Cafe.colors.sage else Cafe.colors.accentFill), contentAlignment = Alignment.Center) {
                Text("${index + 1}", style = Cafe.type.labelSmall, color = Cafe.colors.onAccent)
            }
            Text(place.name, style = Cafe.type.title, color = Cafe.colors.espresso, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Cafe.space.s).weight(1f))
            place.distanceMeters?.let { Text(Geo.formatDistance(it), style = Cafe.type.label, color = Cafe.colors.accentText) }
        }
        listOfNotNull(place.category, place.detail, place.address).takeIf { it.isNotEmpty() }?.let { lines ->
            Text(lines.joinToString(" · "), style = Cafe.type.bodySmall, color = Cafe.colors.cocoa, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Cafe.space.xxs, start = 34.dp))
        }
        if (selected) {
            VSpace(Cafe.space.s)
            Wrap(Modifier.padding(start = 34.dp)) {
                CafeButton(if (routing) "Finding the way…" else "Route", onRoute, kind = ButtonKind.Secondary, icon = Icons.Rounded.Directions, enabled = !routing)
                CafeButton("Navigate", onNavigate, icon = Icons.Rounded.Navigation)
            }
            VSpace(Cafe.space.xs)
            // Any place on the map can become one of yours: "this is my house".
            Row(Modifier.padding(start = 34.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Cafe.space.s)) {
                FollowChip("My home", { onSaveAs("home") }, icon = Icons.Rounded.Home)
                FollowChip("Work", { onSaveAs("work") }, icon = Icons.Rounded.Work)
                FollowChip("Keep as…", { onSaveAs(null) }, icon = Icons.Rounded.Place)
            }
        }
    }
}

@Composable
private fun RouteSummary(destination: String, summary: String, steps: List<String>) {
    Column(Modifier.fillMaxWidth().clip(Cafe.shape.medium).background(Cafe.colors.latte).padding(Cafe.space.m)) {
        Text(destination, style = Cafe.type.title, color = Cafe.colors.espresso)
        Text(summary, style = Cafe.type.body, color = Cafe.colors.accentText)
        steps.forEachIndexed { index, step ->
            Row(Modifier.padding(top = Cafe.space.xs), verticalAlignment = Alignment.Top) {
                Text("${index + 1}", style = Cafe.type.caption, color = Cafe.colors.cocoa, modifier = Modifier.width(20.dp))
                Text(step, style = Cafe.type.bodySmall, color = Cafe.colors.espresso)
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
    place.isCar -> Icons.Rounded.DirectionsCar
    place.name.equals("home", true) -> Icons.Rounded.Home
    place.name.equals("work", true) -> Icons.Rounded.Work
    else -> Icons.Rounded.Navigation
}
