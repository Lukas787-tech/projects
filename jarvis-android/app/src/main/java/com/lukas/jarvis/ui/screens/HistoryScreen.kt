package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.ui.components.EmptyState
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary

private val BubbleMine = androidx.compose.ui.graphics.Color(0x24FFFFFF)
private val BubbleTheirs = androidx.compose.ui.graphics.Color(0x12FFFFFF)

@Composable
fun HistoryScreen(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
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
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromUser = message.role == ChatMessage.ROLE_USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (fromUser) 18.dp else 4.dp,
                        bottomEnd = if (fromUser) 4.dp else 18.dp
                    )
                )
                // Yours is the brighter pane, Jarvis's the dimmer one — the
                // same film at two densities rather than two different colours.
                .background(if (fromUser) BubbleMine else BubbleTheirs)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                TimeUtil.relative(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint
            )
        }
    }
}
