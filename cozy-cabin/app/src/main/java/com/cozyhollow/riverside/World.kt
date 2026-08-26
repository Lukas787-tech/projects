package com.cozyhollow.riverside

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The things that stand on the snow, in metres. [Terrain] says how high. */
object World {

    // ---- landmarks (mirrored from Terrain, which flattens the ground for them) ----
    const val CABIN_X = Terrain.CABIN_X
    const val CABIN_Z = Terrain.CABIN_Z
    const val GLASS_X = Terrain.GLASS_X
    const val GLASS_Z = Terrain.GLASS_Z
    const val MARKET_X = Terrain.MARKET_X
    const val MARKET_Z = Terrain.MARKET_Z
    const val POND_X = Terrain.POND_X
    const val POND_Z = Terrain.POND_Z
    const val SPRING_X = Terrain.SPRING_X
    const val SPRING_Z = Terrain.SPRING_Z

    /** The doormat. Stand here and the cabin lets you in. */
    const val CABIN_DOOR_X = CABIN_X + 1.5f
    const val CABIN_DOOR_Z = CABIN_Z + 3.5f

    // far enough out that the first thing the game offers you is the yard,
    // not the front door you are already standing on
    const val SPAWN_X = CABIN_X + 1.5f
    const val SPAWN_Z = CABIN_Z + 7.0f

    /** The fire ring in the middle of the yard, with logs pulled up round it. */
    const val FIRE_X = CABIN_X + 6.4f
    const val FIRE_Z = CABIN_Z + 6.0f

    /** The chopping block by the woodshed. */
    const val SHED_X = CABIN_X + 5.4f
    const val SHED_Z = CABIN_Z - 4.6f
    const val CHOP_X = SHED_X - 2.6f
    const val CHOP_Z = SHED_Z + 1.6f

    /** The bare tree with the tyre swing, west of the yard. */
    const val SWING_X = CABIN_X - 8.2f
    const val SWING_Z = CABIN_Z - 0.6f

    /** The old truck, parked where it died and never moved again. */
    const val TRUCK_X = CABIN_X - 5.6f
    const val TRUCK_Z = CABIN_Z - 6.4f

    /** The feeders. Fill them and something comes to eat. */
    const val BIRD_X = CABIN_X - 3.4f
    const val BIRD_Z = CABIN_Z + 6.8f
    const val DEER_X = -14.0f
    const val DEER_Z = -6.0f

    /** The hut out on the pond ice, and the bench on the bank by it. */
    const val HUT_X = POND_X + 1.5f
    const val HUT_Z = POND_Z - 1.0f
    const val POND_BENCH_X = POND_X + 8.4f
    const val POND_BENCH_Z = POND_Z + 5.0f

    /** The sledding run: top of the western slope, down toward the pond. */
    const val SLED_X = -13.5f
    const val SLED_Z = 20.5f

    // ---- the glasshouse beds ----
    const val MAX_PLOTS = 16
    const val PLOT_COLS = 4
    const val PLOT_STEP = 1.45f
    private const val BED_X0 = GLASS_X - PLOT_STEP * 1.5f
    private const val BED_Z0 = GLASS_Z + PLOT_STEP * 1.5f

    fun plotX(index: Int): Float = BED_X0 + (index % PLOT_COLS) * PLOT_STEP
    fun plotZ(index: Int): Float = BED_Z0 - (index / PLOT_COLS) * PLOT_STEP

    /** Outer footprint of the glasshouse. */
    const val GLASS_HALF = 4.1f
    val GLASS_MIN_X = GLASS_X - GLASS_HALF
    val GLASS_MAX_X = GLASS_X + GLASS_HALF
    val GLASS_MIN_Z = GLASS_Z - GLASS_HALF
    val GLASS_MAX_Z = GLASS_Z + GLASS_HALF

    fun inGlasshouse(x: Float, z: Float, margin: Float = 0f): Boolean =
        x > GLASS_MIN_X - margin && x < GLASS_MAX_X + margin &&
            z > GLASS_MIN_Z - margin && z < GLASS_MAX_Z + margin

    /** Kept under the old name: the beds are the field, they just have a roof now. */
    fun inField(x: Float, z: Float, margin: Float = 0f): Boolean = inGlasshouse(x, z, margin)

    // ---- the paths ----
    /**
     * Boot-worn tracks through the snow: stall, past the truck, round the
     * cabin door, out to the woodshed and the glasshouse, then on to the
     * bridge. Scenery keeps off them and the ground mesh packs itself down
     * wherever they run.
     */
    val path = floatArrayOf(
        MARKET_X, MARKET_Z + 2.5f,
        -6.0f, -9.0f,
        TRUCK_X + 1.2f, TRUCK_Z + 1.6f,
        CABIN_X - 1.0f, CABIN_Z + 3.8f,
        CABIN_DOOR_X, CABIN_DOOR_Z,
        CABIN_X + 3.6f, CABIN_Z + 4.6f,
        FIRE_X - 0.6f, FIRE_Z - 0.4f,
        GLASS_X - 1.0f, GLASS_Z + 5.4f,
        GLASS_X + 5.2f, GLASS_Z + 1.2f,
        14.0f, 3.0f,
        Terrain.creekX(Terrain.BRIDGE_Z) - Terrain.BRIDGE_SPAN, Terrain.BRIDGE_Z
    )

    /** A second branch: cabin, past the swing tree, down to the pond and the hut. */
    val pathPond = floatArrayOf(
        CABIN_X - 2.0f, CABIN_Z + 2.0f,
        SWING_X + 1.4f, SWING_Z + 2.2f,
        -13.0f, 8.0f,
        -16.5f, 12.0f,
        POND_BENCH_X - 1.0f, POND_BENCH_Z - 1.0f
    )

    /** A third: the woodshed, then away north-east to the steam vent. */
    val pathShed = floatArrayOf(
        CABIN_X + 2.4f, CABIN_Z - 1.0f,
        SHED_X - 0.4f, SHED_Z + 2.4f,
        2.0f, -7.5f,
        9.0f, -8.5f,
        SPRING_X - 3.4f, SPRING_Z + 0.6f
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

    /** Metres to the nearest trodden track, any branch. */
    fun distToPath(x: Float, z: Float): Float = min(
        distToPolyline(path, x, z),
        min(distToPolyline(pathPond, x, z), distToPolyline(pathShed, x, z))
    )

    // ---- things you walk around ----
    class Box(val x0: Float, val z0: Float, val x1: Float, val z1: Float) {
        fun contains(x: Float, z: Float) = x > x0 && x < x1 && z > z0 && z < z1
    }

    val solids = arrayOf(
        // the cabin itself
        Box(CABIN_X - 3.2f, CABIN_Z - 2.4f, CABIN_X + 3.2f, CABIN_Z + 2.4f),
        // Pip's stall
        Box(MARKET_X - 2.6f, MARKET_Z - 1.3f, MARKET_X + 2.6f, MARKET_Z + 1.3f),
        // the woodshed
        Box(SHED_X - 2.1f, SHED_Z - 1.6f, SHED_X + 2.1f, SHED_Z + 1.6f),
        // the truck
        Box(TRUCK_X - 1.5f, TRUCK_Z - 2.7f, TRUCK_X + 1.5f, TRUCK_Z + 2.7f),
        // the hut out on the ice
        Box(HUT_X - 1.5f, HUT_Z - 1.4f, HUT_X + 1.5f, HUT_Z + 1.4f),
        // the glasshouse, four walls with a doorway left open in the south one
        Box(GLASS_MIN_X, GLASS_MIN_Z, GLASS_MAX_X, GLASS_MIN_Z + 0.42f),
        Box(GLASS_MAX_X - 0.42f, GLASS_MIN_Z, GLASS_MAX_X, GLASS_MAX_Z),
        Box(GLASS_MIN_X, GLASS_MIN_Z, GLASS_MIN_X + 0.42f, GLASS_MAX_Z),
        Box(GLASS_MIN_X, GLASS_MAX_Z - 0.42f, GLASS_X - 1.0f, GLASS_MAX_Z),
        Box(GLASS_X + 1.0f, GLASS_MAX_Z - 0.42f, GLASS_MAX_X, GLASS_MAX_Z)
    )

    /** The gap in the south wall of the glasshouse. */
    const val GLASS_DOOR_X = GLASS_X
    val GLASS_DOOR_Z = GLASS_MAX_Z + 0.7f

    // ---- props ----
    object PKind {
        const val LANTERN = 0
        const val BENCH = 1
        const val FIREPIT = 2
        const val WELL = 3
        const val ICEHOLE = 4
        const val SIGN = 5
        const val SNOWMAN = 6
        const val BIRD_FEEDER = 7
        const val STUMP = 8
        const val BARREL = 9
        const val CRATE = 10
        const val LOGPILE = 11
        const val CHOP_BLOCK = 12
        const val TRUCK = 13
        const val SWING_TREE = 14
        const val WOODSHED = 15
        const val ICE_HUT = 16
        const val DEER_FEEDER = 17
        const val SLED = 18
        const val MAILBOX = 19
        const val BRAZIER = 20
        const val LOG_SEAT = 21
        const val SPRING = 22
        const val LAMP_HANG = 23
        const val COUNT = 24
    }

    class Prop(val kind: Int, val x: Float, val z: Float, val yaw: Float, val scale: Float = 1f)

    val props = arrayOf(
        Prop(PKind.FIREPIT, FIRE_X, FIRE_Z, 0f),
        Prop(PKind.LOG_SEAT, FIRE_X - 2.1f, FIRE_Z + 0.6f, 74f),
        Prop(PKind.LOG_SEAT, FIRE_X + 1.4f, FIRE_Z + 1.8f, -34f),
        Prop(PKind.BENCH, POND_BENCH_X, POND_BENCH_Z, 214f),
        Prop(PKind.BENCH, CABIN_DOOR_X + 2.6f, CABIN_DOOR_Z - 0.9f, 178f),
        Prop(PKind.TRUCK, TRUCK_X, TRUCK_Z, 8f),
        Prop(PKind.SWING_TREE, SWING_X, SWING_Z, 0f),
        Prop(PKind.WOODSHED, SHED_X, SHED_Z, 0f),
        Prop(PKind.CHOP_BLOCK, CHOP_X, CHOP_Z, 0f),
        Prop(PKind.LOGPILE, SHED_X - 3.6f, SHED_Z - 0.6f, 90f),
        Prop(PKind.LOGPILE, CABIN_X + 3.1f, CABIN_Z - 1.4f, 0f),
        Prop(PKind.WELL, CABIN_X - 4.8f, CABIN_Z + 4.6f, 12f),
        Prop(PKind.MAILBOX, CABIN_DOOR_X + 4.4f, CABIN_DOOR_Z + 2.0f, -14f),
        Prop(PKind.BIRD_FEEDER, BIRD_X, BIRD_Z, 0f),
        Prop(PKind.DEER_FEEDER, DEER_X, DEER_Z, 24f),
        Prop(PKind.SNOWMAN, CABIN_X - 2.2f, CABIN_Z + 7.4f, 0f),
        Prop(PKind.ICE_HUT, HUT_X, HUT_Z, 16f),
        Prop(PKind.SLED, SLED_X, SLED_Z, 30f),
        Prop(PKind.SPRING, SPRING_X, SPRING_Z, 0f),
        Prop(PKind.BRAZIER, MARKET_X + 3.1f, MARKET_Z + 1.6f, 0f),
        Prop(PKind.SIGN, -3.8f, 5.2f, -12f),
        Prop(PKind.BARREL, CABIN_X - 3.8f, CABIN_Z + 0.9f, 0f),
        Prop(PKind.CRATE, MARKET_X + 3.4f, MARKET_Z - 1.0f, 18f),
        Prop(PKind.CRATE, SHED_X + 2.9f, SHED_Z + 0.4f, -22f),
        Prop(PKind.STUMP, 1.4f, -4.2f, 0f),
        Prop(PKind.STUMP, -17.6f, 2.4f, 0f),
        Prop(PKind.STUMP, 11.0f, 12.6f, 0f),
        Prop(PKind.LANTERN, CABIN_DOOR_X + 2.2f, CABIN_DOOR_Z + 1.6f, 0f),
        Prop(PKind.LANTERN, CABIN_X - 4.6f, CABIN_Z - 2.4f, 0f),
        Prop(PKind.LANTERN, MARKET_X - 2.9f, MARKET_Z + 2.2f, 0f),
        Prop(PKind.LANTERN, 2.4f, 8.0f, 0f),
        Prop(PKind.LANTERN, 13.6f, 2.4f, 0f),
        Prop(PKind.LANTERN, POND_BENCH_X + 1.4f, POND_BENCH_Z - 2.2f, 0f),
        Prop(PKind.LANTERN, -14.2f, 9.8f, 0f),
        Prop(PKind.LANTERN, GLASS_X - 0.2f, GLASS_MAX_Z + 2.4f, 0f),
        Prop(PKind.LANTERN, SHED_X + 2.8f, SHED_Z + 2.6f, 0f)
    )

    /** Props you bump into rather than walk through, by kind. */
    private val propRadius = FloatArray(PKind.COUNT).also { r ->
        r[PKind.LANTERN] = 0.16f
        r[PKind.BENCH] = 0.78f
        r[PKind.FIREPIT] = 0.95f
        r[PKind.WELL] = 1.05f
        r[PKind.SIGN] = 0.22f
        r[PKind.SNOWMAN] = 0.62f
        r[PKind.BIRD_FEEDER] = 0.28f
        r[PKind.STUMP] = 0.5f
        r[PKind.BARREL] = 0.45f
        r[PKind.CRATE] = 0.5f
        r[PKind.LOGPILE] = 0.95f
        r[PKind.CHOP_BLOCK] = 0.55f
        r[PKind.DEER_FEEDER] = 0.9f
        r[PKind.SLED] = 0.5f
        r[PKind.MAILBOX] = 0.2f
        r[PKind.BRAZIER] = 0.45f
        r[PKind.LOG_SEAT] = 0.62f
        r[PKind.SWING_TREE] = 0.5f
        r[PKind.LAMP_HANG] = 0f
        // truck, woodshed, ice hut and the vent all have proper solid boxes
    }

    /** Props that throw warm light once the sun is off the ridge. */
    fun propGlow(kind: Int): Float = when (kind) {
        PKind.LANTERN -> 1f
        PKind.FIREPIT -> 1.5f
        PKind.BRAZIER -> 1.2f
        PKind.ICE_HUT -> 0.7f
        else -> 0f
    }

    // ---- trees you can fell, and things you can pick ----
    class Tree(val x: Float, val z: Float, val kind: Int, val scale: Float, val seed: Int)
    class ForageSpot(val x: Float, val z: Float, val itemId: String, val seed: Int)

    val trees: Array<Tree>
    val forage: Array<ForageSpot>

    val TREE_COUNT: Int get() = trees.size
    val FORAGE_COUNT: Int get() = forage.size

    /** Somewhere out in the open: walkable snow, clear of home and track. */
    private fun openSpot(x: Float, z: Float, pathClear: Float): Boolean {
        val h = Terrain.height(x, z)
        if (h < Terrain.ICE_Y + 0.25f || h > Terrain.WALK_CEILING - 0.4f) return false
        if (Terrain.steepness(x, z) > 0.42f) return false
        if (inGlasshouse(x, z, 2.4f)) return false
        if (distToPath(x, z) < pathClear) return false
        if (Terrain.springWarmth(x, z) > 0.05f) return false
        for (s in solids) {
            if (Box(s.x0 - 3.0f, s.z0 - 3.0f, s.x1 + 3.0f, s.z1 + 3.0f).contains(x, z)) return false
        }
        for (p in props) {
            if (abs(p.x - x) < 2.4f && abs(p.z - z) < 2.4f) return false
        }
        return true
    }

    init {
        // deadwood and standing timber close enough to home to be worth felling
        val treeList = ArrayList<Tree>(30)
        var seed = 17
        while (treeList.size < 26 && seed < 40000) {
            seed++
            val x = -Terrain.HALF + U.hash(seed * 31 + 7) * (Terrain.HALF * 2f)
            val z = -Terrain.HALF + U.hash(seed * 17 + 3) * (Terrain.HALF * 2f)
            if (sqrt(x * x + z * z) > 30f) continue
            if (!openSpot(x, z, 2.6f)) continue
            var tooClose = false
            for (t in treeList) {
                if (abs(t.x - x) < 4.2f && abs(t.z - z) < 4.2f) { tooClose = true; break }
            }
            if (tooClose) continue
            val roll = U.hash(seed * 53 + 11)
            val kind = when { roll < 0.46f -> 0; roll < 0.80f -> 1; else -> 2 }
            val scale = 0.88f + U.hash(seed * 91 + 5) * 0.35f
            treeList.add(Tree(x, z, kind, scale, seed * 977 + 41))
        }
        trees = treeList.toTypedArray()

        val kinds = arrayOf(
            "pinecone", "kindling", "winterberry", "pinecone",
            "kindling", "snowdrop", "winterberry", "capmush"
        )
        val forageList = ArrayList<ForageSpot>(30)
        seed = 5000
        while (forageList.size < 26 && seed < 60000) {
            seed++
            val x = -Terrain.HALF + U.hash(seed * 73 + 19) * (Terrain.HALF * 2f)
            val z = -Terrain.HALF + U.hash(seed * 29 + 13) * (Terrain.HALF * 2f)
            if (sqrt(x * x + z * z) > 32f) continue
            if (!openSpot(x, z, 1.4f)) continue
            var tooClose = false
            for (f in forageList) {
                if (abs(f.x - x) < 3.2f && abs(f.z - z) < 3.2f) { tooClose = true; break }
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
    const val Z_GLASS = 1
    const val Z_MARKET = 2
    const val Z_CREEK = 3
    const val Z_POND = 4
    const val Z_WOODS = 5
    const val Z_MEADOW = 6
    const val Z_SHED = 7
    const val Z_SPRING = 8
    const val Z_INSIDE = 9

    fun zoneAt(x: Float, z: Float): Int {
        if (abs(x - CABIN_X) < 7.5f && abs(z - CABIN_Z) < 7.5f) return Z_HOME
        if (inGlasshouse(x, z, 3f)) return Z_GLASS
        if (abs(x - SHED_X) < 4.5f && abs(z - SHED_Z) < 4.5f) return Z_SHED
        if (abs(x - MARKET_X) < 6f && abs(z - MARKET_Z) < 6f) return Z_MARKET
        val ds = sqrt((x - SPRING_X) * (x - SPRING_X) + (z - SPRING_Z) * (z - SPRING_Z))
        if (ds < Terrain.SPRING_R + 5f) return Z_SPRING
        val dp = sqrt((x - POND_X) * (x - POND_X) + (z - POND_Z) * (z - POND_Z))
        if (dp < Terrain.POND_R + 4f) return Z_POND
        if (abs(x - Terrain.creekX(z)) < 9f) return Z_CREEK
        if (sqrt(x * x + z * z) > 26f) return Z_WOODS
        return Z_MEADOW
    }

    fun zoneName(zone: Int): String = when (zone) {
        Z_HOME -> "The Yard"
        Z_GLASS -> "The Glasshouse"
        Z_MARKET -> "Pip's Stall"
        Z_CREEK -> "Frozen Creek"
        Z_POND -> "Stillwater Pond"
        Z_WOODS -> "The Pinewood"
        Z_SHED -> "The Woodshed"
        Z_SPRING -> "The Steam Vent"
        Z_INSIDE -> "Inside"
        else -> "Snowfield"
    }

    fun treeStanding(state: GameState, i: Int): Boolean =
        i < state.treeRegrow.size && (state.treeRegrow[i] == 0 || state.day >= state.treeRegrow[i])

    fun forageAvailable(state: GameState, i: Int): Boolean =
        i < state.foragePicked.size && state.foragePicked[i] != state.day

    fun clampWalk(x: Float, z: Float, out: FloatArray) {
        out[0] = max(-Terrain.HALF + 3f, min(Terrain.HALF - 3f, x))
        out[1] = max(-Terrain.HALF + 3f, min(Terrain.HALF - 3f, z))
    }
}
