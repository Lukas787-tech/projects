package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.ui.components.EmptyState
import com.lukas.jarvis.ui.components.JarvisCard
import com.lukas.jarvis.ui.components.Picker
import com.lukas.jarvis.ui.components.SectionLabel
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Negative
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary

@Composable
fun TasksScreen(
    tasks: List<Task>,
    onAdd: (String, Long?, String, String?) -> Unit,
    onToggle: (Task) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false
) {
    var showAdd by remember { mutableStateOf(false) }
    val open = remember(tasks) { tasks.filter { !it.done } }
    val done = remember(tasks) { tasks.filter { it.done }.take(30) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = if (embedded) null else "Tasks",
            subtitle = if (open.isEmpty()) "All clear" else "${open.size} open",
            actionIcon = Icons.Default.Add,
            actionLabel = "New task",
            onAction = { showAdd = true }
        )

        if (tasks.isEmpty()) {
            EmptyState(
                title = "No tasks",
                subtitle = "Say \"remind me to call mum tomorrow at six\"."
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(open, key = { it.id }) { task ->
                    TaskRow(task, { onToggle(task) }, { onDelete(task.id) })
                }
                if (done.isNotEmpty()) {
                    item {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            SectionLabel("Done")
                        }
                    }
                    items(done, key = { "done-${it.id}" }) { task ->
                        TaskRow(task, { onToggle(task) }, { onDelete(task.id) })
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showAdd) {
        AddTaskDialog(
            onDismiss = { showAdd = false },
            onConfirm = { title, dueAt, repeat, notes ->
                onAdd(title, dueAt, repeat, notes)
                showAdd = false
            }
        )
    }
}

@Composable
private fun TaskRow(task: Task, onToggle: () -> Unit, onDelete: () -> Unit) {
    val overdue = task.dueAt != null && !task.done && task.dueAt < System.currentTimeMillis()
    JarvisCard {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = task.done,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Accent,
                    uncheckedColor = TextFaint
                )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.done) TextFaint else TextPrimary,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null
                )
                task.dueAt?.let { due ->
                    Text(
                        TimeUtil.format(due) + " · " + TimeUtil.relative(due) +
                            if (task.repeatRule != Task.REPEAT_NONE) " · ${task.repeatRule}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overdue) Negative else TextSecondary
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = TextFaint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Long?, String, String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf(Task.REPEAT_NONE) }
    var notes by remember { mutableStateOf("") }

    val parsedDue = remember(due) { TimeUtil.parse(due.takeIf { it.isNotBlank() }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New task") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What needs doing?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = due,
                    onValueChange = { due = it },
                    label = { Text("When? e.g. tomorrow, +2h, 2026-09-20 18:00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    supportingText = {
                        Text(
                            parsedDue?.let { "Reminder at ${TimeUtil.format(it)}" }
                                ?: if (due.isBlank()) "No reminder" else "Could not read that time",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (due.isNotBlank() && parsedDue == null) Negative else TextFaint
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Picker("Repeat", repeat, Task.ALL_REPEATS, { repeat = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, parsedDue, repeat, notes.takeIf { it.isNotBlank() }) },
                enabled = title.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
