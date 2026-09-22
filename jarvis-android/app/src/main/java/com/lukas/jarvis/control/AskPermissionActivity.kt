package com.lukas.jarvis.control

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Asks for one permission from wherever the need turned up.
 *
 * A tool runs a long way from any activity, and Android only lets an activity
 * raise a permission dialog. The alternative was what this replaces: when the
 * permission for sending a text was missing, the message was handed to the
 * phone's messaging app instead — which pushed Jarvis into the background and
 * looked for all the world like the app had closed.
 *
 * This is invisible and lives for about a second. The system dialog appears
 * over whatever was already on screen, so nothing is taken away from the user
 * and Jarvis is still there underneath when they answer.
 */
class AskPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = intent?.getStringArrayExtra(EXTRA_PERMISSIONS).orEmpty()
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            finish()
            return
        }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
        // No animation: this had no visible content to slide away.
        overridePendingTransition(0, 0)
    }

    companion object {

        private const val EXTRA_PERMISSIONS = "permissions"
        private const val REQUEST = 91

        fun ask(context: Context, vararg permissions: String) {
            val intent = Intent(context.applicationContext, AskPermissionActivity::class.java)
                .putExtra(EXTRA_PERMISSIONS, arrayOf(*permissions))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.applicationContext.startActivity(intent) }
        }
    }
}
