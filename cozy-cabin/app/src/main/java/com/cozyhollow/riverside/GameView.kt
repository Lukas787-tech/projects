package com.cozyhollow.riverside

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/** SurfaceView with a dedicated render thread running a fixed-ish timestep loop. */
class GameView(ctx: Context, private val game: Game) :
    SurfaceView(ctx), SurfaceHolder.Callback, Runnable {

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var surfaceReady = false

    private var lastNs = 0L
    private var fpsAccum = 0f
    private var fpsFrames = 0

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        surfaceReady = true
        startLoop()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {
        game.onResize(w, hh)
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        stopLoop()
    }

    fun startLoop() {
        if (running || !surfaceReady) return
        running = true
        lastNs = System.nanoTime()
        thread = Thread(this, "riverside-render").apply { start() }
    }

    fun stopLoop() {
        running = false
        try { thread?.join(900) } catch (_: InterruptedException) { }
        thread = null
    }

    override fun run() {
        while (running) {
            val now = System.nanoTime()
            var dt = (now - lastNs) / 1_000_000_000f
            lastNs = now
            if (dt > 0.25f) dt = 0.25f
            if (dt <= 0f) dt = 0.0001f

            fpsAccum += dt
            fpsFrames++
            if (fpsAccum >= 0.5f) {
                game.fps = fpsFrames / fpsAccum
                fpsAccum = 0f
                fpsFrames = 0
            }

            try {
                game.update(dt)
            } catch (_: Throwable) {
            }

            var canvas: Canvas? = null
            val h = holder
            try {
                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    h.lockHardwareCanvas()
                } else {
                    h.lockCanvas()
                }
                if (canvas != null) game.draw(canvas)
            } catch (_: Throwable) {
            } finally {
                if (canvas != null) {
                    try { h.unlockCanvasAndPost(canvas) } catch (_: Throwable) { }
                }
            }

            // don't spin faster than the display needs
            val frameMs = (System.nanoTime() - now) / 1_000_000L
            val target = 15L
            if (frameMs < target) {
                try { Thread.sleep(target - frameMs) } catch (_: InterruptedException) { }
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val s = game.scale
        if (s <= 0f) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                game.onPointerDown(ev.getPointerId(i), ev.getX(i) / s, ev.getY(i) / s)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    game.onPointerMove(ev.getPointerId(i), ev.getX(i) / s, ev.getY(i) / s)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                game.onPointerUp(ev.getPointerId(i), ev.getX(i) / s, ev.getY(i) / s)
            }
            MotionEvent.ACTION_CANCEL -> game.onCancelTouch()
        }
        return true
    }
}
