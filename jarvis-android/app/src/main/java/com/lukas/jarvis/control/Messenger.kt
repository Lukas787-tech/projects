package com.lukas.jarvis.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * Messages that actually leave the phone.
 *
 * Drafting one and leaving it for the user to press send is the safe design and
 * the wrong one for an assistant you talk to with your hands full. Android does
 * allow an app to send a text itself, with the user's permission, so that is
 * what this does: the sentence it returns is about something that has already
 * happened, not something teed up.
 *
 * SMS is the only kind that works this way. WhatsApp, Signal and the rest have
 * no way in for a third app, and the only technique that gets there drives the
 * screen through an accessibility service — an app pretending to be a finger.
 * Replying to those is covered instead by answering their notification, which
 * is a supported door and the same one a smartwatch uses.
 */
class Messenger(context: Context) {

    private val app = context.applicationContext

    val maySend: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Sends a text. Nothing else has to happen for it to arrive.
     *
     * Long messages are split the way the carrier expects rather than truncated:
     * a message that silently loses its second half is worse than one that
     * arrives as two.
     */
    fun sendSms(number: String, body: String): String {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.isBlank()) return "I need a number to send to."
        if (body.isBlank()) return "There was no message to send."
        if (!maySend) {
            return "I do not have permission to send texts yet. " +
                "Grant it in Jarvis settings and I will send it without asking again."
        }

        val manager = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        }.getOrNull() ?: return "This phone has no SMS service I can reach."

        return runCatching {
            val parts = manager.divideMessage(body)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(digits, null, parts, null, null)
            } else {
                manager.sendTextMessage(digits, null, body, null, null)
            }
            "Sent to $digits."
        }.getOrElse { failure ->
            "The text did not go out: ${failure.message ?: "the phone refused it"}."
        }
    }
}
