package com.lukas.jarvis.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.glass

/**
 * Voice or text, as a two-position switch.
 *
 * The two ways of using the assistant want opposite screens — one is a planet
 * and a sentence, the other is a transcript and a keyboard — and trying to be
 * both at once is what made the old screen cluttered. So it is a switch, and
 * each side gets the whole screen.
 */
@Composable
fun ModeSwitch(
    voice: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Narrower sides, for a header on a small phone. */
    compact: Boolean = false
) {
    Row(
        modifier = modifier
            .glass(RoundedCornerShape(14.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val pad = if (compact) 9.dp else 14.dp
        Side(Icons.Default.GraphicEq, "Voice mode", voice, pad) { onChange(true) }
        Side(Icons.Default.Keyboard, "Text mode", !voice, pad) { onChange(false) }
    }
}

@Composable
private fun Side(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    pad: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val fill by animateColorAsState(
        targetValue = if (selected) SelectedPane else Color.Transparent,
        label = "mode-fill"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) Accent else TextFaint,
        label = "mode-tint"
    )
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(fill)
            .clickable { onClick() }
            .padding(horizontal = pad, vertical = 7.dp)
            .size(18.dp)
    )
}

private val SelectedPane get() = Film.selected
