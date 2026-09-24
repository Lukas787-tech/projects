package com.lukas.jarvis.surface

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Jarvis in the Quick Settings panel: pull down, tap, talk. Works over any app
 * and from the lock screen's shade, which is often closer to hand than the
 * app's icon.
 */
class JarvisTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "Tap to talk"
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val open = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // From Android 14 only the PendingIntent form is allowed.
                startActivityAndCollapse(Entry.pending(this, Entry.TALK, 7))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(Entry.intent(this, Entry.TALK))
            }
        }
        if (isLocked) unlockAndRun { open() } else open()
    }
}
