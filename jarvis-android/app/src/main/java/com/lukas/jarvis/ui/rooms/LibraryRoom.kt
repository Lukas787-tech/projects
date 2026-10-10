package com.lukas.jarvis.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.ListBook
import com.lukas.jarvis.data.Recurring
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.notify.PlaceWatch
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Mochi
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.character.Prop
import com.lukas.jarvis.ui.kit.ButtonKind
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.kit.CafeSheet
import com.lukas.jarvis.ui.kit.CafeTextField
import com.lukas.jarvis.ui.kit.CheckRow
import com.lukas.jarvis.ui.kit.ChoiceChip
import com.lukas.jarvis.ui.kit.EmptyState
import com.lukas.jarvis.ui.kit.Eyebrow
import com.lukas.jarvis.ui.kit.IconCircle
import com.lukas.jarvis.ui.kit.ListRow
import com.lukas.jarvis.ui.kit.PaperCard
import com.lukas.jarvis.ui.kit.QuietButton
import com.lukas.jarvis.ui.kit.SectionHeader
import com.lukas.jarvis.ui.kit.VSpace
import com.lukas.jarvis.ui.kit.ValueRow
import com.lukas.jarvis.ui.kit.Wrap
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.Elevation
import com.lukas.jarvis.ui.theme.paper
import kotlinx.coroutines.delay

/** The shelves of the Library. */
enum class Shelf(val label: String) { Memory("Memory"), Notes("Notes"), Lists("Lists"), Money("Money"), Tasks("Tasks") }

/** Everything the Library can change, by hand. */
class LibraryActions(
    val onBack: () -> Unit,
    val onShelf: (Shelf) -> Unit,
    val onType: (String) -> Unit,
    val addMemory: (content: String, kind: String) -> Unit,
    val updateMemory: (Memory) -> Unit,
    val deleteMemory: (Long) -> Unit,
    val togglePin: (Memory) -> Unit,
    val addToList: (list: String, item: String) -> Unit,
    val checkItem: (list: String, item: String, done: Boolean) -> Unit,
    val removeItem: (list: String, item: String) -> Unit,
    val clearDone: (list: String) -> Unit,
    val deleteList: (list: String) -> Unit,
    val saveTracker: (Tracker) -> Unit,
    val deleteTracker: (Long) -> Unit,
    val addEntry: (trackerId: Long, amount: Double, direction: String, note: String?) -> Unit,
    val deleteEntry: (Long) -> Unit,
    val addTask: (title: String, dueAt: Long?, repeat: String, notes: String?) -> Unit,
    val toggleTask: (Task) -> Unit,
    val deleteTask: (Long) -> Unit,
    val cancelPlaceReminder: (Long) -> Unit,
    /** One tap on a habit: done for today. */
    val didHabit: (Long) -> Unit = {},
    /** Ends something that logs itself; what it logged stays. */
    val stopRepeat: (Long) -> Unit = {},
    /** A memory upkeep put away, brought back; or all of them; or the list let go. */
    val bringBack: (Long) -> Unit = {},
    val bringAllBack: () -> Unit = {},
    val keepTidy: () -> Unit = {}
)

/** Something just removed, and how to put it back. */
private class Undoable(val said: String, val restore: () -> Unit)

/**
 * The Library: what Mochi remembers, your notes and journal, your lists, the
 * money and anything else you count, and your reminders. Everything can be
 * changed by hand here as well as by asking, and anything removed can be put
 * back from the bar that appears.
 */
@Composable
fun LibraryRoom(
    shelf: Shelf,
    memories: List<Memory>,
    lists: ListBook,
    trackers: List<TrackerStatus>,
    entries: List<Entry>,
    tasks: List<Task>,
    placeReminders: List<PlaceWatch>,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    defaultCurrency: String = "EUR",
    streaks: Map<Long, com.lukas.jarvis.data.Streak> = emptyMap(),
    repeats: List<Recurring> = emptyList(),
    tidied: List<Pair<com.lukas.jarvis.data.Tidied, Memory>> = emptyList()
) {
    var undo by remember { mutableStateOf<Undoable?>(null) }
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(6_000)
            undo = null
        }
    }
    val removed: (Undoable) -> Unit = { undo = it }

    Box(modifier.fillMaxSize()) {
        RoomScaffold(
            title = "Library",
            subtitle = "Everything Mochi keeps for you",
            onBack = actions.onBack,
            mochi = CharacterState(Mood.Idle, description = "Mochi, in the Library"),
            header = {
                // Wrapped, not scrolled sideways: in one row the last shelf sat
                // half past the edge of a narrow phone.
                Wrap(Modifier.fillMaxWidth()) {
                    Shelf.values().forEach { s -> ChoiceChip(s.label, s == shelf, { actions.onShelf(s) }) }
                }
            }
        ) {
            when (shelf) {
                Shelf.Memory -> memoryShelf(memories.filter { it.kind != Memory.KIND_NOTE && it.kind != Memory.KIND_JOURNAL }, actions, removed, tidied)
                Shelf.Notes -> notesShelf(memories.filter { it.kind == Memory.KIND_NOTE || it.kind == Memory.KIND_JOURNAL }, actions, removed)
                Shelf.Lists -> listShelf(lists, actions, removed)
                Shelf.Money -> moneyShelf(trackers, entries, actions, removed, defaultCurrency, streaks, repeats)
                Shelf.Tasks -> taskShelf(tasks, placeReminders, actions, removed)
            }
        }
        undo?.let { pending ->
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(Cafe.space.l)
                    .fillMaxWidth()
                    .paper(Elevation.Lifted, Cafe.shape.large, Cafe.colors)
                    .clip(Cafe.shape.large)
                    .background(Cafe.colors.espresso)
                    .padding(horizontal = Cafe.space.l, vertical = Cafe.space.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(pending.said, style = Cafe.type.bodySmall, color = Cafe.colors.foam, modifier = Modifier.weight(1f))
                CafeButton("Undo", {
                    pending.restore()
                    undo = null
                }, kind = ButtonKind.Quiet)
            }
        }
    }
}

// ------------------------------------------------------------------ memory

private fun LazyListScope.memoryShelf(memories: List<Memory>, actions: LibraryActions, removed: (Undoable) -> Unit, tidied: List<Pair<com.lukas.jarvis.data.Tidied, Memory>> = emptyList()) {
    if (tidied.isNotEmpty()) item(key = "memory-tidied") { TidiedCard(tidied, actions) }
    item(key = "memory-add") { AddMemory(actions, Memory.KIND_FACT, "Something to remember") }
    if (memories.isEmpty()) {
        item(key = "memory-empty") {
            EmptyState(
                title = "Nothing remembered yet",
                body = "Tell Mochi a door code, a size, a preference — it keeps it here and finds it again.",
                action = "Tell Mochi something",
                onAction = { actions.onType("Remember that ") },
                art = { Mochi(CharacterState(Mood.Idle), size = 72.dp) }
            )
        }
        return
    }
    val pinned = memories.filter { it.pinned }
    if (pinned.isNotEmpty()) item(key = "memory-pinned") { Eyebrow("Always at hand") }
    items(pinned + memories.filterNot { it.pinned }, key = { "memory:${it.id}" }) { memory ->
        MemoryRow(memory, actions, removed)
    }
}

@Composable
private fun MemoryRow(memory: Memory, actions: LibraryActions, removed: (Undoable) -> Unit) {
    var editing by rememberSaveable(memory.id) { mutableStateOf(false) }
    PaperCard(Modifier.fillMaxWidth(), padding = Cafe.space.m, onClick = { editing = true }, clickLabel = "Change this memory") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(memory.content, style = Cafe.type.body, color = Cafe.colors.espresso)
                Text("${memory.kind.replaceFirstChar { it.titlecase() }} · ${TimeUtil.relative(memory.createdAt)}", style = Cafe.type.caption, color = Cafe.colors.cocoa)
            }
            IconCircle(
                Icons.Rounded.PushPin,
                if (memory.pinned) "Unpin this memory" else "Keep this memory always at hand",
                { actions.togglePin(memory) },
                size = 40.dp,
                filled = memory.pinned,
                tint = if (memory.pinned) null else Cafe.colors.cocoa
            )
        }
    }
    if (editing) {
        var text by rememberSaveable(memory.id) { mutableStateOf(memory.content) }
        CafeSheet("Memory", onDismiss = { editing = false }) {
            CafeTextField(text, { text = it }, label = "What Mochi remembers", singleLine = false, minLines = 3, modifier = Modifier.fillMaxWidth())
            VSpace(Cafe.space.m)
            Wrap {
                CafeButton("Save", {
                    actions.updateMemory(memory.copy(content = text.trim(), updatedAt = System.currentTimeMillis()))
                    editing = false
                }, enabled = text.isNotBlank())
                CafeButton("Forget", {
                    actions.deleteMemory(memory.id)
                    removed(Undoable("Forgotten.") { actions.addMemory(memory.content, memory.kind) })
                    editing = false
                }, kind = ButtonKind.Danger, icon = Icons.Rounded.DeleteOutline)
                QuietButton("Cancel", { editing = false })
            }
        }
    }
}

@Composable
private fun AddMemory(actions: LibraryActions, kind: String, placeholder: String) {
    var text by rememberSaveable(kind) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CafeTextField(
            text, { text = it },
            placeholder = placeholder,
            imeAction = ImeAction.Done,
            onDone = {
                if (text.isNotBlank()) {
                    actions.addMemory(text.trim(), kind)
                    text = ""
                }
            },
            modifier = Modifier.weight(1f)
        )
        IconCircle(Icons.Rounded.Add, "Add", {
            if (text.isNotBlank()) {
                actions.addMemory(text.trim(), kind)
                text = ""
            }
        }, filled = true, enabled = text.isNotBlank(), modifier = Modifier.padding(start = Cafe.space.s))
    }
}

// ------------------------------------------------------------------- notes

private fun LazyListScope.notesShelf(notes: List<Memory>, actions: LibraryActions, removed: (Undoable) -> Unit) {
    item(key = "notes-add") { AddMemory(actions, Memory.KIND_NOTE, "Write a note") }
    if (notes.isEmpty()) {
        item(key = "notes-empty") {
            EmptyState(
                title = "No notes yet",
                body = "Notes and your journal live here — “dear diary…” works too, by voice.",
                action = "Write in the journal",
                onAction = { actions.onType("Journal: ") },
                art = { Mochi(CharacterState(Mood.Working, Prop.Notepad), size = 72.dp) }
            )
        }
        return
    }
    items(notes.sortedByDescending { it.createdAt }, key = { "note:${it.id}" }) { note -> MemoryRow(note, actions, removed) }
}

// ------------------------------------------------------------------- lists

private fun LazyListScope.listShelf(book: ListBook, actions: LibraryActions, removed: (Undoable) -> Unit) {
    item(key = "lists-new") {
        var name by rememberSaveable { mutableStateOf("") }
        var item by rememberSaveable { mutableStateOf("") }
        PaperCard(Modifier.fillMaxWidth(), tone = com.lukas.jarvis.ui.kit.Tone.Latte, padding = Cafe.space.m) {
            Eyebrow("A new list, or something on one")
            VSpace(Cafe.space.xs)
            CafeTextField(name, { name = it }, placeholder = "Which list? (shopping, packing…)", modifier = Modifier.fillMaxWidth())
            VSpace(Cafe.space.xs)
            CafeTextField(item, { item = it }, placeholder = "What goes on it", modifier = Modifier.fillMaxWidth(), onDone = {
                if (name.isNotBlank() && item.isNotBlank()) {
                    actions.addToList(name.trim(), item.trim())
                    item = ""
                }
            })
            VSpace(Cafe.space.xs)
            CafeButton("Add", {
                actions.addToList(name.trim(), item.trim())
                item = ""
            }, enabled = name.isNotBlank() && item.isNotBlank(), icon = Icons.Rounded.Add)
        }
    }
    if (book.lists.isEmpty()) {
        item(key = "lists-empty") {
            EmptyState(
                title = "No lists yet",
                body = "Shopping, packing, anything without a time.",
                action = "Start a shopping list",
                onAction = { actions.onType("Add to my shopping list: ") },
                art = { Mochi(CharacterState(Mood.Idle), size = 72.dp) }
            )
        }
        return
    }
    items(book.lists, key = { "list:${it.name}" }) { list ->
        var adding by rememberSaveable(list.name) { mutableStateOf("") }
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader(list.name.replaceFirstChar { it.titlecase() }, action = if (list.items.any { it.done }) "Clear done" else null, onAction = { actions.clearDone(list.name) })
            if (list.items.isEmpty()) Text("Empty for now.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            val row: @Composable (com.lukas.jarvis.data.ListItem) -> Unit = { entry ->
                CheckRow(
                    text = entry.text,
                    checked = entry.done,
                    onCheckedChange = { actions.checkItem(list.name, entry.text, it) },
                    trailing = {
                        IconCircle(Icons.Rounded.DeleteOutline, "Take ${entry.text} off the list", {
                            actions.removeItem(list.name, entry.text)
                            removed(Undoable("Took ${entry.text} off.") { actions.addToList(list.name, entry.text) })
                        }, size = 36.dp, tint = Cafe.colors.cocoa)
                    }
                )
            }
            val shop = com.lukas.jarvis.data.Aisle.suits(list.name) && list.open.size >= 3
            var walking by rememberSaveable(list.name) { mutableStateOf(true) }
            if (shop) {
                Wrap {
                    ChoiceChip("In shop order", walking, { walking = true })
                    ChoiceChip("As added", !walking, { walking = false })
                }
            }
            if (shop && walking) {
                // Fruit and veg first, the freezer near the end: one walk round, nothing doubled back for.
                com.lukas.jarvis.data.Aisle.inShopOrder(list.open).forEach { (aisle, open) ->
                    Eyebrow(aisle.label, Modifier.padding(top = Cafe.space.xs))
                    open.forEach { row(it) }
                }
                list.items.filter { it.done }.takeIf { it.isNotEmpty() }?.let { done ->
                    Eyebrow("In the basket", Modifier.padding(top = Cafe.space.xs))
                    done.forEach { row(it) }
                }
            } else {
                list.items.sortedBy { it.done }.forEach { row(it) }
            }
            VSpace(Cafe.space.xs)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CafeTextField(adding, { adding = it }, placeholder = "Add to ${list.name}", modifier = Modifier.weight(1f), onDone = {
                    if (adding.isNotBlank()) {
                        actions.addToList(list.name, adding.trim())
                        adding = ""
                    }
                })
                IconCircle(Icons.Rounded.Add, "Add to ${list.name}", {
                    if (adding.isNotBlank()) {
                        actions.addToList(list.name, adding.trim())
                        adding = ""
                    }
                }, filled = true, enabled = adding.isNotBlank(), modifier = Modifier.padding(start = Cafe.space.s))
            }
            if (com.lukas.jarvis.data.Aisle.suits(list.name)) {
                QuietButton("Show it when I get to the shop", { actions.onType("Show my shopping list when I get to ") }, icon = Icons.Rounded.Place)
            }
            QuietButton("Delete the list", {
                val items = list.items.map { it.text }
                actions.deleteList(list.name)
                removed(Undoable("Deleted the ${list.name} list.") { items.forEach { actions.addToList(list.name, it) } })
            }, icon = Icons.Rounded.DeleteOutline)
        }
    }
}

// ------------------------------------------------------------------- money

private fun LazyListScope.moneyShelf(trackers: List<TrackerStatus>, entries: List<Entry>, actions: LibraryActions, removed: (Undoable) -> Unit, currency: String, streaks: Map<Long, com.lukas.jarvis.data.Streak>, repeats: List<Recurring>) {
    item(key = "money-new") {
        var creating by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (trackers.isNotEmpty()) QuietButton("Something that repeats", { actions.onType("Every month on the 1st: ") }, icon = Icons.Rounded.Autorenew)
            QuietButton("New tracker", { creating = true }, icon = Icons.Rounded.Add)
        }
        if (creating) NewTrackerSheet(currency, onDismiss = { creating = false }, onCreate = {
            actions.saveTracker(it)
            creating = false
        })
    }
    if (trackers.isEmpty()) {
        item(key = "money-empty") {
            EmptyState(
                title = "Nothing counted yet",
                body = "Spending, a budget, calories, kilometres — say what happened and Mochi keeps the total, exactly.",
                action = "Log something",
                onAction = { actions.onType("I spent ") },
                art = { Mochi(CharacterState(Mood.Working, Prop.Coins), size = 72.dp) },
                secondary = "Set a budget",
                onSecondary = { actions.onType("Set a weekly budget of ") }
            )
        }
        return
    }
    items(trackers, key = { "tracker:${it.tracker.id}" }) { status ->
        TrackerCard(status, entries.filter { it.trackerId == status.tracker.id }, actions, removed, streaks[status.tracker.id], repeats.filter { it.trackerId == status.tracker.id })
    }
}

/**
 * What memory upkeep put away overnight: each with why, and a way to bring
 * it back; or all of them; or "That's fine", which lets the list go and
 * leaves them put away.
 */
@Composable
private fun TidiedCard(tidied: List<Pair<com.lukas.jarvis.data.Tidied, Memory>>, actions: LibraryActions) {
    PaperCard(Modifier.fillMaxWidth(), tone = com.lukas.jarvis.ui.kit.Tone.Latte) {
        SectionHeader("Tidied up overnight")
        Text(
            "Put away, not deleted: ${tidied.size} ${if (tidied.size == 1) "memory" else "memories"} that only got in the way.",
            style = Cafe.type.bodySmall,
            color = Cafe.colors.cocoa
        )
        VSpace(Cafe.space.xs)
        tidied.take(8).forEach { (t, memory) ->
            ListRow(
                title = memory.content,
                subtitle = t.reason.said.replaceFirstChar { it.titlecase() },
                trailing = { QuietButton("Bring back", { actions.bringBack(memory.id) }) }
            )
        }
        if (tidied.size > 8) Text("and ${tidied.size - 8} more", style = Cafe.type.caption, color = Cafe.colors.cocoa)
        VSpace(Cafe.space.s)
        Wrap {
            CafeButton("That's fine", actions.keepTidy)
            CafeButton("Bring them all back", actions.bringAllBack, kind = ButtonKind.Secondary)
        }
    }
}

/** One thing that logs itself: what, how much, how often, when next, and a way to stop it. */
@Composable
private fun RepeatRow(rule: Recurring, t: Tracker, actions: LibraryActions) {
    var stopping by remember { mutableStateOf(false) }
    val what = rule.note ?: t.label
    ListRow(
        title = "$what · ${if (rule.direction == Entry.DIR_IN) "+" else "−"}${fmt(rule.amount)} ${t.unit}",
        subtitle = "${Recurring.word(rule.every).replaceFirstChar { it.titlecase() }} · next ${TimeUtil.formatDate(rule.nextAt)}",
        icon = Icons.Rounded.Autorenew,
        trailing = { IconCircle(Icons.Rounded.Close, "Stop $what repeating", { stopping = true }, size = 40.dp, tint = Cafe.colors.cocoa) }
    )
    if (stopping) {
        CafeSheet("Stop $what?", onDismiss = { stopping = false }) {
            Text("Nothing more is logged by itself. What it already logged stays on ${t.label}.", style = Cafe.type.body, color = Cafe.colors.espresso)
            VSpace(Cafe.space.m)
            Wrap {
                CafeButton("Stop it", { actions.stopRepeat(rule.id); stopping = false }, kind = ButtonKind.Danger)
                CafeButton("Keep it", { stopping = false }, kind = ButtonKind.Secondary)
            }
        }
    }
}

@Composable
private fun TrackerCard(status: TrackerStatus, entries: List<Entry>, actions: LibraryActions, removed: (Undoable) -> Unit, streak: com.lukas.jarvis.data.Streak? = null, repeats: List<Recurring> = emptyList()) {
    val t = status.tracker
    var amount by rememberSaveable(t.id) { mutableStateOf("") }
    var note by rememberSaveable(t.id) { mutableStateOf("") }
    var editing by rememberSaveable(t.id) { mutableStateOf(false) }
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader(t.label, action = "Budget", onAction = { editing = true })
        if (com.lukas.jarvis.data.Streaks.isHabit(t)) {
            HabitLine("Today", streak, { actions.didHabit(t.id) })
            VSpace(Cafe.space.xs)
        }
        status.budgetLeft?.let { ValueRow("Left ${Tracker.thisPeriod(t.period)}", "${fmt(it)} ${t.unit}", emphasise = true) }
        status.balance?.let { ValueRow("Balance", "${fmt(it)} ${t.unit}", emphasise = status.budgetLeft == null) }
        ValueRow("Used ${Tracker.thisPeriod(t.period)}", "${fmt(status.periodSpent)} ${t.unit}")
        t.budget?.takeIf { it > 0 }?.let { budget ->
            val used = (status.periodSpent / budget).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(8.dp).clip(Cafe.shape.pill).background(Cafe.colors.latte)) {
                Box(Modifier.fillMaxWidth(used).height(8.dp).clip(Cafe.shape.pill).background(if (used > 0.9f) Cafe.colors.berry else Cafe.colors.sage))
            }
        }
        VSpace(Cafe.space.s)
        if (repeats.isNotEmpty()) {
            Eyebrow("Logs itself")
            repeats.forEach { RepeatRow(it, t, actions) }
            VSpace(Cafe.space.xs)
        }
        entries.take(6).forEach { entry ->
            ListRow(
                title = "${if (entry.direction == Entry.DIR_IN) "+" else "−"}${fmt(entry.amount)} ${t.unit}",
                subtitle = listOfNotNull(entry.note, TimeUtil.relative(entry.occurredAt)).joinToString(" · "),
                trailing = {
                    IconCircle(Icons.Rounded.DeleteOutline, "Take this entry back", {
                        actions.deleteEntry(entry.id)
                        removed(Undoable("Taken back.") { actions.addEntry(t.id, entry.amount, entry.direction, entry.note) })
                    }, size = 36.dp, tint = Cafe.colors.cocoa)
                }
            )
        }
        VSpace(Cafe.space.xs)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Cafe.space.s)) {
            CafeTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, placeholder = "Amount", modifier = Modifier.weight(0.4f))
            CafeTextField(note, { note = it }, placeholder = "What for", modifier = Modifier.weight(0.6f))
        }
        VSpace(Cafe.space.xs)
        val value = amount.replace(',', '.').toDoubleOrNull()
        Wrap {
            CafeButton("Spent", {
                actions.addEntry(t.id, value ?: 0.0, Entry.DIR_OUT, note.ifBlank { null })
                amount = ""; note = ""
            }, enabled = value != null && value > 0)
            CafeButton("Received", {
                actions.addEntry(t.id, value ?: 0.0, Entry.DIR_IN, note.ifBlank { null })
                amount = ""; note = ""
            }, kind = ButtonKind.Secondary, enabled = value != null && value > 0)
        }
    }
    if (editing) {
        var budget by rememberSaveable(t.id) { mutableStateOf(t.budget?.let(::fmt).orEmpty()) }
        var period by rememberSaveable(t.id) { mutableStateOf(t.period) }
        CafeSheet(t.label, onDismiss = { editing = false }) {
            CafeTextField(budget, { budget = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = "Budget (${t.unit})", placeholder = "No budget", modifier = Modifier.fillMaxWidth())
            VSpace(Cafe.space.s)
            Wrap {
                listOf(Tracker.PERIOD_DAILY to "Daily", Tracker.PERIOD_WEEKLY to "Weekly", Tracker.PERIOD_MONTHLY to "Monthly").forEach { (id, label) ->
                    ChoiceChip(label, period == id, { period = id })
                }
            }
            VSpace(Cafe.space.m)
            Wrap {
                CafeButton("Save", {
                    actions.saveTracker(t.copy(budget = budget.replace(',', '.').toDoubleOrNull(), period = period))
                    editing = false
                })
                QuietButton("Cancel", { editing = false })
            }
            VSpace(Cafe.space.l)
            // Deleting takes every entry with it, so it asks once more, right here.
            var sure by remember { mutableStateOf(false) }
            if (!sure) {
                QuietButton("Delete this tracker", { sure = true }, icon = Icons.Rounded.DeleteOutline)
            } else {
                Text("${t.label} and all ${status.entryCount} of its entries go for good.", style = Cafe.type.bodySmall, color = Cafe.colors.espresso)
                VSpace(Cafe.space.s)
                Wrap {
                    CafeButton("Delete ${t.label}", {
                        actions.deleteTracker(t.id)
                        editing = false
                    }, kind = ButtonKind.Danger, icon = Icons.Rounded.DeleteOutline)
                    QuietButton("Keep it", { sure = false })
                }
            }
        }
    }
}

/** A new thing to count: money with a budget or a balance, or anything with a unit. */
@Composable
private fun NewTrackerSheet(currency: String, onDismiss: () -> Unit, onCreate: (Tracker) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(currency) }
    var money by rememberSaveable { mutableStateOf(true) }
    var period by rememberSaveable { mutableStateOf(Tracker.PERIOD_MONTHLY) }
    var budget by rememberSaveable { mutableStateOf("") }
    var balance by rememberSaveable { mutableStateOf("") }
    fun number(text: String) = text.replace(',', '.').toDoubleOrNull()
    CafeSheet("New tracker", onDismiss = onDismiss) {
        CafeTextField(name, { name = it }, label = "Name", placeholder = "Groceries", modifier = Modifier.fillMaxWidth())
        VSpace(Cafe.space.s)
        Wrap {
            ChoiceChip("Money", money, {
                money = true
                if (unit.isBlank()) unit = currency
            })
            ChoiceChip("Something else", !money, { money = false })
        }
        VSpace(Cafe.space.s)
        CafeTextField(unit, { unit = it }, label = "Unit", placeholder = if (money) currency else "kcal, km, glasses", modifier = Modifier.fillMaxWidth())
        VSpace(Cafe.space.s)
        Row(horizontalArrangement = Arrangement.spacedBy(Cafe.space.s)) {
            CafeTextField(budget, { budget = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = "Budget (optional)", placeholder = "None", modifier = Modifier.weight(1f))
            if (money) CafeTextField(balance, { balance = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = "Balance now (optional)", placeholder = "None", modifier = Modifier.weight(1f))
        }
        VSpace(Cafe.space.s)
        Wrap {
            listOf(Tracker.PERIOD_DAILY to "Resets daily", Tracker.PERIOD_WEEKLY to "Weekly", Tracker.PERIOD_MONTHLY to "Monthly").forEach { (id, label) ->
                ChoiceChip(label, period == id, { period = id })
            }
        }
        VSpace(Cafe.space.l)
        CafeButton("Create", {
            onCreate(
                Tracker(
                    name = name.trim().lowercase(),
                    label = name.trim().replaceFirstChar { it.titlecase() },
                    kind = if (money) Tracker.KIND_MONEY else Tracker.KIND_COUNT,
                    unit = unit.trim().ifBlank { currency },
                    budget = number(budget),
                    period = period,
                    startingBalance = if (money) number(balance) else null
                )
            )
        }, enabled = name.isNotBlank())
    }
}

// ------------------------------------------------------------------- tasks

private fun LazyListScope.taskShelf(tasks: List<Task>, places: List<PlaceWatch>, actions: LibraryActions, removed: (Undoable) -> Unit) {
    item(key = "task-add") {
        var title by rememberSaveable { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CafeTextField(title, { title = it }, placeholder = "A reminder (say a time to Mochi for an alarm)", modifier = Modifier.weight(1f), onDone = {
                if (title.isNotBlank()) {
                    actions.addTask(title.trim(), null, Task.REPEAT_NONE, null)
                    title = ""
                }
            })
            IconCircle(Icons.Rounded.Add, "Add the reminder", {
                actions.addTask(title.trim(), null, Task.REPEAT_NONE, null)
                title = ""
            }, filled = true, enabled = title.isNotBlank(), modifier = Modifier.padding(start = Cafe.space.s))
        }
    }
    val open = tasks.filter { !it.done }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
    val done = tasks.filter { it.done }
    if (open.isEmpty() && places.isEmpty()) {
        item(key = "task-empty") {
            EmptyState(
                title = "Nothing open",
                body = "Reminders with real alarms, moved or cancelled just by saying so.",
                action = "Remind me to…",
                onAction = { actions.onType("Remind me to ") },
                art = { Mochi(CharacterState(Mood.Delighted), size = 72.dp) }
            )
        }
    }
    if (open.isNotEmpty()) {
        item(key = "task-open") {
            PaperCard(Modifier.fillMaxWidth()) {
                SectionHeader("Open")
                open.forEach { task -> TaskRow(task, actions, removed) }
            }
        }
    }
    if (places.isNotEmpty()) {
        item(key = "task-places") {
            PaperCard(Modifier.fillMaxWidth()) {
                SectionHeader("At places")
                places.forEach { watch ->
                    ListRow(
                        title = watch.text,
                        subtitle = (if (watch.leaving) "Leaving " else "Arriving at ") + watch.place + if (watch.every) " · every time" else "",
                        icon = Icons.Rounded.Place,
                        trailing = { IconCircle(Icons.Rounded.DeleteOutline, "Cancel this place reminder", { actions.cancelPlaceReminder(watch.id) }, size = 36.dp, tint = Cafe.colors.cocoa) }
                    )
                }
            }
        }
    }
    if (done.isNotEmpty()) {
        item(key = "task-done") {
            var shown by rememberSaveable { mutableStateOf(false) }
            PaperCard(Modifier.fillMaxWidth(), tone = com.lukas.jarvis.ui.kit.Tone.Latte) {
                SectionHeader("Done (${done.size})", action = if (shown) "Hide" else "Show", onAction = { shown = !shown })
                if (shown) done.take(30).forEach { task -> TaskRow(task, actions, removed) }
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task, actions: LibraryActions, removed: (Undoable) -> Unit) {
    CheckRow(
        text = task.title,
        checked = task.done,
        onCheckedChange = { actions.toggleTask(task) },
        detail = listOfNotNull(
            task.dueAt?.let { TimeUtil.format(it) },
            task.repeatRule.takeIf { it != Task.REPEAT_NONE }
        ).joinToString(" · ").ifBlank { null },
        trailing = {
            IconCircle(Icons.Rounded.DeleteOutline, "Delete ${task.title}", {
                actions.deleteTask(task.id)
                removed(Undoable("Deleted ${task.title}.") { actions.addTask(task.title, task.dueAt, task.repeatRule, task.notes) })
            }, size = 36.dp, tint = Cafe.colors.cocoa)
        }
    )
}

private fun fmt(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString() else "%.2f".format(value)

