package com.lukas.jarvis.ui.screens

import com.lukas.jarvis.ui.components.rememberStored
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.auto.Routine
import com.lukas.jarvis.auto.Routines
import com.lukas.jarvis.brief.DayBrief
import com.lukas.jarvis.maps.SavedPlace
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.GlassDialog
import com.lukas.jarvis.ui.components.GlassField
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.web.Weather
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.ui.components.GroupHeader
import com.lukas.jarvis.ui.components.ListRow
import com.lukas.jarvis.ui.components.MeterBar
import com.lukas.jarvis.ui.components.QuickAction
import com.lukas.jarvis.ui.components.StatTile
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Caution
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.Negative
import com.lukas.jarvis.ui.theme.Positive
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.glassCard
import com.lukas.jarvis.ui.theme.sheen
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The day, at a glance.
 *
 * This screen is the same object the assistant reads out when asked how the day
 * looks — one [DayBrief], gathered once. That is the whole point of it: the
 * screen cannot drift from the spoken answer, because there is nothing for it to
 * drift from. What it adds is the things prose is bad at — a budget as a bar
 * rather than a percentage, a row of thumb-sized shortcuts — and it leaves the
 * sentences to the voice.
 */
@Composable
fun TodayScreen(
    brief: DayBrief?,
    loading: Boolean,
    trackers: List<TrackerStatus>,
    onRefresh: () -> Unit,
    onOpen: (Element) -> Unit,
    onCompleteTask: (Task) -> Unit,
    onPlayMusic: () -> Unit,
    onCamera: () -> Unit,
    onScanReceipt: () -> Unit,
    onPark: () -> Unit,
    onGo: (String) -> Unit,
    savedPlaces: List<SavedPlace>,
    routines: List<Routine>,
    onRunRoutine: (String) -> Unit,
    onSaveRoutine: (Routine) -> Unit,
    onDeleteRoutine: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Sends a sentence to the assistant, for a headline tapped to hear more. */
    onAsk: (String) -> Unit = {}
) {
    var editing by remember { mutableStateOf<Routine?>(null) }
    editing?.let { draft ->
        RoutineDialog(
            initial = draft,
            onDismiss = { editing = null },
            onSave = { saved ->
                onSaveRoutine(saved)
                editing = null
            },
            onDelete = if (draft.name.isNotBlank() && routines.any { it.name == draft.name }) {
                {
                    onDeleteRoutine(draft.name)
                    editing = null
                }
            } else {
                null
            }
        )
    }

    // Gathered when the screen appears rather than on a timer: a brief is only
    // ever wanted at the moment it is looked at.
    LaunchedEffect(Unit) { if (brief == null) onRefresh() }

    // Every section can be folded to its heading, and stays the way it was
    // left. What matters on this screen differs from person to person, and a
    // section nobody reads should not keep pushing the rest down.
    var shortcutsFolded by rememberStored("today.fold.shortcuts", false)
    var routinesFolded by rememberStored("today.fold.routines", false)
    var overdueFolded by rememberStored("today.fold.overdue", false)
    var dueFolded by rememberStored("today.fold.due", false)
    var calendarFolded by rememberStored("today.fold.calendar", false)
    var balancesFolded by rememberStored("today.fold.balances", false)
    var newsFolded by rememberStored("today.fold.news", false)

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.snug)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Space.step),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TODAY",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextFaint,
                    modifier = Modifier.weight(1f)
                )
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = TextFaint
                    )
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = TextFaint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        item { HeroCard(brief = brief, loading = loading) }

        item {
            GroupHeader(
                "Shortcuts",
                folded = shortcutsFolded,
                onToggle = { shortcutsFolded = !shortcutsFolded }
            )
        }
        if (!shortcutsFolded) item {
            Shortcuts(
                onOpen = onOpen,
                onPlayMusic = onPlayMusic,
                onCamera = onCamera,
                onScanReceipt = onScanReceipt,
                onPark = onPark,
                onGo = onGo,
                hasCar = savedPlaces.any { it.isCar },
                hasHome = savedPlaces.any { it.name.equals("home", ignoreCase = true) }
            )
        }

        item {
            GroupHeader(
                "Routines",
                trailing = if (routines.isEmpty()) null else routines.size.toString(),
                folded = routinesFolded,
                onToggle = { routinesFolded = !routinesFolded }
            )
        }
        if (routinesFolded) {
            // Folded: the heading alone says what is there.
        } else if (routines.isEmpty()) {
            item {
                RoutineHint(onCreate = { editing = Routine(name = "", steps = emptyList()) })
            }
        } else {
            items(routines, key = { "routine-${it.name}" }) { routine ->
                RoutineLine(
                    routine = routine,
                    onRun = { onRunRoutine(routine.name) },
                    onEdit = { editing = routine }
                )
            }
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    ChipButton(
                        label = "New routine",
                        icon = Icons.Default.Add,
                        onClick = { editing = Routine(name = "", steps = emptyList()) }
                    )
                }
            }
        }

        brief?.let { day ->
            item { Vitals(day) }

            if (day.overdue.isNotEmpty()) {
                item {
                    GroupHeader(
                        "Overdue",
                        trailing = day.overdue.size.toString(),
                        folded = overdueFolded,
                        onToggle = { overdueFolded = !overdueFolded }
                    )
                }
                if (!overdueFolded) items(day.overdue, key = { "overdue-${it.id}" }) { task ->
                    TaskLine(task, tint = Negative, onComplete = { onCompleteTask(task) })
                }
            }

            if (day.dueToday.isNotEmpty()) {
                item {
                    GroupHeader(
                        "Due today",
                        trailing = day.dueToday.size.toString(),
                        folded = dueFolded,
                        onToggle = { dueFolded = !dueFolded }
                    )
                }
                if (!dueFolded) items(day.dueToday, key = { "due-${it.id}" }) { task ->
                    TaskLine(task, tint = Accent, onComplete = { onCompleteTask(task) })
                }
            }

            if (day.appointments.isNotEmpty()) {
                item {
                    GroupHeader(
                        "Calendar",
                        trailing = day.appointments.size.toString(),
                        folded = calendarFolded,
                        onToggle = { calendarFolded = !calendarFolded }
                    )
                }
                // Indexed: the same event in two calendars shares a title and a
                // start, and two equal keys in a lazy list are a crash.
                if (!calendarFolded) itemsIndexed(day.appointments, key = { index, it -> "event-$index-${it.startsAt}" }) { _, event ->
                    ListRow(
                        title = event.title,
                        subtitle = event.location,
                        leading = Icons.Default.CalendarMonth,
                        trailing = if (event.allDay) {
                            "all day"
                        } else {
                            TimeUtil.formatTime(event.startsAt)
                        }
                    )
                }
            }
        }

        brief?.headlines?.takeIf { it.isNotEmpty() }?.let { news ->
            item {
                GroupHeader(
                    "Headlines",
                    trailing = news.size.toString(),
                    folded = newsFolded,
                    onToggle = { newsFolded = !newsFolded }
                )
            }
            if (!newsFolded) itemsIndexed(news, key = { index, _ -> "news-$index" }) { _, story ->
                ListRow(
                    title = story.title,
                    subtitle = listOfNotNull(story.source, story.age).joinToString(" · ").ifBlank { null },
                    leading = Icons.Default.Newspaper,
                    onClick = { onAsk("Tell me more about this story: ${story.title}") }
                )
            }
        }

        if (trackers.isNotEmpty()) {
            item {
                GroupHeader(
                    "Balances",
                    trailing = trackers.size.toString(),
                    folded = balancesFolded,
                    onToggle = { balancesFolded = !balancesFolded }
                )
            }
            if (!balancesFolded) items(trackers, key = { it.tracker.id }) { status ->
                TrackerLine(status) { onOpen(Element.Money) }
            }
        }

        item { Spacer(Modifier.height(Space.loose)) }
    }
}

/** The one card that is the subject of the screen: greeting, weather, date. */
@Composable
private fun HeroCard(brief: DayBrief?, loading: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheen(RoundedCornerShape(Corner.card))
            .padding(Space.gutter)
    ) {
        Text(
            text = brief?.greeting ?: if (loading) "Getting your day together…" else "Today",
            style = MaterialTheme.typography.displayMedium,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Space.hair))
        Text(
            text = brief?.dateLine ?: TimeUtil.format(System.currentTimeMillis()),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        // Pulled out of the safe call: a lambda under `brief?.forecast?.let`
        // does not smart-cast `brief` itself, and the place name lives there.
        val placeName = brief?.placeName
        brief?.forecast?.let { forecast ->
            Spacer(Modifier.height(Space.step))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    skyIcon(forecast.now.description, forecast.now.isDay),
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(Space.snug))
                Column {
                    Text(
                        text = "${forecast.now.temperature.roundToInt()}°  " +
                            forecast.now.description,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = buildString {
                            placeName?.let { append(it).append(" · ") }
                            append("feels ${forecast.now.feelsLike.roundToInt()}°")
                            if (forecast.now.precipitationChance >= 20) {
                                append(" · ${forecast.now.precipitationChance}% rain")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextFaint
                    )
                }
            }

            // The things worth a glance before leaving: when the rain comes,
            // when the light goes, the sun and the air.
            val today = forecast.days.firstOrNull()
            val extras = buildList {
                forecast.rainFrom?.let { add("Rain from $it") }
                if (today != null) {
                    if (forecast.now.isDay && today.sunset.isNotBlank()) add("Sunset ${today.sunset}")
                    if (!forecast.now.isDay) Weather.nextSunrise(forecast)?.let { add("Sunrise $it") }
                    if (!today.uvMax.isNaN() && today.uvMax >= 3) {
                        add("UV ${today.uvMax.roundToInt()} ${Weather.uvWord(today.uvMax)}")
                    }
                }
                forecast.air?.let { add("Air ${Weather.airWord(it.index)}") }
                forecast.air?.pollen?.filter { it.value >= 50 }?.keys?.firstOrNull()?.let { add("${it.replaceFirstChar { c -> c.uppercase() }} pollen high") }
            }
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(Space.hair))
                Text(
                    text = extras.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }

            val ahead = forecast.days.drop(1).take(3)
            if (ahead.isNotEmpty()) {
                Spacer(Modifier.height(Space.snug))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.gutter)) {
                    ahead.forEach { day ->
                        Column {
                            Text(
                                text = day.label.take(9),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextFaint
                            )
                            Text(
                                text = "${day.high.roundToInt()}° / ${day.low.roundToInt()}°",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Battery, network and what is next, in three tiles. */
@Composable
private fun Vitals(brief: DayBrief) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
        // Read defensively: a phone that cannot report its level must show a
        // dash, not take the whole screen down with a number that overflows.
        val batteryPercent = Regex("(\\d{1,3})%").find(brief.battery)?.groupValues?.get(1)
            ?.toIntOrNull()?.takeIf { it in 0..100 }
        StatTile(
            value = batteryPercent?.let { "$it%" } ?: "—",
            label = "Battery",
            caption = if (brief.battery.contains("charging")) "charging" else null,
            tint = when {
                batteryPercent == null -> TextSecondary
                batteryPercent <= 15 -> Negative
                batteryPercent <= 35 -> Caution
                else -> TextPrimary
            },
            modifier = Modifier.weight(1f)
        )
        StatTile(
            value = "${brief.dueToday.size + brief.overdue.size}",
            label = "To do",
            caption = if (brief.overdue.isEmpty()) "due today" else "${brief.overdue.size} overdue",
            tint = if (brief.overdue.isEmpty()) TextPrimary else Negative,
            modifier = Modifier.weight(1f)
        )
        StatTile(
            value = brief.appointments.firstOrNull()
                ?.let { TimeUtil.formatTime(it.startsAt) } ?: "Clear",
            label = "Next",
            caption = brief.appointments.firstOrNull()?.title,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Where a thumb goes most often, in two rows.
 *
 * The top row is things Jarvis does rather than places in the app: look at
 * something, read a receipt into the budget, remember where the car is, get
 * home. Each is the same as saying it, minus the saying.
 */
@Composable
private fun Shortcuts(
    onOpen: (Element) -> Unit,
    onPlayMusic: () -> Unit,
    onCamera: () -> Unit,
    onScanReceipt: () -> Unit,
    onPark: () -> Unit,
    onGo: (String) -> Unit,
    hasCar: Boolean,
    hasHome: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.tight)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
            QuickAction(
                icon = Icons.Default.PhotoCamera,
                label = "Look",
                onClick = onCamera,
                modifier = Modifier.weight(1f),
                active = true
            )
            QuickAction(
                icon = Icons.Default.Receipt,
                label = "Receipt",
                onClick = onScanReceipt,
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = if (hasCar) Icons.Default.DirectionsCar else Icons.Default.LocalParking,
                label = if (hasCar) "My car" else "Park",
                onClick = { if (hasCar) onGo("car") else onPark() },
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = Icons.Default.Home,
                label = if (hasHome) "Home" else "Map",
                onClick = { if (hasHome) onGo("home") else onOpen(Element.Map) },
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = Icons.Default.Checklist,
                label = "Lists",
                onClick = { onOpen(Element.Lists) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
            QuickAction(
                icon = Icons.Default.CheckCircle,
                label = "Tasks",
                onClick = { onOpen(Element.Tasks) },
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = Icons.Default.AccountBalanceWallet,
                label = "Money",
                onClick = { onOpen(Element.Money) },
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = Icons.Default.MusicNote,
                label = "Play",
                onClick = onPlayMusic,
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = Icons.Default.Tune,
                label = "Controls",
                onClick = { onOpen(Element.Devices) },
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = Icons.Default.AutoAwesome,
                label = "Skills",
                onClick = { onOpen(Element.Skills) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RoutineHint(onCreate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(Corner.medium)
            .clickable { onCreate() }
            .padding(horizontal = Space.step, vertical = Space.snug),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Bolt, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.snug))
        Column(modifier = Modifier.weight(1f)) {
            Text("Make your first routine", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                "Several things under one name — or just say \"every morning, tell me my day and play music\".",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun RoutineLine(routine: Routine, onRun: () -> Unit, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(Corner.medium)
            .clickable { onEdit() }
            .padding(start = Space.step, end = Space.tight, top = Space.snug, bottom = Space.snug),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                routine.name.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                buildString {
                    routine.time?.let { append("Daily $it · ") }
                    append("${routine.steps.size} step")
                    if (routine.steps.size != 1) append("s")
                    routine.steps.firstOrNull()?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRun) {
            Icon(Icons.Default.PlayCircle, contentDescription = "Run ${routine.name}", tint = Accent)
        }
    }
}

/** Name, one step per line, and an optional time: the whole of a routine. */
@Composable
private fun RoutineDialog(
    initial: Routine,
    onDismiss: () -> Unit,
    onSave: (Routine) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initial.name) }
    var steps by remember { mutableStateOf(initial.steps.joinToString("\n")) }
    var time by remember { mutableStateOf(initial.time.orEmpty()) }
    val stepList = steps.lines().map { it.trim() }.filter { it.isNotBlank() }
    GlassDialog(
        title = if (initial.name.isBlank()) "New routine" else "Edit routine",
        onDismiss = onDismiss,
        confirmLabel = "Save",
        confirmEnabled = name.isNotBlank() && stepList.isNotEmpty(),
        onConfirm = {
            onSave(Routine(name = name.trim(), steps = stepList, time = Routines.normalizeTime(time)))
        }
    ) {
        GlassField(value = name, onValueChange = { name = it }, label = "Name", placeholder = "Morning")
        GlassField(
            value = steps,
            onValueChange = { steps = it },
            label = "Steps, one per line",
            placeholder = "How does my day look?\nWhat's the weather?\nPlay the radio",
            singleLine = false
        )
        GlassField(
            value = time,
            onValueChange = { time = it },
            label = "Daily at (optional)",
            placeholder = "07:00",
            supportingText = "A notification at this time runs it with one tap."
        )
        if (onDelete != null) {
            ChipButton(label = "Delete routine", icon = Icons.Default.Delete, onClick = onDelete)
        }
    }
}

@Composable
private fun TaskLine(task: Task, tint: androidx.compose.ui.graphics.Color, onComplete: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().glassCard(Corner.medium)) {
        ListRow(
            title = task.title,
            subtitle = task.dueAt?.let { TimeUtil.relative(it) },
            leading = Icons.Default.CheckCircle,
            trailing = "Done",
            trailingTint = tint,
            onClick = onComplete,
            modifier = Modifier.padding(horizontal = Space.snug)
        )
    }
}

@Composable
private fun TrackerLine(status: TrackerStatus, onOpen: () -> Unit) {
    val t = status.tracker
    val left = status.balance ?: status.budgetLeft
    val cap = t.startingBalance ?: t.budget

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(Corner.medium)
            .padding(horizontal = Space.step, vertical = Space.snug)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = t.label,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = left?.let { "${format(it)} ${t.unit}" }
                    ?: "${format(status.periodSpent)} ${t.unit}",
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    left == null -> TextSecondary
                    left < 0 -> Negative
                    cap != null && left < cap * 0.2 -> Caution
                    else -> Positive
                }
            )
        }

        if (cap != null && cap > 0) {
            Spacer(Modifier.height(Space.tight))
            val used = (status.periodSpent / cap).toFloat()
            MeterBar(
                fraction = used,
                tint = when {
                    used >= 1f -> Negative
                    used >= 0.8f -> Caution
                    else -> Accent
                }
            )
            Spacer(Modifier.height(Space.hair + 2.dp))
            Text(
                text = "${format(status.periodSpent)} of ${format(cap)} ${t.unit} " +
                    "this ${Tracker.periodWord(t.period)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
                modifier = Modifier.clickable { onOpen() }
            )
        }
    }
}

private fun format(value: Double): String =
    if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value)
    }

/** The sky as an icon: a sun at noon is wrong for overcast, and wrong at night. */
private fun skyIcon(description: String, isDay: Boolean): androidx.compose.ui.graphics.vector.ImageVector {
    val sky = description.lowercase()
    return when {
        "thunder" in sky -> Icons.Default.Thunderstorm
        "snow" in sky -> Icons.Default.AcUnit
        "rain" in sky || "drizzle" in sky || "shower" in sky -> Icons.Default.Umbrella
        "fog" in sky -> Icons.Default.Dehaze
        "overcast" in sky || "cloud" in sky -> Icons.Default.Cloud
        !isDay -> Icons.Default.NightsStay
        else -> Icons.Default.WbSunny
    }
}
