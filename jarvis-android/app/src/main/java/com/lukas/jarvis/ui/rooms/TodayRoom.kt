package com.lukas.jarvis.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.auto.Routine
import com.lukas.jarvis.auto.RoutineDays
import com.lukas.jarvis.auto.Routines
import com.lukas.jarvis.brief.DayBrief
import com.lukas.jarvis.core.Countdown
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.maps.SavedPlace
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.kit.ButtonKind
import com.lukas.jarvis.ui.kit.CafeSheet
import com.lukas.jarvis.ui.kit.CafeTextField
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.kit.CheckRow
import com.lukas.jarvis.ui.kit.ChoiceChip
import com.lukas.jarvis.ui.kit.Eyebrow
import com.lukas.jarvis.ui.kit.FollowChip
import com.lukas.jarvis.ui.kit.IconCircle
import com.lukas.jarvis.ui.kit.ListRow
import com.lukas.jarvis.ui.kit.PaperCard
import com.lukas.jarvis.ui.kit.QuietButton
import com.lukas.jarvis.ui.kit.SectionHeader
import com.lukas.jarvis.ui.kit.SwitchRow
import com.lukas.jarvis.ui.kit.Skeleton
import com.lukas.jarvis.ui.kit.Tone
import com.lukas.jarvis.ui.kit.VSpace
import com.lukas.jarvis.ui.kit.ValueRow
import com.lukas.jarvis.ui.kit.Wrap
import com.lukas.jarvis.ui.theme.Cafe
import java.time.LocalDate
import kotlin.math.roundToInt

/** What Today can do, all of it leading somewhere. */
class TodayActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    /** Said to Mochi, and the canvas brought forward to show the answer. */
    val onAsk: (String) -> Unit,
    /** Opens the composer with the start of a sentence for the person to finish. */
    val onType: (String) -> Unit,
    val onCompleteTask: (Task) -> Unit,
    val onRunRoutine: (String) -> Unit,
    val onGo: (String) -> Unit,
    val onPark: () -> Unit,
    val onCamera: () -> Unit,
    val onScanReceipt: () -> Unit,
    val onOpenMoney: () -> Unit,
    val onOpenTasks: () -> Unit,
    val onTurnOnCalendar: () -> Unit,
    val onSaveRoutine: (Routine) -> Unit = {},
    val onDeleteRoutine: (String) -> Unit = {},
    val onForgetCountdown: (Long) -> Unit = {},
    val onDidHabit: (Long) -> Unit = {}
)

/**
 * The day at a glance: the sky, what is due, who you are seeing, how the money
 * stands, what is coming up, and the routines and places one tap away. Every
 * empty part offers to start it.
 */
@Composable
fun TodayRoom(
    name: String,
    brief: DayBrief?,
    loading: Boolean,
    trackers: List<TrackerStatus>,
    routines: List<Routine>,
    savedPlaces: List<SavedPlace>,
    countdowns: List<Countdown>,
    actions: TodayActions,
    modifier: Modifier = Modifier,
    streaks: Map<Long, com.lukas.jarvis.data.Streak> = emptyMap()
) {
    val today = LocalDate.now()
    // If the launch gathering failed or never ran, the room asks again on opening.
    LaunchedEffect(Unit) { if (brief == null && !loading) actions.onRefresh() }
    RoomScaffold(
        title = brief?.greeting?.trimEnd('.') ?: "Today",
        subtitle = brief?.dateLine,
        onBack = actions.onBack,
        mochi = CharacterState(if (loading) Mood.Working else Mood.Idle, prop = com.lukas.jarvis.ui.character.Prop.Calendar.takeIf { loading }, description = "$name, looking at your day"),
        onMochi = { actions.onAsk("How does my day look?") },
        modifier = modifier
    ) {
        item(key = "weather") { WeatherCard(brief, loading, actions) }
        item(key = "due") { DueCard(brief, loading, actions) }
        val habits = trackers.filter { com.lukas.jarvis.data.Streaks.isHabit(it.tracker) }
        if (habits.isNotEmpty()) item(key = "habits") {
            PaperCard(Modifier.fillMaxWidth()) {
                SectionHeader("Habits", action = "All", onAction = actions.onOpenMoney)
                habits.take(5).forEach { status -> HabitLine(status.tracker.label, streaks[status.tracker.id], { actions.onDidHabit(status.tracker.id) }) }
            }
        }
        item(key = "calendar") { CalendarCard(brief, brief == null, actions) }
        item(key = "money") { MoneyCard(trackers, actions) }
        val coming = Countdown.upcoming(countdowns, today)
        if (coming.isNotEmpty()) item(key = "countdowns") { CountdownCard(coming, today, actions) }
        item(key = "routines") { RoutineCard(routines, actions) }
        item(key = "places") { PlacesCard(savedPlaces, actions) }
        brief?.headlines?.takeIf { it.isNotEmpty() }?.let { news -> item(key = "news") { NewsCard(news, actions) } }
        item(key = "quick") {
            Wrap {
                CafeButton("Show Mochi something", actions.onCamera, kind = ButtonKind.Secondary, icon = Icons.Rounded.PhotoCamera)
                CafeButton("Scan a receipt", actions.onScanReceipt, kind = ButtonKind.Secondary, icon = Icons.Rounded.ReceiptLong)
            }
        }
    }
}

@Composable
private fun WeatherCard(brief: DayBrief?, loading: Boolean, actions: TodayActions) {
    val forecast = brief?.forecast
    PaperCard(Modifier.fillMaxWidth(), tone = Tone.Honey) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(brief?.placeName ?: "Weather", Modifier.weight(1f))
            IconCircle(Icons.Rounded.Refresh, "Refresh the day", actions.onRefresh, size = 40.dp)
        }
        when {
            forecast != null -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${forecast.now.temperature.roundToInt()}°", style = Cafe.type.display, color = Cafe.colors.espresso)
                    Column(Modifier.padding(start = Cafe.space.m, bottom = Cafe.space.xs)) {
                        Text(forecast.now.description, style = Cafe.type.title, color = Cafe.colors.espresso)
                        Text("Feels like ${forecast.now.feelsLike.roundToInt()}°", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
                    }
                }
                forecast.rainFrom?.let {
                    Text("Rain likely from $it", style = Cafe.type.body, color = Cafe.colors.espresso)
                }
                if (forecast.days.isNotEmpty()) {
                    VSpace(Cafe.space.s)
                    Row(horizontalArrangement = Arrangement.spacedBy(Cafe.space.l)) {
                        forecast.days.take(3).forEach { day ->
                            Column {
                                Text(day.label, style = Cafe.type.caption, color = Cafe.colors.cocoa)
                                Text("${day.high.roundToInt()}° / ${day.low.roundToInt()}°", style = Cafe.type.label, color = Cafe.colors.espresso)
                            }
                        }
                    }
                }
                VSpace(Cafe.space.s)
                Wrap {
                    FollowChip("Do I need a coat?", { actions.onAsk("Do I need a coat today?") })
                    FollowChip("This weekend?", { actions.onAsk("What's the weather this weekend?") })
                }
            }
            loading -> Skeleton(lines = 2, label = "Looking at the sky")
            else -> {
                Text("No forecast yet.", style = Cafe.type.body, color = Cafe.colors.espresso)
                VSpace(Cafe.space.s)
                Wrap {
                    CafeButton("Check the weather", { actions.onAsk("What's the weather like?") })
                    QuietButton("Try again", actions.onRefresh)
                }
            }
        }
    }
}

@Composable
private fun DueCard(brief: DayBrief?, loading: Boolean, actions: TodayActions) {
    val due = brief?.let { it.overdue + it.dueToday }.orEmpty()
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("Due today", action = "All tasks", onAction = actions.onOpenTasks)
        when {
            due.isNotEmpty() -> due.take(6).forEach { task ->
                val overdue = brief?.overdue?.contains(task) == true
                CheckRow(
                    text = task.title,
                    checked = task.done,
                    onCheckedChange = { actions.onCompleteTask(task) },
                    detail = task.dueAt?.let { (if (overdue) "Overdue · " else "") + TimeUtil.formatTime(it) }
                )
            }
            loading -> Skeleton(lines = 2, label = "Gathering what's due")
            else -> {
                Text("Nothing due today.", style = Cafe.type.body, color = Cafe.colors.cocoa)
                VSpace(Cafe.space.xs)
                FollowChip("Add a reminder", { actions.onType("Remind me to ") })
            }
        }
    }
}

@Composable
private fun CalendarCard(brief: DayBrief?, loading: Boolean, actions: TodayActions) {
    val appointments = brief?.appointments.orEmpty()
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("Calendar")
        when {
            // Not knowing yet is not the same as a clear day.
            loading -> Skeleton(lines = 2, label = "Looking at your calendar")
            brief != null && !brief.calendarOn -> {
                Text("Mochi can't see your calendar yet.", style = Cafe.type.body, color = Cafe.colors.espresso)
                VSpace(Cafe.space.xs)
                Wrap {
                    CafeButton("Turn on the calendar", actions.onTurnOnCalendar, icon = Icons.Rounded.Event)
                    QuietButton("Add an event instead", { actions.onType("Add to my calendar: ") })
                }
            }
            appointments.isEmpty() -> {
                Text("A clear day.", style = Cafe.type.body, color = Cafe.colors.cocoa)
                VSpace(Cafe.space.xs)
                FollowChip("Plan something", { actions.onType("Add to my calendar: ") })
            }
            else -> appointments.take(4).forEach { event ->
                ListRow(
                    title = event.title,
                    subtitle = listOfNotNull(
                        if (event.allDay) "All day" else TimeUtil.formatTime(event.startsAt),
                        event.location
                    ).joinToString(" · "),
                    icon = Icons.Rounded.Event,
                    onClick = event.location?.let { place -> { actions.onAsk("How do I get to $place?") } },
                    clickLabel = event.location?.let { "Route to $it" }
                )
            }
        }
    }
}

@Composable
private fun MoneyCard(trackers: List<TrackerStatus>, actions: TodayActions) {
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("Money", action = "Open", onAction = actions.onOpenMoney)
        if (trackers.isEmpty()) {
            Text("Nothing counted yet.", style = Cafe.type.body, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.xs)
            FollowChip("I spent…", { actions.onType("I spent ") })
            return@PaperCard
        }
        trackers.take(3).forEach { status ->
            val t = status.tracker
            val left = status.budgetLeft
            ValueRow(
                t.label,
                left?.let { "${money(it)} ${t.unit} left" } ?: status.balance?.let { "${money(it)} ${t.unit}" } ?: "${money(status.periodSpent)} ${t.unit} ${com.lukas.jarvis.data.Tracker.thisPeriod(t.period)}"
            )
            val budget = t.budget
            if (budget != null && budget > 0) {
                val used = (status.periodSpent / budget).toFloat().coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(Cafe.shape.pill).background(Cafe.colors.latte)) {
                    Box(
                        Modifier.fillMaxWidth(used).height(6.dp).clip(Cafe.shape.pill)
                            .background(if (used > 0.9f) Cafe.colors.berry else Cafe.colors.sage)
                    )
                }
                VSpace(Cafe.space.xs)
            }
        }
    }
}

@Composable
private fun CountdownCard(countdowns: List<Countdown>, today: LocalDate, actions: TodayActions) {
    var forgetting by remember { mutableStateOf<Countdown?>(null) }
    forgetting?.let { countdown ->
        CafeSheet(title = "Stop counting down to ${countdown.name}?", onDismiss = { forgetting = null }) {
            Text("It goes from Today and from the morning brief.", style = Cafe.type.body, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.l)
            Wrap {
                CafeButton("Forget it", {
                    actions.onForgetCountdown(countdown.id)
                    forgetting = null
                })
                CafeButton("Keep it", { forgetting = null }, kind = ButtonKind.Secondary)
            }
        }
    }
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("Coming up")
        countdowns.take(4).forEach { countdown ->
            ListRow(
                title = countdown.name,
                subtitle = countdown.describe(today).removePrefix(countdown.name).removePrefix("'s").removePrefix(":").trim().replaceFirstChar { it.titlecase() },
                onClick = { actions.onAsk("How many days until ${countdown.name}?") },
                trailing = { IconCircle(Icons.Rounded.Close, "Stop counting down to ${countdown.name}", { forgetting = countdown }, size = 40.dp, tint = Cafe.colors.cocoa) }
            )
        }
    }
}

@Composable
private fun RoutineCard(routines: List<Routine>, actions: TodayActions) {
    var editing by remember { mutableStateOf<Routine?>(null) }
    editing?.let { draft ->
        RoutineSheet(
            initial = draft,
            exists = routines.any { it.name == draft.name },
            onDismiss = { editing = null },
            onSave = { saved ->
                // A rename is a new name for the same routine: the old one goes,
                // with its alarm, instead of staying as a duplicate.
                if (draft.name.isNotBlank() && !draft.name.equals(saved.name, ignoreCase = true)) actions.onDeleteRoutine(draft.name)
                actions.onSaveRoutine(saved)
                editing = null
            },
            onDelete = {
                actions.onDeleteRoutine(draft.name)
                editing = null
            }
        )
    }
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("Routines", action = "New", onAction = { editing = Routine(name = "", steps = emptyList()) })
        if (routines.isEmpty()) {
            Text("Several things under one name: weather, your day, the radio.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.xs)
            Wrap {
                FollowChip("Make a morning routine", { actions.onAsk("Make me a morning routine: the weather, my day and the news") })
                FollowChip("Make a bedtime routine", { actions.onAsk("Make me a bedtime routine: an alarm for 7, do not disturb until then") })
            }
            return@PaperCard
        }
        routines.take(5).forEach { routine ->
            ListRow(
                title = routine.name.replaceFirstChar { it.titlecase() },
                subtitle = listOf(routine.schedule, routine.steps.joinToString(" · ")).filter { it.isNotBlank() }.joinToString(" — ").take(110),
                onClick = { editing = routine },
                clickLabel = "Change the ${routine.name} routine",
                trailing = { IconCircle(Icons.Rounded.PlayArrow, "Run the ${routine.name} routine", { actions.onRunRoutine(routine.name) }, filled = true) }
            )
        }
    }
}

@Composable
private fun PlacesCard(saved: List<SavedPlace>, actions: TodayActions) {
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("Places")
        Wrap {
            val home = saved.firstOrNull { it.name.equals("home", ignoreCase = true) }
            val car = saved.firstOrNull { it.isCar }
            if (home != null) CafeButton("Take me home", { actions.onGo(home.name) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Home)
            else FollowChip("This is home", { actions.onAsk("Save this place as home") })
            if (car != null) CafeButton("Where's my car?", { actions.onGo(car.name) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.DirectionsCar)
            CafeButton("I parked here", actions.onPark, kind = ButtonKind.Secondary, icon = Icons.Rounded.LocalParking)
            saved.filterNot { it == home || it == car }.take(3).forEach { place ->
                FollowChip(place.name, { actions.onGo(place.name) })
            }
        }
    }
}

@Composable
private fun NewsCard(news: List<com.lukas.jarvis.web.Headline>, actions: TodayActions) {
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("In the news")
        news.take(3).forEach { headline ->
            ListRow(
                title = headline.title,
                subtitle = listOfNotNull(headline.source, headline.age).joinToString(" · ").ifBlank { null },
                onClick = { actions.onAsk("Tell me more about: ${headline.title}") },
                clickLabel = "Ask Mochi about this"
            )
        }
    }
}

private fun money(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else "%.2f".format(value)

/** A routine's name, its steps one per line, and when it runs by itself. */
@Composable
private fun RoutineSheet(
    initial: Routine,
    exists: Boolean,
    onDismiss: () -> Unit,
    onSave: (Routine) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var steps by remember { mutableStateOf(initial.steps.joinToString("\n")) }
    var time by remember { mutableStateOf(initial.time.orEmpty()) }
    var days by remember {
        mutableStateOf(if (initial.days.isEmpty()) "" else RoutineDays.describe(initial.days).removePrefix("on ").removePrefix("at "))
    }
    var quiet by remember { mutableStateOf(initial.quiet) }
    var starts by remember { mutableStateOf(initial.trigger?.kind) }
    var device by remember { mutableStateOf(initial.trigger?.device.orEmpty()) }
    val stepList = steps.lines().map { it.trim() }.filter { it.isNotBlank() }
    val trigger = starts?.let { kind ->
        val bluetooth = kind == com.lukas.jarvis.auto.Trigger.Kind.Connected || kind == com.lukas.jarvis.auto.Trigger.Kind.Disconnected
        com.lukas.jarvis.auto.Trigger(kind, device.trim().lowercase().takeIf { bluetooth && it.isNotBlank() })
    }
    val parsedDays = RoutineDays.parse(days)
    CafeSheet(title = if (initial.name.isBlank()) "New routine" else "Change routine", onDismiss = onDismiss) {
        CafeTextField(name, { name = it }, label = "Name", placeholder = "Morning")
        VSpace(Cafe.space.m)
        CafeTextField(steps, { steps = it }, label = "Steps, one per line", placeholder = "How does my day look?\nWhat's the weather?\nPlay the radio", singleLine = false, minLines = 3, imeAction = ImeAction.Default)
        VSpace(Cafe.space.m)
        CafeTextField(time, { time = it }, label = "At (optional)", placeholder = "07:00")
        Text(
            if (quiet) "Runs by itself at this time and sends the answer." else "A notification at this time runs it with one tap.",
            style = Cafe.type.bodySmall,
            color = Cafe.colors.cocoa,
            modifier = Modifier.padding(start = Cafe.space.xs, top = Cafe.space.xs)
        )
        if (time.isNotBlank()) {
            VSpace(Cafe.space.m)
            CafeTextField(days, { days = it }, label = "Days", placeholder = "every day, weekdays, mon wed fri")
            Text(RoutineDays.describe(parsedDays).replaceFirstChar { it.uppercase() }, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa, modifier = Modifier.padding(start = Cafe.space.xs, top = Cafe.space.xs))
            SwitchRow(
                title = "Run quietly",
                detail = "For questions and checks: the answer arrives as a notification, no tap needed",
                checked = quiet,
                onCheckedChange = { quiet = it }
            )
        }
        VSpace(Cafe.space.m)
        Eyebrow("Starts by itself")
        Wrap {
            ChoiceChip("Only when asked", starts == null, { starts = null })
            ChoiceChip("When charging", starts == com.lukas.jarvis.auto.Trigger.Kind.Charging, { starts = com.lukas.jarvis.auto.Trigger.Kind.Charging })
            ChoiceChip("Bluetooth connects", starts == com.lukas.jarvis.auto.Trigger.Kind.Connected, { starts = com.lukas.jarvis.auto.Trigger.Kind.Connected })
            ChoiceChip("Bluetooth goes", starts == com.lukas.jarvis.auto.Trigger.Kind.Disconnected, { starts = com.lukas.jarvis.auto.Trigger.Kind.Disconnected })
            ChoiceChip("An event starts", starts == com.lukas.jarvis.auto.Trigger.Kind.EventStarts, { starts = com.lukas.jarvis.auto.Trigger.Kind.EventStarts })
        }
        if (starts == com.lukas.jarvis.auto.Trigger.Kind.Connected || starts == com.lukas.jarvis.auto.Trigger.Kind.Disconnected) {
            VSpace(Cafe.space.s)
            CafeTextField(device, { device = it }, label = "Which device (optional)", placeholder = "car, AirPods")
        }
        trigger?.let {
            Text(
                it.describe.replaceFirstChar { c -> c.uppercase() } + ", it runs in the background and sends the answer.",
                style = Cafe.type.bodySmall,
                color = Cafe.colors.cocoa,
                modifier = Modifier.padding(start = Cafe.space.xs, top = Cafe.space.xs)
            )
        }
        VSpace(Cafe.space.l)
        Wrap {
            CafeButton(
                "Save",
                { onSave(Routine(name = name.trim(), steps = stepList, time = Routines.normalizeTime(time), days = parsedDays, quiet = quiet, trigger = trigger)) },
                enabled = name.isNotBlank() && stepList.isNotEmpty()
            )
            if (exists) CafeButton("Delete routine", onDelete, kind = ButtonKind.Secondary, icon = Icons.Rounded.DeleteOutline)
        }
    }
}
