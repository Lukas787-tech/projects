package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.ui.components.EmptyState
import com.lukas.jarvis.ui.components.MessageActions
import com.lukas.jarvis.ui.components.MessageBubble
import com.lukas.jarvis.ui.components.ToolTrail
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary

@Composable
fun HistoryScreen(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: MessageActions = MessageActions()
) {
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(messages, query) {
        val q = query.trim()
        if (q.isBlank()) messages else messages.filter { it.content.contains(q, ignoreCase = true) }
    }

    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = "History",
            subtitle = when {
                messages.isEmpty() -> "Nothing yet"
                query.isNotBlank() -> "${shown.size} of ${messages.size} messages"
                else -> "${messages.size} messages"
            },
            actionIcon = Icons.Default.Share,
            actionLabel = "Share the conversation",
            onAction = if (messages.isEmpty()) null else {
                { shareTranscript(context, shown) }
            },
            onBack = onBack
        )
        if (messages.isNotEmpty()) {
            com.lukas.jarvis.ui.components.GlassField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search everything said",
                leadingIcon = Icons.Default.Search,
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.snug)
            )
        }
        if (messages.isEmpty()) {
            EmptyState("No conversation yet", "Everything you say is kept here.")
        } else if (shown.isEmpty()) {
            EmptyState("Nothing matches", "No message contains “${query.trim()}”.")
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { messageKey(it) }) { message ->
                    MessageBubble(message, actions = actions)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** The conversation as plain text, handed to whichever app the user picks. */
private fun shareTranscript(context: android.content.Context, messages: List<ChatMessage>) {
    val text = messages.joinToString("\n\n") { message ->
        val who = if (message.role == ChatMessage.ROLE_USER) "Me" else "Jarvis"
        "$who (${TimeUtil.relative(message.createdAt)}):\n" +
            com.lukas.jarvis.ui.components.Markdown.plain(message.content)
    }
    runCatching {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text.take(90_000))
        context.startActivity(
            android.content.Intent.createChooser(send, "Share the conversation")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * A row key that stays unique even for a message not yet stored.
 *
 * The id alone was the key, and a message shown before its insert returns has
 * id 0 — two of those in a lazy list is a crash, not a glitch. The time and the
 * role keep them apart until the real id arrives.
 */
internal fun messageKey(message: ChatMessage): String =
    "${message.id}:${message.createdAt}:${message.role}"
