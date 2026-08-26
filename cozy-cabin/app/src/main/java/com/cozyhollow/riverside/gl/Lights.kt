package com.cozyhollow.riverside.gl

import android.graphics.Color
import com.cozyhollow.riverside.U

/**
 * The warm lights.
 *
 * Everything in the world that glows orange — a lit window, a lantern on a
 * post, the fire in the yard, the brazier at the stall, the hearth indoors —
 * registers itself here every frame. The set then keeps only the [Shaders.MAX_LIGHTS]
 * that matter most from where the camera is standing and hands those to the
 * shader.
 *
 * "Matter most" is not simply "nearest". A big fire twenty metres off is worth
 * more to the frame than a candle at your elbow, so the score is the distance
 * scaled by the light's own reach: a light is interesting while you are inside
 * its pool and stops mattering once you are well outside it.
 */
class LightSet {

    private val cap = 32
    private val cx = FloatArray(cap)
    private val cy = FloatArray(cap)
    private val cz = FloatArray(cap)
    private val cr = FloatArray(cap)
    private val cg = FloatArray(cap)
    private val cb = FloatArray(cap)
    private val radius = FloatArray(cap)
    private val power = FloatArray(cap)
    private var n = 0

    /**
     * Packed for the shader as two vec4 arrays: xyz position with the reach in
     * w, and xyz colour with the burn strength in w. Two arrays rather than
     * three keeps the fragment stage inside the sixteen uniform vectors that
     * GL ES 2.0 guarantees, which matters on the oldest phones this runs on.
     */
    val posBuf = FloatArray(Shaders.MAX_LIGHTS * 4)
    val colBuf = FloatArray(Shaders.MAX_LIGHTS * 4)

    fun begin() {
        n = 0
    }

    /**
     * [power] is how hard the light burns, roughly 0..2. [radius] is where its
     * pool ends, in metres. A lantern is about (4.5, 0.9); a bonfire is more
     * like (9, 1.6).
     */
    fun add(x: Float, y: Float, z: Float, color: Int, radiusM: Float, power: Float) {
        if (n >= cap || radiusM <= 0.01f || power <= 0.001f) return
        cx[n] = x; cy[n] = y; cz[n] = z
        cr[n] = Color.red(color) / 255f * power
        cg[n] = Color.green(color) / 255f * power
        cb[n] = Color.blue(color) / 255f * power
        radius[n] = radiusM
        this.power[n] = power
        n++
    }

    /** Picks the best few for this viewpoint and packs them for upload. */
    fun select(eyeX: Float, eyeY: Float, eyeZ: Float) {
        for (k in 0 until Shaders.MAX_LIGHTS) {
            posBuf[k * 4] = 0f; posBuf[k * 4 + 1] = -1000f
            posBuf[k * 4 + 2] = 0f; posBuf[k * 4 + 3] = 0f
            colBuf[k * 4] = 0f; colBuf[k * 4 + 1] = 0f
            colBuf[k * 4 + 2] = 0f; colBuf[k * 4 + 3] = 0f
        }
        if (n == 0) return

        // selection sort over a handful of candidates: cheaper than allocating
        // anything, and n is never more than a couple of dozen
        val taken = BooleanArray(n)
        for (slot in 0 until Shaders.MAX_LIGHTS) {
            var best = -1
            var bestScore = Float.MAX_VALUE
            for (i in 0 until n) {
                if (taken[i]) continue
                val dx = cx[i] - eyeX
                val dy = cy[i] - eyeY
                val dz = cz[i] - eyeZ
                val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                val score = d / (radius[i] * (0.6f + power[i] * 0.5f))
                if (score < bestScore) { bestScore = score; best = i }
            }
            if (best < 0) break
            taken[best] = true
            posBuf[slot * 4] = cx[best]
            posBuf[slot * 4 + 1] = cy[best]
            posBuf[slot * 4 + 2] = cz[best]
            posBuf[slot * 4 + 3] = radius[best]
            colBuf[slot * 4] = cr[best]
            colBuf[slot * 4 + 1] = cg[best]
            colBuf[slot * 4 + 2] = cb[best]
            // fade a light out rather than popping it when it drops off the list
            colBuf[slot * 4 + 3] = U.clamp01(2.2f - bestScore)
        }
    }
}
