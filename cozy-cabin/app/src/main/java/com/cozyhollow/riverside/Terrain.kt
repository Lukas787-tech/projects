package com.cozyhollow.riverside

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shape of the hollow, in metres.
 *
 * Everything else in the game asks this object how high the ground is: the
 * terrain mesh, where a tree's roots sit, how far the player's feet drop when
 * they walk downhill, where the water laps. One height function, one truth —
 * two copies of it is how you end up with trees hovering over the grass.
 *
 * The valley is a bowl: a soft floor with rolling swells, a river that meanders
 * north to south through it, a still pond in the west, and wooded hills rising
 * all the way round the rim so the world never shows you its edge.
 */
object Terrain {

    /** The map runs -HALF..HALF in both x and z. */
    const val HALF = 46f

    /** Height of the water table. Both the river and the pond sit at it. */
    const val WATER_Y = -0.30f

    /** Deepest the riverbed and the pond floor are cut. */
    const val BED_Y = -1.35f

    // ---- the homestead, flattened into the valley floor ----
    const val CABIN_X = -8.5f
    const val CABIN_Z = 3.0f
    const val FIELD_X = 6.5f
    const val FIELD_Z = 8.0f
    const val MARKET_X = -3.0f
    const val MARKET_Z = -13.5f
    const val POND_X = -21.5f
    const val POND_Z = 14.0f
    const val POND_R = 7.2f

    /** Where the footbridge crosses, and how high its deck rides. */
    const val BRIDGE_Z = 1.0f
    const val BRIDGE_HALF_Z = 1.9f
    const val BRIDGE_SPAN = 7.0f
    const val BRIDGE_Y = 0.55f

    /** Centre line of the river at a given depth into the map. */
    fun riverX(z: Float): Float =
        20.5f + sin(z * 0.075f) * 5.2f + sin(z * 0.031f + 1.7f) * 2.4f

    /** Smooth value noise, so the swells never repeat in a way you can read. */
    private fun noise(x: Float, z: Float, seed: Int): Float {
        val xi = floor(x); val zi = floor(z)
        val fx = U.smooth(x - xi); val fz = U.smooth(z - zi)
        val ix = xi.toInt(); val iz = zi.toInt()
        val a = U.hash2(ix * 31 + seed, iz * 17 + seed)
        val b = U.hash2((ix + 1) * 31 + seed, iz * 17 + seed)
        val c = U.hash2(ix * 31 + seed, (iz + 1) * 17 + seed)
        val d = U.hash2((ix + 1) * 31 + seed, (iz + 1) * 17 + seed)
        return U.lerp(U.lerp(a, b, fx), U.lerp(c, d, fx), fz)
    }

    /** Flattens a circle of ground toward [level] so buildings sit level. */
    private fun pad(h: Float, x: Float, z: Float, cx: Float, cz: Float, r0: Float, r1: Float, level: Float): Float {
        val d = sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz))
        if (d > r1) return h
        val t = 1f - U.smoothRange(d, r0, r1)
        return U.lerp(h, level, t)
    }

    /** Ground height at a point, ignoring anything built on top of it. */
    fun height(x: Float, z: Float): Float {
        // rolling floor
        var h = sin(x * 0.19f) * cos(z * 0.17f) * 0.42f +
            sin((x * 0.7f + z * 0.9f) * 0.13f) * 0.30f +
            (noise(x * 0.09f, z * 0.09f, 91) - 0.5f) * 1.4f

        // the rim: wooded hills all the way round, hiding the edge of the map
        val r = sqrt(x * x + z * z)
        val rim = U.smoothRange(r, 25f, 45f)
        h += rim * rim * 17f + rim * (noise(x * 0.16f, z * 0.16f, 41) - 0.5f) * 4f

        // level ground where you live and work
        h = pad(h, x, z, CABIN_X, CABIN_Z, 5.5f, 11f, 0.30f)
        h = pad(h, x, z, FIELD_X, FIELD_Z, 5.0f, 10f, 0.12f)
        h = pad(h, x, z, MARKET_X, MARKET_Z, 3.6f, 8f, 0.55f)

        // the river, cut through whatever the ground was doing
        val d = abs(x - riverX(z))
        val bed = BED_Y + U.smoothRange(d, 3.0f, 8.0f) * 5.0f
        if (bed < h) h = bed

        // the pond
        val dp = sqrt((x - POND_X) * (x - POND_X) + (z - POND_Z) * (z - POND_Z))
        val pondBed = (BED_Y + 0.1f) + U.smoothRange(dp, POND_R - 4f, POND_R) * 4.2f
        if (pondBed < h) h = pondBed

        return h
    }

    /** True inside the planks of the footbridge. */
    fun onBridge(x: Float, z: Float): Boolean {
        if (abs(z - BRIDGE_Z) > BRIDGE_HALF_Z) return false
        return abs(x - riverX(BRIDGE_Z)) < BRIDGE_SPAN
    }

    /** Deck height, arched gently so it reads as a bridge and not a plank. */
    fun bridgeY(x: Float): Float {
        val t = U.clamp01(abs(x - riverX(BRIDGE_Z)) / BRIDGE_SPAN)
        return BRIDGE_Y + (1f - t * t) * 0.42f
    }

    /** What a pair of boots stands on here: the deck if there is one, else soil. */
    fun groundY(x: Float, z: Float): Float =
        if (onBridge(x, z)) bridgeY(x) else height(x, z)

    fun isWater(x: Float, z: Float): Boolean = height(x, z) < WATER_Y - 0.02f

    /** How deep the water is, or 0 on dry land. */
    fun depth(x: Float, z: Float): Float = max(0f, WATER_Y - height(x, z))

    /** Surface of whatever you would splash into, for ripples and reflections. */
    fun surfaceY(x: Float, z: Float): Float = max(WATER_Y, height(x, z))

    /** 0 flat, 1 cliff. Steep ground gets rock instead of grass, and no trees. */
    fun steepness(x: Float, z: Float): Float {
        val e = 0.7f
        val hx = height(x + e, z) - height(x - e, z)
        val hz = height(x, z + e) - height(x, z - e)
        return U.clamp01(sqrt(hx * hx + hz * hz) / (2f * e) * 0.9f)
    }

    /** Ground normal, written into [out]. */
    fun normal(x: Float, z: Float, out: FloatArray) {
        val e = 0.55f
        val hx = height(x + e, z) - height(x - e, z)
        val hz = height(x, z + e) - height(x, z - e)
        var nx = -hx
        var ny = 2f * e
        var nz = -hz
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
        out[0] = nx / len; out[1] = ny / len; out[2] = nz / len
    }

    /**
     * Where the ground turns to sand: a hand's width above the waterline,
     * anywhere near enough to the water to be damp.
     */
    fun sandiness(x: Float, z: Float): Float {
        val h = height(x, z)
        return U.clamp01(1f - U.norm(h - WATER_Y, 0.05f, 0.85f))
    }

    /** Highest ground a walk can climb before the hills take over. */
    const val WALK_CEILING = 3.4f

    /** True if a pair of boots cannot go here: too deep, too steep, too high. */
    fun impassable(x: Float, z: Float): Boolean {
        if (x < -HALF + 2f || x > HALF - 2f || z < -HALF + 2f || z > HALF - 2f) return true
        if (onBridge(x, z)) return false
        val h = height(x, z)
        if (h < WATER_Y + 0.08f) return true
        if (h > WALK_CEILING) return true
        return false
    }

    /** Distance to the nearest fishable water, for the "cast a line" prompt. */
    fun nearWater(x: Float, z: Float, reach: Float): Boolean {
        var a = 0
        while (a < 12) {
            val ang = a * 0.5236f
            val px = x + cos(ang) * reach
            val pz = z + sin(ang) * reach
            if (isWater(px, pz)) return true
            a++
        }
        return isWater(x, z)
    }

    /** A point on the water within reach, for the bobber to land on. */
    fun castSpot(x: Float, z: Float, reach: Float, out: FloatArray): Boolean {
        var best = -1f
        var a = 0
        while (a < 24) {
            val ang = a * 0.2618f
            var d = reach * 0.45f
            while (d <= reach) {
                val px = x + cos(ang) * d
                val pz = z + sin(ang) * d
                val dep = depth(px, pz)
                if (dep > 0.25f && dep > best) {
                    best = dep; out[0] = px; out[1] = pz
                }
                d += reach * 0.28f
            }
            a++
        }
        return best > 0f
    }

    /** Keeps a walk inside the bowl without a visible wall. */
    fun clampToValley(px: Float, pz: Float, out: FloatArray) {
        out[0] = min(HALF - 3f, max(-HALF + 3f, px))
        out[1] = min(HALF - 3f, max(-HALF + 3f, pz))
    }
}
