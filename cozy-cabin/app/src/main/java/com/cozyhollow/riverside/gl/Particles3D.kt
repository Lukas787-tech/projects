package com.cozyhollow.riverside.gl

import android.graphics.Color
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
}

/**
 * Pooled 3D particles drawn as camera-facing pixel squares. Positions are in
 * metres; callers that think in gameplay world units convert with [W3.x].
 */
class Particles3D(private val cap: Int = 700) {

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

    fun burstHarvest(worldX: Float, worldZ: Float, heightM: Float, color: Int, n: Int) {
        val x = W3.x(worldX)
        val z = W3.z(worldZ)
        for (k in 0 until n) {
            val a = rnd() * 6.2832f
            val sp = 0.9f + rnd() * 1.9f
            spawn(P3.CHUNK, x, heightM, z, cos(a) * sp, 1.6f + rnd() * 1.6f, sin(a) * sp * 0.5f,
                0.75f, 0.075f + rnd() * 0.06f, color)
        }
        for (k in 0 until n / 2) {
            spawn(P3.SPARK, x + (rnd() - 0.5f) * 0.7f, heightM + rnd() * 0.5f, z,
                0f, 0.7f, 0f, 0.6f, 0.05f, Color.parseColor("#FFF3C0"))
        }
    }

    fun burstCoins(worldX: Float, worldZ: Float, heightM: Float, n: Int) {
        val x = W3.x(worldX)
        val z = W3.z(worldZ)
        for (k in 0 until n) {
            spawn(P3.COIN, x, heightM, z, (rnd() - 0.5f) * 2.4f, 2.8f + rnd() * 1.6f,
                (rnd() - 0.5f) * 0.7f, 1.0f, 0.13f, Color.parseColor("#E8B44A"))
        }
    }

    fun burstChop(worldX: Float, worldZ: Float, heightM: Float) {
        val x = W3.x(worldX)
        val z = W3.z(worldZ) + 0.4f
        for (k in 0 until 14) {
            val a = rnd() * 6.2832f
            val sp = 1.1f + rnd() * 2.2f
            spawn(P3.CHUNK, x, heightM, z, cos(a) * sp, 1.4f + rnd() * 1.8f,
                sin(a) * sp * 0.5f, 0.8f, 0.07f + rnd() * 0.05f, Color.parseColor("#A87646"))
        }
    }

    fun burstWater(worldX: Float, worldZ: Float, heightM: Float) {
        val x = W3.x(worldX)
        val z = W3.z(worldZ)
        for (k in 0 until 16) {
            val a = rnd() * 6.2832f
            spawn(P3.CHUNK, x + (rnd() - 0.5f) * 0.5f, heightM, z,
                cos(a) * 0.7f, 1.1f + rnd() * 0.9f, sin(a) * 0.4f,
                0.55f, 0.045f, Color.parseColor("#9FD4E8"))
        }
    }

    fun splash(worldX: Float, worldZ: Float, strength: Float) {
        val x = W3.x(worldX)
        val z = W3.z(worldZ)
        for (k in 0 until (12 * strength).toInt().coerceAtLeast(5)) {
            val a = rnd() * 6.2832f
            val sp = (0.8f + rnd() * 1.6f) * strength
            spawn(P3.CHUNK, x, W3.WATER_Y, z,
                cos(a) * sp, 1.8f + rnd() * 1.4f, sin(a) * sp * 0.6f,
                0.7f, 0.05f + rnd() * 0.04f, Color.parseColor("#EAF7FB"))
        }
        spawn(P3.RING, x, W3.WATER_Y + 0.02f, z, 0f, 0f, 0f, 0.7f, 0.35f, Color.WHITE)
    }

    fun hearts(worldX: Float, worldZ: Float, heightM: Float, n: Int) {
        val x = W3.x(worldX)
        val z = W3.z(worldZ)
        for (k in 0 until n) {
            spawn(P3.HEART, x + (rnd() - 0.5f) * 0.5f, heightM, z,
                (rnd() - 0.5f) * 0.4f, 1.0f, 0f, 1.4f, 0.14f, Color.parseColor("#D06A72"))
        }
    }

    fun dust(worldX: Float, worldZ: Float) {
        val x = W3.x(worldX)
        val z = W3.z(worldZ)
        for (k in 0 until 4) {
            spawn(P3.CHUNK, x + (rnd() - 0.5f) * 0.3f, 0.04f, z,
                (rnd() - 0.5f) * 0.8f, 0.5f + rnd() * 0.4f, (rnd() - 0.5f) * 0.4f,
                0.45f, 0.06f, Color.parseColor("#C9B08C"))
        }
    }

    // ------------------------------------------------------------ ambient

    fun updateAmbient(dt: Float, camXm: Float, night: Float, weather: Int, quality: Float) {
        if (weather == Weather.RAIN) {
            rainAcc += dt * 120f * quality
            while (rainAcc >= 1f) {
                rainAcc -= 1f
                spawn(
                    P3.RAIN, camXm + (rnd() - 0.5f) * 26f, 9f + rnd() * 3f,
                    -12f + rnd() * 18f, -1.6f, -16f, 0f, 1.1f, 0.05f,
                    Color.parseColor("#CFE4F2")
                )
            }
        }
        leafAcc += dt * 2.2f * quality
        while (leafAcc >= 1f) {
            leafAcc -= 1f
            val x = camXm + (rnd() - 0.5f) * 22f
            if (x > W3.RIVER_X) continue
            val hue = when ((rnd() * 3f).toInt()) {
                0 -> Color.parseColor("#D9A05B")
                1 -> Color.parseColor("#C4703F")
                else -> Color.parseColor("#8FBF6F")
            }
            spawn(P3.LEAF, x, 3.5f + rnd() * 3f, -7f + rnd() * 12f, -0.3f, -0.55f, 0f, 7f, 0.09f, hue)
        }
        if (night > 0.35f) {
            flyAcc += dt * 3f * quality
            while (flyAcc >= 1f) {
                flyAcc -= 1f
                val x = 2f + rnd() * 17f
                if (kotlin.math.abs(x - camXm) > 12f) continue
                spawn(
                    P3.FIREFLY, x, 0.6f + rnd() * 2.2f, -6f + rnd() * 10f,
                    (rnd() - 0.5f) * 0.4f, (rnd() - 0.5f) * 0.3f, (rnd() - 0.5f) * 0.3f,
                    5f, 0.055f, Color.parseColor("#DFF59A")
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
                    val groundY = if (px[i] > W3.RIVER_X) W3.WATER_Y else 0f
                    if (py[i] <= groundY) {
                        life[i] = 0f
                        if (rnd() < 0.35f) {
                            spawn(P3.RING, px[i], groundY + 0.02f, pz[i], 0f, 0f, 0f, 0.35f, 0.14f, Color.WHITE)
                        }
                    }
                }
                P3.LEAF -> {
                    px[i] += (vx[i] + sin(life[i] * 2.2f) * 0.5f) * dt
                    py[i] += vy[i] * dt
                    if (py[i] <= 0.05f) life[i] = 0f
                }
                P3.FIREFLY -> {
                    px[i] += (vx[i] + sin(life[i] * 1.7f + i) * 0.3f) * dt
                    py[i] += (vy[i] + cos(life[i] * 2.1f + i) * 0.25f) * dt
                    pz[i] += vz[i] * dt
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
                    if (py[i] < 0.02f) {
                        py[i] = 0.02f
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
            P3.RAIN -> 0.65f
            P3.RING -> t * 0.75f
            P3.FIREFLY -> {
                val pulse = sin(life[i] * 5.4f + i)
                U.clamp01(t * 1.8f) * (0.35f + 0.65f * pulse * pulse)
            }
            else -> U.clamp01(t * 1.8f)
        }
    }

    fun sizeOf(i: Int): Float = when (kind[i]) {
        P3.RING -> size[i] * (0.4f + (1f - life[i] / maxLife[i]) * 2.4f)
        P3.RAIN -> size[i]
        else -> size[i]
    }
}
