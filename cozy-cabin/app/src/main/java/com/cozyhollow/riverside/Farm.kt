package com.cozyhollow.riverside

import kotlin.math.abs

/** Proximity queries against the field, the woods and the forageables. */
object FarmQuery {

    fun nearestTree(st: GameState, x: Float, reach: Float): Int {
        var best = -1
        var bestD = reach
        for (i in 0 until World.TREE_COUNT) {
            if (!World.treeStanding(st, i)) continue
            val d = abs(World.trees[i].x - x)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun nearestForage(st: GameState, x: Float, reach: Float): Int {
        var best = -1
        var bestD = reach
        for (i in 0 until World.FORAGE_COUNT) {
            if (!World.forageAvailable(st, i)) continue
            val d = abs(World.forage[i].x - x)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun nearestPlot(st: GameState, x: Float, reach: Float): Int {
        var best = -1
        var bestD = reach
        for (i in 0 until st.tier.plots) {
            val d = abs(World.plotX(i) - x)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
