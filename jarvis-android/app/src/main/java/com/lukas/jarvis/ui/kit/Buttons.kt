package com.lukas.jarvis.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.CafeMotion

/** How much a button asks for attention. One [Primary] per moment, at most. */
enum class ButtonKind { Primary, Secondary, Quiet, Danger }

/** A press that settles back, like a soft key. Calm when motion is reduced. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val reduce = Cafe.reduceMotion
    val scale by animateFloatAsState(if (pressed && !reduce) 0.96f else 1f, CafeMotion.quick(reduce), label = "press")
    return scale
}

/**
 * The café's button: a pill, at least 48 dp tall, with an optional icon.
 * [Primary] is the moment's one clear next step.
 */
@Composable
fun CafeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fill: Boolean = false
) {
    val colors = Cafe.colors
    val (bg, fg) = when (kind) {
        ButtonKind.Primary -> colors.accentFill to colors.onAccent
        ButtonKind.Secondary -> colors.latte to colors.espresso
        ButtonKind.Quiet -> Color.Transparent to colors.accentText
        ButtonKind.Danger -> colors.berrySoft to colors.berryText
    }
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .scale(pressScale(source))
            .defaultMinSize(minHeight = Cafe.space.touch, minWidth = Cafe.space.touch)
            .clip(Cafe.shape.pill)
            .background(bg)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = if (kind == ButtonKind.Quiet) Cafe.space.m else Cafe.space.l, vertical = Cafe.space.s),
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(
            text,
            style = Cafe.type.label,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (fill) Modifier.weight(1f, fill = false) else Modifier
        )
    }
}

/** A text-only button for the less important way forward. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) =
    CafeButton(text, onClick, modifier, kind = ButtonKind.Quiet, icon = icon)

/**
 * A round icon button. [description] is required: an icon alone says nothing
 * to TalkBack.
 */
@Composable
fun IconCircle(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: Dp = Cafe.space.touch,
    tint: Color? = null,
    enabled: Boolean = true
) {
    val colors = Cafe.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .scale(pressScale(source))
            .sizeIn(minWidth = Cafe.space.touch, minHeight = Cafe.space.touch)
            .size(size)
            .clip(Cafe.shape.pill)
            .background(if (filled) colors.accentFill else colors.latte)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint ?: if (filled) colors.onAccent else colors.espresso,
            modifier = Modifier.size(size * 0.46f)
        )
    }
}

/**
 * A follow-up under a result: "Route there", "Remind me". It is sent as if it
 * had been said, so it always leads somewhere.
 */
@Composable
fun FollowChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = Cafe.colors
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .scale(pressScale(source))
            .defaultMinSize(minHeight = Cafe.space.touch)
            .clip(Cafe.shape.pill)
            .background(colors.accentSoft)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = Cafe.space.m + Cafe.space.xxs, vertical = Cafe.space.s),
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.xs + Cafe.space.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(18.dp))
        Text(label, style = Cafe.type.labelSmall, color = colors.espresso, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One choice among several: a colour, a mode, a filter. */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null
) {
    val colors = Cafe.colors
    Row(
        modifier
            .defaultMinSize(minHeight = Cafe.space.touch)
            .clip(Cafe.shape.pill)
            .background(if (selected) colors.accentFill else colors.latte)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = Cafe.space.l, vertical = Cafe.space.s),
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Text(
            label,
            style = Cafe.type.labelSmall,
            color = if (selected) colors.onAccent else colors.espresso,
            maxLines = 1
        )
    }
}
