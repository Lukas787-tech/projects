package com.cozyhollow.riverside.gl

import com.cozyhollow.riverside.Game
import com.cozyhollow.riverside.Terrain
import com.cozyhollow.riverside.U
import com.cozyhollow.riverside.World
import kotlin.math.sin

/**
 * Everything with a roof on it.
 *
 * The cabin is the picture the whole game is composed around: dark log walls,
 * a steep near-black roof carrying a foot of snow, a porch, a stone chimney
 * with smoke coming off it, and one big window burning orange in all that
 * blue. Every other building here is a variation on the same three materials.
 */
class Buildings(private val d: DrawCtx) {

    /** How lit the windows are right now, 0..1. Set once per frame. */
    var glow = 1f

    private val t: Textures get() = d.tex!!

    // ------------------------------------------------------------- cabin

    fun cabin(g: Game, level: Int) {
        val x = World.CABIN_X
        val z = World.CABIN_Z
        val y = Terrain.height(x, z)
        when (level) {
            1 -> cabinBody(g, x, y, z, 6.2f, 2.45f, 4.4f, storeys = 1, dormer = false)
            2 -> cabinBody(g, x, y, z, 6.8f, 2.65f, 4.8f, storeys = 1, dormer = true)
            3 -> cabinBody(g, x, y, z, 7.2f, 2.8f, 5.0f, storeys = 2, dormer = false)
            else -> cabinBody(g, x, y, z, 7.6f, 2.9f, 5.2f, storeys = 2, dormer = true)
        }
    }

    private fun cabinBody(
        g: Game, x: Float, y: Float, z: Float,
        w: Float, h: Float, dp: Float, storeys: Int, dormer: Boolean
    ) {
        val hw = w * 0.5f
        val hd = dp * 0.5f
        val wallTop = y + h * storeys + (if (storeys > 1) 0.18f else 0f)

        // ---- footings: rough stone, banked with drift on the north side ----
        d.slab(x, y - 0.34f, z, w + 0.36f, 0.42f, dp + 0.36f, t.stone, 0.7f, 0.86f, 0.88f, 0.94f)

        // ---- walls ----
        d.box(x, y, z, w, h, dp, t.logs, uvPerM = 0.42f)
        if (storeys > 1) {
            // a band of planking between the courses, so the second storey reads
            d.box(x, y + h, z, w + 0.1f, 0.18f, dp + 0.1f, t.plankWorn, uvPerM = 1.2f)
            d.box(x, y + h + 0.18f, z, w, h * 0.86f, dp, t.logs, uvPerM = 0.42f)
        }

        // ---- corner posts, the way a log cabin is actually notched ----
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            d.box(
                x + sx * (hw - 0.06f), y, z + sz * (hd - 0.06f),
                0.30f, wallTop - y + 0.1f, 0.30f, t.bark, uvPerM = 0.9f, r = 0.8f, g = 0.76f, b = 0.74f
            )
        }

        // ---- the roof ----
        val rw = w + 1.2f
        val rd = dp + 1.1f
        val rh = (dp + 1.1f) * 0.48f
        d.box(x, wallTop, z, w + 0.3f, 0.16f, dp + 0.3f, t.bark, uvPerM = 1.0f, r = 0.7f, g = 0.66f, b = 0.64f)
        d.roofAt(x, wallTop + 0.16f, z, rw, rh, rd, t.roofDark)
        // the snow load: a hair narrower so the slate shows at the ridge and eave
        d.roofAt(x, wallTop + 0.30f, z, rw - 0.34f, rh - 0.20f, rd - 0.18f, t.roofSnow, 1.02f, 1.05f, 1.12f)
        // and a lip of snow overhanging the eaves
        for (sz in intArrayOf(-1, 1)) {
            d.slab(
                x, wallTop + 0.10f, z + sz * (rd * 0.5f - 0.06f),
                rw + 0.06f, 0.10f, 0.22f, t.snow, 0.9f, 1.02f, 1.05f, 1.12f
            )
        }

        if (dormer) {
            val dz = z + hd * 0.42f
            d.box(x - w * 0.16f, wallTop + rh * 0.34f, dz, 1.9f, 1.05f, 1.7f, t.plankWorn, uvPerM = 1.1f)
            d.roofAt(x - w * 0.16f, wallTop + rh * 0.34f + 1.05f, dz, 2.2f, 0.72f, 2.0f, t.roofDark)
            d.roofAt(x - w * 0.16f, wallTop + rh * 0.34f + 1.16f, dz, 1.94f, 0.6f, 1.84f, t.roofSnow, 1.02f, 1.05f, 1.12f)
            window(x - w * 0.16f, wallTop + rh * 0.34f + 0.34f, dz + 0.87f, 0.72f, 0.62f)
        }

        // ---- the porch, on the south face ----
        porch(x + 0.5f, y, z + hd, 3.6f, 1.9f)

        // ---- door and windows ----
        door(x + 1.5f, y, z + hd + 0.02f, 1.05f, 1.95f)
        // the big one: this is the orange rectangle the frame is built around
        window(x - 1.25f, y + 0.85f, z + hd + 0.02f, 1.35f, 1.15f)
        window(x - hw - 0.02f, y + 0.95f, z - 0.6f, 0.95f, 0.95f, faceX = true, sign = -1f)
        if (storeys > 1) {
            window(x - 1.4f, y + h + 0.72f, z + hd + 0.02f, 1.0f, 0.9f)
            window(x + 0.6f, y + h + 0.72f, z + hd + 0.02f, 1.0f, 0.9f)
        }

        // ---- the chimney ----
        val cxp = x + hw - 0.9f
        val czp = z - hd - 0.05f
        val top = wallTop + rh + 0.9f
        d.box(cxp, y, czp, 0.95f, top - y, 0.65f, t.stone, uvPerM = 0.9f)
        d.slab(cxp, top, czp, 1.15f, 0.14f, 0.85f, t.stone, 1f, 0.8f, 0.82f, 0.88f)
        d.snowOn(cxp, top + 0.14f, czp, 1.15f, 0.85f, 0.10f, 0.02f)
        // icicles along the eave, on the shaded side
        icicles(x, wallTop + 0.06f, z - rd * 0.5f + 0.1f, rw * 0.8f, 7)
    }

    private fun porch(x: Float, y: Float, z: Float, w: Float, dp: Float) {
        val zc = z + dp * 0.5f
        // steps down into the snow
        d.slab(x, y - 0.12f, zc + dp * 0.5f + 0.16f, w * 0.42f, 0.14f, 0.34f, t.plankWorn, 1.1f)
        d.slab(x, y + 0.02f, zc, w, 0.20f, dp, t.plankWorn, 1.1f)
        // a scuff of trodden snow on the boards
        d.slab(x - w * 0.2f, y + 0.22f, zc, w * 0.5f, 0.03f, dp * 0.7f, t.snow, 0.9f, 1.0f, 1.03f, 1.1f)
        for (s in intArrayOf(-1, 1)) {
            val px = x + s * (w * 0.5f - 0.14f)
            d.box(px, y + 0.22f, zc + dp * 0.5f - 0.14f, 0.16f, 2.1f, 0.16f, t.planks, uvPerM = 1.1f)
            // a simple rail between post and wall
            d.box(px, y + 0.92f, zc, 0.09f, 0.09f, dp - 0.28f, t.planks, uvPerM = 1.4f)
            for (k in 0 until 3) {
                d.box(px, y + 0.24f, zc - dp * 0.28f + k * dp * 0.28f, 0.06f, 0.7f, 0.06f, t.planks, uvPerM = 1.4f)
            }
        }
        // porch roof, with its own little load of snow
        d.slab(x, y + 2.32f, zc, w + 0.4f, 0.14f, dp + 0.3f, t.roofDark, 1.1f)
        d.snowOn(x, y + 2.46f, zc, w + 0.4f, dp + 0.3f, 0.10f, 0.03f)
    }

    /**
     * A window. The lit texture is drawn emissive so it stays bright no matter
     * what the sun is doing, which is the point of it.
     */
    fun window(
        wx: Float, wy: Float, wz: Float, w: Float, h: Float,
        faceX: Boolean = false, sign: Float = 1f
    ) {
        val lit = glow > 0.05f
        d.emissive(if (lit) glow else 0f)
        d.ms.push().translate(wx, wy, wz)
        if (faceX) d.ms.rotateY(90f * sign)
        d.ms.scale(w, h, 1f)
        d.bindAndDraw(d.prims?.quad, if (lit) t.windowLit else t.window)
        d.ms.pop()
        d.emissive(0f)
        // a sill, with snow on it
        val sx = if (faceX) 0.16f else w + 0.2f
        val sz = if (faceX) w + 0.2f else 0.16f
        d.slab(
            wx + (if (faceX) sign * 0.06f else 0f), wy - 0.1f, wz + (if (faceX) 0f else 0.06f),
            sx, 0.09f, sz, t.planks, 1.2f, 0.8f, 0.78f, 0.76f
        )
        d.snowOn(
            wx + (if (faceX) sign * 0.06f else 0f), wy - 0.01f, wz + (if (faceX) 0f else 0.06f),
            sx, sz, 0.05f, 0.01f
        )
    }

    fun door(dx: Float, dy: Float, dz: Float, w: Float, h: Float) {
        d.ms.push().translate(dx, dy, dz).scale(w, h, 1f)
        d.bindAndDraw(d.prims?.quad, t.door)
        d.ms.pop()
        d.box(dx, dy + h, dz - 0.06f, w + 0.24f, 0.12f, 0.2f, t.planks, uvPerM = 1.2f, r = 0.8f, g = 0.78f, b = 0.76f)
    }

    /** A row of icicles hanging off an edge. */
    fun icicles(x: Float, y: Float, z: Float, width: Float, n: Int) {
        for (k in 0 until n) {
            val fx = x - width * 0.5f + (k + 0.5f) * width / n
            val len = 0.16f + U.hash(k * 977 + 31) * 0.34f
            d.ms.push().translate(fx, y, z).rotateZ(180f).scale(0.075f, len, 0.075f)
            d.bindAndDraw(d.prims?.cone, t.white, 0.86f, 0.94f, 1.05f, 0.9f)
            d.ms.pop()
        }
    }

    // -------------------------------------------------------- glasshouse

    /**
     * The glasshouse: the only green thing left in the valley, lit from inside
     * so it sits in the snow like a lantern with vegetables in it.
     */
    fun glasshouse(g: Game) {
        val x = World.GLASS_X
        val z = World.GLASS_Z
        val y = Terrain.height(x, z)
        val hw = World.GLASS_HALF
        val wallH = 1.95f

        // stone kerb
        d.slab(x, y - 0.22f, z, hw * 2f + 0.3f, 0.34f, hw * 2f + 0.3f, t.stone, 0.8f, 0.84f, 0.86f, 0.92f)

        d.emissive(0.28f + glow * 0.42f)
        // four walls of glass, with the doorway left open in the south one
        d.box(x, y, z - hw + 0.1f, hw * 2f, wallH, 0.16f, t.glass, uvPerM = 0.55f)
        d.box(x - hw + 0.1f, y, z, 0.16f, wallH, hw * 2f, t.glass, uvPerM = 0.55f)
        d.box(x + hw - 0.1f, y, z, 0.16f, wallH, hw * 2f, t.glass, uvPerM = 0.55f)
        d.box(x - hw * 0.62f - 0.1f, y, z + hw - 0.1f, hw * 1.24f - 0.2f, wallH, 0.16f, t.glass, uvPerM = 0.55f)
        d.box(x + hw * 0.62f + 0.1f, y, z + hw - 0.1f, hw * 1.24f - 0.2f, wallH, 0.16f, t.glass, uvPerM = 0.55f)
        // the roof
        d.roofAt(x, y + wallH, z, hw * 2f + 0.4f, 1.5f, hw * 2f + 0.4f, t.glass)
        d.emissive(0f)

        // the frame that holds it all together
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            d.box(x + sx * (hw - 0.08f), y, z + sz * (hw - 0.08f), 0.18f, wallH + 0.1f, 0.18f, t.planks, uvPerM = 1.2f)
        }
        d.box(x, y + wallH, z, hw * 2f + 0.4f, 0.12f, 0.14f, t.planks, uvPerM = 1.2f)
        d.box(x, y + wallH + 1.44f, z, hw * 2f + 0.5f, 0.14f, 0.16f, t.planks, uvPerM = 1.2f)
        // glazing bars, so the roof is not one blank sheet
        for (k in -2..2) {
            d.box(x + k * hw * 0.42f, y + wallH, z, 0.09f, 1.5f, hw * 2f + 0.4f, t.planks, uvPerM = 1.1f, r = 0.9f, g = 0.9f, b = 0.92f)
        }
        // snow that has slid down and caught along the eaves
        for (sz in intArrayOf(-1, 1)) {
            d.slab(x, y + wallH + 0.02f, z + sz * (hw + 0.14f), hw * 2f + 0.4f, 0.12f, 0.3f, t.snow, 0.9f, 1.02f, 1.05f, 1.12f)
        }
        // and patches that never slid off at all
        d.slab(x - hw * 0.5f, y + wallH + 0.62f, z + hw * 0.42f, hw * 0.8f, 0.09f, hw * 0.5f, t.snow, 0.9f, 1.02f, 1.05f, 1.12f)
        d.slab(x + hw * 0.55f, y + wallH + 0.5f, z - hw * 0.5f, hw * 0.7f, 0.09f, hw * 0.6f, t.snow, 0.9f, 1.02f, 1.05f, 1.12f)

        // the door frame
        d.box(x - 1.05f, y, z + hw - 0.1f, 0.14f, wallH, 0.22f, t.planks, uvPerM = 1.2f)
        d.box(x + 1.05f, y, z + hw - 0.1f, 0.14f, wallH, 0.22f, t.planks, uvPerM = 1.2f)
        d.box(x, y + wallH - 0.14f, z + hw - 0.1f, 2.24f, 0.14f, 0.22f, t.planks, uvPerM = 1.2f)
    }

    // ------------------------------------------------------------- stall

    /** Pip's stall: a plank counter, a canvas roof, and a brazier beside it. */
    fun stall(g: Game) {
        val x = World.MARKET_X
        val z = World.MARKET_Z
        val y = Terrain.height(x, z)
        d.box(x, y, z + 0.7f, 4.8f, 0.9f, 0.8f, t.planks, uvPerM = 1.0f)
        d.slab(x, y + 0.9f, z + 0.7f, 5.1f, 0.12f, 1.05f, t.plankWorn, 1.1f)
        for (px in floatArrayOf(-2.4f, 2.4f)) {
            d.box(x + px, y, z + 0.95f, 0.18f, 2.6f, 0.18f, t.planks, uvPerM = 1.1f)
            d.box(x + px, y, z - 0.85f, 0.18f, 2.6f, 0.18f, t.planks, uvPerM = 1.1f)
        }
        d.roofAt(x, y + 2.6f, z + 0.05f, 5.1f, 1.0f, 2.4f, t.awning)
        d.roofAt(x, y + 2.72f, z + 0.05f, 4.8f, 0.88f, 2.2f, t.snow, 1.02f, 1.05f, 1.12f)
        d.box(x, y + 2.25f, z + 1.24f, 5.1f, 0.35f, 0.1f, t.awning, closed = true, uvPerM = 1.1f)
        // the sign board
        d.box(x - 0.7f, y + 3.6f, z, 0.1f, 0.3f, 0.1f, t.planks, uvPerM = 1.2f)
        d.box(x + 0.7f, y + 3.6f, z, 0.1f, 0.3f, 0.1f, t.planks, uvPerM = 1.2f)
        d.box(x, y + 3.87f, z, 2.2f, 0.64f, 0.14f, t.plankWorn, closed = true, uvPerM = 1.2f)
        d.snowOn(x, y + 4.51f, z, 2.2f, 0.14f, 0.07f, 0.02f)
        crate(x - 1.5f, y + 1.02f, z + 0.7f, "#C8546A")
        crate(x - 0.2f, y + 1.02f, z + 0.7f, "#E8973E")
        crate(x + 1.1f, y + 1.02f, z + 0.7f, "#4E7A56")
        // strings of lamps along the front rail
        for (k in 0 until 5) {
            val lx = x - 2.0f + k * 1.0f
            val sag = sin((k + 0.5f) / 5f * 3.1416f) * 0.22f
            d.emissive(glow)
            d.blob(lx, y + 2.16f - sag, z + 1.3f, 0.075f, t.lantern)
            d.emissive(0f)
        }
    }

    private fun crate(x: Float, y: Float, z: Float, produce: String) {
        val col = android.graphics.Color.parseColor(produce)
        d.box(x, y, z, 0.64f, 0.5f, 0.58f, t.crate, closed = true, uvPerM = 1.7f)
        d.blob(x - 0.14f, y + 0.6f, z, 0.12f, t.solid(col))
        d.blob(x + 0.15f, y + 0.58f, z + 0.05f, 0.105f, t.solid(col))
        d.blob(x, y + 0.72f, z - 0.05f, 0.1f, t.solid(col))
    }

    // ---------------------------------------------------------- woodshed

    /** An open-fronted shed with a mono-pitch roof and the winter's wood in it. */
    fun woodshed() {
        val x = World.SHED_X
        val z = World.SHED_Z
        val y = Terrain.height(x, z)
        val w = 4.0f
        val dp = 3.0f
        // four posts
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            val h = if (sz < 0) 2.6f else 2.05f
            d.box(x + sx * (w * 0.5f - 0.14f), y, z + sz * (dp * 0.5f - 0.14f), 0.22f, h, 0.22f, t.bark, uvPerM = 0.9f)
        }
        // back and side boarding
        d.box(x, y, z - dp * 0.5f + 0.1f, w, 2.5f, 0.16f, t.planks, uvPerM = 1.0f)
        d.box(x - w * 0.5f + 0.1f, y, z, 0.16f, 2.2f, dp, t.planks, uvPerM = 1.0f)
        // the roof, sloping to the front
        d.ms.push().translate(x, y + 2.62f, z).rotateX(-9.5f)
        d.slab(0f, 0f, 0f, w + 0.6f, 0.14f, dp + 0.7f, t.plankWorn, 1.0f)
        d.slab(0f, 0.14f, 0f, w + 0.6f, 0.12f, dp + 0.7f, t.snow, 0.9f, 1.02f, 1.05f, 1.12f)
        d.ms.pop()
        // the stack itself: rows of log ends facing out
        for (row in 0 until 5) {
            for (k in 0 until 7) {
                val lx = x - 1.5f + k * 0.5f
                val ly = y + 0.16f + row * 0.42f
                val jitter = U.hash(row * 31 + k) * 0.06f
                d.logZ(lx, ly, z - 0.4f + jitter, dp * 0.62f, 0.21f, t.logs, 0.94f, 0.9f, 0.86f)
            }
        }
        d.snowOn(x - 0.05f, y + 0.16f + 5 * 0.42f, z - 0.4f, 3.6f, dp * 0.62f, 0.07f, 0.0f)
    }

    // ----------------------------------------------------------- ice hut

    /** The hut out on the pond: dark boards, a stove pipe, one small window. */
    fun iceHut(g: Game) {
        val x = World.HUT_X
        val z = World.HUT_Z
        val y = Terrain.ICE_Y
        d.ms.push().translate(x, y, z).rotateY(16f)
        d.slab(0f, -0.06f, 0f, 2.5f, 0.16f, 2.3f, t.plankWorn, 1.1f)
        d.box(0f, 0.1f, 0f, 2.3f, 1.75f, 2.1f, t.planks, uvPerM = 0.9f, r = 0.72f, g = 0.7f, b = 0.76f)
        d.roofAt(0f, 1.85f, 0f, 2.7f, 0.85f, 2.5f, t.roofDark)
        d.roofAt(0f, 1.96f, 0f, 2.44f, 0.74f, 2.32f, t.roofSnow, 1.02f, 1.05f, 1.12f)
        // stove pipe
        d.cyl(0.7f, 2.3f, -0.4f, 0.09f, 0.9f, t.rusty)
        d.slab(0.7f, 3.2f, -0.4f, 0.28f, 0.06f, 0.28f, t.rusty, 1.4f)
        // door and a little lit window
        d.panel(0f, 0.1f, 1.06f, 0.8f, 1.35f, t.door)
        d.emissive(glow)
        d.panel(-0.72f, 0.85f, 1.06f, 0.5f, 0.45f, if (glow > 0.05f) t.windowLit else t.window)
        d.emissive(0f)
        d.ms.pop()
    }

    // ------------------------------------------------------------- fences

    /**
     * The yard fence. Built once into a static mesh by the renderer; this is
     * just the geometry description so the two live next to each other.
     */
    fun buildYardFence(b: MeshBuilder) {
        val pts = yardFence
        b.tint(0xFFFFFF, 0f)
        var i = 0
        while (i + 3 < pts.size) {
            val ax = pts[i]; val az = pts[i + 1]
            val bx = pts[i + 2]; val bz = pts[i + 3]
            val dx = bx - ax; val dz = bz - az
            val len = kotlin.math.sqrt(dx * dx + dz * dz)
            val n = kotlin.math.max(1, (len / 1.35f).toInt())
            for (k in 0..n) {
                val tt = k.toFloat() / n
                val px = ax + dx * tt
                val pz = az + dz * tt
                val py = Terrain.height(px, pz) - 0.12f
                b.box(px, py, pz, 0.13f, 1.05f, 0.13f, 0.9f)
                // a dab of snow on every post cap
                b.tint(0xE8EEF8, 0f)
                b.box(px, py + 1.05f, pz, 0.19f, 0.07f, 0.19f, 0.9f, top = true, bottom = true)
                b.tint(0xFFFFFF, 0f)
            }
            for (yy in floatArrayOf(0.36f, 0.72f)) {
                for (k in 0 until n) {
                    val t0 = k.toFloat() / n
                    val t1 = (k + 1f) / n
                    val x0 = ax + dx * t0; val z0 = az + dz * t0
                    val x1 = ax + dx * t1; val z1 = az + dz * t1
                    val mx = (x0 + x1) * 0.5f
                    val mz = (z0 + z1) * 0.5f
                    val my = (Terrain.height(x0, z0) + Terrain.height(x1, z1)) * 0.5f + yy - 0.12f
                    val seg = len / n
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dz)) {
                        b.box(mx, my, mz, seg, 0.08f, 0.07f, 0.9f, top = true, bottom = true)
                    } else {
                        b.box(mx, my, mz, 0.07f, 0.08f, seg, 0.9f, top = true, bottom = true)
                    }
                }
            }
            i += 2
        }
    }

    companion object {
        /** Where the fence runs: round the front of the yard, with a gap at the path. */
        val yardFence = floatArrayOf(
            World.CABIN_X + 4.6f, World.CABIN_Z - 3.0f,
            World.CABIN_X + 4.6f, World.CABIN_Z + 8.2f,
            World.CABIN_X - 1.2f, World.CABIN_Z + 8.6f,
            // gap for the path, then it picks up again to the west
            World.CABIN_X - 4.6f, World.CABIN_Z + 8.2f,
            World.CABIN_X - 6.4f, World.CABIN_Z + 5.0f,
            World.CABIN_X - 6.6f, World.CABIN_Z - 1.0f
        )
    }
}
