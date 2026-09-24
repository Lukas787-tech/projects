package com.lukas.jarvis.control

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import java.util.Locale
import kotlin.math.roundToInt

/** The numbers behind the control centre, read fresh each time it is shown. */
data class PhoneLevels(
    val media: Int,
    val ring: Int,
    val alarm: Int,
    val brightness: Int,
    val autoBrightness: Boolean,
    /** Whether brightness may be changed at all; Android grants it on its own page. */
    val canWriteSettings: Boolean,
    /** "normal", "vibrate" or "silent". */
    val ringer: String,
    val quiet: Boolean,
    val quietAccess: Boolean,
    val torch: Boolean
)

/**
 * What the phone can say about itself, and the handful of switches an ordinary
 * app is allowed to flip.
 *
 * Every method returns a sentence rather than a value, because every one of them
 * is read out loud at the other end. Where Android refuses — the torch on a
 * phone with no flash, the ringer while Do Not Disturb holds it — the sentence
 * says so, which is the difference between an assistant and a button that lies.
 */
class Device(context: Context) {

    private val app = context.applicationContext

    private val audio get() = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val power get() = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val clipboard get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val notifications
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    // ---------------------------------------------------------------- status

    /** Everything at once, which is what "how is my phone doing" actually asks. */
    fun status(): String {
        val parts = listOf(battery(), connection(), storage(), ringer(), describeQuiet())
        return parts.joinToString(" ")
    }

    fun battery(): String {
        val manager = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return "I cannot read the battery on this phone."
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = manager.isCharging
        val saver = power?.isPowerSaveMode == true

        return buildString {
            append("Battery is at $level%")
            append(if (charging) ", charging" else "")
            if (saver) append(", battery saver on")
            append(".")
            if (!charging && level <= 15) append(" Worth finding a charger.")
        }
    }

    fun connection(): String {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "I cannot read the network state."
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return "No network — the phone is offline."
        val kind = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "some network"
        }
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (validated) "On $kind." else "Connected to $kind, but it has no internet."
    }

    fun storage(): String {
        val stat = runCatching { StatFs(app.filesDir.absolutePath) }.getOrNull()
            ?: return "I cannot read the storage."
        val free = stat.availableBytes.toDouble()
        val total = stat.totalBytes.toDouble().coerceAtLeast(1.0)
        val percent = (free / total * 100).roundToInt()
        return String.format(
            Locale.US,
            "%.1f GB of storage free (%d%%).",
            free / 1_073_741_824.0,
            percent
        )
    }

    fun ringer(): String = when (audio?.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "Ringer is silent."
        AudioManager.RINGER_MODE_VIBRATE -> "Ringer is on vibrate."
        AudioManager.RINGER_MODE_NORMAL -> "Ringer is on."
        else -> "I cannot read the ringer."
    }

    /** The make, model and Android version, for "what phone am I on". */
    fun hardware(): String =
        "${Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.getDefault()) }} " +
            "${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})."

    // --------------------------------------------------------------- switches

    fun setRinger(mode: String): String {
        val audio = audio ?: return "No audio service on this phone."
        val wanted = when (mode.trim().lowercase(Locale.ROOT)) {
            "silent", "mute", "off", "quiet" -> AudioManager.RINGER_MODE_SILENT
            "vibrate", "buzz" -> AudioManager.RINGER_MODE_VIBRATE
            "normal", "loud", "on", "ring" -> AudioManager.RINGER_MODE_NORMAL
            else -> return "I can set the ringer to normal, vibrate or silent."
        }
        // Dropping to silent counts as a Do Not Disturb change, and Android
        // throws rather than asking if the app has not been granted that.
        if (wanted == AudioManager.RINGER_MODE_SILENT &&
            notifications?.isNotificationPolicyAccessGranted == false
        ) {
            openSettings("dnd")
            return "Going fully silent needs Do Not Disturb access, which Android only " +
                "grants from its own settings page. I have opened it for you."
        }
        return runCatching {
            audio.ringerMode = wanted
            ringer()
        }.getOrElse { "The system would not let me change the ringer." }
    }

    /**
     * Sets, raises or lowers one of the phone's volumes. Percentages are of
     * that stream's own range, which differs between phones — 15 steps on one,
     * 25 on another — so "half" means half on both.
     */
    fun volume(stream: String, action: String, level: Int?): String {
        val audio = audio ?: return "No audio service on this phone."
        val (type, label) = STREAMS[stream.trim().lowercase(Locale.ROOT)]
            ?: (AudioManager.STREAM_MUSIC to "media")
        val max = audio.getStreamMaxVolume(type).coerceAtLeast(1)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(type) else 0
        val now = audio.getStreamVolume(type)
        val step = maxOf(1, (max / 10.0).roundToInt())
        val target = when (action.trim().lowercase(Locale.ROOT)) {
            "up", "louder", "raise" -> now + step
            "down", "quieter", "lower" -> now - step
            "mute", "off", "silence" -> min
            "max", "full" -> max
            "unmute", "on" -> if (now <= min) (max / 2).coerceAtLeast(min + 1) else now
            "set" -> level?.let { (it.coerceIn(0, 100) * max / 100.0).roundToInt() }
                ?: return "Say how loud, as a percentage."
            else -> return volumes()
        }.coerceIn(min, max)
        return runCatching {
            audio.setStreamVolume(type, target, AudioManager.FLAG_SHOW_UI)
            val percent = audio.getStreamVolume(type) * 100 / max
            "${label.replaceFirstChar { it.titlecase(Locale.ROOT) }} volume is at $percent%."
        }.getOrElse {
            // The ring and notification volumes reaching zero is a Do Not
            // Disturb change, which Android refuses without that access.
            if (it is SecurityException) {
                "Do Not Disturb is holding the $label volume, so the system would not change it."
            } else {
                "The system would not change the $label volume."
            }
        }
    }

    /** Every volume at once, for "how loud is my phone". */
    fun volumes(): String {
        val audio = audio ?: return "No audio service on this phone."
        return STREAMS.values.distinct().joinToString(", ", postfix = ".") { (type, label) ->
            val max = audio.getStreamMaxVolume(type).coerceAtLeast(1)
            "$label ${audio.getStreamVolume(type) * 100 / max}%"
        }.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /**
     * Screen brightness, as the percentage the system slider shows.
     *
     * The slider has not been linear since Android 9 — halfway along it is
     * about a tenth of the panel's power — so the number said is turned into
     * the stored value the way the system turns the slider into it, and "50%"
     * looks like the slider at half.
     */
    fun brightness(level: Int?, auto: Boolean?, change: String?): String {
        if (!Settings.System.canWrite(app)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:${app.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }
            return "Changing the brightness needs the 'modify system settings' permission, which " +
                "Android only grants from its own page. I have opened it — switch Jarvis on " +
                "there and ask me again."
        }
        val resolver = app.contentResolver
        return runCatching {
            val current = sliderPercent(Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128))
            val target = when (change?.trim()?.lowercase(Locale.ROOT)) {
                "up", "brighter" -> (current + 15).coerceAtMost(100)
                "down", "dimmer", "darker" -> (current - 15).coerceAtLeast(1)
                "max", "full" -> 100
                "min", "lowest" -> 1
                else -> level
            }
            if (auto != null && target == null) {
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            }
            if (target != null) {
                // A level said out loud is a level wanted, not a hint for the
                // light sensor to override a second later.
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, storedValue(target.coerceIn(1, 100)))
            }
            describeBrightness()
        }.getOrElse { "The system would not change the brightness." }
    }

    fun describeBrightness(): String {
        val resolver = app.contentResolver
        val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        val percent = sliderPercent(Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128))
        return if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            "Brightness is automatic, around $percent%."
        } else {
            "Brightness is at $percent%."
        }
    }

    fun levels(): PhoneLevels {
        val audio = audio
        fun percent(type: Int): Int = audio?.let {
            it.getStreamVolume(type) * 100 / it.getStreamMaxVolume(type).coerceAtLeast(1)
        } ?: 0
        val resolver = app.contentResolver
        return PhoneLevels(
            media = percent(AudioManager.STREAM_MUSIC),
            ring = percent(AudioManager.STREAM_RING),
            alarm = percent(AudioManager.STREAM_ALARM),
            brightness = runCatching {
                sliderPercent(Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
            }.getOrDefault(50),
            autoBrightness = runCatching {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }.getOrDefault(false),
            canWriteSettings = Settings.System.canWrite(app),
            ringer = when (audio?.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            },
            quiet = notifications?.currentInterruptionFilter.let {
                it != null && it != NotificationManager.INTERRUPTION_FILTER_ALL &&
                    it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            },
            quietAccess = notifications?.isNotificationPolicyAccessGranted == true,
            torch = torchOn
        )
    }

    /**
     * Do Not Disturb. [minutes] ends it again by itself — "for an hour" —
     * with a one-off alarm, since Android has no public call for a timed one.
     */
    fun doNotDisturb(mode: String, minutes: Int?): String {
        val manager = notifications ?: return "No notification service on this phone."
        if (!manager.isNotificationPolicyAccessGranted) {
            openSettings("dnd")
            return "Do Not Disturb needs its own access, which Android only grants from its own " +
                "settings page. I have opened it — switch Jarvis on there and ask me again."
        }
        val filter = when (mode.trim().lowercase(Locale.ROOT)) {
            "on", "priority", "important" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms", "alarms_only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "total", "silence", "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            "off", "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
            else -> return describeQuiet()
        }
        return runCatching {
            manager.setInterruptionFilter(filter)
            com.lukas.jarvis.notify.QuietReceiver.schedule(
                app,
                if (filter != NotificationManager.INTERRUPTION_FILTER_ALL) minutes else null
            )
            describeQuiet() + if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && minutes != null && minutes > 0) {
                " It ends by itself in ${span(minutes)}."
            } else {
                ""
            }
        }.getOrElse { "The system would not change Do Not Disturb." }
    }

    private fun span(minutes: Int): String = when {
        minutes % 60 == 0 && minutes >= 60 -> (minutes / 60).let { if (it == 1) "an hour" else "$it hours" }
        minutes == 1 -> "a minute"
        else -> "$minutes minutes"
    }

    fun describeQuiet(): String = when (notifications?.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "Do Not Disturb is on — only priority interruptions get through."
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "Do Not Disturb is on — alarms only."
        NotificationManager.INTERRUPTION_FILTER_NONE -> "Do Not Disturb is on — total silence."
        NotificationManager.INTERRUPTION_FILTER_ALL -> "Do Not Disturb is off."
        else -> "I cannot read Do Not Disturb."
    }

    /**
     * The torch.
     *
     * CameraManager can drive it without the camera permission since Android 6,
     * which is why this is a tool rather than a settings shortcut. The flash is
     * looked up each time because a phone can have more than one camera and only
     * one of them lit.
     */
    fun torch(on: Boolean): String {
        val manager = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "No camera service on this phone."
        return runCatching {
            val id = manager.cameraIdList.firstOrNull { cameraId ->
                manager.getCameraCharacteristics(cameraId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone has no flash I can switch on."
            manager.setTorchMode(id, on)
            torchOn = on
            if (on) "Torch on." else "Torch off."
        }.getOrElse {
            "The torch would not respond — another app usually has the camera open."
        }
    }

    /** Remembered so "turn it off" after "turn it on" does not need asking twice. */
    var torchOn: Boolean = false
        private set

    // -------------------------------------------------------------- clipboard

    fun copy(text: String): String {
        if (text.isBlank()) return "There was nothing to copy."
        val clipboard = clipboard ?: return "No clipboard on this phone."
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis", text))
            "Copied: ${text.take(80)}${if (text.length > 80) "…" else ""}"
        }.getOrElse { "The clipboard would not take it." }
    }

    fun paste(): String {
        val clipboard = clipboard ?: return "No clipboard on this phone."
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) return "The clipboard is empty."
        val text = clip.getItemAt(0).coerceToText(app).toString()
        return if (text.isBlank()) {
            "The clipboard holds something that is not text."
        } else {
            "The clipboard holds: $text"
        }
    }

    // ------------------------------------------------------- settings screens

    /** The Android settings pages worth being able to ask for by name. */
    fun openSettings(page: String): String {
        val key = page.trim().lowercase(Locale.ROOT)
        val action = PAGES.entries.firstOrNull { (name, _) -> key.contains(name) }?.value
            ?: return "I can open: ${PAGES.keys.joinToString(", ")}."
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { app.startActivity(intent); true }.getOrDefault(false)) {
            "Opened the ${key} settings."
        } else {
            "This phone has no settings page for '$page'."
        }
    }

    private companion object {
        val STREAMS = linkedMapOf(
            "media" to (AudioManager.STREAM_MUSIC to "media"),
            "music" to (AudioManager.STREAM_MUSIC to "media"),
            "ring" to (AudioManager.STREAM_RING to "ring"),
            "ringer" to (AudioManager.STREAM_RING to "ring"),
            "notification" to (AudioManager.STREAM_NOTIFICATION to "notification"),
            "alarm" to (AudioManager.STREAM_ALARM to "alarm"),
            "call" to (AudioManager.STREAM_VOICE_CALL to "call")
        )

        // The system's own slider curve (BrightnessUtils): a square law for the
        // lower half, a log curve above, over a 0–12 range.
        private const val HLG_R = 0.5
        private const val HLG_A = 0.17883277
        private const val HLG_B = 0.28466892
        private const val HLG_C = 0.55991073

        fun storedValue(percent: Int): Int {
            val f = percent / 100.0
            val ret = if (f <= HLG_R) (f / HLG_R) * (f / HLG_R) else kotlin.math.exp((f - HLG_C) / HLG_A) + HLG_B
            return (1 + 254 * (ret.coerceIn(0.0, 12.0) / 12.0)).roundToInt().coerceIn(1, 255)
        }

        fun sliderPercent(stored: Int): Int {
            val ret = (stored - 1).coerceAtLeast(0) / 254.0 * 12.0
            val f = if (ret <= 1) HLG_R * kotlin.math.sqrt(ret) else HLG_A * kotlin.math.ln(ret - HLG_B) + HLG_C
            return (f * 100).roundToInt().coerceIn(0, 100)
        }

        val PAGES = linkedMapOf(
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "wi-fi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "data" to Settings.ACTION_DATA_ROAMING_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "apps" to Settings.ACTION_APPLICATION_SETTINGS,
            "notifications" to Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            "dnd" to Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            "disturb" to Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            "brightness" to Settings.ACTION_DISPLAY_SETTINGS,
            "volume" to Settings.ACTION_SOUND_SETTINGS,
            "nfc" to Settings.ACTION_NFC_SETTINGS,
            "hotspot" to Settings.ACTION_WIRELESS_SETTINGS,
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "date" to Settings.ACTION_DATE_SETTINGS,
            "language" to Settings.ACTION_LOCALE_SETTINGS,
            "security" to Settings.ACTION_SECURITY_SETTINGS,
            "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            "settings" to Settings.ACTION_SETTINGS
        )
    }
}
