package com.cozyhollow.riverside

/** Proximity queries against the field, the woods and the forageables, in 2D. */
object FarmQuery {

    private fun d2(ax: Float, az: Float, bx: Float, bz: Float): Float {
        val dx = ax - bx
        val dz = az - bz
        return dx * dx + dz * dz
    }

    fun nearestTree(st: GameState, x: Float, z: Float, reach: Float): Int {
        var best = -1
        var bestD = reach * reach
        for (i in World.trees.indices) {
            if (!World.treeStanding(st, i)) continue
            val d = d2(x, z, World.trees[i].x, World.trees[i].z)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun nearestForage(st: GameState, x: Float, z: Float, reach: Float): Int {
        var best = -1
        var bestD = reach * reach
        for (i in World.forage.indices) {
            if (!World.forageAvailable(st, i)) continue
            val d = d2(x, z, World.forage[i].x, World.forage[i].z)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun nearestPlot(st: GameState, x: Float, z: Float, reach: Float): Int {
        var best = -1
        var bestD = reach * reach
        for (i in 0 until st.tier.plots) {
            val d = d2(x, z, World.plotX(i), World.plotZ(i))
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
