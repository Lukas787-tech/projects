package com.cozyhollow.riverside.gl

import android.opengl.Matrix

/** A tiny matrix stack over android.opengl.Matrix. */
class MStack(depth: Int = 16) {
    private val stack = Array(depth) { FloatArray(16) }
    private var sp = 0
    private val tmp = FloatArray(16)

    val m: FloatArray get() = stack[sp]

    fun identity(): MStack {
        sp = 0
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
