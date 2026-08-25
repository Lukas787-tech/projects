package com.cozyhollow.riverside

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object Act {
    const val NONE = 0
    const val SWING = 1     // axe / hoe
    const val WATER = 2
    const val PICK = 3
    const val FISH = 4
    const val CHEER = 5
}

/** The little farmer: fully procedural, animated with a few sine waves. */
class Player {

    var x = World.CABIN_X + 140f
    var vx = 0f
    var facing = 1
    var walkPhase = 0f
    var idlePhase = 0f
    var action = Act.NONE
    var actionT = 0f
    var actionDur = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rf = RectF()

    private val skin = Color.parseColor("#F2C79E")
    private val skinDark = Color.parseColor("#D9A87E")
    private val hair = Color.parseColor("#6B4A32")
    private val shirt = Color.parseColor("#7FA9D9")
    private val shirtDark = Color.parseColor("#5F86B4")
    private val pants = Color.parseColor("#7C6A56")
    private val boots = Color.parseColor("#5A4634")
    private val hatCol = Color.parseColor("#E2C079")
    private val scarf = Color.parseColor("#D0707A")

    val speed: Float get() = 240f

    val busy: Boolean get() = action != Act.NONE && action != Act.FISH

    fun startAction(a: Int, dur: Float) {
        action = a; actionT = 0f; actionDur = dur
    }

    fun stopAction() {
        action = Act.NONE; actionT = 0f; actionDur = 0f
    }

    fun update(dt: Float, moveDir: Float) {
        if (action != Act.NONE) {
            actionT += dt
            if (action != Act.FISH && actionT >= actionDur) stopAction()
        }
        val canMove = !busy && action != Act.FISH
        vx = if (canMove) moveDir * speed else 0f
        if (abs(vx) > 1f) {
            x += vx * dt
            walkPhase += dt * 9.5f
            facing = if (vx > 0) 1 else -1
        } else {
            walkPhase *= (1f - kotlin.math.min(1f, dt * 8f))
        }
        idlePhase += dt
        x = x.coerceIn(World.WALK_MIN, World.WALK_MAX)
    }

    private fun rr(l: Float, t: Float, r: Float, b: Float): RectF {
        rf.set(l, t, r, b); return rf
    }

    /** Draws the player standing on [gy]. Scene tint is applied by the caller. */
    fun draw(c: Canvas, gy: Float, tint: Int, tintStrength: Float) {
        val moving = abs(vx) > 1f
        val bob = if (moving) abs(sin(walkPhase)) * 3.4f else sin(idlePhase * 2.1f) * 1.8f
        val baseY = gy - bob
        val f = facing.toFloat()

        // ---------- shadow ----------
        fill.color = U.withAlpha(Pal.shadow, 0.22f)
        c.drawOval(rr(x - 26f, gy - 7f, x + 26f, gy + 7f), fill)

        c.save()
        c.translate(x, baseY)
        c.scale(f, 1f)

        val swing = when (action) {
            Act.SWING -> sin(U.clamp01(actionT / actionDur) * 3.14159f) * 1.5f
            Act.WATER -> U.smooth(U.clamp01(actionT / actionDur * 2f)) * 0.7f
            Act.PICK -> sin(U.clamp01(actionT / actionDur) * 3.14159f) * 0.8f
            Act.CHEER -> sin(actionT * 12f) * 0.5f + 1.1f
            else -> 0f
        }

        // ---------- legs ----------
        val legSwing = if (moving) sin(walkPhase) * 9f else 0f
        drawLeg(c, -6f, legSwing, tint, tintStrength)
        drawLeg(c, 6f, -legSwing, tint, tintStrength)

        // ---------- body ----------
        fill.color = tinted(shirt, tint, tintStrength)
        c.drawRoundRect(rr(-15f, -58f, 15f, -18f), 11f, 11f, fill)
        fill.color = tinted(shirtDark, tint, tintStrength)
        c.drawRoundRect(rr(-15f, -30f, 15f, -18f), 8f, 8f, fill)
        // overall straps
        fill.color = tinted(pants, tint, tintStrength)
        c.drawRect(-11f, -56f, -6f, -26f, fill)
        c.drawRect(5f, -56f, 10f, -26f, fill)

        // ---------- scarf ----------
        fill.color = tinted(scarf, tint, tintStrength)
        c.drawRoundRect(rr(-14f, -64f, 14f, -54f), 5f, 5f, fill)
        val flap = if (moving) sin(walkPhase * 0.8f) * 6f else sin(idlePhase * 1.4f) * 3f
        path.reset()
        path.moveTo(-12f, -60f)
        path.quadTo(-22f - flap * 0.4f, -50f, -18f - flap, -34f)
        path.lineTo(-9f - flap * 0.6f, -36f)
        path.quadTo(-13f, -50f, -6f, -58f)
        path.close()
        c.drawPath(path, fill)

        // ---------- arms ----------
        drawArm(c, -1f, swing, moving, tint, tintStrength)

        // ---------- head ----------
        val headY = -76f
        fill.color = tinted(skin, tint, tintStrength)
        c.drawCircle(0f, headY, 18f, fill)
        fill.color = tinted(skinDark, tint, tintStrength)
        c.drawCircle(0f, headY + 6f, 15f, fill)
        fill.color = tinted(skin, tint, tintStrength)
        c.drawCircle(0f, headY, 18f, fill)
        // hair
        fill.color = tinted(hair, tint, tintStrength)
        path.reset()
        path.addArc(rr(-19f, headY - 20f, 19f, headY + 16f), 180f, 180f)
        path.close()
        c.drawPath(path, fill)
        c.drawCircle(-15f, headY - 2f, 6f, fill)
        // face
        fill.color = Pal.ink
        val blink = if ((idlePhase % 4.2f) < 0.14f) 0.25f else 1f
        c.drawOval(rr(2f, headY - 3f - 3f * blink, 6f, headY - 3f + 3f * blink), fill)
        c.drawOval(rr(11f, headY - 3f - 3f * blink, 15f, headY - 3f + 3f * blink), fill)
        fill.color = U.withAlpha(Color.parseColor("#E8907E"), 0.5f)
        c.drawCircle(4f, headY + 5f, 3.6f, fill)
        c.drawCircle(15f, headY + 5f, 3.2f, fill)
        stroke.strokeWidth = 1.8f
        stroke.color = U.withAlpha(Pal.ink, 0.75f)
        path.reset()
        path.moveTo(7f, headY + 5f)
        path.quadTo(9.5f, headY + 8.5f, 12f, headY + 5f)
        c.drawPath(path, stroke)

        // ---------- straw hat ----------
        fill.color = tinted(hatCol, tint, tintStrength)
        c.drawOval(rr(-26f, headY - 15f, 26f, headY - 5f), fill)
        c.drawRoundRect(rr(-13f, headY - 27f, 13f, headY - 11f), 9f, 9f, fill)
        fill.color = tinted(U.shade(hatCol, 0.84f), tint, tintStrength)
        c.drawRect(-13f, headY - 16f, 13f, headY - 12f, fill)

        c.restore()

        // ---------- held tool ----------
        drawTool(c, x, baseY, f, swing, tint, tintStrength)
    }

    private fun drawLeg(c: Canvas, ox: Float, swing: Float, tint: Int, ts: Float) {
        fill.color = tinted(pants, tint, ts)
        c.drawRoundRect(rr(ox - 6f, -24f, ox + 6f, -4f + swing * 0.2f), 5f, 5f, fill)
        fill.color = tinted(boots, tint, ts)
        c.drawRoundRect(rr(ox - 7.5f + swing * 0.5f, -8f, ox + 8f + swing * 0.5f, 2f), 4f, 4f, fill)
    }

    private fun drawArm(c: Canvas, ox: Float, swing: Float, moving: Boolean, tint: Int, ts: Float) {
        val sw = if (action != Act.NONE) swing else if (moving) sin(walkPhase + 3.14159f) * 0.5f else 0f
        c.save()
        c.translate(12f, -52f)
        c.rotate(-sw * 42f)
        fill.color = tinted(shirt, tint, ts)
        c.drawRoundRect(rr(-5f, -5f, 5f, 26f), 5f, 5f, fill)
        fill.color = tinted(skin, tint, ts)
        c.drawCircle(0f, 27f, 6f, fill)
        c.restore()
    }

    private fun drawTool(c: Canvas, px: Float, py: Float, f: Float, swing: Float, tint: Int, ts: Float) {
        if (action == Act.NONE || action == Act.CHEER) return
        c.save()
        c.translate(px, py)
        c.scale(f, 1f)
        c.translate(12f, -52f)
        c.rotate(-swing * 42f)
        c.translate(0f, 27f)
        when (action) {
            Act.SWING -> {
                stroke.strokeWidth = 5f
                stroke.color = tinted(Pal.woodDark, tint, ts)
                c.drawLine(0f, 4f, 0f, -44f, stroke)
                fill.color = tinted(Color.parseColor("#B8BEC6"), tint, ts)
                path.reset()
                path.moveTo(-3f, -44f); path.lineTo(16f, -50f); path.lineTo(16f, -34f); path.lineTo(-3f, -32f)
                path.close()
                c.drawPath(path, fill)
            }
            Act.WATER -> {
                fill.color = tinted(Color.parseColor("#8FA8B8"), tint, ts)
                c.drawRoundRect(rr(-8f, -10f, 14f, 12f), 5f, 5f, fill)
                stroke.strokeWidth = 4f
                stroke.color = tinted(Color.parseColor("#8FA8B8"), tint, ts)
                c.drawLine(14f, -6f, 30f, 2f, stroke)
            }
            Act.FISH -> {
                stroke.strokeWidth = 4f
                stroke.color = tinted(Pal.woodDark, tint, ts)
                c.drawLine(-2f, 6f, 26f, -48f, stroke)
            }
            Act.PICK -> {
                fill.color = tinted(Pal.leaf, tint, ts)
                c.drawCircle(4f, -4f, 6f, fill)
            }
        }
        c.restore()
    }

    /** Where the fishing line leaves the rod, in world space. */
    fun rodTipX(): Float = x + facing * 34f
    fun rodTipY(gy: Float): Float = gy - 100f

    private fun tinted(base: Int, tint: Int, strength: Float): Int =
        if (strength <= 0.01f) base else U.lerpColor(base, tint, strength)
}
