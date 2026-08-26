package com.cozyhollow.riverside

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

object Act {
    const val NONE = 0
    const val SWING = 1     // axe / auger
    const val POUR = 2      // watering can, kettle
    const val PICK = 3
    const val FISH = 4
    const val CHEER = 5
    const val SIT = 6
    const val WORK = 7      // stove, bench, feeding
    const val SOAK = 8      // in the steam vent
}

/** Where you are and what you are doing. The renderer builds the body. */
class Player {

    var x = World.SPAWN_X
    var z = World.SPAWN_Z
    /** Height of whatever is under the boots, eased so drifts never jolt. */
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
    /** Ground slope under the feet, so the body leans into a drift. */
    var pitch = 0f
    /** True while walking about inside the cabin. */
    var indoors = false

    /** How deep the snow is here, 0..1. Deep drifts slow you to a trudge. */
    var sink = 0f
        private set

    /** Ticks up while walking so the game knows when to leave a footprint. */
    var stepAccum = 0f

    /** A gentle amble. Nothing in this game is a race. */
    private val baseSpeed: Float get() = if (indoors) 2.6f else 3.7f

    val busy: Boolean get() = action != Act.NONE &&
        action != Act.FISH && action != Act.SIT && action != Act.SOAK

    val moving: Boolean get() = abs(vx) + abs(vz) > 0.15f

    val sitting: Boolean get() = action == Act.SIT || action == Act.SOAK

    fun startAction(a: Int, dur: Float) {
        action = a; actionT = 0f; actionDur = dur
    }

    fun stopAction() {
        action = Act.NONE; actionT = 0f; actionDur = 0f
    }

    fun placeAt(px: Float, pz: Float) {
        x = px; z = pz
        y = if (indoors) Interior.FLOOR_Y else Terrain.groundY(px, pz)
    }

    fun enterInterior() {
        indoors = true
        stopAction()
        placeAt(Interior.DOOR_X, Interior.DOOR_Z - 0.75f)
        yaw = 180f
        pitch = 0f
        sink = 0f
    }

    fun exitInterior() {
        indoors = false
        stopAction()
        placeAt(World.CABIN_DOOR_X, World.CABIN_DOOR_Z)
        yaw = 0f
        pitch = 0f
    }

    private fun groundHere(px: Float, pz: Float): Float =
        if (indoors) Interior.FLOOR_Y else Terrain.groundY(px, pz)

    private fun blockedHere(st: GameState, px: Float, pz: Float): Boolean =
        if (indoors) Interior.blocked(px, pz) else World.blocked(st, px, pz)

    /**
     * [chill] is how much the cold is slowing you down, 0..1. It never stops
     * you — the worst it does is turn a walk into a trudge, which is the game
     * quietly suggesting you head for a fire.
     */
    fun update(dt: Float, dirX: Float, dirZ: Float, st: GameState, chill: Float = 0f) {
        if (action != Act.NONE) {
            actionT += dt
            val endless = action == Act.FISH || action == Act.SIT || action == Act.SOAK
            if (!endless && actionT >= actionDur) stopAction()
        }
        val canMove = !busy && action != Act.FISH && action != Act.SIT && action != Act.SOAK

        var mx = if (canMove) dirX else 0f
        var mz = if (canMove) dirZ else 0f
        val len = sqrt(mx * mx + mz * mz)
        if (len > 1f) { mx /= len; mz /= len }

        // fresh drift off the beaten track is heavy going, and so is being cold
        sink = if (indoors) 0f else {
            val off = U.clamp01((World.distToPath(x, z) - 1.2f) / 5f)
            val ice = if (Terrain.onIce(x, z)) 0f else off
            U.lerp(sink, ice, kotlin.math.min(1f, dt * 3f))
        }
        val speed = baseSpeed * (1f - sink * 0.24f) * (1f - chill * 0.34f)

        vx = mx * speed
        vz = mz * speed

        if (len > 0.06f) {
            // move each axis on its own so you slide along walls instead of sticking
            val nx = x + vx * dt
            if (!blockedHere(st, nx, z)) x = nx
            val nz = z + vz * dt
            if (!blockedHere(st, x, nz)) z = nz

            walkPhase += dt * 8.2f * (0.6f + len * 0.6f) * (1f - sink * 0.3f)
            stepAccum += dt * speed
            val target = Math.toDegrees(atan2(mx.toDouble(), mz.toDouble())).toFloat()
            yaw = turnToward(yaw, target, dt * 520f)
        } else {
            vx = 0f; vz = 0f
            walkPhase *= (1f - kotlin.math.min(1f, dt * 8f))
        }
        idlePhase += dt

        val gy = groundHere(x, z)
        y = U.lerp(y, gy, kotlin.math.min(1f, dt * 14f))

        // lean with whatever you are climbing
        if (indoors) {
            pitch = U.lerp(pitch, 0f, kotlin.math.min(1f, dt * 6f))
        } else {
            val ahead = 0.5f
            val rad = Math.toRadians(yaw.toDouble())
            val fx = kotlin.math.sin(rad).toFloat()
            val fz = kotlin.math.cos(rad).toFloat()
            val slope = Terrain.groundY(x + fx * ahead, z + fz * ahead) - gy
            pitch = U.lerp(pitch, U.clamp(-slope * 26f, -14f, 14f), kotlin.math.min(1f, dt * 6f))
        }
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
