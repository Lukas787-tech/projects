package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.lukas.jarvis.ui.theme.OnAccent
import com.lukas.jarvis.ui.theme.glass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.control.NowPlaying
import com.lukas.jarvis.control.PhoneLevels
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.SegmentedTabs
import com.lukas.jarvis.ui.components.ToggleRow
import com.lukas.jarvis.ui.components.Panel
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    modifier: Modifier = Modifier,
    /** The media volume as it really is, so the slider starts where the phone is. */
    mediaVolume: Int? = null,
    nowPlaying: NowPlaying? = null,
    canSeeMedia: Boolean = false,
    onRefresh: () -> Unit = {},
    onGrantAccess: () -> Unit = {}
) {
    var volume by remember(mediaVolume) { mutableFloatStateOf(mediaVolume?.div(100f) ?: -1f) }

    // What is playing changes by itself — the next song comes on — so it is
    // looked at every couple of seconds while this screen is up.
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(2_000)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(title = "Music", subtitle = "Controls whatever app is playing")

        Panel(title = "Now playing") {
            when {
                !canSeeMedia -> {
                    Text(
                        "Allow notification access and the song, the artist and the app show here — " +
                            "and \"what's playing?\" gets an answer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    ChipButton(
                        label = "Allow notification access",
                        icon = Icons.Default.NotificationsActive,
                        onClick = onGrantAccess,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                nowPlaying == null -> Text(
                    "Nothing is playing.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextFaint
                )
                else -> {
                    Text(
                        nowPlaying.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        maxLines = 2
                    )
                    Text(
                        listOfNotNull(nowPlaying.artist, nowPlaying.app).joinToString("  ·  ") +
                            if (nowPlaying.playing) "" else "  ·  paused",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 1
                    )
                    if (nowPlaying.durationMs > 0) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (nowPlaying.positionMs.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f) },
                            color = Accent,
                            trackColor = TextFaint.copy(alpha = 0.25f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        Panel(title = "Controls") {
            // Round buttons, the way every player draws them: four words in
            // four narrow boxes broke mid-word on a phone.
            val playing = nowPlaying?.playing == true
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TransportButton(Icons.Default.SkipPrevious, "Previous track", onPrevious)
                if (nowPlaying == null) {
                    TransportButton(Icons.Default.PlayArrow, "Play", onPlay, prominent = true)
                    TransportButton(Icons.Default.Pause, "Pause", onPause)
                } else {
                    TransportButton(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pause" else "Play",
                        if (playing) onPause else onPlay,
                        prominent = true
                    )
                }
                TransportButton(Icons.Default.SkipNext, "Next track", onNext)
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
                    inactiveTrackColor = TextFaint.copy(alpha = 0.25f)
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
 * The phone's own control centre: the switches and levels an app may touch,
 * showing where each one really is, and the paired Bluetooth devices.
 *
 * Connecting a Bluetooth device is reserved for the system: every route an
 * ordinary app once had has been closed, and pretending otherwise would mean
 * telling the user their headphones are connected while nothing happened. So
 * this lists what is paired and opens the page where a connection is two taps
 * away.
 */
@Composable
fun DevicesScreen(
    status: String,
    phoneStatus: String,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onTorch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    levels: PhoneLevels? = null,
    onVolume: (stream: String, percent: Int) -> Unit = { _, _ -> },
    onBrightness: (Int) -> Unit = {},
    onAutoBrightness: (Boolean) -> Unit = {},
    onRinger: (String) -> Unit = {},
    onQuiet: (Boolean) -> Unit = {}
) {
    LaunchedEffect(Unit) { onRefresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
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
        }

        if (levels != null) {
            Panel(title = "Switches") {
                val modes = listOf("normal", "vibrate", "silent")
                SegmentedTabs(
                    options = listOf("Ring", "Vibrate", "Silent"),
                    selectedIndex = modes.indexOf(levels.ringer).coerceAtLeast(0),
                    onSelect = { onRinger(modes[it]) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "Do Not Disturb",
                    subtitle = if (levels.quietAccess) {
                        "Only priority interruptions get through"
                    } else {
                        "Needs access, granted once on Android's own page"
                    },
                    checked = levels.quiet,
                    onChange = onQuiet
                )
                ToggleRow(
                    title = "Torch",
                    checked = levels.torch,
                    onChange = onTorch
                )
                ToggleRow(
                    title = "Automatic brightness",
                    subtitle = if (levels.canWriteSettings) null else "Needs 'modify system settings', granted once",
                    checked = levels.autoBrightness,
                    onChange = onAutoBrightness
                )
            }

            Panel(title = "Levels") {
                LevelSlider("Media", Icons.Default.MusicNote, levels.media) { onVolume("media", it) }
                LevelSlider("Ring", Icons.Default.NotificationsActive, levels.ring) { onVolume("ring", it) }
                LevelSlider("Alarm", Icons.Default.Alarm, levels.alarm) { onVolume("alarm", it) }
                LevelSlider("Brightness", Icons.Default.LightMode, levels.brightness, minimum = 1) {
                    onBrightness(it)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
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
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    prominent: Boolean = false
) {
    val size = if (prominent) 68.dp else 52.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (prominent) Modifier.background(Accent) else Modifier.glass(CircleShape)
            )
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (prominent) OnAccent else TextPrimary,
            modifier = Modifier.size(if (prominent) 34.dp else 26.dp)
        )
    }
}

/**
 * One level with its name and number. It follows the finger locally and only
 * tells the phone when the finger lifts, so dragging is one change, not fifty.
 */
@Composable
private fun LevelSlider(
    label: String,
    icon: ImageVector,
    percent: Int,
    minimum: Int = 0,
    onSet: (Int) -> Unit
) {
    var value by remember(percent) { mutableFloatStateOf(percent / 100f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
        Text(
            "${(value * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary
        )
    }
    Slider(
        value = value,
        onValueChange = { value = it.coerceAtLeast(minimum / 100f) },
        onValueChangeFinished = { onSet((value * 100).roundToInt()) },
        colors = SliderDefaults.colors(
            thumbColor = Accent,
            activeTrackColor = Accent,
            inactiveTrackColor = TextFaint.copy(alpha = 0.25f)
        )
    )
}
