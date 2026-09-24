package com.lukas.jarvis.control

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * The parts of the phone an ordinary app is allowed to touch.
 *
 * Android draws a hard line here, and it is worth naming rather than papering
 * over: transport controls and volume are open to any app, while the things
 * that would let an app impersonate the user — connecting a Bluetooth device,
 * switching the radio on — are reserved for the system. Where that line is hit,
 * this opens the right settings page and says so, instead of pretending to have
 * done something and leaving the user to discover it did not happen.
 */
/** What a music app says it is playing. */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val app: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long
)

class Phone(context: Context) {

    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    // ------------------------------------------------------------ now playing

    /**
     * The music app that is playing, or was last: Android shares its media
     * sessions with an app that holds notification access, which Jarvis
     * already asks for to answer messages. Null without that access or with
     * nothing loaded.
     */
    private fun session(): android.media.session.MediaController? {
        if (!com.lukas.jarvis.notify.ReplyListener.isEnabled(app)) return null
        val manager = app.getSystemService(android.media.session.MediaSessionManager::class.java) ?: return null
        val sessions = runCatching {
            manager.getActiveSessions(
                android.content.ComponentName(app, com.lukas.jarvis.notify.ReplyListener::class.java)
            )
        }.getOrNull().orEmpty()
        return sessions.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }

    val canSeeMedia: Boolean get() = com.lukas.jarvis.notify.ReplyListener.isEnabled(app)

    fun nowPlaying(): NowPlaying? {
        val controller = session() ?: return null
        val meta = controller.metadata ?: return null
        val title = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        val artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val label = runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(controller.packageName, 0)).toString()
        }.getOrDefault(controller.packageName)
        val state = controller.playbackState
        return NowPlaying(
            title = title,
            artist = artist?.takeIf { it.isNotBlank() },
            app = label,
            playing = state?.state == android.media.session.PlaybackState.STATE_PLAYING,
            positionMs = state?.position ?: 0L,
            durationMs = meta.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        )
    }

    /** "Sonne by Rammstein, playing in Spotify." */
    fun describeNowPlaying(): String {
        if (!canSeeMedia) {
            return "I can only see what is playing with notification access, which is switched " +
                "on in Android's settings under Notification access."
        }
        val now = nowPlaying() ?: return "Nothing is playing."
        return buildString {
            append(now.title)
            now.artist?.let { append(" by ").append(it) }
            append(if (now.playing) ", playing in " else ", paused in ").append(now.app).append(".")
        }
    }

    // ------------------------------------------------------------------ media

    /**
     * Starts music. With a query, whichever music app claims the search intent
     * handles it — that is the only way to ask for a particular song without
     * building against one service's SDK and its account. Without one, it is
     * the play button, which resumes whatever was last playing.
     */
    fun play(query: String?): String {
        if (query.isNullOrBlank()) {
            return if (mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)) {
                "Playing."
            } else {
                "Nothing is loaded to play. Name something and I will search for it."
            }
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(android.app.SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (start(intent)) {
            "Asked your music app to play $query."
        } else {
            "No music app on this phone offered to handle a search. " +
                "Open one once and try again."
        }
    }

    // The session's own controls where it can be seen, which reach the app
    // that is actually playing; the media button otherwise.
    fun pause(): String =
        if (session()?.transportControls?.let { it.pause(); true } == true ||
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        ) "Paused." else "Nothing is playing."

    fun next(): String =
        if (session()?.transportControls?.let { it.skipToNext(); true } == true ||
            mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        ) "Skipped." else "Nothing is playing."

    fun previous(): String =
        if (session()?.transportControls?.let { it.skipToPrevious(); true } == true ||
            mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        ) "Back a track." else "Nothing is playing."

    /**
     * A media button, sent as the press and release a real button sends. Apps
     * ignore a down without an up, which is the usual reason this silently does
     * nothing when it is written the short way.
     */
    private fun mediaKey(code: Int): Boolean {
        val audio = audio ?: return false
        return runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            true
        }.getOrDefault(false)
    }

    // ----------------------------------------------------------------- volume

    fun setVolume(percent: Int): String {
        val audio = audio ?: return "No audio service on this phone."
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val wanted = (percent.coerceIn(0, 100) * max / 100f).toInt().coerceIn(0, max)
        return runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, wanted, 0)
            "Media volume at ${wanted * 100 / max}%."
        }.getOrElse {
            // Do Not Disturb holds the volume on many phones and throws here.
            "The system would not let me change the volume — Do Not Disturb is " +
                "usually what blocks it."
        }
    }

    fun volume(): String {
        val audio = audio ?: return "No audio service on this phone."
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return "Media volume is at ${now * 100 / max}%."
    }

    // -------------------------------------------------------------- bluetooth

    private val adapter: BluetoothAdapter?
        get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val mayTalkToBluetooth: Boolean
        get() = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** The devices already paired with this phone, and whether the radio is on. */
    fun bluetoothDevices(): String {
        val adapter = adapter ?: return "This phone has no Bluetooth."
        if (!mayTalkToBluetooth) {
            return "I need the Bluetooth permission before I can see your devices. " +
                "Grant it in Android settings under Jarvis."
        }
        if (!adapter.isEnabled) {
            return "Bluetooth is switched off. I cannot switch it on myself — " +
                "Android reserves that — but say the word and I will open the settings."
        }
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        if (bonded.isEmpty()) return "Bluetooth is on, but nothing is paired with this phone yet."
        val names = bonded.mapNotNull { device ->
            runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return buildString {
            append("Paired devices: ")
            append(names.joinToString(", "))
            append(". Android only lets the system connect to one, so tell me to open ")
            append("Bluetooth settings and it is two taps from there.")
        }
    }

    /**
     * The Bluetooth page of Android settings.
     *
     * This is the honest end of the road: an app without system privileges
     * cannot connect an audio device or turn the radio on, and every method
     * that once could has been closed since Android 13. Opening the page the
     * user would have opened anyway is the most this can truthfully do.
     */
    fun openBluetoothSettings(): String {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(intent)) {
            "Bluetooth settings are open."
        } else {
            "I could not open Bluetooth settings on this phone."
        }
    }

    private fun start(intent: Intent): Boolean = runCatching {
        app.startActivity(intent)
        true
    }.getOrDefault(false)
}
