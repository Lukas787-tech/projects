package com.cozyhollow.riverside

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

object PT {
    const val LEAF = 0
    const val RAIN = 1
    const val SPLASH = 2
    const val SPARKLE = 3
    const val FIREFLY = 4
    const val POP = 5
    const val COIN = 6
    const val DROP = 7
    const val DUST = 8
    const val HEART = 9
}

/**
 * Pooled, allocation-free particle system. Everything lives in world space; the
 * caller applies the camera transform before [draw].
 */
class Particles(private val cap: Int = 620) {

    private val px = FloatArray(cap)
    private val py = FloatArray(cap)
    private val pvx = FloatArray(cap)
    private val pvy = FloatArray(cap)
    private val life = FloatArray(cap)
    private val maxLife = FloatArray(cap)
    private val size = FloatArray(cap)
    private val rot = FloatArray(cap)
    private val vrot = FloatArray(cap)
    private val col = IntArray(cap)
    private val type = IntArray(cap)
    private var cursor = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val rf = RectF()

    private var rainAcc = 0f
    private var leafAcc = 0f
    private var flyAcc = 0f

    fun clear() {
        for (i in 0 until cap) life[i] = 0f
    }

    fun spawn(
        t: Int, x: Float, y: Float, vx: Float, vy: Float,
        lifeSec: Float, sz: Float, color: Int, spin: Float = 0f
    ) {
        var tries = 0
        while (tries < cap) {
            cursor = (cursor + 1) % cap
            if (life[cursor] <= 0f) break
            tries++
        }
        val i = cursor
        px[i] = x; py[i] = y; pvx[i] = vx; pvy[i] = vy
        life[i] = lifeSec; maxLife[i] = lifeSec
        size[i] = sz; col[i] = color; type[i] = t
        rot[i] = U.hash((x * 13f).toInt() + i) * 6.28f
        vrot[i] = spin
    }

    // ------------------------------------------------------------ bursts

    fun burstHarvest(x: Float, y: Float, color: Int, n: Int) {
        for (k in 0 until n) {
            val a = U.hash(k * 37 + (x.toInt())) * 6.28f
            val sp = 60f + U.hash(k * 91) * 120f
            spawn(PT.POP, x, y, cos(a) * sp, sin(a) * sp - 90f, 0.75f, 5f + U.hash(k * 7) * 5f, color, 6f)
        }
        for (k in 0 until n / 2) {
            spawn(PT.SPARKLE, x + (U.hash(k * 5) - 0.5f) * 40f, y + (U.hash(k * 11) - 0.5f) * 40f,
                0f, -30f, 0.6f, 3.4f, Color.parseColor("#FFF3C0"))
        }
    }

    fun burstCoins(x: Float, y: Float, n: Int) {
        for (k in 0 until n) {
            val a = -1.57f + (U.hash(k * 53) - 0.5f) * 1.6f
            val sp = 150f + U.hash(k * 29) * 130f
            spawn(PT.COIN, x, y, cos(a) * sp, sin(a) * sp, 0.95f, 9f, Pal.gold, 8f)
        }
    }

    fun burstChop(x: Float, y: Float) {
        for (k in 0 until 12) {
            val a = U.hash(k * 61 + 3) * 6.28f
            val sp = 70f + U.hash(k * 17) * 140f
            spawn(PT.POP, x, y, cos(a) * sp, sin(a) * sp - 60f, 0.7f, 4f + U.hash(k) * 4f, Pal.woodDark, 9f)
        }
    }

    fun burstWater(x: Float, y: Float) {
        for (k in 0 until 14) {
            val a = -1.4f + (U.hash(k * 23) - 0.5f) * 2.2f
            spawn(PT.DROP, x + (U.hash(k * 3) - 0.5f) * 30f, y,
                cos(a) * 60f, sin(a) * 90f, 0.55f, 3.6f, Color.parseColor("#9FD4E8"))
        }
    }

    fun splash(x: Float, y: Float, strength: Float) {
        for (k in 0 until (10 * strength).toInt().coerceAtLeast(4)) {
            val a = -1.57f + (U.hash(k * 41) - 0.5f) * 2.0f
            val sp = (90f + U.hash(k * 13) * 130f) * strength
            spawn(PT.DROP, x, y, cos(a) * sp, sin(a) * sp, 0.6f, 3.4f + U.hash(k) * 2.5f, Pal.foam)
        }
        spawn(PT.SPLASH, x, y, 0f, 0f, 0.55f, 10f, Pal.foam)
    }

    fun hearts(x: Float, y: Float, n: Int) {
        for (k in 0 until n) {
            spawn(PT.HEART, x + (U.hash(k * 7) - 0.5f) * 30f, y, (U.hash(k * 3) - 0.5f) * 30f, -60f,
                1.3f, 9f, Pal.berry)
        }
    }

    fun dust(x: Float, y: Float) {
        for (k in 0 until 5) {
            spawn(PT.DUST, x + (U.hash(k * 11) - 0.5f) * 22f, y, (U.hash(k * 5) - 0.5f) * 40f, -18f,
                0.5f, 6f, Color.parseColor("#C9B08C"))
        }
    }

    // ------------------------------------------------------------ ambient

    fun updateAmbient(
        dt: Float, camX: Float, vw: Float, night: Float,
        weather: Int, quality: Float, playerX: Float
    ) {
        // rain
        if (weather == Weather.RAIN) {
            rainAcc += dt * 150f * quality
            while (rainAcc >= 1f) {
                rainAcc -= 1f
                val x = camX - 120f + U.hash((rainAcc * 9871f).toInt() + cursor * 7) * (vw + 300f)
                spawn(PT.RAIN, x, -60f, -130f, 1150f, 1.1f, 12f, Color.parseColor("#CFE4F2"))
            }
        }
        // drifting leaves near the forest
        leafAcc += dt * 2.6f * quality
        while (leafAcc >= 1f) {
            leafAcc -= 1f
            val x = camX - 60f + U.hash(cursor * 13 + 5) * (vw + 120f)
            if (x > World.RIVER_EDGE) continue
            val hue = when ((U.hash(cursor * 3) * 3f).toInt()) {
                0 -> Color.parseColor("#D9A05B")
                1 -> Color.parseColor("#C4703F")
                else -> Color.parseColor("#8FBF6F")
            }
            spawn(PT.LEAF, x, 120f + U.hash(cursor * 29) * 160f, -22f, 34f, 6.5f, 7f, hue, 1.6f)
        }
        // fireflies after dusk, in the woods
        if (night > 0.35f) {
            flyAcc += dt * 3.2f * quality
            while (flyAcc >= 1f) {
                flyAcc -= 1f
                val x = 120f + U.hash(cursor * 47 + 9) * 1000f
                if (kotlin.math.abs(x - playerX) > vw * 0.8f) continue
                spawn(PT.FIREFLY, x, World.GROUND_Y - 30f - U.hash(cursor * 17) * 150f,
                    (U.hash(cursor * 5) - 0.5f) * 22f, (U.hash(cursor * 11) - 0.5f) * 18f,
                    4.5f, 3.2f, Color.parseColor("#DFF59A"))
            }
        }
    }

    fun update(dt: Float) {
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            if (life[i] <= 0f) continue
            when (type[i]) {
                PT.RAIN -> {
                    px[i] += pvx[i] * dt; py[i] += pvy[i] * dt
                    val hitY = if (px[i] > World.RIVER_EDGE) World.WATER_Y else World.GROUND_Y + 4f
                    if (py[i] >= hitY) {
                        life[i] = 0f
                        if (U.hash(i * 7 + (py[i]).toInt()) < 0.4f) {
                            spawn(PT.SPLASH, px[i], hitY, 0f, 0f, 0.3f, 5f, Pal.foam)
                        }
                    }
                }
                PT.LEAF -> {
                    px[i] += (pvx[i] + sin(life[i] * 2.4f) * 26f) * dt
                    py[i] += pvy[i] * dt
                    rot[i] += vrot[i] * dt
                    if (py[i] > World.GROUND_Y - 2f) life[i] = 0f
                }
                PT.FIREFLY -> {
                    px[i] += (pvx[i] + sin(life[i] * 1.7f + i) * 16f) * dt
                    py[i] += (pvy[i] + cos(life[i] * 2.1f + i) * 14f) * dt
                }
                PT.SPLASH -> { /* static expanding ring */ }
                PT.SPARKLE -> { py[i] += pvy[i] * dt }
                PT.HEART -> {
                    px[i] += (pvx[i] + sin(life[i] * 4f) * 18f) * dt
                    py[i] += pvy[i] * dt
                }
                else -> {
                    pvy[i] += 620f * dt
                    px[i] += pvx[i] * dt
                    py[i] += pvy[i] * dt
                    rot[i] += vrot[i] * dt
                }
            }
        }
    }

    fun draw(c: Canvas, camX: Float, vw: Float) {
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            val sx = px[i]
            if (sx < camX - 160f || sx > camX + vw + 160f) continue
            val t = life[i] / maxLife[i]
            when (type[i]) {
                PT.RAIN -> {
                    stroke.strokeWidth = 1.9f
                    stroke.color = U.withAlpha(col[i], 0.52f)
                    c.drawLine(sx, py[i], sx - 3.4f, py[i] - size[i] * 2.4f, stroke)
                }
                PT.SPLASH -> {
                    val e = 1f - t
                    stroke.strokeWidth = 2f
                    stroke.color = U.withAlpha(col[i], t * 0.7f)
                    c.drawCircle(sx, py[i], size[i] * (0.4f + e * 1.9f), stroke)
                }
                PT.LEAF -> {
                    paint.color = U.withAlpha(col[i], U.clamp01(t * 1.6f))
                    c.save(); c.translate(sx, py[i]); c.rotate(rot[i] * 57.3f)
                    rf.set(-size[i], -size[i] * 0.55f, size[i], size[i] * 0.55f)
                    c.drawOval(rf, paint)
                    c.restore()
                }
                PT.FIREFLY -> {
                    val pulse = 0.45f + 0.55f * sin(life[i] * 5.4f + i).let { it * it }
                    paint.color = U.withAlpha(col[i], U.clamp01(t * 1.8f) * pulse * 0.35f)
                    c.drawCircle(sx, py[i], size[i] * 3.6f, paint)
                    paint.color = U.withAlpha(col[i], U.clamp01(t * 1.8f) * pulse)
                    c.drawCircle(sx, py[i], size[i], paint)
                }
                PT.COIN -> {
                    paint.color = U.withAlpha(Pal.goldDeep, U.clamp01(t * 2f))
                    val squash = kotlin.math.abs(cos(rot[i]))
                    rf.set(sx - size[i] * squash, py[i] - size[i], sx + size[i] * squash, py[i] + size[i])
                    c.drawOval(rf, paint)
                    paint.color = U.withAlpha(Pal.gold, U.clamp01(t * 2f))
                    rf.inset(size[i] * 0.22f * squash, size[i] * 0.22f)
                    c.drawOval(rf, paint)
                }
                PT.HEART -> {
                    paint.color = U.withAlpha(col[i], U.clamp01(t * 1.4f))
                    val s = size[i]
                    c.drawCircle(sx - s * 0.4f, py[i] - s * 0.25f, s * 0.5f, paint)
                    c.drawCircle(sx + s * 0.4f, py[i] - s * 0.25f, s * 0.5f, paint)
                    c.save(); c.translate(sx, py[i]); c.rotate(45f)
                    rf.set(-s * 0.55f, -s * 0.55f, s * 0.55f, s * 0.55f)
                    c.drawRect(rf, paint); c.restore()
                }
                PT.SPARKLE -> {
                    paint.color = U.withAlpha(col[i], U.clamp01(t))
                    val s = size[i] * (0.5f + t * 0.9f)
                    c.drawRect(sx - s * 0.24f, py[i] - s, sx + s * 0.24f, py[i] + s, paint)
                    c.drawRect(sx - s, py[i] - s * 0.24f, sx + s, py[i] + s * 0.24f, paint)
                }
                PT.DUST -> {
                    paint.color = U.withAlpha(col[i], U.clamp01(t) * 0.55f)
                    c.drawCircle(sx, py[i], size[i] * (1.4f - t * 0.5f), paint)
                }
                else -> {
                    paint.color = U.withAlpha(col[i], U.clamp01(t * 1.5f))
                    c.save(); c.translate(sx, py[i]); c.rotate(rot[i] * 57.3f)
                    rf.set(-size[i], -size[i] * 0.7f, size[i], size[i] * 0.7f)
                    c.drawRoundRect(rf, size[i] * 0.4f, size[i] * 0.4f, paint)
                    c.restore()
                }
            }
        }
    }
}
