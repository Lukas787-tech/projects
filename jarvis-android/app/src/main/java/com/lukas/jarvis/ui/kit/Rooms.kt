package com.lukas.jarvis.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.Elevation
import com.lukas.jarvis.ui.theme.paper

data class RoomItem(val id: String, val label: String, val icon: ImageVector)

/**
 * The way between rooms when you are not on the canvas: Talk, Today, the
 * Library, the Map and You. A soft paper strip, the room you are in washed in
 * the accent; Talk is always one tap away.
 */
@Composable
fun RoomBar(
    items: List<RoomItem>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Cafe.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Cafe.space.m, vertical = Cafe.space.s)
            .navigationBarsPadding()
            .paper(Elevation.Lifted, Cafe.shape.xlarge, colors)
            .clip(Cafe.shape.xlarge)
            .background(colors.paper)
            .padding(Cafe.space.xs),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            val on = item.id == selected
            Column(
                Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 56.dp)
                    .clip(Cafe.shape.large)
                    .background(if (on) colors.accentSoft else colors.paper)
                    .clickable(role = Role.Tab, onClickLabel = "Open ${item.label}") { onSelect(item.id) }
                    .semantics { this.selected = on }
                    .padding(vertical = Cafe.space.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(item.icon, contentDescription = null, tint = if (on) colors.accentText else colors.cocoa, modifier = Modifier.size(22.dp))
                Text(item.label, style = Cafe.type.caption, color = if (on) colors.espresso else colors.cocoa, maxLines = 1)
            }
        }
    }
}
