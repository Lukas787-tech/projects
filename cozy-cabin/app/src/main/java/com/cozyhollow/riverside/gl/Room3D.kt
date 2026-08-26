package com.cozyhollow.riverside.gl

import android.graphics.Color
import com.cozyhollow.riverside.Decor
import com.cozyhollow.riverside.Game
import com.cozyhollow.riverside.Interior
import com.cozyhollow.riverside.U
import kotlin.math.sin

/**
 * The room.
 *
 * One room, lit almost entirely by the hearth, with a lamp on the table and
 * whatever grey is coming in at the window. Everything in it can be walked up
 * to and used: the stove cooks, the bench makes, the bed sleeps, the chair sits
 * you down facing the window, and the cat is asleep on the boards in front of
 * the fire whatever else is happening.
 */
class Room3D(private val d: DrawCtx) {

    private val t: Textures get() = d.tex!!

    private val hx = Interior.HALF_X
    private val hz = Interior.HALF_Z
    private val wh = Interior.WALL_H

    /**
     * Draws the shell: floor, four walls, ceiling and beams.
     *
     * The camera sits outside the room and above it, looking in — a doll's
     * house rather than a first-person view — so any wall between the lens and
     * the floor has to go, and so does the ceiling. Which walls those are
     * depends on where the camera has swung round to, so it is decided here
     * per frame rather than baked in.
     */
    fun shell(g: Game, eyeX: Float, eyeY: Float, eyeZ: Float) {
        // floor boards, worn pale in the middle where everyone walks
        d.slab(0f, -0.12f, 0f, hx * 2f, 0.12f, hz * 2f, t.plankWorn, 1.4f, 0.92f, 0.88f, 0.86f)
        d.slab(0f, 0f, 0f, hx * 1.1f, 0.006f, hz * 1.1f, t.plankWorn, 1.2f, 1.02f, 0.98f, 0.94f)

        // walls, as four boxes sitting just outside the room
        val w = 0.34f
        val margin = 0.4f
        if (eyeZ < hz + margin) {
            d.box(0f, 0f, -hz - w * 0.5f, hx * 2f + w * 2f, wh, w, t.logs, uvPerM = 0.5f)
            d.box(0f, 0f, -hz + 0.03f, hx * 2f, 0.16f, 0.06f, t.planks, uvPerM = 1.4f, r = 0.8f, g = 0.76f, b = 0.74f)
        }
        if (eyeZ > -hz - margin) {
            d.box(0f, 0f, hz + w * 0.5f, hx * 2f + w * 2f, wh, w, t.logs, uvPerM = 0.5f)
        }
        if (eyeX > -hx - margin) {
            d.box(-hx - w * 0.5f, 0f, 0f, w, wh, hz * 2f, t.logs, uvPerM = 0.5f)
        }
        if (eyeX < hx + margin) {
            d.box(hx + w * 0.5f, 0f, 0f, w, wh, hz * 2f, t.logs, uvPerM = 0.5f)
        }

        // ceiling and its beams, only while the lens is under them
        if (eyeY < wh) {
            d.slab(0f, wh, 0f, hx * 2f + w * 2f, 0.2f, hz * 2f + w * 2f, t.planks, 1.1f, 0.7f, 0.68f, 0.7f)
            for (k in -2..2) {
                d.box(
                    0f, wh - 0.22f, k * 1.05f, hx * 2f, 0.22f, 0.18f, t.bark,
                    uvPerM = 1.0f, r = 0.68f, g = 0.62f, b = 0.58f
                )
            }
        } else {
            // seen from above, keep just the wall plates so the room has a lip
            for (sz in intArrayOf(-1, 1)) {
                d.box(
                    0f, wh - 0.16f, sz * (hz + w * 0.5f), hx * 2f + w * 2f, 0.16f, w, t.bark,
                    uvPerM = 1.0f, r = 0.66f, g = 0.6f, b = 0.56f
                )
            }
        }
    }

    /** Everything standing in the room. */
    fun furniture(g: Game) {
        hearth(g)
        stove(g)
        bed(g)
        chair(g)
        bench(g)
        shelf(g)
        chest(g)
        table(g)
        rug(g)
        doorAndWindow(g)
        plant(g)
        decorations(g)
    }

    // ------------------------------------------------------------- hearth

    private fun hearth(g: Game) {
        val x = Interior.HEARTH_X
        val z = Interior.HEARTH_Z
        // stone breast, running up into the ceiling
        d.box(x, 0f, z - 0.1f, 2.1f, wh, 0.9f, t.stone, uvPerM = 0.7f, r = 0.88f, g = 0.86f, b = 0.9f)
        // the opening
        d.box(x, 0f, z + 0.28f, 1.2f, 0.95f, 0.16f, t.solid(Color.parseColor("#171420")), uvPerM = 1f)
        // hearthstone, out into the room
        d.slab(x, 0f, z + 0.62f, 2.0f, 0.09f, 0.72f, t.stone, 1.1f, 0.8f, 0.78f, 0.82f)
        // mantel
        d.box(x, 1.02f, z + 0.34f, 2.05f, 0.14f, 0.42f, t.bark, uvPerM = 1.1f, r = 0.76f, g = 0.7f, b = 0.64f)

        val lit = g.st.hearthLit && g.st.hearthFuel > 0f
        // logs in the grate
        for (k in 0 until 3) {
            d.ms.push().translate(x, 0.1f + k * 0.11f, z + 0.26f).rotateY(k * 26f - 22f)
            d.logX(0f, 0f, 0f, 0.86f, 0.11f, t.logs, if (lit) 0.5f else 0.86f, if (lit) 0.4f else 0.82f, if (lit) 0.36f else 0.78f)
            d.ms.pop()
        }
        if (lit) {
            val flick = 0.85f + sin(g.timeMs * 0.012f) * 0.11f + sin(g.timeMs * 0.026f) * 0.05f
            d.emissive(1f)
            d.cone(x, 0.16f, z + 0.26f, 0.42f, 0.72f * flick, t.solid(Color.parseColor("#F07838")))
            d.cone(x, 0.24f, z + 0.26f, 0.26f, 0.58f * flick, t.solid(Color.parseColor("#FFC862")))
            d.cone(x, 0.34f, z + 0.26f, 0.13f, 0.4f * flick, t.solid(Color.parseColor("#FFF4D8")))
            d.emissive(0f)
        } else {
            d.blob(x, 0.1f, z + 0.26f, 0.24f, t.rock, 0.3f, 0.28f, 0.3f)
        }
        // a poker and the log basket
        d.ms.push().translate(x + 1.15f, 0.09f, z + 0.5f).rotateZ(-12f)
        d.box(0f, 0f, 0f, 0.03f, 0.82f, 0.03f, t.metal, uvPerM = 3f, r = 0.7f, g = 0.7f, b = 0.76f)
        d.ms.pop()
        d.ms.push().translate(x - 1.15f, 0f, z + 0.62f).scale(0.46f, 0.3f, 0.46f)
        d.uv(1.6f, 0.6f)
        d.bindAndDraw(d.prims?.cyl, t.straw, 0.9f, 0.84f, 0.7f)
        d.ms.pop()
        val wood = g.st.count("firewood")
        for (k in 0 until minOf(4, wood)) {
            d.logX(x - 1.15f, 0.24f + k * 0.09f, z + 0.62f + (k % 2) * 0.06f, 0.42f, 0.07f, t.logs, 0.92f, 0.86f, 0.8f)
        }
    }

    // -------------------------------------------------------------- stove

    private fun stove(g: Game) {
        val x = Interior.STOVE_X
        val z = Interior.STOVE_Z
        d.box(x, 0f, z + 0.1f, 0.94f, 0.9f, 0.72f, t.solid(Color.parseColor("#33303C")), uvPerM = 1.2f)
        d.slab(x, 0.9f, z + 0.1f, 1.02f, 0.08f, 0.8f, t.metal, 1.2f, 0.62f, 0.62f, 0.68f)
        // the door, glowing when there is a fire in it
        val lit = g.st.hearthLit && g.st.hearthFuel > 0f
        d.emissive(if (lit) 0.9f else 0f)
        d.panel(x, 0.28f, z + 0.47f, 0.42f, 0.36f, if (lit) t.lantern else t.metal)
        d.emissive(0f)
        // flue up into the ceiling
        d.cyl(x, 0.98f, z + 0.1f, 0.09f, wh - 0.98f, t.rusty, 0.6f, 0.58f, 0.6f)
        // the pot, always on
        d.ms.push().translate(x + 0.22f, 0.98f, z + 0.06f).scale(0.42f, 0.3f, 0.42f)
        d.uv(1.6f, 0.6f)
        d.bindAndDraw(d.prims?.cyl, t.metal, 0.5f, 0.5f, 0.56f)
        d.ms.pop()
        d.disc(x + 0.22f, 1.29f, z + 0.06f, 0.2f, t.metal, 0.42f, 0.42f, 0.48f)
        // the kettle
        d.blob(x - 0.28f, 1.08f, z + 0.1f, 0.14f, t.metal, 0.72f, 0.72f, 0.78f, squash = 0.85f)
        d.cyl(x - 0.28f, 1.2f, z + 0.1f, 0.035f, 0.09f, t.metal, 0.72f, 0.72f, 0.78f)
        // a shelf of jars above it
        d.slab(x, 1.62f, z + 0.02f, 1.1f, 0.06f, 0.28f, t.planks, 1.4f, 0.84f, 0.8f, 0.76f)
        val jars = arrayOf("#C8434E", "#D8BE72", "#7FA86A", "#8A5A40")
        for (k in jars.indices) {
            d.ms.push().translate(x - 0.4f + k * 0.27f, 1.68f, z + 0.02f).scale(0.14f, 0.2f, 0.14f)
            d.uv(1.2f, 0.6f)
            d.bindAndDraw(d.prims?.cyl, t.solid(Color.parseColor(jars[k])))
            d.ms.pop()
        }
    }

    // ---------------------------------------------------------- furniture

    private fun bed(g: Game) {
        val x = -hx + 0.9f
        val z = 0.75f
        d.ms.push().translate(x, 0f, z).rotateY(90f)
        d.box(0f, 0f, 0f, 2.0f, 0.36f, 1.05f, t.planks, uvPerM = 1.1f, r = 0.82f, g = 0.78f, b = 0.74f)
        d.box(-1.02f, 0f, 0f, 0.12f, 0.9f, 1.05f, t.planks, uvPerM = 1.1f, r = 0.8f, g = 0.76f, b = 0.72f)
        d.box(1.02f, 0f, 0f, 0.12f, 0.62f, 1.05f, t.planks, uvPerM = 1.1f, r = 0.8f, g = 0.76f, b = 0.72f)
        // mattress, quilt and a pillow
        d.slab(0f, 0.36f, 0f, 1.88f, 0.18f, 0.98f, t.white, 1.2f, 0.96f, 0.94f, 0.92f)
        d.slab(0.16f, 0.5f, 0f, 1.5f, 0.13f, 1.0f, t.knitQuilt, 1.2f)
        d.slab(-0.72f, 0.52f, 0f, 0.46f, 0.16f, 0.82f, t.white, 1.4f, 1.0f, 0.98f, 0.96f)
        d.ms.pop()
    }

    private fun chair(g: Game) {
        val x = Interior.CHAIR_SIT_X
        val z = Interior.CHAIR_SIT_Z
        d.ms.push().translate(x, 0f, z).rotateY(Interior.CHAIR_SIT_YAW)
        // rockers
        for (s in intArrayOf(-1, 1)) {
            d.box(s * 0.3f, 0.02f, 0f, 0.07f, 0.09f, 0.86f, t.bark, uvPerM = 1.4f, r = 0.76f, g = 0.7f, b = 0.64f)
            d.box(s * 0.3f, 0.11f, -0.28f, 0.06f, 0.34f, 0.06f, t.bark, uvPerM = 1.8f, r = 0.76f, g = 0.7f, b = 0.64f)
            d.box(s * 0.3f, 0.11f, 0.28f, 0.06f, 0.34f, 0.06f, t.bark, uvPerM = 1.8f, r = 0.76f, g = 0.7f, b = 0.64f)
        }
        d.slab(0f, 0.45f, 0f, 0.66f, 0.08f, 0.62f, t.plankWorn, 1.4f)
        d.ms.push().translate(0f, 0.53f, -0.28f).rotateX(-14f)
        d.box(0f, 0f, 0f, 0.66f, 0.7f, 0.07f, t.plankWorn, uvPerM = 1.4f)
        d.ms.pop()
        for (s in intArrayOf(-1, 1)) {
            d.box(s * 0.32f, 0.53f, 0f, 0.06f, 0.3f, 0.5f, t.bark, uvPerM = 1.6f, r = 0.76f, g = 0.7f, b = 0.64f)
        }
        // a blanket over the back
        d.slab(0.02f, 0.9f, -0.24f, 0.6f, 0.1f, 0.3f, t.knitQuilt, 1.4f)
        d.ms.pop()
    }

    private fun bench(g: Game) {
        val x = hx - 0.55f
        val z = -0.9f
        d.ms.push().translate(x, 0f, z).rotateY(270f)
        d.slab(0f, 0.82f, 0f, 1.8f, 0.12f, 0.66f, t.plankWorn, 1.2f)
        for (s in intArrayOf(-1, 1)) {
            d.box(s * 0.78f, 0f, 0f, 0.12f, 0.84f, 0.56f, t.planks, uvPerM = 1.2f, r = 0.8f, g = 0.76f, b = 0.72f)
        }
        // pegboard of tools on the wall behind it
        d.box(0f, 1.0f, -0.32f, 1.7f, 0.9f, 0.05f, t.planks, uvPerM = 1.2f, r = 0.74f, g = 0.7f, b = 0.68f)
        for (k in 0 until 4) {
            val tx = -0.6f + k * 0.4f
            d.box(tx, 1.2f, -0.28f, 0.03f, 0.4f, 0.03f, t.bark, uvPerM = 3f, r = 0.8f, g = 0.74f, b = 0.66f)
            d.box(tx, 1.6f, -0.28f, 0.14f, 0.1f, 0.05f, t.metal, closed = true, uvPerM = 2.4f, r = 0.8f, g = 0.82f, b = 0.88f)
        }
        // a half-finished something on the bench
        d.box(0.3f, 0.94f, 0.06f, 0.32f, 0.1f, 0.2f, t.logs, closed = true, uvPerM = 2f)
        d.box(-0.4f, 0.94f, 0f, 0.2f, 0.16f, 0.2f, t.crate, closed = true, uvPerM = 2.4f)
        d.ms.pop()
    }

    private fun shelf(g: Game) {
        val x = hx - 0.4f
        val z = 1.1f
        d.ms.push().translate(x, 0f, z).rotateY(270f)
        d.box(0f, 0f, -0.12f, 1.3f, 1.9f, 0.34f, t.planks, uvPerM = 1.0f, r = 0.8f, g = 0.76f, b = 0.72f)
        for (row in 0 until 4) {
            val sy = 0.34f + row * 0.44f
            d.slab(0f, sy, -0.02f, 1.24f, 0.05f, 0.3f, t.plankWorn, 1.4f)
            // books, in whatever colours were to hand
            var bx = -0.5f
            var k = 0
            while (bx < 0.5f) {
                val hh = 0.2f + U.hash(row * 71 + k) * 0.1f
                val bw = 0.045f + U.hash(row * 97 + k) * 0.03f
                val hue = when ((U.hash(row * 131 + k) * 5f).toInt()) {
                    0 -> "#8A4A52"; 1 -> "#3E5A78"; 2 -> "#4E7A56"; 3 -> "#8A6A3E"; else -> "#6E5A7A"
                }
                d.box(bx, sy + 0.05f, -0.02f, bw, hh, 0.22f, t.solid(Color.parseColor(hue)), closed = true, uvPerM = 3f)
                bx += bw + 0.012f
                k++
            }
        }
        d.ms.pop()
    }

    private fun chest(g: Game) {
        val x = -hx + 0.8f
        val z = -1.75f
        d.ms.push().translate(x, 0f, z).rotateY(20f)
        d.box(0f, 0f, 0f, 1.0f, 0.52f, 0.6f, t.crate, closed = true, uvPerM = 1.4f)
        d.ms.push().translate(0f, 0.52f, 0f).rotateX(90f).scale(1.0f, 0.6f, 0.52f).translate(0f, -0.5f, 0f)
        d.uv(1.4f, 0.8f)
        d.bindAndDraw(d.prims?.cyl, t.crate, 0.94f, 0.9f, 0.86f)
        d.ms.pop()
        d.box(0f, 0.2f, 0.31f, 0.16f, 0.16f, 0.05f, t.metal, closed = true, uvPerM = 3f, r = 0.9f, g = 0.82f, b = 0.6f)
        d.ms.pop()
    }

    private fun table(g: Game) {
        val x = -0.85f
        val z = 0.4f
        d.ms.push().translate(x, 0f, z).rotateY(12f)
        d.slab(0f, 0.56f, 0f, 0.76f, 0.07f, 0.62f, t.plankWorn, 1.4f)
        d.box(0f, 0f, 0f, 0.12f, 0.58f, 0.12f, t.bark, uvPerM = 1.6f, r = 0.78f, g = 0.72f, b = 0.66f)
        d.slab(0f, 0.02f, 0f, 0.42f, 0.05f, 0.4f, t.bark, 1.4f, 0.78f, 0.72f, 0.66f)
        // the lamp on it
        d.blob(0f, 0.66f, 0f, 0.08f, t.metal, 0.7f, 0.68f, 0.72f)
        d.cyl(0f, 0.7f, 0f, 0.02f, 0.14f, t.metal, 0.7f, 0.68f, 0.72f)
        d.emissive(0.95f)
        d.ms.push().translate(0f, 0.84f, 0f).rotateZ(180f).scale(0.26f, 0.2f, 0.26f)
        d.bindAndDraw(d.prims?.cone, t.lantern)
        d.ms.pop()
        d.emissive(0f)
        // a mug, waiting
        d.ms.push().translate(0.2f, 0.63f, 0.14f).scale(0.11f, 0.12f, 0.11f)
        d.uv(1.4f, 0.7f)
        d.bindAndDraw(d.prims?.cyl, t.white, 0.98f, 0.94f, 0.9f)
        d.ms.pop()
        d.ms.pop()
    }

    private fun rug(g: Game) {
        if (!g.st.decorPlaced.containsValue("rug")) {
            d.disc(0.35f, 0.012f, -0.15f, 0.85f, t.knitQuilt, 0.7f, 0.66f, 0.66f, 0.9f)
            return
        }
        d.disc(0.35f, 0.012f, -0.15f, 1.05f, t.knitQuilt, 1f, 1f, 1f)
        d.disc(0.35f, 0.016f, -0.15f, 0.72f, t.knitQuilt, 0.86f, 0.8f, 0.78f)
    }

    private fun plant(g: Game) {
        val x = -hx + 0.55f
        val z = hz - 0.6f
        d.ms.push().translate(x, 0f, z).scale(0.3f, 0.26f, 0.3f)
        d.uv(1.4f, 0.6f)
        d.bindAndDraw(d.prims?.cyl, t.crate, 0.9f, 0.7f, 0.6f)
        d.ms.pop()
        d.blob(x, 0.42f, z, 0.22f, t.needles, 0.9f, 1.05f, 0.9f)
        d.blob(x - 0.12f, 0.34f, z + 0.08f, 0.14f, t.needles, 0.86f, 1.0f, 0.88f)
    }

    private fun doorAndWindow(g: Game) {
        // the front door, in the south wall
        d.ms.push().translate(Interior.DOOR_X, 0f, hz - 0.02f).rotateY(180f)
        d.panel(0f, 0f, 0f, 1.05f, 1.95f, t.door)
        d.ms.pop()
        d.box(Interior.DOOR_X, 1.95f, hz - 0.08f, 1.3f, 0.12f, 0.12f, t.planks, uvPerM = 1.4f, r = 0.8f, g = 0.76f, b = 0.72f)
        // coat hooks beside it
        for (k in 0 until 3) {
            d.box(Interior.DOOR_X - 0.9f - k * 0.22f, 1.6f, hz - 0.1f, 0.04f, 0.1f, 0.08f, t.metal, uvPerM = 3f)
        }

        // the window, with the blue outside showing through it
        val wx = Interior.WINDOW_X
        d.emissive(0.42f)
        d.ms.push().translate(wx, 0.95f, hz - 0.03f).rotateY(180f).scale(1.35f, 1.15f, 1f)
        d.bindAndDraw(d.prims?.quad, t.window, 1.15f, 1.25f, 1.5f)
        d.ms.pop()
        d.emissive(0f)
        d.box(wx, 0.86f, hz - 0.16f, 1.5f, 0.1f, 0.26f, t.planks, uvPerM = 1.4f, r = 0.82f, g = 0.78f, b = 0.74f)
        d.box(wx, 2.12f, hz - 0.16f, 1.5f, 0.1f, 0.2f, t.planks, uvPerM = 1.4f, r = 0.82f, g = 0.78f, b = 0.74f)
        for (s in intArrayOf(-1, 1)) {
            d.box(wx + s * 0.72f, 0.96f, hz - 0.16f, 0.1f, 1.16f, 0.2f, t.planks, uvPerM = 1.4f, r = 0.82f, g = 0.78f, b = 0.74f)
        }
    }

    // ------------------------------------------------------- decorations

    /** Whatever the player has put up. All of it is purely for looking at. */
    private fun decorations(g: Game) {
        val placed = g.st.decorPlaced
        when (placed[Decor.SLOT_WALL]) {
            "wreath" -> {
                d.ms.push().translate(0.35f, 1.72f, -hz + 0.03f).rotateX(90f)
                d.ms.push().scale(0.62f, 0.16f, 0.62f)
                d.bindAndDraw(d.prims?.blob, t.needles, 0.8f, 1.0f, 0.86f)
                d.ms.pop()
                d.ms.pop()
                for (k in 0 until 7) {
                    val a = k * 0.897f
                    d.blob(
                        0.35f + kotlin.math.cos(a) * 0.28f, 1.72f + kotlin.math.sin(a) * 0.28f, -hz + 0.06f,
                        0.045f, t.solid(Color.parseColor("#C8434E"))
                    )
                }
            }
            "garland" -> {
                for (k in 0 until 9) {
                    val tt = k / 8f
                    val gx = -1.6f + tt * 3.4f
                    val sag = kotlin.math.sin(tt * 3.1416f) * 0.22f
                    val hue = when (k % 4) {
                        0 -> "#C8434E"; 1 -> "#E8973E"; 2 -> "#7FA86A"; else -> "#6E7EC0"
                    }
                    d.box(gx, 2.02f - sag, -hz + 0.06f, 0.11f, 0.15f, 0.03f, t.solid(Color.parseColor(hue)), closed = true, uvPerM = 4f)
                }
            }
            "antlers" -> {
                d.box(0.35f, 1.6f, -hz + 0.04f, 0.16f, 0.2f, 0.08f, t.bark, uvPerM = 3f, r = 0.86f, g = 0.82f, b = 0.76f)
                for (s in intArrayOf(-1, 1)) {
                    d.limb(0.35f + s * 0.06f, 1.78f, -hz + 0.06f, 0.4f, 0.03f, s * 26f, 24f, t.bark, 0.9f, 0.86f, 0.78f)
                    d.limb(0.35f + s * 0.2f, 2.1f, -hz + 0.06f, 0.24f, 0.022f, s * 50f, 36f, t.bark, 0.9f, 0.86f, 0.78f)
                }
            }
            "painting" -> {
                d.box(0.35f, 1.52f, -hz + 0.03f, 0.86f, 0.62f, 0.05f, t.planks, uvPerM = 1.6f, r = 0.86f, g = 0.78f, b = 0.66f)
                d.box(0.35f, 1.58f, -hz + 0.07f, 0.72f, 0.48f, 0.02f, t.ice, uvPerM = 1.4f, r = 0.9f, g = 0.98f, b = 1.05f)
            }
        }

        val mantelY = 1.16f
        val mx = Interior.HEARTH_X
        val mz = Interior.HEARTH_Z + 0.34f
        when (placed[Decor.SLOT_MANTEL]) {
            "clock" -> {
                d.box(mx, mantelY, mz, 0.26f, 0.34f, 0.16f, t.bark, closed = true, uvPerM = 2.4f, r = 0.9f, g = 0.82f, b = 0.68f)
                d.disc(mx, mantelY + 0.2f, mz + 0.09f, 0.09f, t.white, 1f, 0.98f, 0.92f)
            }
            "candles" -> {
                for (k in 0 until 5) {
                    val cx = mx - 0.4f + k * 0.2f
                    val hh = 0.1f + U.hash(k * 41) * 0.1f
                    d.cyl(cx, mantelY, mz, 0.028f, hh, t.white, 1f, 0.96f, 0.86f)
                    d.emissive(1f)
                    d.cone(cx, mantelY + hh, mz, 0.022f, 0.06f, t.solid(Color.parseColor("#FFD07A")))
                    d.emissive(0f)
                }
            }
            "jar" -> {
                d.emissive(0.65f)
                d.ms.push().translate(mx, mantelY, mz).scale(0.2f, 0.28f, 0.2f)
                d.uv(1.3f, 0.7f)
                d.bindAndDraw(d.prims?.cyl, t.lantern, 0.9f, 1.0f, 0.8f)
                d.ms.pop()
                d.emissive(0f)
            }
        }

        when (placed[Decor.SLOT_FLOOR]) {
            "basket" -> {
                d.ms.push().translate(Interior.HEARTH_X - 1.5f, 0f, Interior.HEARTH_Z + 0.9f).scale(0.44f, 0.34f, 0.44f)
                d.uv(1.6f, 0.7f)
                d.bindAndDraw(d.prims?.cyl, t.straw, 0.94f, 0.88f, 0.72f)
                d.ms.pop()
            }
            "catbed" -> {
                d.ms.push().translate(Interior.HEARTH_X - 1.05f, 0f, Interior.HEARTH_Z + 1.32f).scale(0.5f, 0.16f, 0.5f)
                d.uv(1.6f, 0.5f)
                d.bindAndDraw(d.prims?.cyl, t.knitQuilt, 0.9f, 0.86f, 0.9f)
                d.ms.pop()
            }
        }

        when (placed[Decor.SLOT_WINDOW]) {
            "sill" -> {
                for (k in 0 until 3) {
                    val px = Interior.WINDOW_X - 0.4f + k * 0.4f
                    d.ms.push().translate(px, 0.96f, hz - 0.16f).scale(0.16f, 0.14f, 0.16f)
                    d.uv(1.3f, 0.6f)
                    d.bindAndDraw(d.prims?.cyl, t.crate, 0.9f, 0.7f, 0.6f)
                    d.ms.pop()
                    d.blob(px, 1.16f, hz - 0.16f, 0.12f, t.needles, 0.86f, 1.05f, 0.86f)
                }
            }
            "frost" -> {
                for (k in 0 until 5) {
                    val px = Interior.WINDOW_X - 0.5f + k * 0.25f
                    val len = 0.16f + U.hash(k * 53) * 0.12f
                    d.box(px, 2.02f - len, hz - 0.2f, 0.035f, len, 0.02f, t.ice, uvPerM = 4f, r = 0.9f, g = 0.98f, b = 1.1f)
                }
            }
        }
    }

    /** Where the warm light in here is coming from, for the light set. */
    fun lights(g: Game, set: LightSet) {
        val lit = g.st.hearthLit && g.st.hearthFuel > 0f
        val flick = 0.9f + sin(g.timeMs * 0.011f) * 0.08f + sin(g.timeMs * 0.027f) * 0.04f
        if (lit) {
            set.add(
                Interior.HEARTH_X, 0.55f, Interior.HEARTH_Z + 0.4f,
                com.cozyhollow.riverside.Pal.fireWarm, 5.4f, 1.55f * flick
            )
            set.add(
                Interior.STOVE_X, 0.35f, Interior.STOVE_Z + 0.5f,
                com.cozyhollow.riverside.Pal.fireWarm, 2.6f, 0.7f * flick
            )
        }
        set.add(-0.85f, 0.86f, 0.4f, com.cozyhollow.riverside.Pal.lampWarm, 3.4f, 0.85f)
        if (g.st.decorPlaced[Decor.SLOT_MANTEL] == "candles") {
            set.add(Interior.HEARTH_X, 1.34f, Interior.HEARTH_Z + 0.34f, com.cozyhollow.riverside.Pal.lampWarm, 2.0f, 0.5f)
        }
        if (g.st.decorPlaced[Decor.SLOT_MANTEL] == "jar") {
            set.add(Interior.HEARTH_X, 1.34f, Interior.HEARTH_Z + 0.34f, Color.parseColor("#C8F088"), 2.4f, 0.6f)
        }
    }
}
