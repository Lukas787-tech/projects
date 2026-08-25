package com.cozyhollow.riverside

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.sin

object FPhase {
    const val IDLE = 0
    const val CAST = 1
    const val WAIT = 2
    const val BITE = 3
    const val REEL = 4
    const val CAUGHT = 5
    const val LOST = 6
}

/**
 * Cast, wait for the bob to dip, hook it, then keep the fish inside the basket
 * while you reel. Holding anywhere on screen raises the basket.
 */
class Fishing {

    var phase = FPhase.IDLE
    var t = 0f
    private var waitFor = 0f

    var bobX = 0f
    var fishId: String? = null
    private var difficulty = 0.3f

    // reel state, all normalised 0..1 along the track
    private var zone = 0.28f
    private var zoneV = 0f
    private var zoneH = 0.22f
    private var fishPos = 0.5f
    private var fishV = 0f
    private var fishTarget = 0.5f
    private var fishTimer = 0f
    var progress = 0.28f
    var caughtSize = 0f

    private val fill = Paint()
    private val grad = Paint()
    private val stroke = Paint().apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rf = RectF()

    private fun rr(l: Float, tp: Float, r: Float, b: Float): RectF {
        rf.set(l, tp, r, b); return rf
    }

    val active: Boolean get() = phase != FPhase.IDLE

    fun cancel() {
        phase = FPhase.IDLE; t = 0f; fishId = null
    }

    fun cast(targetX: Float, rodLevel: Int, weather: Int) {
        bobX = targetX
        phase = FPhase.CAST
        t = 0f
        val speedUp = 1f - (rodLevel - 1) * 0.16f
        val rainBonus = if (weather == Weather.RAIN) 0.7f else 1f
        waitFor = (1.4f + U.hash((targetX * 7f).toInt()) * 4.4f) * speedUp * rainBonus
        zoneH = 0.20f + (rodLevel - 1) * 0.055f
        zone = 0.22f; zoneV = 0f
        fishPos = 0.55f; fishV = 0f; fishTarget = 0.5f; fishTimer = 0f
        progress = 0.30f
    }

    /** Picks what is biting, weighted by clock, weather and rod quality. */
    private fun pickFish(minutes: Float, weather: Int, rodLevel: Int) {
        var total = 0f
        val weights = FloatArray(Catalog.fish.size)
        for (i in Catalog.fish.indices) {
            val f = Catalog.fish[i]
            val inWindow = minutes >= f.fromMin && minutes <= f.toMin
            if (!inWindow) { weights[i] = 0f; continue }
            var w = f.weight
            if (weather == Weather.RAIN) w *= (1f + f.rainBonus)
            // a better rod nudges the odds toward the rarer end of the table
            if (f.weight < 15f) w *= (1f + (rodLevel - 1) * 0.75f)
            weights[i] = w
            total += w
        }
        if (total <= 0f) {
            fishId = "f_minnow"; difficulty = 0.12f; return
        }
        var roll = U.hash((minutes * 31f).toInt() + (System.nanoTime() and 0xFFFFL).toInt()) * total
        for (i in Catalog.fish.indices) {
            roll -= weights[i]
            if (roll <= 0f) {
                fishId = Catalog.fish[i].id
                difficulty = Catalog.fish[i].difficulty
                return
            }
        }
        fishId = Catalog.fish[0].id
        difficulty = Catalog.fish[0].difficulty
    }

    /** Returns an [Sfx] id to play, or -1. */
    fun update(dt: Float, holding: Boolean, minutes: Float, weather: Int, rodLevel: Int): Int {
        t += dt
        when (phase) {
            FPhase.CAST -> {
                if (t >= 0.55f) { phase = FPhase.WAIT; t = 0f }
            }
            FPhase.WAIT -> {
                if (t >= waitFor) {
                    pickFish(minutes, weather, rodLevel)
                    phase = FPhase.BITE; t = 0f
                    return Sfx.BITE
                }
            }
            FPhase.BITE -> {
                // 1.1s to react
                if (t > 1.1f) { phase = FPhase.WAIT; t = 0f; waitFor = 1.6f + U.hash(t.toInt()) * 3f }
            }
            FPhase.REEL -> {
                updateReel(dt, holding)
                if (progress >= 1f) {
                    phase = FPhase.CAUGHT; t = 0f
                    caughtSize = 0.6f + U.hash((System.nanoTime() and 0xFFFL).toInt()) * 0.8f
                    return Sfx.CATCH
                }
                if (progress <= 0f) {
                    phase = FPhase.LOST; t = 0f
                    return Sfx.FAIL
                }
            }
            FPhase.CAUGHT, FPhase.LOST -> {
                if (t > 2.2f) phase = FPhase.IDLE
            }
        }
        return -1
    }

    /** Call when the player taps during [FPhase.BITE]. */
    fun hook(): Boolean {
        if (phase != FPhase.BITE) return false
        phase = FPhase.REEL
        t = 0f
        return true
    }

    private fun updateReel(dt: Float, holding: Boolean) {
        // basket physics
        zoneV += (if (holding) 1.9f else -1.55f) * dt
        zoneV *= (1f - 1.6f * dt)
        zone += zoneV * dt
        if (zone < 0f) { zone = 0f; zoneV = kotlin.math.max(0f, zoneV) }
        if (zone > 1f - zoneH) { zone = 1f - zoneH; zoneV = kotlin.math.min(0f, zoneV) }

        // fish darts toward new targets
        fishTimer -= dt
        if (fishTimer <= 0f) {
            fishTimer = U.lerp(1.05f, 0.34f, difficulty) * (0.6f + U.hash((t * 977f).toInt()) * 0.9f)
            fishTarget = U.hash((t * 613f).toInt() + 7)
            // rarer fish like the extremes of the track
            if (difficulty > 0.6f) fishTarget = if (fishTarget < 0.5f) fishTarget * 0.55f else 1f - (1f - fishTarget) * 0.55f
        }
        val accel = U.lerp(2.4f, 7.5f, difficulty)
        fishV += (fishTarget - fishPos) * accel * dt
        fishV *= (1f - 2.4f * dt)
        fishPos = (fishPos + fishV * dt).coerceIn(0f, 1f)

        val zc = zone + zoneH / 2f
        val inside = abs(fishPos - zc) < zoneH / 2f
        progress += if (inside) 0.30f * dt else -(0.13f + difficulty * 0.14f) * dt
        progress = progress.coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------ drawing

    /** The reeling mini-game overlay, drawn in UI space. */
    fun drawUi(c: Canvas, vw: Float, vh: Float, time: Float) {
        when (phase) {
            FPhase.REEL -> drawReel(c, vw, vh, time)
            FPhase.CAUGHT -> drawResult(c, vw, vh, true)
            FPhase.LOST -> drawResult(c, vw, vh, false)
            FPhase.WAIT, FPhase.CAST -> {
                Ui.text(c, "Waiting for a nibble...", vw / 2f, vh - 44f, 22f,
                    U.withAlpha(Pal.cream, 0.85f), Paint.Align.CENTER, Ui.body)
            }
            FPhase.BITE -> {
                val pulse = 0.7f + 0.3f * sin(time * 0.02f)
                Ui.textOut(c, "TAP!", vw / 2f, vh - 40f, 40f * pulse, Pal.gold,
                    Pal.woodDeep, Paint.Align.CENTER, Ui.display, 6f)
            }
        }
    }

    private fun drawReel(c: Canvas, vw: Float, vh: Float, time: Float) {
        val trackH = vh * 0.60f
        val trackW = 70f
        val x = vw - 130f
        val y = (vh - trackH) / 2f - 10f

        // frame
        fill.color = U.withAlpha(Pal.shadow, 0.28f)
        c.drawRoundRect(rr(x - 12f + 4f, y - 12f + 6f, x + trackW + 12f + 4f, y + trackH + 12f + 6f), 22f, 22f, fill)
        fill.color = Pal.woodDeep
        c.drawRoundRect(rr(x - 12f, y - 12f, x + trackW + 12f, y + trackH + 12f), 22f, 22f, fill)
        fill.color = Pal.woodDark
        c.drawRoundRect(rr(x - 7f, y - 7f, x + trackW + 7f, y + trackH + 7f), 18f, 18f, fill)

        // water column
        grad.shader = LinearGradient(
            0f, y, 0f, y + trackH,
            intArrayOf(U.lerpColor(Pal.waterTop, Pal.foam, 0.3f), Pal.waterMid, U.shade(Pal.waterDeep, 0.8f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rr(x, y, x + trackW, y + trackH), 14f, 14f, grad)
        grad.shader = null
        for (i in 0 until 7) {
            val wy = y + 18f + i * (trackH / 7f) + sin(time * 0.0016f + i) * 4f
            fill.color = U.withAlpha(Pal.foam, 0.13f)
            c.drawRoundRect(rr(x + 8f, wy, x + trackW - 8f, wy + 3f), 2f, 2f, fill)
        }

        // catch basket (y is inverted: 0 at bottom)
        val zTop = y + trackH * (1f - zone - zoneH)
        val zBot = y + trackH * (1f - zone)
        fill.color = U.withAlpha(Pal.leaf, 0.34f)
        c.drawRoundRect(rr(x + 3f, zTop, x + trackW - 3f, zBot), 12f, 12f, fill)
        stroke.strokeWidth = 3.4f
        stroke.color = U.withAlpha(U.shade(Pal.leaf, 1.25f), 0.95f)
        c.drawRoundRect(rr(x + 3f, zTop, x + trackW - 3f, zBot), 12f, 12f, stroke)
        stroke.strokeWidth = 2f
        stroke.color = U.withAlpha(Pal.cream, 0.5f)
        c.drawLine(x + 10f, zTop + 7f, x + trackW - 10f, zTop + 7f, stroke)

        // the fish
        val fy = y + trackH * (1f - fishPos)
        val id = fishId
        if (id != null) {
            val wig = sin(time * 0.012f) * 3f
            c.save()
            c.translate(x + trackW / 2f, fy + wig)
            IconDraw.draw(c, Catalog.item(id), 0f, 0f, 46f, fill)
            c.restore()
        }

        // progress rail
        val px = x + trackW + 22f
        fill.color = Pal.woodDeep
        c.drawRoundRect(rr(px, y - 6f, px + 20f, y + trackH + 6f), 10f, 10f, fill)
        val ph = trackH * progress
        grad.shader = LinearGradient(
            0f, y + trackH - ph, 0f, y + trackH,
            U.shade(Pal.gold, 1.2f), Pal.goldDeep, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rr(px + 3f, y + trackH - ph, px + 17f, y + trackH), 7f, 7f, grad)
        grad.shader = null

        Ui.textOut(c, "HOLD", x + trackW / 2f, y + trackH + 52f, 24f, Pal.cream, Pal.woodDeep,
            Paint.Align.CENTER, Ui.body, 5f)
    }

    private fun drawResult(c: Canvas, vw: Float, vh: Float, caught: Boolean) {
        val pop = U.easeBack(U.clamp01(t / 0.34f))
        val w = 420f; val h = 190f
        val x = (vw - w) / 2f; val y = vh * 0.30f
        c.save()
        c.translate(vw / 2f, y + h / 2f)
        c.scale(pop, pop)
        c.translate(-vw / 2f, -(y + h / 2f))
        Ui.panel(c, x, y, w, h)
        if (caught) {
            val id = fishId ?: "f_minnow"
            val item = Catalog.item(id)
            Ui.text(c, "Nice catch!", vw / 2f, y + 52f, 30f, Pal.ink, Paint.Align.CENTER, Ui.display)
            IconDraw.draw(c, item, x + 92f, y + 118f, 76f, fill)
            Ui.text(c, item.name, x + 148f, y + 108f, 26f, Pal.ink, Paint.Align.LEFT, Ui.body)
            Ui.text(c, "${(caughtSize * 46f + 12f).toInt()} cm", x + 148f, y + 138f, 20f, Pal.inkSoft, Paint.Align.LEFT)
            Ui.coin(c, x + w - 62f, y + 122f, 17f)
            Ui.text(c, "${item.price}", x + w - 84f, y + 130f, 24f, Pal.goldDeep, Paint.Align.RIGHT)
        } else {
            Ui.text(c, "It got away...", vw / 2f, y + 82f, 30f, Pal.ink, Paint.Align.CENTER, Ui.display)
            Ui.text(c, "The river keeps its secrets.", vw / 2f, y + 124f, 21f, Pal.inkSoft, Paint.Align.CENTER)
        }
        c.restore()
    }
}
