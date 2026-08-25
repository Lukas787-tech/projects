package com.cozyhollow.riverside

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.cozyhollow.riverside.gl.Renderer3D
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Hosts the GL renderer and marshals touch input onto the render thread. */
class GameView(ctx: Context, private val game: Game) : GLSurfaceView(ctx) {

    private val r3d = Renderer3D()
    private var lastNs = 0L
    private var lastQuality = -1
    private var fpsAccum = 0f
    private var fpsFrames = 0

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(SceneRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
        keepScreenOn = true
        isFocusable = true
    }

    private inner class SceneRenderer : GLSurfaceView.Renderer {

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                r3d.onSurfaceCreated()
            } catch (e: Throwable) {
                android.util.Log.e("Riverside", "GL setup failed", e)
            }
            lastNs = System.nanoTime()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            try {
                r3d.onSurfaceChanged(width, height, game.settings.quality)
            } catch (e: Throwable) {
                android.util.Log.e("Riverside", "GL resize failed", e)
            }
            game.onResize(width, height)
            lastQuality = game.settings.quality
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime()
            var dt = (now - lastNs) / 1_000_000_000f
            lastNs = now
            if (dt > 0.25f) dt = 0.25f
            if (dt <= 0f) dt = 0.0001f

            fpsAccum += dt
            fpsFrames++
            if (fpsAccum >= 0.5f) {
                game.fps = fpsFrames / fpsAccum
                fpsAccum = 0f; fpsFrames = 0
            }

            // the graphics setting changes the size of the offscreen buffer, so
            // the renderer has to be told when the player changes it
            if (game.settings.quality != lastQuality) {
                lastQuality = game.settings.quality
                try {
                    r3d.onQualityChanged(lastQuality)
                } catch (e: Throwable) {
                    android.util.Log.e("Riverside", "quality change failed", e)
                }
            }

            try {
                game.update(dt)
                r3d.drawFrame(game)
            } catch (e: Throwable) {
                android.util.Log.e("Riverside", "frame failed", e)
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val s = game.scale
        if (s <= 0f) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                val x = ev.getX(i) / s; val y = ev.getY(i) / s
                queueEvent { game.onPointerDown(id, x, y) }
            }
            MotionEvent.ACTION_MOVE -> {
                val n = ev.pointerCount
                val ids = IntArray(n) { ev.getPointerId(it) }
                val xs = FloatArray(n) { ev.getX(it) / s }
                val ys = FloatArray(n) { ev.getY(it) / s }
                queueEvent {
                    for (k in 0 until n) game.onPointerMove(ids[k], xs[k], ys[k])
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                val x = ev.getX(i) / s; val y = ev.getY(i) / s
                queueEvent { game.onPointerUp(id, x, y) }
            }
            MotionEvent.ACTION_CANCEL -> queueEvent { game.onCancelTouch() }
        }
        return true
    }
}
