package com.lukas.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
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
 * Place reminders through Android's own location service, as far as it can
 * be driven without a phone: the reminder is armed as a proximity alert, the
 * phone "moves", and what the location service fires reaches the receiver,
 * which posts the notification. Only the physical GPS is left out.
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

    private fun moveTo(point: GeoPoint) {
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = point.lat
            longitude = point.lon
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        shadowOf(locations).simulateLocation(fix)
        shadowOf(Looper.getMainLooper()).idle()
    }

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
        assertTrue("fired on arrival: ${titles()}", "Buy milk" in titles())
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
        assertTrue("fired on coming back: ${titles()}", "Water the plants" in titles())
    }

    @Test
    fun leavingFiresOnTheWayOut() {
        moveTo(home)
        app.container.placeReminders.add("take the keys", "home", home, leaving = true, every = false, here = home)
        moveTo(home)
        assertTrue("Take the keys" !in titles())

        moveTo(away)
        assertTrue("fired on leaving: ${titles()}", "Take the keys" in titles())
    }

    @Test
    fun everyTimeStaysAfterFiring() {
        moveTo(away)
        app.container.placeReminders.add("stretch", "the gym", home, leaving = false, every = true, here = away)
        moveTo(home)
        assertTrue("Stretch" in titles())
        assertEquals(1, app.container.placeReminders.current.size)
    }
}
