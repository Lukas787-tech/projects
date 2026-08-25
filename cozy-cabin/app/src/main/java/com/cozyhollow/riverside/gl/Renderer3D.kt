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
import com.cozyhollow.riverside.Terrain
import com.cozyhollow.riverside.U
import com.cozyhollow.riverside.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the hollow.
 *
 * The world is real 3D geometry on a real heightmap, filmed by a camera that
 * orbits behind the farmer. It renders into an offscreen buffer, which is then
 * graded and vignetted on the way to the screen, and the interface is composited
 * on top at its own resolution so the text stays crisp.
 */
class Renderer3D {

    // ---- programs ----
    private var worldProg = 0
    private var waterProg = 0
    private var skyProg = 0
    private var blitProg = 0
    private var uiProg = 0

    private var aPos = 0; private var aNor = 0; private var aUv = 0; private var aCol = 0
    private var uProj = 0; private var uView = 0; private var uModel = 0
    private var uCurve = 0; private var uTimeLoc = 0; private var uWind = 0; private var uUvScale = 0
    private var uSunDir = 0; private var uSunCol = 0; private var uSkyFill = 0; private var uGroundFill = 0
    private var uFog = 0; private var uFogCol = 0; private var uColor = 0; private var uTex = 0
    private var uEmissive = 0; private var uCut = 0

    private var wAPos = 0; private var wANor = 0; private var wAUv = 0; private var wACol = 0
    private var wProj = 0; private var wView = 0; private var wCurve = 0; private var wTime = 0
    private var wCamPos = 0; private var wFog = 0; private var wFogCol = 0
    private var wShallow = 0; private var wDeep = 0; private var wSky = 0; private var wSun = 0
    private var wSunDir = 0; private var wTex = 0

    private var skyAPos = 0
    private var skyTop = 0; private var skyMid = 0; private var skyHor = 0
    private var skyStars = 0; private var skySun = 0; private var skySunCol = 0
    private var skySunGlow = 0; private var skySunSize = 0; private var skyAspect = 0
    private var skyTime = 0; private var skyHaze = 0

    private var blitAPos = 0; private var blitTex = 0; private var blitGrade = 0; private var blitVig = 0
    private var uiAPos = 0; private var uiTexLoc = 0

    private var fullQuad = 0

    // ---- resources ----
    private var tex: Textures? = null
    private var prims: Prims? = null
    private var scene: Scenery? = null
    private var rt: RenderTarget? = null

    private var fenceMesh: Mesh? = null
    private var deckMesh: Mesh? = null
    private var railMesh: Mesh? = null

    // ---- ui layer ----
    private var uiBitmap: Bitmap? = null
    private var uiCanvas: Canvas? = null
    private var uiTexId = 0
    private var uiW = 1; private var uiH = 1

    var rtW = 640; private set
    var rtH = 360; private set
    private var screenW = 1; private var screenH = 1
    private var quality = 1

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val ms = MStack(24)
    private var diagged = false
    private val eye = FloatArray(3)
    private val fwd = FloatArray(3)
    private val sunDir = FloatArray(3)
    private val castTmp = FloatArray(2)

    /** How far scenery is drawn, and where the fog swallows it. */
    private val drawDist: Float get() = when (quality) { 0 -> 34f; 1 -> 44f; else -> 52f }

    var ready = false; private set

    // ================================================================ setup

    fun onSurfaceCreated() {
        ready = false
        worldProg = Gl.program(Shaders.WORLD_VS, Shaders.WORLD_FS)
        aPos = glGetAttribLocation(worldProg, "aPos")
        aNor = glGetAttribLocation(worldProg, "aNor")
        aUv = glGetAttribLocation(worldProg, "aUv")
        aCol = glGetAttribLocation(worldProg, "aCol")
        uProj = glGetUniformLocation(worldProg, "uProj")
        uView = glGetUniformLocation(worldProg, "uView")
        uModel = glGetUniformLocation(worldProg, "uModel")
        uCurve = glGetUniformLocation(worldProg, "uCurve")
        uTimeLoc = glGetUniformLocation(worldProg, "uTime")
        uWind = glGetUniformLocation(worldProg, "uWind")
        uUvScale = glGetUniformLocation(worldProg, "uUvScale")
        uSunDir = glGetUniformLocation(worldProg, "uSunDir")
        uSunCol = glGetUniformLocation(worldProg, "uSunCol")
        uSkyFill = glGetUniformLocation(worldProg, "uSkyFill")
        uGroundFill = glGetUniformLocation(worldProg, "uGroundFill")
        uFog = glGetUniformLocation(worldProg, "uFog")
        uFogCol = glGetUniformLocation(worldProg, "uFogCol")
        uColor = glGetUniformLocation(worldProg, "uColor")
        uTex = glGetUniformLocation(worldProg, "uTex")
        uEmissive = glGetUniformLocation(worldProg, "uEmissive")
        uCut = glGetUniformLocation(worldProg, "uCut")

        waterProg = Gl.program(Shaders.WATER_VS, Shaders.WATER_FS)
        wAPos = glGetAttribLocation(waterProg, "aPos")
        wANor = glGetAttribLocation(waterProg, "aNor")
        wAUv = glGetAttribLocation(waterProg, "aUv")
        wACol = glGetAttribLocation(waterProg, "aCol")
        wProj = glGetUniformLocation(waterProg, "uProj")
        wView = glGetUniformLocation(waterProg, "uView")
        wCurve = glGetUniformLocation(waterProg, "uCurve")
        wTime = glGetUniformLocation(waterProg, "uTime")
        wCamPos = glGetUniformLocation(waterProg, "uCamPos")
        wFog = glGetUniformLocation(waterProg, "uFog")
        wFogCol = glGetUniformLocation(waterProg, "uFogCol")
        wShallow = glGetUniformLocation(waterProg, "uShallow")
        wDeep = glGetUniformLocation(waterProg, "uDeep")
        wSky = glGetUniformLocation(waterProg, "uSkyCol")
        wSun = glGetUniformLocation(waterProg, "uSunCol")
        wSunDir = glGetUniformLocation(waterProg, "uSunDir")
        wTex = glGetUniformLocation(waterProg, "uTex")

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
        skyTime = glGetUniformLocation(skyProg, "uTime")
        skyHaze = glGetUniformLocation(skyProg, "uHaze")

        blitProg = Gl.program(Shaders.BLIT_VS, Shaders.BLIT_FS)
        blitAPos = glGetAttribLocation(blitProg, "aPos")
        blitTex = glGetUniformLocation(blitProg, "uTex")
        blitGrade = glGetUniformLocation(blitProg, "uGrade")
        blitVig = glGetUniformLocation(blitProg, "uVignette")

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
        scene = Scenery().also { it.build() }
        buildStructures()

        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glFrontFace(GL_CCW)
        ready = true
    }

    fun onSurfaceChanged(w: Int, h: Int, qualityLevel: Int) {
        screenW = w; screenH = h
        quality = qualityLevel.coerceIn(0, 2)
        val target = when (quality) { 0 -> 360; 1 -> 540; else -> 720 }
        rtH = min(h, target)
        rtW = (rtH.toFloat() * w / h).toInt().coerceAtLeast(2)
        if (rtW % 2 == 1) rtW++
        rt?.release()
        rt = RenderTarget(rtW, rtH, smooth = true)

        uiH = min(h, 540)
        uiW = (uiH.toFloat() * w / h).toInt().coerceAtLeast(2)
        uiBitmap?.recycle()
        val bmp = Bitmap.createBitmap(uiW, uiH, Bitmap.Config.ARGB_8888)
        uiBitmap = bmp
        uiCanvas = Canvas(bmp)
        if (uiTexId != 0) glDeleteTextures(1, intArrayOf(uiTexId), 0)
        uiTexId = Gl.emptyTexture(uiW, uiH, smooth = true)

        Matrix.perspectiveM(proj, 0, 44f, w.toFloat() / h, 0.35f, 160f)
    }

    /** Rebuilds the offscreen buffers when the graphics setting changes. */
    fun onQualityChanged(qualityLevel: Int) {
        if (screenW > 1 && screenH > 1 && qualityLevel != quality) {
            onSurfaceChanged(screenW, screenH, qualityLevel)
        }
    }

    // ============================================================ structures

    private fun buildStructures() {
        // ---- the field fence ----
        var b = MeshBuilder()
        run {
            val x0 = World.FIELD_MIN_X
            val x1 = World.FIELD_MAX_X
            val z0 = World.FIELD_MIN_Z
            val z1 = World.FIELD_MAX_Z
            fun post(px: Float, pz: Float) {
                b.tint(0xFFFFFF, 0f)
                b.box(px, Terrain.height(px, pz) - 0.1f, pz, 0.14f, 1.0f, 0.14f, 0.9f)
            }
            fun rail(ax: Float, az: Float, bx: Float, bz: Float, y: Float) {
                val mx = (ax + bx) * 0.5f
                val mz = (az + bz) * 0.5f
                val len = sqrt((bx - ax) * (bx - ax) + (bz - az) * (bz - az))
                val my = (Terrain.height(ax, az) + Terrain.height(bx, bz)) * 0.5f + y
                if (abs(bx - ax) > abs(bz - az)) b.box(mx, my, mz, len, 0.08f, 0.08f, 0.9f, top = true, bottom = true)
                else b.box(mx, my, mz, 0.08f, 0.08f, len, 0.9f, top = true, bottom = true)
            }
            var x = x0
            while (x <= x1 + 0.01f) { post(x, z0); post(x, z1); x += 1.4f }
            var z = z0
            while (z <= z1 + 0.01f) { post(x0, z); post(x1, z); z += 1.4f }
            for (y in floatArrayOf(0.34f, 0.68f)) {
                rail(x0, z0, x1, z0, y); rail(x0, z1, x1, z1, y)
                rail(x0, z0, x0, z1, y); rail(x1, z0, x1, z1, y)
            }
        }
        fenceMesh = b.build()

        // ---- the footbridge and the pond jetty ----
        b = MeshBuilder()
        val rail = MeshBuilder()
        run {
            val cx = Terrain.riverX(Terrain.BRIDGE_Z)
            val span = Terrain.BRIDGE_SPAN
            val steps = 14
            b.tint(0xFFFFFF, 0f)
            for (i in 0 until steps) {
                val ax = cx - span + (i * 2f * span / steps)
                val bx = cx - span + ((i + 1) * 2f * span / steps)
                val ay = Terrain.bridgeY(ax)
                val by = Terrain.bridgeY(bx)
                val z0 = Terrain.BRIDGE_Z - Terrain.BRIDGE_HALF_Z
                val z1 = Terrain.BRIDGE_Z + Terrain.BRIDGE_HALF_Z
                b.quad(
                    ax, ay, z1, bx, by, z1, bx, by, z0, ax, ay, z0,
                    0f, 1f, 0f, (bx - ax) * 0.9f, (z1 - z0) * 0.9f, i * 0.35f, 0f
                )
                // a lip along each side so the deck reads as planks on beams
                b.box((ax + bx) * 0.5f, ay - 0.16f, z0 + 0.06f, bx - ax, 0.16f, 0.12f, 0.9f)
                b.box((ax + bx) * 0.5f, ay - 0.16f, z1 - 0.06f, bx - ax, 0.16f, 0.12f, 0.9f)
            }
            rail.tint(0xFFFFFF, 0f)
            var i = 0
            while (i <= steps) {
                val px = cx - span + (i * 2f * span / steps)
                val py = Terrain.bridgeY(px)
                rail.box(px, py, Terrain.BRIDGE_Z - Terrain.BRIDGE_HALF_Z + 0.1f, 0.1f, 0.8f, 0.1f, 0.9f)
                rail.box(px, py, Terrain.BRIDGE_Z + Terrain.BRIDGE_HALF_Z - 0.1f, 0.1f, 0.8f, 0.1f, 0.9f)
                i += 3
            }
            for (side in intArrayOf(-1, 1)) {
                val pz = Terrain.BRIDGE_Z + side * (Terrain.BRIDGE_HALF_Z - 0.1f)
                var k = 0
                while (k < steps) {
                    val ax = cx - span + (k * 2f * span / steps)
                    val bx = cx - span + ((k + 1) * 2f * span / steps)
                    val my = (Terrain.bridgeY(ax) + Terrain.bridgeY(bx)) * 0.5f + 0.72f
                    rail.box((ax + bx) * 0.5f, my, pz, bx - ax, 0.08f, 0.08f, 0.9f, top = true, bottom = true)
                    k++
                }
            }

            // the jetty out over the pond
            val jx = World.POND_X + 4.4f
            val jz = World.POND_Z + 1.2f
            b.tint(0xFFFFFF, 0f)
            for (k in 0 until 6) {
                val px = jx - k * 0.95f
                b.box(px, Terrain.WATER_Y + 0.22f, jz, 0.95f, 0.1f, 1.7f, 0.9f, top = true, bottom = true)
                b.box(px, Terrain.BED_Y, jz - 0.7f, 0.12f, Terrain.WATER_Y - Terrain.BED_Y + 0.24f, 0.12f, 0.9f)
                b.box(px, Terrain.BED_Y, jz + 0.7f, 0.12f, Terrain.WATER_Y - Terrain.BED_Y + 0.24f, 0.12f, 0.9f)
            }
        }
        deckMesh = b.build()
        railMesh = rail.build()
    }

    // ================================================================ camera

    private fun computeCamera(g: Game) {
        val yaw = Math.toRadians(g.camYaw.toDouble())
        val sy = sin(yaw).toFloat()
        val cy = cos(yaw).toFloat()
        val shake = g.screenShake
        val shx = if (shake > 0.01f) sin(g.timeMs * 0.05f) * shake * 0.09f else 0f
        val shy = if (shake > 0.01f) sin(g.timeMs * 0.037f) * shake * 0.06f else 0f

        val tx = g.camTX + shx
        val ty = g.camTY
        val tz = g.camTZ
        var ex = tx + sy * g.camDist
        var ez = tz + cy * g.camDist
        var ey = ty + g.camHeight + shy
        // never let the camera sink into a hillside
        val floorY = Terrain.height(ex, ez) + 1.5f
        if (ey < floorY) ey = floorY
        eye[0] = ex; eye[1] = ey; eye[2] = ez

        Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty + 1.15f, tz, 0f, 1f, 0f)
        fwd[0] = tx - ex; fwd[1] = (ty + 1.15f) - ey; fwd[2] = tz - ez
        val len = sqrt(fwd[0] * fwd[0] + fwd[1] * fwd[1] + fwd[2] * fwd[2]).coerceAtLeast(1e-4f)
        fwd[0] /= len; fwd[1] /= len; fwd[2] /= len
    }

    /** Rough frustum test: is a sphere at (x,z) worth drawing? */
    private fun visible(x: Float, z: Float, radius: Float, maxDist: Float): Boolean {
        val dx = x - eye[0]
        val dz = z - eye[2]
        val d = sqrt(dx * dx + dz * dz)
        if (d > maxDist + radius) return false
        if (d < radius + 3f) return true
        val nx = dx / d
        val nz = dz / d
        val facing = nx * fwd[0] + nz * fwd[2]
        return facing > 0.35f - radius / d
    }

    // ================================================================= frame

    fun drawFrame(g: Game) {
        val target = rt ?: return
        if (!ready) return

        val sky = g.sky
        val night = g.nightAmount()
        val day = 1f - night
        computeCamera(g)
        // billboards face the camera, which only ever turns about y
        billboardYaw = g.camYaw

        target.bind()
        glDepthMask(true)
        glClear(GL_DEPTH_BUFFER_BIT)

        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDisable(GL_BLEND)
        drawSky(g, sky, night)

        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(true)

        // ---- world program: one set of lighting for the whole frame ----
        glUseProgram(worldProg)
        glUniformMatrix4fv(uProj, 1, false, proj, 0)
        glUniformMatrix4fv(uView, 1, false, view, 0)
        glUniform1f(uCurve, CURVE)
        glUniform1i(uTex, 0)
        glActiveTexture(GL_TEXTURE0)

        val minutes = g.st.timeMin % 1440f
        val sunT = U.norm(minutes, 300f, 1170f)
        val elev = U.lerp(0.30f, 0.95f, day)
        sunDir[0] = cos(sunT * 3.1416f) * 0.65f
        sunDir[1] = elev
        sunDir[2] = 0.42f
        val sl = sqrt(sunDir[0] * sunDir[0] + sunDir[1] * sunDir[1] + sunDir[2] * sunDir[2])
        sunDir[0] /= sl; sunDir[1] /= sl; sunDir[2] /= sl
        glUniform3f(uSunDir, sunDir[0], sunDir[1], sunDir[2])

        val sc = 0.30f + 0.72f * day
        val sunR = Color.red(sky.sunColor) / 255f * sc
        val sunG = Color.green(sky.sunColor) / 255f * sc
        val sunB = Color.blue(sky.sunColor) / 255f * sc
        glUniform3f(uSunCol, sunR, sunG, sunB)

        // hemisphere fill: sky above, warm bounce off the grass below
        val fillUp = 0.20f + 0.16f * day
        glUniform3f(
            uSkyFill,
            Color.red(sky.mid) / 255f * fillUp,
            Color.green(sky.mid) / 255f * fillUp,
            Color.blue(sky.mid) / 255f * fillUp
        )
        val fillDown = 0.10f + 0.10f * day
        glUniform3f(uGroundFill, 0.42f * fillDown, 0.52f * fillDown, 0.30f * fillDown)

        val fogNear = drawDist * 0.55f
        glUniform2f(uFog, fogNear, drawDist * 1.15f)
        glUniform3f(
            uFogCol,
            Color.red(sky.horizon) / 255f, Color.green(sky.horizon) / 255f, Color.blue(sky.horizon) / 255f
        )
        glUniform1f(uTimeLoc, (g.timeMs * 0.001f) % 6283f)
        glUniform1f(uWind, g.windAmount())
        glUniform1f(uEmissive, 0f)
        glUniform1f(uCut, 0.45f)

        if (!diagged) {
            diagged = true
            val sc = scene
            android.util.Log.i(
                "Riverside",
                "diag chunks=" + (sc?.chunks?.size ?: -1) +
                    " ground=" + (sc?.chunks?.count { it.ground != null } ?: -1) +
                    " bark=" + (sc?.chunks?.count { it.bark != null } ?: -1) +
                    " water=" + (if (sc?.water != null) 1 else 0) +
                    " h00=" + Terrain.height(0f, 0f) +
                    " hSpawn=" + Terrain.height(World.SPAWN_X, World.SPAWN_Z) +
                    " eye=" + eye[0] + "," + eye[1] + "," + eye[2] +
                    " fwd=" + fwd[0] + "," + fwd[1] + "," + fwd[2] +
                    " drawDist=" + drawDist +
                    " visCabin=" + visible(World.CABIN_X, World.CABIN_Z, 6f, drawDist) +
                    " visChunk0=" + (sc?.chunks?.firstOrNull()?.let { visible(it.cx, it.cz, 8.6f, drawDist) } ?: false) +
                    " visAny=" + (sc?.chunks?.count { visible(it.cx, it.cz, 8.6f, drawDist) } ?: -1)
            )
        }

        drawChunks(g)
        drawShadows(g)
        drawStructures(g, night)
        drawProps(g, night)
        drawPlots(g)
        drawForage(g)
        drawTrees(g)
        drawCharacters(g)
        if (g.fishing.active) drawBobber(g)

        drawWater(g, sky)

        glUseProgram(worldProg)
        drawEffects(g, night)

        // ---- present ----
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenW, screenH)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        glUseProgram(blitProg)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, target.color)
        glUniform1i(blitTex, 0)
        // a hair warmer by day, cooler and quieter at night
        glUniform3f(
            blitGrade,
            U.lerp(0.94f, 1.04f, day),
            U.lerp(0.95f, 1.00f, day),
            U.lerp(1.06f, 0.97f, day)
        )
        glUniform1f(blitVig, 0.42f)
        drawFullQuad(blitAPos)

        drawUiLayer(g)
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
        glUniform1f(skyTime, (g.timeMs * 0.001f) % 6283f)
        glUniform1f(skyHaze, 0.35f + sky.haze)

        val m = g.st.timeMin % 1440f
        val sunUp = m in 290f..1180f
        val t = if (sunUp) U.norm(m, 300f, 1170f) else U.norm(if (m > 1140f) m - 1140f else m + 300f, 0f, 600f)
        // the sun tracks the same arc the light comes from, so shadows agree
        val sx = U.lerp(0.86f, 0.14f, t)
        val sy = 0.28f + sin(t * 3.1416f) * 0.54f
        glUniform2f(skySun, sx, sy)
        if (sunUp) {
            glUniform3f(skySunCol, Color.red(sky.sunColor) / 255f, Color.green(sky.sunColor) / 255f, Color.blue(sky.sunColor) / 255f)
            glUniform3f(skySunGlow, Color.red(sky.sunGlow) / 255f * 0.55f, Color.green(sky.sunGlow) / 255f * 0.55f, Color.blue(sky.sunGlow) / 255f * 0.55f)
            glUniform1f(skySunSize, 0.045f)
        } else {
            glUniform3f(skySunCol, 0.95f, 0.94f, 0.86f)
            glUniform3f(skySunGlow, 0.26f, 0.30f, 0.44f)
            glUniform1f(skySunSize, 0.032f)
        }
        drawFullQuad(skyAPos)
    }

    // ------------------------------------------------------------- drawing

    private var uvX = 1f
    private var uvY = 1f

    private fun uv(a: Float, b: Float) {
        uvX = a; uvY = b
    }

    private fun bindAndDraw(
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
    private fun box(
        x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float,
        texId: Int, closed: Boolean = false, uvPerM: Float = TEXELS,
        r: Float = 1f, g: Float = 1f, b: Float = 1f
    ) {
        ms.push().translate(x, y, z).scale(sx, sy, sz)
        uv(max(sx, sz) * uvPerM, sy * uvPerM)
        bindAndDraw(if (closed) prims?.boxClosed else prims?.box, texId, r, g, b)
        ms.pop()
    }

    private fun drawChunks(g: Game) {
        val t = tex ?: return
        val s = scene ?: return
        ms.identity()
        val dist = drawDist
        val half = Scenery.CHUNK * 0.75f
        for (ch in s.chunks) {
            if (!visible(ch.cx, ch.cz, half, dist)) continue
            bindAndDraw(ch.ground, t.ground)
        }
        for (ch in s.chunks) {
            if (!visible(ch.cx, ch.cz, half, dist)) continue
            bindAndDraw(ch.bark, t.bark)
            bindAndDraw(ch.leaf, t.leaf)
            bindAndDraw(ch.rock, t.rock)
        }
        if (quality > 0) {
            val near = dist * 0.62f
            for (ch in s.chunks) {
                if (!visible(ch.cx, ch.cz, half, near)) continue
                bindAndDraw(ch.detail, t.detail)
                bindAndDraw(ch.flower, t.flowers)
            }
        }
    }

    private fun shadowAt(x: Float, z: Float, r: Float, alpha: Float) {
        val y = Terrain.groundY(x, z) + 0.03f
        ms.identity().translate(x, y, z).scale(r * 2f, 1f, r * 2f)
        bindAndDraw(prims?.flat, tex!!.shadow, 0.16f, 0.14f, 0.12f, alpha)
    }

    /** Contact shadows, so nothing looks like it is hovering. */
    private fun drawShadows(g: Game) {
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glUniform1f(uCut, 0.01f)
        val a = 0.44f * (1f - g.nightAmount() * 0.55f)

        for (i in World.trees.indices) {
            val tr = World.trees[i]
            if (!visible(tr.x, tr.z, 2f, 26f)) continue
            if (World.treeStanding(g.st, i)) shadowAt(tr.x, tr.z, 1.5f * tr.scale, a)
            else shadowAt(tr.x, tr.z, 0.45f * tr.scale, a)
        }
        for (p in World.props) {
            if (p.kind == World.PKind.LANTERN || p.kind == World.PKind.JETTY) continue
            if (!visible(p.x, p.z, 2f, 24f)) continue
            shadowAt(p.x, p.z, 0.8f, a * 0.9f)
        }
        for (i in World.forage.indices) {
            val f = World.forage[i]
            if (!World.forageAvailable(g.st, i)) continue
            if (!visible(f.x, f.z, 1f, 18f)) continue
            shadowAt(f.x, f.z, 0.26f, a)
        }
        if (visible(World.CABIN_X, World.CABIN_Z, 5f, 40f)) {
            shadowAt(World.CABIN_X, World.CABIN_Z, 3.2f + g.st.cabinLevel * 0.35f, a)
        }
        if (visible(World.MARKET_X, World.MARKET_Z, 4f, 40f)) shadowAt(World.MARKET_X, World.MARKET_Z, 3.0f, a)
        val p = g.player
        shadowAt(p.x, p.z, 0.42f, 0.55f)
        // Pip, behind the counter
        shadowAt(World.MARKET_X + 1.7f, World.MARKET_Z - 0.5f, 0.4f, a)

        glUniform1f(uCut, 0.45f)
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    // --------------------------------------------------------- structures

    private fun drawStructures(g: Game, night: Float) {
        val t = tex ?: return
        ms.identity()
        if (visible(Terrain.FIELD_X, Terrain.FIELD_Z, 8f, drawDist)) {
            bindAndDraw(fenceMesh, t.planks, 0.96f, 0.88f, 0.78f)
        }
        val bx = Terrain.riverX(Terrain.BRIDGE_Z)
        if (visible(bx, Terrain.BRIDGE_Z, 9f, drawDist) || visible(World.POND_X, World.POND_Z, 9f, drawDist)) {
            bindAndDraw(deckMesh, t.plankWorn)
            bindAndDraw(railMesh, t.planks, 0.94f, 0.86f, 0.76f)
        }
        if (visible(World.CABIN_X, World.CABIN_Z, 6f, drawDist)) drawCabin(g, night)
        if (visible(World.MARKET_X, World.MARKET_Z, 6f, drawDist)) drawMarket(g, night)
    }

    private fun drawCabin(g: Game, night: Float) {
        val t = tex!!
        val x = World.CABIN_X
        val z = World.CABIN_Z
        val y = Terrain.height(x, z)
        val lit = if (night > 0.2f) t.windowLit else t.window
        val emissive = if (night > 0.2f) U.smoothRange(night, 0.2f, 0.6f) else 0f
        val level = g.st.cabinLevel
        ms.identity()

        fun window(wx: Float, wy: Float, wz: Float, w: Float, h: Float) {
            glUniform1f(uEmissive, emissive)
            ms.push().translate(wx, wy, wz).scale(w, h, 1f)
            bindAndDraw(prims?.quad, lit)
            ms.pop()
            glUniform1f(uEmissive, 0f)
        }

        when (level) {
            1 -> {
                box(x, y, z, 4.6f, 2.4f, 3.4f, t.logs)
                roofAt(x, y + 2.4f, z, 5.3f, 1.5f, 4.1f, t.shingleRed)
                doorAt(x + 1.2f, y, z + 1.72f, 1.05f, 1.8f, t.door)
                window(x - 1.0f, y + 1.15f, z + 1.72f, 0.95f, 0.95f)
                box(x + 1.6f, y + 2.2f, z - 0.7f, 0.55f, 1.6f, 0.55f, t.stone)
                porch(x, y, z + 2.0f, 2.6f, 1.2f, t)
            }
            2 -> {
                box(x, y, z, 5.6f, 2.7f, 3.8f, t.logs)
                roofAt(x, y + 2.7f, z, 6.4f, 1.7f, 4.5f, t.shingleRed)
                doorAt(x + 1.6f, y, z + 1.92f, 1.1f, 1.9f, t.door)
                window(x - 1.5f, y + 1.35f, z + 1.92f, 1f, 1f)
                window(x - 0.1f, y + 1.35f, z + 1.92f, 1f, 1f)
                box(x + 2.0f, y + 2.5f, z - 0.8f, 0.6f, 1.7f, 0.6f, t.stone)
                porch(x + 0.2f, y, z + 2.2f, 3.4f, 1.5f, t)
                flowerBox(x - 1.5f, y + 0.95f, z + 2.0f, t)
            }
            3 -> {
                box(x, y, z, 6.4f, 0.45f, 4.4f, t.stone)
                box(x, y + 0.45f, z, 6.2f, 3.9f, 4.2f, t.planks)
                box(x, y + 2.1f, z, 6.3f, 0.18f, 4.3f, t.logs)
                roofAt(x, y + 4.35f, z, 7.2f, 2.0f, 5.0f, t.shingleRed)
                doorAt(x + 1.9f, y + 0.45f, z + 2.12f, 1.15f, 2f, t.door)
                window(x - 1.8f, y + 1.4f, z + 2.12f, 1.05f, 1.05f)
                window(x - 0.3f, y + 1.4f, z + 2.12f, 1.05f, 1.05f)
                window(x - 1.8f, y + 3.2f, z + 2.12f, 1.05f, 1.05f)
                window(x - 0.3f, y + 3.2f, z + 2.12f, 1.05f, 1.05f)
                flowerBox(x - 1.8f, y + 0.87f, z + 2.2f, t)
                box(x + 2.3f, y + 4.0f, z - 1.0f, 0.7f, 2.1f, 0.7f, t.stone)
                porch(x + 0.3f, y, z + 2.6f, 4.0f, 1.8f, t)
            }
            else -> {
                box(x, y, z, 7.8f, 0.5f, 4.8f, t.stone)
                box(x, y + 0.5f, z, 7.6f, 4.5f, 4.6f, t.planks)
                box(x, y + 2.4f, z, 7.7f, 0.2f, 4.7f, t.logs)
                roofAt(x, y + 5.0f, z, 8.6f, 2.3f, 5.4f, t.shinglePlum)
                box(x - 0.4f, y + 5.0f, z + 1.0f, 2.1f, 1.2f, 1.8f, t.planks)
                roofAt(x - 0.4f, y + 6.2f, z + 1.0f, 2.5f, 0.9f, 2.2f, t.shinglePlum)
                window(x - 0.4f, y + 5.35f, z + 1.92f, 0.9f, 0.9f)
                doorAt(x + 2.3f, y + 0.5f, z + 2.32f, 1.2f, 2.1f, t.door)
                window(x - 2.6f, y + 1.55f, z + 2.32f, 1.1f, 1.1f)
                window(x - 1.1f, y + 1.55f, z + 2.32f, 1.1f, 1.1f)
                window(x + 0.4f, y + 1.55f, z + 2.32f, 1.1f, 1.1f)
                window(x - 2.6f, y + 3.5f, z + 2.32f, 1.1f, 1.1f)
                window(x - 1.1f, y + 3.5f, z + 2.32f, 1.1f, 1.1f)
                flowerBox(x - 2.6f, y + 1.02f, z + 2.4f, t)
                flowerBox(x - 1.1f, y + 1.02f, z + 2.4f, t)
                box(x + 2.8f, y + 4.6f, z - 1.1f, 0.85f, 2.3f, 0.85f, t.stone)
                porch(x + 0.4f, y, z + 2.8f, 4.6f, 2.0f, t)
                box(x, y + 7.3f, z, 0.07f, 0.7f, 0.07f, t.metal)
                box(x + 0.25f, y + 7.75f, z, 0.5f, 0.16f, 0.05f, t.metal)
            }
        }
    }

    private fun roofAt(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, texId: Int) {
        ms.push().translate(x, y, z).scale(w, h, d)
        val slope = sqrt(h * h + (d * 0.5f) * (d * 0.5f))
        uv(w * TEXELS, slope * TEXELS)
        bindAndDraw(prims?.roof, texId)
        ms.pop()
    }

    private fun doorAt(x: Float, y: Float, z: Float, w: Float, h: Float, texId: Int) {
        ms.push().translate(x, y, z).scale(w, h, 1f)
        bindAndDraw(prims?.quad, texId)
        ms.pop()
    }

    private fun porch(x: Float, y: Float, z: Float, w: Float, d: Float, t: Textures) {
        box(x, y + 0.02f, z, w, 0.2f, d, t.plankWorn, closed = true)
        box(x - w / 2f + 0.15f, y + 0.22f, z + d / 2f - 0.15f, 0.14f, 1.9f, 0.14f, t.planks)
        box(x + w / 2f - 0.15f, y + 0.22f, z + d / 2f - 0.15f, 0.14f, 1.9f, 0.14f, t.planks)
        box(x, y + 2.12f, z, w + 0.25f, 0.16f, d + 0.1f, t.planks)
    }

    private fun flowerBox(x: Float, y: Float, z: Float, t: Textures) {
        box(x, y, z, 1.0f, 0.22f, 0.26f, t.planks, closed = true)
        for (k in 0 until 4) {
            val fx = x - 0.34f + k * 0.23f
            box(fx, y + 0.22f, z, 0.14f, 0.13f, 0.14f, t.leafGreen)
            box(
                fx, y + 0.33f, z, 0.11f, 0.1f, 0.11f,
                t.solid(if (k % 2 == 0) Color.parseColor("#D06A72") else Color.parseColor("#E8B44A"))
            )
        }
    }

    private fun drawMarket(g: Game, night: Float) {
        val t = tex!!
        val x = World.MARKET_X
        val z = World.MARKET_Z
        val y = Terrain.height(x, z)
        ms.identity()
        box(x, y, z + 0.7f, 4.8f, 0.85f, 0.8f, t.planks, closed = true)
        box(x, y + 0.85f, z + 0.7f, 5.1f, 0.12f, 1.05f, t.logs, closed = true)
        for (px in floatArrayOf(-2.4f, 2.4f)) {
            box(x + px, y, z + 0.95f, 0.18f, 2.5f, 0.18f, t.planks)
            box(x + px, y, z - 0.85f, 0.18f, 2.5f, 0.18f, t.planks)
        }
        roofAt(x, y + 2.5f, z + 0.05f, 5.1f, 1.0f, 2.4f, t.awning)
        box(x, y + 2.15f, z + 1.24f, 5.1f, 0.35f, 0.1f, t.awning, closed = true)
        box(x - 0.7f, y + 3.5f, z, 0.1f, 0.3f, 0.1f, t.planks)
        box(x + 0.7f, y + 3.5f, z, 0.1f, 0.3f, 0.1f, t.planks)
        box(x, y + 3.77f, z, 2.2f, 0.64f, 0.14f, t.planks, closed = true)
        crateAt(x - 1.5f, y + 0.97f, z + 0.7f, Color.parseColor("#E08240"), t)
        crateAt(x - 0.2f, y + 0.97f, z + 0.7f, Color.parseColor("#D6564C"), t)
        crateAt(x + 1.1f, y + 0.97f, z + 0.7f, Color.parseColor("#6FA45A"), t)
        drawPip(g, x + 1.7f, y, z - 0.5f)
    }

    private fun crateAt(x: Float, y: Float, z: Float, produce: Int, t: Textures) {
        box(x, y, z, 0.64f, 0.5f, 0.58f, t.crate, closed = true, uvPerM = 1.7f)
        ms.push().translate(x - 0.14f, y + 0.5f, z).scale(0.24f, 0.24f, 0.24f)
        bindAndDraw(prims?.blob, t.solid(produce)); ms.pop()
        ms.push().translate(x + 0.15f, y + 0.5f, z + 0.05f).scale(0.21f, 0.21f, 0.21f)
        bindAndDraw(prims?.blob, t.solid(produce)); ms.pop()
        ms.push().translate(x, y + 0.66f, z - 0.05f).scale(0.2f, 0.2f, 0.2f)
        bindAndDraw(prims?.blob, t.solid(produce)); ms.pop()
    }

    // --------------------------------------------------------------- props

    private fun drawProps(g: Game, night: Float) {
        val t = tex!!
        ms.identity()
        for (p in World.props) {
            if (!visible(p.x, p.z, 2f, drawDist * 0.8f)) continue
            val y = Terrain.height(p.x, p.z)
            when (p.kind) {
                World.PKind.LANTERN -> lantern(g, p.x, y, p.z, night)
                World.PKind.BENCH -> bench(p.x, y, p.z, p.yaw, t)
                World.PKind.CAMPFIRE -> campfire(g, p.x, y, p.z, night, t)
                World.PKind.WELL -> well(p.x, y, p.z, t)
                World.PKind.SIGN -> sign(p.x, y, p.z, p.yaw, t)
                World.PKind.SCARECROW -> scarecrow(g, p.x, y, p.z, t)
                World.PKind.BEEHIVE -> beehive(p.x, y, p.z, t)
                World.PKind.STUMP -> {
                    box(p.x, y, p.z, 0.8f, 0.45f, 0.8f, t.bark)
                    ms.push().translate(p.x, y + 0.45f, p.z).scale(0.78f, 0.03f, 0.78f)
                    bindAndDraw(prims?.flat, t.logs, 0.9f, 0.78f, 0.6f)
                    ms.pop()
                }
                World.PKind.BARREL -> {
                    ms.push().translate(p.x, y, p.z).scale(0.62f, 0.9f, 0.62f)
                    uv(1.6f, 1.4f)
                    bindAndDraw(prims?.cyl, t.planks)
                    ms.pop()
                    box(p.x, y + 0.9f, p.z, 0.5f, 0.06f, 0.5f, t.logs, closed = true)
                }
                World.PKind.CRATE -> {
                    box(p.x, y, p.z, 0.7f, 0.6f, 0.7f, t.crate, closed = true, uvPerM = 1.6f)
                    box(p.x + 0.1f, y + 0.6f, p.z - 0.1f, 0.55f, 0.45f, 0.55f, t.crate, closed = true, uvPerM = 1.6f)
                }
                World.PKind.PLANTER -> {
                    box(p.x, y, p.z, 0.7f, 0.45f, 0.7f, t.planks, closed = true)
                    ms.push().translate(p.x, y + 0.62f, p.z).scale(0.62f, 0.5f, 0.62f)
                    bindAndDraw(prims?.blob, t.leaf, 0.5f, 0.78f, 0.38f)
                    ms.pop()
                    for (k in 0 until 3) {
                        val a = k * 2.1f
                        box(
                            p.x + cos(a) * 0.2f, y + 0.78f, p.z + sin(a) * 0.2f, 0.14f, 0.13f, 0.14f,
                            t.solid(if (k == 1) Color.parseColor("#E8A0C0") else Color.parseColor("#F2D45A"))
                        )
                    }
                }
                World.PKind.LOGPILE -> {
                    for (k in 0 until 3) {
                        ms.push().translate(p.x, y + 0.2f + k * 0.34f, p.z - k * 0.02f)
                            .rotateZ(90f).scale(0.36f, 1.9f, 0.36f).translate(0f, -0.5f, 0f)
                        uv(1f, 1.6f)
                        bindAndDraw(prims?.cyl, t.bark)
                        ms.pop()
                    }
                }
                World.PKind.JETTY -> Unit
            }
        }
    }

    private fun lantern(g: Game, x: Float, y: Float, z: Float, night: Float) {
        val t = tex!!
        box(x, y, z, 0.12f, 1.55f, 0.12f, t.metal, r = 0.6f, g = 0.55f, b = 0.5f)
        box(x, y + 1.55f, z, 0.34f, 0.1f, 0.34f, t.metal, closed = true, r = 0.6f, g = 0.55f, b = 0.5f)
        val on = night > 0.15f
        glUniform1f(uEmissive, if (on) U.smoothRange(night, 0.15f, 0.5f) else 0f)
        box(x, y + 1.05f, z, 0.28f, 0.42f, 0.28f, t.lantern, closed = true)
        glUniform1f(uEmissive, 0f)
        box(x, y + 1.47f, z, 0.36f, 0.1f, 0.36f, t.metal, closed = true, r = 0.55f, g = 0.5f, b = 0.46f)
    }

    private fun bench(x: Float, y: Float, z: Float, yaw: Float, t: Textures) {
        ms.push().translate(x, y, z).rotateY(yaw)
        box(0f, 0.42f, 0f, 1.9f, 0.1f, 0.6f, t.plankWorn, closed = true)
        box(0f, 0.52f, -0.26f, 1.9f, 0.55f, 0.1f, t.plankWorn, closed = true)
        box(-0.78f, 0f, 0f, 0.14f, 0.44f, 0.5f, t.planks)
        box(0.78f, 0f, 0f, 0.14f, 0.44f, 0.5f, t.planks)
        ms.pop()
    }

    private fun campfire(g: Game, x: Float, y: Float, z: Float, night: Float, t: Textures) {
        for (k in 0 until 6) {
            val a = k * 1.047f
            ms.push().translate(x + cos(a) * 0.55f, y, z + sin(a) * 0.55f).scale(0.3f, 0.22f, 0.3f)
            bindAndDraw(prims?.blob, t.rock, 0.9f, 0.88f, 0.84f)
            ms.pop()
        }
        for (k in 0 until 3) {
            ms.push().translate(x, y + 0.12f, z).rotateY(k * 60f).rotateZ(66f)
                .scale(0.14f, 1.0f, 0.14f).translate(0f, -0.5f, 0f)
            bindAndDraw(prims?.cyl, t.bark)
            ms.pop()
        }
        // flame: a couple of stacked cones, flickering
        val flick = 0.85f + sin(g.timeMs * 0.011f) * 0.1f + sin(g.timeMs * 0.023f) * 0.05f
        glUniform1f(uEmissive, 1f)
        ms.push().translate(x, y + 0.18f, z).scale(0.5f, 0.75f * flick, 0.5f)
        bindAndDraw(prims?.cone, t.solid(Color.parseColor("#F2913C")))
        ms.pop()
        ms.push().translate(x, y + 0.3f, z).scale(0.3f, 0.6f * flick, 0.3f)
        bindAndDraw(prims?.cone, t.solid(Color.parseColor("#FFD97A")))
        ms.pop()
        glUniform1f(uEmissive, 0f)
    }

    private fun well(x: Float, y: Float, z: Float, t: Textures) {
        ms.push().translate(x, y, z).scale(1.5f, 0.85f, 1.5f)
        uv(2.4f, 1.4f)
        bindAndDraw(prims?.cyl, t.stone)
        ms.pop()
        box(x - 0.62f, y + 0.85f, z, 0.14f, 1.3f, 0.14f, t.planks)
        box(x + 0.62f, y + 0.85f, z, 0.14f, 1.3f, 0.14f, t.planks)
        roofAt(x, y + 2.15f, z, 1.8f, 0.55f, 1.6f, t.shingleRed)
        box(x, y + 1.9f, z, 1.1f, 0.1f, 0.1f, t.bark)
        box(x, y + 1.45f, z, 0.42f, 0.4f, 0.42f, t.planks, closed = true)
    }

    private fun sign(x: Float, y: Float, z: Float, yaw: Float, t: Textures) {
        ms.push().translate(x, y, z).rotateY(yaw)
        box(0f, 0f, 0f, 0.14f, 1.5f, 0.14f, t.planks)
        box(0f, 1.05f, 0.06f, 1.3f, 0.45f, 0.08f, t.plankWorn, closed = true)
        box(0.35f, 0.6f, 0.06f, 0.9f, 0.32f, 0.08f, t.plankWorn, closed = true)
        ms.pop()
    }

    private fun scarecrow(g: Game, x: Float, y: Float, z: Float, t: Textures) {
        val sway = sin(g.timeMs * 0.0012f) * 3f
        ms.push().translate(x, y, z).rotateZ(sway)
        box(0f, 0f, 0f, 0.14f, 1.9f, 0.14f, t.bark)
        box(0f, 1.35f, 0f, 1.5f, 0.1f, 0.1f, t.bark)
        box(0f, 1.0f, 0f, 0.7f, 0.7f, 0.35f, t.scarf, closed = true)
        box(0f, 1.7f, 0f, 0.42f, 0.4f, 0.4f, t.straw, closed = true)
        ms.push().translate(0f, 2.06f, 0f).scale(0.9f, 0.08f, 0.9f)
        bindAndDraw(prims?.flat, t.straw)
        ms.pop()
        ms.pop()
    }

    private fun beehive(x: Float, y: Float, z: Float, t: Textures) {
        box(x, y, z, 0.7f, 0.28f, 0.7f, t.planks, closed = true)
        for (k in 0 until 3) {
            val r = 0.62f - k * 0.08f
            ms.push().translate(x, y + 0.28f + k * 0.24f, z).scale(r, 0.24f, r)
            uv(1.4f, 1f)
            bindAndDraw(prims?.cyl, t.thatch)
            ms.pop()
        }
        ms.push().translate(x, y + 1.0f, z).scale(0.42f, 0.3f, 0.42f)
        bindAndDraw(prims?.blob, t.thatch)
        ms.pop()
    }

    // ------------------------------------------------------- plots & crops

    private fun drawPlots(g: Game) {
        val t = tex!!
        val open = g.st.tier.plots
        ms.identity()
        for (i in 0 until World.MAX_PLOTS) {
            if (i >= open) continue
            val x = World.plotX(i)
            val z = World.plotZ(i)
            if (!visible(x, z, 1f, 26f)) continue
            val plot = g.st.plots[i]
            if (!plot.tilled) continue
            val y = Terrain.height(x, z)
            box(x, y, z, 1.5f, 0.14f, 1.5f, if (plot.watered) t.tilledWet else t.tilled, closed = true, uvPerM = 0.8f)
            val cropId = plot.cropId ?: continue
            val crop = Catalog.crops[cropId] ?: continue
            drawCrop(g, x, y + 0.14f, z, crop.id, U.clamp01(plot.growth / crop.days), plot.ready)
        }
    }

    private fun drawCrop(g: Game, x: Float, y: Float, z: Float, cropId: String, prog: Float, ready: Boolean) {
        val t = tex!!
        val item = Catalog.item(cropId)
        val sway = sin(g.timeMs * 0.0016f + x * 1.7f) * 3.2f * (0.4f + prog)
        val h = U.lerp(0.3f, 1.05f, U.easeOut(prog))
        ms.push().translate(x, y, z).rotateZ(sway)
        box(0f, 0f, 0f, 0.08f, h, 0.08f, t.leafGreen)
        val leaves = if (prog < 0.3f) 2 else 4
        for (k in 0 until leaves) {
            val ly = h * (0.25f + k * 0.2f)
            val dir = if (k % 2 == 0) 1f else -1f
            ms.push().translate(0f, ly, 0f).rotateZ(dir * 42f).scale(0.44f, 0.08f, 0.26f).translate(dir * 0.5f, 0f, 0f)
            bindAndDraw(prims?.box, t.leafGreen)
            ms.pop()
        }
        if (ready) {
            val fruitTex = t.solid(item.a)
            when (cropId) {
                "carrot", "turnip" -> {
                    ms.push().translate(0f, 0.04f, 0f).scale(0.36f, 0.32f, 0.36f)
                    bindAndDraw(prims?.blob, fruitTex); ms.pop()
                }
                "corn" -> box(0.15f, h * 0.42f, 0f, 0.17f, 0.42f, 0.17f, fruitTex, closed = true)
                "pumpkin" -> {
                    ms.push().translate(0.26f, 0.22f, 0.1f).scale(0.54f, 0.44f, 0.52f)
                    bindAndDraw(prims?.blob, fruitTex); ms.pop()
                }
                "berry" -> {
                    for (k in 0 until 4) {
                        ms.push().translate(-0.16f + k * 0.11f, h * (0.5f + (k % 2) * 0.18f), 0.06f)
                            .scale(0.15f, 0.15f, 0.15f)
                        bindAndDraw(prims?.blob, fruitTex); ms.pop()
                    }
                }
                else -> {
                    ms.push().translate(-0.14f, h * 0.6f, 0.05f).scale(0.25f, 0.25f, 0.25f)
                    bindAndDraw(prims?.blob, fruitTex); ms.pop()
                    ms.push().translate(0.15f, h * 0.78f, -0.03f).scale(0.22f, 0.22f, 0.22f)
                    bindAndDraw(prims?.blob, fruitTex); ms.pop()
                }
            }
        }
        ms.pop()
    }

    private fun drawForage(g: Game) {
        val t = tex!!
        ms.identity()
        for (i in World.forage.indices) {
            val f = World.forage[i]
            if (!World.forageAvailable(g.st, i)) continue
            if (!visible(f.x, f.z, 1f, 24f)) continue
            val y = Terrain.height(f.x, f.z)
            val item = Catalog.item(f.itemId)
            val bob = sin(g.timeMs * 0.0022f + i * 1.4f) * 0.03f
            ms.push().translate(f.x, y + 0.02f + bob, f.z).rotateY((g.timeMs * 0.02f) % 360f)
            when (f.itemId) {
                "mushroom" -> {
                    box(0f, 0f, 0f, 0.12f, 0.18f, 0.12f, t.solid(Color.parseColor("#F4EAD8")))
                    ms.push().translate(0f, 0.2f, 0f).scale(0.38f, 0.26f, 0.38f)
                    bindAndDraw(prims?.blob, t.solid(item.a)); ms.pop()
                }
                "flower" -> {
                    box(0f, 0f, 0f, 0.06f, 0.3f, 0.06f, t.leafGreen)
                    ms.push().translate(0f, 0.34f, 0f).scale(0.24f, 0.18f, 0.24f)
                    bindAndDraw(prims?.blob, t.solid(item.a)); ms.pop()
                }
                "honey" -> box(0f, 0f, 0f, 0.26f, 0.32f, 0.26f, t.solid(item.a), closed = true)
                else -> {
                    ms.push().translate(0f, 0.14f, 0f).scale(0.26f, 0.32f, 0.26f)
                    bindAndDraw(prims?.blob, t.solid(item.a)); ms.pop()
                }
            }
            ms.pop()
        }
    }

    private fun drawTrees(g: Game) {
        val t = tex!!
        ms.identity()
        for (i in World.trees.indices) {
            val tr = World.trees[i]
            if (!visible(tr.x, tr.z, 3f, drawDist * 0.75f)) continue
            val y = Terrain.height(tr.x, tr.z)
            val standing = World.treeStanding(g.st, i)
            val s = tr.scale
            if (!standing) {
                box(tr.x, y, tr.z, 0.5f * s, 0.4f * s, 0.5f * s, t.bark)
                ms.push().translate(tr.x, y + 0.4f * s, tr.z).scale(0.48f * s, 0.03f, 0.48f * s)
                bindAndDraw(prims?.flat, t.logs, 0.85f, 0.7f, 0.5f)
                ms.pop()
                continue
            }
            val shake = if (i == g.shakeTreeIndex && g.shakeAmount > 0f)
                sin(g.shakeAmount * 46f) * 2.6f * U.clamp01(g.shakeAmount * 3f) else 0f
            val sway = sin(g.timeMs * 0.0009f + i) * 0.7f
            ms.push().translate(tr.x, y, tr.z).rotateZ(shake + sway * 0.3f)
            when (tr.kind) {
                0 -> {
                    ms.push().scale(0.34f, 1.6f * s, 0.34f)
                    uv(1f, 1.6f * s * TEXELS)
                    bindAndDraw(prims?.cyl, t.bark); ms.pop()
                    for (k in 0 until 3) {
                        val cy = (1.1f + k * 1.15f) * s
                        val cr = (2.4f - k * 0.55f) * s
                        val ch = (2.1f - k * 0.25f) * s
                        ms.push().translate(0f, cy, 0f).scale(cr, ch, cr)
                        uv(cr * TEXELS * 1.6f, ch * TEXELS)
                        bindAndDraw(prims?.cone, t.leaf, 0.32f, 0.54f, 0.34f); ms.pop()
                    }
                }
                1 -> {
                    ms.push().scale(0.38f, 2.0f * s, 0.38f)
                    uv(1f, 2f * s * TEXELS)
                    bindAndDraw(prims?.cyl, t.bark); ms.pop()
                    ms.push().translate(0f, 3.0f * s, 0f).scale(2.8f * s, 2.6f * s, 2.6f * s)
                    uv(2.8f * s * TEXELS, 2.6f * s * TEXELS)
                    bindAndDraw(prims?.blob, t.leaf, 0.48f, 0.76f, 0.36f); ms.pop()
                    ms.push().translate(-1.05f * s, 2.4f * s, 0.4f * s).scale(1.7f * s, 1.6f * s, 1.6f * s)
                    bindAndDraw(prims?.blob, t.leaf, 0.44f, 0.7f, 0.34f); ms.pop()
                    ms.push().translate(1.1f * s, 2.55f * s, -0.35f * s).scale(1.6f * s, 1.5f * s, 1.5f * s)
                    bindAndDraw(prims?.blob, t.leaf, 0.46f, 0.74f, 0.35f); ms.pop()
                }
                else -> {
                    ms.push().scale(0.3f, 2.4f * s, 0.3f)
                    uv(1f, 2.4f * s * TEXELS)
                    bindAndDraw(prims?.cyl, t.bark, 1.3f, 1.26f, 1.18f); ms.pop()
                    ms.push().translate(0f, 3.1f * s, 0f).scale(2.1f * s, 2.0f * s, 2.0f * s)
                    bindAndDraw(prims?.blob, t.leaf, 1.12f, 0.74f, 0.84f); ms.pop()
                    ms.push().translate(-0.8f * s, 2.7f * s, 0.35f * s).scale(1.4f * s, 1.3f * s, 1.3f * s)
                    bindAndDraw(prims?.blob, t.leaf, 1.14f, 0.8f, 0.88f); ms.pop()
                }
            }
            ms.pop()
        }
    }

    // ---------------------------------------------------------- characters

    private fun drawCharacters(g: Game) {
        drawPlayer(g)
        drawDucks(g)
    }

    private fun drawPlayer(g: Game) {
        val t = tex!!
        val p = g.player
        val moving = p.moving
        val bob = if (moving) abs(sin(p.walkPhase)) * 0.05f else sin(p.idlePhase * 2.1f) * 0.02f
        val legSwing = if (moving) sin(p.walkPhase) * 32f else 0f
        val armSwing = when (p.action) {
            Act.SWING -> -70f + sin(U.clamp01(p.actionT / max(p.actionDur, 0.01f)) * 3.1416f) * 120f
            Act.WATER -> -55f
            Act.PICK -> -40f * sin(U.clamp01(p.actionT / max(p.actionDur, 0.01f)) * 3.1416f)
            Act.FISH -> -62f
            Act.SIT -> -20f
            else -> if (moving) sin(p.walkPhase + 3.1416f) * 26f else 0f
        }
        val sitDrop = if (p.sitting) 0.42f else 0f

        ms.identity()
        ms.push().translate(p.x, p.y + bob - sitDrop, p.z).rotateY(p.yaw).rotateX(p.pitch * 0.4f)

        if (p.sitting) {
            // knees forward, hands on the bench
            limb(-0.13f, 0.62f, 0f, 0.2f, 0.62f, 0.2f, 78f, t.denim)
            limb(0.13f, 0.62f, 0f, 0.2f, 0.62f, 0.2f, 78f, t.denim)
        } else {
            limb(-0.13f, 0.62f, 0f, 0.2f, 0.62f, 0.2f, legSwing, t.denim)
            limb(0.13f, 0.62f, 0f, 0.2f, 0.62f, 0.2f, -legSwing, t.denim)
            box(-0.13f, 0f, 0.03f, 0.23f, 0.12f, 0.3f, t.boot, closed = true)
            box(0.13f, 0f, 0.03f, 0.23f, 0.12f, 0.3f, t.boot, closed = true)
        }

        box(0f, 0.6f, 0f, 0.46f, 0.58f, 0.32f, t.shirt)
        box(0f, 1.12f, 0f, 0.52f, 0.1f, 0.34f, t.scarf)
        limb(-0.31f, 1.12f, 0f, 0.17f, 0.5f, 0.18f, armSwing, t.shirt)
        limb(0.31f, 1.12f, 0f, 0.17f, 0.5f, 0.18f, -armSwing * 0.35f, t.shirt)
        box(0f, 1.22f, 0f, 0.44f, 0.42f, 0.4f, t.skin)
        box(0f, 1.52f, 0f, 0.46f, 0.13f, 0.42f, t.hair)
        box(0f, 1.22f, -0.19f, 0.44f, 0.32f, 0.06f, t.hair)
        box(0f, 1.64f, 0f, 0.82f, 0.06f, 0.78f, t.straw, closed = true)
        box(0f, 1.68f, 0f, 0.42f, 0.16f, 0.4f, t.straw, closed = true)

        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        ms.push().translate(0f, 1.26f, 0.21f).scale(0.38f, 0.38f, 1f)
        bindAndDraw(prims?.quad, t.face); ms.pop()
        glDisable(GL_BLEND)

        drawTool(g, armSwing, t)
        ms.pop()
    }

    private fun limb(px: Float, py: Float, pz: Float, sx: Float, sy: Float, sz: Float, deg: Float, texId: Int) {
        ms.push().translate(px, py, pz).rotateX(deg).scale(sx, sy, sz).translate(0f, -1f, 0f)
        bindAndDraw(prims?.box, texId)
        ms.pop()
    }

    private fun drawTool(g: Game, armSwing: Float, t: Textures) {
        val p = g.player
        if (p.action == Act.NONE || p.action == Act.CHEER || p.action == Act.SIT) return
        ms.push().translate(-0.31f, 1.12f, 0f).rotateX(armSwing).translate(0f, -0.5f, 0f)
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
                box(0f, -0.1f, 0f, 0.05f, 1.6f, 0.05f, t.bark)
                ms.pop()
            }
            Act.PICK -> {
                ms.push().translate(0f, -0.1f, 0.08f).scale(0.18f, 0.18f, 0.18f)
                bindAndDraw(prims?.blob, t.leafGreen); ms.pop()
            }
        }
        ms.pop()
    }

    /** Pip the shopkeeper: a small fox behind the counter. */
    private fun drawPip(g: Game, x: Float, y: Float, z: Float) {
        val t = tex!!
        val bob = sin(g.timeMs * 0.0022f) * 0.04f
        ms.push().translate(x, y + bob, z).rotateY(-18f).scale(1.32f, 1.32f, 1.32f)
        box(0f, 0f, 0f, 0.5f, 0.55f, 0.4f, t.foxFur)
        box(0f, 0.12f, 0.21f, 0.32f, 0.4f, 0.06f, t.foxCream)
        box(0f, 0.55f, 0.02f, 0.52f, 0.46f, 0.46f, t.foxFur)
        box(0f, 0.62f, 0.24f, 0.3f, 0.26f, 0.06f, t.foxCream)
        box(-0.17f, 1.01f, 0.02f, 0.16f, 0.22f, 0.1f, t.foxFur)
        box(0.17f, 1.01f, 0.02f, 0.16f, 0.22f, 0.1f, t.foxFur)
        ms.push().translate(0f, 0.18f, -0.3f).rotateX(28f).scale(0.24f, 0.5f, 0.24f)
        bindAndDraw(prims?.box, t.foxFur); ms.pop()
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        ms.push().translate(0f, 0.6f, 0.27f).scale(0.46f, 0.46f, 1f)
        bindAndDraw(prims?.quad, t.foxFace); ms.pop()
        glDisable(GL_BLEND)
        ms.pop()
    }

    /** Two ducks, doing slow laps of the pond. */
    private fun drawDucks(g: Game) {
        val t = tex!!
        if (!visible(World.POND_X, World.POND_Z, 8f, 34f)) return
        ms.identity()
        for (k in 0 until 2) {
            val a = g.timeMs * 0.00012f + k * 2.4f
            val rr = 2.6f + k * 1.3f
            val x = World.POND_X + cos(a) * rr
            val z = World.POND_Z + sin(a) * rr * 0.8f
            val y = Terrain.WATER_Y + 0.1f + sin(g.timeMs * 0.002f + k) * 0.02f
            val yaw = Math.toDegrees((a + 1.5708f).toDouble()).toFloat()
            ms.push().translate(x, y, z).rotateY(yaw)
            ms.push().scale(0.44f, 0.3f, 0.32f)
            bindAndDraw(prims?.blob, t.white, 0.96f, 0.94f, 0.9f); ms.pop()
            box(0f, 0.18f, 0.1f, 0.16f, 0.26f, 0.16f, t.white, closed = true, r = 0.96f, g = 0.94f, b = 0.9f)
            box(0f, 0.3f, 0.18f, 0.1f, 0.07f, 0.14f, t.solid(Color.parseColor("#E8A33C")), closed = true)
            ms.pop()
        }
    }

    private fun drawBobber(g: Game) {
        val t = tex!!
        val f = g.fishing
        if (f.phase == FPhase.IDLE) return
        val castT = if (f.phase == FPhase.CAST) U.easeOut(U.clamp01(f.t / 0.6f)) else 1f
        val p = g.player
        val bx = U.lerp(p.x, f.bobX, castT)
        val bz = U.lerp(p.z, f.bobZ, castT)
        val arc = sin(castT * 3.1416f) * 1.4f
        val by = U.lerp(p.y + 1.6f, Terrain.WATER_Y + 0.08f, castT) + arc + f.dip(g.timeMs)
        ms.identity()
        ms.push().translate(bx, by, bz).scale(0.16f, 0.16f, 0.16f)
        bindAndDraw(prims?.blob, t.solid(Color.parseColor("#E4E0D2"))); ms.pop()
        ms.push().translate(bx, by + 0.07f, bz).scale(0.15f, 0.15f, 0.15f)
        bindAndDraw(prims?.blob, t.solid(Color.parseColor("#D0707A"))); ms.pop()

        if (castT >= 1f) {
            glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDepthMask(false)
            glUniform1f(uCut, 0.01f)
            for (k in 0 until 3) {
                val ph = ((g.timeMs * 0.0009f) + k * 0.33f) % 1f
                val s = 0.35f + ph * 1.3f
                ms.identity().translate(bx, Terrain.WATER_Y + 0.04f, bz).scale(s, 1f, s)
                bindAndDraw(prims?.flat, t.ring, 1f, 1f, 1f, (1f - ph) * 0.55f)
            }
            glUniform1f(uCut, 0.45f)
            glDepthMask(true); glDisable(GL_BLEND)
        }
    }

    // --------------------------------------------------------------- water

    private fun drawWater(g: Game, sky: MutableSkyKey) {
        val s = scene ?: return
        val w = s.water ?: return
        val t = tex ?: return
        glUseProgram(waterProg)
        glUniformMatrix4fv(wProj, 1, false, proj, 0)
        glUniformMatrix4fv(wView, 1, false, view, 0)
        glUniform1f(wCurve, CURVE)
        glUniform1f(wTime, (g.timeMs * 0.001f) % 6283f)
        glUniform3f(wCamPos, eye[0], eye[1], eye[2])
        glUniform2f(wFog, drawDist * 0.55f, drawDist * 1.15f)
        glUniform3f(
            wFogCol,
            Color.red(sky.horizon) / 255f, Color.green(sky.horizon) / 255f, Color.blue(sky.horizon) / 255f
        )
        val day = 1f - g.nightAmount()
        glUniform3f(wShallow, 0.42f * (0.4f + day * 0.7f), 0.72f * (0.4f + day * 0.7f), 0.74f * (0.45f + day * 0.65f))
        glUniform3f(wDeep, 0.10f * (0.4f + day * 0.7f), 0.32f * (0.4f + day * 0.7f), 0.46f * (0.45f + day * 0.65f))
        glUniform3f(wSky, Color.red(sky.mid) / 255f, Color.green(sky.mid) / 255f, Color.blue(sky.mid) / 255f)
        glUniform3f(
            wSun,
            Color.red(sky.sunColor) / 255f * day, Color.green(sky.sunColor) / 255f * day,
            Color.blue(sky.sunColor) / 255f * day
        )
        glUniform3f(wSunDir, sunDir[0], sunDir[1], sunDir[2])
        glUniform1i(wTex, 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, t.water)

        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        w.bind(wAPos, wANor, wAUv, wACol)
        w.draw()
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    // ------------------------------------------------------------- effects

    private fun drawEffects(g: Game, night: Float) {
        val t = tex!!
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glUniform1f(uCut, 0.01f)
        glUniform1f(uEmissive, 1f)

        // lantern and firelight halos, once the sun is down
        if (night > 0.1f) {
            val a = U.smoothRange(night, 0.1f, 0.5f)
            for (p in World.props) {
                val glowY: Float
                val size: Float
                when (p.kind) {
                    World.PKind.LANTERN -> { glowY = 1.25f; size = 2.4f }
                    World.PKind.CAMPFIRE -> { glowY = 0.5f; size = 4.2f }
                    else -> continue
                }
                if (!visible(p.x, p.z, 3f, drawDist)) continue
                val flick = if (p.kind == World.PKind.CAMPFIRE)
                    0.85f + sin(g.timeMs * 0.009f) * 0.15f else 1f
                billboard(p.x, Terrain.height(p.x, p.z) + glowY, p.z, size * flick, t.glow, 1f, 0.86f, 0.55f, 0.42f * a)
            }
        }
        // the campfire burns by day too, just quieter
        val fx = World.FIRE_X
        val fz = World.FIRE_Z
        if (visible(fx, fz, 3f, drawDist)) {
            billboard(fx, Terrain.height(fx, fz) + 0.45f, fz, 1.6f, t.glow, 1f, 0.8f, 0.4f, 0.3f)
        }
        glUniform1f(uEmissive, 0f)

        chimneySmoke(g)
        drawClouds(g)
        drawParticles(g)
        drawHint(g)

        glUniform1f(uCut, 0.45f)
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    /** A camera-facing quad. Good enough for smoke, glow and pixel particles. */
    private fun billboard(
        x: Float, y: Float, z: Float, size: Float, texId: Int,
        r: Float, g: Float, b: Float, a: Float
    ) {
        ms.identity().translate(x, y, z).rotateY(billboardYaw).scale(size, size, size).translate(0f, -0.5f, 0f)
        bindAndDraw(prims?.quad, texId, r, g, b, a)
    }

    private var billboardYaw = 0f

    private fun chimneySmoke(g: Game) {
        val t = tex!!
        val level = g.st.cabinLevel
        val x = World.CABIN_X
        val z = World.CABIN_Z
        if (!visible(x, z, 6f, drawDist)) return
        val baseY = Terrain.height(x, z)
        val topY = baseY + when (level) { 1 -> 3.9f; 2 -> 4.3f; 3 -> 6.2f; else -> 7.0f }
        val cx = x + when (level) { 1 -> 1.6f; 2 -> 2.0f; 3 -> 2.3f; else -> 2.8f }
        val cz = z - when (level) { 1 -> 0.7f; 2 -> 0.8f; 3 -> 1.0f; else -> 1.1f }
        for (i in 0 until 6) {
            val ph = ((g.timeMs * 0.00019f) + i * 0.167f) % 1f
            val a = (1f - ph) * 0.38f
            val s = 0.3f + ph * 1.5f
            billboard(cx + sin(ph * 5f + i) * 0.6f * ph, topY + ph * 3.2f, cz, s, t.cloud, 1f, 0.98f, 0.95f, a)
        }
        // and a wisp from the campfire
        for (i in 0 until 4) {
            val ph = ((g.timeMs * 0.00026f) + i * 0.25f) % 1f
            billboard(
                World.FIRE_X + sin(ph * 4f + i) * 0.4f * ph,
                Terrain.height(World.FIRE_X, World.FIRE_Z) + 0.7f + ph * 2.4f,
                World.FIRE_Z, 0.35f + ph * 1.1f, t.cloud, 0.9f, 0.88f, 0.86f, (1f - ph) * 0.3f
            )
        }
    }

    private fun drawClouds(g: Game) {
        if (quality < 1) return
        val t = tex!!
        val n = if (quality >= 2) 14 else 9
        for (i in 0 until n) {
            val speed = 0.5f + U.hash(i * 11 + 5) * 0.5f
            var off = (U.hash(i * 37 + 3) + g.timeMs * 0.0000045f * speed) % 1f
            if (off < 0f) off += 1f
            val ang = off * 6.2832f
            val rr = 70f + U.hash(i * 29 + 9) * 45f
            val x = cos(ang) * rr
            val z = sin(ang) * rr
            val y = 22f + U.hash(i * 61 + 5) * 14f
            val sc = 12f + U.hash(i * 53 + 7) * 16f
            val a = 0.42f + U.hash(i * 17 + 1) * 0.26f
            ms.identity().translate(x, y, z)
                .rotateY(Math.toDegrees((-ang + 1.5708f).toDouble()).toFloat())
                .scale(sc, sc * 0.45f, 1f).translate(0f, -0.5f, 0f)
            bindAndDraw(prims?.quad, t.cloud, 1f, 1f, 1f, a)
        }
    }

    private fun drawParticles(g: Game) {
        val t = tex!!
        val p = g.particles
        for (i in p.life.indices) {
            if (p.life[i] <= 0f) continue
            val a = p.alphaOf(i)
            if (a <= 0.02f) continue
            if (!visible(p.px[i], p.pz[i], 1f, drawDist)) continue
            val s = p.sizeOf(i)
            val c = p.col[i]
            val r = Color.red(c) / 255f
            val gg = Color.green(c) / 255f
            val bb = Color.blue(c) / 255f
            when (p.kind[i]) {
                P3.RING -> {
                    ms.identity().translate(p.px[i], p.py[i], p.pz[i]).scale(s * 2f, 1f, s * 2f)
                    bindAndDraw(prims?.flat, t.ring, r, gg, bb, a)
                }
                P3.RAIN -> {
                    ms.identity().translate(p.px[i], p.py[i], p.pz[i])
                        .rotateY(billboardYaw).scale(s * 0.5f, s * 7f, s * 0.5f)
                    bindAndDraw(prims?.quad, t.dot, r, gg, bb, a)
                }
                P3.FIREFLY -> billboard(p.px[i], p.py[i], p.pz[i], s * 5f, t.glow, r, gg, bb, a)
                else -> billboard(p.px[i], p.py[i], p.pz[i], s * 2f, t.dot, r, gg, bb, a)
            }
        }
    }

    /** A bobbing marker over whatever the action button will act on. */
    private fun drawHint(g: Game) {
        val hx = g.hintTargetX()
        if (hx.isNaN()) return
        val hz = g.hintTargetZ()
        val bob = sin(g.timeMs * 0.006f) * 0.1f
        ms.identity()
            .translate(hx, Terrain.groundY(hx, hz) + g.hintHeight() + 0.5f + bob, hz)
            .rotateZ(180f).scale(0.34f, 0.36f, 0.34f)
        bindAndDraw(prims?.cone, tex!!.solid(Color.parseColor("#FFF3C0")), 1f, 1f, 1f, 0.9f)
    }

    // ------------------------------------------------------------- ui pass

    private fun drawUiLayer(g: Game) {
        val bmp = uiBitmap ?: return
        val c = uiCanvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val s = uiH / com.cozyhollow.riverside.Ui.DESIGN_H
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
    }

    companion object {
        /** Texture repeats per metre. */
        private const val TEXELS = 0.75f
        /** How hard the world curves away toward the horizon. */
        private const val CURVE = 0.0034f
    }
}
