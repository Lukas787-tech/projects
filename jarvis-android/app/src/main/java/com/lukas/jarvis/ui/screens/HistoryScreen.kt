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

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = "Chat",
            subtitle = if (messages.isEmpty()) "Nothing yet" else "${messages.size} messages",
            onBack = onBack
        )
        if (messages.isEmpty()) {
            EmptyState("No conversation yet", "Everything you say is kept here.")
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { messageKey(it) }) { message ->
                    MessageBubble(message, actions = actions)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
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
