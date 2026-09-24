package com.lukas.jarvis.surface

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lukas.jarvis.MainActivity

/**
 * The ways into the assistant from outside the app: the launcher shortcuts,
 * the home-screen widget and the Quick Settings tile all open the activity
 * with one of these actions, and the activity alone decides what each means.
 */
object Entry {
    const val TALK = "com.lukas.jarvis.TALK"
    const val TYPE = "com.lukas.jarvis.TYPE"
    const val SCAN = "com.lukas.jarvis.SCAN"
    const val TODAY = "com.lukas.jarvis.TODAY"

    fun intent(context: Context, action: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun pending(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            code,
            intent(context, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
