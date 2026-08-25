package com.cozyhollow.riverside

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

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

    var x = World.CABIN_X + 200f
    var z = World.CABIN_Z + 200f
    var vx = 0f
    var vz = 0f
    /** Model heading in degrees; 0 faces the camera. */
    var yaw = 0f
    var facing = 1
    var walkPhase = 0f
    var idlePhase = 0f
    var action = Act.NONE
    var actionT = 0f
    var actionDur = 0f

    val speed: Float get() = 250f

    val busy: Boolean get() = action != Act.NONE && action != Act.FISH

    val moving: Boolean get() = abs(vx) + abs(vz) > 6f

    fun startAction(a: Int, dur: Float) {
        action = a; actionT = 0f; actionDur = dur
    }

    fun stopAction() {
        action = Act.NONE; actionT = 0f; actionDur = 0f
    }

    fun update(dt: Float, dirX: Float, dirZ: Float, st: GameState) {
        if (action != Act.NONE) {
            actionT += dt
            if (action != Act.FISH && actionT >= actionDur) stopAction()
        }
        val canMove = !busy && action != Act.FISH

        var mx = if (canMove) dirX else 0f
        var mz = if (canMove) dirZ else 0f
        val len = sqrt(mx * mx + mz * mz)
        if (len > 1f) { mx /= len; mz /= len }

        vx = mx * speed
        vz = mz * speed

        if (len > 0.06f) {
            // move each axis on its own so you slide along walls instead of sticking
            val nx = x + vx * dt
            if (!World.blocked(st, nx, z)) x = nx
            val nz = z + vz * dt
            if (!World.blocked(st, x, nz)) z = nz

            walkPhase += dt * 9.5f * (0.6f + len * 0.6f)
            val target = Math.toDegrees(atan2(mx.toDouble(), mz.toDouble())).toFloat()
            yaw = turnToward(yaw, target, dt * 620f)
            facing = if (mx >= 0f) 1 else -1
        } else {
            vx = 0f; vz = 0f
            walkPhase *= (1f - kotlin.math.min(1f, dt * 8f))
        }
        idlePhase += dt

        x = x.coerceIn(World.WALK_MIN, World.WALK_MAX)
        z = z.coerceIn(World.Z_MIN, World.Z_MAX)
    }

    /** Shortest-way angle interpolation, so turning never spins the long way round. */
    private fun turnToward(from: Float, to: Float, maxStep: Float): Float {
        var diff = (to - from) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val step = diff.coerceIn(-maxStep, maxStep)
        var r = from + step
        if (r > 360f) r -= 360f
        if (r < -360f) r += 360f
        return r
    }
}
