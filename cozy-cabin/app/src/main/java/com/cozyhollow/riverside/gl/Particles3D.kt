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
    const val SNOW = 2
    const val SPLASH = 3
    const val EMBER = 4
    const val BREATH = 5
    const val COIN = 6
    const val RING = 7
    const val HEART = 8
    const val STEAM = 9
    const val DRIFT = 10
    const val PRINT = 11
    const val BIRD = 12
}

/**
 * Pooled particles, all in metres.
 *
 * The ambient ones — falling snow, ground drift racing along in a blizzard,
 * embers off the fire, your own breath, steam off the vent — are spawned in a
 * ring around wherever you are standing, so the valley always has something
 * moving in it without paying for the whole map.
 */
class Particles3D(private val cap: Int = 1100) {

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
    /** Per-particle spin, so no two flakes tumble alike. */
    val phase = FloatArray(cap)
    private var cursor = 0
    private var snowAcc = 0f
    private var driftAcc = 0f
    private var steamAcc = 0f
    private var emberAcc = 0f
    private var rng = 0x51ED2701

    /** Set by the game: how hard the wind is pushing everything sideways. */
    var windX = -0.9f
    var windZ = 0.25f

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
        phase[i] = rnd() * 6.2832f
    }

    // ------------------------------------------------------------- bursts

    fun burstHarvest(x: Float, z: Float, height: Float, color: Int, n: Int) {
        val y = Terrain.groundY(x, z) + height
        for (k in 0 until n) {
            val a = rnd() * 6.2832f
            val sp = 0.9f + rnd() * 1.9f
            spawn(
                P3.CHUNK, x, y, z, cos(a) * sp, 1.6f + rnd() * 1.6f, sin(a) * sp,
                0.75f, 0.075f + rnd() * 0.06f, color
            )
        }
        for (k in 0 until n / 2) {
            spawn(
                P3.SPARK, x + (rnd() - 0.5f) * 0.7f, y + rnd() * 0.5f, z + (rnd() - 0.5f) * 0.7f,
                0f, 0.7f, 0f, 0.6f, 0.05f, Color.parseColor("#FFF0C8")
            )
        }
    }

    fun burstCoins(x: Float, z: Float, height: Float, n: Int) {
        val y = Terrain.groundY(x, z) + height
        for (k in 0 until n) {
            spawn(
                P3.COIN, x, y, z, (rnd() - 0.5f) * 2.4f, 2.8f + rnd() * 1.6f,
                (rnd() - 0.5f) * 2.4f, 1.0f, 0.13f, Color.parseColor("#F0B45E")
            )
        }
    }

    /** Chips of wood and a puff of snow off the branches. */
    fun burstChop(x: Float, z: Float, height: Float) {
        val y = Terrain.groundY(x, z) + height
        for (k in 0 until 14) {
            val a = rnd() * 6.2832f
            val sp = 1.1f + rnd() * 2.2f
            spawn(
                P3.CHUNK, x, y * 0.35f + Terrain.groundY(x, z) * 0.65f, z,
                cos(a) * sp, 1.4f + rnd() * 1.8f, sin(a) * sp,
                0.8f, 0.07f + rnd() * 0.05f, Color.parseColor("#A87A4E")
            )
        }
        for (k in 0 until 20) {
            val a = rnd() * 6.2832f
            spawn(
                P3.SNOW, x + cos(a) * (rnd() * 1.4f), y + rnd() * 1.2f, z + sin(a) * (rnd() * 1.4f),
                cos(a) * 0.5f, -0.9f - rnd() * 0.8f, sin(a) * 0.5f,
                1.6f, 0.07f + rnd() * 0.05f, Color.parseColor("#EAF0FA")
            )
        }
    }

    /** Snow kicked up: digging a hole, stamping a path, falling off a roof. */
    fun burstSnow(x: Float, z: Float, height: Float, n: Int) {
        val y = Terrain.groundY(x, z) + height
        for (k in 0 until n) {
            val a = rnd() * 6.2832f
            val sp = 0.6f + rnd() * 1.6f
            spawn(
                P3.CHUNK, x + (rnd() - 0.5f) * 0.4f, y, z + (rnd() - 0.5f) * 0.4f,
                cos(a) * sp, 1.2f + rnd() * 1.4f, sin(a) * sp,
                0.7f, 0.05f + rnd() * 0.05f, Color.parseColor("#F0F5FC")
            )
        }
    }

    fun burstWater(x: Float, z: Float, height: Float) {
        val y = Terrain.groundY(x, z) + height
        for (k in 0 until 16) {
            val a = rnd() * 6.2832f
            spawn(
                P3.CHUNK, x + (rnd() - 0.5f) * 0.5f, y, z + (rnd() - 0.5f) * 0.5f,
                cos(a) * 0.7f, 1.1f + rnd() * 0.9f, sin(a) * 0.7f,
                0.55f, 0.045f, Color.parseColor("#B4D8EE")
            )
        }
    }

    fun splash(x: Float, z: Float, strength: Float) {
        val y = Terrain.ICE_Y
        for (k in 0 until (12 * strength).toInt().coerceAtLeast(5)) {
            val a = rnd() * 6.2832f
            val sp = (0.8f + rnd() * 1.6f) * strength
            spawn(
                P3.CHUNK, x, y, z, cos(a) * sp, 1.8f + rnd() * 1.4f, sin(a) * sp,
                0.7f, 0.05f + rnd() * 0.04f, Color.parseColor("#D8EEF8")
            )
        }
        spawn(P3.RING, x, y + 0.03f, z, 0f, 0f, 0f, 0.7f, 0.4f, Color.WHITE)
    }

    fun hearts(x: Float, z: Float, height: Float, n: Int) {
        val y = Terrain.groundY(x, z) + height
        for (k in 0 until n) {
            spawn(
                P3.HEART, x + (rnd() - 0.5f) * 0.5f, y, z + (rnd() - 0.5f) * 0.5f,
                (rnd() - 0.5f) * 0.4f, 1.0f, 0f, 1.4f, 0.14f, Color.parseColor("#E0808E")
            )
        }
    }

    /**
     * A boot print, left flat on the snow behind you.
     *
     * [spawn] leaves the cursor on the slot it just used, so the heading can be
     * written straight into that particle's phase afterwards — the print is
     * drawn rotated by it rather than tumbling like everything else.
     */
    fun footprint(x: Float, z: Float, yawDeg: Float) {
        val y = Terrain.groundY(x, z)
        spawn(P3.PRINT, x, y + 0.02f, z, 0f, 0f, 0f, 26f, 0.19f, Color.WHITE)
        phase[cursor] = Math.toRadians(yawDeg.toDouble()).toFloat()
    }

    /** The plume of your own breath in cold air. */
    fun breath(x: Float, y: Float, z: Float, yawDeg: Float, strength: Float) {
        val rad = Math.toRadians(yawDeg.toDouble())
        val fx = sin(rad).toFloat()
        val fz = cos(rad).toFloat()
        spawn(
            P3.BREATH, x + fx * 0.24f, y, z + fz * 0.24f,
            fx * 0.55f + (rnd() - 0.5f) * 0.12f, 0.34f, fz * 0.55f + (rnd() - 0.5f) * 0.12f,
            1.5f, 0.10f * strength, Color.parseColor("#E4EEF8")
        )
    }

    /** Embers lifting off a fire. */
    fun embers(x: Float, y: Float, z: Float, n: Int) {
        for (k in 0 until n) {
            spawn(
                P3.EMBER, x + (rnd() - 0.5f) * 0.5f, y, z + (rnd() - 0.5f) * 0.5f,
                (rnd() - 0.5f) * 0.5f, 1.4f + rnd() * 1.4f, (rnd() - 0.5f) * 0.5f,
                2.2f, 0.04f + rnd() * 0.03f,
                if (rnd() < 0.4f) Color.parseColor("#FFD07A") else Color.parseColor("#FF8A3C")
            )
        }
    }

    fun dust(x: Float, z: Float) {
        val y = Terrain.groundY(x, z) + 0.04f
        for (k in 0 until 4) {
            spawn(
                P3.CHUNK, x + (rnd() - 0.5f) * 0.3f, y, z + (rnd() - 0.5f) * 0.3f,
                (rnd() - 0.5f) * 0.8f, 0.5f + rnd() * 0.4f, (rnd() - 0.5f) * 0.8f,
                0.45f, 0.05f, Color.parseColor("#E4ECF6")
            )
        }
    }

    // ------------------------------------------------------------ ambient

    private fun ringPos(cx: Float, cz: Float, radius: Float, out: FloatArray) {
        val a = rnd() * 6.2832f
        val d = sqrt01() * radius
        out[0] = cx + cos(a) * d
        out[1] = cz + sin(a) * d
    }

    private fun sqrt01(): Float = kotlin.math.sqrt(rnd())

    private val tmp = FloatArray(2)

    /**
     * [indoors] flips the whole ambient set: no weather inside, but a steady
     * drift of dust motes turning in the firelight instead.
     */
    fun updateAmbient(
        dt: Float, cx: Float, cz: Float, night: Float, weather: Int,
        quality: Float, indoors: Boolean
    ) {
        if (indoors) {
            snowAcc += dt * 5f * quality
            while (snowAcc >= 1f) {
                snowAcc -= 1f
                spawn(
                    P3.STEAM, cx + (rnd() - 0.5f) * 4.5f, 0.4f + rnd() * 1.9f, cz + (rnd() - 0.5f) * 3.6f,
                    (rnd() - 0.5f) * 0.08f, 0.05f + rnd() * 0.06f, (rnd() - 0.5f) * 0.08f,
                    7f, 0.022f, Color.parseColor("#FFE0B0")
                )
            }
            return
        }

        // ---- the snow itself ----
        val fall = when (weather) {
            Weather.BLIZZARD -> 190f
            Weather.SNOW -> 74f
            Weather.OVERCAST -> 9f
            else -> 3f       // a few crystals off the trees even on a clear day
        }
        snowAcc += dt * fall * quality
        while (snowAcc >= 1f) {
            snowAcc -= 1f
            ringPos(cx, cz, 18f, tmp)
            val big = rnd() < 0.14f
            spawn(
                P3.SNOW, tmp[0], 9f + rnd() * 5f, tmp[1],
                windX * (0.7f + rnd() * 0.6f), -(0.9f + rnd() * 1.1f), windZ * (0.7f + rnd() * 0.6f),
                14f, (if (big) 0.115f else 0.06f) + rnd() * 0.03f, Color.parseColor("#F2F7FF")
            )
        }

        // ---- ground drift, streaming along the crust when it blows ----
        if (weather == Weather.BLIZZARD || weather == Weather.SNOW) {
            val rate = if (weather == Weather.BLIZZARD) 60f else 12f
            driftAcc += dt * rate * quality
            while (driftAcc >= 1f) {
                driftAcc -= 1f
                ringPos(cx, cz, 14f, tmp)
                val gy = Terrain.groundY(tmp[0], tmp[1])
                spawn(
                    P3.DRIFT, tmp[0], gy + rnd() * 0.5f, tmp[1],
                    windX * (2.4f + rnd() * 2.2f), 0.12f, windZ * (2.4f + rnd() * 2.2f),
                    2.2f, 0.09f + rnd() * 0.06f, Color.parseColor("#E8F0FC")
                )
            }
        }

        // ---- steam off the vent, whenever you are near enough to see it ----
        val dsx = cx - Terrain.SPRING_X
        val dsz = cz - Terrain.SPRING_Z
        if (dsx * dsx + dsz * dsz < 34f * 34f) {
            steamAcc += dt * 16f * quality
            while (steamAcc >= 1f) {
                steamAcc -= 1f
                val a = rnd() * 6.2832f
                val d = sqrt01() * Terrain.SPRING_R * 0.8f
                spawn(
                    P3.STEAM, Terrain.SPRING_X + cos(a) * d, 0.2f + rnd() * 0.3f, Terrain.SPRING_Z + sin(a) * d,
                    windX * 0.28f + (rnd() - 0.5f) * 0.2f, 0.85f + rnd() * 0.6f,
                    windZ * 0.28f + (rnd() - 0.5f) * 0.2f,
                    4.5f, 0.30f + rnd() * 0.26f, Color.parseColor("#DCE8F4")
                )
            }
        }
    }

    /** Embers and heat shimmer over whichever fires are actually burning. */
    fun updateFire(dt: Float, x: Float, y: Float, z: Float, strength: Float, quality: Float) {
        emberAcc += dt * 7f * strength * quality
        while (emberAcc >= 1f) {
            emberAcc -= 1f
            embers(x, y, z, 1)
        }
    }

    fun update(dt: Float) {
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            if (life[i] <= 0f) continue
            when (kind[i]) {
                P3.SNOW -> {
                    // flakes do not fall straight: they wander, because they are
                    // light enough that the air pushes them about on the way down
                    val t = maxLife[i] - life[i]
                    px[i] += (vx[i] + sin(t * 1.3f + phase[i]) * 0.55f) * dt
                    py[i] += vy[i] * dt
                    pz[i] += (vz[i] + cos(t * 1.1f + phase[i]) * 0.55f) * dt
                    if (py[i] <= Terrain.groundY(px[i], pz[i]) + 0.02f) life[i] = 0f
                }
                P3.DRIFT -> {
                    val t = maxLife[i] - life[i]
                    px[i] += vx[i] * dt
                    pz[i] += vz[i] * dt
                    py[i] = Terrain.groundY(px[i], pz[i]) + 0.08f + sin(t * 6f + phase[i]) * 0.14f
                }
                P3.STEAM -> {
                    val t = maxLife[i] - life[i]
                    px[i] += (vx[i] + sin(t * 0.9f + phase[i]) * 0.25f) * dt
                    py[i] += vy[i] * dt
                    pz[i] += (vz[i] + cos(t * 0.7f + phase[i]) * 0.25f) * dt
                    vy[i] *= (1f - dt * 0.28f)
                }
                P3.BREATH -> {
                    px[i] += vx[i] * dt
                    py[i] += vy[i] * dt
                    pz[i] += vz[i] * dt
                    vx[i] *= (1f - dt * 1.5f); vz[i] *= (1f - dt * 1.5f)
                    vy[i] *= (1f - dt * 0.5f)
                }
                P3.EMBER -> {
                    val t = maxLife[i] - life[i]
                    px[i] += (vx[i] + sin(t * 3.1f + phase[i]) * 0.4f) * dt
                    py[i] += vy[i] * dt
                    pz[i] += (vz[i] + cos(t * 2.7f + phase[i]) * 0.4f) * dt
                    vy[i] *= (1f - dt * 0.42f)
                }
                P3.RING, P3.PRINT -> { /* they sit where they were put */ }
                P3.HEART -> {
                    px[i] += (vx[i] + sin(life[i] * 4f) * 0.3f) * dt
                    py[i] += vy[i] * dt
                }
                P3.BIRD -> {
                    val t = maxLife[i] - life[i]
                    px[i] += (vx[i] + sin(t * 2.2f + phase[i]) * 0.7f) * dt
                    pz[i] += (vz[i] + cos(t * 1.8f + phase[i]) * 0.7f) * dt
                    py[i] += sin(t * 5.5f) * 0.6f * dt
                    val floor = Terrain.groundY(px[i], pz[i]) + 0.3f
                    if (py[i] < floor) py[i] = floor
                }
                else -> {
                    vy[i] -= 9.4f * dt
                    px[i] += vx[i] * dt
                    py[i] += vy[i] * dt
                    pz[i] += vz[i] * dt
                    val floor = Terrain.surfaceY(px[i], pz[i]) + 0.02f
                    if (py[i] < floor) {
                        py[i] = floor
                        vy[i] = -vy[i] * 0.26f
                        vx[i] *= 0.5f; vz[i] *= 0.5f
                    }
                }
            }
        }
    }

    fun alphaOf(i: Int): Float {
        val t = life[i] / maxLife[i]
        return when (kind[i]) {
            P3.SNOW -> U.clamp01(t * 6f) * 0.9f
            P3.DRIFT -> U.clamp01(t * 2.4f) * U.clamp01((1f - t) * 5f) * 0.5f
            P3.RING -> t * 0.75f
            P3.PRINT -> U.clamp01(t * 1.6f) * 0.55f
            P3.STEAM -> U.clamp01(t * 2.6f) * U.clamp01((1f - t) * 5f) * 0.44f
            P3.BREATH -> U.clamp01(t * 3.2f) * U.clamp01((1f - t) * 6f) * 0.5f
            P3.EMBER -> {
                val pulse = sin(life[i] * 7.4f + i)
                U.clamp01(t * 2.4f) * (0.45f + 0.55f * pulse * pulse)
            }
            P3.BIRD -> U.clamp01(t * 3f) * U.clamp01((1f - t) * 8f)
            else -> U.clamp01(t * 1.8f)
        }
    }

    fun sizeOf(i: Int): Float = when (kind[i]) {
        P3.RING -> size[i] * (0.4f + (1f - life[i] / maxLife[i]) * 2.6f)
        P3.STEAM -> size[i] * (0.6f + (1f - life[i] / maxLife[i]) * 1.9f)
        P3.BREATH -> size[i] * (0.5f + (1f - life[i] / maxLife[i]) * 2.4f)
        P3.EMBER -> size[i] * (1f - (1f - life[i] / maxLife[i]) * 0.4f)
        else -> size[i]
    }
}
