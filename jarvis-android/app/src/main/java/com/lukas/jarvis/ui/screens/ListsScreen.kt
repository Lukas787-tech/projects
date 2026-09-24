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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.data.ListBook
import com.lukas.jarvis.data.ListItem
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.EmptyState
import com.lukas.jarvis.ui.components.GlassDialog
import com.lukas.jarvis.ui.components.GlassField
import com.lukas.jarvis.ui.components.JarvisCard
import com.lukas.jarvis.ui.components.SectionLabel
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary

/**
 * The user's lists: one at a time, picked from a row of names, with the open
 * items first and what is ticked off below. Everything here is also a sentence
 * away — "add bread to the shopping list" — and both write the same book.
 */
@Composable
fun ListsScreen(
    book: ListBook,
    onAdd: (list: String, item: String) -> Unit,
    onCheck: (list: String, item: String, done: Boolean) -> Unit,
    onRemove: (list: String, item: String) -> Unit,
    onClearDone: (list: String) -> Unit,
    onDeleteList: (list: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    val names = book.lists.map { it.name }
    // A list deleted by voice while it was open falls back to the first one.
    val active = chosen?.takeIf { it in names } ?: names.firstOrNull()
    val list = active?.let { book.find(it) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = null,
            subtitle = if (names.isEmpty()) "No lists yet" else "${names.size} list${if (names.size == 1) "" else "s"}",
            actionIcon = Icons.AutoMirrored.Filled.PlaylistAdd,
            actionLabel = "New list",
            onAction = { creating = true }
        )

        if (list == null) {
            EmptyState(
                title = "No lists yet",
                subtitle = "Say \"add oat milk to the shopping list\", or start one with the button above."
            )
        } else {
            if (names.size > 1) {
                ChoiceChips(
                    options = names,
                    selected = list.name,
                    display = { name ->
                        val open = book.find(name)?.open?.size ?: 0
                        name.replaceFirstChar { it.uppercase() } + if (open > 0) "  $open" else ""
                    },
                    onSelect = { chosen = it }
                )
                Spacer(Modifier.height(12.dp))
            }

            GlassField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "Add to ${list.name}",
                leadingIcon = Icons.Default.Add,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (draft.isNotBlank()) onAdd(list.name, draft.trim())
                    draft = ""
                }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            val open = list.items.filter { !it.done }
            val done = list.items.filter { it.done }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                if (list.items.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Nothing on it. Add something above, or say it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextFaint
                        )
                    }
                }
                items(open, key = { "open-" + it.text.lowercase() }) { item ->
                    ItemRow(item, { onCheck(list.name, item.text, it) }, { onRemove(list.name, item.text) })
                }
                if (done.isNotEmpty()) {
                    item(key = "done-label") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Got it", modifier = Modifier.weight(1f))
                            IconButton(onClick = { onClearDone(list.name) }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear ticked", tint = TextFaint)
                            }
                        }
                    }
                    items(done, key = { "done-" + it.text.lowercase() }) { item ->
                        ItemRow(item, { onCheck(list.name, item.text, it) }, { onRemove(list.name, item.text) })
                    }
                }
                item(key = "delete") {
                    Spacer(Modifier.height(8.dp))
                    ChipButton(
                        label = "Delete the ${list.name} list",
                        icon = Icons.Default.Close,
                        onClick = { onDeleteList(list.name) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        GlassDialog(
            title = "New list",
            onDismiss = { creating = false },
            confirmLabel = "Create",
            confirmEnabled = name.isNotBlank(),
            onConfirm = {
                chosen = ListBook.canonical(name)
                onAdd(name.trim(), "")
                creating = false
            }
        ) {
            GlassField(value = name, onValueChange = { name = it }, label = "What is it for? Shopping, packing…")
        }
    }
}

@Composable
private fun ItemRow(item: ListItem, onCheck: (Boolean) -> Unit, onRemove: () -> Unit) {
    JarvisCard {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = item.done,
                onCheckedChange = onCheck,
                colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = TextFaint)
            )
            Text(
                item.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.done) TextFaint else TextPrimary,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove", tint = TextFaint, modifier = Modifier.size(16.dp))
            }
        }
    }
}
