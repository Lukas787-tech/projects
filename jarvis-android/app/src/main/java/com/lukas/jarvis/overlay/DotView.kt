package com.lukas.jarvis.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import com.lukas.jarvis.vm.Stage
import kotlin.math.min
import kotlin.math.sin

/**
 * The floating dot, drawn without Compose.
 *
 * A Compose view in a window owned by a service needs a lifecycle owner, a
 * saved-state owner and a recomposer wired up by hand, and every one of those
 * is a crash in someone's notification shade if it is wrong. This is the same
 * pearl drawn straight onto a canvas: fewer moving parts, and nothing that can
 * fail outside the app's own process.
 */
class DotView(context: Context) : View(context) {

    var stage: Stage = Stage.Idle
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Voice loudness 0..1, which the pearl swells with while listening. */
    var level: Float = 0f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (kotlin.math.abs(field - next) < 0.02f) return
            field = next
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val arcBounds = RectF()

    private var phase = 0f

    // One animator for the breathing and the working arc. It runs only while
    // the view is attached, because a forgotten animator in a service is a
    // battery complaint nobody traces back to here.
    private val ticker = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 3600
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val centreX = width / 2f
        val centreY = height / 2f
        val outer = min(width, height) / 2f

        val glow = when (stage) {
            Stage.Idle -> 0.34f
            Stage.Listening -> 0.85f
            Stage.Thinking -> 0.58f
            Stage.Speaking -> 1f
        }
        val tint = if (stage == Stage.Thinking) SILVER_SOFT else SILVER

        val pulse = 1f + 0.05f * sin(phase)
        val voice = if (stage == Stage.Listening) level * 0.28f else 0f
        val core = outer * (0.32f * pulse + voice)

        // Halo, so the dot reads over a bright wallpaper as well as a dark one.
        fill.shader = RadialGradient(
            centreX, centreY, outer,
            intArrayOf(
                withAlpha(tint, 0.30f * glow),
                withAlpha(tint, 0.10f * glow),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centreX, centreY, outer, fill)

        ring.shader = null
        ring.strokeWidth = 1.5f
        ring.color = withAlpha(tint, 0.25f + 0.30f * glow)
        canvas.drawCircle(centreX, centreY, outer * 0.68f, ring)

        fill.shader = RadialGradient(
            centreX - core * 0.2f, centreY - core * 0.24f, core * 1.7f,
            intArrayOf(withAlpha(Color.WHITE, 0.92f * glow), tint, withAlpha(tint, 0.65f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centreX, centreY, core, fill)

        if (stage == Stage.Thinking) {
            val radius = outer * 0.68f
            arcBounds.set(
                centreX - radius, centreY - radius,
                centreX + radius, centreY + radius
            )
            val turn = Math.toDegrees(phase.toDouble()).toFloat() * 3f
            ring.strokeWidth = 2.4f
            ring.shader = SweepGradient(
                centreX, centreY,
                intArrayOf(Color.TRANSPARENT, withAlpha(tint, 0.95f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.12f, 0.3f)
            )
            canvas.save()
            canvas.rotate(turn, centreX, centreY)
            canvas.drawArc(arcBounds, 0f, 110f, false, ring)
            canvas.restore()
            ring.shader = null
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha.coerceIn(0f, 1f) * 255).toInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private companion object {
        val SILVER = Color.rgb(0xE8, 0xEA, 0xED)
        val SILVER_SOFT = Color.rgb(0x9C, 0xA0, 0xA8)
    }
}
