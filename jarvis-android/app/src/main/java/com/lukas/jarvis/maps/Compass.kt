package com.lukas.jarvis.maps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Which way the phone is pointing, in degrees from north.
 *
 * Standing still, a GPS fix has no direction at all, and "which way do I go"
 * is exactly the question asked standing still. The rotation vector sensor
 * answers it; the map draws it as the cone in front of the blue dot.
 */
object Compass {

    fun headings(context: Context): Flow<Float> = callbackFlow {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensors == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        var smoothed = Float.NaN

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                @Suppress("DEPRECATION")
                val display = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                val (x, y) = when (display) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
                SensorManager.getOrientation(remapped, orientation)
                val degrees = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                // Low-passed the short way round, so the cone does not shiver
                // and does not spin through 360 degrees crossing north.
                smoothed = if (smoothed.isNaN()) {
                    degrees
                } else {
                    var delta = degrees - smoothed
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    (smoothed + delta * 0.2f + 360f) % 360f
                }
                trySend(smoothed)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sensors.unregisterListener(listener) }
    }
}
