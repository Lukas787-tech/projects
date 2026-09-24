package com.lukas.jarvis.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolGroup
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.glass

/** One icon per family, shared by the chips, the Skills screen and anything else. */
fun ToolGroup.icon(): ImageVector = when (this) {
    ToolGroup.Memory -> Icons.Default.Psychology
    ToolGroup.Money -> Icons.Default.AccountBalanceWallet
    ToolGroup.Tasks -> Icons.Default.CheckCircle
    ToolGroup.Thinking -> Icons.Default.Calculate
    ToolGroup.Web -> Icons.Default.Language
    ToolGroup.Weather -> Icons.Default.WbSunny
    ToolGroup.Places -> Icons.Default.Place
    ToolGroup.Calendar -> Icons.Default.CalendarMonth
    ToolGroup.People -> Icons.Default.Person
    ToolGroup.Phone -> Icons.Default.PhoneAndroid
    ToolGroup.Messages -> Icons.Default.Sms
    ToolGroup.Media -> Icons.Default.MusicNote
    ToolGroup.Screen -> Icons.Default.Visibility
    ToolGroup.Vision -> Icons.Default.PhotoCamera
    ToolGroup.Automation -> Icons.Default.Bolt
    ToolGroup.News -> Icons.Default.Newspaper
    ToolGroup.Language -> Icons.Default.Translate
    ToolGroup.Create -> Icons.Default.Palette
    ToolGroup.Markets -> Icons.Default.CandlestickChart
    ToolGroup.Knowledge -> Icons.Default.Lightbulb
    ToolGroup.Fun -> Icons.Default.Casino
    ToolGroup.Home -> Icons.Default.Home
}

/**
 * How a reply was reached, as a row of small capsules.
 *
 * Under a finished reply they are the receipt: this came from the weather
 * service and the calendar, not from the model's imagination. While a turn is
 * running the newest one breathes, which is the difference between "it is
 * searching the web" and a spinner that could mean anything, including stuck.
 *
 * Tools that share a chip — the three halves of a phone call — show once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolTrail(
    tools: List<String>,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(Space.hair + 2.dp)
) {
    val chips = tools.distinctBy { ToolCatalog.chip(it) }
    if (chips.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = Arrangement.spacedBy(Space.hair + 2.dp)
    ) {
        chips.forEachIndexed { index, tool ->
            ToolChip(tool = tool, working = live && index == chips.lastIndex)
        }
    }
}

@Composable
fun ToolChip(tool: String, modifier: Modifier = Modifier, working: Boolean = false) {
    val group = ToolCatalog.info(tool)?.group
    val tint = if (working) Accent else TextSecondary

    val pulse = if (working) {
        val transition = rememberInfiniteTransition(label = "chip")
        val value by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "chip-pulse"
        )
        value
    } else {
        1f
    }

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (working) Film.selected else Film.resting)
            .padding(start = Space.tight, end = Space.snug - 2.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (group != null) {
            Icon(
                group.icon(),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp).alpha(pulse)
            )
        }
        Text(
            text = ToolCatalog.chip(tool),
            // The small-caps tracking is for headings; a chip is a word.
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.3.sp),
            color = tint,
            maxLines = 1
        )
    }
}

/**
 * An icon on a small square of glass — the mark each family carries on the
 * Skills screen. Dimmed rather than hidden when its ability is off, so the
 * list keeps its shape and says what switching it on would bring back.
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    lit: Boolean = true,
    tint: Color = if (lit) Accent else TextFaint
) {
    Box(
        modifier = modifier
            .size(size)
            .glass(RoundedCornerShape(Corner.small), raised = lit),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.48f))
    }
}
