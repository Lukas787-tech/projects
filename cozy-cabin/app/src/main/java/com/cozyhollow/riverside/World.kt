package com.cozyhollow.riverside

/**
 * Static layout of the valley. Everything is expressed in "world units" where the
 * screen is always [VIEW_H] units tall, so the game looks identical on every device.
 * Left to right: deep forest -> your cabin -> the farm -> the market -> the river.
 */
object World {

    const val VIEW_H = 720f
    const val GROUND_Y = 468f
    const val WORLD_W = 4200f
    const val WALK_MIN = 96f
    const val WALK_MAX = 3390f

    const val CABIN_X = 1250f
    const val MARKET_X = 3068f
    const val RIVER_EDGE = 3420f
    const val WATER_Y = 500f

    const val FARM_START = 1636f
    const val PLOT_SPACING = 76f
    const val MAX_PLOTS = 16

    const val TREE_COUNT = 24
    const val FORAGE_COUNT = 16

    // zones
    const val Z_FOREST = 0
    const val Z_HOME = 1
    const val Z_FARM = 2
    const val Z_MARKET = 3
    const val Z_RIVER = 4

    class Tree(val x: Float, val kind: Int, val scale: Float, val seed: Int)
    class ForageSpot(val x: Float, val itemId: String, val seed: Int)

    val trees: Array<Tree>
    val forage: Array<ForageSpot>

    init {
        trees = Array(TREE_COUNT) { i ->
            // Most of the choppable trees live in the forest; a few frame the cabin.
            val x = if (i < 18) {
                140f + i * 48f + U.hash(i * 31 + 7) * 22f
            } else {
                1000f + (i - 18) * 96f + U.hash(i * 17 + 3) * 40f
            }
            val kind = if (U.hash(i * 53 + 11) < 0.55f) 0 else 1
            val scale = 0.82f + U.hash(i * 91 + 5) * 0.36f
            Tree(x, kind, scale, i * 977 + 41)
        }

        val kinds = arrayOf("mushroom", "acorn", "flower", "mushroom", "flower", "honey")
        forage = Array(FORAGE_COUNT) { i ->
            val x = if (i < 10) {
                180f + i * 88f + U.hash(i * 73 + 19) * 36f
            } else {
                2960f + (i - 10) * 74f + U.hash(i * 29 + 13) * 26f
            }
            val id = kinds[(U.hash(i * 137 + 23) * kinds.size).toInt().coerceIn(0, kinds.size - 1)]
            ForageSpot(x, id, i * 613 + 17)
        }
    }

    fun plotX(index: Int): Float = FARM_START + index * PLOT_SPACING

    fun zoneAt(x: Float): Int = when {
        x < 1040f -> Z_FOREST
        x < 1560f -> Z_HOME
        x < 2900f -> Z_FARM
        x < 3290f -> Z_MARKET
        else -> Z_RIVER
    }

    fun zoneName(z: Int): String = when (z) {
        Z_FOREST -> "Whispering Woods"
        Z_HOME -> "Home"
        Z_FARM -> "The Field"
        Z_MARKET -> "Market Stall"
        else -> "Riverbank"
    }

    /** Half-width of the cabin footprint, used for the door hotspot. */
    const val CABIN_DOOR_X = CABIN_X + 46f

    fun treeStanding(state: GameState, i: Int): Boolean =
        state.treeRegrow[i] == 0 || state.day >= state.treeRegrow[i]

    fun forageAvailable(state: GameState, i: Int): Boolean =
        state.foragePicked[i] != state.day
}
