package com.cozyhollow.riverside

import kotlin.math.abs

object Act {
    const val NONE = 0
    const val SWING = 1     // axe / hoe
    const val WATER = 2
    const val PICK = 3
    const val FISH = 4
    const val CHEER = 5
}

/** Movement and animation state for the farmer. The renderer builds the body. */
class Player {

    var x = World.CABIN_X + 140f
    var vx = 0f
    var facing = 1
    var walkPhase = 0f
    var idlePhase = 0f
    var action = Act.NONE
    var actionT = 0f
    var actionDur = 0f

    val speed: Float get() = 250f

    val busy: Boolean get() = action != Act.NONE && action != Act.FISH

    fun startAction(a: Int, dur: Float) {
        action = a; actionT = 0f; actionDur = dur
    }

    fun stopAction() {
        action = Act.NONE; actionT = 0f; actionDur = 0f
    }

    fun update(dt: Float, moveDir: Float) {
        if (action != Act.NONE) {
            actionT += dt
            if (action != Act.FISH && actionT >= actionDur) stopAction()
        }
        val canMove = !busy && action != Act.FISH
        vx = if (canMove) moveDir * speed else 0f
        if (abs(vx) > 1f) {
            x += vx * dt
            walkPhase += dt * 9.5f
            facing = if (vx > 0) 1 else -1
        } else {
            walkPhase *= (1f - kotlin.math.min(1f, dt * 8f))
        }
        idlePhase += dt
        x = x.coerceIn(World.WALK_MIN, World.WALK_MAX)
    }
}
