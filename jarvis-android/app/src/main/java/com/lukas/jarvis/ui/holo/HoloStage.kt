package com.lukas.jarvis.ui.holo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.brief.DayBrief
import com.lukas.jarvis.control.NowPlaying
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.ListBook
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.maps.SavedPlace
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.stage.Holo
import com.lukas.jarvis.stage.Projection
import com.lukas.jarvis.stage.Scene
import com.lukas.jarvis.ui.components.CoreStyle
import com.lukas.jarvis.ui.components.Reactor
import com.lukas.jarvis.ui.globe.Globe
import com.lukas.jarvis.ui.globe.GlobeMood
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentBright
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.Negative
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.ThemeState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Everything the panels draw from, read live. */
class HoloData(
    val map: MapState = MapState(),
    val tiles: TileCache? = null,
    val mapStyle: MapStyle = MapStyle.Dark,
    val saved: List<SavedPlace> = emptyList(),
    val hereLabel: String? = null,
    val routing: Boolean = false,
    val brief: DayBrief? = null,
    val tasks: List<Task> = emptyList(),
    val trackers: List<TrackerStatus> = emptyList(),
    val lists: ListBook = ListBook(),
    val nowPlaying: NowPlaying? = null,
    val canSeeMedia: Boolean = false
)

/** What the panels and the glances can do. */
class HoloActions(
    val onDismiss: () -> Unit = {},
    val onTouch: () -> Unit = {},
    val onExpand: (Holo) -> Unit = {},
    val onPromote: (Holo) -> Unit = {},
    val onExpire: (busy: Boolean) -> Unit = {},
    val onRefreshBrief: () -> Unit = {},
    val onSelectPlace: (Int) -> Unit = {},
    val onRoute: (Int) -> Unit = {},
    val onNavigate: (Int) -> Unit = {},
    val onFollow: (Boolean) -> Unit = {},
    val onToggleTask: (Task) -> Unit = {},
    val onCheckItem: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onPlay: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onRefreshMusic: () -> Unit = {},
    val onGrantMedia: () -> Unit = {}
)

private const val ENTER_MS = 1_150
private const val ENTER_DELAY_MS = 380
private const val EXIT_MS = 380

/**
 * The assistant's own screen as a heads-up display.
 *
 * With nothing to show, the core fills it. When the assistant raises a panel
 * the core lifts and shrinks to the top — never gone, still breathing and
 * listening — a packet of light runs down a beam from it, and the panel scans
 * into being where the beam lands. Put away, or left alone long enough, the
 * panel switches off and the core comes back down.
 */
@Composable
fun HoloStage(
    scene: Scene,
    mood: GlobeMood,
    level: Float,
    busy: Boolean,
    coreStyle: CoreStyle,
    data: HoloData,
    actions: HoloActions,
    modifier: Modifier = Modifier
) {
    val calm = ThemeState.reduceMotion
    val focus = scene.focus
    val lift = animateFloatAsState(
        targetValue = if (focus != null) 1f else 0f,
        animationSpec = tween(if (calm) 220 else 900, easing = FastOutSlowInEasing),
        label = "lift"
    )
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(focus?.serial) {
        if (focus == null || calm) return@LaunchedEffect
        pulse.snapTo(0f)
        delay(120)
        pulse.animateTo(1f, tween(ENTER_DELAY_MS + 140, easing = LinearEasing))
    }

    // Left alone, the screen settles by itself; checked once a second.
    val quiet by rememberUpdatedState(!busy)
    val expire by rememberUpdatedState(actions.onExpire)
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            expire(!quiet)
        }
    }

    // The day behind a panel about it is gathered fresh when it goes up.
    LaunchedEffect(focus?.serial) {
        val holo = focus?.holo ?: return@LaunchedEffect
        if (holo == Holo.Day || (holo == Holo.Weather && focus?.forecast == null)) actions.onRefreshBrief()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val fullPx = minOf(widthPx, heightPx)
        val smallPx = with(density) { SMALL_CORE.toPx() }
        val smallCenterY = smallPx / 2f + with(density) { 2.dp.toPx() }
        val panelTopPx = smallPx + with(density) { 28.dp.toPx() }
        val panelTop = with(density) { panelTopPx.toDp() }

        // The beam, behind everything else.
        val accent = Accent
        val bright = AccentBright
        Canvas(modifier = Modifier.fillMaxSize()) {
            val l = lift.value
            if (l <= 0.02f) return@Canvas
            val side = fullPx + (smallPx - fullPx) * l
            val cy = heightPx / 2f + (smallCenterY - heightPx / 2f) * l
            val voice = if (mood == GlobeMood.Speaking) 0.85f + 0.3f * level else 1f
            drawBeam(
                from = Offset(size.width / 2f, cy),
                radius = side / 2f,
                left = 0f,
                right = size.width,
                top = panelTopPx,
                strength = (l * l * voice).coerceIn(0f, 1f),
                pulse = pulse.value,
                accent = accent,
                bright = bright
            )
        }

        // The core: the whole stage at rest, a small projector above a panel.
        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val side = (fullPx + (smallPx - fullPx) * lift.value).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(side, side))
                    layout(side, side) { placeable.place(0, 0) }
                }
                .offset {
                    val l = lift.value
                    val side = fullPx + (smallPx - fullPx) * l
                    val cy = heightPx / 2f + (smallCenterY - heightPx / 2f) * l
                    IntOffset(((widthPx - side) / 2f).roundToInt(), (cy - side / 2f).roundToInt())
                }
        ) {
            if (coreStyle == CoreStyle.Globe) {
                Globe(
                    mood = mood,
                    level = level,
                    here = data.map.here,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Reactor(
                    mood = mood,
                    level = level,
                    style = coreStyle,
                    compact = focus != null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        AnimatedContent(
            targetState = focus,
            contentKey = { it?.serial ?: -1L },
            transitionSpec = {
                (fadeIn(tween(if (calm) 240 else ENTER_MS), initialAlpha = 0.99f) togetherWith
                    fadeOut(tween(if (calm) 180 else EXIT_MS), targetAlpha = 0.99f))
                    .using(SizeTransform(clip = false))
            },
            label = "holo",
            modifier = Modifier
                .fillMaxSize()
                .padding(top = panelTop)
        ) { projection ->
            if (projection == null) {
                Box(Modifier.fillMaxSize())
                return@AnimatedContent
            }
            val shown = transition.animateFloat(
                transitionSpec = {
                    if (targetState == EnterExitState.Visible) {
                        if (calm) tween(240) else tween(ENTER_MS - ENTER_DELAY_MS, delayMillis = ENTER_DELAY_MS, easing = LinearEasing)
                    } else {
                        tween(if (calm) 180 else EXIT_MS, easing = LinearEasing)
                    }
                },
                label = "materialize"
            ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

            HoloFrame(
                title = titleOf(projection, data),
                subtitle = subtitleOf(projection, data),
                icon = iconOf(projection, data),
                code = "H${projection.holo.ordinal + 1}·${(projection.serial % 100).toString().padStart(2, '0')}",
                live = projection.holo == Holo.Map || (projection.holo == Holo.Music && data.nowPlaying?.playing == true),
                progress = { shown.value },
                onClose = actions.onDismiss,
                onTouch = actions.onTouch,
                onExpand = { actions.onExpand(projection.holo) },
                fill = projection.holo == Holo.Map,
                modifier = if (projection.holo == Holo.Map) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
            ) {
                HoloBody(projection, data, actions)
            }
        }
    }
}

private val SMALL_CORE = 84.dp

@Composable
private fun HoloBody(projection: Projection, data: HoloData, actions: HoloActions) {
    when (projection.holo) {
        Holo.Map -> {
            val tiles = data.tiles ?: return
            MapHolo(
                map = data.map,
                tiles = tiles,
                style = data.mapStyle,
                saved = data.saved,
                routing = data.routing,
                onSelect = actions.onSelectPlace,
                onRoute = actions.onRoute,
                onNavigate = actions.onNavigate,
                onFollow = actions.onFollow
            )
        }
        Holo.Weather -> WeatherHolo(
            forecast = projection.forecast ?: data.brief?.forecast,
            place = projection.note.ifBlank { data.brief?.placeName.orEmpty() }
        )
        Holo.Day -> DayHolo(data.brief, data.tasks, data.trackers)
        Holo.Tasks -> TasksHolo(data.tasks, actions.onToggleTask)
        Holo.Money -> MoneyHolo(data.trackers)
        Holo.Lists -> ListHolo(data.lists, projection.note, actions.onCheckItem)
        Holo.Music -> MusicHolo(
            nowPlaying = data.nowPlaying,
            canSeeMedia = data.canSeeMedia,
            onPlay = actions.onPlay,
            onPause = actions.onPause,
            onNext = actions.onNext,
            onPrevious = actions.onPrevious,
            onRefresh = actions.onRefreshMusic,
            onGrantAccess = actions.onGrantMedia
        )
    }
}

private fun titleOf(p: Projection, data: HoloData): String = when (p.holo) {
    Holo.Map -> when {
        data.map.route != null -> "Route"
        data.map.places.isNotEmpty() -> "Places"
        else -> "Location"
    }
    else -> p.holo.title
}

private fun subtitleOf(p: Projection, data: HoloData): String? = when (p.holo) {
    Holo.Map -> when {
        data.map.route != null || data.map.places.isNotEmpty() -> data.map.title
        else -> data.hereLabel ?: data.map.title.takeIf { it.isNotBlank() }
    }
    Holo.Weather -> p.note.ifBlank { data.brief?.placeName.orEmpty() }.ifBlank { null }
    Holo.Day -> data.brief?.greeting
    Holo.Lists -> p.note.ifBlank { null }?.replaceFirstChar { it.uppercase() }
    Holo.Music -> data.nowPlaying?.app
    else -> null
}

private fun iconOf(p: Projection, data: HoloData): ImageVector = when (p.holo) {
    Holo.Map -> when {
        data.map.route != null -> Icons.Default.Directions
        data.map.places.isNotEmpty() -> Icons.Default.Place
        else -> Icons.Default.MyLocation
    }
    else -> iconOf(p.holo)
}

private fun iconOf(holo: Holo): ImageVector = when (holo) {
    Holo.Map -> Icons.Default.MyLocation
    Holo.Weather -> Icons.Default.WbSunny
    Holo.Day -> Icons.Default.Today
    Holo.Tasks -> Icons.Default.CheckCircle
    Holo.Money -> Icons.Default.AccountBalanceWallet
    Holo.Lists -> Icons.Default.Checklist
    Holo.Music -> Icons.Default.MusicNote
}

// -------------------------------------------------------------------- glances

/** Something at the edge of vision: a panel that stepped back, or one worth a look. */
data class Glance(val holo: Holo, val label: String, val icon: ImageVector, val hot: Boolean = false)

/**
 * The glances for the rail: panels that receded, newest first, then whatever
 * is worth a look right now — an appointment coming up, tasks gone overdue,
 * music playing. Never the panel already in front.
 */
fun glances(scene: Scene, data: HoloData, now: Long = System.currentTimeMillis()): List<Glance> {
    val out = mutableListOf<Glance>()
    scene.shelf.forEach { p -> out += Glance(p.holo, shelfLabel(p, data), iconOf(p, data)) }
    data.brief?.appointments?.firstOrNull { !it.allDay && it.startsAt > now && it.startsAt - now <= 2 * 3_600_000L }?.let {
        out += Glance(Holo.Day, "${it.title} · ${TimeUtil.formatTime(it.startsAt)}", Icons.Default.Event)
    }
    val overdue = data.tasks.count { !it.done && (it.dueAt ?: Long.MAX_VALUE) < now }
    if (overdue > 0) out += Glance(Holo.Tasks, "$overdue overdue", Icons.Default.Warning, hot = true)
    data.nowPlaying?.takeIf { it.playing }?.let { out += Glance(Holo.Music, it.title, Icons.Default.MusicNote) }
    return out.filter { it.holo != scene.focus?.holo }.distinctBy { it.holo }
}

private fun shelfLabel(p: Projection, data: HoloData): String = when (p.holo) {
    Holo.Map -> data.map.title.ifBlank { "Location" }
    Holo.Weather -> p.forecast?.let { "${it.now.temperature.roundToInt()}° ${p.note}".trim() } ?: "Weather"
    Holo.Lists -> p.note.ifBlank { "Lists" }.replaceFirstChar { it.uppercase() }
    else -> p.holo.title
}

/** One glance as a small lit capsule; a tap brings its panel back. */
@Composable
fun GlanceChip(glance: Glance, onClick: () -> Unit) {
    val tone = if (glance.hot) Negative else Accent
    Row(
        modifier = Modifier
            .clip(ChamferShape(cut = 8.dp, soft = 2.dp))
            .background(Film.resting)
            .border(1.dp, tone.copy(alpha = 0.45f), ChamferShape(cut = 8.dp, soft = 2.dp))
            .clickable(onClickLabel = "Show ${glance.holo.title}", onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(tone))
        Spacer(Modifier.width(7.dp))
        Icon(glance.icon, contentDescription = null, tint = tone, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            glance.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (glance.hot) TextPrimary else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp)
        )
    }
}
