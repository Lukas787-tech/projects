package com.lukas.jarvis.ui.kit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolGroup
import com.lukas.jarvis.ui.theme.Cafe

/**
 * What Mochi reached for, in order: Memory, Weather, Calendar. While it works
 * the newest one breathes, so a slow answer says what it is waiting on; once
 * it has answered the trail stays as a receipt for where each number came from.
 */
@Composable
fun ToolTrail(tools: List<String>, working: Boolean, modifier: Modifier = Modifier) {
    if (tools.isEmpty()) return
    val distinct = tools.distinct()
    val spoken = distinct.joinToString { ToolCatalog.chip(it) }
    Wrap(modifier.semantics(mergeDescendants = true) {
        contentDescription = if (working) "Working on: $spoken" else "Used: $spoken"
    }) {
        distinct.forEachIndexed { index, tool ->
            TrailChip(tool, live = working && index == distinct.lastIndex)
        }
    }
}

@Composable
private fun TrailChip(tool: String, live: Boolean) {
    val group = ToolCatalog.info(tool)?.group ?: ToolGroup.Thinking
    val reduce = Cafe.reduceMotion
    val alpha = if (live && !reduce) {
        val t = rememberInfiniteTransition(label = "trail")
        val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breathe")
        a
    } else {
        1f
    }
    Row(
        Modifier
            .alpha(alpha)
            .clip(Cafe.shape.pill)
            .background(if (live) Cafe.colors.accentSoft else Cafe.colors.latte)
            .padding(horizontal = Cafe.space.s + Cafe.space.xxs, vertical = Cafe.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.xs)
    ) {
        Icon(group.icon(), contentDescription = null, tint = Cafe.colors.cocoa, modifier = Modifier.size(14.dp))
        Text(ToolCatalog.chip(tool), style = Cafe.type.caption, color = Cafe.colors.espresso)
    }
}
