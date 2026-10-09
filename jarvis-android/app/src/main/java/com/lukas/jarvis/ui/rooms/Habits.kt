package com.lukas.jarvis.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.data.Streak
import com.lukas.jarvis.data.Streaks
import com.lukas.jarvis.ui.kit.ButtonKind
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.theme.Cafe

/** One habit: its name, its run, this week as seven days, and the tap that keeps it going. */
@Composable
fun HabitLine(label: String, streak: Streak?, onDid: () -> Unit, modifier: Modifier = Modifier) {
    val s = streak ?: Streak(0, 0, false, List(7) { false })
    Row(modifier.fillMaxWidth().padding(vertical = Cafe.space.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Cafe.type.body, color = Cafe.colors.espresso)
            val run = Streaks.describe(s)
            Text(
                when {
                    run.isNotEmpty() -> run
                    s.today -> "Done today"
                    s.best > 0 -> "Best run: ${s.best} days"
                    else -> "Not started yet"
                },
                style = Cafe.type.caption,
                color = if (s.current >= 2) Cafe.colors.accentText else Cafe.colors.cocoa
            )
            Row(
                Modifier.padding(top = Cafe.space.xs).semantics { contentDescription = "${s.daysThisWeek} of the last 7 days" },
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                s.week.forEachIndexed { index, done ->
                    Box(
                        Modifier
                            .size(if (index == 6) 12.dp else 10.dp)
                            .clip(CircleShape)
                            .background(if (done) Cafe.colors.sage else Cafe.colors.latteDeep)
                    )
                }
            }
        }
        if (s.today) {
            Box(Modifier.size(Cafe.space.touch).clip(CircleShape).background(Cafe.colors.sageSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, contentDescription = "$label done today", tint = Cafe.colors.sageText, modifier = Modifier.size(22.dp))
            }
        } else {
            CafeButton("Did it", onDid, kind = ButtonKind.Secondary)
        }
    }
}
