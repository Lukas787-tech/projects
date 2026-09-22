package com.lukas.jarvis.control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * Placing a call, with exactly one question in the way.
 *
 * Sending the wrong text is embarrassing; ringing the wrong person at two in
 * the morning is not the same kind of mistake, and speech recognition is at its
 * worst on names. So a call is armed first and placed second, and the two steps
 * are separate turns of the conversation: the assistant says who it is about to
 * ring, and the user has to answer. One misheard sentence cannot get through
 * that, because it takes two.
 *
 * The arming expires. A "yes" belongs to the question that was just asked, and
 * a "yes" arriving three minutes later is answering something else.
 */
class Caller(context: Context) {

    private val app = context.applicationContext

    data class Pending(val number: String, val who: String, val armedAt: Long)

    @Volatile
    private var pending: Pending? = null

    val mayCall: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    /** What is waiting on a yes, if anything is still fresh enough to be. */
    fun armed(): Pending? {
        val current = pending ?: return null
        if (System.currentTimeMillis() - current.armedAt > WINDOW_MS) {
            pending = null
            return null
        }
        return current
    }

    /**
     * Readies a call and returns the question to put to the user.
     *
     * Nothing rings here. The number is read back in full so a wrong digit or a
     * wrong Anna is caught while it is still cheap.
     */
    fun arm(number: String, who: String?): String {
        val digits = number.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }
        if (digits.isBlank()) return "That is not a number I can ring."
        pending = Pending(digits, who?.trim().orEmpty(), System.currentTimeMillis())
        val name = who?.trim().orEmpty()
        return if (name.isBlank()) {
            "Ready to ring $digits. Ask them to confirm before you place it."
        } else {
            "Ready to ring $name on $digits. Ask them to confirm before you place it."
        }
    }

    fun cancel(): String {
        val had = pending != null
        pending = null
        return if (had) "Call cancelled." else "There was no call waiting."
    }

    /** Places the armed call. This rings. */
    fun place(): String {
        val target = armed() ?: return "Nothing is waiting to be called. " +
            "Ask me to ring someone and I will check the number with you first."
        pending = null

        if (!mayCall) {
            // Opening the dialler instead would push Jarvis into the background,
            // which is what made this look like the app closing. Asking for the
            // permission leaves the user where they were.
            AskPermissionActivity.ask(app, Manifest.permission.CALL_PHONE)
            pending = Pending(target.number, target.who, System.currentTimeMillis())
            return "I need permission to place calls — it is asking you now. " +
                "Say yes again once you have allowed it and I will ring ${
                    target.who.ifBlank { target.number }
                }."
        }

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${target.number}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(intent)) {
            val name = target.who.ifBlank { target.number }
            "Ringing $name."
        } else {
            "The call would not go through on this phone."
        }
    }

    private fun start(intent: Intent): Boolean = runCatching {
        app.startActivity(intent)
        true
    }.getOrDefault(false)

    private companion object {
        /** How long a yes still belongs to the question that prompted it. */
        const val WINDOW_MS = 90_000L
    }
}
