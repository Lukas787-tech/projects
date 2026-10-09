package com.lukas.jarvis.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Cafe

/** A round, soft badge holding an icon: the leading mark of a row or card. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, tone: Tone = Tone.Latte) {
    Box(
        modifier
            .size(40.dp)
            .clip(Cafe.shape.pill)
            .background(toneColor(tone)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when (tone) {
                Tone.Sage -> Cafe.colors.sageText
                Tone.Berry -> Cafe.colors.berryText
                Tone.Accent -> Cafe.colors.accentText
                else -> Cafe.colors.espresso
            },
            modifier = Modifier.size(20.dp)
        )
    }
}

/** One line in a list: a title, maybe a second line, maybe something on each side. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null
) {
    val colors = Cafe.colors
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Cafe.space.touch + Cafe.space.s)
            .clip(Cafe.shape.medium)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Cafe.space.xs, vertical = Cafe.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.m)
    ) {
        if (icon != null) IconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = Cafe.type.body, color = colors.espresso, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = Cafe.type.bodySmall, color = colors.cocoa, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

/** A thing on a list with a box to tick. Ticked things stay, crossed through, until cleared. */
@Composable
fun CheckRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = Cafe.colors
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Cafe.space.touch)
            .clip(Cafe.shape.medium)
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) }
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "Done" else "Not done" }
            .padding(horizontal = Cafe.space.xs, vertical = Cafe.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.m)
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(Cafe.shape.small)
                .background(if (checked) colors.sage else colors.latte),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.paper, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = Cafe.type.body,
                color = if (checked) colors.cocoa else colors.espresso,
                textDecoration = if (checked) TextDecoration.LineThrough else null
            )
            if (!detail.isNullOrBlank()) Text(detail, style = Cafe.type.bodySmall, color = colors.cocoa)
        }
        trailing?.invoke()
    }
}

/** A label and its value, side by side: "Left this week · €23". */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, emphasise: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = Cafe.space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (emphasise) Cafe.type.title else Cafe.type.label,
            color = Cafe.colors.espresso
        )
    }
}
