package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.ui.components.EmptyState
import com.lukas.jarvis.ui.components.JarvisCard
import com.lukas.jarvis.ui.components.Picker
import com.lukas.jarvis.ui.components.SectionLabel
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Hairline
import com.lukas.jarvis.ui.theme.Negative
import com.lukas.jarvis.ui.theme.Positive
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import java.util.Locale

/**
 * Money is the obvious use, but a tracker is just a named number with a
 * direction, so calories or gym sessions render identically.
 */
@Composable
fun TrackersScreen(
    trackers: List<TrackerStatus>,
    entries: List<Entry>,
    defaultCurrency: String,
    onSaveTracker: (Tracker) -> Unit,
    onDeleteTracker: (Long) -> Unit,
    onAddEntry: (Long, Double, String, String?) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false
) {
    var showTrackerDialog by remember { mutableStateOf(false) }
    var entryTarget by remember { mutableStateOf<TrackerStatus?>(null) }

    val trackersById = remember(trackers) { trackers.associate { it.tracker.id to it.tracker } }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = if (embedded) null else "Trackers",
            subtitle = if (trackers.isEmpty()) "Nothing tracked yet" else "${trackers.size} active",
            actionIcon = Icons.Default.Add,
            actionLabel = "New tracker",
            onAction = { showTrackerDialog = true }
        )

        if (trackers.isEmpty() && entries.isEmpty()) {
            EmptyState(
                title = "No trackers",
                subtitle = "Say \"I bought chips for 2 euros\" and one appears by itself."
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(trackers, key = { it.tracker.id }) { status ->
                    TrackerCard(
                        status = status,
                        onAddEntry = { entryTarget = status },
                        onDelete = { onDeleteTracker(status.tracker.id) }
                    )
                }

                if (entries.isNotEmpty()) {
                    item {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            SectionLabel("Recent activity")
                        }
                    }
                    items(entries, key = { "entry-${it.id}" }) { entry ->
                        EntryRow(
                            entry = entry,
                            tracker = trackersById[entry.trackerId],
                            onDelete = { onDeleteEntry(entry.id) }
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showTrackerDialog) {
        TrackerDialog(
            defaultCurrency = defaultCurrency,
            onDismiss = { showTrackerDialog = false },
            onConfirm = {
                onSaveTracker(it)
                showTrackerDialog = false
            }
        )
    }

    entryTarget?.let { target ->
        EntryDialog(
            tracker = target.tracker,
            onDismiss = { entryTarget = null },
            onConfirm = { amount, direction, note ->
                onAddEntry(target.tracker.id, amount, direction, note)
                entryTarget = null
            }
        )
    }
}

@Composable
private fun TrackerCard(
    status: TrackerStatus,
    onAddEntry: () -> Unit,
    onDelete: () -> Unit
) {
    val tracker = status.tracker
    JarvisCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tracker.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onAddEntry, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add entry",
                        tint = Accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete tracker",
                        tint = TextFaint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val headline = status.balance ?: status.budgetLeft ?: status.periodSpent
            val headlineLabel = when {
                status.balance != null -> "left"
                status.budgetLeft != null -> "budget left"
                else -> "used this ${Tracker.periodWord(tracker.period)}"
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    format(headline, tracker),
                    style = MaterialTheme.typography.displayLarge,
                    color = if (headline < 0) Negative else TextPrimary
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    headlineLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            status.tracker.budget?.let { budget ->
                Spacer(Modifier.height(10.dp))
                val fraction = if (budget > 0) {
                    (status.periodSpent / budget).coerceIn(0.0, 1.0).toFloat()
                } else {
                    0f
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Hairline)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (fraction >= 1f) Negative else Accent)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "${format(status.periodSpent, tracker)} used this " +
                    "${Tracker.periodWord(tracker.period)} · ${status.entryCount} entries",
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint
            )
        }
    }
}

@Composable
private fun EntryRow(entry: Entry, tracker: Tracker?, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.note ?: tracker?.label ?: "Entry",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                "${tracker?.label ?: "unknown"} · ${TimeUtil.relative(entry.occurredAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint
            )
        }
        Text(
            (if (entry.direction == Entry.DIR_OUT) "−" else "+") + format(entry.amount, tracker),
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.direction == Entry.DIR_OUT) TextPrimary else Positive
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete entry",
                tint = TextFaint,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun TrackerDialog(
    defaultCurrency: String,
    onDismiss: () -> Unit,
    onConfirm: (Tracker) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(defaultCurrency) }
    var kind by remember { mutableStateOf(Tracker.KIND_MONEY) }
    var period by remember { mutableStateOf(Tracker.PERIOD_MONTHLY) }
    var budget by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tracker") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name, e.g. groceries") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("Unit, e.g. EUR or kcal") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    label = { Text("Starting balance (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = budget,
                    onValueChange = { budget = it },
                    label = { Text("Budget per period (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Picker("Kind", kind, Tracker.ALL_KINDS, { kind = it })
                Picker("Resets", period, Tracker.ALL_PERIODS, { period = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        Tracker(
                            name = name.trim(),
                            label = name.trim().replaceFirstChar {
                                it.titlecase(Locale.getDefault())
                            },
                            kind = kind,
                            unit = unit.trim(),
                            budget = budget.toDoubleOrNull(),
                            period = period,
                            startingBalance = balance.toDoubleOrNull()
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EntryDialog(
    tracker: Tracker,
    onDismiss: () -> Unit,
    onConfirm: (Double, String, String?) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(Entry.DIR_OUT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tracker.label) },
        text = {
            Column {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount in ${tracker.unit}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("What for?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Picker(
                    label = "Direction",
                    value = direction,
                    options = listOf(Entry.DIR_OUT, Entry.DIR_IN),
                    onSelect = { direction = it },
                    display = { if (it == Entry.DIR_OUT) "Spent / used" else "Added / received" }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    amount.toDoubleOrNull()?.let {
                        onConfirm(it, direction, note.takeIf { n -> n.isNotBlank() })
                    }
                },
                enabled = amount.toDoubleOrNull() != null
            ) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun format(value: Double, tracker: Tracker?): String {
    val text = if (tracker?.kind == Tracker.KIND_MONEY) {
        String.format(Locale.US, "%.2f", value)
    } else if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value)
    }
    val unit = tracker?.unit.orEmpty()
    return if (unit.isBlank()) text else "$text $unit"
}
