package com.lukas.jarvis.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.brief.DayBrief
import com.lukas.jarvis.core.TimeUtil
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
    modifier: Modifier = Modifier
) {
    // Gathered when the screen appears rather than on a timer: a brief is only
    // ever wanted at the moment it is looked at.
    LaunchedEffect(Unit) { if (brief == null) onRefresh() }

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

        item { Shortcuts(onOpen = onOpen, onPlayMusic = onPlayMusic) }

        brief?.let { day ->
            item { Vitals(day) }

            if (day.overdue.isNotEmpty()) {
                item { GroupHeader("Overdue", trailing = day.overdue.size.toString()) }
                items(day.overdue, key = { "overdue-${it.id}" }) { task ->
                    TaskLine(task, tint = Negative, onComplete = { onCompleteTask(task) })
                }
            }

            if (day.dueToday.isNotEmpty()) {
                item { GroupHeader("Due today", trailing = day.dueToday.size.toString()) }
                items(day.dueToday, key = { "due-${it.id}" }) { task ->
                    TaskLine(task, tint = Accent, onComplete = { onCompleteTask(task) })
                }
            }

            if (day.appointments.isNotEmpty()) {
                item { GroupHeader("Calendar") }
                items(day.appointments, key = { it.title + it.startsAt }) { event ->
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

        if (trackers.isNotEmpty()) {
            item { GroupHeader("Balances") }
            items(trackers, key = { it.tracker.id }) { status ->
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

        brief?.forecast?.let { forecast ->
            Spacer(Modifier.height(Space.step))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.WbSunny,
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
                            brief.placeName?.let { append(it).append(" · ") }
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
        val batteryPercent = Regex("(\\d+)%").find(brief.battery)?.groupValues?.get(1)
        StatTile(
            value = batteryPercent?.let { "$it%" } ?: "—",
            label = "Battery",
            caption = if (brief.battery.contains("charging")) "charging" else null,
            tint = when {
                batteryPercent == null -> TextSecondary
                batteryPercent.toInt() <= 15 -> Negative
                batteryPercent.toInt() <= 35 -> Caution
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

/** The five places a thumb goes most often. */
@Composable
private fun Shortcuts(onOpen: (Element) -> Unit, onPlayMusic: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
        QuickAction(
            icon = Icons.Default.CheckCircle,
            label = "Tasks",
            onClick = { onOpen(Element.Tasks) },
            modifier = Modifier.weight(1f)
        )
        QuickAction(
            icon = Icons.Default.Psychology,
            label = "Notes",
            onClick = { onOpen(Element.Notes) },
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
            icon = Icons.Default.Map,
            label = "Map",
            onClick = { onOpen(Element.Map) },
            modifier = Modifier.weight(1f)
        )
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
