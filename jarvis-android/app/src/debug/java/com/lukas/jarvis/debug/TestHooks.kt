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
        val container = (context.applicationContext as? JarvisApp)?.container ?: return
        if (intent.action == ACTION_ROUTINE) {
            // A quiet routine saved and started at once through WorkManager,
            // exactly as its alarm would start it.
            val name = intent.getStringExtra("name") ?: "device check"
            val step = intent.getStringExtra("step") ?: "What is 2 plus 2?"
            container.routines.save(
                com.lukas.jarvis.auto.Routine(name = name, steps = listOf(step), time = null, quiet = true)
            )
            com.lukas.jarvis.auto.RoutineWorker.enqueue(context, name)
            resultData = "queued $name"
            return
        }
        if (intent.action != ACTION_PLACE) return
        val lat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: return
        val lon = intent.getStringExtra("lon")?.toDoubleOrNull() ?: return
        val routine = intent.getStringExtra("routine")?.takeIf { it.isNotBlank() }
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() }
            ?: routine?.let { "run the $it routine" } ?: "test reminder"
        container.placeReminders.add(
            text = text,
            place = "the test spot",
            point = GeoPoint(lat, lon),
            leaving = intent.getBooleanExtra("leaving", false),
            every = false,
            here = container.locator.remembered(),
            routine = routine
        )
        resultData = "armed: ${container.placeReminders.canWatch}, closed: ${container.placeReminders.canWatchClosed}"
    }

    companion object {
        const val ACTION_PLACE = "com.lukas.jarvis.debug.PLACE"
        const val ACTION_ROUTINE = "com.lukas.jarvis.debug.ROUTINE"
    }
}
