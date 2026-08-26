package com.cozyhollow.riverside.gl

import android.graphics.Color
import com.cozyhollow.riverside.Game
import com.cozyhollow.riverside.Terrain
import com.cozyhollow.riverside.U
import com.cozyhollow.riverside.World
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything standing about in the yard.
 *
 * These are the objects that make the place look lived in rather than
 * designed: the truck that has not started since October, the tyre on a rope,
 * the block with the axe still in it, a snowman somebody made and nobody has
 * taken down.
 */
class Props3D(private val d: DrawCtx) {

    var glow = 1f

    private val t: Textures get() = d.tex!!

    fun draw(g: Game, p: World.Prop, y: Float) {
        when (p.kind) {
            World.PKind.LANTERN -> lantern(p.x, y, p.z)
            World.PKind.BENCH -> bench(p.x, y, p.z, p.yaw)
            World.PKind.FIREPIT -> firepit(g, p.x, y, p.z)
            World.PKind.WELL -> well(p.x, y, p.z)
            World.PKind.SIGN -> sign(p.x, y, p.z, p.yaw)
            World.PKind.SNOWMAN -> snowman(g, p.x, y, p.z)
            World.PKind.BIRD_FEEDER -> birdFeeder(g, p.x, y, p.z)
            World.PKind.STUMP -> stump(p.x, y, p.z)
            World.PKind.BARREL -> barrel(p.x, y, p.z)
            World.PKind.CRATE -> crates(p.x, y, p.z, p.yaw)
            World.PKind.LOGPILE -> logpile(p.x, y, p.z, p.yaw)
            World.PKind.CHOP_BLOCK -> chopBlock(g, p.x, y, p.z)
            World.PKind.TRUCK -> truck(p.x, y, p.z, p.yaw)
            World.PKind.SWING_TREE -> swingTree(g, p.x, y, p.z)
            World.PKind.DEER_FEEDER -> deerFeeder(g, p.x, y, p.z)
            World.PKind.SLED -> sled(p.x, y, p.z, p.yaw)
            World.PKind.MAILBOX -> mailbox(p.x, y, p.z, p.yaw)
            World.PKind.BRAZIER -> brazier(g, p.x, y, p.z)
            World.PKind.LOG_SEAT -> logSeat(p.x, y, p.z, p.yaw)
            World.PKind.SPRING -> spring(g, p.x, y, p.z)
            else -> Unit
        }
    }

    // ------------------------------------------------------------- lights

    fun lantern(x: Float, y: Float, z: Float) {
        d.box(x, y, z, 0.13f, 1.75f, 0.13f, t.rusty, uvPerM = 1.2f, r = 0.7f, g = 0.66f, b = 0.66f)
        d.slab(x, y + 1.75f, z, 0.36f, 0.09f, 0.36f, t.rusty, 1.4f, 0.66f, 0.62f, 0.62f)
        d.snowOn(x, y + 1.84f, z, 0.36f, 0.36f, 0.05f, 0.01f)
        d.emissive(glow)
        d.box(x, y + 1.22f, z, 0.3f, 0.46f, 0.3f, t.lantern, closed = true, uvPerM = 1.6f)
        d.emissive(0f)
        d.slab(x, y + 1.68f, z, 0.38f, 0.08f, 0.38f, t.rusty, 1.4f, 0.62f, 0.58f, 0.58f)
        d.slab(x, y + 1.16f, z, 0.34f, 0.07f, 0.34f, t.rusty, 1.4f, 0.62f, 0.58f, 0.58f)
    }

    fun brazier(g: Game, x: Float, y: Float, z: Float) {
        for (k in 0 until 3) {
            val a = k * 2.094f
            d.limb(x + cos(a) * 0.22f, y, z + sin(a) * 0.22f, 0.7f, 0.07f, Math.toDegrees(a.toDouble()).toFloat(), 14f, t.rusty)
        }
        d.ms.push().translate(x, y + 0.62f, z).scale(0.72f, 0.34f, 0.72f)
        d.uv(1.6f, 0.6f)
        d.bindAndDraw(d.prims?.cyl, t.rusty, 0.72f, 0.66f, 0.62f)
        d.ms.pop()
        flame(g, x, y + 0.82f, z, 0.42f, 0.62f)
    }

    fun firepit(g: Game, x: Float, y: Float, z: Float) {
        // a ring of stones, half buried, with the snow melted back around them
        for (k in 0 until 8) {
            val a = k * 0.7854f + 0.2f
            val rr = 0.86f + U.hash(k * 41) * 0.12f
            d.blob(x + cos(a) * rr, y + 0.06f, z + sin(a) * rr, 0.19f + U.hash(k * 53) * 0.07f, t.rock, 0.8f, 0.8f, 0.86f)
        }
        d.disc(x, y + 0.02f, z, 1.35f, t.shingle, 0.66f, 0.62f, 0.6f, 0.9f)
        val burning = g.st.firepitFuel > 0f
        // the logs, teepeed over the ash
        for (k in 0 until 4) {
            d.ms.push().translate(x, y + 0.1f, z).rotateY(k * 46f).rotateZ(62f)
                .scale(0.15f, 1.05f, 0.15f).translate(0f, -0.5f, 0f)
            d.uv(1f, 1.4f)
            d.bindAndDraw(d.prims?.cyl, t.logs, 0.7f, 0.62f, 0.56f)
            d.ms.pop()
        }
        if (burning) flame(g, x, y + 0.16f, z, 0.6f, 0.95f)
        else d.blob(x, y + 0.1f, z, 0.3f, t.rock, 0.34f, 0.32f, 0.34f, 0.5f)
    }

    /** Two stacked cones, flickering. Cheap, and it reads as fire at any size. */
    fun flame(g: Game, x: Float, y: Float, z: Float, r: Float, h: Float) {
        val flick = 0.85f + sin(g.timeMs * 0.011f) * 0.1f + sin(g.timeMs * 0.023f) * 0.05f
        d.emissive(1f)
        d.cone(x, y, z, r, h * flick, t.solid(Color.parseColor("#F07838")))
        d.cone(x, y + h * 0.16f, z, r * 0.58f, h * 0.74f * flick, t.solid(Color.parseColor("#FFC862")))
        d.cone(x, y + h * 0.3f, z, r * 0.28f, h * 0.5f * flick, t.solid(Color.parseColor("#FFF0C8")))
        d.emissive(0f)
    }

    // -------------------------------------------------------------- yard

    /**
     * The truck.
     *
     * It has not run in years. The bed is full of snow, the tyres are flat
     * into the drift, and everything about the silhouette says the shape of a
     * place people actually live.
     */
    fun truck(x: Float, y: Float, z: Float, yaw: Float) {
        d.ms.push().translate(x, y, z).rotateY(yaw)
        val r = 0.86f; val g = 0.42f; val b = 0.36f
        // chassis
        d.box(0f, 0.34f, 0f, 1.72f, 0.28f, 4.6f, t.rusty, uvPerM = 0.9f, r = 0.6f, g = 0.56f, b = 0.56f)
        // cab
        d.box(0f, 0.58f, -0.75f, 1.86f, 0.72f, 1.9f, t.truckPaint, uvPerM = 0.9f, r = r, g = g, b = b)
        d.box(0f, 1.30f, -0.62f, 1.72f, 0.76f, 1.5f, t.truckPaint, uvPerM = 0.9f, r = r * 0.92f, g = g * 0.92f, b = b * 0.92f)
        // glass, dark and cold
        d.panel(0f, 1.38f, 0.16f, 1.5f, 0.6f, t.window, 0.7f, 0.76f, 0.9f)
        d.ms.push().translate(0.87f, 1.38f, -0.62f).rotateY(90f).scale(1.3f, 0.6f, 1f)
        d.bindAndDraw(d.prims?.quad, t.window, 0.6f, 0.68f, 0.84f)
        d.ms.pop()
        d.ms.push().translate(-0.87f, 1.38f, -0.62f).rotateY(-90f).scale(1.3f, 0.6f, 1f)
        d.bindAndDraw(d.prims?.quad, t.window, 0.6f, 0.68f, 0.84f)
        d.ms.pop()
        // bonnet
        d.box(0f, 0.86f, -2.02f, 1.7f, 0.46f, 1.2f, t.truckPaint, uvPerM = 0.9f, r = r, g = g, b = b)
        d.box(0f, 0.7f, -2.62f, 1.76f, 0.34f, 0.24f, t.rusty, uvPerM = 1.2f, r = 0.62f, g = 0.6f, b = 0.6f)
        // headlamps
        d.blob(-0.6f, 0.92f, -2.6f, 0.14f, t.metal, 1.0f, 0.98f, 0.9f)
        d.blob(0.6f, 0.92f, -2.6f, 0.14f, t.metal, 1.0f, 0.98f, 0.9f)
        // the bed, with stake sides
        d.box(0f, 0.62f, 1.25f, 1.86f, 0.2f, 2.5f, t.rusty, uvPerM = 1.0f, r = 0.6f, g = 0.56f, b = 0.56f)
        for (s in intArrayOf(-1, 1)) {
            d.box(s * 0.9f, 0.82f, 1.25f, 0.1f, 0.62f, 2.5f, t.truckPaint, uvPerM = 1.0f, r = r * 0.9f, g = g * 0.9f, b = b * 0.9f)
            for (k in 0 until 4) {
                d.box(s * 0.9f, 0.82f, 0.15f + k * 0.72f, 0.14f, 0.78f, 0.12f, t.planks, uvPerM = 1.3f, r = 0.66f, g = 0.62f, b = 0.62f)
            }
        }
        d.box(0f, 0.82f, 2.46f, 1.86f, 0.62f, 0.1f, t.truckPaint, uvPerM = 1.0f, r = r * 0.9f, g = g * 0.9f, b = b * 0.9f)
        // the winter's snow, filling the bed and lying along everything flat
        d.slab(0f, 0.82f, 1.25f, 1.7f, 0.26f, 2.4f, t.snow, 0.8f, 1.02f, 1.05f, 1.12f)
        d.snowOn(0f, 2.06f, -0.62f, 1.72f, 1.5f, 0.12f, 0.05f)
        d.snowOn(0f, 1.32f, -2.02f, 1.7f, 1.2f, 0.09f, 0.04f)
        // wheels, sunk into the drift
        for (sx in intArrayOf(-1, 1)) for (sz in floatArrayOf(-1.75f, 1.55f)) {
            d.ms.push().translate(sx * 0.86f, 0.36f, sz).rotateZ(90f).scale(0.72f, 0.22f, 0.72f)
                .translate(0f, -0.5f, 0f)
            d.uv(1.4f, 0.5f)
            d.bindAndDraw(d.prims?.cyl, t.boot, 0.7f, 0.7f, 0.74f)
            d.ms.pop()
        }
        d.ms.pop()
    }

    /** The bare tree with a tyre hanging off it. */
    fun swingTree(g: Game, x: Float, y: Float, z: Float) {
        d.cyl(x, y - 0.15f, z, 0.28f, 2.6f, t.bark, 0.74f, 0.7f, 0.68f, uvU = 1.4f)
        val top = y + 2.3f
        for (k in 0 until 5) {
            val yaw = k * 72f + 14f
            val lean = 34f + U.hash(k * 71) * 22f
            d.limb(x, top, z, 1.9f, 0.15f, yaw, lean, t.bark, 0.72f, 0.68f, 0.66f)
            val rad = Math.toRadians(yaw.toDouble())
            val lr = Math.toRadians(lean.toDouble())
            val tx = x + (sin(rad) * sin(lr) * 1.9f).toFloat()
            val ty = top + (cos(lr) * 1.9f).toFloat()
            val tz = z + (cos(rad) * sin(lr) * 1.9f).toFloat()
            for (j in 0 until 2) {
                d.limb(tx, ty, tz, 1.25f, 0.07f, yaw + (if (j == 0) 34f else -30f), lean * 0.7f, t.bark, 0.7f, 0.66f, 0.64f)
            }
            // snow sitting along the upper side of each limb
            d.ms.push().translate(x, top + 0.07f, z).rotateY(yaw).rotateX(lean)
                .scale(0.13f, 1.8f, 0.13f)
            d.bindAndDraw(d.prims?.cyl, t.snow, 1.02f, 1.05f, 1.12f)
            d.ms.pop()
        }
        // the rope and the tyre, swinging a little in the wind
        val sway = sin(g.timeMs * 0.0009f) * 5.5f
        d.ms.push().translate(x + 1.05f, top + 0.4f, z + 0.5f).rotateZ(sway)
        d.box(0f, -1.75f, 0f, 0.045f, 1.78f, 0.045f, t.straw, uvPerM = 2.0f, r = 0.86f, g = 0.82f, b = 0.72f)
        d.ms.push().translate(0f, -1.78f, 0f).rotateX(90f).scale(0.64f, 0.18f, 0.64f).translate(0f, -0.5f, 0f)
        d.uv(1.6f, 0.4f)
        d.bindAndDraw(d.prims?.cyl, t.boot, 0.6f, 0.6f, 0.66f)
        d.ms.pop()
        d.ms.pop()
    }

    fun snowman(g: Game, x: Float, y: Float, z: Float) {
        d.blob(x, y + 0.46f, z, 0.52f, t.snow, 1.02f, 1.05f, 1.12f)
        d.blob(x, y + 1.16f, z, 0.38f, t.snow, 1.03f, 1.06f, 1.13f)
        d.blob(x, y + 1.7f, z, 0.28f, t.snow, 1.04f, 1.07f, 1.14f)
        // twig arms
        d.limb(x - 0.3f, y + 1.2f, z, 0.62f, 0.035f, 270f, 74f, t.bark, 0.6f, 0.56f, 0.52f)
        d.limb(x + 0.3f, y + 1.2f, z, 0.62f, 0.035f, 90f, 62f, t.bark, 0.6f, 0.56f, 0.52f)
        // a carrot, three coal buttons, and somebody's old scarf
        d.ms.push().translate(x, y + 1.72f, z + 0.24f).rotateX(90f).scale(0.09f, 0.28f, 0.09f)
        d.bindAndDraw(d.prims?.cone, t.solid(Color.parseColor("#E8973E")))
        d.ms.pop()
        d.blob(x - 0.09f, y + 1.78f, z + 0.22f, 0.035f, t.solid(Color.parseColor("#2E2A38")))
        d.blob(x + 0.09f, y + 1.78f, z + 0.22f, 0.035f, t.solid(Color.parseColor("#2E2A38")))
        for (k in 0 until 3) d.blob(x, y + 1.12f + k * 0.14f, z + 0.34f, 0.04f, t.solid(Color.parseColor("#2E2A38")))
        d.box(x, y + 1.4f, z, 0.62f, 0.16f, 0.58f, t.scarf, closed = true, uvPerM = 1.8f)
        val flap = sin(g.timeMs * 0.0022f) * 7f
        d.ms.push().translate(x + 0.2f, y + 1.4f, z - 0.24f).rotateZ(flap)
        d.box(0f, -0.5f, 0f, 0.16f, 0.52f, 0.1f, t.scarf, uvPerM = 1.8f)
        d.ms.pop()
        // a bucket, worn at an angle
        d.ms.push().translate(x, y + 1.9f, z).rotateZ(-13f).scale(0.3f, 0.3f, 0.3f)
        d.uv(1.5f, 0.6f)
        d.bindAndDraw(d.prims?.cyl, t.rusty, 0.72f, 0.7f, 0.74f)
        d.ms.pop()
    }

    fun birdFeeder(g: Game, x: Float, y: Float, z: Float) {
        d.box(x, y, z, 0.11f, 1.5f, 0.11f, t.planks, uvPerM = 1.4f)
        d.slab(x, y + 1.5f, z, 0.62f, 0.07f, 0.62f, t.plankWorn, 1.4f)
        for (s in intArrayOf(-1, 1)) {
            d.box(x + s * 0.26f, y + 1.57f, z, 0.05f, 0.3f, 0.05f, t.planks, uvPerM = 1.6f)
        }
        d.roofAt(x, y + 1.87f, z, 0.8f, 0.3f, 0.8f, t.roofDark)
        d.roofAt(x, y + 1.94f, z, 0.7f, 0.26f, 0.72f, t.roofSnow, 1.02f, 1.05f, 1.12f)
        val fed = g.st.birdFedDay == g.st.day
        if (fed) {
            d.slab(x, y + 1.57f, z, 0.44f, 0.05f, 0.44f, t.straw, 1.6f, 1.0f, 0.94f, 0.72f)
        }
    }

    fun deerFeeder(g: Game, x: Float, y: Float, z: Float) {
        d.ms.push().translate(x, y, z).rotateY(24f)
        for (s in intArrayOf(-1, 1)) {
            d.box(s * 0.7f, 0f, 0f, 0.14f, 0.62f, 0.9f, t.planks, uvPerM = 1.2f)
        }
        d.box(0f, 0.5f, 0f, 1.7f, 0.35f, 0.86f, t.plankWorn, uvPerM = 1.2f)
        val fed = g.st.deerFedDay == g.st.day
        if (fed) {
            d.slab(0f, 0.78f, 0f, 1.5f, 0.22f, 0.7f, t.straw, 1.4f, 1.0f, 0.94f, 0.7f)
        } else {
            d.snowOn(0f, 0.85f, 0f, 1.5f, 0.7f, 0.08f, 0f)
        }
        d.ms.pop()
    }

    fun chopBlock(g: Game, x: Float, y: Float, z: Float) {
        d.ms.push().translate(x, y, z).scale(0.86f, 0.62f, 0.86f)
        d.uv(1.8f, 0.8f)
        d.bindAndDraw(d.prims?.cyl, t.bark, 0.78f, 0.72f, 0.68f)
        d.ms.pop()
        d.disc(x, y + 0.63f, z, 0.42f, t.logs, 0.9f, 0.82f, 0.72f)
        // the axe, left in it
        d.ms.push().translate(x + 0.1f, y + 0.6f, z).rotateY(28f).rotateZ(-24f)
        d.box(0f, 0f, 0f, 0.05f, 0.72f, 0.05f, t.bark, uvPerM = 2f, r = 0.8f, g = 0.72f, b = 0.62f)
        d.ms.push().translate(0f, 0.72f, 0f).rotateZ(90f).scale(0.09f, 0.26f, 0.2f)
        d.bindAndDraw(d.prims?.box, t.metal, 0.9f, 0.92f, 0.98f)
        d.ms.pop()
        d.ms.pop()
        // split rounds scattered around the base
        for (k in 0 until 5) {
            val a = k * 1.25f + 0.4f
            val rr = 0.8f + U.hash(k * 89) * 0.5f
            val px = x + cos(a) * rr
            val pz = z + sin(a) * rr
            d.logX(px, Terrain.height(px, pz) + 0.1f, pz, 0.42f, 0.11f, t.logs, 0.86f, 0.8f, 0.74f)
        }
    }

    fun logpile(x: Float, y: Float, z: Float, yaw: Float) {
        d.ms.push().translate(x, y, z).rotateY(yaw)
        for (row in 0 until 3) {
            val n = 4 - row
            for (k in 0 until n) {
                d.logX(
                    (k - (n - 1) * 0.5f) * 0.46f, 0.22f + row * 0.4f, -row * 0.03f,
                    1.9f, 0.22f, t.logs, 0.9f, 0.84f, 0.78f
                )
            }
        }
        d.snowOn(0f, 1.42f, -0.06f, 1.9f, 0.5f, 0.09f, 0.03f)
        d.ms.pop()
    }

    fun sled(x: Float, y: Float, z: Float, yaw: Float) {
        d.ms.push().translate(x, y, z).rotateY(yaw).rotateZ(-6f)
        for (s in intArrayOf(-1, 1)) {
            d.box(s * 0.24f, 0.02f, 0f, 0.09f, 0.14f, 1.15f, t.planks, uvPerM = 1.4f, r = 0.8f, g = 0.72f, b = 0.62f)
        }
        for (k in 0 until 5) {
            d.box(0f, 0.16f, -0.44f + k * 0.22f, 0.62f, 0.06f, 0.16f, t.plankWorn, closed = true, uvPerM = 1.6f)
        }
        d.box(0f, 0.22f, -0.6f, 0.5f, 0.26f, 0.06f, t.planks, uvPerM = 1.6f, r = 0.82f, g = 0.4f, b = 0.36f)
        d.ms.pop()
    }

    fun mailbox(x: Float, y: Float, z: Float, yaw: Float) {
        d.ms.push().translate(x, y, z).rotateY(yaw)
        d.box(0f, 0f, 0f, 0.12f, 1.1f, 0.12f, t.bark, uvPerM = 1.4f)
        d.ms.push().translate(0f, 1.24f, 0f).rotateX(90f).scale(0.34f, 0.62f, 0.34f).translate(0f, -0.5f, 0f)
        d.uv(1.4f, 0.7f)
        d.bindAndDraw(d.prims?.cyl, t.rusty, 0.74f, 0.7f, 0.72f)
        d.ms.pop()
        d.snowOn(0f, 1.4f, 0f, 0.3f, 0.6f, 0.06f, 0.01f)
        d.box(0.2f, 1.16f, 0.2f, 0.05f, 0.3f, 0.05f, t.metal, uvPerM = 1.8f, r = 0.9f, g = 0.35f, b = 0.3f)
        d.ms.pop()
    }

    fun logSeat(x: Float, y: Float, z: Float, yaw: Float) {
        d.ms.push().translate(x, y, z).rotateY(yaw)
        d.box(-0.62f, 0f, 0f, 0.3f, 0.26f, 0.36f, t.bark, uvPerM = 1.2f)
        d.box(0.62f, 0f, 0f, 0.3f, 0.26f, 0.36f, t.bark, uvPerM = 1.2f)
        d.logX(0f, 0.4f, 0f, 1.7f, 0.24f, t.logs, 0.9f, 0.84f, 0.78f)
        d.ms.pop()
    }

    fun bench(x: Float, y: Float, z: Float, yaw: Float) {
        d.ms.push().translate(x, y, z).rotateY(yaw)
        d.slab(0f, 0.44f, 0f, 1.9f, 0.1f, 0.58f, t.plankWorn, 1.2f)
        d.box(0f, 0.54f, -0.25f, 1.9f, 0.55f, 0.09f, t.plankWorn, closed = true, uvPerM = 1.2f)
        d.box(-0.78f, 0f, 0f, 0.13f, 0.46f, 0.5f, t.planks, uvPerM = 1.2f)
        d.box(0.78f, 0f, 0f, 0.13f, 0.46f, 0.5f, t.planks, uvPerM = 1.2f)
        d.snowOn(0f, 0.54f, 0.06f, 1.9f, 0.44f, 0.06f, 0.01f)
        d.ms.pop()
    }

    fun well(x: Float, y: Float, z: Float) {
        d.ms.push().translate(x, y, z).scale(1.5f, 0.9f, 1.5f)
        d.uv(2.4f, 1.4f)
        d.bindAndDraw(d.prims?.cyl, t.stone, 0.88f, 0.9f, 0.96f)
        d.ms.pop()
        d.disc(x, y + 0.92f, z, 0.7f, t.ice, 0.7f, 0.82f, 0.94f)
        d.box(x - 0.62f, y + 0.9f, z, 0.14f, 1.3f, 0.14f, t.planks, uvPerM = 1.2f)
        d.box(x + 0.62f, y + 0.9f, z, 0.14f, 1.3f, 0.14f, t.planks, uvPerM = 1.2f)
        d.roofAt(x, y + 2.2f, z, 1.9f, 0.55f, 1.7f, t.roofDark)
        d.roofAt(x, y + 2.3f, z, 1.7f, 0.48f, 1.55f, t.roofSnow, 1.02f, 1.05f, 1.12f)
        d.logX(x, y + 1.95f, z, 1.1f, 0.07f, t.bark)
        d.box(x, y + 1.5f, z, 0.4f, 0.38f, 0.4f, t.planks, closed = true, uvPerM = 1.4f)
        icicleRow(x, y + 2.2f, z + 0.82f, 1.5f, 5)
    }

    private fun icicleRow(x: Float, y: Float, z: Float, width: Float, n: Int) {
        for (k in 0 until n) {
            val fx = x - width * 0.5f + (k + 0.5f) * width / n
            val len = 0.14f + U.hash(k * 313 + 7) * 0.24f
            d.ms.push().translate(fx, y, z).rotateZ(180f).scale(0.06f, len, 0.06f)
            d.bindAndDraw(d.prims?.cone, t.white, 0.86f, 0.94f, 1.05f, 0.9f)
            d.ms.pop()
        }
    }

    fun sign(x: Float, y: Float, z: Float, yaw: Float) {
        d.ms.push().translate(x, y, z).rotateY(yaw)
        d.box(0f, 0f, 0f, 0.14f, 1.6f, 0.14f, t.planks, uvPerM = 1.2f)
        d.box(0f, 1.1f, 0.06f, 1.3f, 0.45f, 0.08f, t.plankWorn, closed = true, uvPerM = 1.3f)
        d.box(0.35f, 0.62f, 0.06f, 0.9f, 0.32f, 0.08f, t.plankWorn, closed = true, uvPerM = 1.3f)
        d.snowOn(0f, 1.55f, 0.06f, 1.3f, 0.08f, 0.05f, 0.02f)
        d.ms.pop()
    }

    fun stump(x: Float, y: Float, z: Float) {
        d.ms.push().translate(x, y, z).scale(0.8f, 0.44f, 0.8f)
        d.uv(1.8f, 0.7f)
        d.bindAndDraw(d.prims?.cyl, t.bark, 0.78f, 0.74f, 0.7f)
        d.ms.pop()
        d.disc(x, y + 0.45f, z, 0.39f, t.logs, 0.9f, 0.82f, 0.72f)
        d.snowOn(x, y + 0.46f, z, 0.64f, 0.64f, 0.06f, 0f)
    }

    fun barrel(x: Float, y: Float, z: Float) {
        d.ms.push().translate(x, y, z).scale(0.62f, 0.92f, 0.62f)
        d.uv(1.6f, 1.4f)
        d.bindAndDraw(d.prims?.cyl, t.planks, 0.82f, 0.78f, 0.74f)
        d.ms.pop()
        d.disc(x, y + 0.93f, z, 0.28f, t.ice, 0.72f, 0.84f, 0.96f)
        d.snowOn(x, y + 0.94f, z, 0.5f, 0.5f, 0.06f, 0.02f)
    }

    fun crates(x: Float, y: Float, z: Float, yaw: Float) {
        d.ms.push().translate(x, y, z).rotateY(yaw)
        d.box(0f, 0f, 0f, 0.7f, 0.6f, 0.7f, t.crate, closed = true, uvPerM = 1.6f)
        d.box(0.1f, 0.6f, -0.1f, 0.55f, 0.45f, 0.55f, t.crate, closed = true, uvPerM = 1.6f)
        d.snowOn(0.1f, 1.05f, -0.1f, 0.55f, 0.55f, 0.07f, 0.02f)
        d.ms.pop()
    }

    /** The steam vent: dark water in a rocky dish, and nothing frozen near it. */
    fun spring(g: Game, x: Float, y: Float, z: Float) {
        val r = Terrain.SPRING_R
        d.disc(x, y + 0.06f, z, r * 0.72f, t.ice, 0.20f, 0.30f, 0.36f)
        d.disc(x, y + 0.08f, z, r * 0.46f, t.ice, 0.12f, 0.20f, 0.26f)
        for (k in 0 until 11) {
            val a = k * 0.571f + 0.3f
            val rr = r * (0.82f + U.hash(k * 61) * 0.2f)
            val px = x + cos(a) * rr
            val pz = z + sin(a) * rr
            d.blob(px, Terrain.height(px, pz) + 0.1f, pz, 0.34f + U.hash(k * 79) * 0.26f, t.rock, 0.62f, 0.62f, 0.68f)
        }
        // a plank bench to leave your coat on
        d.ms.push().translate(x - r * 0.9f, Terrain.height(x - r * 0.9f, z + r * 0.6f), z + r * 0.6f).rotateY(38f)
        d.slab(0f, 0.34f, 0f, 1.3f, 0.1f, 0.42f, t.plankWorn, 1.2f)
        d.box(-0.5f, 0f, 0f, 0.11f, 0.36f, 0.36f, t.planks, uvPerM = 1.2f)
        d.box(0.5f, 0f, 0f, 0.11f, 0.36f, 0.36f, t.planks, uvPerM = 1.2f)
        d.ms.pop()
    }

    /** A hole cut in the ice, with the auger's spoil piled beside it. */
    fun iceHole(g: Game, x: Float, z: Float) {
        val y = Terrain.ICE_Y
        d.disc(x, y + 0.012f, z, 0.42f, t.solid(Color.parseColor("#16283A")))
        d.disc(x, y + 0.016f, z, 0.3f, t.solid(Color.parseColor("#0C1826")))
        for (k in 0 until 6) {
            val a = k * 1.047f + 0.4f
            d.blob(x + cos(a) * 0.52f, y + 0.02f, z + sin(a) * 0.52f, 0.11f, t.snow, 1.02f, 1.05f, 1.12f)
        }
    }
}
