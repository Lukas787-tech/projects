package com.cozyhollow.riverside.gl

import android.graphics.Color
import android.opengl.GLES20.*
import com.cozyhollow.riverside.Act
import com.cozyhollow.riverside.Game
import com.cozyhollow.riverside.Interior
import com.cozyhollow.riverside.Terrain
import com.cozyhollow.riverside.U
import com.cozyhollow.riverside.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Everything that moves under its own power: you, the fox, the cat, the wildlife. */
class Actors(private val d: DrawCtx) {

    private val t: Textures get() = d.tex!!

    // -------------------------------------------------------------- you

    fun player(g: Game) {
        val p = g.player
        val moving = p.moving
        val bob = if (moving) abs(sin(p.walkPhase)) * 0.05f else sin(p.idlePhase * 2.1f) * 0.02f
        val legSwing = if (moving) sin(p.walkPhase) * 30f else 0f
        val armSwing = when (p.action) {
            Act.SWING -> -70f + sin(U.clamp01(p.actionT / max(p.actionDur, 0.01f)) * 3.1416f) * 120f
            Act.POUR -> -55f
            Act.PICK -> -40f * sin(U.clamp01(p.actionT / max(p.actionDur, 0.01f)) * 3.1416f)
            Act.FISH -> -62f
            Act.WORK -> -46f + sin(p.actionT * 9f) * 22f
            Act.SIT -> -20f
            Act.SOAK -> -8f
            else -> if (moving) sin(p.walkPhase + 3.1416f) * 26f else 0f
        }
        val sitDrop = when {
            p.action == Act.SOAK -> 0.72f
            p.action == Act.SIT -> 0.42f
            else -> 0f
        }
        // cold makes you hunch: shoulders up, head down, arms in
        val chill = g.chillAmount()
        val hunch = chill * 0.07f
        val shiver = if (chill > 0.35f) sin(g.timeMs * 0.045f) * chill * 0.012f else 0f

        d.ms.identity()
        d.ms.push().translate(p.x + shiver, p.y + bob - sitDrop - hunch, p.z)
            .rotateY(p.yaw).rotateX(p.pitch * 0.4f)

        if (p.sitting) {
            limb(-0.13f, 0.62f, 0f, 0.2f, 0.62f, 0.2f, 78f, t.trousers)
            limb(0.13f, 0.62f, 0f, 0.2f, 0.62f, 0.2f, 78f, t.trousers)
        } else {
            limb(-0.13f, 0.62f, 0f, 0.2f, 0.62f, 0.2f, legSwing, t.trousers)
            limb(0.13f, 0.62f, 0f, 0.2f, 0.62f, 0.2f, -legSwing, t.trousers)
            d.box(-0.13f, 0f, 0.03f, 0.24f, 0.14f, 0.32f, t.boot, closed = true, uvPerM = 2f)
            d.box(0.13f, 0f, 0.03f, 0.24f, 0.14f, 0.32f, t.boot, closed = true, uvPerM = 2f)
        }

        // the coat: a bit wider than the summer shirt, and it flares at the hem
        d.box(0f, 0.52f, 0f, 0.52f, 0.28f, 0.38f, t.coat, uvPerM = 1.6f, r = 0.94f, g = 0.94f, b = 0.98f)
        d.box(0f, 0.74f, 0f, 0.48f, 0.44f, 0.34f, t.coat, uvPerM = 1.6f)
        limb(-0.30f, 1.14f, 0f, 0.18f, 0.5f, 0.19f, armSwing, t.coat)
        limb(0.30f, 1.14f, 0f, 0.18f, 0.5f, 0.19f, -armSwing * 0.35f, t.coat)
        // mittens on the ends
        mitten(-0.30f, 1.14f, 0f, armSwing)
        mitten(0.30f, 1.14f, 0f, -armSwing * 0.35f)
        // the scarf: two wraps and a tail that lifts in the wind
        d.box(0f, 1.14f, 0f, 0.5f, 0.16f, 0.36f, t.scarf, closed = true, uvPerM = 2.2f)
        val flap = sin(g.timeMs * 0.0035f) * (10f + g.windAmount() * 90f)
        d.ms.push().translate(0.1f, 1.16f, -0.16f).rotateX(-flap * 0.4f).rotateZ(flap)
        d.box(0f, -0.42f, 0f, 0.16f, 0.44f, 0.09f, t.scarf, uvPerM = 2.2f)
        d.ms.pop()
        // head
        d.box(0f, 1.24f, 0f, 0.42f, 0.4f, 0.4f, t.skin, uvPerM = 2f)
        d.box(0f, 1.24f, -0.18f, 0.42f, 0.3f, 0.06f, t.hair, uvPerM = 2f)
        // the hat, with a bobble
        d.box(0f, 1.5f, 0f, 0.46f, 0.2f, 0.44f, t.hat, closed = true, uvPerM = 2.4f)
        d.box(0f, 1.48f, 0f, 0.5f, 0.09f, 0.48f, t.hat, closed = true, uvPerM = 2.4f)
        d.blob(0f, 1.76f, 0f, 0.09f, t.hat)

        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        d.ms.push().translate(0f, 1.28f, 0.21f).scale(0.36f, 0.36f, 1f)
        d.bindAndDraw(d.prims?.quad, t.face); d.ms.pop()
        glDisable(GL_BLEND)

        tool(g, armSwing)
        d.ms.pop()
    }

    private fun mitten(px: Float, py: Float, pz: Float, deg: Float) {
        d.ms.push().translate(px, py, pz).rotateX(deg).translate(0f, -0.5f, 0f)
        d.blob(0f, -0.03f, 0.02f, 0.105f, t.mitten)
        d.ms.pop()
    }

    private fun limb(
        px: Float, py: Float, pz: Float,
        sx: Float, sy: Float, sz: Float, deg: Float, texId: Int
    ) {
        d.ms.push().translate(px, py, pz).rotateX(deg).scale(sx, sy, sz).translate(0f, -1f, 0f)
        d.bindAndDraw(d.prims?.box, texId)
        d.ms.pop()
    }

    private fun tool(g: Game, armSwing: Float) {
        val p = g.player
        if (p.action == Act.NONE || p.action == Act.CHEER) return
        d.ms.push().translate(-0.30f, 1.14f, 0f).rotateX(armSwing).translate(0f, -0.5f, 0f)
        when (p.action) {
            Act.SWING -> {
                d.box(0f, -0.05f, 0.05f, 0.06f, 0.85f, 0.06f, t.bark, uvPerM = 2f)
                d.ms.push().translate(0f, 0.72f, 0.05f).rotateZ(90f).scale(0.1f, 0.3f, 0.22f)
                d.bindAndDraw(d.prims?.box, t.metal); d.ms.pop()
            }
            Act.POUR -> {
                d.box(0f, -0.18f, 0.1f, 0.3f, 0.28f, 0.24f, t.metal, closed = true, uvPerM = 2f)
                d.box(0.22f, -0.06f, 0.1f, 0.22f, 0.06f, 0.06f, t.metal, closed = true, uvPerM = 2f)
            }
            Act.FISH -> {
                d.ms.push().rotateX(-38f)
                d.box(0f, -0.1f, 0f, 0.045f, 1.1f, 0.045f, t.bark, uvPerM = 2f)
                d.ms.pop()
            }
            Act.PICK, Act.WORK -> {
                d.blob(0f, -0.1f, 0.08f, 0.09f, t.mitten)
            }
            Act.SIT, Act.SOAK -> {
                // a mug, held in both hands
                d.emissive(0.25f)
                d.ms.push().translate(0.02f, -0.12f, 0.16f).scale(0.14f, 0.15f, 0.14f)
                d.uv(1.4f, 0.7f)
                d.bindAndDraw(d.prims?.cyl, t.white, 0.98f, 0.94f, 0.88f)
                d.ms.pop()
                d.emissive(0f)
            }
        }
        d.ms.pop()
    }

    /** The lamp you carry once it is properly dark. */
    fun playerLamp(g: Game) {
        val p = g.player
        val rad = Math.toRadians((p.yaw + 62f).toDouble())
        val lx = p.x + sin(rad).toFloat() * 0.34f
        val lz = p.z + cos(rad).toFloat() * 0.34f
        val ly = p.y + 0.92f + sin(p.walkPhase) * 0.03f
        d.box(lx, ly, lz, 0.1f, 0.06f, 0.1f, t.rusty, uvPerM = 2f, r = 0.66f, g = 0.62f, b = 0.62f)
        d.emissive(1f)
        d.box(lx, ly - 0.2f, lz, 0.16f, 0.22f, 0.16f, t.lantern, closed = true, uvPerM = 2.2f)
        d.emissive(0f)
        d.box(lx, ly - 0.24f, lz, 0.2f, 0.05f, 0.2f, t.rusty, uvPerM = 2f, r = 0.6f, g = 0.56f, b = 0.56f)
    }

    // -------------------------------------------------------------- Pip

    /** Pip the fox, behind the counter in a very large scarf. */
    fun pip(g: Game, x: Float, y: Float, z: Float) {
        val bob = sin(g.timeMs * 0.0022f) * 0.04f
        d.ms.push().translate(x, y + bob, z).rotateY(-18f).scale(1.3f, 1.3f, 1.3f)
        d.box(0f, 0f, 0f, 0.5f, 0.55f, 0.4f, t.foxFur, uvPerM = 2f)
        d.box(0f, 0.12f, 0.21f, 0.32f, 0.4f, 0.06f, t.foxCream, uvPerM = 2f)
        d.box(0f, 0.5f, 0.02f, 0.54f, 0.2f, 0.44f, t.scarf, closed = true, uvPerM = 2.4f)
        d.box(0f, 0.66f, 0.02f, 0.5f, 0.42f, 0.44f, t.foxFur, uvPerM = 2f)
        d.box(0f, 0.72f, 0.24f, 0.28f, 0.24f, 0.06f, t.foxCream, uvPerM = 2f)
        d.box(-0.16f, 1.06f, 0.02f, 0.16f, 0.22f, 0.1f, t.foxFur, uvPerM = 2f)
        d.box(0.16f, 1.06f, 0.02f, 0.16f, 0.22f, 0.1f, t.foxFur, uvPerM = 2f)
        d.ms.push().translate(0f, 0.18f, -0.3f).rotateX(28f).scale(0.24f, 0.5f, 0.24f)
        d.bindAndDraw(d.prims?.box, t.foxFur); d.ms.pop()
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        d.ms.push().translate(0f, 0.7f, 0.27f).scale(0.42f, 0.42f, 1f)
        d.bindAndDraw(d.prims?.quad, t.foxFace); d.ms.pop()
        glDisable(GL_BLEND)
        d.ms.pop()
    }

    // ------------------------------------------------------------ Mitten

    /**
     * The cat, asleep in front of the hearth. She is a loaf of fur with a tail
     * curled round her and one ear that twitches.
     */
    fun cat(g: Game, x: Float, y: Float, z: Float, yaw: Float, awake: Boolean) {
        val breathe = sin(g.timeMs * 0.0016f) * 0.012f
        d.ms.push().translate(x, y, z).rotateY(yaw)
        d.blob(0f, 0.16f + breathe, 0f, 0.21f, t.catFur, squash = 0.78f)
        d.blob(0.02f, 0.15f, 0.16f, 0.13f, t.catCream, squash = 0.8f)
        if (awake) {
            d.blob(0f, 0.36f, 0.16f, 0.13f, t.catFur)
            d.box(-0.07f, 0.44f, 0.16f, 0.07f, 0.09f, 0.05f, t.catFur, uvPerM = 3f)
            d.box(0.07f, 0.44f, 0.16f, 0.07f, 0.09f, 0.05f, t.catFur, uvPerM = 3f)
            glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            d.ms.push().translate(0f, 0.30f, 0.28f).scale(0.2f, 0.2f, 1f)
            d.bindAndDraw(d.prims?.quad, t.catFace); d.ms.pop()
            glDisable(GL_BLEND)
        } else {
            d.blob(0f, 0.22f, 0.19f, 0.115f, t.catFur)
            val twitch = if ((g.timeMs % 4200f) < 200f) 22f else 0f
            d.ms.push().translate(-0.06f, 0.3f, 0.19f).rotateZ(twitch)
            d.box(0f, 0f, 0f, 0.06f, 0.08f, 0.05f, t.catFur, uvPerM = 3f)
            d.ms.pop()
            d.box(0.06f, 0.3f, 0.19f, 0.06f, 0.08f, 0.05f, t.catFur, uvPerM = 3f)
            glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            d.ms.push().translate(0f, 0.2f, 0.30f).scale(0.18f, 0.18f, 1f)
            d.bindAndDraw(d.prims?.quad, t.catFace); d.ms.pop()
            glDisable(GL_BLEND)
        }
        // the tail, curled round and flicking now and then
        val flick = sin(g.timeMs * 0.0011f) * 16f
        d.ms.push().translate(-0.14f, 0.1f, -0.14f).rotateY(flick)
        d.ms.push().rotateZ(70f).scale(0.06f, 0.34f, 0.06f)
        d.bindAndDraw(d.prims?.cyl, t.catFur); d.ms.pop()
        d.ms.pop()
        d.ms.pop()
    }

    // ------------------------------------------------------- the wildlife

    /** A deer at the feeder, standing very still and watching you. */
    fun deer(g: Game, x: Float, y: Float, z: Float, yaw: Float, seed: Int) {
        val breathe = sin(g.timeMs * 0.0013f + seed) * 0.01f
        // it lowers its head to the trough, then lifts it to check on you
        val cycle = ((g.timeMs * 0.00013f + seed * 0.37f) % 1f)
        val headDown = U.smoothRange(cycle, 0.1f, 0.3f) * (1f - U.smoothRange(cycle, 0.55f, 0.75f))
        d.ms.push().translate(x, y + breathe, z).rotateY(yaw)
        // body
        d.box(0f, 0.78f, 0f, 0.44f, 0.46f, 1.15f, t.deerFur, uvPerM = 1.6f)
        d.blob(0f, 1.0f, -0.5f, 0.24f, t.deerFur, squash = 0.9f)
        // legs
        for (sx in intArrayOf(-1, 1)) for (sz in floatArrayOf(-0.42f, 0.42f)) {
            d.box(sx * 0.16f, 0f, sz, 0.09f, 0.8f, 0.09f, t.deerFur, uvPerM = 2.4f, r = 0.86f, g = 0.84f, b = 0.84f)
        }
        // neck and head, swinging down to the hay
        d.ms.push().translate(0f, 1.02f, 0.5f).rotateX(28f + headDown * 52f)
        d.box(0f, 0f, 0f, 0.19f, 0.46f, 0.19f, t.deerFur, uvPerM = 2f)
        d.ms.push().translate(0f, 0.46f, 0.08f).rotateX(-24f)
        d.box(0f, 0f, 0f, 0.19f, 0.2f, 0.34f, t.deerFur, closed = true, uvPerM = 2f)
        d.box(0f, 0.04f, 0.2f, 0.13f, 0.13f, 0.14f, t.deerFur, closed = true, uvPerM = 2.4f, r = 0.8f, g = 0.78f, b = 0.78f)
        d.box(-0.1f, 0.2f, -0.02f, 0.06f, 0.14f, 0.09f, t.deerFur, uvPerM = 3f)
        d.box(0.1f, 0.2f, -0.02f, 0.06f, 0.14f, 0.09f, t.deerFur, uvPerM = 3f)
        // small antlers
        for (sx in intArrayOf(-1, 1)) {
            d.limb(sx * 0.07f, 0.2f, -0.05f, 0.28f, 0.035f, sx * 20f, 22f, t.bark, 0.8f, 0.76f, 0.7f)
            d.limb(sx * 0.13f, 0.44f, -0.12f, 0.17f, 0.025f, sx * 44f, 40f, t.bark, 0.8f, 0.76f, 0.7f)
        }
        d.ms.pop()
        d.ms.pop()
        d.box(0f, 0.92f, -0.62f, 0.14f, 0.16f, 0.1f, t.deerFur, closed = true, uvPerM = 3f, r = 1.1f, g = 1.08f, b = 1.04f)
        d.ms.pop()
    }

    /** Chickadees round the feeder: little blue-grey scraps that never sit still. */
    fun bird(g: Game, x: Float, y: Float, z: Float, yaw: Float, flap: Float) {
        d.ms.push().translate(x, y, z).rotateY(yaw)
        d.blob(0f, 0.05f, 0f, 0.065f, t.birdBlue, squash = 0.85f)
        d.blob(0f, 0.10f, 0.05f, 0.045f, t.birdBlue)
        d.box(0f, 0.10f, 0.09f, 0.02f, 0.02f, 0.04f, t.solid(Color.parseColor("#2E2A38")), closed = true, uvPerM = 6f)
        // tail
        d.ms.push().translate(0f, 0.05f, -0.08f).rotateX(24f).scale(0.04f, 0.09f, 0.02f)
        d.bindAndDraw(d.prims?.box, t.birdBlue); d.ms.pop()
        // wings, beating when it is in the air
        for (s in intArrayOf(-1, 1)) {
            d.ms.push().translate(s * 0.055f, 0.06f, 0f).rotateZ(s * flap * 46f)
                .scale(0.03f, 0.08f, 0.09f)
            d.bindAndDraw(d.prims?.box, t.birdBlue, 0.9f, 0.9f, 0.96f)
            d.ms.pop()
        }
        d.ms.pop()
    }
}
