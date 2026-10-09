package com.lukas.jarvis.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.control.NowPlaying
import com.lukas.jarvis.control.PhoneLevels
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.character.Prop
import com.lukas.jarvis.ui.kit.ButtonKind
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.kit.ChoiceChip
import com.lukas.jarvis.ui.kit.ControlTile
import com.lukas.jarvis.ui.kit.PaperCard
import com.lukas.jarvis.ui.kit.SectionHeader
import com.lukas.jarvis.ui.kit.SliderRow
import com.lukas.jarvis.ui.kit.Tone
import com.lukas.jarvis.ui.kit.VSpace
import com.lukas.jarvis.ui.kit.Wrap
import com.lukas.jarvis.ui.theme.Cafe
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MusicActions(
    val onBack: () -> Unit,
    val onPlay: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onVolume: (Int) -> Unit = {},
    val onOpenDevices: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onGrantAccess: () -> Unit = {}
)

/**
 * The transport controls, for whatever is playing. Not a music player: it
 * sends the presses headphones send, so it drives any app without an account
 * with any of them.
 */
@Composable
fun MusicRoom(nowPlaying: NowPlaying?, canSeeMedia: Boolean, mediaVolume: Int?, actions: MusicActions, modifier: Modifier = Modifier) {
    // What is playing changes by itself, so it is looked at every couple of seconds while this is up.
    LaunchedEffect(Unit) {
        while (true) {
            actions.onRefresh()
            delay(2_000)
        }
    }
    val playing = nowPlaying?.playing == true
    RoomScaffold(
        title = "Music",
        subtitle = "Plays through whatever app you use",
        onBack = actions.onBack,
        modifier = modifier,
        mochi = CharacterState(if (playing) Mood.Delighted else Mood.Idle, prop = Prop.Note, description = "Mochi, listening along")
    ) {
        item(key = "now") {
            PaperCard(Modifier.fillMaxWidth(), tone = Tone.Honey) {
                SectionHeader("Now playing")
                when {
                    !canSeeMedia -> {
                        Text("Allow notification access and the song, the artist and the app show here — and \"what's playing?\" gets an answer.", style = Cafe.type.body, color = Cafe.colors.espresso)
                        VSpace(Cafe.space.s)
                        CafeButton("Allow notification access", actions.onGrantAccess, kind = ButtonKind.Secondary, icon = Icons.Rounded.NotificationsActive)
                    }
                    nowPlaying == null -> Text("Nothing is playing.", style = Cafe.type.body, color = Cafe.colors.cocoa)
                    else -> {
                        Text(nowPlaying.title, style = Cafe.type.headline, color = Cafe.colors.espresso, maxLines = 2)
                        Text(
                            listOfNotNull(nowPlaying.artist, nowPlaying.app).joinToString(" · ") + if (nowPlaying.playing) "" else " · paused",
                            style = Cafe.type.bodySmall,
                            color = Cafe.colors.cocoa,
                            maxLines = 1
                        )
                        if (nowPlaying.durationMs > 0) {
                            VSpace(Cafe.space.s)
                            val done = (nowPlaying.positionMs.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f)
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(Cafe.shape.pill).background(Cafe.colors.latte)) {
                                Box(Modifier.fillMaxWidth(done).height(6.dp).clip(Cafe.shape.pill).background(Cafe.colors.accentFill))
                            }
                        }
                    }
                }
                VSpace(Cafe.space.l)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Transport(Icons.Rounded.SkipPrevious, "Previous track", actions.onPrevious)
                    Transport(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", if (playing) actions.onPause else actions.onPlay, big = true)
                    Transport(Icons.Rounded.SkipNext, "Next track", actions.onNext)
                }
            }
        }
        item(key = "volume") {
            var volume by remember(mediaVolume) { mutableFloatStateOf((mediaVolume ?: 50) / 100f) }
            PaperCard(Modifier.fillMaxWidth()) {
                SliderRow(
                    "Volume",
                    volume,
                    { volume = it },
                    valueLabel = "${(volume * 100).roundToInt()}%",
                    onValueChangeFinished = { actions.onVolume((volume * 100).roundToInt()) }
                )
                Text("Media volume. Do Not Disturb can hold it, and then nothing moves.", style = Cafe.type.caption, color = Cafe.colors.cocoa)
            }
        }
        item(key = "say") {
            PaperCard(Modifier.fillMaxWidth(), tone = Tone.Latte) {
                Text("Say \"play Sonne by Rammstein\" and the request goes to your music app. Mochi has no library of its own and no account anywhere.", style = Cafe.type.body, color = Cafe.colors.espresso)
                VSpace(Cafe.space.s)
                // Where you would look for your headphones is next to what plays through them.
                CafeButton("Bluetooth devices", actions.onOpenDevices, kind = ButtonKind.Secondary, icon = Icons.Rounded.Bluetooth)
            }
        }
    }
}

@Composable
private fun Transport(icon: ImageVector, label: String, onClick: () -> Unit, big: Boolean = false) {
    val size = if (big) 72.dp else 56.dp
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (big) Cafe.colors.accentFill else Cafe.colors.paper)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (big) Cafe.colors.onAccent else Cafe.colors.espresso, modifier = Modifier.size(if (big) 36.dp else 28.dp))
    }
}

class DeviceActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit = {},
    val onOpenBluetooth: () -> Unit = {},
    val onTorch: (Boolean) -> Unit = {},
    val onVolume: (stream: String, percent: Int) -> Unit = { _, _ -> },
    val onBrightness: (Int) -> Unit = {},
    val onAutoBrightness: (Boolean) -> Unit = {},
    val onRinger: (String) -> Unit = {},
    val onQuiet: (Boolean) -> Unit = {}
)

/**
 * The phone's own control centre: the switches and levels an app may touch,
 * showing where each really is, and what is paired. Connecting a Bluetooth
 * device is the system's alone, so this opens the page where it is two taps away.
 */
@Composable
fun DevicesRoom(bluetooth: String, phoneStatus: String, levels: PhoneLevels?, actions: DeviceActions, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { actions.onRefresh() }
    RoomScaffold(
        title = "This phone",
        subtitle = "Switches, levels and what's paired",
        onBack = actions.onBack,
        modifier = modifier,
        mochi = CharacterState(Mood.Idle, prop = Prop.Phone, description = "Mochi, holding the phone")
    ) {
        item(key = "status") {
            PaperCard(Modifier.fillMaxWidth(), tone = Tone.Latte) {
                // The sentences Mochi reads out when asked, so the screen and the answer agree.
                Text(phoneStatus.ifBlank { "Checking…" }, style = Cafe.type.body, color = Cafe.colors.espresso)
                VSpace(Cafe.space.xs)
                com.lukas.jarvis.ui.kit.QuietButton("Check again", actions.onRefresh)
            }
        }
        item(key = "tiles") {
            Wrap(Modifier.fillMaxWidth()) {
                val torch = levels?.torch == true
                ControlTile(Icons.Rounded.FlashlightOn, "Torch", if (torch) "On" else "Off", torch, { actions.onTorch(!torch) })
                if (levels != null) {
                    ControlTile(
                        Icons.Rounded.DoNotDisturbOn,
                        "Do Not Disturb",
                        if (!levels.quietAccess) "Needs access" else if (levels.quiet) "On" else "Off",
                        levels.quiet,
                        { actions.onQuiet(!levels.quiet) }
                    )
                    ControlTile(
                        Icons.Rounded.BrightnessAuto,
                        "Auto brightness",
                        if (!levels.canWriteSettings) "Needs access" else if (levels.autoBrightness) "On" else "Off",
                        levels.autoBrightness,
                        { actions.onAutoBrightness(!levels.autoBrightness) }
                    )
                }
            }
        }
        if (levels != null) {
            item(key = "ringer") {
                PaperCard(Modifier.fillMaxWidth()) {
                    SectionHeader("Sound")
                    Wrap {
                        listOf("normal" to "Ring", "vibrate" to "Vibrate", "silent" to "Silent").forEach { (id, label) ->
                            ChoiceChip(label, levels.ringer == id, { actions.onRinger(id) })
                        }
                    }
                    VSpace(Cafe.space.s)
                    Level("Media", Icons.Rounded.MusicNote, levels.media) { actions.onVolume("media", it) }
                    Level("Ring", Icons.Rounded.NotificationsActive, levels.ring) { actions.onVolume("ring", it) }
                    Level("Alarm", Icons.Rounded.Alarm, levels.alarm) { actions.onVolume("alarm", it) }
                    Level("Brightness", Icons.Rounded.LightMode, levels.brightness, minimum = 1) { actions.onBrightness(it) }
                }
            }
        }
        item(key = "paired") {
            PaperCard(Modifier.fillMaxWidth()) {
                SectionHeader("Paired")
                Text(bluetooth.ifBlank { "Checking…" }, style = Cafe.type.body, color = Cafe.colors.espresso)
                VSpace(Cafe.space.s)
                CafeButton("Open Bluetooth settings", actions.onOpenBluetooth, icon = Icons.Rounded.Bluetooth)
                Text(
                    "Android keeps connecting a device and switching the radio on for itself. Mochi sees what's paired and takes you to the right page; the last tap is yours.",
                    style = Cafe.type.caption,
                    color = Cafe.colors.cocoa,
                    modifier = Modifier.padding(top = Cafe.space.s)
                )
            }
        }
    }
}

/** One level, which follows the finger and tells the phone only when it lifts. */
@Composable
private fun Level(label: String, icon: ImageVector, percent: Int, minimum: Int = 0, onSet: (Int) -> Unit) {
    var value by remember(percent) { mutableFloatStateOf(percent / 100f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Cafe.colors.accentText, modifier = Modifier.size(20.dp).padding(end = 0.dp))
        SliderRow(
            label,
            value,
            { value = it.coerceAtLeast(minimum / 100f) },
            modifier = Modifier.padding(start = Cafe.space.s),
            valueLabel = "${(value * 100).roundToInt()}%",
            onValueChangeFinished = { onSet((value * 100).roundToInt()) }
        )
    }
}
