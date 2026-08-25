package com.cozyhollow.riverside

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.sin

object FPhase {
    const val IDLE = 0
    const val CAST = 1
    const val WAIT = 2
    const val CAUGHT = 3
}

/**
 * Fishing, without the fight.
 *
 * You cast, the float sits out there and turns slow circles on the water, and
 * after a little while something takes it. There is no timing window and no
 * way to lose the fish — the waiting *is* the activity. Sit and watch the
 * ripples; the game will tell you when it lands.
 */
class Fishing {

    var phase = FPhase.IDLE
    var t = 0f
    private var waitFor = 0f

    var bobX = 0f
    var bobZ = 0f
    var fishId: String? = null
    var caughtSize = 0f

    val active: Boolean get() = phase != FPhase.IDLE

    /** True once the float is down and the ripples have started. */
    val settled: Boolean get() = phase == FPhase.WAIT || phase == FPhase.CAUGHT

    fun cancel() {
        phase = FPhase.IDLE; t = 0f; fishId = null
    }

    fun cast(targetX: Float, targetZ: Float, rodLevel: Int, weather: Int) {
        bobX = targetX
        bobZ = targetZ
        phase = FPhase.CAST
        t = 0f
        val speedUp = 1f - (rodLevel - 1) * 0.12f
        val rainBonus = if (weather == Weather.RAIN) 0.75f else 1f
        waitFor = (2.6f + U.hash((targetX * 37f + targetZ * 11f).toInt()) * 4.2f) * speedUp * rainBonus
    }

    /** Picks what came along, weighted by the clock and the weather. */
    private fun pickFish(minutes: Float, weather: Int, rodLevel: Int) {
        var total = 0f
        val weights = FloatArray(Catalog.fish.size)
        for (i in Catalog.fish.indices) {
            val f = Catalog.fish[i]
            if (minutes < f.fromMin || minutes > f.toMin) { weights[i] = 0f; continue }
            var w = f.weight
            if (weather == Weather.RAIN) w *= (1f + f.rainBonus)
            if (f.weight < 15f) w *= (1f + (rodLevel - 1) * 0.6f)
            weights[i] = w
            total += w
        }
        if (total <= 0f) {
            fishId = Catalog.fish[0].id
            return
        }
        var roll = U.hash((minutes * 31f).toInt() + (System.nanoTime() and 0xFFFFL).toInt()) * total
        for (i in Catalog.fish.indices) {
            roll -= weights[i]
            if (roll <= 0f) { fishId = Catalog.fish[i].id; return }
        }
        fishId = Catalog.fish[0].id
    }

    /** Returns an [Sfx] id to play, or -1. */
    fun update(dt: Float, minutes: Float, weather: Int, rodLevel: Int): Int {
        t += dt
        when (phase) {
            FPhase.CAST -> {
                if (t >= 0.6f) { phase = FPhase.WAIT; t = 0f; return Sfx.WATER }
            }
            FPhase.WAIT -> {
                if (t >= waitFor) {
                    pickFish(minutes, weather, rodLevel)
                    caughtSize = 0.6f + U.hash((t * 977f).toInt()) * 0.8f
                    phase = FPhase.CAUGHT
                    t = 0f
                    return Sfx.CATCH
                }
            }
            FPhase.CAUGHT -> {
                if (t >= 1.1f) { phase = FPhase.IDLE; t = 0f }
            }
        }
        return -1
    }

    /** How far the float has dipped, for the renderer. */
    fun dip(timeMs: Float): Float = when (phase) {
        FPhase.WAIT -> sin(timeMs * 0.0035f) * 0.035f
        FPhase.CAUGHT -> -0.12f
        else -> 0f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** One quiet line, low on the screen. Nothing to press, nothing to miss. */
    fun drawUi(c: Canvas, vw: Float, vh: Float, timeMs: Float) {
        val msg = when (phase) {
            FPhase.CAST -> "..."
            FPhase.WAIT -> {
                val dots = ((timeMs * 0.002f).toInt() % 3) + 1
                "The float drifts" + ".".repeat(dots)
            }
            FPhase.CAUGHT -> "Something's on the line!"
            else -> return
        }
        val alpha = if (phase == FPhase.CAUGHT) 1f else 0.85f
        Ui.textOut(
            c, msg, vw / 2f, vh - 118f, 26f, Pal.cream, U.withAlpha(Pal.shadow, 0.75f),
            Paint.Align.CENTER, Ui.body, 5f, alpha
        )
        paint.color = U.withAlpha(Pal.cream, 0.0f)
    }
}
