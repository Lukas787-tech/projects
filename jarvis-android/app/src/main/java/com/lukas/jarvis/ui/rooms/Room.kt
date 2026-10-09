package com.lukas.jarvis.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Mochi
import com.lukas.jarvis.ui.kit.IconCircle
import com.lukas.jarvis.ui.theme.Cafe

/**
 * A room of the app: Today, the Library, You. A serif title, a small Mochi,
 * a way back to the canvas that is always there, and the room's content as a
 * list with the café's gutters. Wide screens keep the column readable.
 */
@Composable
fun RoomScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    mochi: CharacterState? = null,
    onMochi: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    Box(modifier.fillMaxSize().background(Cafe.colors.foam), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = Cafe.space.gutter, end = Cafe.space.gutter, top = Cafe.space.s, bottom = Cafe.space.xxl),
            verticalArrangement = Arrangement.spacedBy(Cafe.space.m)
        ) {
            item(key = "room-header") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconCircle(Icons.AutoMirrored.Rounded.ArrowBack, "Back to talking", onBack)
                        Column(Modifier.weight(1f).padding(start = Cafe.space.m)) {
                            Text(title, style = Cafe.type.headline, color = Cafe.colors.espresso, modifier = Modifier.semantics { heading() })
                            if (subtitle != null) Text(subtitle, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
                        }
                        if (mochi != null) Mochi(mochi, size = 64.dp, onClick = onMochi, clickLabel = "Talk to Mochi")
                    }
                    header?.let {
                        Box(Modifier.fillMaxWidth().padding(top = Cafe.space.m)) { it() }
                    }
                }
            }
            content()
        }
    }
}
