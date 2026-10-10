package com.lukas.jarvis

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import com.lukas.jarvis.overlay.BubbleService
import com.lukas.jarvis.overlay.DotView
import com.lukas.jarvis.vm.Stage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/*
 * The floating dot, which only appears when the You room finds it switched
 * on and the phone lets Mochi draw over other apps. No other test and no
 * emulator run had that permission and the switch at once, so a dot that
 * crashed as it was made closed the app every time the You room opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingDotTest {

    @Test
    fun theDotIsMadeAndDrawnInEveryStage() {
        val app = RuntimeEnvironment.getApplication()
        val canvas = Canvas(Bitmap.createBitmap(124, 124, Bitmap.Config.ARGB_8888))
        listOf(false, true).forEach { dark ->
            val dot = DotView(app, "caramel", dark)
            dot.layout(0, 0, 124, 124)
            Stage.entries.forEach { stage ->
                dot.stage = stage
                listOf(0f, 0.5f, 1f).forEach { level ->
                    dot.level = level
                    dot.draw(canvas)
                }
            }
        }
    }

    @Test
    fun switchedOnItComesUpAndStaysUp() {
        val app = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildService(BubbleService::class.java).create()
        controller.startCommand(0, 1)
        val service = shadowOf(controller.get())
        assertNotNull(service.lastForegroundNotification)
        assertFalse("the dot stopped itself as it started", service.isStoppedBySelf)
        controller.destroy()
    }
}
