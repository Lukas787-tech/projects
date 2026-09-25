package com.lukas.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.location.LocationManager
import android.os.Looper
import com.lukas.jarvis.maps.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/*
 * Place reminders from the moment Android reports a crossing: the broadcast
 * the location service sends for a proximity alert (entering or leaving) is
 * delivered to the app's receiver, which decides, posts the notification and
 * updates the store. Robolectric does not run proximity alerts itself, so the
 * test plays the location service's part; the watching of the circle is the
 * one piece only a phone exercises.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceAlertTest {

    private lateinit var app: JarvisApp
    private lateinit var locations: LocationManager
    private val home = GeoPoint(52.5200, 13.4050)
    private val away = GeoPoint(52.5400, 13.4050) // about 2 km north

    @Before
    fun phone() {
        app = RuntimeEnvironment.getApplication() as JarvisApp
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
        locations = app.getSystemService(LocationManager::class.java)
        shadowOf(locations).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        app.container.placeReminders.current.forEach { app.container.placeReminders.remove(it.id) }
    }

    /** Where the phone is, as the location service would see it. */
    private var inside = mutableMapOf<Long, Boolean>()
    private var at: GeoPoint = away

    /**
     * Moves the phone and, for every reminder whose circle it crosses, sends
     * the broadcast Android's location service sends.
     */
    private fun moveTo(point: GeoPoint) {
        at = point
        app.container.placeReminders.current.forEach { watch ->
            val now = com.lukas.jarvis.maps.Geo.distance(point, watch.point) <= watch.radius
            val was = inside[watch.id]
            inside[watch.id] = now
            // The service reports the first fix inside, and every change after.
            if ((was == null && now) || (was != null && was != now)) {
                app.sendBroadcast(
                    android.content.Intent(app, com.lukas.jarvis.notify.PlaceReceiver::class.java)
                        .setAction(com.lukas.jarvis.notify.PlaceReminders.ACTION_CROSSED)
                        .putExtra(com.lukas.jarvis.notify.PlaceReminders.EXTRA_ID, watch.id)
                        .putExtra(LocationManager.KEY_PROXIMITY_ENTERING, now)
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** What happened, for a failure message: broadcasts sent and the reminders' state. */
    private fun trace(): String =
        "broadcasts=" + shadowOf(app).broadcastIntents.map { "${it.action} ${it.extras?.keySet()}" } +
            " reminders=" + app.container.placeReminders.current.map { "${it.text}:armed=${it.armed}" }

    private fun titles(): List<String> =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
            .mapNotNull { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }

    @Test
    fun arrivingPostsTheReminderOnce() {
        moveTo(away)
        app.container.placeReminders.add("buy milk", "home", home, leaving = false, every = false, here = away)
        moveTo(away)
        assertTrue("nothing yet while away", "Buy milk" !in titles())

        moveTo(home)
        assertTrue("fired on arrival: ${titles()} ${trace()}", "Buy milk" in titles())
        // Used up: it is gone from the list, and the alert with it.
        assertTrue(app.container.placeReminders.current.isEmpty())
    }

    @Test
    fun setWhileThereWaitsForTheNextArrival() {
        moveTo(home)
        app.container.placeReminders.add("water the plants", "home", home, leaving = false, every = false, here = home)
        moveTo(home)
        assertTrue("not on the spot it was set", "Water the plants" !in titles())

        moveTo(away)
        moveTo(home)
        assertTrue("fired on coming back: ${titles()} ${trace()}", "Water the plants" in titles())
    }

    @Test
    fun leavingFiresOnTheWayOut() {
        moveTo(home)
        app.container.placeReminders.add("take the keys", "home", home, leaving = true, every = false, here = home)
        moveTo(home)
        assertTrue("Take the keys" !in titles())

        moveTo(away)
        assertTrue("fired on leaving: ${titles()} ${trace()}", "Take the keys" in titles())
    }

    @Test
    fun everyTimeStaysAfterFiring() {
        moveTo(away)
        app.container.placeReminders.add("stretch", "the gym", home, leaving = false, every = true, here = away)
        moveTo(home)
        assertTrue("${titles()} ${trace()}", "Stretch" in titles())
        assertEquals(1, app.container.placeReminders.current.size)
    }
}
