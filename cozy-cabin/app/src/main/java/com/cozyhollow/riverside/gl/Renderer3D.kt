package com.cozyhollow.riverside.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.opengl.Matrix
import com.cozyhollow.riverside.Act
import com.cozyhollow.riverside.Catalog
import com.cozyhollow.riverside.FPhase
import com.cozyhollow.riverside.Game
import com.cozyhollow.riverside.MutableSkyKey
import com.cozyhollow.riverside.Tiers
import com.cozyhollow.riverside.U
import com.cozyhollow.riverside.Weather
import com.cozyhollow.riverside.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders the valley as real 3D geometry into a small offscreen buffer, then
 * blits that buffer to the display with nearest-neighbour filtering so every
 * texel lands as a hard pixel.
 */
class Renderer3D {

    // ---- programs ----
    private var worldProg = 0
    private var skyProg = 0
    private var blitProg = 0
    private var uiProg = 0

    private var aPos = 0; private var aNor = 0; private var aUv = 0
    private var uViewProj = 0; private var uModel = 0; private var uCamXLoc = 0
    private var uCurve = 0; private var uBaseY = 0; private var uUvScale = 0; private var uTimeLoc = 0; private var uWave = 0
    private var uSunDir = 0; private var uSunCol = 0; private var uAmbient = 0
    private var uFog = 0; private var uFogCol = 0; private var uColor = 0; private var uTex = 0

    private var skyAPos = 0
    private var skyTop = 0; private var skyMid = 0; private var skyHor = 0
    private var skyStars = 0; private var skySun = 0; private var skySunCol = 0
    private var skySunGlow = 0; private var skySunSize = 0; private var skyAspect = 0

    private var blitAPos = 0; private var blitTex = 0
    private var uiAPos = 0; private var uiTexLoc = 0

    private var fullQuad = 0

    // ---- resources ----
    private var tex: Textures? = null
    private var prims: Prims? = null
    private var rt: RenderTarget? = null

    private var grassMesh: Mesh? = null
    private var bankMesh: Mesh? = null
    private var bedMesh: Mesh? = null
    private var waterMesh: Mesh? = null
    private var barkMesh: Mesh? = null
    private var pineMesh: Mesh? = null
    private var oakMesh: Mesh? = null
    private var tuftMesh: Mesh? = null
    private var farShoreMesh: Mesh? = null
    private var forestShadowMesh: Mesh? = null
    private var pathMesh: Mesh? = null
    private var fenceMesh: Mesh? = null
    private var rockMesh: Mesh? = null
    private var bushMesh: Mesh? = null
    private var flowerMesh: Mesh? = null

    // ---- ui layer ----
    private var uiBitmap: Bitmap? = null
    private var uiCanvas: Canvas? = null
    private var uiTexId = 0

    var rtW = 480; private set
    var rtH = 270; private set
    private var screenW = 1; private var screenH = 1

    /** Where the water stops and the far bank begins. */
    private val FAR_SHORE_Z = -12.5f

    private val EMPTY_STATE = com.cozyhollow.riverside.GameState()

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val ms = MStack(24)

    var ready = false; private set

    // ================================================================ setup

    fun onSurfaceCreated() {
        ready = false
        worldProg = Gl.program(Shaders.WORLD_VS, Shaders.WORLD_FS)
        aPos = glGetAttribLocation(worldProg, "aPos")
        aNor = glGetAttribLocation(worldProg, "aNor")
        aUv = glGetAttribLocation(worldProg, "aUv")
        uViewProj = glGetUniformLocation(worldProg, "uViewProj")
        uModel = glGetUniformLocation(worldProg, "uModel")
        uCamXLoc = glGetUniformLocation(worldProg, "uCamX")
        uCurve = glGetUniformLocation(worldProg, "uCurve")
        uBaseY = glGetUniformLocation(worldProg, "uBaseY")
        uUvScale = glGetUniformLocation(worldProg, "uUvScale")
        uTimeLoc = glGetUniformLocation(worldProg, "uTime")
        uWave = glGetUniformLocation(worldProg, "uWave")
        uSunDir = glGetUniformLocation(worldProg, "uSunDir")
        uSunCol = glGetUniformLocation(worldProg, "uSunCol")
        uAmbient = glGetUniformLocation(worldProg, "uAmbient")
        uFog = glGetUniformLocation(worldProg, "uFog")
        uFogCol = glGetUniformLocation(worldProg, "uFogCol")
        uColor = glGetUniformLocation(worldProg, "uColor")
        uTex = glGetUniformLocation(worldProg, "uTex")

        skyProg = Gl.program(Shaders.SKY_VS, Shaders.SKY_FS)
        skyAPos = glGetAttribLocation(skyProg, "aPos")
        skyTop = glGetUniformLocation(skyProg, "uTop")
        skyMid = glGetUniformLocation(skyProg, "uMid")
        skyHor = glGetUniformLocation(skyProg, "uHorizon")
        skyStars = glGetUniformLocation(skyProg, "uStars")
        skySun = glGetUniformLocation(skyProg, "uSun")
        skySunCol = glGetUniformLocation(skyProg, "uSunCol")
        skySunGlow = glGetUniformLocation(skyProg, "uSunGlow")
        skySunSize = glGetUniformLocation(skyProg, "uSunSize")
        skyAspect = glGetUniformLocation(skyProg, "uAspect")

        blitProg = Gl.program(Shaders.BLIT_VS, Shaders.BLIT_FS)
        blitAPos = glGetAttribLocation(blitProg, "aPos")
        blitTex = glGetUniformLocation(blitProg, "uTex")

        uiProg = Gl.program(Shaders.BLIT_VS, Shaders.UI_FS)
        uiAPos = glGetAttribLocation(uiProg, "aPos")
        uiTexLoc = glGetUniformLocation(uiProg, "uTex")

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        fullQuad = ids[0]
        glBindBuffer(GL_ARRAY_BUFFER, fullQuad)
        glBufferData(GL_ARRAY_BUFFER, quad.size * 4, Gl.floatBuf(quad), GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)

        tex = Textures()
        prims = Prims()
        buildStatic()

        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glFrontFace(GL_CCW)
        ready = true
    }

    fun onSurfaceChanged(w: Int, h: Int) {
        screenW = w; screenH = h
        rtH = 270
        rtW = (270f * w / h).toInt().coerceIn(300, 760)
        if (rtW % 2 == 1) rtW++
        rt?.release()
        rt = RenderTarget(rtW, rtH)

        uiBitmap?.recycle()
        val bmp = Bitmap.createBitmap(rtW, rtH, Bitmap.Config.ARGB_8888)
        uiBitmap = bmp
        uiCanvas = Canvas(bmp)
        if (uiTexId != 0) glDeleteTextures(1, intArrayOf(uiTexId), 0)
        uiTexId = Gl.emptyTexture(rtW, rtH)

        Matrix.perspectiveM(proj, 0, 33f, w.toFloat() / h, 0.4f, 150f)
    }

    // ============================================================== statics

    private fun buildStatic() {
        val bankX = W3.BANK_X
        val bankEnd = W3.BANK_END

        var b = MeshBuilder()
        b.plane(-6f, bankX, -24f, 16f, 0f, 150, 8, 1f)
        grassMesh = b.build()

        // the sloped bank, sampled so the curve stays smooth
        b = MeshBuilder()
        run {
            val steps = 10
            for (i in 0 until steps) {
                val x0 = U.lerp(bankX, bankEnd, i.toFloat() / steps)
                val x1 = U.lerp(bankX, bankEnd, (i + 1f) / steps)
                val y0 = W3.groundHeight(x0)
                val y1 = W3.groundHeight(x1)
                val z0 = FAR_SHORE_Z; val z1 = 16f
                val nx = -(y1 - y0)
                val len = kotlin.math.sqrt(nx * nx + (x1 - x0) * (x1 - x0))
                b.quad(
                    x0, y0, z1, x1, y1, z1, x1, y1, z0, x0, y0, z0,
                    nx / len, (x1 - x0) / len, 0f, (x1 - x0), (z1 - z0)
                )
            }
        }
        bankMesh = b.build()

        b = MeshBuilder()
        b.plane(bankEnd, 88f, FAR_SHORE_Z, 16f, W3.BED_Y, 50, 5, 1f)
        bedMesh = b.build()

        b = MeshBuilder()
        b.plane(W3.RIVER_X - 1.4f, 88f, FAR_SHORE_Z, 16f, W3.WATER_Y, 60, 6, 0.34f)
        waterMesh = b.build()

        // land on the far side of the water, so the river reads as a river
        b = MeshBuilder()
        b.plane(bankX - 6f, 88f, -24f, FAR_SHORE_Z + 0.4f, 0f, 60, 4, 1f)
        farShoreMesh = b.build()

        buildForest()
        buildTufts()
        buildPath()
        buildFence()
        buildProps()
    }

    /** A worn dirt track running the length of the valley. */
    private fun buildPath() {
        val b = MeshBuilder()
        val steps = 90
        val x0 = 3f
        val x1 = W3.BANK_X - 0.6f
        var travelled = 0f
        for (i in 0 until steps) {
            val ax = U.lerp(x0, x1, i.toFloat() / steps)
            val bx = U.lerp(x0, x1, (i + 1f) / steps)
            val az = 3.6f + sin(ax * 0.16f) * 0.75f + sin(ax * 0.41f) * 0.3f
            val bz = 3.6f + sin(bx * 0.16f) * 0.75f + sin(bx * 0.41f) * 0.3f
            val aw = 0.78f + sin(ax * 0.9f) * 0.1f
            val bw = 0.78f + sin(bx * 0.9f) * 0.1f
            val seg = kotlin.math.sqrt((bx - ax) * (bx - ax) + (bz - az) * (bz - az))
            b.quad(
                ax, 0.014f, az + aw, bx, 0.014f, bz + bw,
                bx, 0.014f, bz - bw, ax, 0.014f, az - aw,
                0f, 1f, 0f,
                seg * 0.75f, aw * 2f * 0.75f, travelled * 0.75f, 0f
            )
            travelled += seg
        }
        pathMesh = b.build()
    }

    /** A low rail fence along the back and sides of the field. */
    private fun buildFence() {
        val b = MeshBuilder()
        val x0 = W3.x(World.plotX(0)) - 1.0f
        val x1 = W3.x(World.plotX(World.PLOT_COLS - 1)) + 1.0f
        val z0 = W3.z(World.plotZ(0)) - 1.0f
        val z1 = W3.z(World.plotZ(World.MAX_PLOTS - 1)) + 1.0f

        fun post(px: Float, pz: Float) {
            b.box(px, 0f, pz, 0.12f, 0.82f, 0.12f, 0.75f, top = true, bottom = false)
        }
        fun railX(ax: Float, bx: Float, pz: Float, y: Float) {
            b.box((ax + bx) / 2f, y, pz, bx - ax, 0.09f, 0.07f, 0.75f, top = true, bottom = true)
        }
        fun railZ(px: Float, az: Float, bz: Float, y: Float) {
            b.box(px, y, (az + bz) / 2f, 0.07f, 0.09f, bz - az, 0.75f, top = true, bottom = true)
        }

        var x = x0
        while (x <= x1 + 0.01f) { post(x, z0); x += 1.25f }
        railX(x0, x1, z0, 0.32f); railX(x0, x1, z0, 0.60f)

        var z = z0
        while (z <= z1 - 1.6f) { post(x0, z); post(x1, z); z += 1.25f }
        railZ(x0, z0, z1 - 1.6f, 0.32f); railZ(x0, z0, z1 - 1.6f, 0.60f)
        railZ(x1, z0, z1 - 1.6f, 0.32f); railZ(x1, z0, z1 - 1.6f, 0.60f)
        fenceMesh = b.build()
    }

    /** Rocks, bushes and wildflowers scattered over the walkable valley. */
    private fun buildProps() {
        val rocks = MeshBuilder()
        val bushes = MeshBuilder()
        val flowers = MeshBuilder()
        var seed = 3000
        var placed = 0
        while (placed < 420 && seed < 9000) {
            seed++
            val wx = 130f + U.hash(seed * 19) * 3180f
            val wz = World.Z_MIN + U.hash(seed * 37) * (World.Z_MAX - World.Z_MIN)
            val x = W3.x(wx)
            val z = W3.z(wz)
            // keep clear of the field, the buildings and the path
            if (World.blocked(EMPTY_STATE, wx, wz)) continue
            if (kotlin.math.abs(z - 3.6f) < 1.5f) continue
            if (wx > World.FARM_X0 - 200f && wx < World.plotX(World.PLOT_COLS - 1) + 200f &&
                wz > World.FARM_Z0 - 200f && wz < World.plotZ(World.MAX_PLOTS - 1) + 200f) continue
            placed++
            val roll = U.hash(seed * 53)
            when {
                roll < 0.18f -> {
                    val r = 0.16f + U.hash(seed * 7) * 0.22f
                    rocks.blob(x, r * 0.45f, z, r, 3, 6, 0.75f)
                }
                roll < 0.44f -> {
                    val r = 0.30f + U.hash(seed * 11) * 0.3f
                    bushes.blob(x, r * 0.72f, z, r, 4, 7, 0.75f)
                    if (U.hash(seed * 13) < 0.5f) {
                        bushes.blob(x + r * 0.7f, r * 0.5f, z + r * 0.3f, r * 0.6f, 3, 6, 0.75f)
                    }
                }
                else -> {
                    val q = (U.hash(seed * 23) * 4f).toInt().coerceIn(0, 3)
                    val u0 = (q % 2) * 0.5f
                    val v0 = (q / 2) * 0.5f
                    val w = 0.14f
                    val h = 0.28f + U.hash(seed * 29) * 0.1f
                    val ang = U.hash(seed * 31) * 3.1416f
                    val cx0 = cos(ang) * w; val cz0 = sin(ang) * w
                    val cx1 = cos(ang + 1.5708f) * w; val cz1 = sin(ang + 1.5708f) * w
                    flowers.quad(
                        x - cx0, 0f, z - cz0, x + cx0, 0f, z + cz0,
                        x + cx0, h, z + cz0, x - cx0, h, z - cz0,
                        -cz0, 0f, cx0, 0.5f, 0.5f, u0, v0
                    )
                    flowers.quad(
                        x - cx1, 0f, z - cz1, x + cx1, 0f, z + cz1,
                        x + cx1, h, z + cz1, x - cx1, h, z - cz1,
                        -cz1, 0f, cx1, 0.5f, 0.5f, u0, v0
                    )
                }
            }
            if (rocks.vertexCount > 26000 || bushes.vertexCount > 26000 || flowers.vertexCount > 26000) break
        }
        rockMesh = rocks.build()
        bushMesh = bushes.build()
        flowerMesh = flowers.build()
    }

    private fun buildForest() {
        val bark = MeshBuilder()
        val pine = MeshBuilder()
        val oak = MeshBuilder()
        val shade = MeshBuilder()
        val rows = floatArrayOf(-9.5f, -13.5f, -18f)
        var seed = 1
        for (r in rows.indices) {
            val z = rows[r]
            val spacing = 2.3f + r * 0.5f
            var x = -6f
            while (x < 84f) {
                seed++
                val jitter = (U.hash(seed * 31) - 0.5f) * spacing * 0.9f
                val px = x + jitter
                x += spacing
                // rows in front of the far shore must not stand in the river
                if (px > W3.BANK_X - 1f && z > FAR_SHORE_Z) continue
                val s = 0.75f + U.hash(seed * 17) * 0.6f + r * 0.12f
                val zz = z + (U.hash(seed * 7) - 0.5f) * 2.2f
                if (shade.vertexCount < 28000) {
                    val sr = 1.5f * s
                    shade.quad(
                        px - sr, 0.02f, zz + sr, px + sr, 0.02f, zz + sr,
                        px + sr, 0.02f, zz - sr, px - sr, 0.02f, zz - sr,
                        0f, 1f, 0f, 1f, 1f
                    )
                }
                if (U.hash(seed * 53) < 0.62f) {
                    bark.cylinder(px, 0f, zz, 0.14f * s, 0.11f * s, 1.1f * s, 6, 1f)
                    pine.cone(px, 0.75f * s, zz, 1.15f * s, 1.7f * s, 7, 0.7f)
                    pine.cone(px, 1.75f * s, zz, 0.92f * s, 1.5f * s, 7, 0.7f)
                    pine.cone(px, 2.7f * s, zz, 0.62f * s, 1.25f * s, 7, 0.7f)
                } else {
                    bark.cylinder(px, 0f, zz, 0.17f * s, 0.14f * s, 1.5f * s, 6, 1f)
                    oak.blob(px, 2.35f * s, zz, 1.15f * s, 4, 7, 0.6f)
                    oak.blob(px - 0.75f * s, 1.85f * s, zz + 0.3f * s, 0.72f * s, 3, 6, 0.6f)
                    oak.blob(px + 0.8f * s, 2.0f * s, zz - 0.25f * s, 0.66f * s, 3, 6, 0.6f)
                }
                if (bark.vertexCount > 28000 || pine.vertexCount > 28000 || oak.vertexCount > 28000) break
            }
        }
        barkMesh = bark.build()
        pineMesh = pine.build()
        oakMesh = oak.build()
        forestShadowMesh = shade.build()
    }

    /** Crossed quads of grass scattered over the walkable band. */
    private fun buildTufts() {
        val b = MeshBuilder()
        var seed = 500
        var i = 0
        while (i < 1400 && b.vertexCount < 28000) {
            seed++
            i++
            val x = U.hash(seed * 13) * (W3.BANK_X + 4f) - 3f
            val z = -6f + U.hash(seed * 29) * 11f
            if (x > W3.BANK_X - 0.4f) continue
            val h = 0.22f + U.hash(seed * 41) * 0.26f
            val w = 0.17f
            val ang = U.hash(seed * 61) * 3.1416f
            val cx0 = kotlin.math.cos(ang) * w
            val cz0 = kotlin.math.sin(ang) * w
            val cx1 = kotlin.math.cos(ang + 1.5708f) * w
            val cz1 = kotlin.math.sin(ang + 1.5708f) * w
            b.quad(
                x - cx0, 0f, z - cz0, x + cx0, 0f, z + cz0,
                x + cx0, h, z + cz0, x - cx0, h, z - cz0,
                -cz0, 0f, cx0, 1f, 1f
            )
            b.quad(
                x - cx1, 0f, z - cz1, x + cx1, 0f, z + cz1,
                x + cx1, h, z + cz1, x - cx1, h, z - cz1,
                -cz1, 0f, cx1, 1f, 1f
            )
        }
        tuftMesh = b.build()
    }

    // ================================================================ frame

    private val sunDir = FloatArray(3)

    fun drawFrame(g: Game) {
        val target = rt ?: return
        if (!ready) return

        val sky = g.sky
        val night = g.nightAmount()
        val camXm = W3.x(g.camX)

        target.bind()
        // clear depth while writes are still enabled - glDepthMask(false) makes
        // glClear(GL_DEPTH_BUFFER_BIT) a silent no-op and everything then fails
        // the depth test against an uninitialised buffer
        glDepthMask(true)
        glClear(GL_DEPTH_BUFFER_BIT)

        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDisable(GL_BLEND)
        drawSky(g, sky, night)

        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(true)

        val camZm = W3.z(g.camZ)
        val shake = g.screenShake
        val shx = if (shake > 0.01f) sin(g.timeMs * 0.06f) * shake * 0.10f else 0f
        val shy = if (shake > 0.01f) sin(g.timeMs * 0.045f) * shake * 0.07f else 0f
        Matrix.setLookAtM(
            view, 0,
            camXm + shx, 5.6f + shy, 18.2f + camZm,
            camXm + shx, 1.35f + shy, -2.0f + camZm,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        glUseProgram(worldProg)
        glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
        glUniform1f(uCamXLoc, camXm)
        glUniform1f(uCurve, W3.CURVE)
        glUniform1i(uTex, 0)
        glActiveTexture(GL_TEXTURE0)

        // lighting from the clock
        val day = 1f - night
        val elev = U.lerp(0.35f, 0.95f, day)
        val sunT = U.norm(g.st.timeMin % 1440f, 300f, 1170f)
        sunDir[0] = cos(sunT * 3.1416f) * 0.55f
        sunDir[1] = elev
        sunDir[2] = 0.45f
        val len = kotlin.math.sqrt(sunDir[0] * sunDir[0] + sunDir[1] * sunDir[1] + sunDir[2] * sunDir[2])
        glUniform3f(uSunDir, sunDir[0] / len, sunDir[1] / len, sunDir[2] / len)

        val sc = 0.42f + 0.68f * day
        glUniform3f(
            uSunCol,
            Color.red(sky.sunColor) / 255f * sc,
            Color.green(sky.sunColor) / 255f * sc,
            Color.blue(sky.sunColor) / 255f * sc
        )
        val ac = 0.26f + 0.24f * day
        glUniform3f(
            uAmbient,
            U.lerp(Color.red(sky.ambient) / 255f, 1f, 0.30f) * ac,
            U.lerp(Color.green(sky.ambient) / 255f, 1f, 0.30f) * ac,
            U.lerp(Color.blue(sky.ambient) / 255f, 1f, 0.30f) * ac
        )
        glUniform2f(uFog, 16f, 52f)
        glUniform1f(uTimeLoc, (g.timeMs * 0.001f) % 6283f)
        glUniform1f(uWave, 0f)
        glUniform3f(
            uFogCol,
            Color.red(sky.horizon) / 255f, Color.green(sky.horizon) / 255f, Color.blue(sky.horizon) / 255f
        )
        glDepthMask(true)

        drawClouds(g)
        drawTerrain(g)
        drawShadows(g)
        drawForest(g)
        drawProps(g)
        drawCabin(g, night)
        drawMarket(g, night)
        drawTrees(g)
        drawPlots(g)
        drawForage(g)
        drawPlayer(g)
        drawHint(g)
        if (g.fishing.active) drawBobber(g)

        drawParticles(g)

        drawUiLayer(g)

        // ---- present ----
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenW, screenH)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        glUseProgram(blitProg)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, target.color)
        glUniform1i(blitTex, 0)
        drawFullQuad(blitAPos)
    }

    private fun drawFullQuad(attr: Int) {
        glBindBuffer(GL_ARRAY_BUFFER, fullQuad)
        glEnableVertexAttribArray(attr)
        glVertexAttribPointer(attr, 2, GL_FLOAT, false, 8, 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(attr)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    private fun drawSky(g: Game, sky: MutableSkyKey, night: Float) {
        glUseProgram(skyProg)
        glUniform3f(skyTop, Color.red(sky.top) / 255f, Color.green(sky.top) / 255f, Color.blue(sky.top) / 255f)
        glUniform3f(skyMid, Color.red(sky.mid) / 255f, Color.green(sky.mid) / 255f, Color.blue(sky.mid) / 255f)
        glUniform3f(skyHor, Color.red(sky.horizon) / 255f, Color.green(sky.horizon) / 255f, Color.blue(sky.horizon) / 255f)
        glUniform1f(skyStars, sky.starAlpha)
        glUniform1f(skyAspect, rtW.toFloat() / rtH)

        val m = g.st.timeMin % 1440f
        val sunUp = m in 290f..1180f
        val t = if (sunUp) U.norm(m, 300f, 1170f) else U.norm(if (m > 1140f) m - 1140f else m + 300f, 0f, 600f)
        val sx = U.lerp(0.13f, 0.87f, t)
        val sy = 0.30f + sin(t * 3.1416f) * 0.52f
        glUniform2f(skySun, sx, sy)
        if (sunUp) {
            glUniform3f(skySunCol, Color.red(sky.sunColor) / 255f, Color.green(sky.sunColor) / 255f, Color.blue(sky.sunColor) / 255f)
            glUniform3f(skySunGlow, Color.red(sky.sunGlow) / 255f * 0.5f, Color.green(sky.sunGlow) / 255f * 0.5f, Color.blue(sky.sunGlow) / 255f * 0.5f)
            glUniform1f(skySunSize, 0.052f)
        } else {
            glUniform3f(skySunCol, 0.95f, 0.94f, 0.86f)
            glUniform3f(skySunGlow, 0.30f, 0.34f, 0.48f)
            glUniform1f(skySunSize, 0.038f)
        }
        drawFullQuad(skyAPos)
    }

    // ------------------------------------------------------------ drawing

    /** Texture repeats per metre. Keeps texels close to one screen pixel. */
    private val TEXELS = 0.75f
    private var uvX = 1f
    private var uvY = 1f

    /** Sets the UV scale for the next draw only. */
    private fun uv(a: Float, b: Float) {
        uvX = a; uvY = b
    }

    private fun bindAndDraw(mesh: Mesh?, texId: Int, tintR: Float = 1f, tintG: Float = 1f, tintB: Float = 1f, alpha: Float = 1f) {
        val m = mesh ?: return
        glBindTexture(GL_TEXTURE_2D, texId)
        glUniformMatrix4fv(uModel, 1, false, ms.m, 0)
        glUniform4f(uColor, tintR, tintG, tintB, alpha)
        glUniform2f(uUvScale, uvX, uvY)
        m.bind(aPos, aNor, aUv)
        m.draw()
        uvX = 1f; uvY = 1f
    }

    private fun setBase(y: Float) = glUniform1f(uBaseY, y)

    /** Box helper: centre x/z, base y, size. */
    private fun box(
        x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float,
        texId: Int, closed: Boolean = false, uvPerM: Float = TEXELS
    ) {
        ms.push().translate(x, y, z).scale(sx, sy, sz)
        uv(kotlin.math.max(sx, sz) * uvPerM, sy * uvPerM)
        bindAndDraw(if (closed) prims?.boxClosed else prims?.box, texId)
        ms.pop()
    }

    private fun drawTerrain(g: Game) {
        setBase(0f)
        ms.identity()
        bindAndDraw(grassMesh, tex!!.grass)
        bindAndDraw(farShoreMesh, tex!!.grass)
        bindAndDraw(bankMesh, tex!!.sand)
        uv(1f, 1f)
        bindAndDraw(pathMesh, tex!!.soil)
        if (g.settings.quality > 0) bindAndDraw(tuftMesh, tex!!.blade)
        setBase(W3.BED_Y)
        bindAndDraw(bedMesh, tex!!.sand, 0.8f, 0.78f, 0.7f)
        setBase(W3.WATER_Y)
        ms.identity().translate(0f, 0f, 0f)
        // scroll the water texture by shifting the mesh's UV through the model matrix is
        // not possible here, so nudge the whole sheet instead: it reads as flow
        val drift = (g.timeMs * 0.00006f) % 1f
        ms.identity().translate(0f, 0f, drift * 2f - 1f)
        glUniform1f(uWave, 0.045f)
        bindAndDraw(waterMesh, tex!!.water)
        glUniform1f(uWave, 0f)
        setBase(0f)
    }

    private fun shadowAt(x: Float, z: Float, r: Float, alpha: Float) {
        ms.identity().translate(x, 0.018f, z).scale(r * 2f, 1f, r * 2f)
        bindAndDraw(prims?.flat, tex!!.shadow, 1f, 1f, 1f, alpha)
    }

    /** Contact shadows: without them everything looks like it is hovering. */
    private fun drawShadows(g: Game) {
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        setBase(0f)
        ms.identity()
        bindAndDraw(forestShadowMesh, tex!!.shadow, 1f, 1f, 1f, 0.34f)
        val camXm = W3.x(g.camX)

        for (i in World.trees.indices) {
            val tr = World.trees[i]
            val x = W3.x(tr.x)
            if (abs(x - camXm) > 15f) continue
            val tz = W3.z(tr.z)
            if (World.treeStanding(g.st, i)) shadowAt(x, tz, 1.35f * tr.scale, 0.46f)
            else shadowAt(x, tz, 0.4f * tr.scale, 0.42f)
        }
        val lvl = g.st.cabinLevel
        if (abs(W3.CABIN_X - camXm) < 18f) {
            shadowAt(W3.CABIN_X, W3.CABIN_Z, 2.7f + lvl * 0.4f, 0.44f)
        }
        if (abs(W3.MARKET_X - camXm) < 18f) shadowAt(W3.MARKET_X, W3.MARKET_Z + 0.2f, 2.8f, 0.44f)

        for (i in World.forage.indices) {
            val f = World.forage[i]
            val x = W3.x(f.x)
            if (abs(x - camXm) > 13f) continue
            if (World.forageAvailable(g.st, i)) shadowAt(x, W3.z(f.z), 0.24f, 0.46f)
        }
        shadowAt(W3.x(g.player.x), W3.z(g.player.z), 0.40f, 0.58f)

        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun drawForest(g: Game) {
        setBase(0f)
        ms.identity()
        bindAndDraw(barkMesh, tex!!.bark)
        bindAndDraw(pineMesh, tex!!.pine)
        bindAndDraw(oakMesh, tex!!.oak)
    }

    /** Cloud billboards, drawn flat (no world curve) so they read as distant sky. */
    private fun drawClouds(g: Game) {
        if (g.settings.quality < 1) return
        val t = tex!!
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glUniform1f(uCurve, 0f)
        setBase(0f)
        val camXm = W3.x(g.camX)
        val n = if (g.settings.quality >= 2) 13 else 8
        val span = 96f
        for (i in 0 until n) {
            val speed = 0.55f + U.hash(i * 11 + 5) * 0.5f
            var off = (U.hash(i * 37 + 3) * span + g.timeMs * 0.00006f * speed * span) % span
            if (off < 0f) off += span
            val x = camXm + off - span * 0.5f
            val y = 13f + U.hash(i * 61 + 5) * 10f
            val z = -46f - U.hash(i * 29 + 9) * 20f
            val sc = 8f + U.hash(i * 53 + 7) * 10f
            val a = 0.5f + U.hash(i * 17 + 1) * 0.3f
            ms.identity().translate(x, y, z).scale(sc, sc * 0.5f, 1f).translate(0f, -0.5f, 0f)
            bindAndDraw(prims?.quad, t.cloud, 1f, 1f, 1f, a)
        }
        glUniform1f(uCurve, W3.CURVE)
        glDisable(GL_BLEND)
        glDepthMask(true)
        glEnable(GL_DEPTH_TEST)
    }

    private fun drawProps(g: Game) {
        val t = tex!!
        setBase(0f)
        ms.identity()
        bindAndDraw(fenceMesh, t.planks)
        bindAndDraw(rockMesh, t.stone)
        bindAndDraw(bushMesh, t.oak, 0.9f, 1f, 0.86f)
        if (g.settings.quality > 0) bindAndDraw(flowerMesh, t.flowers)
    }

    private fun drawTrees(g: Game) {
        val t = tex!!
        setBase(0f)
        ms.identity()
        val camXm = W3.x(g.camX)
        for (i in World.trees.indices) {
            val tr = World.trees[i]
            val x = W3.x(tr.x)
            if (abs(x - camXm) > 13f) continue
            val standing = World.treeStanding(g.st, i)
            val s = tr.scale
            val tz = W3.z(tr.z)
            if (!standing) {
                box(x, 0f, tz, 0.42f * s, 0.34f * s, 0.42f * s, t.bark, closed = false)
                ms.push().translate(x, 0.34f * s, tz).scale(0.44f * s, 0.02f, 0.44f * s)
                bindAndDraw(prims?.flat, t.oak, 0.75f, 0.6f, 0.45f)
                ms.pop()
                continue
            }
            val shake = if (i == g.shakeTreeIndex && g.shakeAmount > 0f)
                sin(g.shakeAmount * 46f) * 2.6f * U.clamp01(g.shakeAmount * 3f) else 0f
            val sway = sin(g.timeMs * 0.0009f + i) * 0.7f
            ms.push().translate(x, 0f, tz).rotateZ(shake + sway * 0.3f)
            if (tr.kind == 0) {
                ms.push().scale(0.30f * s, 1.5f * s, 0.30f * s)
                uv(1f, 1.5f * s * TEXELS)
                bindAndDraw(prims?.cyl, t.bark); ms.pop()
                for (k in 0 until 3) {
                    val cy = (1.0f + k * 1.15f) * s
                    val cr = (2.5f - k * 0.55f) * s
                    val ch = (2.1f - k * 0.25f) * s
                    ms.push().translate(0f, cy, 0f).scale(cr, ch, cr)
                    uv(cr * TEXELS * 1.6f, ch * TEXELS)
                    bindAndDraw(prims?.cone, t.pine); ms.pop()
                }
            } else {
                ms.push().scale(0.34f * s, 1.9f * s, 0.34f * s)
                uv(1f, 1.9f * s * TEXELS)
                bindAndDraw(prims?.cyl, t.bark); ms.pop()
                ms.push().translate(0f, 2.9f * s, 0f).scale(2.7f * s, 2.5f * s, 2.5f * s)
                uv(2.7f * s * TEXELS, 2.5f * s * TEXELS)
                bindAndDraw(prims?.blob, t.oak); ms.pop()
                ms.push().translate(-1.0f * s, 2.3f * s, 0.4f * s).scale(1.6f * s, 1.5f * s, 1.5f * s)
                bindAndDraw(prims?.blob, t.oak); ms.pop()
                ms.push().translate(1.05f * s, 2.45f * s, -0.35f * s).scale(1.5f * s, 1.4f * s, 1.4f * s)
                bindAndDraw(prims?.blob, t.oak); ms.pop()
            }
            ms.pop()
        }
    }

    // ------------------------------------------------------------- cabin

    private fun drawCabin(g: Game, night: Float) {
        val t = tex!!
        val x = W3.CABIN_X
        val z = W3.CABIN_Z
        val lit = if (night > 0.25f) t.windowLit else t.window
        val level = g.st.cabinLevel
        setBase(0f)
        ms.identity()
        when (level) {
            1 -> {
                box(x, 0f, z, 4.2f, 2.35f, 3.1f, t.logs)
                roofAt(x, 2.35f, z, 4.8f, 1.5f, 3.7f, t.shingleRed)
                doorAt(x + 1.2f, z + 1.56f, 1.0f, 1.7f, t.door)
                windowAt(x - 0.9f, 1.15f, z + 1.56f, 0.9f, 0.9f, lit)
                box(x + 1.5f, 2.2f, z - 0.6f, 0.5f, 1.5f, 0.5f, t.stone)
            }
            2 -> {
                box(x, 0f, z, 5.4f, 2.7f, 3.5f, t.logs)
                roofAt(x, 2.7f, z, 6.1f, 1.7f, 4.2f, t.shingleRed)
                doorAt(x + 1.6f, z + 1.76f, 1.05f, 1.85f, t.door)
                windowAt(x - 1.5f, 1.35f, z + 1.76f, 0.95f, 0.95f, lit)
                windowAt(x - 0.1f, 1.35f, z + 1.76f, 0.95f, 0.95f, lit)
                box(x + 1.9f, 2.5f, z - 0.7f, 0.55f, 1.6f, 0.55f, t.stone)
                porch(x + 3.6f, z + 0.6f, 2.1f, 2.4f, t)
            }
            3 -> {
                box(x, 0f, z, 6.2f, 0.45f, 4.0f, t.stone)
                box(x, 0.45f, z, 6.0f, 3.9f, 3.8f, t.planks)
                box(x, 2.1f, z, 6.1f, 0.18f, 3.9f, t.logs)
                roofAt(x, 4.35f, z, 6.9f, 2.0f, 4.6f, t.shingleRed)
                doorAt(x + 1.9f, z + 1.91f, 1.1f, 1.95f, t.door)
                windowAt(x - 1.8f, 0.95f, z + 1.91f, 1.0f, 1.0f, lit)
                windowAt(x - 0.3f, 0.95f, z + 1.91f, 1.0f, 1.0f, lit)
                windowAt(x - 1.8f, 2.75f, z + 1.91f, 1.0f, 1.0f, lit)
                windowAt(x - 0.3f, 2.75f, z + 1.91f, 1.0f, 1.0f, lit)
                flowerBox(x - 1.8f, 0.42f, z + 1.98f, t)
                box(x + 2.2f, 4.0f, z - 0.9f, 0.65f, 2.0f, 0.65f, t.stone)
                porch(x + 4.0f, z + 0.8f, 2.2f, 2.6f, t)
            }
            else -> {
                box(x, 0f, z, 7.6f, 0.5f, 4.4f, t.stone)
                box(x, 0.5f, z, 7.4f, 4.5f, 4.2f, t.planks)
                box(x, 2.4f, z, 7.5f, 0.2f, 4.3f, t.logs)
                roofAt(x, 5.0f, z, 8.4f, 2.3f, 5.0f, t.shinglePlum)
                // attic gable
                box(x - 0.4f, 5.0f, z + 0.9f, 2.0f, 1.2f, 1.6f, t.planks)
                roofAt(x - 0.4f, 6.2f, z + 0.9f, 2.4f, 0.9f, 2.0f, t.shinglePlum)
                windowAt(x - 0.4f, 5.35f, z + 1.72f, 0.85f, 0.85f, lit)
                doorAt(x + 2.3f, z + 2.11f, 1.15f, 2.05f, t.door)
                windowAt(x - 2.6f, 1.1f, z + 2.11f, 1.05f, 1.05f, lit)
                windowAt(x - 1.1f, 1.1f, z + 2.11f, 1.05f, 1.05f, lit)
                windowAt(x + 0.4f, 1.1f, z + 2.11f, 1.05f, 1.05f, lit)
                windowAt(x - 2.6f, 3.2f, z + 2.11f, 1.05f, 1.05f, lit)
                windowAt(x - 1.1f, 3.2f, z + 2.11f, 1.05f, 1.05f, lit)
                flowerBox(x - 2.6f, 0.47f, z + 2.18f, t)
                flowerBox(x - 1.1f, 0.47f, z + 2.18f, t)
                box(x + 2.7f, 4.6f, z - 1.0f, 0.8f, 2.2f, 0.8f, t.stone)
                porch(x + 4.7f, z + 0.9f, 2.6f, 3.0f, t)
                // weather vane
                box(x, 7.3f, z, 0.07f, 0.7f, 0.07f, t.metal)
                box(x + 0.25f, 7.75f, z, 0.5f, 0.16f, 0.05f, t.metal)
            }
        }
        chimneySmoke(g, x, level)
    }

    private fun roofAt(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, texId: Int) {
        ms.push().translate(x, y, z).scale(w, h, d)
        val slope = kotlin.math.sqrt(h * h + (d * 0.5f) * (d * 0.5f))
        uv(w * TEXELS, slope * TEXELS)
        bindAndDraw(prims?.roof, texId)
        ms.pop()
    }

    private fun doorAt(x: Float, z: Float, w: Float, h: Float, texId: Int) {
        ms.push().translate(x, 0f, z).scale(w, h, 1f)
        bindAndDraw(prims?.quad, texId)
        ms.pop()
    }

    private fun windowAt(x: Float, y: Float, z: Float, w: Float, h: Float, texId: Int) {
        ms.push().translate(x, y, z).scale(w, h, 1f)
        bindAndDraw(prims?.quad, texId)
        ms.pop()
    }

    private fun porch(x: Float, z: Float, w: Float, d: Float, t: Textures) {
        box(x, 0f, z, w, 0.22f, d, t.planks, closed = true)
        box(x - w / 2 + 0.12f, 0.22f, z + d / 2 - 0.12f, 0.16f, 1.9f, 0.16f, t.planks)
        box(x + w / 2 - 0.12f, 0.22f, z + d / 2 - 0.12f, 0.16f, 1.9f, 0.16f, t.planks)
        box(x, 2.12f, z, w + 0.2f, 0.18f, d, t.planks)
        box(x, 0.85f, z + d / 2 - 0.12f, w - 0.1f, 0.1f, 0.1f, t.planks)
    }

    private fun flowerBox(x: Float, y: Float, z: Float, t: Textures) {
        box(x, y, z, 0.95f, 0.22f, 0.22f, t.planks, closed = true)
        for (k in 0 until 4) {
            val fx = x - 0.33f + k * 0.22f
            box(fx, y + 0.22f, z, 0.13f, 0.12f, 0.13f, t.leafGreen)
            box(fx, y + 0.32f, z, 0.1f, 0.09f, 0.1f, t.solid(if (k % 2 == 0) Color.parseColor("#D06A72") else Color.parseColor("#E8B44A")))
        }
    }

    private fun chimneySmoke(g: Game, x: Float, level: Int) {
        val t = tex!!
        val topY = when (level) { 1 -> 3.7f; 2 -> 4.1f; 3 -> 6.0f; else -> 6.8f }
        val cx = when (level) { 1 -> x + 1.5f; 2 -> x + 1.9f; 3 -> x + 2.2f; else -> x + 2.7f }
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        for (i in 0 until 5) {
            val ph = ((g.timeMs * 0.00022f) + i * 0.2f) % 1f
            val a = (1f - ph) * 0.42f
            val s = 0.25f + ph * 0.8f
            ms.push().translate(cx + sin(ph * 5f + i) * 0.5f * ph, topY + ph * 2.6f, W3.CABIN_Z)
                .scale(s, s, s).translate(0f, -0.5f, 0f)
            bindAndDraw(prims?.quad, t.cloud, 1f, 1f, 1f, a)
            ms.pop()
        }
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    // ------------------------------------------------------------ market

    private fun drawMarket(g: Game, night: Float) {
        val t = tex!!
        val x = W3.MARKET_X
        val z = W3.MARKET_Z
        setBase(0f)
        ms.identity()
        // counter, low enough that the shopkeeper reads over it
        box(x, 0f, z + 0.7f, 4.6f, 0.82f, 0.7f, t.planks, closed = true)
        box(x, 0.82f, z + 0.7f, 4.9f, 0.12f, 0.95f, t.logs, closed = true)
        // posts
        box(x - 2.3f, 0f, z + 0.9f, 0.18f, 2.45f, 0.18f, t.planks)
        box(x + 2.3f, 0f, z + 0.9f, 0.18f, 2.45f, 0.18f, t.planks)
        box(x - 2.3f, 0f, z - 0.8f, 0.18f, 2.45f, 0.18f, t.planks)
        box(x + 2.3f, 0f, z - 0.8f, 0.18f, 2.45f, 0.18f, t.planks)
        // awning: a proper pitched canopy with a valance along the front
        roofAt(x, 2.45f, z + 0.05f, 4.9f, 1.0f, 2.3f, t.awning)
        box(x, 2.1f, z + 1.18f, 4.9f, 0.35f, 0.1f, t.awning, closed = true)
        // sign standing above the ridge on two little posts
        box(x - 0.7f, 3.45f, z, 0.1f, 0.3f, 0.1f, t.planks)
        box(x + 0.7f, 3.45f, z, 0.1f, 0.3f, 0.1f, t.planks)
        box(x, 3.72f, z, 2.1f, 0.62f, 0.14f, t.planks, closed = true)
        // crates of produce
        crateAt(x - 1.4f, 0.94f, z + 0.7f, Color.parseColor("#E08240"), t)
        crateAt(x - 0.15f, 0.94f, z + 0.7f, Color.parseColor("#D6564C"), t)
        crateAt(x + 1.1f, 0.94f, z + 0.7f, Color.parseColor("#6FA45A"), t)
        drawPip(g, x + 1.8f, z - 0.35f)
    }

    private fun crateAt(x: Float, y: Float, z: Float, produce: Int, t: Textures) {
        box(x, y, z, 0.62f, 0.5f, 0.55f, t.crate, closed = true, uvPerM = 1.7f)
        ms.push().translate(x - 0.14f, y + 0.5f, z).scale(0.24f, 0.24f, 0.24f)
        bindAndDraw(prims?.blob, t.solid(produce)); ms.pop()
        ms.push().translate(x + 0.15f, y + 0.5f, z + 0.05f).scale(0.21f, 0.21f, 0.21f)
        bindAndDraw(prims?.blob, t.solid(produce)); ms.pop()
        ms.push().translate(x, y + 0.66f, z - 0.05f).scale(0.2f, 0.2f, 0.2f)
        bindAndDraw(prims?.blob, t.solid(produce)); ms.pop()
    }

    /** Pip the shopkeeper: a small fox built from boxes, bobbing behind the counter. */
    private fun drawPip(g: Game, x: Float, z: Float) {
        val t = tex!!
        val bob = sin(g.timeMs * 0.0022f) * 0.04f
        ms.push().translate(x, bob, z).rotateY(-18f).scale(1.32f, 1.32f, 1.32f)
        box(0f, 0f, 0f, 0.5f, 0.55f, 0.4f, t.foxFur)
        box(0f, 0.12f, 0.21f, 0.32f, 0.4f, 0.06f, t.foxCream)
        box(0f, 0.55f, 0.02f, 0.52f, 0.46f, 0.46f, t.foxFur)
        box(0f, 0.62f, 0.24f, 0.3f, 0.26f, 0.06f, t.foxCream)
        // ears
        box(-0.17f, 1.01f, 0.02f, 0.16f, 0.22f, 0.1f, t.foxFur)
        box(0.17f, 1.01f, 0.02f, 0.16f, 0.22f, 0.1f, t.foxFur)
        // tail
        ms.push().translate(0f, 0.18f, -0.3f).rotateX(28f).scale(0.24f, 0.5f, 0.24f)
        bindAndDraw(prims?.box, t.foxFur); ms.pop()
        // face decal
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        ms.push().translate(0f, 0.6f, 0.27f).scale(0.46f, 0.46f, 1f)
        bindAndDraw(prims?.quad, t.foxFace); ms.pop()
        glDisable(GL_BLEND)
        ms.pop()
    }

    // ------------------------------------------------------------ player

    private fun drawPlayer(g: Game) {
        val t = tex!!
        val p = g.player
        val x = W3.x(p.x)
        val moving = p.moving
        val bob = if (moving) abs(sin(p.walkPhase)) * 0.045f else sin(p.idlePhase * 2.1f) * 0.018f
        val yaw = p.yaw
        val legSwing = if (moving) sin(p.walkPhase) * 32f else 0f
        val armSwing = when (p.action) {
            Act.SWING -> -70f + sin(U.clamp01(p.actionT / max(p.actionDur, 0.01f)) * 3.1416f) * 120f
            Act.WATER -> -55f
            Act.PICK -> -40f * sin(U.clamp01(p.actionT / max(p.actionDur, 0.01f)) * 3.1416f)
            Act.FISH -> -62f
            else -> if (moving) sin(p.walkPhase + 3.1416f) * 26f else 0f
        }

        setBase(0f)
        ms.identity()
        ms.push().translate(x, bob, W3.z(p.z)).rotateY(yaw)

        // legs, hung from the hip
        limb(-0.12f, 0.62f, 0f, 0.19f, 0.62f, 0.2f, legSwing, t.denim)
        limb(0.12f, 0.62f, 0f, 0.19f, 0.62f, 0.2f, -legSwing, t.denim)
        box(-0.12f, 0f, 0.03f, 0.22f, 0.12f, 0.28f, t.boot, closed = true)
        box(0.12f, 0f, 0.03f, 0.22f, 0.12f, 0.28f, t.boot, closed = true)

        // body
        box(0f, 0.6f, 0f, 0.44f, 0.56f, 0.3f, t.shirt)
        box(0f, 1.1f, 0f, 0.5f, 0.1f, 0.32f, t.scarf)

        // arms
        limb(-0.30f, 1.1f, 0f, 0.16f, 0.5f, 0.17f, armSwing, t.shirt)
        limb(0.30f, 1.1f, 0f, 0.16f, 0.5f, 0.17f, -armSwing * 0.35f, t.shirt)

        // head
        box(0f, 1.2f, 0f, 0.44f, 0.42f, 0.4f, t.skin)
        box(0f, 1.5f, 0f, 0.46f, 0.13f, 0.42f, t.hair)
        box(0f, 1.2f, -0.19f, 0.44f, 0.32f, 0.06f, t.hair)
        // straw hat
        box(0f, 1.62f, 0f, 0.78f, 0.06f, 0.74f, t.straw, closed = true)
        box(0f, 1.66f, 0f, 0.4f, 0.16f, 0.38f, t.straw, closed = true)

        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        ms.push().translate(0f, 1.24f, 0.21f).scale(0.38f, 0.38f, 1f)
        bindAndDraw(prims?.quad, t.face); ms.pop()
        glDisable(GL_BLEND)

        drawTool(g, armSwing, t)
        ms.pop()
    }

    /** A limb that swings about a pivot at its top. */
    private fun limb(px: Float, py: Float, pz: Float, sx: Float, sy: Float, sz: Float, deg: Float, texId: Int) {
        ms.push().translate(px, py, pz).rotateX(deg).scale(sx, sy, sz).translate(0f, -1f, 0f)
        bindAndDraw(prims?.box, texId)
        ms.pop()
    }

    private fun drawTool(g: Game, armSwing: Float, t: Textures) {
        val p = g.player
        if (p.action == Act.NONE || p.action == Act.CHEER) return
        ms.push().translate(-0.30f, 1.1f, 0f).rotateX(armSwing).translate(0f, -0.5f, 0f)
        when (p.action) {
            Act.SWING -> {
                box(0f, -0.05f, 0.05f, 0.06f, 0.85f, 0.06f, t.bark)
                ms.push().translate(0f, 0.72f, 0.05f).rotateZ(90f).scale(0.1f, 0.3f, 0.22f)
                bindAndDraw(prims?.box, t.metal); ms.pop()
            }
            Act.WATER -> {
                box(0f, -0.18f, 0.1f, 0.3f, 0.28f, 0.24f, t.metal, closed = true)
                box(0.22f, -0.06f, 0.1f, 0.22f, 0.06f, 0.06f, t.metal, closed = true)
            }
            Act.FISH -> {
                ms.push().rotateX(-42f)
                box(0f, -0.1f, 0f, 0.05f, 1.5f, 0.05f, t.bark)
                ms.pop()
            }
            Act.PICK -> {
                ms.push().translate(0f, -0.1f, 0.08f).scale(0.18f, 0.18f, 0.18f)
                bindAndDraw(prims?.blob, t.leafGreen); ms.pop()
            }
        }
        ms.pop()
    }

    // ------------------------------------------------------- plots & crops

    private fun drawPlots(g: Game) {
        val t = tex!!
        val camXm = W3.x(g.camX)
        val open = g.st.tier.plots
        setBase(0f)
        ms.identity()
        for (i in 0 until World.MAX_PLOTS) {
            val x = W3.x(World.plotX(i))
            if (abs(x - camXm) > 12f) continue
            if (i >= open) continue
            val plot = g.st.plots[i]
            if (!plot.tilled) continue
            val pz = W3.z(World.plotZ(i))
            box(x, 0f, pz, 1.2f, 0.14f, 1.05f, if (plot.watered) t.tilledWet else t.tilled, closed = true)
            val cropId = plot.cropId ?: continue
            val crop = Catalog.crops[cropId] ?: continue
            drawCrop(g, x, pz, crop.id, U.clamp01(plot.growth / crop.days), plot.ready)
        }
    }

    private fun drawCrop(g: Game, x: Float, zm: Float, cropId: String, prog: Float, ready: Boolean) {
        val t = tex!!
        val item = Catalog.item(cropId)
        val sway = sin(g.timeMs * 0.0016f + x * 1.7f) * 3.2f * (0.4f + prog)
        val h = U.lerp(0.30f, 1.05f, U.easeOut(prog))
        ms.push().translate(x, 0.14f, zm).rotateZ(sway)
        // stem
        box(0f, 0f, 0f, 0.07f, h, 0.07f, t.leafGreen)
        // leaves
        val leaves = if (prog < 0.3f) 2 else 4
        for (k in 0 until leaves) {
            val ly = h * (0.25f + k * 0.2f)
            val dir = if (k % 2 == 0) 1f else -1f
            ms.push().translate(0f, ly, 0f).rotateZ(dir * 42f).scale(0.42f, 0.075f, 0.24f).translate(dir * 0.5f, 0f, 0f)
            bindAndDraw(prims?.box, t.leafGreen)
            ms.pop()
        }
        if (ready) {
            val fruitTex = t.solid(item.a)
            when (cropId) {
                "carrot", "turnip" -> {
                    ms.push().translate(0f, 0.02f, 0f).scale(0.34f, 0.3f, 0.34f)
                    bindAndDraw(prims?.blob, fruitTex); ms.pop()
                }
                "corn" -> {
                    box(0.14f, h * 0.42f, 0f, 0.16f, 0.4f, 0.16f, fruitTex, closed = true)
                }
                "pumpkin" -> {
                    ms.push().translate(0.24f, 0.2f, 0.1f).scale(0.52f, 0.42f, 0.5f)
                    bindAndDraw(prims?.blob, fruitTex); ms.pop()
                }
                "berry" -> {
                    for (k in 0 until 4) {
                        ms.push().translate(-0.16f + k * 0.11f, h * (0.5f + (k % 2) * 0.18f), 0.06f)
                            .scale(0.14f, 0.14f, 0.14f)
                        bindAndDraw(prims?.blob, fruitTex); ms.pop()
                    }
                }
                else -> {
                    ms.push().translate(-0.14f, h * 0.6f, 0.05f).scale(0.24f, 0.24f, 0.24f)
                    bindAndDraw(prims?.blob, fruitTex); ms.pop()
                    ms.push().translate(0.15f, h * 0.78f, -0.03f).scale(0.21f, 0.21f, 0.21f)
                    bindAndDraw(prims?.blob, fruitTex); ms.pop()
                }
            }
        }
        ms.pop()
    }

    private fun drawForage(g: Game) {
        val t = tex!!
        val camXm = W3.x(g.camX)
        setBase(0f)
        ms.identity()
        for (i in World.forage.indices) {
            val f = World.forage[i]
            val x = W3.x(f.x)
            if (abs(x - camXm) > 12f) continue
            if (!World.forageAvailable(g.st, i)) continue
            val item = Catalog.item(f.itemId)
            val bob = sin(g.timeMs * 0.0022f + i * 1.4f) * 0.03f
            ms.push().translate(x, 0.02f + bob, W3.z(f.z)).rotateY(g.timeMs * 0.02f % 360f)
            when (f.itemId) {
                "mushroom" -> {
                    box(0f, 0f, 0f, 0.1f, 0.16f, 0.1f, t.solid(Color.parseColor("#F4EAD8")))
                    ms.push().translate(0f, 0.18f, 0f).scale(0.34f, 0.24f, 0.34f)
                    bindAndDraw(prims?.blob, t.solid(item.a)); ms.pop()
                }
                "flower" -> {
                    box(0f, 0f, 0f, 0.05f, 0.26f, 0.05f, t.leafGreen)
                    ms.push().translate(0f, 0.3f, 0f).scale(0.2f, 0.16f, 0.2f)
                    bindAndDraw(prims?.blob, t.solid(item.a)); ms.pop()
                }
                "honey" -> box(0f, 0f, 0f, 0.22f, 0.3f, 0.22f, t.solid(item.a), closed = true)
                else -> {
                    ms.push().translate(0f, 0.12f, 0f).scale(0.24f, 0.3f, 0.24f)
                    bindAndDraw(prims?.blob, t.solid(item.a)); ms.pop()
                }
            }
            ms.pop()
        }
    }

    /** A bobbing chevron floating over the thing the action button will act on. */
    private fun drawHint(g: Game) {
        val hx = g.hintTargetX()
        if (hx.isNaN()) return
        val bob = sin(g.timeMs * 0.006f) * 0.11f
        setBase(0f)
        ms.identity().translate(W3.x(hx), g.hintHeight() + 0.55f + bob, W3.z(g.hintTargetZ()))
            .rotateZ(180f).scale(0.32f, 0.34f, 0.32f)
        bindAndDraw(prims?.cone, tex!!.solid(Color.parseColor("#FFF3C0")))
    }

    private fun drawBobber(g: Game) {
        val t = tex!!
        val f = g.fishing
        if (f.phase == FPhase.IDLE) return
        val castT = if (f.phase == FPhase.CAST) U.easeOut(U.clamp01(f.t / 0.55f)) else 1f
        val fromX = W3.x(g.player.x)
        val toX = W3.x(f.bobX)
        val bx = U.lerp(fromX, toX, castT)
        val arc = sin(castT * 3.1416f) * 1.6f
        val by = U.lerp(1.6f, W3.WATER_Y + 0.08f, castT) + arc
        val pz = W3.z(g.player.z)
        val bz = U.lerp(pz, pz - 1.6f, castT)
        val dip = if (f.phase == FPhase.BITE) sin(g.timeMs * 0.03f) * 0.06f else sin(g.timeMs * 0.004f) * 0.02f
        setBase(0f)
        ms.identity()
        ms.push().translate(bx, by + dip, bz).scale(0.16f, 0.16f, 0.16f)
        bindAndDraw(prims?.blob, t.solid(Color.parseColor("#E4E0D2"))); ms.pop()
        ms.push().translate(bx, by + dip + 0.07f, bz).scale(0.15f, 0.15f, 0.15f)
        bindAndDraw(prims?.blob, t.solid(Color.parseColor("#D0707A"))); ms.pop()

        if (castT >= 1f) {
            glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDepthMask(false)
            for (k in 0 until 3) {
                val ph = ((g.timeMs * 0.0009f) + k * 0.33f) % 1f
                val s = 0.3f + ph * 1.1f
                ms.push().translate(bx, W3.WATER_Y + 0.03f, bz).rotateX(-90f).scale(s, s, s).translate(0f, -0.5f, 0f)
                bindAndDraw(prims?.quad, t.ring, 1f, 1f, 1f, (1f - ph) * 0.5f)
                ms.pop()
            }
            glDepthMask(true); glDisable(GL_BLEND)
        }
    }

    // --------------------------------------------------------- particles

    private fun drawParticles(g: Game) {
        val t = tex!!
        val p = g.particles
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        setBase(0f)
        val camXm = W3.x(g.camX)
        for (i in p.life.indices) {
            if (p.life[i] <= 0f) continue
            if (abs(p.px[i] - camXm) > 16f) continue
            val a = p.alphaOf(i)
            if (a <= 0.02f) continue
            val s = p.sizeOf(i)
            val c = p.col[i]
            val r = Color.red(c) / 255f; val gg = Color.green(c) / 255f; val bb = Color.blue(c) / 255f
            when (p.kind[i]) {
                P3.RING -> {
                    ms.identity().translate(p.px[i], p.py[i], p.pz[i]).rotateX(-90f)
                        .scale(s * 2f, s * 2f, s * 2f).translate(0f, -0.5f, 0f)
                    bindAndDraw(prims?.quad, t.ring, r, gg, bb, a)
                }
                P3.RAIN -> {
                    ms.identity().translate(p.px[i], p.py[i], p.pz[i]).scale(s * 0.5f, s * 7f, s * 0.5f)
                    bindAndDraw(prims?.quad, t.dot, r, gg, bb, a)
                }
                else -> {
                    ms.identity().translate(p.px[i], p.py[i], p.pz[i])
                        .scale(s * 2f, s * 2f, s * 2f).translate(0f, -0.5f, 0f)
                    bindAndDraw(prims?.quad, t.dot, r, gg, bb, a)
                }
            }
        }
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    // ------------------------------------------------------------- ui pass

    private fun drawUiLayer(g: Game) {
        val bmp = uiBitmap ?: return
        val c = uiCanvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val s = rtH / com.cozyhollow.riverside.Ui.DESIGN_H
        c.save()
        c.scale(s, s)
        g.drawUi(c)
        c.restore()

        glBindTexture(GL_TEXTURE_2D, uiTexId)
        GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bmp)

        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glUseProgram(uiProg)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, uiTexId)
        glUniform1i(uiTexLoc, 0)
        drawFullQuad(uiAPos)
        glDisable(GL_BLEND)
        glUseProgram(worldProg)
    }
}
