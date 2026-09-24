package com.lukas.jarvis.control

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the text on the screen, once, when asked to.
 *
 * "Summarise this article", "what does this say", "reply to this" — asked
 * through the floating dot or the wake word while another app is open — need
 * the words that app is showing. The only way Android offers to read another
 * app's screen is an accessibility service, so this is one, separate from the
 * one that presses send and switched on separately.
 *
 * It is held to the narrowest use: events are ignored entirely, nothing is
 * read until a request asks for it, only the app windows on screen are read
 * (never Jarvis itself, the keyboard or the system bars), and what it read is
 * returned to that one request and kept nowhere.
 */
class ScreenReader : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    // Deliberately inert: this service does nothing in response to events.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun read(limit: Int): Reading? {
        val own = packageName
        val roots = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { runCatching { it.root }.getOrNull() }
            .filter { it.packageName?.toString() != own }
            .ifEmpty {
                listOfNotNull(runCatching { rootInActiveWindow }.getOrNull())
                    .filter { it.packageName?.toString() != own }
            }
        // Only Jarvis is showing: nothing else to read, which is not the same
        // as reading being switched off.
        if (roots.isEmpty()) return Reading("", "")

        val lines = LinkedHashSet<String>()
        var total = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 60 || total > limit) return
            if (!node.isVisibleToUser) return
            val text = node.text?.toString()?.trim().orEmpty()
            val label = node.contentDescription?.toString()?.trim().orEmpty()
            listOf(text, label).filter { it.length > 1 }.forEach { piece ->
                if (lines.add(piece)) total += piece.length + 1
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        roots.forEach { walk(it, 0) }
        val app = roots.firstOrNull()?.packageName?.toString().orEmpty()
        return Reading(appLabel(app), lines.joinToString("\n").take(limit))
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    data class Reading(val app: String, val text: String)

    companion object {

        @Volatile
        private var instance: ScreenReader? = null

        val running: Boolean get() = instance != null

        /** The words on screen right now, or null when reading is not switched on. */
        fun capture(limit: Int = 6000): Reading? = instance?.read(limit)

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val mine = ComponentName(context, ScreenReader::class.java).flattenToString()
            return flat.split(':').any { it.equals(mine, ignoreCase = true) }
        }

        fun permissionIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
