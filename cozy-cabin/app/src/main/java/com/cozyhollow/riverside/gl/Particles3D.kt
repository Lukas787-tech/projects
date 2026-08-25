package com.cozyhollow.riverside.gl

import android.graphics.Color
import com.cozyhollow.riverside.Terrain
import com.cozyhollow.riverside.U
import com.cozyhollow.riverside.Weather
import kotlin.math.cos
import kotlin.math.sin

object P3 {
    const val CHUNK = 0
    const val SPARK = 1
    const val RAIN = 2
    const val SPLASH = 3
    const val LEAF = 4
    const val FIREFLY = 5
    const val COIN = 6
    const val RING = 7
    const val HEART = 8
    const val BUTTERFLY = 9
    const val POLLEN = 10
}

/**
 * Pooled particles, all in metres. The ambient ones — pollen turning in the
 * afternoon light, butterflies, drifting leaves, fireflies after dark, rain —
 * are spawned in a ring around wherever you are standing, so the valley always
 * has something small moving in it without paying for the whole map.
 */
class Particles3D(private val cap: Int = 800) {

    val px = FloatArray(cap)
    val py = FloatArray(cap)
    val pz = FloatArray(cap)
    private val vx = FloatArray(cap)
    private val vy = FloatArray(cap)
    private val vz = FloatArray(cap)
    val life = FloatArray(cap)
    val maxLife = FloatArray(cap)
    val size = FloatArray(cap)
    val col = IntArray(cap)
    val kind = IntArray(cap)
    private var cursor = 0
    private var rainAcc = 0f
    private var leafAcc = 0f
    private var flyAcc = 0f
    private var pollenAcc = 0f
    private var flutterAcc = 0f
    private var rng = 0x51ED2701

    private fun rnd(): Float {
        rng = rng * 1664525 + 1013904223
        return ((rng ushr 8) and 0xFFFF) / 65535f
    }

    fun clear() {
        for (i in 0 until cap) life[i] = 0f
    }

    fun spawn(
        k: Int, x: Float, y: Float, z: Float,
        dx: Float, dy: Float, dz: Float,
        lifeSec: Float, sz: Float, color: Int
    ) {
        var tries = 0
        while (tries < cap) {
            cursor = (cursor + 1) % cap
            if (life[cursor] <= 0f) break
            tries++
        }
        val i = cursor
        px[i] = x; py[i] = y; pz[i] = z
        vx[i] = dx; vy[i] = dy; vz[i] = dz
        life[i] = lifeSec; maxLife[i] = lifeSec
        size[i] = sz; col[i] = color; kind[i] = k
    }

    // ------------------------------------------------------------- bursts

    fun burstHarvest(x: Float, z: Float, height: Float, color: Int, n: Int) {
        val y = Terrain.groundY(x, z) + height
        for (k in 0 until n) {
            val a = rnd() * 6.2832f
            val sp = 0.9f + rnd() * 1.9f
            spawn(P3.CHUNK, x, y, z, cos(a) * sp, 1.6f + rnd() * 1.6f, sin(a) * sp,
                0.75f, 0.075f + rnd() * 0.06f, color)
        }
        for (k in 0 until n / 2) {
            spawn(P3.SPARK, x + (rnd() - 0.5f) * 0.7f, y + rnd() * 0.5f, z + (rnd() - 0.5f) * 0.7f,
                0f, 0.7f, 0f, 0.6f, 0.05f, Color.parseColor("#FFF3C0"))
        }
    }

    fun burstCoins(x: Float, z: Float, height: Float, n: Int) {
        val y = Terrain.groundY(x, z) + height
        for (k in 0 until n) {
            spawn(P3.COIN, x, y, z, (rnd() - 0.5f) * 2.4f, 2.8f + rnd() * 1.6f,
                (rnd() - 0.5f) * 2.4f, 1.0f, 0.13f, Color.parseColor("#E8B44A"))
        }
    }

    fun burstChop(x: Float, z: Float, height: Float) {
        val y = Terrain.groundY(x, z) + height
        for (k in 0 until 14) {
            val a = rnd() * 6.2832f
            val sp = 1.1f + rnd() * 2.2f
            spawn(P3.CHUNK, x, y, z, cos(a) * sp, 1.4f + rnd() * 1.8f,
                sin(a) * sp, 0.8f, 0.07f + rnd() * 0.05f, Color.parseColor("#A87646"))
        }
    }

    fun burstWater(x: Float, z: Float, height: Float) {
        val y = Terrain.groundY(x, z) + height
        for (k in 0 until 16) {
            val a = rnd() * 6.2832f
            spawn(P3.CHUNK, x + (rnd() - 0.5f) * 0.5f, y, z + (rnd() - 0.5f) * 0.5f,
                cos(a) * 0.7f, 1.1f + rnd() * 0.9f, sin(a) * 0.7f,
                0.55f, 0.045f, Color.parseColor("#9FD4E8"))
        }
    }

    fun splash(x: Float, z: Float, strength: Float) {
        val y = Terrain.WATER_Y
        for (k in 0 until (12 * strength).toInt().coerceAtLeast(5)) {
            val a = rnd() * 6.2832f
            val sp = (0.8f + rnd() * 1.6f) * strength
            spawn(P3.CHUNK, x, y, z, cos(a) * sp, 1.8f + rnd() * 1.4f, sin(a) * sp,
                0.7f, 0.05f + rnd() * 0.04f, Color.parseColor("#EAF7FB"))
        }
        spawn(P3.RING, x, y + 0.03f, z, 0f, 0f, 0f, 0.7f, 0.4f, Color.WHITE)
    }

    fun hearts(x: Float, z: Float, height: Float, n: Int) {
        val y = Terrain.groundY(x, z) + height
        for (k in 0 until n) {
            spawn(P3.HEART, x + (rnd() - 0.5f) * 0.5f, y, z + (rnd() - 0.5f) * 0.5f,
                (rnd() - 0.5f) * 0.4f, 1.0f, 0f, 1.4f, 0.14f, Color.parseColor("#D06A72"))
        }
    }

    fun dust(x: Float, z: Float) {
        val y = Terrain.groundY(x, z) + 0.04f
        for (k in 0 until 4) {
            spawn(P3.CHUNK, x + (rnd() - 0.5f) * 0.3f, y, z + (rnd() - 0.5f) * 0.3f,
                (rnd() - 0.5f) * 0.8f, 0.5f + rnd() * 0.4f, (rnd() - 0.5f) * 0.8f,
                0.45f, 0.06f, Color.parseColor("#C9B08C"))
        }
    }

    // ------------------------------------------------------------ ambient

    private fun ringPos(cx: Float, cz: Float, radius: Float, out: FloatArray) {
        val a = rnd() * 6.2832f
        val d = sqrt01() * radius
        out[0] = cx + cos(a) * d
        out[1] = cz + sin(a) * d
    }

    private fun sqrt01(): Float {
        val r = rnd()
        return kotlin.math.sqrt(r)
    }

    private val tmp = FloatArray(2)

    fun updateAmbient(dt: Float, cx: Float, cz: Float, night: Float, weather: Int, quality: Float) {
        val day = 1f - night

        if (weather == Weather.RAIN) {
            rainAcc += dt * 150f * quality
            while (rainAcc >= 1f) {
                rainAcc -= 1f
                ringPos(cx, cz, 16f, tmp)
                spawn(P3.RAIN, tmp[0], 11f + rnd() * 3f, tmp[1], -0.8f, -17f, 0f, 1.1f, 0.05f,
                    Color.parseColor("#CFE4F2"))
            }
        }

        // leaves turning loose from the canopy
        leafAcc += dt * 2.6f * quality
        while (leafAcc >= 1f) {
            leafAcc -= 1f
            ringPos(cx, cz, 15f, tmp)
            if (Terrain.isWater(tmp[0], tmp[1])) continue
            val hue = when ((rnd() * 3f).toInt()) {
                0 -> Color.parseColor("#D9A05B")
                1 -> Color.parseColor("#C4703F")
                else -> Color.parseColor("#8FBF6F")
            }
            spawn(P3.LEAF, tmp[0], Terrain.height(tmp[0], tmp[1]) + 3f + rnd() * 3.5f, tmp[1],
                -0.35f, -0.5f, 0.2f, 7.5f, 0.09f, hue)
        }

        if (day > 0.4f) {
            // motes of pollen hanging in the light
            pollenAcc += dt * 7f * quality
            while (pollenAcc >= 1f) {
                pollenAcc -= 1f
                ringPos(cx, cz, 11f, tmp)
                spawn(
                    P3.POLLEN, tmp[0], Terrain.height(tmp[0], tmp[1]) + 0.4f + rnd() * 2.2f, tmp[1],
                    (rnd() - 0.5f) * 0.25f, 0.06f + rnd() * 0.1f, (rnd() - 0.5f) * 0.25f,
                    6f, 0.035f, Color.parseColor("#FFF4CC")
                )
            }
            // and a butterfly or two over the meadow
            flutterAcc += dt * 0.5f * quality
            while (flutterAcc >= 1f) {
                flutterAcc -= 1f
                ringPos(cx, cz, 9f, tmp)
                if (Terrain.isWater(tmp[0], tmp[1])) continue
                val hue = if (rnd() < 0.5f) Color.parseColor("#F2D45A") else Color.parseColor("#E8A0C0")
                spawn(
                    P3.BUTTERFLY, tmp[0], Terrain.height(tmp[0], tmp[1]) + 0.7f + rnd() * 0.8f, tmp[1],
                    (rnd() - 0.5f) * 0.9f, 0f, (rnd() - 0.5f) * 0.9f, 9f, 0.09f, hue
                )
            }
        }

        if (night > 0.3f) {
            flyAcc += dt * 4.5f * quality
            while (flyAcc >= 1f) {
                flyAcc -= 1f
                ringPos(cx, cz, 12f, tmp)
                if (Terrain.isWater(tmp[0], tmp[1])) continue
                spawn(
                    P3.FIREFLY, tmp[0], Terrain.height(tmp[0], tmp[1]) + 0.4f + rnd() * 1.8f, tmp[1],
                    (rnd() - 0.5f) * 0.4f, (rnd() - 0.5f) * 0.25f, (rnd() - 0.5f) * 0.4f,
                    6f, 0.055f, Color.parseColor("#DFF59A")
                )
            }
        }
    }

    fun update(dt: Float) {
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            if (life[i] <= 0f) continue
            when (kind[i]) {
                P3.RAIN -> {
                    px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt
                    val groundY = Terrain.surfaceY(px[i], pz[i])
                    if (py[i] <= groundY) {
                        life[i] = 0f
                        if (rnd() < 0.3f) {
                            spawn(P3.RING, px[i], groundY + 0.03f, pz[i], 0f, 0f, 0f, 0.35f, 0.16f, Color.WHITE)
                        }
                    }
                }
                P3.LEAF -> {
                    px[i] += (vx[i] + sin(life[i] * 2.2f) * 0.5f) * dt
                    py[i] += vy[i] * dt
                    pz[i] += (vz[i] + cos(life[i] * 1.7f) * 0.35f) * dt
                    if (py[i] <= Terrain.surfaceY(px[i], pz[i]) + 0.04f) life[i] = 0f
                }
                P3.FIREFLY, P3.POLLEN -> {
                    px[i] += (vx[i] + sin(life[i] * 1.7f + i) * 0.3f) * dt
                    py[i] += (vy[i] + cos(life[i] * 2.1f + i) * 0.2f) * dt
                    pz[i] += (vz[i] + cos(life[i] * 1.3f + i) * 0.3f) * dt
                }
                P3.BUTTERFLY -> {
                    // a lazy figure of eight, dipping and rising
                    val t = maxLife[i] - life[i]
                    px[i] += (vx[i] + sin(t * 1.9f) * 0.55f) * dt
                    pz[i] += (vz[i] + cos(t * 1.3f) * 0.55f) * dt
                    py[i] += sin(t * 4.5f) * 0.5f * dt
                    val floor = Terrain.surfaceY(px[i], pz[i]) + 0.35f
                    if (py[i] < floor) py[i] = floor
                }
                P3.RING -> { /* expands in place */ }
                P3.HEART -> {
                    px[i] += (vx[i] + sin(life[i] * 4f) * 0.3f) * dt
                    py[i] += vy[i] * dt
                }
                else -> {
                    vy[i] -= 9.4f * dt
                    px[i] += vx[i] * dt
                    py[i] += vy[i] * dt
                    pz[i] += vz[i] * dt
                    val floor = Terrain.surfaceY(px[i], pz[i]) + 0.02f
                    if (py[i] < floor) {
                        py[i] = floor
                        vy[i] = -vy[i] * 0.32f
                        vx[i] *= 0.55f; vz[i] *= 0.55f
                    }
                }
            }
        }
    }

    fun alphaOf(i: Int): Float {
        val t = life[i] / maxLife[i]
        return when (kind[i]) {
            P3.RAIN -> 0.6f
            P3.RING -> t * 0.75f
            P3.POLLEN -> U.clamp01(t * 2.4f) * U.clamp01((1f - t) * 6f) * 0.55f
            P3.BUTTERFLY -> U.clamp01(t * 3f) * U.clamp01((1f - t) * 8f)
            P3.FIREFLY -> {
                val pulse = sin(life[i] * 5.4f + i)
                U.clamp01(t * 1.8f) * (0.35f + 0.65f * pulse * pulse)
            }
            else -> U.clamp01(t * 1.8f)
        }
    }

    fun sizeOf(i: Int): Float = when (kind[i]) {
        P3.RING -> size[i] * (0.4f + (1f - life[i] / maxLife[i]) * 2.6f)
        P3.BUTTERFLY -> size[i] * (0.85f + 0.3f * sin((maxLife[i] - life[i]) * 16f))
        else -> size[i]
    }
}
