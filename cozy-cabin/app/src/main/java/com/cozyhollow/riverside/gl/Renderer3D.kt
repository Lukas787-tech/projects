package com.cozyhollow.riverside.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.opengl.Matrix
import com.cozyhollow.riverside.Catalog
import com.cozyhollow.riverside.FPhase
import com.cozyhollow.riverside.Game
import com.cozyhollow.riverside.Interior
import com.cozyhollow.riverside.MutableSkyKey
import com.cozyhollow.riverside.Pal
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
 * Draws the hollow in winter.
 *
 * The world is real 3D geometry on a real heightfield, filmed by a camera that
 * sits high and well back, so the yard reads as a lit diorama rather than a
 * platform game. It renders into an offscreen buffer, which is bloomed, split-
 * toned and vignetted on the way to the screen, and the interface is composited
 * on top at its own resolution so the text stays crisp.
 *
 * The one thing this renderer does that the summer one did not is warm light.
 * Every window, lantern, brazier and fire registers with a [LightSet] each
 * frame, the best few get uploaded, and the world and ice shaders pool them
 * onto the snow. That is the whole picture: cold blue everywhere, and small
 * pools of orange around the places people are.
 */
class Renderer3D {

    // ---- programs ----
    private var worldProg = 0
    private var iceProg = 0
    private var skyProg = 0
    private var blitProg = 0
    private var uiProg = 0

    private var aPos = 0; private var aNor = 0; private var aUv = 0; private var aCol = 0
    private var uProj = 0; private var uView = 0; private var uModel = 0
    private var uCurve = 0; private var uTimeLoc = 0; private var uWind = 0; private var uUvScale = 0
    private var uSunDir = 0; private var uSunCol = 0; private var uSkyFill = 0; private var uGroundFill = 0
    private var uFog = 0; private var uFogCol = 0; private var uColor = 0; private var uTex = 0
    private var uEmissive = 0; private var uCut = 0; private var uNear = 0
    private var uSparkle = 0; private var uEye = 0
    private var uLightPos = 0; private var uLightCol = 0

    private var wAPos = 0; private var wANor = 0; private var wAUv = 0; private var wACol = 0
    private var wProj = 0; private var wView = 0; private var wCurve = 0; private var wTime = 0
    private var wCamPos = 0; private var wFog = 0; private var wFogCol = 0
    private var wShallow = 0; private var wDeep = 0; private var wSky = 0; private var wSun = 0
    private var wSunDir = 0; private var wTex = 0; private var wSnow = 0
    private var wLightPos = 0; private var wLightCol = 0

    private var skyAPos = 0
    private var skyTop = 0; private var skyMid = 0; private var skyHor = 0
    private var skyStars = 0; private var skySun = 0; private var skySunCol = 0
    private var skySunGlow = 0; private var skySunSize = 0; private var skyAspect = 0
    private var skyTime = 0; private var skyHaze = 0; private var skyAurora = 0

    private var blitAPos = 0; private var blitTex = 0; private var blitGrade = 0; private var blitVig = 0
    private var blitShadow = 0; private var blitBloom = 0; private var blitTexel = 0
    private var uiAPos = 0; private var uiTexLoc = 0

    private var fullQuad = 0

    // ---- resources ----
    private var tex: Textures? = null
    private var prims: Prims? = null
    private var scene: Scenery? = null
    private var rt: RenderTarget? = null
    private var decal: GroundDecal? = null

    private val ctx = DrawCtx()
    private val lights = LightSet()
    private var builds: Buildings? = null
    private var props: Props3D? = null
    private var actors: Actors? = null
    private var room: Room3D? = null

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
    private val ms get() = ctx.ms
    private val eye = FloatArray(3)
    private val fwd = FloatArray(3)

    private var camToTarget = 12f
    private val sunDir = FloatArray(3)

    /** Everything between the lens and you stipples away rather than blocking the shot. */
    private val nearFade: Float get() = max(3f, camToTarget - 2.0f)

    /** How far scenery is drawn, and where the snow haze swallows it. */
    private val drawDist: Float get() = when (quality) { 0 -> 36f; 1 -> 48f; else -> 58f }

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
        uNear = glGetUniformLocation(worldProg, "uNear")
        uSparkle = glGetUniformLocation(worldProg, "uSparkle")
        uEye = glGetUniformLocation(worldProg, "uEye")
        uLightPos = glGetUniformLocation(worldProg, "uLightPos[0]")
        uLightCol = glGetUniformLocation(worldProg, "uLightCol[0]")

        iceProg = Gl.program(Shaders.ICE_VS, Shaders.ICE_FS)
        wAPos = glGetAttribLocation(iceProg, "aPos")
        wANor = glGetAttribLocation(iceProg, "aNor")
        wAUv = glGetAttribLocation(iceProg, "aUv")
        wACol = glGetAttribLocation(iceProg, "aCol")
        wProj = glGetUniformLocation(iceProg, "uProj")
        wView = glGetUniformLocation(iceProg, "uView")
        wCurve = glGetUniformLocation(iceProg, "uCurve")
        wTime = glGetUniformLocation(iceProg, "uTime")
        wCamPos = glGetUniformLocation(iceProg, "uCamPos")
        wFog = glGetUniformLocation(iceProg, "uFog")
        wFogCol = glGetUniformLocation(iceProg, "uFogCol")
        wShallow = glGetUniformLocation(iceProg, "uShallow")
        wDeep = glGetUniformLocation(iceProg, "uDeep")
        wSky = glGetUniformLocation(iceProg, "uSkyCol")
        wSun = glGetUniformLocation(iceProg, "uSunCol")
        wSunDir = glGetUniformLocation(iceProg, "uSunDir")
        wSnow = glGetUniformLocation(iceProg, "uSnowCol")
        wTex = glGetUniformLocation(iceProg, "uTex")
        wLightPos = glGetUniformLocation(iceProg, "uLightPos[0]")
        wLightCol = glGetUniformLocation(iceProg, "uLightCol[0]")

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
        skyAurora = glGetUniformLocation(skyProg, "uAurora")

        blitProg = Gl.program(Shaders.BLIT_VS, Shaders.BLIT_FS)
        blitAPos = glGetAttribLocation(blitProg, "aPos")
        blitTex = glGetUniformLocation(blitProg, "uTex")
        blitGrade = glGetUniformLocation(blitProg, "uGrade")
        blitShadow = glGetUniformLocation(blitProg, "uShadowTint")
        blitVig = glGetUniformLocation(blitProg, "uVignette")
        blitBloom = glGetUniformLocation(blitProg, "uBloom")
        blitTexel = glGetUniformLocation(blitProg, "uTexel")

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
        decal = GroundDecal()
        scene = Scenery().also { it.build() }

        ctx.tex = tex
        ctx.prims = prims
        ctx.aPos = aPos; ctx.aNor = aNor; ctx.aUv = aUv; ctx.aCol = aCol
        ctx.uModel = uModel; ctx.uColor = uColor; ctx.uUvScale = uUvScale
        ctx.uEmissive = uEmissive; ctx.uCut = uCut
        builds = Buildings(ctx)
        props = Props3D(ctx)
        actors = Actors(ctx)
        room = Room3D(ctx)
        buildStructures()

        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glFrontFace(GL_CCW)
        ready = true
    }

    fun onSurfaceChanged(w: Int, h: Int, qualityLevel: Int) {
        screenW = w; screenH = h
        quality = qualityLevel.coerceIn(0, 2)
        val target = when (quality) { 0 -> 432; 1 -> 648; else -> 900 }
        rtH = min(h, target)
        rtW = (rtH.toFloat() * w / h).toInt().coerceAtLeast(2)
        if (rtW % 2 == 1) rtW++
        rt?.release()
        rt = RenderTarget(rtW, rtH, smooth = true)

        uiH = min(h, 620)
        uiW = (uiH.toFloat() * w / h).toInt().coerceAtLeast(2)
        uiBitmap?.recycle()
        val bmp = Bitmap.createBitmap(uiW, uiH, Bitmap.Config.ARGB_8888)
        uiBitmap = bmp
        uiCanvas = Canvas(bmp)
        if (uiTexId != 0) glDeleteTextures(1, intArrayOf(uiTexId), 0)
        uiTexId = Gl.emptyTexture(uiW, uiH, smooth = true)

        // a narrow lens, filmed from further off: the yard reads as a diorama
        Matrix.perspectiveM(proj, 0, 34f, w.toFloat() / h, 0.35f, 190f)
    }

    fun onQualityChanged(qualityLevel: Int) {
        if (screenW > 1 && screenH > 1 && qualityLevel != quality) {
            onSurfaceChanged(screenW, screenH, qualityLevel)
        }
    }

    // ============================================================ structures

    private fun buildStructures() {
        var b = MeshBuilder()
        builds?.buildYardFence(b)
        fenceMesh = if (b.isEmpty) null else b.build()

        // the plank bridge over the creek, and its rails
        b = MeshBuilder()
        val rail = MeshBuilder()
        run {
            val cx = Terrain.creekX(Terrain.BRIDGE_Z)
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
                b.box((ax + bx) * 0.5f, ay - 0.16f, z0 + 0.06f, bx - ax, 0.16f, 0.12f, 0.9f)
                b.box((ax + bx) * 0.5f, ay - 0.16f, z1 - 0.06f, bx - ax, 0.16f, 0.12f, 0.9f)
            }
            rail.tint(0xFFFFFF, 0f)
            var i = 0
            while (i <= steps) {
                val px = cx - span + (i * 2f * span / steps)
                val py = Terrain.bridgeY(px)
                rail.box(px, py, Terrain.BRIDGE_Z - Terrain.BRIDGE_HALF_Z + 0.1f, 0.1f, 0.85f, 0.1f, 0.9f)
                rail.box(px, py, Terrain.BRIDGE_Z + Terrain.BRIDGE_HALF_Z - 0.1f, 0.1f, 0.85f, 0.1f, 0.9f)
                i += 3
            }
            for (side in intArrayOf(-1, 1)) {
                val pz = Terrain.BRIDGE_Z + side * (Terrain.BRIDGE_HALF_Z - 0.1f)
                var k = 0
                while (k < steps) {
                    val ax = cx - span + (k * 2f * span / steps)
                    val bx = cx - span + ((k + 1) * 2f * span / steps)
                    val my = (Terrain.bridgeY(ax) + Terrain.bridgeY(bx)) * 0.5f + 0.76f
                    rail.box((ax + bx) * 0.5f, my, pz, bx - ax, 0.08f, 0.08f, 0.9f, top = true, bottom = true)
                    // snow along the top of the handrail
                    rail.tint(0xE8EEF8, 0f)
                    rail.box((ax + bx) * 0.5f, my + 0.08f, pz, bx - ax, 0.06f, 0.11f, 0.9f, top = true, bottom = true)
                    rail.tint(0xFFFFFF, 0f)
                    k++
                }
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
        val ex = tx + sy * g.camDist
        val ez = tz + cy * g.camDist
        var ey = ty + g.camHeight + shy
        if (!g.player.indoors) {
            val floorY = Terrain.height(ex, ez) + 1.5f
            if (ey < floorY) ey = floorY
        }
        eye[0] = ex; eye[1] = ey; eye[2] = ez

        val lookY = ty + (if (g.player.indoors) 0.9f else 1.15f)
        Matrix.setLookAtM(view, 0, ex, ey, ez, tx, lookY, tz, 0f, 1f, 0f)
        fwd[0] = tx - ex; fwd[1] = lookY - ey; fwd[2] = tz - ez
        val len = sqrt(fwd[0] * fwd[0] + fwd[1] * fwd[1] + fwd[2] * fwd[2]).coerceAtLeast(1e-4f)
        fwd[0] /= len; fwd[1] /= len; fwd[2] /= len
        camToTarget = len
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
        return facing > 0.30f - radius / d
    }

    // ================================================================= frame

    fun drawFrame(g: Game) {
        val target = rt ?: return
        if (!ready) return

        val sky = g.sky
        val night = g.nightAmount()
        computeCamera(g)
        ctx.billboardYaw = g.camYaw

        gatherLights(g, night)
        lights.select(eye[0], eye[1], eye[2])

        target.bind()
        glDepthMask(true)
        glClear(GL_DEPTH_BUFFER_BIT)

        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDisable(GL_BLEND)
        if (g.player.indoors) {
            // no sky indoors: the gaps where the near walls have been taken
            // away should read as the dark of the rest of the house, not as
            // a hole cut through to the weather
            glClearColor(0.055f, 0.05f, 0.075f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
        } else {
            drawSky(g, sky, night)
        }

        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(true)

        setupWorldProgram(g, sky, night)

        if (g.player.indoors) drawInside(g) else drawOutside(g, sky, night)

        present(g, night)
        drawUiLayer(g)
    }

    // ---------------------------------------------------------------- light

    private fun gatherLights(g: Game, night: Float) {
        lights.begin()
        val lampsOn = g.lampAmount()
        val flick = 0.9f + sin(g.timeMs * 0.009f) * 0.1f

        if (g.player.indoors) {
            room?.lights(g, lights)
            return
        }

        // the cabin's own windows, which are the point of the whole picture
        if (lampsOn > 0.02f) {
            val cy = Terrain.height(World.CABIN_X, World.CABIN_Z)
            lights.add(World.CABIN_X - 1.25f, cy + 1.35f, World.CABIN_Z + 2.4f, Pal.windowWarm, 8.6f, 1.5f * lampsOn)
            lights.add(World.CABIN_X + 1.5f, cy + 1.1f, World.CABIN_Z + 3.1f, Pal.lampWarm, 5.0f, 0.7f * lampsOn)
        }
        // the glasshouse, lit from within
        run {
            val gy = Terrain.height(World.GLASS_X, World.GLASS_Z)
            lights.add(World.GLASS_X, gy + 1.3f, World.GLASS_Z, Pal.lampWarm, 9.0f, 0.55f + 0.6f * lampsOn)
        }
        // props
        for (p in World.props) {
            val power = World.propGlow(p.kind)
            if (power <= 0f) continue
            val y = Terrain.height(p.x, p.z)
            when (p.kind) {
                World.PKind.LANTERN -> if (lampsOn > 0.02f)
                    lights.add(p.x, y + 1.35f, p.z, Pal.lampWarm, 5.4f, 0.95f * lampsOn)
                World.PKind.FIREPIT -> if (g.st.firepitFuel > 0f)
                    lights.add(p.x, y + 0.6f, p.z, Pal.fireWarm, 9.4f, 1.6f * flick)
                World.PKind.BRAZIER ->
                    lights.add(p.x, y + 1.0f, p.z, Pal.fireWarm, 6.2f, 1.1f * flick)
                World.PKind.ICE_HUT -> if (lampsOn > 0.02f)
                    lights.add(p.x, Terrain.ICE_Y + 1.0f, p.z, Pal.windowWarm, 5.0f, 0.8f * lampsOn)
            }
        }
        // the stall's string of lamps
        if (lampsOn > 0.02f) {
            val my = Terrain.height(World.MARKET_X, World.MARKET_Z)
            lights.add(World.MARKET_X, my + 2.2f, World.MARKET_Z + 1.3f, Pal.lampWarm, 7.0f, 1.0f * lampsOn)
        }
        // and the lamp you are carrying
        if (lampsOn > 0.15f) {
            val p = g.player
            lights.add(
                p.x, p.y + 0.8f, p.z, Pal.lampWarm,
                com.cozyhollow.riverside.ToolUp.lampRadius(g.st.lanternLevel), 0.85f * lampsOn
            )
        }
    }

    private fun uploadLights() {
        glUniform4fv(uLightPos, Shaders.MAX_LIGHTS, lights.posBuf, 0)
        glUniform4fv(uLightCol, Shaders.MAX_LIGHTS, lights.colBuf, 0)
    }

    // --------------------------------------------------------------- setup

    private fun setupWorldProgram(g: Game, sky: MutableSkyKey, night: Float) {
        val day = 1f - night
        glUseProgram(worldProg)
        glUniformMatrix4fv(uProj, 1, false, proj, 0)
        glUniformMatrix4fv(uView, 1, false, view, 0)
        glUniform1f(uCurve, if (g.player.indoors) 0f else CURVE)
        glUniform1i(uTex, 0)
        glActiveTexture(GL_TEXTURE0)
        uploadLights()
        glUniform3f(uEye, eye[0], eye[1], eye[2])

        // The winter sun never climbs far. It comes in low from the south-west
        // and rakes across the drifts, which is what gives every rise its long
        // blue shadow.
        val minutes = g.st.timeMin % 1440f
        val sunT = U.norm(minutes, 440f, 1010f)
        val elev = U.lerp(0.16f, 0.46f, sin(sunT * 3.1416f))
        sunDir[0] = cos(sunT * 3.1416f) * 0.86f
        sunDir[1] = max(0.12f, elev)
        sunDir[2] = 0.36f
        val sl = sqrt(sunDir[0] * sunDir[0] + sunDir[1] * sunDir[1] + sunDir[2] * sunDir[2])
        sunDir[0] /= sl; sunDir[1] /= sl; sunDir[2] /= sl
        glUniform3f(uSunDir, sunDir[0], sunDir[1], sunDir[2])

        val sc = if (g.player.indoors) 0.10f else 0.22f + 0.66f * day
        glUniform3f(
            uSunCol,
            Color.red(sky.sunColor) / 255f * sc,
            Color.green(sky.sunColor) / 255f * sc,
            Color.blue(sky.sunColor) / 255f * sc
        )

        // Snow bounces almost everything back up again, so the fill from below
        // is nearly as strong as the fill from the sky. Without it the shaded
        // side of a drift goes black and the whole valley looks like ash.
        val fillUp = if (g.player.indoors) 0.10f else 0.24f + 0.24f * day
        glUniform3f(
            uSkyFill,
            Color.red(sky.mid) / 255f * fillUp,
            Color.green(sky.mid) / 255f * fillUp,
            Color.blue(sky.mid) / 255f * fillUp
        )
        val fillDown = if (g.player.indoors) 0.07f else 0.20f + 0.26f * day
        glUniform3f(uGroundFill, 0.72f * fillDown, 0.80f * fillDown, 0.96f * fillDown)

        val fogNear = drawDist * (if (g.st.weather == com.cozyhollow.riverside.Weather.BLIZZARD) 0.22f else 0.48f)
        glUniform2f(uFog, fogNear, drawDist * 1.12f)
        glUniform3f(
            uFogCol,
            Color.red(sky.horizon) / 255f, Color.green(sky.horizon) / 255f, Color.blue(sky.horizon) / 255f
        )
        glUniform1f(uTimeLoc, (g.timeMs * 0.001f) % 6283f)
        glUniform1f(uWind, g.windAmount())
        glUniform1f(uEmissive, 0f)
        glUniform1f(uCut, 0.45f)
        glUniform1f(uNear, 0f)
        glUniform1f(uSparkle, if (g.player.indoors || quality == 0) 0f else 0.35f + 0.65f * day)

        val glow = g.lampAmount()
        builds?.glow = glow
        props?.glow = glow
    }

    // ------------------------------------------------------------- outdoors

    private fun drawOutside(g: Game, sky: MutableSkyKey, night: Float) {
        drawChunks(g)
        drawShadows(g)
        glUniform1f(uNear, nearFade)
        drawStructures(g)
        drawProps(g)
        drawPlots(g)
        drawForage(g)
        drawTrees(g)
        glUniform1f(uNear, 0f)
        drawCharacters(g)
        if (g.fishing.active) drawHoleAndBobber(g)

        drawIce(g, sky)

        glUseProgram(worldProg)
        drawEffects(g, night)
    }

    private fun drawChunks(g: Game) {
        val t = tex ?: return
        val s = scene ?: return
        ms.identity()
        val dist = drawDist
        val half = Scenery.CHUNK * 0.75f
        for (ch in s.chunks) {
            if (!visible(ch.cx, ch.cz, half, dist)) continue
            ctx.bindAndDraw(ch.ground, t.snow)
        }
        glUniform1f(uNear, nearFade)
        for (ch in s.chunks) {
            if (!visible(ch.cx, ch.cz, half, dist)) continue
            ctx.bindAndDraw(ch.bark, t.bark)
            ctx.bindAndDraw(ch.leaf, t.needles)
            ctx.bindAndDraw(ch.rock, t.rock)
            ctx.bindAndDraw(ch.snow, t.snow)
        }
        if (quality > 0) {
            val near = dist * 0.6f
            for (ch in s.chunks) {
                if (!visible(ch.cx, ch.cz, half, near)) continue
                ctx.bindAndDraw(ch.detail, t.detail)
                ctx.bindAndDraw(ch.flower, t.flowers)
            }
        }
        glUniform1f(uNear, 0f)
    }

    private fun shadowAt(x: Float, z: Float, r: Float, alpha: Float) {
        val d = decal ?: return
        ms.identity()
        glBindTexture(GL_TEXTURE_2D, tex!!.shadow)
        glUniformMatrix4fv(uModel, 1, false, ms.m, 0)
        // shadows on snow are blue, not grey: they are lit by the sky alone
        glUniform4f(uColor, 0.30f, 0.38f, 0.58f, alpha)
        glUniform2f(uUvScale, 1f, 1f)
        d.draw(x, z, r, 0.05f, aPos, aNor, aUv, aCol)
    }

    /** Contact shadows, so nothing looks like it is hovering over the drift. */
    private fun drawShadows(g: Game) {
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glUniform1f(uCut, 0.01f)
        val a = 0.40f * (1f - g.nightAmount() * 0.55f)

        for (i in World.trees.indices) {
            val tr = World.trees[i]
            if (!visible(tr.x, tr.z, 2f, 28f)) continue
            if (World.treeStanding(g.st, i)) shadowAt(tr.x, tr.z, 1.4f * tr.scale, a * 0.8f)
            else shadowAt(tr.x, tr.z, 0.45f * tr.scale, a)
        }
        for (p in World.props) {
            if (p.kind == World.PKind.LANTERN || p.kind == World.PKind.SPRING) continue
            if (!visible(p.x, p.z, 3f, 26f)) continue
            val r = when (p.kind) {
                World.PKind.TRUCK -> 2.5f
                World.PKind.WOODSHED -> 2.4f
                World.PKind.SWING_TREE -> 1.6f
                World.PKind.ICE_HUT -> 1.6f
                else -> 0.8f
            }
            shadowAt(p.x, p.z, r, a * 0.9f)
        }
        for (i in World.forage.indices) {
            val f = World.forage[i]
            if (!World.forageAvailable(g.st, i)) continue
            if (!visible(f.x, f.z, 1f, 18f)) continue
            shadowAt(f.x, f.z, 0.26f, a)
        }
        if (visible(World.CABIN_X, World.CABIN_Z, 6f, 44f)) {
            shadowAt(World.CABIN_X, World.CABIN_Z, 3.6f + g.st.cabinLevel * 0.3f, a)
        }
        if (visible(World.GLASS_X, World.GLASS_Z, 6f, 44f)) shadowAt(World.GLASS_X, World.GLASS_Z, 4.2f, a * 0.7f)
        if (visible(World.MARKET_X, World.MARKET_Z, 4f, 44f)) shadowAt(World.MARKET_X, World.MARKET_Z, 3.0f, a)
        val p = g.player
        shadowAt(p.x, p.z, 0.42f, 0.5f)
        shadowAt(World.MARKET_X + 1.7f, World.MARKET_Z - 0.5f, 0.4f, a)

        glUniform1f(uCut, 0.45f)
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun drawStructures(g: Game) {
        val t = tex ?: return
        val b = builds ?: return
        ms.identity()
        if (visible(World.CABIN_X, World.CABIN_Z, 10f, drawDist)) {
            ctx.bindAndDraw(fenceMesh, t.planks, 0.86f, 0.84f, 0.86f)
        }
        val bx = Terrain.creekX(Terrain.BRIDGE_Z)
        if (visible(bx, Terrain.BRIDGE_Z, 9f, drawDist)) {
            ctx.bindAndDraw(deckMesh, t.plankWorn)
            ctx.bindAndDraw(railMesh, t.planks, 0.9f, 0.88f, 0.9f)
        }
        if (visible(World.CABIN_X, World.CABIN_Z, 7f, drawDist)) b.cabin(g, g.st.cabinLevel)
        if (visible(World.GLASS_X, World.GLASS_Z, 7f, drawDist)) b.glasshouse(g)
        if (visible(World.MARKET_X, World.MARKET_Z, 7f, drawDist)) b.stall(g)
        if (visible(World.SHED_X, World.SHED_Z, 6f, drawDist)) b.woodshed()
        if (visible(World.HUT_X, World.HUT_Z, 5f, drawDist)) b.iceHut(g)
    }

    private fun drawProps(g: Game) {
        val pr = props ?: return
        ms.identity()
        for (p in World.props) {
            if (!visible(p.x, p.z, 3f, drawDist * 0.85f)) continue
            val y = if (p.kind == World.PKind.ICE_HUT) Terrain.ICE_Y else Terrain.height(p.x, p.z)
            pr.draw(g, p, y)
        }
        // holes you have already cut in the ice stay all day
        for (i in 0 until g.holeCount()) {
            val hx = g.holeX(i)
            val hz = g.holeZ(i)
            if (!visible(hx, hz, 1f, 30f)) continue
            pr.iceHole(g, hx, hz)
        }
    }

    private fun drawPlots(g: Game) {
        val t = tex!!
        val open = g.st.tier.plots
        ms.identity()
        for (i in 0 until World.MAX_PLOTS) {
            if (i >= open) continue
            val x = World.plotX(i)
            val z = World.plotZ(i)
            if (!visible(x, z, 1f, 28f)) continue
            val plot = g.st.plots[i]
            val y = Terrain.height(x, z)
            // every bed has a raised timber edge: it is a glasshouse, after all
            ctx.box(x, y - 0.04f, z, 1.36f, 0.18f, 1.36f, t.planks, uvPerM = 1.2f, r = 0.82f, g = 0.78f, b = 0.72f)
            if (!plot.tilled) {
                ctx.slab(x, y + 0.1f, z, 1.2f, 0.05f, 1.2f, t.soil, 1.6f, 0.9f, 0.86f, 0.82f)
                continue
            }
            ctx.slab(x, y + 0.1f, z, 1.2f, 0.08f, 1.2f, if (plot.watered) t.tilledWet else t.tilled, 1.6f)
            val cropId = plot.cropId ?: continue
            val crop = Catalog.crops[cropId] ?: continue
            drawCrop(g, x, y + 0.18f, z, crop.id, U.clamp01(plot.growth / crop.days), plot.ready)
        }
    }

    private fun drawCrop(g: Game, x: Float, y: Float, z: Float, cropId: String, prog: Float, ready: Boolean) {
        val t = tex!!
        val item = Catalog.item(cropId)
        val sway = sin(g.timeMs * 0.0011f + x * 1.7f) * 1.6f * (0.4f + prog)
        val h = U.lerp(0.26f, 0.92f, U.easeOut(prog))
        ms.push().translate(x, y, z).rotateZ(sway)
        ctx.box(0f, 0f, 0f, 0.08f, h, 0.08f, t.leafGreen, uvPerM = 2f)
        val leaves = if (prog < 0.3f) 2 else 4
        for (k in 0 until leaves) {
            val ly = h * (0.25f + k * 0.2f)
            val dir = if (k % 2 == 0) 1f else -1f
            ms.push().translate(0f, ly, 0f).rotateZ(dir * 42f).scale(0.44f, 0.08f, 0.26f).translate(dir * 0.5f, 0f, 0f)
            ctx.bindAndDraw(prims?.box, t.leafGreen)
            ms.pop()
        }
        if (ready) {
            val fruitTex = t.solid(item.a)
            when (cropId) {
                "radish", "beet" -> ctx.blob(0f, 0.1f, 0f, 0.17f, fruitTex)
                "pepper" -> {
                    for (k in 0 until 3) {
                        ms.push().translate(-0.1f + k * 0.1f, h * (0.42f + k * 0.14f), 0.06f).rotateZ(18f)
                        ctx.cone(0f, 0f, 0f, 0.06f, 0.26f, fruitTex)
                        ms.pop()
                    }
                }
                "squash" -> ctx.blob(0.24f, 0.2f, 0.1f, 0.27f, fruitTex, squash = 0.82f)
                "moonbell" -> {
                    ctx.emissive(0.5f)
                    for (k in 0 until 3) {
                        ms.push().translate(-0.12f + k * 0.12f, h * (0.66f + k * 0.1f), 0.04f).rotateZ(180f)
                        ctx.cone(0f, 0f, 0f, 0.09f, 0.16f, fruitTex)
                        ms.pop()
                    }
                    ctx.emissive(0f)
                }
                else -> {
                    ctx.blob(-0.12f, h * 0.6f, 0.05f, 0.12f, fruitTex)
                    ctx.blob(0.13f, h * 0.78f, -0.03f, 0.11f, fruitTex)
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
            if (!visible(f.x, f.z, 1f, 26f)) continue
            val y = Terrain.groundY(f.x, f.z)
            val item = Catalog.item(f.itemId)
            val bob = sin(g.timeMs * 0.0018f + i * 1.4f) * 0.02f
            ms.push().translate(f.x, y + 0.02f + bob, f.z).rotateY((g.timeMs * 0.014f) % 360f)
            when (f.itemId) {
                "capmush" -> {
                    ctx.box(0f, 0f, 0f, 0.1f, 0.16f, 0.1f, t.solid(Color.parseColor("#E4D8C8")), uvPerM = 3f)
                    ctx.blob(0f, 0.2f, 0f, 0.17f, t.solid(item.a), squash = 0.72f)
                    ctx.blob(0f, 0.24f, 0f, 0.15f, t.snow, 1.02f, 1.05f, 1.12f, squash = 0.5f)
                }
                "snowdrop" -> {
                    ctx.box(0f, 0f, 0f, 0.045f, 0.28f, 0.045f, t.leafGreen, uvPerM = 4f)
                    ms.push().translate(0f, 0.32f, 0f).rotateZ(180f)
                    ctx.cone(0f, 0f, 0f, 0.09f, 0.14f, t.solid(item.a))
                    ms.pop()
                }
                "winterberry" -> {
                    ctx.box(0f, 0f, 0f, 0.05f, 0.3f, 0.05f, t.bark, uvPerM = 4f)
                    for (k in 0 until 5) {
                        val a = k * 1.26f
                        ctx.blob(cos(a) * 0.09f, 0.24f + (k % 2) * 0.07f, sin(a) * 0.09f, 0.048f, t.solid(item.a))
                    }
                }
                "kindling" -> {
                    for (k in 0 until 4) {
                        ms.push().rotateY(k * 44f)
                        ctx.logX(0f, 0.05f + k * 0.045f, 0f, 0.44f, 0.035f, t.bark, 0.86f, 0.8f, 0.74f)
                        ms.pop()
                    }
                }
                else -> {
                    // a pine cone, half buried
                    ms.push().scale(1f, 1.35f, 1f)
                    ctx.blob(0f, 0.09f, 0f, 0.1f, t.solid(item.a))
                    ms.pop()
                    ctx.blob(0f, 0.2f, 0f, 0.06f, t.solid(item.b))
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
            if (!visible(tr.x, tr.z, 3f, drawDist * 0.78f)) continue
            val y = Terrain.height(tr.x, tr.z)
            val standing = World.treeStanding(g.st, i)
            val s = tr.scale
            if (!standing) {
                ctx.cyl(tr.x, y, tr.z, 0.26f * s, 0.4f * s, t.bark, 0.78f, 0.74f, 0.7f)
                ctx.disc(tr.x, y + 0.4f * s + 0.01f, tr.z, 0.25f * s, t.logs, 0.9f, 0.82f, 0.72f)
                ctx.snowOn(tr.x, y + 0.42f * s, tr.z, 0.44f * s, 0.44f * s, 0.05f, 0f)
                continue
            }
            val shake = if (i == g.shakeTreeIndex && g.shakeAmount > 0f)
                sin(g.shakeAmount * 46f) * 2.6f * U.clamp01(g.shakeAmount * 3f) else 0f
            val sway = sin(g.timeMs * 0.0007f + i) * 0.5f
            ms.push().translate(tr.x, y, tr.z).rotateZ(shake + sway * 0.3f)
            when (tr.kind) {
                0 -> {
                    ctx.cyl(0f, -0.1f, 0f, 0.19f * s, 1.6f * s, t.bark, 0.74f, 0.7f, 0.68f)
                    for (k in 0 until 4) {
                        val cy = (0.9f + k * 1.0f) * s
                        val cr = (1.55f - k * 0.28f) * s
                        val ch = (1.8f - k * 0.16f) * s
                        ctx.cone(0f, cy, 0f, cr, ch, t.needles, 0.4f, 0.62f, 0.54f)
                        ctx.cone(0f, cy + ch * 0.3f, 0f, cr * 0.94f, ch * 0.6f, t.snow, 1.02f, 1.05f, 1.12f)
                    }
                }
                1 -> {
                    ctx.cyl(0f, -0.1f, 0f, 0.26f * s, 1.8f * s, t.bark, 0.66f, 0.62f, 0.6f)
                    for (k in 0 until 5) {
                        val yaw = k * 72f + 20f
                        ctx.limb(0f, 1.7f * s, 0f, 1.9f * s, 0.14f * s, yaw, 38f, t.bark, 0.66f, 0.62f, 0.6f)
                        ctx.limb(0f, 1.76f * s, 0f, 1.8f * s, 0.1f * s, yaw, 38f, t.snow, 1.02f, 1.05f, 1.12f)
                    }
                }
                else -> {
                    ctx.cyl(0f, -0.1f, 0f, 0.15f * s, 2.6f * s, t.barkBirch, 1.15f, 1.14f, 1.12f)
                    for (k in 0 until 4) {
                        val yaw = k * 90f + 32f
                        ctx.limb(0f, 2.2f * s, 0f, 1.3f * s, 0.06f * s, yaw, 32f, t.barkBirch, 1.1f, 1.1f, 1.08f)
                    }
                }
            }
            ms.pop()
        }
    }

    // ---------------------------------------------------------- characters

    private fun drawCharacters(g: Game) {
        val a = actors ?: return
        a.player(g)
        if (g.lampAmount() > 0.15f) a.playerLamp(g)
        if (visible(World.MARKET_X, World.MARKET_Z, 5f, 34f)) {
            a.pip(g, World.MARKET_X + 1.7f, Terrain.height(World.MARKET_X + 1.7f, World.MARKET_Z - 0.5f), World.MARKET_Z - 0.5f)
        }
        drawWildlife(g)
    }

    private fun drawWildlife(g: Game) {
        val a = actors ?: return
        // deer at the trough, once it has been filled
        if (g.st.deerFedDay == g.st.day && visible(World.DEER_X, World.DEER_Z, 6f, 34f)) {
            for (k in 0 until 2) {
                val ox = if (k == 0) -1.3f else 1.5f
                val oz = if (k == 0) 1.5f else 1.1f
                val x = World.DEER_X + ox
                val z = World.DEER_Z + oz
                a.deer(g, x, Terrain.height(x, z), z, 200f + k * 24f, k)
            }
        }
        // chickadees round the feeder
        if (g.st.birdFedDay == g.st.day && visible(World.BIRD_X, World.BIRD_Z, 5f, 26f)) {
            for (k in 0 until 4) {
                val t = g.timeMs * 0.0006f + k * 1.6f
                val r = 0.55f + (k % 2) * 0.35f
                val x = World.BIRD_X + cos(t) * r
                val z = World.BIRD_Z + sin(t * 1.3f) * r
                val hop = abs(sin(t * 3f))
                val y = Terrain.height(x, z) + 1.55f + hop * 0.28f
                a.bird(g, x, y, z, Math.toDegrees(t.toDouble()).toFloat(), hop)
            }
        }
    }

    private fun drawHoleAndBobber(g: Game) {
        val t = tex!!
        val f = g.fishing
        if (f.phase == FPhase.IDLE) return
        val bx = f.bobX
        val bz = f.bobZ
        val by = Terrain.ICE_Y + 0.06f + f.dip(g.timeMs)
        ms.identity()
        ctx.blob(bx, by, bz, 0.07f, t.solid(Color.parseColor("#E4E0D2")))
        ctx.blob(bx, by + 0.06f, bz, 0.065f, t.solid(Color.parseColor("#D0707A")))
        // the line, from the rod tip down to the hole
        val p = g.player
        val rad = Math.toRadians(p.yaw.toDouble())
        val tipX = p.x + sin(rad).toFloat() * 0.7f
        val tipZ = p.z + cos(rad).toFloat() * 0.7f
        val tipY = p.y + 1.5f
        val steps = 5
        for (k in 0 until steps) {
            val t0 = k / steps.toFloat()
            val t1 = (k + 1) / steps.toFloat()
            val ax = U.lerp(tipX, bx, t0); val az = U.lerp(tipZ, bz, t0)
            val ay = U.lerp(tipY, by, t0)
            val bx2 = U.lerp(tipX, bx, t1); val bz2 = U.lerp(tipZ, bz, t1)
            val by2 = U.lerp(tipY, by, t1)
            ctx.box(
                (ax + bx2) * 0.5f, (ay + by2) * 0.5f, (az + bz2) * 0.5f,
                0.012f, abs(by2 - ay).coerceAtLeast(0.02f), 0.012f, t.white,
                uvPerM = 4f, r = 0.9f, g = 0.94f, b = 1f
            )
        }
    }

    // --------------------------------------------------------------- ice

    private fun drawIce(g: Game, sky: MutableSkyKey) {
        val s = scene ?: return
        val w = s.ice ?: return
        val t = tex ?: return
        val day = 1f - g.nightAmount()
        glUseProgram(iceProg)
        glUniformMatrix4fv(wProj, 1, false, proj, 0)
        glUniformMatrix4fv(wView, 1, false, view, 0)
        glUniform1f(wCurve, CURVE)
        glUniform1f(wTime, (g.timeMs * 0.001f) % 6283f)
        glUniform3f(wCamPos, eye[0], eye[1], eye[2])
        glUniform2f(wFog, drawDist * 0.48f, drawDist * 1.12f)
        glUniform3f(
            wFogCol,
            Color.red(sky.horizon) / 255f, Color.green(sky.horizon) / 255f, Color.blue(sky.horizon) / 255f
        )
        val lift = 0.34f + day * 0.66f
        glUniform3f(wShallow, 0.44f * lift, 0.60f * lift, 0.72f * lift)
        glUniform3f(wDeep, 0.10f * lift, 0.20f * lift, 0.32f * lift)
        glUniform3f(wSky, Color.red(sky.mid) / 255f, Color.green(sky.mid) / 255f, Color.blue(sky.mid) / 255f)
        glUniform3f(
            wSun,
            Color.red(sky.sunColor) / 255f * day, Color.green(sky.sunColor) / 255f * day,
            Color.blue(sky.sunColor) / 255f * day
        )
        glUniform3f(wSunDir, sunDir[0], sunDir[1], sunDir[2])
        val snowLift = 0.30f + day * 0.62f
        glUniform3f(wSnow, 0.86f * snowLift, 0.90f * snowLift, 1.0f * snowLift)
        glUniform4fv(wLightPos, Shaders.MAX_LIGHTS, lights.posBuf, 0)
        glUniform4fv(wLightCol, Shaders.MAX_LIGHTS, lights.colBuf, 0)
        glUniform1i(wTex, 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, t.ice)

        w.bind(wAPos, wANor, wAUv, wACol)
        w.draw()
    }

    // ------------------------------------------------------------- indoors

    private fun drawInside(g: Game) {
        val r = room ?: return
        val a = actors ?: return
        ms.identity()
        r.shell(g, eye[0], eye[1], eye[2])
        r.furniture(g)
        // Mitten, asleep in front of the fire unless you have just woken her
        val awake = g.catAwake
        a.cat(
            g, Interior.HEARTH_X - 1.05f, 0f, Interior.HEARTH_Z + 1.32f,
            -28f, awake
        )
        a.player(g)

        glUseProgram(worldProg)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glUniform1f(uCut, 0.01f)
        glUniform1f(uEmissive, 1f)
        val t = tex!!
        val lit = g.st.hearthLit && g.st.hearthFuel > 0f
        if (lit) {
            val flick = 0.85f + sin(g.timeMs * 0.009f) * 0.15f
            ctx.billboard(
                Interior.HEARTH_X, 0.7f, Interior.HEARTH_Z + 0.4f, 3.4f * flick,
                t.glow, 1f, 0.72f, 0.4f, 0.36f
            )
        }
        ctx.billboard(-0.85f, 1.0f, 0.4f, 1.6f, t.glow, 1f, 0.84f, 0.55f, 0.3f)
        glUniform1f(uEmissive, 0f)
        drawParticles(g)
        drawHint(g)
        glUniform1f(uCut, 0.45f)
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

        val lamps = g.lampAmount()
        if (lamps > 0.05f) {
            for (p in World.props) {
                val glowY: Float
                val size: Float
                when (p.kind) {
                    World.PKind.LANTERN -> { glowY = 1.3f; size = 2.6f }
                    World.PKind.BRAZIER -> { glowY = 0.9f; size = 3.6f }
                    else -> continue
                }
                if (!visible(p.x, p.z, 3f, drawDist)) continue
                ctx.billboard(
                    p.x, Terrain.height(p.x, p.z) + glowY, p.z, size,
                    t.glow, 1f, 0.82f, 0.5f, 0.40f * lamps
                )
            }
            // the cabin windows, blooming into the dark
            val cy = Terrain.height(World.CABIN_X, World.CABIN_Z)
            if (visible(World.CABIN_X, World.CABIN_Z, 8f, drawDist)) {
                ctx.billboard(
                    World.CABIN_X - 1.25f, cy + 1.35f, World.CABIN_Z + 2.5f, 3.4f,
                    t.glow, 1f, 0.76f, 0.44f, 0.40f * lamps
                )
            }
        }
        // the yard fire burns whenever it has been fed
        if (g.st.firepitFuel > 0f && visible(World.FIRE_X, World.FIRE_Z, 3f, drawDist)) {
            val flick = 0.85f + sin(g.timeMs * 0.009f) * 0.15f
            ctx.billboard(
                World.FIRE_X, Terrain.height(World.FIRE_X, World.FIRE_Z) + 0.6f, World.FIRE_Z,
                5.2f * flick, t.glow, 1f, 0.7f, 0.36f, 0.42f
            )
        }
        // the brazier at the stall never goes out
        run {
            val bx = World.MARKET_X + 3.1f
            val bz = World.MARKET_Z + 1.6f
            if (visible(bx, bz, 3f, drawDist)) {
                ctx.billboard(bx, Terrain.height(bx, bz) + 0.9f, bz, 2.6f, t.glow, 1f, 0.72f, 0.38f, 0.34f)
            }
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

    private fun chimneySmoke(g: Game) {
        val t = tex!!
        val level = g.st.cabinLevel
        val x = World.CABIN_X
        val z = World.CABIN_Z
        if (!visible(x, z, 8f, drawDist)) return
        if (!(g.st.hearthLit && g.st.hearthFuel > 0f)) return
        val baseY = Terrain.height(x, z)
        val topY = baseY + when (level) { 1 -> 5.3f; 2 -> 5.8f; 3 -> 7.4f; else -> 7.9f }
        val cx = x + when (level) { 1 -> 2.2f; 2 -> 2.5f; 3 -> 2.7f; else -> 2.9f }
        val cz = z - when (level) { 1 -> 2.25f; 2 -> 2.45f; 3 -> 2.55f; else -> 2.65f }
        for (i in 0 until 8) {
            val ph = ((g.timeMs * 0.00016f) + i * 0.125f) % 1f
            val a = (1f - ph) * 0.34f
            val s = 0.34f + ph * 2.2f
            ctx.billboard(
                cx + g.particles.windX * ph * 2.2f + sin(ph * 5f + i) * 0.5f * ph,
                topY + ph * 4.4f,
                cz + g.particles.windZ * ph * 2.2f,
                s, t.cloud, 0.94f, 0.95f, 0.98f, a
            )
        }
    }

    private fun drawClouds(g: Game) {
        if (quality < 1) return
        val t = tex!!
        val n = if (quality >= 2) 14 else 9
        for (i in 0 until n) {
            val speed = 0.5f + U.hash(i * 11 + 5) * 0.5f
            var off = (U.hash(i * 37 + 3) + g.timeMs * 0.0000055f * speed) % 1f
            if (off < 0f) off += 1f
            val ang = off * 6.2832f
            val rr = 74f + U.hash(i * 29 + 9) * 45f
            val x = cos(ang) * rr
            val z = sin(ang) * rr
            val y = 20f + U.hash(i * 61 + 5) * 14f
            val sc = 14f + U.hash(i * 53 + 7) * 18f
            val a = 0.34f + U.hash(i * 17 + 1) * 0.24f
            ms.identity().translate(x, y, z)
                .rotateY(Math.toDegrees((-ang + 1.5708f).toDouble()).toFloat())
                .scale(sc, sc * 0.42f, 1f).translate(0f, -0.5f, 0f)
            ctx.bindAndDraw(prims?.quad, t.cloud, 0.94f, 0.96f, 1f, a)
        }
    }

    private fun drawParticles(g: Game) {
        val t = tex!!
        val p = g.particles
        for (i in p.life.indices) {
            if (p.life[i] <= 0f) continue
            val a = p.alphaOf(i)
            if (a <= 0.02f) continue
            if (!g.player.indoors && !visible(p.px[i], p.pz[i], 1f, drawDist)) continue
            val s = p.sizeOf(i)
            val c = p.col[i]
            val r = Color.red(c) / 255f
            val gg = Color.green(c) / 255f
            val bb = Color.blue(c) / 255f
            when (p.kind[i]) {
                P3.RING -> {
                    ms.identity().translate(p.px[i], p.py[i], p.pz[i]).scale(s * 2f, 1f, s * 2f)
                    ctx.bindAndDraw(prims?.flat, t.ring, r, gg, bb, a)
                }
                P3.PRINT -> {
                    ms.identity().translate(p.px[i], p.py[i], p.pz[i])
                        .rotateY(Math.toDegrees(p.phase[i].toDouble()).toFloat())
                        .scale(s * 2f, 1f, s * 2.6f)
                    ctx.bindAndDraw(prims?.flat, t.boot9, 0.55f, 0.64f, 0.86f, a)
                }
                P3.SNOW, P3.DRIFT -> ctx.billboard(p.px[i], p.py[i], p.pz[i], s * 2.4f, t.flake, r, gg, bb, a)
                P3.STEAM, P3.BREATH -> ctx.billboard(p.px[i], p.py[i], p.pz[i], s * 2.6f, t.cloud, r, gg, bb, a)
                P3.EMBER -> ctx.billboard(p.px[i], p.py[i], p.pz[i], s * 5f, t.glow, r, gg, bb, a)
                else -> ctx.billboard(p.px[i], p.py[i], p.pz[i], s * 2f, t.dot, r, gg, bb, a)
            }
        }
    }

    /** A bobbing marker over whatever the action button will act on. */
    private fun drawHint(g: Game) {
        val hx = g.hintTargetX()
        if (hx.isNaN()) return
        val hz = g.hintTargetZ()
        val bob = sin(g.timeMs * 0.006f) * 0.1f
        val ground = if (g.player.indoors) Interior.FLOOR_Y else Terrain.groundY(hx, hz)
        ms.identity()
            .translate(hx, ground + g.hintHeight() + 0.5f + bob, hz)
            .rotateZ(180f).scale(0.3f, 0.34f, 0.3f)
        ctx.bindAndDraw(prims?.cone, tex!!.solid(Color.parseColor("#FFE7B4")), 1f, 1f, 1f, 0.92f)
    }

    // ------------------------------------------------------------ present

    private fun present(g: Game, night: Float) {
        val target = rt ?: return
        val day = 1f - night
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenW, screenH)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        glUseProgram(blitProg)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, target.color)
        glUniform1i(blitTex, 0)
        if (g.player.indoors) {
            glUniform3f(blitGrade, 1.06f, 1.00f, 0.94f)
            glUniform3f(blitShadow, 0.86f, 0.84f, 0.94f)
        } else {
            glUniform3f(blitGrade, U.lerp(0.92f, 1.00f, day), U.lerp(0.95f, 1.00f, day), U.lerp(1.10f, 1.04f, day))
            // the shadows go blue, hard: it is the whole colour story of the game
            glUniform3f(blitShadow, U.lerp(0.62f, 0.80f, day), U.lerp(0.72f, 0.87f, day), U.lerp(1.05f, 1.06f, day))
        }
        glUniform1f(blitVig, 0.46f)
        glUniform1f(blitBloom, if (quality == 0) 0f else U.lerp(1.1f, 0.55f, day))
        glUniform2f(blitTexel, 1f / rtW, 1f / rtH)
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
        glUniform1f(skyTime, (g.timeMs * 0.001f) % 6283f)
        glUniform1f(skyHaze, 0.3f + sky.haze)
        glUniform1f(skyAurora, g.auroraAmount())

        val m = g.st.timeMin % 1440f
        val sunUp = m in 430f..1020f
        val t = if (sunUp) U.norm(m, 440f, 1010f) else U.norm(if (m > 1000f) m - 1000f else m + 440f, 0f, 860f)
        // the sun tracks the same arc the light comes from, so shadows agree
        val sx = U.lerp(0.84f, 0.16f, t)
        val sy = 0.24f + sin(t * 3.1416f) * 0.28f
        glUniform2f(skySun, sx, sy)
        if (sunUp) {
            glUniform3f(skySunCol, Color.red(sky.sunColor) / 255f, Color.green(sky.sunColor) / 255f, Color.blue(sky.sunColor) / 255f)
            glUniform3f(skySunGlow, Color.red(sky.sunGlow) / 255f * 0.6f, Color.green(sky.sunGlow) / 255f * 0.6f, Color.blue(sky.sunGlow) / 255f * 0.6f)
            glUniform1f(skySunSize, 0.05f)
        } else {
            // the moon, which in a hard winter is brighter than the sun ever is
            glUniform3f(skySunCol, 0.96f, 0.96f, 0.92f)
            glUniform3f(skySunGlow, 0.24f, 0.30f, 0.48f)
            glUniform1f(skySunSize, 0.034f)
        }
        drawFullQuad(skyAPos)
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
        /** How hard the world curves away toward the horizon. */
        private const val CURVE = 0.0028f
    }
}
