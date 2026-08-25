package com.cozyhollow.riverside

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.sin

/** Renders the farm plots, the choppable trees and the forageables. */
class FarmRender {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rf = RectF()

    private fun rr(l: Float, t: Float, r: Float, b: Float): RectF {
        rf.set(l, t, r, b); return rf
    }

    // ------------------------------------------------------------- plots

    fun drawPlots(c: Canvas, st: GameState, scene: Scene, time: Float, camX: Float, vw: Float) {
        val gy = World.GROUND_Y
        val open = st.tier.plots
        for (i in 0 until World.MAX_PLOTS) {
            val x = World.plotX(i)
            if (x < camX - 90f || x > camX + vw + 90f) continue
            val p = st.plots[i]
            if (i >= open) {
                // a quiet marker showing where the field can grow
                if (i < open + 4) {
                    stroke.strokeWidth = 2f
                    stroke.color = U.withAlpha(Pal.woodDeep, 0.11f)
                    c.drawRoundRect(rr(x - 30f, gy - 9f, x + 30f, gy + 9f), 6f, 6f, stroke)
                }
                continue
            }
            if (!p.tilled) {
                stroke.strokeWidth = 2.2f
                stroke.color = U.withAlpha(Pal.soilDark, 0.16f)
                c.drawRoundRect(rr(x - 30f, gy - 9f, x + 30f, gy + 9f), 6f, 6f, stroke)
                continue
            }
            drawSoil(c, x, gy, p.watered, scene)
            val cropId = p.cropId
            if (cropId != null) {
                val crop = Catalog.crops[cropId]
                if (crop != null) {
                    val prog = U.clamp01(p.growth / crop.days)
                    drawPlant(c, x, gy, crop, prog, time, scene, p.ready)
                }
            }
        }
    }

    private fun drawSoil(c: Canvas, x: Float, gy: Float, watered: Boolean, scene: Scene) {
        val base = if (watered) U.shade(Pal.soilTilled, 0.72f) else Pal.soilTilled
        fill.color = scene.sky.aerial(U.shade(base, 0.82f), 0.02f)
        c.drawRoundRect(rr(x - 32f, gy - 11f, x + 32f, gy + 10f), 7f, 7f, fill)
        fill.color = scene.sky.aerial(base, 0.02f)
        c.drawRoundRect(rr(x - 30f, gy - 10f, x + 30f, gy + 7f), 6f, 6f, fill)
        // ridges
        fill.color = scene.sky.aerial(U.shade(base, 1.16f), 0.02f)
        for (k in 0 until 3) {
            val ry = gy - 7f + k * 5.4f
            c.drawRoundRect(rr(x - 26f, ry, x + 26f, ry + 2.4f), 1.2f, 1.2f, fill)
        }
        if (watered) {
            fill.color = U.withAlpha(Color.parseColor("#9FD4E8"), 0.22f)
            c.drawRoundRect(rr(x - 28f, gy - 9f, x + 28f, gy + 5f), 5f, 5f, fill)
        }
    }

    private fun drawPlant(
        c: Canvas, x: Float, gy: Float, crop: Crop, prog: Float,
        time: Float, scene: Scene, ready: Boolean
    ) {
        val item = Catalog.item(crop.produceId)
        val green = scene.sky.aerial(Pal.leaf, 0.02f)
        val greenDark = scene.sky.aerial(Pal.leafDeep, 0.02f)
        val fruit = scene.sky.aerial(item.a, 0.02f)
        val sway = sin(time * 0.0018f + x * 0.03f) * (1.4f + prog * 2.2f)
        val h = U.lerp(10f, 62f, U.easeOut(prog))

        when {
            prog < 0.22f -> {
                stroke.strokeWidth = 3f
                stroke.color = greenDark
                c.drawLine(x, gy - 2f, x + sway * 0.4f, gy - h * 0.7f, stroke)
                fill.color = green
                c.drawCircle(x - 5f + sway * 0.3f, gy - h * 0.66f, 5f, fill)
                c.drawCircle(x + 5f + sway * 0.3f, gy - h * 0.72f, 4.4f, fill)
            }
            else -> {
                when (crop.id) {
                    "carrot", "turnip" -> rootPlant(c, x, gy, h, sway, green, greenDark, fruit, ready)
                    "corn" -> cornPlant(c, x, gy, h, sway, green, greenDark, fruit, ready)
                    "pumpkin" -> pumpkinPlant(c, x, gy, h, sway, green, greenDark, fruit, ready)
                    "berry" -> bushPlant(c, x, gy, h, sway, green, greenDark, fruit, ready, 5)
                    else -> stakedPlant(c, x, gy, h, sway, green, greenDark, fruit, ready)
                }
            }
        }

        if (ready) {
            val pulse = 0.45f + 0.55f * sin(time * 0.004f + x * 0.01f)
            fill.color = U.withAlpha(Color.parseColor("#FFF3C0"), 0.30f + pulse * 0.28f)
            c.drawCircle(x + 22f, gy - h - 12f, 3.4f + pulse * 1.6f, fill)
            c.drawCircle(x - 20f, gy - h - 2f, 2.4f + pulse * 1.2f, fill)
        }
    }

    private fun rootPlant(
        c: Canvas, x: Float, gy: Float, h: Float, sway: Float,
        green: Int, greenDark: Int, fruit: Int, ready: Boolean
    ) {
        stroke.strokeWidth = 3.4f
        stroke.color = greenDark
        for (k in -1..1) {
            path.reset()
            path.moveTo(x, gy - 2f)
            path.quadTo(x + k * 12f + sway, gy - h * 0.6f, x + k * 17f + sway * 1.4f, gy - h)
            c.drawPath(path, stroke)
        }
        fill.color = green
        c.drawOval(rr(x - 20f + sway, gy - h - 8f, x - 2f + sway, gy - h + 8f), fill)
        c.drawOval(rr(x + 2f + sway, gy - h - 10f, x + 22f + sway, gy - h + 6f), fill)
        c.drawOval(rr(x - 9f + sway * 0.5f, gy - h - 14f, x + 9f + sway * 0.5f, gy - h + 2f), fill)
        if (ready) {
            fill.color = fruit
            c.drawOval(rr(x - 13f, gy - 16f, x + 13f, gy + 4f), fill)
            fill.color = U.shade(fruit, 1.16f)
            c.drawOval(rr(x - 8f, gy - 13f, x + 2f, gy - 4f), fill)
        }
    }

    private fun cornPlant(
        c: Canvas, x: Float, gy: Float, h: Float, sway: Float,
        green: Int, greenDark: Int, fruit: Int, ready: Boolean
    ) {
        stroke.strokeWidth = 5f
        stroke.color = greenDark
        path.reset()
        path.moveTo(x, gy)
        path.quadTo(x + sway * 0.5f, gy - h * 0.6f, x + sway, gy - h * 1.25f)
        c.drawPath(path, stroke)
        fill.color = green
        for (k in 0 until 4) {
            val ly = gy - h * (0.32f + k * 0.24f)
            val dir = if (k % 2 == 0) 1f else -1f
            path.reset()
            path.moveTo(x + sway * 0.4f, ly)
            path.quadTo(x + dir * 32f + sway, ly - 12f, x + dir * 40f + sway, ly + 10f)
            path.quadTo(x + dir * 20f + sway, ly + 4f, x + sway * 0.4f, ly)
            c.drawPath(path, fill)
        }
        if (ready) {
            fill.color = fruit
            c.drawRoundRect(rr(x + 4f + sway, gy - h * 1.05f, x + 20f + sway, gy - h * 0.55f), 7f, 7f, fill)
            fill.color = U.shade(fruit, 0.84f)
            var yy = gy - h * 1.0f
            while (yy < gy - h * 0.6f) {
                c.drawRect(x + 5f + sway, yy, x + 19f + sway, yy + 2f, fill); yy += 8f
            }
        }
    }

    private fun pumpkinPlant(
        c: Canvas, x: Float, gy: Float, h: Float, sway: Float,
        green: Int, greenDark: Int, fruit: Int, ready: Boolean
    ) {
        stroke.strokeWidth = 3.6f
        stroke.color = greenDark
        path.reset()
        path.moveTo(x - 24f, gy - 3f)
        path.quadTo(x, gy - 16f - sway, x + 24f, gy - 3f)
        c.drawPath(path, stroke)
        fill.color = green
        c.drawOval(rr(x - 30f, gy - 20f, x - 8f, gy - 4f), fill)
        c.drawOval(rr(x + 8f, gy - 22f, x + 32f, gy - 6f), fill)
        c.drawOval(rr(x - 12f + sway, gy - 30f, x + 12f + sway, gy - 12f), fill)
        if (ready) {
            fill.color = U.shade(fruit, 0.86f)
            c.drawOval(rr(x - 22f, gy - 26f, x + 22f, gy + 4f), fill)
            fill.color = fruit
            c.drawOval(rr(x - 16f, gy - 27f, x + 16f, gy + 3f), fill)
            fill.color = U.shade(fruit, 1.15f)
            c.drawOval(rr(x - 6f, gy - 27f, x + 6f, gy + 3f), fill)
            fill.color = greenDark
            c.drawRect(x - 3f, gy - 33f, x + 3f, gy - 24f, fill)
        }
    }

    private fun bushPlant(
        c: Canvas, x: Float, gy: Float, h: Float, sway: Float,
        green: Int, greenDark: Int, fruit: Int, ready: Boolean, berries: Int
    ) {
        fill.color = greenDark
        c.drawCircle(x - 14f + sway * 0.4f, gy - h * 0.45f, 15f, fill)
        c.drawCircle(x + 14f + sway * 0.4f, gy - h * 0.42f, 14f, fill)
        fill.color = green
        c.drawCircle(x + sway * 0.6f, gy - h * 0.62f, 17f, fill)
        c.drawCircle(x - 11f + sway * 0.5f, gy - h * 0.52f, 12f, fill)
        c.drawCircle(x + 12f + sway * 0.5f, gy - h * 0.55f, 11f, fill)
        if (ready) {
            fill.color = fruit
            for (k in 0 until berries) {
                val bx = x - 16f + k * 8.6f + sway * 0.5f
                val by = gy - h * (0.34f + U.hash(k * 17) * 0.34f)
                c.drawCircle(bx, by, 4.6f, fill)
            }
        }
    }

    private fun stakedPlant(
        c: Canvas, x: Float, gy: Float, h: Float, sway: Float,
        green: Int, greenDark: Int, fruit: Int, ready: Boolean
    ) {
        stroke.strokeWidth = 4f
        stroke.color = greenDark
        path.reset()
        path.moveTo(x, gy)
        path.quadTo(x + sway * 0.5f, gy - h * 0.5f, x + sway, gy - h)
        c.drawPath(path, stroke)
        fill.color = green
        for (k in 0 until 3) {
            val ly = gy - h * (0.34f + k * 0.26f)
            val dir = if (k % 2 == 0) 1f else -1f
            c.drawOval(rr(
                x + dir * 6f + sway * 0.5f - 14f, ly - 8f,
                x + dir * 6f + sway * 0.5f + 14f, ly + 8f
            ), fill)
        }
        if (ready) {
            fill.color = fruit
            c.drawCircle(x - 11f + sway * 0.7f, gy - h * 0.62f, 8.4f, fill)
            c.drawCircle(x + 12f + sway * 0.8f, gy - h * 0.78f, 7.6f, fill)
            fill.color = U.withAlpha(Color.WHITE, 0.4f)
            c.drawCircle(x - 14f + sway * 0.7f, gy - h * 0.66f, 2.6f, fill)
        }
    }

    // ------------------------------------------------------------- trees

    fun drawTrees(
        c: Canvas, st: GameState, scene: Scene, time: Float,
        camX: Float, vw: Float, shakeIdx: Int, shakeAmt: Float
    ) {
        val gy = World.GROUND_Y
        for (i in 0 until World.TREE_COUNT) {
            val t = World.trees[i]
            if (t.x < camX - 140f || t.x > camX + vw + 140f) continue
            val standing = World.treeStanding(st, i)
            if (!standing) {
                stump(c, t.x, gy, t.scale, scene)
                continue
            }
            val shake = if (i == shakeIdx) sin(shakeAmt * 46f) * 5.5f * U.clamp01(shakeAmt * 3f) else 0f
            val breeze = sin(time * 0.0011f + i) * 1.6f
            scene.softShadow(c, t.x, gy + 2f, 34f * t.scale, 9f * t.scale, 0.20f)
            c.save()
            c.translate(t.x + shake + breeze, gy)
            c.translate(0f, 0f)
            val green = if (t.kind == 0) Color.parseColor("#3F7A55") else Color.parseColor("#5E9A52")
            val tint = scene.sky.aerial(green, 0.02f)
            val trunk = scene.sky.aerial(Color.parseColor("#7A5A3E"), 0.02f)
            if (t.kind == 0) scene.pine(c, 0f, 0f, t.scale * 1.25f, tint, trunk)
            else scene.oak(c, 0f, 0f, t.scale * 1.25f, tint, trunk)
            c.restore()
        }
    }

    private fun stump(c: Canvas, x: Float, gy: Float, s: Float, scene: Scene) {
        scene.softShadow(c, x, gy + 2f, 18f * s, 6f * s, 0.16f)
        fill.color = scene.sky.aerial(Color.parseColor("#6B4A32"), 0.02f)
        c.drawRoundRect(rr(x - 13f * s, gy - 22f * s, x + 13f * s, gy), 4f, 4f, fill)
        fill.color = scene.sky.aerial(Color.parseColor("#A87F55"), 0.02f)
        c.drawOval(rr(x - 13f * s, gy - 27f * s, x + 13f * s, gy - 17f * s), fill)
        stroke.strokeWidth = 1.6f
        stroke.color = scene.sky.aerial(Color.parseColor("#7E5C3C"), 0.02f)
        c.drawOval(rr(x - 7f * s, gy - 25f * s, x + 7f * s, gy - 19f * s), stroke)
        fill.color = scene.sky.aerial(Pal.leaf, 0.02f)
        c.drawCircle(x + 15f * s, gy - 4f, 5f * s, fill)
    }

    // ------------------------------------------------------------ forage

    fun drawForage(c: Canvas, st: GameState, scene: Scene, time: Float, camX: Float, vw: Float) {
        val gy = World.GROUND_Y
        for (i in 0 until World.FORAGE_COUNT) {
            val f = World.forage[i]
            if (f.x < camX - 60f || f.x > camX + vw + 60f) continue
            if (!World.forageAvailable(st, i)) continue
            val item = Catalog.item(f.itemId)
            val bob = sin(time * 0.0022f + i * 1.4f) * 2.6f
            scene.softShadow(c, f.x, gy + 3f, 13f, 4.5f, 0.18f)
            val pulse = 0.5f + 0.5f * sin(time * 0.003f + i)
            fill.color = U.withAlpha(Color.parseColor("#FFF3C0"), 0.14f + pulse * 0.12f)
            c.drawCircle(f.x, gy - 14f + bob, 20f, fill)
            IconDraw.draw(c, item, f.x, gy - 14f + bob, 34f, fill)
        }
    }

    /** Nearest interactable tree index within reach, or -1. */
    fun nearestTree(st: GameState, x: Float, reach: Float): Int {
        var best = -1
        var bestD = reach
        for (i in 0 until World.TREE_COUNT) {
            if (!World.treeStanding(st, i)) continue
            val d = abs(World.trees[i].x - x)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun nearestForage(st: GameState, x: Float, reach: Float): Int {
        var best = -1
        var bestD = reach
        for (i in 0 until World.FORAGE_COUNT) {
            if (!World.forageAvailable(st, i)) continue
            val d = abs(World.forage[i].x - x)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun nearestPlot(st: GameState, x: Float, reach: Float): Int {
        var best = -1
        var bestD = reach
        for (i in 0 until st.tier.plots) {
            val d = abs(World.plotX(i) - x)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
