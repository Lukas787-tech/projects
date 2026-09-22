package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.Panel
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextSecondary

/**
 * The transport controls, for whatever is playing.
 *
 * This is not a music player and does not try to be one. It sends the same
 * button presses a pair of headphones sends, so it drives Spotify, YouTube
 * Music, a podcast app or the local files app without knowing which is there
 * and without an account with any of them. Asking for a particular song hands
 * the search to whichever app claims it, which is the only way to do that
 * without building against one service.
 */
@Composable
fun MusicScreen(
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onVolume: (Int) -> Unit,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier
) {
    var volume by remember { mutableFloatStateOf(-1f) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(title = "Music", subtitle = "Controls whatever app is playing")

        Panel(title = "Playing") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ChipButton(
                    label = "Back",
                    icon = Icons.Default.SkipPrevious,
                    onClick = onPrevious,
                    modifier = Modifier.weight(1f)
                )
                ChipButton(
                    label = "Play",
                    icon = Icons.Default.PlayArrow,
                    onClick = onPlay,
                    prominent = true,
                    modifier = Modifier.weight(1f)
                )
                ChipButton(
                    label = "Pause",
                    icon = Icons.Default.Pause,
                    onClick = onPause,
                    modifier = Modifier.weight(1f)
                )
                ChipButton(
                    label = "Next",
                    icon = Icons.Default.SkipNext,
                    onClick = onNext,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Panel(title = "Volume") {
            Slider(
                value = if (volume < 0f) 0.5f else volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { onVolume((volume * 100).toInt()) },
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = TextFaint
                )
            )
            Text(
                "Media volume. Do Not Disturb can hold this, in which case nothing moves.",
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint
            )
        }

        Text(
            "Say \"play Sonne by Rammstein\" and the request goes to your music app. " +
                "Jarvis has no library of its own and no account anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(Modifier.height(16.dp))
        // Devices has no slot in the bar, and where you would look for your
        // headphones is here, next to what is playing through them.
        ChipButton(
            label = "Bluetooth devices",
            icon = Icons.Default.Bluetooth,
            onClick = onOpenDevices,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * The paired Bluetooth devices, and an honest account of what can be done.
 *
 * Connecting one is reserved for the system: every route an ordinary app once
 * had has been closed, and pretending otherwise would mean telling the user
 * their headphones are connected while nothing happened. So this lists what is
 * paired and opens the page where a connection is two taps away.
 */
@Composable
fun DevicesScreen(
    status: String,
    phoneStatus: String,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onTorch: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) { onRefresh() }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = "Devices",
            subtitle = "This phone, and what is paired with it",
            actionIcon = Icons.Default.Refresh,
            actionLabel = "Check again",
            onAction = onRefresh
        )

        // The same sentences the assistant reads out when asked how the phone is
        // doing, so the screen and the answer cannot disagree.
        Panel(title = "This phone") {
            Text(
                phoneStatus.ifBlank { "Checking…" },
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ChipButton(
                    label = "Torch on",
                    icon = Icons.Default.FlashlightOn,
                    onClick = { onTorch(true) },
                    modifier = Modifier.weight(1f)
                )
                ChipButton(
                    label = "Torch off",
                    icon = Icons.Default.FlashlightOff,
                    onClick = { onTorch(false) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Panel(title = "Paired") {
            Text(
                status.ifBlank { "Checking…" },
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Spacer(Modifier.height(14.dp))
            ChipButton(
                label = "Open Bluetooth settings",
                icon = Icons.Default.Bluetooth,
                onClick = onOpenSettings,
                prominent = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            "Android reserves connecting a device and switching the radio on for the " +
                "system itself. Jarvis can see what is paired and take you to the right " +
                "page; the last tap is yours.",
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint
        )
    }
}
