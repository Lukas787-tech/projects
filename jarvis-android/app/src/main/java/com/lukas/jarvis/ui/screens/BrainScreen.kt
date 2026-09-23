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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lukas.jarvis.ui.components.GlassDialog
import com.lukas.jarvis.ui.components.GlassField
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.ui.components.EmptyState
import com.lukas.jarvis.ui.components.JarvisCard
import com.lukas.jarvis.ui.components.Picker
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary

/** Everything Jarvis has stored, newest first, with a way to correct it. */
@Composable
fun BrainScreen(
    memories: List<Memory>,
    onAdd: (String, String, List<String>, Int) -> Unit,
    onDelete: (Long) -> Unit,
    onTogglePin: (Memory) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false
) {
    var showAdd by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val filtered = remember(memories, query) {
        if (query.isBlank()) {
            memories
        } else {
            val needle = query.trim().lowercase()
            memories.filter {
                it.content.lowercase().contains(needle) ||
                    it.tags.any { tag -> tag.contains(needle) }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = if (embedded) null else "Memory",
            subtitle = "${memories.size} stored",
            actionIcon = Icons.Default.Add,
            actionLabel = "Add memory",
            onAction = { showAdd = true }
        )

        GlassField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search memory",
            leadingIcon = Icons.Default.Search,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            EmptyState(
                title = if (memories.isEmpty()) "Nothing stored yet" else "No matches",
                subtitle = if (memories.isEmpty()) {
                    "Tell Jarvis something and it lands here."
                } else {
                    "Try different words."
                }
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { memory ->
                    MemoryRow(
                        memory = memory,
                        onDelete = { onDelete(memory.id) },
                        onTogglePin = { onTogglePin(memory) }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showAdd) {
        AddMemoryDialog(
            onDismiss = { showAdd = false },
            onConfirm = { content, kind, tags, importance ->
                onAdd(content, kind, tags, importance)
                showAdd = false
            }
        )
    }
}

@Composable
private fun MemoryRow(memory: Memory, onDelete: () -> Unit, onTogglePin: () -> Unit) {
    JarvisCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                memory.content,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${memory.kind} · ${TimeUtil.relative(memory.createdAt)}" +
                        if (memory.tags.isEmpty()) "" else " · ${memory.tags.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextFaint,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Pin",
                        tint = if (memory.pinned) Accent else TextFaint,
                        modifier = Modifier.size(16.dp)
                    )
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
}

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, List<String>, Int) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Memory.KIND_FACT) }
    var tags by remember { mutableStateOf("") }

    GlassDialog(
        title = "New memory",
        onDismiss = onDismiss,
        confirmLabel = "Save",
        confirmEnabled = content.isNotBlank(),
        onConfirm = {
            onConfirm(
                content,
                kind,
                tags.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                3
            )
        }
    ) {
        GlassField(
            value = content,
            onValueChange = { content = it },
            label = "What should I remember?",
            singleLine = false
        )
        GlassField(value = tags, onValueChange = { tags = it }, label = "Tags, comma separated")
        Picker(
            label = "Kind",
            value = kind,
            options = Memory.ALL_KINDS,
            onSelect = { kind = it }
        )
    }
}

/**
 * Shared header used by every list screen.
 *
 * The title is optional because a screen shown inside the hub already has its
 * name on the segment above it, and printing it twice wastes the top of a phone
 * screen on saying the same word twice.
 */
@Composable
fun ScreenHeader(
    title: String?,
    subtitle: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (title == null) 4.dp else 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextSecondary
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                // displayLarge is reserved for figures now — a balance, a count —
                // so a screen title sits one step down the scale.
                Text(title, style = MaterialTheme.typography.displayMedium, color = TextPrimary)
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        if (actionIcon != null && onAction != null) {
            IconButton(onClick = onAction) {
                Icon(actionIcon, contentDescription = actionLabel, tint = Accent)
            }
        }
    }
}
