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
    const val SIT = 6
}

/** Where the farmer is and what they are doing. The renderer builds the body. */
class Player {

    var x = World.SPAWN_X
    var z = World.SPAWN_Z
    /** Height of the ground under the boots, eased so slopes never jolt. */
    var y = 0f
    var vx = 0f
    var vz = 0f
    /** Model heading in degrees; 0 faces +z, which is toward a resting camera. */
    var yaw = 0f
    var walkPhase = 0f
    var idlePhase = 0f
    var action = Act.NONE
    var actionT = 0f
    var actionDur = 0f
    /** Ground slope under the feet, so the body leans into a hill. */
    var pitch = 0f

    /** A gentle amble. Nothing in this game is a race. */
    val speed: Float get() = 3.9f

    val busy: Boolean get() = action != Act.NONE && action != Act.FISH && action != Act.SIT

    val moving: Boolean get() = abs(vx) + abs(vz) > 0.15f

    val sitting: Boolean get() = action == Act.SIT

    fun startAction(a: Int, dur: Float) {
        action = a; actionT = 0f; actionDur = dur
    }

    fun stopAction() {
        action = Act.NONE; actionT = 0f; actionDur = 0f
    }

    fun placeAt(px: Float, pz: Float) {
        x = px; z = pz
        y = Terrain.groundY(px, pz)
    }

    fun update(dt: Float, dirX: Float, dirZ: Float, st: GameState) {
        if (action != Act.NONE) {
            actionT += dt
            val endless = action == Act.FISH || action == Act.SIT
            if (!endless && actionT >= actionDur) stopAction()
        }
        val canMove = !busy && action != Act.FISH && action != Act.SIT

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

            walkPhase += dt * 8.2f * (0.6f + len * 0.6f)
            val target = Math.toDegrees(atan2(mx.toDouble(), mz.toDouble())).toFloat()
            yaw = turnToward(yaw, target, dt * 520f)
        } else {
            vx = 0f; vz = 0f
            walkPhase *= (1f - kotlin.math.min(1f, dt * 8f))
        }
        idlePhase += dt

        val gy = Terrain.groundY(x, z)
        y = U.lerp(y, gy, kotlin.math.min(1f, dt * 14f))

        // lean with the hill you are walking on
        val ahead = 0.5f
        val rad = Math.toRadians(yaw.toDouble())
        val fx = kotlin.math.sin(rad).toFloat()
        val fz = kotlin.math.cos(rad).toFloat()
        val slope = Terrain.groundY(x + fx * ahead, z + fz * ahead) - gy
        pitch = U.lerp(pitch, U.clamp(-slope * 26f, -14f, 14f), kotlin.math.min(1f, dt * 6f))
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
