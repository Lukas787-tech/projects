package com.lukas.jarvis.maps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the phone is, using the platform LocationManager only.
 *
 * Play Services' fused provider is nicer, but it is also a dependency and a
 * Google account away; the plain manager is already on every device and a fix
 * good to a hundred metres is all a "what is near me" question needs.
 */
class Locator(context: Context) {

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val prefs = app.getSharedPreferences("jarvis_location", Context.MODE_PRIVATE)

    val hasPermission: Boolean
        get() = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any {
            ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * A fix, or null if there is none to be had. A cached fix is preferred while
     * it is fresh, because waiting on GPS for a question about the next street
     * over is a long silence for nothing.
     */
    suspend fun current(
        maxAgeMillis: Long = 3 * 60_000L,
        timeoutMillis: Long = 9_000L
    ): GeoPoint? {
        if (!hasPermission || manager == null) return remembered()

        bestLastKnown(maxAgeMillis)?.let { return remember(it) }

        val live = withTimeoutOrNull(timeoutMillis) { requestSingleFix() }
        return live?.let { remember(it) } ?: bestLastKnown(Long.MAX_VALUE)?.let { remember(it) }
            ?: remembered()
    }

    /** The freshest usable fix any provider already has. */
    @SuppressLint("MissingPermission") // hasPermission is checked by every caller
    private fun bestLastKnown(maxAgeMillis: Long): Location? {
        val manager = manager ?: return null
        val now = System.currentTimeMillis()
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider ->
                        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .filter { now - it.time <= maxAgeMillis }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission") // hasPermission is checked by every caller
    private suspend fun requestSingleFix(): Location? = suspendCancellableCoroutine { cont ->
        val manager = manager
        val provider = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).firstOrNull { runCatching { manager?.isProviderEnabled(it) == true }.getOrDefault(false) }

        if (manager == null || provider == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        // A plain object, not a lambda: removeUpdates needs the same instance back.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { manager.removeUpdates(this) }
                if (cont.isActive) cont.resume(location)
            }

            // Deprecated on API 29+ but still abstract on the older devices this
            // app supports, so both stay.
            @Deprecated("Required on API < 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

            override fun onProviderDisabled(provider: String) = Unit
            override fun onProviderEnabled(provider: String) = Unit
        }

        val started = runCatching {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            true
        }.getOrDefault(false)

        if (!started) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
    }

    /**
     * The last fix survives a restart. A cold start in a basement with the
     * question "anywhere to eat around here" should still get an answer about
     * roughly the right city.
     */
    private fun remember(location: Location): GeoPoint {
        prefs.edit()
            .putFloat(KEY_LAT, location.latitude.toFloat())
            .putFloat(KEY_LON, location.longitude.toFloat())
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
        return GeoPoint(location.latitude, location.longitude)
    }

    /**
     * How old the fix behind the last answer is. A cached fix keeps the app
     * useful in a basement, but a two-day-old one is from another city, and the
     * answer built on it has to say so rather than sound certain.
     */
    fun lastFixAgeMillis(): Long? {
        val at = prefs.getLong(KEY_AT, 0L)
        return if (at <= 0L) null else (System.currentTimeMillis() - at).coerceAtLeast(0L)
    }

    fun remembered(): GeoPoint? {
        if (!prefs.contains(KEY_LAT)) return null
        return GeoPoint(
            prefs.getFloat(KEY_LAT, 0f).toDouble(),
            prefs.getFloat(KEY_LON, 0f).toDouble()
        )
    }

    private companion object {
        const val KEY_LAT = "last_lat"
        const val KEY_LON = "last_lon"
        const val KEY_AT = "last_at"
    }
}
