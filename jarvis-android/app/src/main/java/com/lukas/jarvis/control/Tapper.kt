package com.lukas.jarvis.control

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses the send button in a messaging app, once, when asked to.
 *
 * WhatsApp and the rest have no interface a third app may call. A link can open
 * a chat with the message already typed, but the last press is a press, and the
 * only thing on Android that can make it is an accessibility service — the same
 * mechanism a switch or a screen reader uses to operate an app on someone's
 * behalf.
 *
 * That is a strong permission, so this holds itself to the narrowest shape that
 * does the job:
 *
 *  - It does nothing at all unless a send was armed moments earlier. Unarmed, it
 *    ignores every event it is handed.
 *  - The arming names one package and expires after a few seconds, so it cannot
 *    be left waiting to press something later.
 *  - It reads nothing and stores nothing. It looks for one button and clicks it.
 *
 * It is also the most fragile thing in the app, and worth being honest about:
 * it depends on a view id inside someone else's app, and an update that renames
 * that id breaks it until the fallback finds the button by its description.
 */
class Tapper : AccessibilityService() {

    override fun onServiceConnected() {
        connected = true
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val job = armed ?: return
        if (System.currentTimeMillis() > job.expiresAt) {
            armed = null
            return
        }
        if (event?.packageName?.toString() != job.packageName) return

        val root = rootInActiveWindow ?: return
        val button = findSendButton(root, job.packageName) ?: return
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            armed = null
        }
    }

    /**
     * The send button, by id first and by description second.
     *
     * The id is exact and survives translation; the description survives an id
     * being renamed. Between them one usually holds.
     */
    private fun findSendButton(
        root: AccessibilityNodeInfo,
        packageName: String
    ): AccessibilityNodeInfo? {
        SEND_IDS.forEach { suffix ->
            val byId = runCatching {
                root.findAccessibilityNodeInfosByViewId("$packageName:id/$suffix")
            }.getOrNull().orEmpty()
            byId.firstOrNull { it.isClickable && it.isVisibleToUser }?.let { return it }
        }
        return firstClickableDescribedAsSend(root)
    }

    private fun firstClickableDescribedAsSend(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val described = node.contentDescription?.toString()?.lowercase().orEmpty()
        if (node.isClickable && node.isVisibleToUser && SEND_WORDS.any { described.contains(it) }) {
            return node
        }
        for (index in 0 until node.childCount) {
            firstClickableDescribedAsSend(node.getChild(index))?.let { return it }
        }
        return null
    }

    companion object {

        private data class Job(val packageName: String, val expiresAt: Long)

        @Volatile
        private var armed: Job? = null

        @Volatile
        private var connected = false

        /** View ids used by the apps this is aimed at. */
        private val SEND_IDS = listOf("send", "send_btn", "btn_send")

        /** Enough languages to cover a phone that is not set to English. */
        private val SEND_WORDS = listOf("send", "senden", "abschicken", "envoyer", "enviar", "invia")

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val mine = ComponentName(context, Tapper::class.java).flattenToString()
            return flat.split(':').any { it.equals(mine, ignoreCase = true) }
        }

        /** The system page where this is switched on; there is no in-app way. */
        fun permissionIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Watch [packageName] for a send button and press it once.
         *
         * The window is short on purpose. It has to outlast an app cold-starting
         * and drawing a chat, and it must not outlast the user's attention: an
         * arming still live a minute later would press send on whatever they
         * happened to be typing themselves.
         */
        fun armFor(packageName: String, windowMs: Long = 9_000L) {
            armed = Job(packageName, System.currentTimeMillis() + windowMs)
        }

        fun disarm() {
            armed = null
        }

        val running: Boolean get() = connected
    }
}
