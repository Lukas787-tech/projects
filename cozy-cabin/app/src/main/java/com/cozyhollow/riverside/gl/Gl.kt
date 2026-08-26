package com.cozyhollow.riverside.gl

import android.opengl.GLES20
import android.opengl.GLES20.*
import com.cozyhollow.riverside.Terrain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Thin helpers over GL ES 2.0: shaders, buffers, textures, render targets. */
object Gl {

    fun floatBuf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }

    fun shortBuf(data: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
            .asShortBuffer().apply { put(data); position(0) }

    fun compile(type: Int, src: String): Int {
        val id = glCreateShader(type)
        glShaderSource(id, src)
        glCompileShader(id)
        val ok = IntArray(1)
        glGetShaderiv(id, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(id)
            glDeleteShader(id)
            throw RuntimeException("shader compile failed: $log\n$src")
        }
        return id
    }

    fun program(vs: String, fs: String): Int {
        val v = compile(GL_VERTEX_SHADER, vs)
        val f = compile(GL_FRAGMENT_SHADER, fs)
        val p = glCreateProgram()
        glAttachShader(p, v)
        glAttachShader(p, f)
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetProgramInfoLog(p)
            throw RuntimeException("program link failed: $log")
        }
        glDeleteShader(v)
        glDeleteShader(f)
        return p
    }

    private fun pixelBuffer(pixels: IntArray, w: Int, h: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            buf.put(((p shr 16) and 0xFF).toByte())   // R
            buf.put(((p shr 8) and 0xFF).toByte())    // G
            buf.put((p and 0xFF).toByte())            // B
            buf.put(((p ushr 24) and 0xFF).toByte())  // A
        }
        buf.position(0)
        return buf
    }

    /**
     * Uploads an ARGB pixel array.
     *
     * [smooth] picks linear filtering, which is the default here: the winter
     * look wants soft matte surfaces described by light rather than hard
     * texels. [mip] builds a mip chain as well, so ground stretching away to
     * the treeline stops boiling into noise.
     */
    fun texture(
        pixels: IntArray, w: Int, h: Int,
        repeat: Boolean = true, mip: Boolean = false, smooth: Boolean = true
    ): Int {
        val buf = pixelBuffer(pixels, w, h)
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        val wrap = if (repeat) GL_REPEAT else GL_CLAMP_TO_EDGE
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap)
        if (mip) {
            glGenerateMipmap(GL_TEXTURE_2D)
            glTexParameteri(
                GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                if (smooth) GL_LINEAR_MIPMAP_LINEAR else GL_NEAREST_MIPMAP_LINEAR
            )
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if (smooth) GL_LINEAR else GL_NEAREST)
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, if (smooth) GL_LINEAR else GL_NEAREST)
        glBindTexture(GL_TEXTURE_2D, 0)
        return ids[0]
    }

    fun emptyTexture(w: Int, h: Int, smooth: Boolean = false): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        val f = if (smooth) GL_LINEAR else GL_NEAREST
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, f)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, f)
        glBindTexture(GL_TEXTURE_2D, 0)
        return ids[0]
    }
}

/** Offscreen colour+depth target the whole world is rendered into. */
class RenderTarget(val w: Int, val h: Int, smooth: Boolean = false) {
    val fbo: Int
    val color: Int
    private val depth: Int

    init {
        color = Gl.emptyTexture(w, h, smooth)
        val rb = IntArray(1)
        glGenRenderbuffers(1, rb, 0)
        depth = rb[0]
        glBindRenderbuffer(GL_RENDERBUFFER, depth)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, w, h)

        val fb = IntArray(1)
        glGenFramebuffers(1, fb, 0)
        fbo = fb[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth)
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("framebuffer incomplete: $status")
        }
    }

    fun bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, w, h)
    }

    fun release() {
        glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        glDeleteTextures(1, intArrayOf(color), 0)
        glDeleteRenderbuffers(1, intArrayOf(depth), 0)
    }
}

/**
 * Interleaved position(3) / normal(3) / uv(2) / colour+sway(4) mesh in a VBO.
 *
 * The fourth channel of the colour is how much the wind moves this vertex: 0
 * for tree trunks and roof beams, up near 1 for the tip of a blade of grass.
 */
class Mesh(verts: FloatArray, indices: ShortArray) {
    private val vbo = IntArray(1)
    private val ibo = IntArray(1)
    val count = indices.size

    init {
        glGenBuffers(1, vbo, 0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo[0])
        glBufferData(GL_ARRAY_BUFFER, verts.size * 4, Gl.floatBuf(verts), GL_STATIC_DRAW)

        glGenBuffers(1, ibo, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, Gl.shortBuf(indices), GL_STATIC_DRAW)

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun bind(aPos: Int, aNor: Int, aUv: Int, aCol: Int) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo[0])
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        val stride = STRIDE * 4
        glEnableVertexAttribArray(aPos)
        glVertexAttribPointer(aPos, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(aNor)
        glVertexAttribPointer(aNor, 3, GL_FLOAT, false, stride, 3 * 4)
        glEnableVertexAttribArray(aUv)
        glVertexAttribPointer(aUv, 2, GL_FLOAT, false, stride, 6 * 4)
        if (aCol >= 0) {
            glEnableVertexAttribArray(aCol)
            glVertexAttribPointer(aCol, 4, GL_FLOAT, false, stride, 8 * 4)
        }
    }

    fun draw() {
        GLES20.glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_SHORT, 0)
    }

    fun release() {
        glDeleteBuffers(1, vbo, 0)
        glDeleteBuffers(1, ibo, 0)
    }

    companion object {
        const val STRIDE = 12
    }
}

/** Accumulates geometry, then bakes it into a [Mesh]. */
class MeshBuilder {
    private val v = ArrayList<Float>(8192)
    private val idx = ArrayList<Short>(8192)
    private var n = 0

    private var cr = 1f
    private var cg = 1f
    private var cb = 1f
    private var cs = 0f

    val isEmpty: Boolean get() = idx.isEmpty()

    /** Vertex count, so callers can split before crossing the 16-bit index limit. */
    val vertexCount: Int get() = n

    /** Tint and wind weight applied to every vertex from here on. */
    fun color(r: Float, g: Float, b: Float, sway: Float = 0f): MeshBuilder {
        cr = r; cg = g; cb = b; cs = sway
        return this
    }

    fun tint(argb: Int, sway: Float = 0f): MeshBuilder = color(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
        sway
    )

    /** Multiplies the current tint, for cheap per-object variation. */
    fun shade(f: Float): MeshBuilder = color(cr * f, cg * f, cb * f, cs)

    fun plain(): MeshBuilder = color(1f, 1f, 1f, 0f)

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, tv: Float) {
        v.add(x); v.add(y); v.add(z)
        v.add(nx); v.add(ny); v.add(nz)
        v.add(u); v.add(tv)
        v.add(cr); v.add(cg); v.add(cb); v.add(cs)
        n++
    }

    /** Quad given in counter-clockwise order, with a UV rect in texture tiles. */
    fun quad(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        nx: Float, ny: Float, nz: Float,
        uw: Float, vh: Float, u0: Float = 0f, v0: Float = 0f
    ) {
        val base = n
        vertex(x0, y0, z0, nx, ny, nz, u0, v0 + vh)
        vertex(x1, y1, z1, nx, ny, nz, u0 + uw, v0 + vh)
        vertex(x2, y2, z2, nx, ny, nz, u0 + uw, v0)
        vertex(x3, y3, z3, nx, ny, nz, u0, v0)
        tri(base, base + 1, base + 2)
        tri(base, base + 2, base + 3)
    }

    fun tri(a: Int, b: Int, c: Int) {
        idx.add(a.toShort()); idx.add(b.toShort()); idx.add(c.toShort())
    }

    /** Axis-aligned box. [tile] controls how many texture repeats per world unit. */
    fun box(
        cx: Float, cy: Float, cz: Float,
        sx: Float, sy: Float, sz: Float,
        tile: Float = 1f, top: Boolean = true, bottom: Boolean = false
    ) {
        val x0 = cx - sx / 2f; val x1 = cx + sx / 2f
        val y0 = cy; val y1 = cy + sy
        val z0 = cz - sz / 2f; val z1 = cz + sz / 2f
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f, sx * tile, sy * tile)
        quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f, sx * tile, sy * tile)
        quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1f, 0f, 0f, sz * tile, sy * tile)
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f, sz * tile, sy * tile)
        if (top) quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0f, 1f, 0f, sx * tile, sz * tile)
        if (bottom) quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0f, -1f, 0f, sx * tile, sz * tile)
    }

    /** Gable roof: a triangular prism running along X. */
    fun roof(
        cx: Float, baseY: Float, cz: Float,
        width: Float, height: Float, depth: Float, tile: Float = 1f
    ) {
        val x0 = cx - width / 2f; val x1 = cx + width / 2f
        val z0 = cz - depth / 2f; val z1 = cz + depth / 2f
        val apex = baseY + height
        val slopeLen = kotlin.math.sqrt((depth / 2f) * (depth / 2f) + height * height)
        quad(x0, baseY, z1, x1, baseY, z1, x1, apex, cz, x0, apex, cz,
            0f, height, depth / 2f, width * tile, slopeLen * tile)
        quad(x1, baseY, z0, x0, baseY, z0, x0, apex, cz, x1, apex, cz,
            0f, height, -depth / 2f, width * tile, slopeLen * tile)
        val b = n
        vertex(x1, baseY, z0, 1f, 0f, 0f, 0f, 0f)
        vertex(x1, baseY, z1, 1f, 0f, 0f, depth * tile, 0f)
        vertex(x1, apex, cz, 1f, 0f, 0f, depth * tile / 2f, height * tile)
        tri(b, b + 1, b + 2)
        val b2 = n
        vertex(x0, baseY, z1, -1f, 0f, 0f, 0f, 0f)
        vertex(x0, baseY, z0, -1f, 0f, 0f, depth * tile, 0f)
        vertex(x0, apex, cz, -1f, 0f, 0f, depth * tile / 2f, height * tile)
        tri(b2, b2 + 1, b2 + 2)
    }

    /** Vertical prism with [seg] sides — trunks, posts, barrels. */
    fun cylinder(
        cx: Float, baseY: Float, cz: Float,
        rBottom: Float, rTop: Float, height: Float, seg: Int, tile: Float = 1f, cap: Boolean = true
    ) {
        val step = (Math.PI * 2.0 / seg).toFloat()
        for (i in 0 until seg) {
            val a0 = i * step
            val a1 = (i + 1) * step
            val c0 = kotlin.math.cos(a0); val s0 = kotlin.math.sin(a0)
            val c1 = kotlin.math.cos(a1); val s1 = kotlin.math.sin(a1)
            val u0 = i.toFloat() / seg * rBottom * 6.28f * tile
            val u1 = (i + 1).toFloat() / seg * rBottom * 6.28f * tile
            val b = n
            vertex(cx + c0 * rBottom, baseY, cz + s0 * rBottom, c0, 0.25f, s0, u0, 0f)
            vertex(cx + c1 * rBottom, baseY, cz + s1 * rBottom, c1, 0.25f, s1, u1, 0f)
            vertex(cx + c1 * rTop, baseY + height, cz + s1 * rTop, c1, 0.25f, s1, u1, height * tile)
            vertex(cx + c0 * rTop, baseY + height, cz + s0 * rTop, c0, 0.25f, s0, u0, height * tile)
            tri(b, b + 1, b + 2); tri(b, b + 2, b + 3)
        }
        if (cap && rTop > 0.001f) {
            val cb = n
            vertex(cx, baseY + height, cz, 0f, 1f, 0f, 0.5f, 0.5f)
            for (i in 0..seg) {
                val a = i * step
                vertex(
                    cx + kotlin.math.cos(a) * rTop, baseY + height, cz + kotlin.math.sin(a) * rTop,
                    0f, 1f, 0f,
                    0.5f + kotlin.math.cos(a) * 0.5f, 0.5f + kotlin.math.sin(a) * 0.5f
                )
            }
            for (i in 0 until seg) tri(cb, cb + 1 + i, cb + 2 + i)
        }
    }

    fun cone(cx: Float, baseY: Float, cz: Float, r: Float, height: Float, seg: Int, tile: Float = 1f) {
        cylinder(cx, baseY, cz, r, 0.0001f, height, seg, tile, cap = false)
    }

    /** Low-poly blob used for leafy canopies. */
    fun blob(cx: Float, cy: Float, cz: Float, r: Float, rings: Int, seg: Int, tile: Float = 1f) {
        val start = n
        for (ri in 0..rings) {
            val phi = (Math.PI * ri / rings).toFloat()
            val y = kotlin.math.cos(phi) * r
            val rr = kotlin.math.sin(phi) * r
            for (si in 0..seg) {
                val th = (Math.PI * 2.0 * si / seg).toFloat()
                val x = kotlin.math.cos(th) * rr
                val z = kotlin.math.sin(th) * rr
                val len = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(0.0001f)
                vertex(
                    cx + x, cy + y, cz + z, x / len, y / len, z / len,
                    si.toFloat() / seg * r * 4f * tile, ri.toFloat() / rings * r * 2f * tile
                )
            }
        }
        val rowLen = seg + 1
        for (ri in 0 until rings) {
            for (si in 0 until seg) {
                val a = start + ri * rowLen + si
                val b = a + 1
                val c = a + rowLen
                val d = c + 1
                tri(a, c, b); tri(b, c, d)
            }
        }
    }

    /** Flat horizontal plane, subdivided. */
    fun plane(
        x0: Float, x1: Float, z0: Float, z1: Float, y: Float,
        divX: Int, divZ: Int, tile: Float = 1f
    ) {
        val start = n
        for (iz in 0..divZ) {
            val z = U2.lerp(z0, z1, iz.toFloat() / divZ)
            for (ix in 0..divX) {
                val x = U2.lerp(x0, x1, ix.toFloat() / divX)
                vertex(x, y, z, 0f, 1f, 0f, x * tile, z * tile)
            }
        }
        val rowLen = divX + 1
        for (iz in 0 until divZ) {
            for (ix in 0 until divX) {
                val a = start + iz * rowLen + ix
                val b = a + 1
                val c = a + rowLen
                val d = c + 1
                tri(a, c, b); tri(b, c, d)
            }
        }
    }

    /** A pair of crossed upright quads — grass tufts, flowers, reeds. */
    fun cross(x: Float, y: Float, z: Float, w: Float, h: Float, ang: Float, sway: Float, u0: Float = 0f, v0: Float = 0f, uw: Float = 1f, vh: Float = 1f) {
        val cx0 = kotlin.math.cos(ang) * w
        val cz0 = kotlin.math.sin(ang) * w
        val cx1 = kotlin.math.cos(ang + 1.5708f) * w
        val cz1 = kotlin.math.sin(ang + 1.5708f) * w
        val keepR = cr; val keepG = cg; val keepB = cb
        // roots pinned, tips loose, so the whole tuft bends instead of sliding
        color(keepR, keepG, keepB, 0f)
        val b0 = n
        vertex(x - cx0, y, z - cz0, -cz0, 0.4f, cx0, u0, v0 + vh)
        vertex(x + cx0, y, z + cz0, -cz0, 0.4f, cx0, u0 + uw, v0 + vh)
        color(keepR, keepG, keepB, sway)
        vertex(x + cx0, y + h, z + cz0, -cz0, 0.4f, cx0, u0 + uw, v0)
        vertex(x - cx0, y + h, z - cz0, -cz0, 0.4f, cx0, u0, v0)
        tri(b0, b0 + 1, b0 + 2); tri(b0, b0 + 2, b0 + 3)
        color(keepR, keepG, keepB, 0f)
        val b1 = n
        vertex(x - cx1, y, z - cz1, -cz1, 0.4f, cx1, u0, v0 + vh)
        vertex(x + cx1, y, z + cz1, -cz1, 0.4f, cx1, u0 + uw, v0 + vh)
        color(keepR, keepG, keepB, sway)
        vertex(x + cx1, y + h, z + cz1, -cz1, 0.4f, cx1, u0 + uw, v0)
        vertex(x - cx1, y + h, z - cz1, -cz1, 0.4f, cx1, u0, v0)
        tri(b1, b1 + 1, b1 + 2); tri(b1, b1 + 2, b1 + 3)
        color(keepR, keepG, keepB, sway)
    }

    fun build(): Mesh {
        val verts = FloatArray(v.size) { v[it] }
        val ind = ShortArray(idx.size) { idx[it] }
        return Mesh(verts, ind)
    }

    fun clear() {
        v.clear(); idx.clear(); n = 0
        plain()
    }
}

/**
 * A round contact shadow that follows the ground it falls on.
 *
 * A flat quad laid over rolling terrain buries most of itself and leaves a
 * black crescent sticking out of the hillside. This is a small grid instead,
 * with every vertex dropped onto the heightmap and lifted a finger's width,
 * rewritten in place each time it is drawn.
 */
class GroundDecal(private val cells: Int = 4) {
    private val n = cells + 1
    private val verts = FloatArray(n * n * Mesh.STRIDE)
    private val buf: FloatBuffer = ByteBuffer.allocateDirect(verts.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val vbo = IntArray(1)
    private val ibo = IntArray(1)
    private var count = 0

    init {
        val idx = ShortArray(cells * cells * 6)
        var k = 0
        for (j in 0 until cells) {
            for (i in 0 until cells) {
                val a = j * n + i
                val b = a + 1
                val c = a + n + 1
                val d = a + n
                idx[k++] = a.toShort(); idx[k++] = b.toShort(); idx[k++] = c.toShort()
                idx[k++] = a.toShort(); idx[k++] = c.toShort(); idx[k++] = d.toShort()
            }
        }
        count = idx.size

        glGenBuffers(1, vbo, 0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo[0])
        glBufferData(GL_ARRAY_BUFFER, verts.size * 4, null, GL_DYNAMIC_DRAW)
        glGenBuffers(1, ibo, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, Gl.shortBuf(idx), GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Lays the disc over the ground at [cx],[cz] and draws it. */
    fun draw(cx: Float, cz: Float, r: Float, lift: Float, aPos: Int, aNor: Int, aUv: Int, aCol: Int) {
        var p = 0
        for (j in 0 until n) {
            val tv = j / cells.toFloat()
            val z = cz + (0.5f - tv) * 2f * r
            for (i in 0 until n) {
                val tu = i / cells.toFloat()
                val x = cx + (tu - 0.5f) * 2f * r
                verts[p++] = x
                verts[p++] = Terrain.groundY(x, z) + lift
                verts[p++] = z
                verts[p++] = 0f; verts[p++] = 1f; verts[p++] = 0f
                verts[p++] = tu; verts[p++] = tv
                verts[p++] = 1f; verts[p++] = 1f; verts[p++] = 1f; verts[p++] = 0f
            }
        }
        buf.position(0); buf.put(verts); buf.position(0)

        glBindBuffer(GL_ARRAY_BUFFER, vbo[0])
        glBufferSubData(GL_ARRAY_BUFFER, 0, verts.size * 4, buf)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        val stride = Mesh.STRIDE * 4
        glEnableVertexAttribArray(aPos)
        glVertexAttribPointer(aPos, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(aNor)
        glVertexAttribPointer(aNor, 3, GL_FLOAT, false, stride, 3 * 4)
        glEnableVertexAttribArray(aUv)
        glVertexAttribPointer(aUv, 2, GL_FLOAT, false, stride, 6 * 4)
        if (aCol >= 0) {
            glEnableVertexAttribArray(aCol)
            glVertexAttribPointer(aCol, 4, GL_FLOAT, false, stride, 8 * 4)
        }
        GLES20.glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_SHORT, 0)
    }

    fun release() {
        glDeleteBuffers(1, vbo, 0)
        glDeleteBuffers(1, ibo, 0)
    }
}

internal object U2 {
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
