package com.cozyhollow.riverside.gl

import android.opengl.GLES20
import android.opengl.GLES20.*
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

    /** Uploads an ARGB pixel array as a crisp, tiling, unfiltered texture. */
    fun texture(pixels: IntArray, w: Int, h: Int, repeat: Boolean = true): Int {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            buf.put(((p shr 16) and 0xFF).toByte())   // R
            buf.put(((p shr 8) and 0xFF).toByte())    // G
            buf.put((p and 0xFF).toByte())            // B
            buf.put(((p ushr 24) and 0xFF).toByte())  // A
        }
        buf.position(0)
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        val wrap = if (repeat) GL_REPEAT else GL_CLAMP_TO_EDGE
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glBindTexture(GL_TEXTURE_2D, 0)
        return ids[0]
    }

    fun emptyTexture(w: Int, h: Int): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glBindTexture(GL_TEXTURE_2D, 0)
        return ids[0]
    }
}

/** Offscreen colour+depth target the whole world is rendered into at low res. */
class RenderTarget(val w: Int, val h: Int) {
    val fbo: Int
    val color: Int
    private val depth: Int

    init {
        color = Gl.emptyTexture(w, h)
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
 * Interleaved position(3) / normal(3) / uv(2) mesh in a static VBO.
 */
class Mesh(verts: FloatArray, indices: ShortArray) {
    private val vbo = IntArray(1)
    private val ibo = IntArray(1)
    val count = indices.size

    init {
        glGenBuffers(1, vbo, 0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo[0])
        val vb = Gl.floatBuf(verts)
        glBufferData(GL_ARRAY_BUFFER, verts.size * 4, vb, GL_STATIC_DRAW)

        glGenBuffers(1, ibo, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        val ib = Gl.shortBuf(indices)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, ib, GL_STATIC_DRAW)

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun bind(aPos: Int, aNor: Int, aUv: Int) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo[0])
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        val stride = 8 * 4
        glEnableVertexAttribArray(aPos)
        glVertexAttribPointer(aPos, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(aNor)
        glVertexAttribPointer(aNor, 3, GL_FLOAT, false, stride, 3 * 4)
        glEnableVertexAttribArray(aUv)
        glVertexAttribPointer(aUv, 2, GL_FLOAT, false, stride, 6 * 4)
    }

    fun draw() {
        GLES20.glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_SHORT, 0)
    }

    fun release() {
        glDeleteBuffers(1, vbo, 0)
        glDeleteBuffers(1, ibo, 0)
    }
}

/** Accumulates geometry, then bakes it into a [Mesh]. */
class MeshBuilder {
    private val v = ArrayList<Float>(4096)
    private val idx = ArrayList<Short>(4096)
    private var n = 0

    val isEmpty: Boolean get() = idx.isEmpty()

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, tv: Float) {
        v.add(x); v.add(y); v.add(z)
        v.add(nx); v.add(ny); v.add(nz)
        v.add(u); v.add(tv)
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
        // front (+z)
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f, sx * tile, sy * tile)
        // back (-z)
        quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f, sx * tile, sy * tile)
        // right (+x)
        quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1f, 0f, 0f, sz * tile, sy * tile)
        // left (-x)
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
        // two sloped faces
        val slopeLen = kotlin.math.sqrt((depth / 2f) * (depth / 2f) + height * height)
        quad(x0, baseY, z1, x1, baseY, z1, x1, apex, cz, x0, apex, cz,
            0f, height, depth / 2f, width * tile, slopeLen * tile)
        quad(x1, baseY, z0, x0, baseY, z0, x0, apex, cz, x1, apex, cz,
            0f, height, -depth / 2f, width * tile, slopeLen * tile)
        // gable ends
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

    /** Subdivided horizontal plane, so world curvature bends it smoothly. */
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

    fun build(): Mesh {
        val verts = FloatArray(v.size) { v[it] }
        val ind = ShortArray(idx.size) { idx[it] }
        return Mesh(verts, ind)
    }

    fun clear() {
        v.clear(); idx.clear(); n = 0
    }

    /** Vertex count, so callers can split before crossing the 16-bit index limit. */
    val vertexCount: Int get() = n
}

internal object U2 {
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
