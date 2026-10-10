package com.lukas.jarvis.ui.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.moment.Bar
import com.lukas.jarvis.moment.Chart
import com.lukas.jarvis.ui.theme.Cafe

/** How many rows a card draws before the rest wait behind "Show the numbers". */
private const val MOST_BARS = 6

/**
 * The numbers behind a money or habit card, drawn in the café's own colours:
 * a meter against each budget, bars side by side otherwise, a mark where last
 * month stood, and a habit's week as seven dots. Every row also says its
 * numbers in words, so nothing here is told by colour alone.
 */
@Composable
internal fun ChartBody(chart: Chart, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = Cafe.space.s), verticalArrangement = Arrangement.spacedBy(Cafe.space.m)) {
        if (chart.week.isNotEmpty()) WeekRow(chart.week)
        // Bars without a budget share one scale; a meter is measured against its own budget.
        val scale = chart.bars.filter { it.limit == null }
            .maxOfOrNull { maxOf(it.value, it.compare ?: 0.0) }
            ?.takeIf { it > 0.0 } ?: 1.0
        chart.bars.take(MOST_BARS).forEach { BarRow(it, scale) }
        if (chart.bars.size > MOST_BARS) {
            Text("and ${chart.bars.size - MOST_BARS} more", style = Cafe.type.caption, color = Cafe.colors.cocoa)
        }
        if (chart.caption.isNotBlank()) {
            Text(chart.caption, style = Cafe.type.caption, color = Cafe.colors.cocoa)
        }
    }
}

@Composable
private fun BarRow(bar: Bar, scale: Double) {
    val colors = Cafe.colors
    val limit = bar.limit?.takeIf { it > 0.0 }
    fun share(v: Double): Float = (if (limit != null) v / limit else v / scale).toFloat().coerceIn(0f, 1f)
    val near = limit != null && !bar.over && bar.value >= limit * 0.8
    val fill = when {
        bar.over -> colors.berry
        near -> colors.honey
        else -> colors.accent
    }
    val state = when {
        bar.over -> ", over budget"
        near -> ", nearly all of it"
        else -> ""
    }
    val words = "${bar.label}: ${bar.shown}" + (if (bar.note.isNotBlank()) " ${bar.note}" else "") + state
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = words }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                bar.label,
                style = Cafe.type.label,
                color = colors.espresso,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(bar.shown, style = Cafe.type.label, color = if (bar.over) colors.berryText else colors.espresso)
        }
        Box(
            Modifier
                .padding(vertical = Cafe.space.xs)
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(colors.latteDeep)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(share(bar.value))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(fill)
            )
            bar.compare?.takeIf { it > 0.0 }?.let { last ->
                // Where the same days of last month had got to: a thin mark, not a second bar.
                Box(Modifier.fillMaxWidth(share(last)).fillMaxHeight()) {
                    Box(Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(colors.espresso))
                }
            }
        }
        val note = bar.note + if (bar.over) " · over budget" else ""
        if (note.isNotBlank()) {
            Text(note.trim().removePrefix("· "), style = Cafe.type.caption, color = if (bar.over) colors.berryText else colors.cocoa)
        }
    }
}

/** A habit's last seven days, oldest first, today a little larger. */
@Composable
private fun WeekRow(week: List<Boolean>) {
    val done = week.count { it }
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "$done of the last ${week.size} days" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        week.forEachIndexed { index, on ->
            Box(
                Modifier
                    .size(if (index == week.lastIndex) 16.dp else 13.dp)
                    .clip(CircleShape)
                    .background(if (on) Cafe.colors.sage else Cafe.colors.latteDeep)
            )
        }
        Text(
            "$done of ${week.size} days",
            style = Cafe.type.caption,
            color = Cafe.colors.cocoa,
            modifier = Modifier.padding(start = Cafe.space.s)
        )
    }
}
