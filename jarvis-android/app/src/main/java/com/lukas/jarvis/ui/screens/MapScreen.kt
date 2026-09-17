package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.Place
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.EmptyState
import com.lukas.jarvis.ui.components.JarvisCard
import com.lukas.jarvis.ui.components.SectionLabel
import com.lukas.jarvis.ui.map.MapCanvas
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Hairline
import com.lukas.jarvis.ui.theme.Ink
import com.lukas.jarvis.ui.theme.InkCard
import com.lukas.jarvis.ui.theme.Positive
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary

/**
 * The map tab: whatever the last place question produced, drawn and listed.
 *
 * Nothing here needs typing — asking "anywhere to eat around here" fills it —
 * but every pin can still be routed to or handed off by hand.
 */
@Composable
fun MapScreen(
    state: MapState,
    tiles: TileCache,
    travelMode: String,
    routing: Boolean,
    onSelect: (Int) -> Unit,
    onRoute: (Int) -> Unit,
    onNavigate: (Int) -> Unit,
    onModeChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val route = state.route

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = "Map",
            subtitle = state.title.ifBlank { "Ask for somewhere and it lands here" },
            actionIcon = if (state.places.isEmpty() && route == null) null else Icons.Default.Clear,
            actionLabel = "Clear the map",
            onAction = onClear.takeIf { state.places.isNotEmpty() || route != null }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (state.places.isEmpty()) 300.dp else 260.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Hairline, RoundedCornerShape(20.dp))
        ) {
            MapCanvas(state = state, tiles = tiles, onSelectPlace = onSelect)

            // OpenStreetMap's licence asks for the credit to be visible on the map.
            Text(
                "© OpenStreetMap",
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Ink.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Geo.ALL_MODES.forEach { mode ->
                ChipButton(
                    label = mode.replaceFirstChar { it.uppercase() },
                    onClick = { onModeChange(mode) },
                    prominent = mode == travelMode
                )
            }
        }

        if (state.places.isEmpty() && route == null) {
            EmptyState(
                title = "Nothing pinned yet",
                subtitle = "Say \"I'm hungry, what's near me\" or \"how do I get to the station\"."
            )
        } else {
            // The route summary scrolls with the results rather than sitting above
            // them: turn lists get long, and the list below must not be squeezed
            // to nothing on a short screen.
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (route != null) {
                    item {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            RouteCard(
                                destination = route.destination.ifBlank { "your destination" },
                                summary = "${Geo.formatDistance(route.distanceMeters)} · " +
                                    "${Geo.formatDuration(route.durationSeconds)} " +
                                    Geo.modeVerb(route.mode),
                                steps = route.steps
                                    .filter { it.distanceMeters > 15 }
                                    .take(6)
                                    .map { step ->
                                        "${step.instruction} — " +
                                            Geo.formatDistance(step.distanceMeters)
                                    }
                            )
                        }
                    }
                }
                if (state.places.isNotEmpty()) {
                    item {
                        Column {
                            Spacer(Modifier.height(4.dp))
                            SectionLabel("Results")
                        }
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
                        onNavigate = { onNavigate(index) }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
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
    onNavigate: () -> Unit
) {
    JarvisCard(onClick = onSelect) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Positive else InkCard)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) Ink else TextSecondary
                    )
                }
                Text(
                    place.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 10.dp).weight(1f)
                )
                place.distanceMeters?.let {
                    Text(
                        Geo.formatDistance(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Accent
                    )
                }
            }

            listOfNotNull(place.category, place.detail, place.address)
                .takeIf { it.isNotEmpty() }
                ?.let { lines ->
                    Text(
                        lines.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChipButton(
                    label = "Route",
                    icon = Icons.Default.Directions,
                    busy = routing,
                    onClick = onRoute
                )
                ChipButton(
                    label = "Navigate",
                    icon = Icons.Default.Navigation,
                    onClick = onNavigate
                )
            }
        }
    }
}

@Composable
private fun RouteCard(destination: String, summary: String, steps: List<String>) {
    JarvisCard {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(destination, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = Accent)
            steps.forEach { step ->
                Text(
                    "· $step",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
