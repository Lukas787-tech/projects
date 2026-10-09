package com.lukas.jarvis.ui.rooms

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Mochi
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.character.Prop
import com.lukas.jarvis.ui.kit.ButtonKind
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.kit.CafeSheet
import com.lukas.jarvis.ui.kit.CafeTextField
import com.lukas.jarvis.ui.kit.EmptyState
import com.lukas.jarvis.ui.kit.LocalPicture
import com.lukas.jarvis.ui.kit.Markdown
import com.lukas.jarvis.ui.kit.QuietButton
import com.lukas.jarvis.ui.kit.ToolTrail
import com.lukas.jarvis.ui.kit.VSpace
import com.lukas.jarvis.ui.kit.Wrap
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.Elevation
import com.lukas.jarvis.ui.theme.paper

class HistoryActions(
    val onBack: () -> Unit,
    /** A fresh start: Mochi stops carrying the last conversation along. Nothing is deleted. */
    val onNewConversation: () -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onSpeak: (ChatMessage) -> Unit = {},
    val onRemember: (ChatMessage) -> Unit = {},
    val onRetry: (ChatMessage) -> Unit = {},
    val onDelete: (ChatMessage) -> Unit = {}
)

/**
 * The whole conversation, oldest at the top and the latest in view, searchable,
 * with every line's actions a tap away: copy, read aloud, share, remember,
 * try again, delete.
 */
@Composable
fun HistoryRoom(messages: List<ChatMessage>, name: String, actions: HistoryActions, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf<ChatMessage?>(null) }
    val shown = remember(messages, query) {
        val q = query.trim()
        if (q.isBlank()) messages else messages.filter { it.content.contains(q, ignoreCase = true) }
    }
    val list = rememberLazyListState()
    // Two leading items (the search and the empty states) sit before the lines.
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.scrollToItem(shown.size + 1) }
    RoomScaffold(
        title = "The conversation",
        subtitle = when {
            messages.isEmpty() -> "Nothing yet"
            query.isNotBlank() -> "${shown.size} of ${messages.size} messages"
            else -> "${messages.size} messages"
        },
        onBack = actions.onBack,
        modifier = modifier,
        state = list,
        mochi = CharacterState(Mood.Idle, prop = Prop.Notepad, description = "$name, with the notes"),
        header = {
            Column {
                Wrap {
                    CafeButton("New conversation", actions.onNewConversation, kind = ButtonKind.Secondary, icon = Icons.Rounded.AddComment)
                    if (messages.isNotEmpty()) QuietButton("Share it all", { actions.onShare(transcript(shown, name)) }, icon = Icons.Rounded.Share)
                }
                if (messages.isNotEmpty()) {
                    VSpace(Cafe.space.s)
                    CafeTextField(query, { query = it }, placeholder = "Search everything said")
                }
            }
        }
    ) {
        item(key = "empty") {
            when {
                messages.isEmpty() -> EmptyState(
                    title = "No conversation yet",
                    body = "Everything you and $name say is kept here, on this phone.",
                    action = "Start talking",
                    onAction = actions.onBack,
                    art = { Mochi(CharacterState(Mood.Idle, description = name), size = 96.dp) }
                )
                shown.isEmpty() -> EmptyState(
                    title = "Nothing matches",
                    body = "No message says “${query.trim()}”.",
                    action = "Clear the search",
                    onAction = { query = "" }
                )
            }
        }
        item(key = "spacer") { VSpace(Cafe.space.xxs) }
        items(shown, key = { "${it.id}:${it.createdAt}:${it.role}" }) { message ->
            Line(message, name, onChoose = { chosen = message }, actions = actions)
        }
    }
    chosen?.let { message ->
        LineSheet(message, isLast = message == messages.lastOrNull { it.role == ChatMessage.ROLE_ASSISTANT }, actions = actions) { chosen = null }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Line(message: ChatMessage, name: String, onChoose: () -> Unit, actions: HistoryActions) {
    val mine = message.role == ChatMessage.ROLE_USER
    val colors = Cafe.colors
    val shape = if (mine) RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp) else RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
    // A photo turn keeps what was seen under the question, quieter.
    val isPhoto = mine && message.content.startsWith("📷")
    val said = if (isPhoto) message.content.substringBefore("\n\n") else message.content
    val seen = if (isPhoto) message.content.substringAfter("\n\n", "") else ""
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .then(if (mine) Modifier else Modifier.paper(Elevation.Resting, shape, colors))
                .clip(shape)
                .background(if (mine) colors.accentSoft else colors.paper)
                .combinedClickable(onClickLabel = "Actions for this message", onClick = onChoose, onLongClick = onChoose)
                .semantics {
                    contentDescription = (if (mine) "You" else name) + ", " + TimeUtil.relative(message.createdAt) + ": " + Markdown.plain(said).take(400)
                    customActions = listOf(
                        CustomAccessibilityAction("Copy") { actions.onCopy(Markdown.plain(message.content)); true },
                        CustomAccessibilityAction("Remember this") { actions.onRemember(message); true }
                    )
                }
                .padding(horizontal = Cafe.space.l, vertical = Cafe.space.m)
        ) {
            message.image?.let { path ->
                LocalPicture.current.Show(path, "A picture $name drew", Modifier.fillMaxWidth().heightIn(max = 300.dp))
                VSpace(Cafe.space.s)
            }
            if (mine) {
                Text(said, style = Cafe.type.body, color = colors.espresso)
            } else {
                val rendered = remember(message.content, colors.accentText) {
                    Markdown.render(message.content, colors.accentText, colors.latte, colors.cocoa)
                }
                Text(rendered, style = Cafe.type.body, color = colors.espresso)
            }
            if (seen.isNotBlank()) Text(seen, style = Cafe.type.bodySmall, color = colors.cocoa, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Cafe.space.xs))
            if (!mine && message.tools.isNotEmpty()) {
                VSpace(Cafe.space.s)
                ToolTrail(message.tools, working = false)
            }
            Text(TimeUtil.relative(message.createdAt), style = Cafe.type.caption, color = colors.cocoa, modifier = Modifier.padding(top = Cafe.space.xs))
        }
    }
}

@Composable
private fun LineSheet(message: ChatMessage, isLast: Boolean, actions: HistoryActions, onDismiss: () -> Unit) {
    val mine = message.role == ChatMessage.ROLE_USER
    val plain = Markdown.plain(message.content)
    var sure by remember { mutableStateOf(false) }
    CafeSheet(if (mine) "What you said" else "What Mochi said", onDismiss = onDismiss) {
        Text(plain, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa, maxLines = 4, overflow = TextOverflow.Ellipsis)
        VSpace(Cafe.space.l)
        Wrap {
            CafeButton("Copy", { actions.onCopy(plain); onDismiss() }, kind = ButtonKind.Secondary, icon = Icons.Rounded.ContentCopy)
            if (!mine) CafeButton("Read aloud", { actions.onSpeak(message); onDismiss() }, kind = ButtonKind.Secondary, icon = Icons.AutoMirrored.Rounded.VolumeUp)
            CafeButton("Share", { actions.onShare(plain); onDismiss() }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Share)
            CafeButton("Remember this", { actions.onRemember(message); onDismiss() }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Psychology)
            if (isLast && !mine) CafeButton("Try again", { actions.onRetry(message); onDismiss() }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh)
        }
        VSpace(Cafe.space.m)
        if (!sure) {
            QuietButton("Delete this message", { sure = true }, icon = Icons.Rounded.DeleteOutline)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Cafe.space.s), verticalAlignment = Alignment.CenterVertically) {
                CafeButton("Delete it", { actions.onDelete(message); onDismiss() }, kind = ButtonKind.Danger)
                QuietButton("Keep it", { sure = false })
            }
        }
    }
}

/** The conversation as plain text, for sharing. */
private fun transcript(messages: List<ChatMessage>, name: String): String = messages.joinToString("\n\n") { message ->
    val who = if (message.role == ChatMessage.ROLE_USER) "Me" else name
    "$who (${TimeUtil.relative(message.createdAt)}):\n" + Markdown.plain(message.content)
}
