package com.cozyhollow.riverside

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The things that stand on the ground, in metres. [Terrain] says how high. */
object World {

    // ---- landmarks (mirrored from Terrain, which flattens the ground for them) ----
    const val CABIN_X = Terrain.CABIN_X
    const val CABIN_Z = Terrain.CABIN_Z
    const val MARKET_X = Terrain.MARKET_X
    const val MARKET_Z = Terrain.MARKET_Z
    const val POND_X = Terrain.POND_X
    const val POND_Z = Terrain.POND_Z

    /** Standing on the doormat, where the game offers you an early night. */
    const val CABIN_DOOR_X = CABIN_X + 1.1f
    const val CABIN_DOOR_Z = CABIN_Z + 2.9f

    const val SPAWN_X = CABIN_X + 1.2f
    const val SPAWN_Z = CABIN_Z + 5.4f

    /** The bench by the pond: sit here and the world just carries on around you. */
    const val BENCH_X = POND_X + 5.6f
    const val BENCH_Z = POND_Z + 5.2f

    /** The fire ring outside the cabin. */
    const val FIRE_X = CABIN_X + 4.6f
    const val FIRE_Z = CABIN_Z + 6.2f

    // ---- the field ----
    const val MAX_PLOTS = 16
    const val PLOT_COLS = 4
    const val PLOT_STEP = 1.75f
    private const val FIELD_X0 = Terrain.FIELD_X - PLOT_STEP * 1.5f
    private const val FIELD_Z0 = Terrain.FIELD_Z + PLOT_STEP * 1.5f

    fun plotX(index: Int): Float = FIELD_X0 + (index % PLOT_COLS) * PLOT_STEP
    fun plotZ(index: Int): Float = FIELD_Z0 - (index / PLOT_COLS) * PLOT_STEP

    val FIELD_MIN_X = FIELD_X0 - 1.5f
    val FIELD_MAX_X = FIELD_X0 + PLOT_STEP * 3f + 1.5f
    val FIELD_MIN_Z = FIELD_Z0 - PLOT_STEP * 3f - 1.5f
    val FIELD_MAX_Z = FIELD_Z0 + 1.5f

    fun inField(x: Float, z: Float, margin: Float = 0f): Boolean =
        x > FIELD_MIN_X - margin && x < FIELD_MAX_X + margin &&
            z > FIELD_MIN_Z - margin && z < FIELD_MAX_Z + margin

    // ---- the path ----
    /**
     * A worn track: market, past the cabin door, along the field, out to the
     * bridge, then back round to the pond. Scenery keeps off it and the ground
     * mesh paints itself dirt wherever it runs.
     */
    val path = floatArrayOf(
        MARKET_X, MARKET_Z + 2.5f,
        -6.0f, -8.0f,
        CABIN_X + 2.2f, CABIN_Z - 1.5f,
        CABIN_DOOR_X, CABIN_DOOR_Z,
        -2.0f, 7.2f,
        Terrain.FIELD_X - 3.4f, Terrain.FIELD_Z + 3.0f,
        Terrain.FIELD_X + 3.0f, Terrain.FIELD_Z + 2.4f,
        13.0f, 4.0f,
        Terrain.riverX(Terrain.BRIDGE_Z) - Terrain.BRIDGE_SPAN, Terrain.BRIDGE_Z
    )

    /** A second branch, cabin down to the pond and its little jetty. */
    val pathPond = floatArrayOf(
        CABIN_X - 1.5f, CABIN_Z + 4.0f,
        -13.0f, 8.0f,
        -17.0f, 11.5f,
        POND_X + 5.0f, POND_Z + 4.0f
    )

    private fun distToPolyline(pts: FloatArray, x: Float, z: Float): Float {
        var best = 1e9f
        var i = 0
        while (i + 3 < pts.size) {
            val ax = pts[i]; val az = pts[i + 1]
            val bx = pts[i + 2]; val bz = pts[i + 3]
            val dx = bx - ax; val dz = bz - az
            val len2 = dx * dx + dz * dz
            var t = if (len2 < 1e-5f) 0f else ((x - ax) * dx + (z - az) * dz) / len2
            t = U.clamp01(t)
            val px = ax + dx * t; val pz = az + dz * t
            val d = sqrt((x - px) * (x - px) + (z - pz) * (z - pz))
            if (d < best) best = d
            i += 2
        }
        return best
    }

    /** Metres to the nearest track, either branch. */
    fun distToPath(x: Float, z: Float): Float =
        min(distToPolyline(path, x, z), distToPolyline(pathPond, x, z))

    // ---- buildings you walk around ----
    class Box(val x0: Float, val z0: Float, val x1: Float, val z1: Float) {
        fun contains(x: Float, z: Float) = x > x0 && x < x1 && z > z0 && z < z1
    }

    val solids = arrayOf(
        Box(CABIN_X - 3.0f, CABIN_Z - 2.3f, CABIN_X + 3.0f, CABIN_Z + 2.3f),
        Box(MARKET_X - 2.6f, MARKET_Z - 1.3f, MARKET_X + 2.6f, MARKET_Z + 1.3f)
    )

    // ---- props ----
    object PKind {
        const val LANTERN = 0
        const val BENCH = 1
        const val CAMPFIRE = 2
        const val WELL = 3
        const val JETTY = 4
        const val SIGN = 5
        const val SCARECROW = 6
        const val BEEHIVE = 7
        const val STUMP = 8
        const val BARREL = 9
        const val CRATE = 10
        const val PLANTER = 11
        const val LOGPILE = 12
    }

    class Prop(val kind: Int, val x: Float, val z: Float, val yaw: Float, val scale: Float = 1f)

    val props = arrayOf(
        Prop(PKind.CAMPFIRE, FIRE_X, FIRE_Z, 0f),
        Prop(PKind.BENCH, BENCH_X, BENCH_Z, 210f),
        Prop(PKind.BENCH, FIRE_X + 1.9f, FIRE_Z + 1.2f, -70f),
        Prop(PKind.WELL, CABIN_X - 4.6f, CABIN_Z + 4.6f, 12f),
        Prop(PKind.JETTY, POND_X + 4.4f, POND_Z + 1.2f, 0f),
        Prop(PKind.SIGN, -3.6f, 6.0f, -12f),
        Prop(PKind.SCARECROW, Terrain.FIELD_X + 3.2f, Terrain.FIELD_Z - 3.0f, 25f),
        Prop(PKind.BEEHIVE, -15.5f, -6.0f, 30f),
        Prop(PKind.BEEHIVE, -16.8f, -7.4f, -20f),
        Prop(PKind.LOGPILE, CABIN_X + 4.2f, CABIN_Z - 1.6f, 8f),
        Prop(PKind.BARREL, CABIN_X - 3.6f, CABIN_Z + 1.2f, 0f),
        Prop(PKind.CRATE, MARKET_X + 3.4f, MARKET_Z + 1.0f, 18f),
        Prop(PKind.PLANTER, CABIN_DOOR_X + 1.5f, CABIN_DOOR_Z + 0.2f, 0f),
        Prop(PKind.PLANTER, CABIN_DOOR_X - 3.0f, CABIN_DOOR_Z + 0.2f, 0f),
        Prop(PKind.STUMP, 2.0f, -4.5f, 0f),
        Prop(PKind.STUMP, -18.0f, 2.0f, 0f),
        Prop(PKind.LANTERN, CABIN_DOOR_X + 2.4f, CABIN_DOOR_Z + 1.4f, 0f),
        Prop(PKind.LANTERN, -4.4f, -1.5f, 0f),
        Prop(PKind.LANTERN, MARKET_X + 2.9f, MARKET_Z + 2.2f, 0f),
        Prop(PKind.LANTERN, 2.2f, 8.6f, 0f),
        Prop(PKind.LANTERN, 12.4f, 3.2f, 0f),
        Prop(PKind.LANTERN, POND_X + 5.2f, POND_Z + 2.6f, 0f),
        Prop(PKind.LANTERN, -14.0f, 9.2f, 0f)
    )

    /** Props you bump into rather than walk through. */
    private val propRadius = floatArrayOf(
        0.16f, 0.75f, 0.85f, 1.05f, 0f, 0.22f, 0.3f, 0.55f, 0.5f, 0.45f, 0.45f, 0.5f, 0.9f
    )

    // ---- trees you can fell, and things you can pick ----
    class Tree(val x: Float, val z: Float, val kind: Int, val scale: Float, val seed: Int)
    class ForageSpot(val x: Float, val z: Float, val itemId: String, val seed: Int)

    val trees: Array<Tree>
    val forage: Array<ForageSpot>

    val TREE_COUNT: Int get() = trees.size
    val FORAGE_COUNT: Int get() = forage.size

    /** Somewhere out in the meadow: walkable ground, clear of home and track. */
    private fun meadowSpot(x: Float, z: Float, pathClear: Float): Boolean {
        val h = Terrain.height(x, z)
        if (h < Terrain.WATER_Y + 0.45f || h > Terrain.WALK_CEILING - 0.4f) return false
        if (Terrain.steepness(x, z) > 0.42f) return false
        if (inField(x, z, 2.2f)) return false
        if (distToPath(x, z) < pathClear) return false
        for (s in solids) {
            if (Box(s.x0 - 3.5f, s.z0 - 3.5f, s.x1 + 3.5f, s.z1 + 3.5f).contains(x, z)) return false
        }
        for (p in props) {
            if (abs(p.x - x) < 2.2f && abs(p.z - z) < 2.2f) return false
        }
        return true
    }

    init {
        val treeList = ArrayList<Tree>(30)
        var seed = 17
        while (treeList.size < 26 && seed < 40000) {
            seed++
            val x = -Terrain.HALF + U.hash(seed * 31 + 7) * (Terrain.HALF * 2f)
            val z = -Terrain.HALF + U.hash(seed * 17 + 3) * (Terrain.HALF * 2f)
            if (sqrt(x * x + z * z) > 30f) continue
            if (!meadowSpot(x, z, 2.6f)) continue
            var tooClose = false
            for (t in treeList) {
                if (abs(t.x - x) < 4.2f && abs(t.z - z) < 4.2f) { tooClose = true; break }
            }
            if (tooClose) continue
            val roll = U.hash(seed * 53 + 11)
            val kind = when { roll < 0.42f -> 0; roll < 0.78f -> 1; else -> 2 }
            val scale = 0.88f + U.hash(seed * 91 + 5) * 0.35f
            treeList.add(Tree(x, z, kind, scale, seed * 977 + 41))
        }
        trees = treeList.toTypedArray()

        val kinds = arrayOf("mushroom", "acorn", "flower", "mushroom", "flower", "honey", "flower", "acorn")
        val forageList = ArrayList<ForageSpot>(30)
        seed = 5000
        while (forageList.size < 24 && seed < 60000) {
            seed++
            val x = -Terrain.HALF + U.hash(seed * 73 + 19) * (Terrain.HALF * 2f)
            val z = -Terrain.HALF + U.hash(seed * 29 + 13) * (Terrain.HALF * 2f)
            if (sqrt(x * x + z * z) > 32f) continue
            if (!meadowSpot(x, z, 1.5f)) continue
            var tooClose = false
            for (f in forageList) {
                if (abs(f.x - x) < 3.4f && abs(f.z - z) < 3.4f) { tooClose = true; break }
            }
            if (tooClose) continue
            for (t in trees) {
                if (abs(t.x - x) < 1.6f && abs(t.z - z) < 1.6f) { tooClose = true; break }
            }
            if (tooClose) continue
            val id = kinds[(U.hash(seed * 137 + 23) * kinds.size).toInt().coerceIn(0, kinds.size - 1)]
            forageList.add(ForageSpot(x, z, id, seed * 613 + 17))
        }
        forage = forageList.toTypedArray()
    }

    // ---- collision ----
    fun blocked(st: GameState, x: Float, z: Float): Boolean {
        if (Terrain.impassable(x, z)) return true
        for (s in solids) if (s.contains(x, z)) return true
        for (i in props.indices) {
            val p = props[i]
            val r = propRadius[p.kind]
            if (r <= 0f) continue
            val dx = x - p.x
            val dz = z - p.z
            if (dx * dx + dz * dz < r * r) return true
        }
        for (i in trees.indices) {
            if (!treeStanding(st, i)) continue
            val t = trees[i]
            val dx = x - t.x
            val dz = z - t.z
            val r = 0.42f * t.scale
            if (dx * dx + dz * dz < r * r) return true
        }
        return false
    }

    // ---- zones, for the little name that fades in at the top of the screen ----
    const val Z_HOME = 0
    const val Z_FIELD = 1
    const val Z_MARKET = 2
    const val Z_RIVER = 3
    const val Z_POND = 4
    const val Z_WOODS = 5
    const val Z_MEADOW = 6

    fun zoneAt(x: Float, z: Float): Int {
        if (abs(x - CABIN_X) < 7f && abs(z - CABIN_Z) < 7f) return Z_HOME
        if (inField(x, z, 3f)) return Z_FIELD
        if (abs(x - MARKET_X) < 6f && abs(z - MARKET_Z) < 6f) return Z_MARKET
        val dp = sqrt((x - POND_X) * (x - POND_X) + (z - POND_Z) * (z - POND_Z))
        if (dp < Terrain.POND_R + 4f) return Z_POND
        if (abs(x - Terrain.riverX(z)) < 9f) return Z_RIVER
        if (sqrt(x * x + z * z) > 26f) return Z_WOODS
        return Z_MEADOW
    }

    fun zoneName(zone: Int): String = when (zone) {
        Z_HOME -> "Home"
        Z_FIELD -> "The Field"
        Z_MARKET -> "Market Stall"
        Z_RIVER -> "Riverbank"
        Z_POND -> "Still Pond"
        Z_WOODS -> "Whispering Woods"
        else -> "Sunny Meadow"
    }

    fun treeStanding(state: GameState, i: Int): Boolean =
        i < state.treeRegrow.size && (state.treeRegrow[i] == 0 || state.day >= state.treeRegrow[i])

    fun forageAvailable(state: GameState, i: Int): Boolean =
        i < state.foragePicked.size && state.foragePicked[i] != state.day

    /** Nearest thing worth pointing the camera at, used by the intro fly-in. */
    fun clampWalk(x: Float, z: Float, out: FloatArray) {
        out[0] = max(-Terrain.HALF + 3f, min(Terrain.HALF - 3f, x))
        out[1] = max(-Terrain.HALF + 3f, min(Terrain.HALF - 3f, z))
    }
}
