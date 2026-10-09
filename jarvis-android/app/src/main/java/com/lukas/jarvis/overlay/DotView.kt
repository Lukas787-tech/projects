package com.lukas.jarvis.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Choreo
import com.lukas.jarvis.ui.character.MochiPalette
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.character.Sprites
import com.lukas.jarvis.ui.theme.cafeColors
import com.lukas.jarvis.vm.Stage
import kotlin.math.min

/**
 * Mochi floating over other apps, drawn without Compose.
 *
 * A Compose view in a window owned by a service needs a lifecycle owner, a
 * saved-state owner and a recomposer wired up by hand, and every one of those
 * is a crash in someone's notification shade if it is wrong. This is the same
 * sprite on a paper disc, drawn straight onto a canvas: the same frames the
 * app shows, at whole-pixel sizes, and nothing that can fail outside the app.
 */
class DotView(context: Context, accentId: String = "caramel", dark: Boolean = false) : View(context) {

    var stage: Stage = Stage.Idle
        set(value) {
            if (field == value) return
            field = value
            frames = build(value)
            invalidate()
        }

    /** Voice loudness 0..1, which opens Mochi's mouth while it speaks. */
    var level: Float = 0f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (kotlin.math.abs(field - next) < 0.05f) return
            field = next
            invalidate()
        }

    private val palette = MochiPalette.from(cafeColors(dark, accentId)).argb
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(0x2B, 0x22, 0x1E) else Color.rgb(0xFF, 0xFD, 0xF9) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = if (dark) Color.rgb(0x3A, 0x2E, 0x28) else Color.rgb(0xEF, 0xE4, 0xD6)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 0x3A, 0x2E, 0x28) }
    private val pixels = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val src = Rect()
    private val dst = Rect()

    private var frames: List<Bitmap> = build(Stage.Idle)
    private var tick = 0

    // One animator at Mochi's own eight frames a second. It runs only while the
    // view is attached: a forgotten animator in a service is a battery complaint.
    private val ticker = ValueAnimator.ofInt(0, Choreo.FPS).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            val next = it.animatedValue as Int
            if (next != tick % Choreo.FPS) {
                tick++
                invalidate()
            }
        }
    }

    private fun state(stage: Stage) = CharacterState(
        mood = when (stage) {
            Stage.Idle -> Mood.Idle
            Stage.Listening -> Mood.Listening
            Stage.Thinking -> Mood.Thinking
            Stage.Speaking -> Mood.Speaking
        },
        talking = stage == Stage.Speaking
    )

    private fun bitmap(state: CharacterState, t: Int, mouth: com.lukas.jarvis.ui.character.Mouth?): Bitmap {
        val cells = Sprites.compose(Choreo.pose(state, t, talkMouth = mouth))
        val argb = IntArray(cells.size) { palette[cells[it].toInt()] }
        return Bitmap.createBitmap(argb, Sprites.W, Sprites.H, Bitmap.Config.ARGB_8888)
    }

    private fun build(stage: Stage): List<Bitmap> {
        val state = state(stage)
        talking.clear()
        return (0 until Choreo.loopLength(state).coerceAtLeast(1)).map { t -> bitmap(state, t, null) }
    }

    // While speaking the mouth follows the voice, so those frames are made as
    // they are needed and kept: a loop times a handful of mouth shapes.
    private val talking = HashMap<Pair<Int, com.lukas.jarvis.ui.character.Mouth>, Bitmap>()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 4f
        canvas.drawCircle(cx, cy + 3f, radius, shadow)
        canvas.drawCircle(cx, cy, radius, disc)
        canvas.drawCircle(cx, cy, radius, edge)

        val t = tick % frames.size
        val frame = if (stage == Stage.Speaking) {
            val mouth = Choreo.mouthFor(level)
            talking.getOrPut(t to mouth) { bitmap(state(stage), t, mouth) }
        } else {
            frames[t]
        }
        // The largest whole-pixel scale that keeps the sprite inside the disc.
        val scale = ((radius * 1.85f) / Sprites.W).toInt().coerceAtLeast(1)
        val w = Sprites.W * scale
        val h = Sprites.H * scale
        src.set(0, 0, Sprites.W, Sprites.H)
        dst.set((cx - w / 2f).toInt(), (cy - h / 2f).toInt(), (cx + w / 2f).toInt(), (cy + h / 2f).toInt())
        canvas.drawBitmap(frame, src, dst, pixels)
    }
}
