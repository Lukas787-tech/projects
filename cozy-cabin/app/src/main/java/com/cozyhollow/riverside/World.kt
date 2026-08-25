package com.cozyhollow.riverside

/**
 * Static layout of the valley, in "world units" (60 units = 1 metre).
 *
 * The valley is a walkable *area*, not a line: x runs left to right along the
 * river, z runs from the deep woods at the back toward the camera at the front.
 * Left to right: forest -> your cabin -> the field -> the market -> the river.
 */
object World {

    const val WORLD_W = 4200f

    // walkable bounds
    const val WALK_MIN = 120f
    const val WALK_MAX = 3350f
    const val Z_MIN = -430f
    const val Z_MAX = 315f

    const val CABIN_X = 1250f
    const val CABIN_Z = -175f
    const val MARKET_X = 3040f
    const val MARKET_Z = -160f
    const val RIVER_EDGE = 3420f

    /** In front of the cabin door, where sleeping is offered. */
    const val CABIN_DOOR_X = CABIN_X + 70f
    const val CABIN_DOOR_Z = CABIN_Z + 150f

    // ---- the field: a 4x4 grid rather than a single row ----
    const val MAX_PLOTS = 16
    const val PLOT_COLS = 4
    const val FARM_X0 = 1840f
    const val PLOT_DX = 122f
    const val FARM_Z0 = -120f
    const val PLOT_DZ = 116f

    const val TREE_COUNT = 30
    const val FORAGE_COUNT = 20

    // zones
    const val Z_FOREST = 0
    const val Z_HOME = 1
    const val Z_FARM = 2
    const val Z_MARKET = 3
    const val Z_RIVER = 4

    class Tree(val x: Float, val z: Float, val kind: Int, val scale: Float, val seed: Int)
    class ForageSpot(val x: Float, val z: Float, val itemId: String, val seed: Int)

    class Box(val x0: Float, val z0: Float, val x1: Float, val z1: Float) {
        fun contains(x: Float, z: Float) = x > x0 && x < x1 && z > z0 && z < z1
    }

    val trees: Array<Tree>
    val forage: Array<ForageSpot>

    /** Buildings you have to walk around. */
    val solids = arrayOf(
        Box(CABIN_X - 140f, CABIN_Z - 110f, CABIN_X + 140f, CABIN_Z + 110f),
        Box(MARKET_X - 165f, MARKET_Z - 75f, MARKET_X + 165f, MARKET_Z + 75f)
    )

    /** Nothing scatters inside the field or on top of a building. */
    private val fieldArea = Box(FARM_X0 - 140f, FARM_Z0 - 130f, plotX(PLOT_COLS - 1) + 140f, plotZ(MAX_PLOTS - 1) + 130f)

    private fun freeSpot(x: Float, z: Float): Boolean {
        if (fieldArea.contains(x, z)) return false
        for (s in solids) if (Box(s.x0 - 80f, s.z0 - 80f, s.x1 + 80f, s.z1 + 80f).contains(x, z)) return false
        // keep the front strip clear so there is always a way past
        if (z > 170f && x > 900f && x < 3200f) return false
        return true
    }

    init {
        val treeList = ArrayList<Tree>(TREE_COUNT)
        var seed = 7
        while (treeList.size < TREE_COUNT && seed < 4000) {
            seed++
            val x = 150f + U.hash(seed * 31 + 7) * 1600f
            val z = Z_MIN + 40f + U.hash(seed * 17 + 3) * (Z_MAX - Z_MIN - 90f)
            if (!freeSpot(x, z)) continue
            var tooClose = false
            for (t in treeList) {
                if (kotlin.math.abs(t.x - x) < 95f && kotlin.math.abs(t.z - z) < 95f) { tooClose = true; break }
            }
            if (tooClose) continue
            val kind = if (U.hash(seed * 53 + 11) < 0.5f) 0 else 1
            val scale = 0.82f + U.hash(seed * 91 + 5) * 0.4f
            treeList.add(Tree(x, z, kind, scale, seed * 977 + 41))
        }
        trees = treeList.toTypedArray()

        val kinds = arrayOf("mushroom", "acorn", "flower", "mushroom", "flower", "honey")
        val forageList = ArrayList<ForageSpot>(FORAGE_COUNT)
        seed = 500
        while (forageList.size < FORAGE_COUNT && seed < 5000) {
            seed++
            val x = 160f + U.hash(seed * 73 + 19) * 3050f
            val z = Z_MIN + 50f + U.hash(seed * 29 + 13) * (Z_MAX - Z_MIN - 100f)
            if (!freeSpot(x, z)) continue
            var tooClose = false
            for (f in forageList) {
                if (kotlin.math.abs(f.x - x) < 130f && kotlin.math.abs(f.z - z) < 90f) { tooClose = true; break }
            }
            if (tooClose) continue
            for (t in trees) {
                if (kotlin.math.abs(t.x - x) < 55f && kotlin.math.abs(t.z - z) < 55f) { tooClose = true; break }
            }
            if (tooClose) continue
            val id = kinds[(U.hash(seed * 137 + 23) * kinds.size).toInt().coerceIn(0, kinds.size - 1)]
            forageList.add(ForageSpot(x, z, id, seed * 613 + 17))
        }
        forage = forageList.toTypedArray()
    }

    fun plotX(index: Int): Float = FARM_X0 + (index % PLOT_COLS) * PLOT_DX
    fun plotZ(index: Int): Float = FARM_Z0 + (index / PLOT_COLS) * PLOT_DZ

    /** True if this spot is inside something you cannot walk through. */
    fun blocked(st: GameState, x: Float, z: Float): Boolean {
        if (x < WALK_MIN || x > WALK_MAX || z < Z_MIN || z > Z_MAX) return true
        for (s in solids) if (s.contains(x, z)) return true
        for (i in trees.indices) {
            if (!treeStanding(st, i)) continue
            val t = trees[i]
            val dx = x - t.x
            val dz = (z - t.z) * 1.35f
            val r = 26f * t.scale
            if (dx * dx + dz * dz < r * r) return true
        }
        return false
    }

    fun zoneAt(x: Float): Int = when {
        x < 1040f -> Z_FOREST
        x < 1660f -> Z_HOME
        x < 2620f -> Z_FARM
        x < 3250f -> Z_MARKET
        else -> Z_RIVER
    }

    fun zoneName(z: Int): String = when (z) {
        Z_FOREST -> "Whispering Woods"
        Z_HOME -> "Home"
        Z_FARM -> "The Field"
        Z_MARKET -> "Market Stall"
        else -> "Riverbank"
    }

    fun treeStanding(state: GameState, i: Int): Boolean =
        i < state.treeRegrow.size && (state.treeRegrow[i] == 0 || state.day >= state.treeRegrow[i])

    fun forageAvailable(state: GameState, i: Int): Boolean =
        i < state.foragePicked.size && state.foragePicked[i] != state.day
}
