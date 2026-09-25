package com.lukas.jarvis.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.maps.GeoPoint

/**
 * Debug builds only: lets the device test set a place reminder without going
 * through a model, so what it checks is Android's own proximity alert on a
 * moving GPS, not whether a free model happened to answer.
 *
 * adb shell am broadcast -n com.lukas.jarvis.debug/com.lukas.jarvis.debug.TestHooks \
 *   -a com.lukas.jarvis.debug.PLACE --es text "buy milk" --es lat 52.52 --es lon 13.405
 */
class TestHooks : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PLACE) return
        val container = (context.applicationContext as? JarvisApp)?.container ?: return
        val lat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: return
        val lon = intent.getStringExtra("lon")?.toDoubleOrNull() ?: return
        val text = intent.getStringExtra("text") ?: "test reminder"
        container.placeReminders.add(
            text = text,
            place = "the test spot",
            point = GeoPoint(lat, lon),
            leaving = intent.getBooleanExtra("leaving", false),
            every = false,
            here = container.locator.remembered()
        )
        resultData = "armed: ${container.placeReminders.canWatch}, closed: ${container.placeReminders.canWatchClosed}"
    }

    companion object {
        const val ACTION_PLACE = "com.lukas.jarvis.debug.PLACE"
    }
}
