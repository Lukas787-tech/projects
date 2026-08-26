package com.cozyhollow.riverside.gl

import android.opengl.GLES20.*
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The drawing kit.
 *
 * Everything in the world is assembled from a handful of unit primitives
 * pushed through a matrix stack, so rather than pass six uniform locations and
 * a mesh library into every function that draws a mailbox, the whole kit lives
 * here and the building, prop, actor and room renderers each take one of these.
 */
class DrawCtx {

    var tex: Textures? = null
    var prims: Prims? = null
    val ms = MStack(28)

    var aPos = 0; var aNor = 0; var aUv = 0; var aCol = 0
    var uModel = 0; var uColor = 0; var uUvScale = 0
    var uEmissive = 0; var uCut = 0

    /** Billboards only ever turn about y, and always to face the camera. */
    var billboardYaw = 0f

    /** Texture repeats per metre. */
    var texels = 0.6f

    private var uvX = 1f
    private var uvY = 1f

    fun uv(a: Float, b: Float) {
        uvX = a; uvY = b
    }

    fun emissive(v: Float) {
        glUniform1f(uEmissive, v)
    }

    fun cutoff(v: Float) {
        glUniform1f(uCut, v)
    }

    fun bindAndDraw(
        mesh: Mesh?, texId: Int,
        tintR: Float = 1f, tintG: Float = 1f, tintB: Float = 1f, alpha: Float = 1f
    ) {
        val m = mesh ?: return
        glBindTexture(GL_TEXTURE_2D, texId)
        glUniformMatrix4fv(uModel, 1, false, ms.m, 0)
        glUniform4f(uColor, tintR, tintG, tintB, alpha)
        glUniform2f(uUvScale, uvX, uvY)
        m.bind(aPos, aNor, aUv, aCol)
        m.draw()
        uvX = 1f; uvY = 1f
    }

    /** Box helper: centre x/z, base y, size in metres. */
    fun box(
        x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float,
        texId: Int, closed: Boolean = false, uvPerM: Float = texels,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f
    ) {
        ms.push().translate(x, y, z).scale(sx, sy, sz)
        uv(max(sx, sz) * uvPerM, sy * uvPerM)
        bindAndDraw(if (closed) prims?.boxClosed else prims?.box, texId, r, g, b, a)
        ms.pop()
    }

    /**
     * A flat bed, wide and low. The box helper takes its texture density from
     * the object's height, which is right for a wall and wrong for a slab: a
     * five-centimetre one gets a single row of texels smeared across the whole
     * top face. This one measures the footprint instead.
     */
    fun slab(
        x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float,
        texId: Int, uvPerM: Float = texels, r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f
    ) {
        ms.push().translate(x, y, z).scale(sx, sy, sz)
        uv(sx * uvPerM, sz * uvPerM)
        bindAndDraw(prims?.boxClosed, texId, r, g, b, a)
        ms.pop()
    }

    /** A gable roof: ridge along x, base at y. */
    fun roofAt(
        x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, texId: Int,
        r: Float = 1f, g: Float = 1f, b: Float = 1f
    ) {
        ms.push().translate(x, y, z).scale(w, h, d)
        val slope = sqrt(h * h + (d * 0.5f) * (d * 0.5f))
        uv(w * texels, slope * texels)
        bindAndDraw(prims?.roof, texId, r, g, b)
        ms.pop()
    }

    /** A flat upright panel facing +z, origin at the bottom centre. */
    fun panel(
        x: Float, y: Float, z: Float, w: Float, h: Float, texId: Int,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f
    ) {
        ms.push().translate(x, y, z).scale(w, h, 1f)
        bindAndDraw(prims?.quad, texId, r, g, b, a)
        ms.pop()
    }

    /** An upright cylinder: post, trunk, barrel, stove pipe. */
    fun cyl(
        x: Float, y: Float, z: Float, r: Float, h: Float, texId: Int,
        tr: Float = 1f, tg: Float = 1f, tb: Float = 1f, uvU: Float = 1.4f
    ) {
        ms.push().translate(x, y, z).scale(r * 2f, h, r * 2f)
        uv(uvU, h * texels)
        bindAndDraw(prims?.cyl, texId, tr, tg, tb)
        ms.pop()
    }

    /** A cylinder lying on its side, running along x. */
    fun logX(
        x: Float, y: Float, z: Float, len: Float, r: Float, texId: Int,
        tr: Float = 1f, tg: Float = 1f, tb: Float = 1f
    ) {
        ms.push().translate(x, y, z).rotateZ(90f).scale(r * 2f, len, r * 2f).translate(0f, -0.5f, 0f)
        uv(1f, len * texels)
        bindAndDraw(prims?.cyl, texId, tr, tg, tb)
        ms.pop()
    }

    /** A cylinder lying on its side, running along z. */
    fun logZ(
        x: Float, y: Float, z: Float, len: Float, r: Float, texId: Int,
        tr: Float = 1f, tg: Float = 1f, tb: Float = 1f
    ) {
        ms.push().translate(x, y, z).rotateX(90f).scale(r * 2f, len, r * 2f).translate(0f, -0.5f, 0f)
        uv(1f, len * texels)
        bindAndDraw(prims?.cyl, texId, tr, tg, tb)
        ms.pop()
    }

    fun blob(
        x: Float, y: Float, z: Float, r: Float, texId: Int,
        tr: Float = 1f, tg: Float = 1f, tb: Float = 1f, squash: Float = 1f
    ) {
        ms.push().translate(x, y, z).scale(r * 2f, r * 2f * squash, r * 2f)
        bindAndDraw(prims?.blob, texId, tr, tg, tb)
        ms.pop()
    }

    fun cone(
        x: Float, y: Float, z: Float, r: Float, h: Float, texId: Int,
        tr: Float = 1f, tg: Float = 1f, tb: Float = 1f
    ) {
        ms.push().translate(x, y, z).scale(r * 2f, h, r * 2f)
        bindAndDraw(prims?.cone, texId, tr, tg, tb)
        ms.pop()
    }

    /** A flat disc lying on the ground. */
    fun disc(
        x: Float, y: Float, z: Float, r: Float, texId: Int,
        tr: Float = 1f, tg: Float = 1f, tb: Float = 1f, a: Float = 1f
    ) {
        ms.push().translate(x, y, z).scale(r * 2f, 1f, r * 2f)
        bindAndDraw(prims?.flat, texId, tr, tg, tb, a)
        ms.pop()
    }

    /** A camera-facing quad. Good for smoke, glow and every particle. */
    fun billboard(
        x: Float, y: Float, z: Float, size: Float, texId: Int,
        r: Float, g: Float, b: Float, a: Float
    ) {
        ms.identity().translate(x, y, z).rotateY(billboardYaw)
            .scale(size, size, size).translate(0f, -0.5f, 0f)
        bindAndDraw(prims?.quad, texId, r, g, b, a)
    }

    /**
     * Snow lying on a flat top. Slightly wider than what it sits on and only a
     * few centimetres thick, which is all it takes to read as a fresh fall.
     */
    fun snowOn(
        x: Float, y: Float, z: Float, sx: Float, sz: Float,
        thickness: Float = 0.09f, overhang: Float = 0.05f
    ) {
        val t = tex ?: return
        slab(x, y, z, sx + overhang * 2f, thickness, sz + overhang * 2f, t.snow, 0.8f, 1.02f, 1.05f, 1.12f)
    }

    /** A limb: a tapering four-sided stick aimed by a compass angle and a lean. */
    fun limb(
        x: Float, y: Float, z: Float, len: Float, thick: Float,
        yawDeg: Float, leanDeg: Float, texId: Int,
        tr: Float = 1f, tg: Float = 1f, tb: Float = 1f
    ) {
        ms.push().translate(x, y, z).rotateY(yawDeg).rotateX(leanDeg)
            .scale(thick, len, thick)
        uv(1f, len * texels)
        bindAndDraw(prims?.cyl, texId, tr, tg, tb)
        ms.pop()
    }
}
