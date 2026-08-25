package com.cozyhollow.riverside.gl

import android.opengl.Matrix
import com.cozyhollow.riverside.World

/** Conversion between the gameplay's flat world units and 3D metres. */
object W3 {
    /** 60 world units = 1 metre. */
    const val M = 1f / 60f

    fun x(worldX: Float): Float = worldX * M

    val RIVER_X: Float get() = World.RIVER_EDGE * M
    val CABIN_X: Float get() = World.CABIN_X * M
    val MARKET_X: Float get() = World.MARKET_X * M

    /** How hard the valley wraps around its cylinder. */
    const val CURVE = 0.0105f

    const val WALK_Z = 0.55f
    const val CABIN_Z = -2.0f
    const val MARKET_Z = -1.7f
    const val PLOT_Z = -1.15f
    const val TREE_Z = -2.9f

    const val WATER_Y = -0.28f
    const val BED_Y = -0.85f
    /** Where the bank starts falling away toward the water. */
    val BANK_X: Float get() = RIVER_X - 0.9f
    val BANK_END: Float get() = RIVER_X + 1.1f

    fun groundHeight(x: Float): Float {
        val b0 = BANK_X
        val b1 = BANK_END
        return when {
            x <= b0 -> 0f
            x >= b1 -> BED_Y
            else -> {
                val t = (x - b0) / (b1 - b0)
                val s = t * t * (3f - 2f * t)
                BED_Y * s
            }
        }
    }
}

/** A tiny matrix stack over android.opengl.Matrix. */
class MStack(depth: Int = 16) {
    private val stack = Array(depth) { FloatArray(16) }
    private var sp = 0
    private val tmp = FloatArray(16)

    val m: FloatArray get() = stack[sp]

    fun identity(): MStack {
        Matrix.setIdentityM(stack[sp], 0); return this
    }

    fun push(): MStack {
        System.arraycopy(stack[sp], 0, stack[sp + 1], 0, 16); sp++; return this
    }

    fun pop(): MStack {
        sp--; return this
    }

    fun translate(x: Float, y: Float, z: Float): MStack {
        Matrix.translateM(stack[sp], 0, x, y, z); return this
    }

    fun rotateX(deg: Float): MStack {
        Matrix.rotateM(stack[sp], 0, deg, 1f, 0f, 0f); return this
    }

    fun rotateY(deg: Float): MStack {
        Matrix.rotateM(stack[sp], 0, deg, 0f, 1f, 0f); return this
    }

    fun rotateZ(deg: Float): MStack {
        Matrix.rotateM(stack[sp], 0, deg, 0f, 0f, 1f); return this
    }

    fun scale(x: Float, y: Float, z: Float): MStack {
        Matrix.scaleM(stack[sp], 0, x, y, z); return this
    }

    fun mul(other: FloatArray): MStack {
        Matrix.multiplyMM(tmp, 0, stack[sp], 0, other, 0)
        System.arraycopy(tmp, 0, stack[sp], 0, 16)
        return this
    }
}
