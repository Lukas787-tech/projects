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
 * terrain mesh, where a fence post sits, how far your boots drop walking down
 * a drift, where the ice begins. One height function, one truth — two copies
 * of it is how you end up with trees hovering over the snow.
 *
 * It is a shallow bowl: an open yard with the cabin on it, a creek that has
 * frozen solid down the eastern side, a wide frozen pond in the west, a steam
 * vent up in the eastern rocks, and pine hills round the whole rim so the
 * world never shows you its edge.
 *
 * In winter the water table is a *floor*, not a hole. Everything cut below
 * [ICE_Y] is covered by a sheet of ice you can walk out onto, which is where
 * the ice fishing happens.
 */
object Terrain {

    /** The map runs -HALF..HALF in both x and z. */
    const val HALF = 46f

    /** Height of the ice sheet. The pond and the creek both freeze at it. */
    const val ICE_Y = -0.30f

    /** Deepest the creek bed and the pond floor are cut, under the ice. */
    const val BED_Y = -1.45f

    // ---- the homestead, flattened into the valley floor ----
    const val CABIN_X = -6.5f
    const val CABIN_Z = 1.5f

    /** The glasshouse, where anything green in this season has to live. */
    const val GLASS_X = 7.5f
    const val GLASS_Z = 7.0f

    /** Pip's stall, lit by a brazier that never quite goes out. */
    const val MARKET_X = -3.0f
    const val MARKET_Z = -14.0f

    const val POND_X = -21.0f
    const val POND_Z = 15.0f
    const val POND_R = 8.0f

    /** The steam vent in the eastern rocks: the warmest place outdoors. */
    const val SPRING_X = 15.5f
    const val SPRING_Z = -8.5f
    const val SPRING_R = 3.1f

    /** Where the plank bridge crosses the creek, and how high its deck rides. */
    const val BRIDGE_Z = 0.0f
    const val BRIDGE_HALF_Z = 1.9f
    const val BRIDGE_SPAN = 6.4f
    const val BRIDGE_Y = 0.62f

    /** Centre line of the creek at a given depth into the map. */
    fun creekX(z: Float): Float =
        21.0f + sin(z * 0.075f) * 5.0f + sin(z * 0.031f + 1.7f) * 2.2f

    /** Kept for the older call sites; the creek is the river, only frozen. */
    fun riverX(z: Float): Float = creekX(z)

    /** Smooth value noise, so the drifts never repeat in a way you can read. */
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

    /**
     * Ground height at a point, ignoring the ice and anything built on top.
     *
     * On top of the usual rolling floor there is a second, sharper layer of
     * wind-blown drift: long combed ridges that pile up against anything
     * standing still. It is what stops a snowfield reading as a bedsheet.
     */
    fun height(x: Float, z: Float): Float {
        var h = sin(x * 0.19f) * cos(z * 0.17f) * 0.34f +
            sin((x * 0.7f + z * 0.9f) * 0.13f) * 0.24f +
            (noise(x * 0.09f, z * 0.09f, 91) - 0.5f) * 1.2f

        // wind-combed drift, running roughly north-east across the whole bowl
        val drift = noise((x * 0.62f + z * 0.28f) * 0.5f, (z * 0.6f - x * 0.2f) * 0.16f, 137)
        h += (drift - 0.45f) * 0.42f

        // the rim: pine hills all the way round, hiding the edge of the map
        val r = sqrt(x * x + z * z)
        val rim = U.smoothRange(r, 25f, 45f)
        h += rim * rim * 17f + rim * (noise(x * 0.16f, z * 0.16f, 41) - 0.5f) * 4f

        // level ground where you live and work
        h = pad(h, x, z, CABIN_X, CABIN_Z, 7.0f, 13f, 0.34f)
        h = pad(h, x, z, GLASS_X, GLASS_Z, 4.6f, 9f, 0.16f)
        h = pad(h, x, z, MARKET_X, MARKET_Z, 3.6f, 8f, 0.52f)

        // the creek, cut through whatever the ground was doing
        val d = abs(x - creekX(z))
        val bed = BED_Y + U.smoothRange(d, 2.6f, 7.6f) * 5.0f
        if (bed < h) h = bed

        // the pond
        val dp = sqrt((x - POND_X) * (x - POND_X) + (z - POND_Z) * (z - POND_Z))
        val pondBed = (BED_Y + 0.1f) + U.smoothRange(dp, POND_R - 4.5f, POND_R) * 4.2f
        if (pondBed < h) h = pondBed

        // the steam vent sits in a shallow rocky dish that never fills with snow
        val ds = sqrt((x - SPRING_X) * (x - SPRING_X) + (z - SPRING_Z) * (z - SPRING_Z))
        if (ds < SPRING_R + 2.4f) {
            val bowl = 0.16f + U.smoothRange(ds, SPRING_R - 1.2f, SPRING_R + 2.4f) * 1.1f
            h = U.lerp(bowl, h, U.smoothRange(ds, SPRING_R, SPRING_R + 2.4f))
        }

        return h
    }

    /** True inside the planks of the creek bridge. */
    fun onBridge(x: Float, z: Float): Boolean {
        if (abs(z - BRIDGE_Z) > BRIDGE_HALF_Z) return false
        return abs(x - creekX(BRIDGE_Z)) < BRIDGE_SPAN
    }

    /** Deck height, arched gently so it reads as a bridge and not a plank. */
    fun bridgeY(x: Float): Float {
        val t = U.clamp01(abs(x - creekX(BRIDGE_Z)) / BRIDGE_SPAN)
        return BRIDGE_Y + (1f - t * t) * 0.42f
    }

    /** True where the ground is cut below the water table, so ice has formed. */
    fun isIce(x: Float, z: Float): Boolean = height(x, z) < ICE_Y - 0.02f

    /** Kept under the older name: frozen water is still water. */
    fun isWater(x: Float, z: Float): Boolean = isIce(x, z)

    /** How deep the water under the ice is, or 0 on dry land. */
    fun depth(x: Float, z: Float): Float = max(0f, ICE_Y - height(x, z))

    /**
     * What a pair of boots stands on here: the bridge deck if there is one,
     * the ice if the ground is cut below the water table, else snow.
     */
    fun groundY(x: Float, z: Float): Float = when {
        onBridge(x, z) -> bridgeY(x)
        else -> {
            val h = height(x, z)
            if (h < ICE_Y) ICE_Y else h
        }
    }

    /** The surface something splashing or settling would land on. */
    fun surfaceY(x: Float, z: Float): Float = max(ICE_Y, height(x, z))

    /** 0 flat, 1 cliff. Steep ground sheds its snow and shows bare rock. */
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
        val nx = -hx
        val ny = 2f * e
        val nz = -hz
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
        out[0] = nx / len; out[1] = ny / len; out[2] = nz / len
    }

    /**
     * Where the snow thins to frozen shingle: right at the edge of the ice,
     * where the wind scours the bank clean.
     */
    fun shoreline(x: Float, z: Float): Float {
        val h = height(x, z)
        return U.clamp01(1f - U.norm(h - ICE_Y, 0.02f, 0.55f))
    }

    /** How close to the steam vent, 0..1. Nothing there holds snow. */
    fun springWarmth(x: Float, z: Float): Float {
        val d = sqrt((x - SPRING_X) * (x - SPRING_X) + (z - SPRING_Z) * (z - SPRING_Z))
        return 1f - U.smoothRange(d, SPRING_R * 0.5f, SPRING_R + 2.6f)
    }

    /** Highest ground a walk can climb before the pine hills take over. */
    const val WALK_CEILING = 3.4f

    /**
     * True if a pair of boots cannot go here: too steep, too high, off the map.
     *
     * Deep water no longer stops you — it is frozen a foot thick. Walking out
     * onto the pond is half the point of the season.
     */
    fun impassable(x: Float, z: Float): Boolean {
        if (x < -HALF + 2f || x > HALF - 2f || z < -HALF + 2f || z > HALF - 2f) return true
        if (onBridge(x, z)) return false
        val h = height(x, z)
        if (h < ICE_Y) return false          // the ice sheet: solid all winter
        if (h > WALK_CEILING) return true
        // the steam vent itself is too hot to stand in
        val ds = sqrt((x - SPRING_X) * (x - SPRING_X) + (z - SPRING_Z) * (z - SPRING_Z))
        if (ds < SPRING_R * 0.62f) return true
        return false
    }

    /** True where you are standing on the frozen surface rather than on snow. */
    fun onIce(x: Float, z: Float): Boolean = !onBridge(x, z) && height(x, z) < ICE_Y

    /** Is there ice thick and deep enough here to cut a fishing hole in? */
    fun fishable(x: Float, z: Float): Boolean = depth(x, z) > 0.45f

    /** Distance to somewhere worth cutting a hole, for the "fish" prompt. */
    fun nearFishableIce(x: Float, z: Float, reach: Float): Boolean {
        if (fishable(x, z)) return true
        var a = 0
        while (a < 12) {
            val ang = a * 0.5236f
            if (fishable(x + cos(ang) * reach, z + sin(ang) * reach)) return true
            a++
        }
        return false
    }

    /** Kept under the older name so the action code reads the same. */
    fun nearWater(x: Float, z: Float, reach: Float): Boolean = nearFishableIce(x, z, reach)

    /** A patch of ice within reach worth cutting, for the hole to land on. */
    fun castSpot(x: Float, z: Float, reach: Float, out: FloatArray): Boolean {
        if (fishable(x, z)) { out[0] = x; out[1] = z; return true }
        var best = -1f
        var a = 0
        while (a < 24) {
            val ang = a * 0.2618f
            var d = reach * 0.3f
            while (d <= reach) {
                val px = x + cos(ang) * d
                val pz = z + sin(ang) * d
                val dep = depth(px, pz)
                if (dep > 0.45f && dep > best) {
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
