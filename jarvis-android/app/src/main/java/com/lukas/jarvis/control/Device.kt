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
        val parts = listOf(battery(), connection(), storage(), ringer())
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
