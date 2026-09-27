package com.lukas.jarvis.ui.holo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lukas.jarvis.brief.DayBrief
import com.lukas.jarvis.control.NowPlaying
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.ListBook
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.maps.Compass
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.maps.SavedPlace
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.ui.map.MapCanvas
import com.lukas.jarvis.ui.map.rememberMapCamera
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentBright
import com.lukas.jarvis.ui.theme.Caution
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.InkCard
import com.lukas.jarvis.ui.theme.Negative
import com.lukas.jarvis.ui.theme.OnAccent
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.ThemeState
import com.lukas.jarvis.web.Forecast
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * What goes inside the projected panels. Each draws live state, so a panel
 * that is up while something changes — a list ticked off, a fix arriving —
 * changes with it rather than showing the moment it was raised.
 */

// ------------------------------------------------------------------------ map

/**
 * The map, arriving from high above: the projection opens at continent scale
 * and dives to the street while the panel catches, the way a satellite view
 * closes in on a target.
 */
@Composable
internal fun MapHolo(
    map: MapState,
    tiles: TileCache,
    style: MapStyle,
    saved: List<SavedPlace>,
    hereLabel: String?,
    routing: Boolean,
    onSelect: (Int) -> Unit,
    onRoute: (Int) -> Unit,
    onNavigate: (Int) -> Unit,
    onFollow: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val calm = ThemeState.reduceMotion
    val camera = rememberMapCamera(map)
    val dive = remember { Animatable(if (calm) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!calm) dive.animateTo(1f, tween(2400, delayMillis = 150, easing = LinearEasing))
    }
    DisposableEffect(Unit) {
        onFollow(true)
        onDispose { onFollow(false) }
    }
    val heading by produceState<Float?>(null) {
        Compass.headings(context).collect { value = it }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MapCanvas(
            state = map,
            tiles = tiles,
            style = style,
            camera = camera,
            heading = heading,
            saved = saved,
            intro = dive.value,
            introFromZoom = 4.5f,
            onSelectPlace = onSelect,
            modifier = Modifier.fillMaxSize()
        )

        // The map's edges fade into the panel, so it reads as projected light
        // rather than a picture pasted in.
        val edge = InkCard
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    0.55f to Color.Transparent,
                    1f to edge.copy(alpha = 0.75f),
                    center = center,
                    radius = size.maxDimension * 0.72f
                )
            )
            val nothingFound = map.places.isEmpty() && map.route == null
            if (nothingFound) drawReticle(dive.value)
        }

        // What and where, in the top corner.
        val here = map.here
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(InkCard.copy(alpha = 0.78f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            val headline = when {
                map.route != null -> map.title
                map.places.isNotEmpty() -> map.title.ifBlank { "Found nearby" }
                else -> hereLabel ?: map.title.ifBlank { "You are here" }
            }
            Text(
                headline,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            map.route?.let { route ->
                Text(
                    "${Geo.formatDistance(route.distanceMeters)} · ${Geo.formatDuration(route.durationSeconds)} " +
                        Geo.modeVerb(route.mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = Accent
                )
            }
        }

        if (here != null) {
            Text(
                coordinates(here.lat, here.lon) + (map.accuracy?.let { "  ±${it.roundToInt()} m" }.orEmpty()),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp),
                color = AccentBright.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = if (map.places.isEmpty()) 12.dp else 64.dp)
            )
        }

        if (map.places.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                map.places.take(8).forEachIndexed { index, place ->
                    val selected = index == map.selected
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Accent.copy(alpha = 0.22f) else InkCard.copy(alpha = 0.82f))
                            .border(1.dp, if (selected) Accent else Accent.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                            .clickable { onSelect(index) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Accent
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            place.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        place.distanceMeters?.let {
                            Text(Geo.formatDistance(it), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        if (selected) {
                            Spacer(Modifier.width(4.dp))
                            MapAction(Icons.Default.Directions, if (routing) "Finding the way" else "Show the way") {
                                onRoute(index)
                            }
                            MapAction(Icons.Default.Navigation, "Navigate there") { onNavigate(index) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = OnAccent, modifier = Modifier.size(16.dp))
    }
}

/** A targeting mark on the centre of the map, closing in as the dive ends. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawReticle(dive: Float) {
    val settle = ((dive - 0.6f) / 0.4f).coerceIn(0f, 1f)
    if (settle <= 0f) return
    val r = (46f - 18f * settle) * density
    val arm = 9f * density
    val c = center
    val color = Accent.copy(alpha = 0.8f * settle)
    val stroke = 1.5f * density
    for (i in 0 until 4) {
        val angle = Math.toRadians(45.0 + 90.0 * i)
        val cos = kotlin.math.cos(angle).toFloat()
        val sin = kotlin.math.sin(angle).toFloat()
        drawLine(
            color,
            Offset(c.x + cos * r, c.y + sin * r),
            Offset(c.x + cos * (r + arm), c.y + sin * (r + arm)),
            stroke,
            StrokeCap.Round
        )
    }
    drawCircle(color.copy(alpha = 0.35f * settle), radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
}

private fun coordinates(lat: Double, lon: Double): String = String.format(
    Locale.US,
    "%.4f° %s  %.4f° %s",
    abs(lat),
    if (lat >= 0) "N" else "S",
    abs(lon),
    if (lon >= 0) "E" else "W"
)

// -------------------------------------------------------------------- weather

@Composable
internal fun WeatherHolo(forecast: Forecast?, place: String) {
    if (forecast == null) {
        Waiting("Reading the sky…")
        return
    }
    val now = forecast.now
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${now.temperature.roundToInt()}°",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 68.sp, lineHeight = 70.sp),
                color = TextPrimary
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    now.description.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Accent,
                    maxLines = 2
                )
                Text(
                    place.ifBlank { now.place },
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Feels ${now.feelsLike.roundToInt()}° · wind ${now.windKph.roundToInt()} km/h",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Readout("RAIN", "${now.precipitationChance}%", Modifier.weight(1f), hot = now.precipitationChance >= 50)
            Readout("HUMIDITY", "${now.humidity}%", Modifier.weight(1f))
            val uv = if (now.uvIndex.isNaN()) forecast.days.firstOrNull()?.uvMax ?: Double.NaN else now.uvIndex
            Readout("UV", if (uv.isNaN()) "—" else uv.roundToInt().toString(), Modifier.weight(1f), hot = !uv.isNaN() && uv >= 6)
        }
        forecast.rainFrom?.let {
            Spacer(Modifier.height(10.dp))
            Text("Rain from $it", style = MaterialTheme.typography.bodyMedium, color = Caution)
        }
        val days = forecast.days.take(5)
        if (days.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            val low = days.minOf { it.low }
            val high = days.maxOf { it.high }
            days.forEachIndexed { index, day ->
                DayRow(
                    label = if (index == 0) "Today" else weekday(day.label),
                    low = day.low,
                    high = day.high,
                    rain = day.precipitationChance,
                    min = low,
                    max = high
                )
            }
            days.first().let { today ->
                if (today.sunrise.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "SUNRISE ${today.sunrise}   SUNSET ${today.sunset}",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                        color = TextFaint
                    )
                }
            }
        }
    }
}

/** One day as a line: the name, the low, a bar spanning low to high on the week's scale, the high. */
@Composable
private fun DayRow(label: String, low: Double, high: Double, rain: Int, min: Double, max: Double) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.width(56.dp))
        Text(
            if (rain >= 20) "$rain%" else "",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
            color = Accent,
            modifier = Modifier.width(38.dp)
        )
        Text("${low.roundToInt()}°", style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.width(34.dp))
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val from = ((low - min) / span).toFloat()
        val to = ((high - min) / span).toFloat()
        val accent = Accent
        val bright = AccentBright
        val track = Film.lifted
        Canvas(modifier = Modifier.weight(1f).height(6.dp)) {
            val y = size.height / 2
            drawLine(track, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
            drawLine(
                Brush.horizontalGradient(listOf(accent, bright), startX = size.width * from, endX = size.width * to),
                Offset(size.width * from, y),
                Offset(size.width * to.coerceAtLeast(from + 0.02f), y),
                size.height,
                StrokeCap.Round
            )
        }
        Text(
            "${high.roundToInt()}°",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.padding(start = 10.dp).width(34.dp)
        )
    }
}

private fun weekday(label: String): String = runCatching {
    LocalDate.parse(label).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
}.getOrDefault(label.take(3))

/** A small labelled number, the building block of an instrument panel. */
@Composable
private fun Readout(label: String, value: String, modifier: Modifier = Modifier, hot: Boolean = false) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Film.faint)
            .border(1.dp, Accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp), color = TextFaint)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (hot) Caution else TextPrimary,
            maxLines = 1
        )
    }
}

// ------------------------------------------------------------------------ day

@Composable
internal fun DayHolo(brief: DayBrief?, tasks: List<Task>, trackers: List<TrackerStatus>) {
    if (brief == null) {
        Waiting("Gathering the day…")
        return
    }
    val now = System.currentTimeMillis()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(brief.dateLine.uppercase(), style = MaterialTheme.typography.labelMedium, color = Accent)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            brief.forecast?.let { f ->
                Text(
                    "${f.now.temperature.roundToInt()}°",
                    style = MaterialTheme.typography.displayMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(f.now.description, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1)
                    Text(
                        listOfNotNull(brief.placeName, f.rainFrom?.let { "rain from $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (f.rainFrom != null) Caution else TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }

        val next = brief.appointments.firstOrNull { it.endsAt > now }
        if (next != null) {
            Spacer(Modifier.height(14.dp))
            Label("NEXT UP")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    if (next.allDay) "ALL DAY" else TimeUtil.formatTime(next.startsAt),
                    style = MaterialTheme.typography.titleLarge,
                    color = Accent,
                    modifier = Modifier.width(76.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(next.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            if (next.startsAt > now) TimeUtil.relative(next.startsAt, now) else "now",
                            next.location?.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val open = tasks.filter { !it.done }
        val overdue = open.count { (it.dueAt ?: Long.MAX_VALUE) < now }
        val dueToday = brief.dueToday.filter { !it.done }
        if (dueToday.isNotEmpty() || overdue > 0) {
            Spacer(Modifier.height(14.dp))
            Label(if (overdue > 0) "DUE TODAY · $overdue OVERDUE" else "DUE TODAY")
            dueToday.take(3).forEach { task ->
                Text(
                    "▸ ${task.title}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        trackers.firstOrNull { it.tracker.budget != null }?.let { status ->
            Spacer(Modifier.height(16.dp))
            BudgetMeter(status)
        }
    }
}

// ---------------------------------------------------------------------- tasks

@Composable
internal fun TasksHolo(tasks: List<Task>, onToggle: (Task) -> Unit) {
    val now = System.currentTimeMillis()
    val open = tasks.filter { !it.done }.sortedWith(compareBy(nullsLast<Long>()) { it.dueAt })
    if (open.isEmpty()) {
        Waiting("Nothing open. A clear board.")
        return
    }
    val overdue = open.count { (it.dueAt ?: Long.MAX_VALUE) < now }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Label(if (overdue > 0) "${open.size} OPEN · $overdue OVERDUE" else "${open.size} OPEN", Modifier.padding(start = 4.dp, bottom = 4.dp))
        open.take(12).forEach { task ->
            val late = (task.dueAt ?: Long.MAX_VALUE) < now
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggle(task) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.RadioButtonUnchecked,
                    contentDescription = "Tick off ${task.title}",
                    tint = if (late) Negative else Accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                task.dueAt?.let { due ->
                    Text(
                        when {
                            late -> "overdue"
                            LocalDate.now() == java.time.Instant.ofEpochMilli(due).atZone(java.time.ZoneId.systemDefault()).toLocalDate() ->
                                TimeUtil.formatTime(due)
                            else -> TimeUtil.relative(due, now)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (late) Negative else TextSecondary
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- money

@Composable
internal fun MoneyHolo(trackers: List<TrackerStatus>) {
    if (trackers.isEmpty()) {
        Waiting("Nothing counted yet.")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        trackers.take(5).forEach { BudgetMeter(it) }
    }
}

/**
 * A tracker as an instrument: its name, the number that matters, and a
 * segmented gauge that fills as the budget goes and turns red past it.
 */
@Composable
private fun BudgetMeter(status: TrackerStatus) {
    val t = status.tracker
    val budget = t.budget
    val left = status.budgetLeft
    val balance = status.balance
    val fraction = if (budget != null && budget > 0) (status.periodSpent / budget).toFloat() else null
    val tone = when {
        fraction == null -> Accent
        fraction > 1f -> Negative
        fraction > 0.85f -> Caution
        else -> Accent
    }
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(t.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.weight(1f))
            Text(
                when {
                    left != null -> if (left >= 0) "${amount(left, t)} left" else "${amount(-left, t)} over"
                    balance != null -> amount(balance, t)
                    else -> amount(status.periodSpent, t)
                },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light),
                color = if (left != null && left < 0) Negative else TextPrimary
            )
        }
        if (fraction != null) {
            Spacer(Modifier.height(6.dp))
            val track = Film.lifted
            Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                val segments = 28
                val gap = 2.dp.toPx()
                val w = (size.width - gap * (segments - 1)) / segments
                val lit = (fraction.coerceIn(0f, 1f) * segments).roundToInt()
                for (i in 0 until segments) {
                    drawRect(
                        color = if (i < lit) tone.copy(alpha = 0.55f + 0.45f * (i + 1) / segments) else track,
                        topLeft = Offset(i * (w + gap), 0f),
                        size = androidx.compose.ui.geometry.Size(w, size.height)
                    )
                }
            }
            Text(
                "${amount(status.periodSpent, t)} of ${amount(budget ?: 0.0, t)} this ${Tracker.periodWord(t.period)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun amount(value: Double, tracker: Tracker): String {
    val number = if (tracker.kind == Tracker.KIND_MONEY || value != value.toLong().toDouble()) {
        String.format(Locale.US, "%.2f", value)
    } else {
        value.toLong().toString()
    }
    return if (tracker.unit.isBlank()) number else "$number ${tracker.unit}"
}

// ---------------------------------------------------------------------- lists

@Composable
internal fun ListHolo(book: ListBook, name: String, onCheck: (String, String, Boolean) -> Unit) {
    val list = book.find(name) ?: book.lists.firstOrNull { it.open.isNotEmpty() } ?: book.lists.firstOrNull()
    if (list == null) {
        Waiting("No lists yet.")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Label("${list.name.uppercase()} · ${list.open.size} OPEN", Modifier.padding(start = 4.dp, bottom = 4.dp))
        (list.open + list.items.filter { it.done }).take(16).forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onCheck(list.name, item.text, !item.done) }
                    .padding(horizontal = 4.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (item.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (item.done) "Put ${item.text} back" else "Tick off ${item.text}",
                    tint = if (item.done) TextFaint else Accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = if (item.done) TextDecoration.LineThrough else null
                    ),
                    color = if (item.done) TextFaint else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- music

@Composable
internal fun MusicHolo(
    nowPlaying: NowPlaying?,
    canSeeMedia: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRefresh: () -> Unit,
    onGrantAccess: () -> Unit
) {
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(4000)
        }
    }
    val playing = nowPlaying?.playing == true
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Equalizer(playing, Modifier.fillMaxWidth().height(56.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            nowPlaying?.title ?: if (canSeeMedia) "Nothing playing" else "Music",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            listOfNotNull(nowPlaying?.artist, nowPlaying?.app).joinToString(" · ").ifBlank {
                if (canSeeMedia) "Say what to play" else "Allow notification access to see what is playing"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (nowPlaying != null && nowPlaying.durationMs > 0) {
            var now by androidx.compose.runtime.remember(nowPlaying) { mutableLongStateOf(System.currentTimeMillis()) }
            val started = androidx.compose.runtime.remember(nowPlaying) { System.currentTimeMillis() }
            LaunchedEffect(nowPlaying) {
                while (nowPlaying.playing) {
                    delay(500)
                    now = System.currentTimeMillis()
                }
            }
            val position = (nowPlaying.positionMs + if (playing) now - started else 0L).coerceAtMost(nowPlaying.durationMs)
            val fraction = position.toFloat() / nowPlaying.durationMs
            Spacer(Modifier.height(14.dp))
            val accent = Accent
            val track = Film.lifted
            Canvas(modifier = Modifier.fillMaxWidth().height(3.dp)) {
                drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height)
                drawLine(accent, Offset(0f, size.height / 2), Offset(size.width * fraction, size.height / 2), size.height)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (!canSeeMedia) {
            Text(
                "ALLOW ACCESS",
                style = MaterialTheme.typography.labelLarge,
                color = OnAccent,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Accent)
                    .clickable(onClick = onGrantAccess)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Transport(Icons.Default.SkipPrevious, "Previous", onPrevious, big = false)
                Transport(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Pause" else "Play",
                    if (playing) onPause else onPlay,
                    big = true
                )
                Transport(Icons.Default.SkipNext, "Next", onNext, big = false)
            }
        }
    }
}

@Composable
private fun Transport(icon: ImageVector, label: String, onClick: () -> Unit, big: Boolean) {
    Box(
        modifier = Modifier
            .size(if (big) 60.dp else 46.dp)
            .clip(CircleShape)
            .background(if (big) Accent else Film.lifted)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = if (big) OnAccent else TextPrimary, modifier = Modifier.size(if (big) 30.dp else 22.dp))
    }
}

/** Bars that dance while something plays and lie flat when it does not. */
@Composable
private fun Equalizer(playing: Boolean, modifier: Modifier) {
    val calm = ThemeState.reduceMotion
    val moving = playing && !calm
    val wave = if (moving) rememberInfiniteTransition(label = "eq").animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "wave"
    ) else null
    val accent = Accent
    val bright = AccentBright
    Canvas(modifier = modifier) {
        val bars = 24
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        val t = wave?.value ?: 0f
        for (i in 0 until bars) {
            val shape = 0.5f + 0.5f * kotlin.math.sin(i * 0.7f + t * (1 + i % 3))
            val level = if (playing) 0.2f + 0.8f * shape * (0.6f + 0.4f * kotlin.math.sin(t + i).let { it * it }) else 0.08f
            val h = size.height * level
            drawRect(
                Brush.verticalGradient(listOf(bright, accent.copy(alpha = 0.4f)), startY = size.height - h, endY = size.height),
                topLeft = Offset(i * (w + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(w, h)
            )
        }
    }
}

// --------------------------------------------------------------------- shared

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
        color = TextFaint,
        modifier = modifier
    )
}

@Composable
private fun Waiting(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = TextSecondary)
    }
}
