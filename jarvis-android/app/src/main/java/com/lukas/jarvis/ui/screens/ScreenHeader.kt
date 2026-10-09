package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary

/**
 * Shared header used by every list screen.
 *
 * The title is optional because a screen shown inside the hub already has its
 * name on the segment above it, and printing it twice wastes the top of a phone
 * screen on saying the same word twice.
 */
@Composable
fun ScreenHeader(
    title: String?,
    subtitle: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (title == null) 4.dp else 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextSecondary
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                // displayLarge is reserved for figures now — a balance, a count —
                // so a screen title sits one step down the scale.
                Text(title, style = MaterialTheme.typography.displayMedium, color = TextPrimary)
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        if (actionIcon != null && onAction != null) {
            IconButton(onClick = onAction) {
                Icon(actionIcon, contentDescription = actionLabel, tint = Accent)
            }
        }
    }
}
