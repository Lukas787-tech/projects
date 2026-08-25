package com.cozyhollow.riverside

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the whole valley. Every layer asks the current [MutableSkyKey] for its tint,
 * so dawn, rain and midnight all read as one coherent picture.
 */
class Scene {

    val sky = MutableSkyKey()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val signText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private val path = Path()
    private val rf = RectF()

    private var vw = 1280f
    private var vh = World.VIEW_H

    // cached shaders
    private var skyShader: LinearGradient? = null
    private var skyTop = 0; private var skyMid = 0; private var skyHor = 0
    private var soilShader: LinearGradient? = null
    private var waterShader: LinearGradient? = null
    private var waterTopC = 0

    fun setView(w: Float, h: Float) {
        if (w != vw || h != vh) {
            vw = w; vh = h
            skyShader = null; soilShader = null; waterShader = null
        }
    }

    private fun rr(l: Float, t: Float, r: Float, b: Float): RectF {
        rf.set(l, t, r, b); return rf
    }

    // ------------------------------------------------------------------ sky

    fun drawSky(c: Canvas) {
        if (skyShader == null || skyTop != sky.top || skyMid != sky.mid || skyHor != sky.horizon) {
            skyTop = sky.top; skyMid = sky.mid; skyHor = sky.horizon
            skyShader = LinearGradient(
                0f, 0f, 0f, World.GROUND_Y + 40f,
                intArrayOf(sky.top, sky.mid, sky.horizon),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }
        grad.shader = skyShader
        c.drawRect(0f, 0f, vw, World.GROUND_Y + 42f, grad)
        grad.shader = null
    }

    fun drawStars(c: Canvas, time: Float) {
        val a = sky.starAlpha
        if (a <= 0.02f) return
        fill.color = Color.WHITE
        for (i in 0 until 90) {
            val sx = U.hash(i * 37 + 1) * vw
            val sy = U.hash(i * 61 + 5) * World.GROUND_Y * 0.72f
            val tw = 0.55f + 0.45f * sin(time * 0.0016f * (1f + U.hash(i * 13) * 3f) + i)
            val size = 1.1f + U.hash(i * 97) * 1.7f
            fill.alpha = (a * tw * 235f).toInt().coerceIn(0, 255)
            c.drawCircle(sx, sy, size, fill)
        }
        fill.alpha = 255
    }

    fun drawSunMoon(c: Canvas, minutes: Float) {
        // Sun arcs from 5:00 to 19:30, the moon takes the rest of the clock.
        val sunT = U.norm(minutes, 300f, 1170f)
        if (minutes in 285f..1185f) {
            drawOrb(c, sunT, sky.sunColor, sky.sunGlow, 34f, 1f)
        }
        val moonMin = if (minutes >= 1140f) minutes - 1140f else minutes + 300f
        val moonT = U.norm(moonMin, 0f, 600f)
        if (minutes >= 1130f || minutes <= 320f) {
            val fade = if (minutes >= 1130f) U.smoothRange(minutes, 1130f, 1210f)
            else 1f - U.smoothRange(minutes, 250f, 330f)
            drawMoon(c, moonT, fade)
        }
    }

    private fun drawOrb(c: Canvas, t: Float, core: Int, glow: Int, radius: Float, alpha: Float) {
        val x = U.lerp(vw * 0.12f, vw * 0.88f, t)
        val y = World.GROUND_Y * 0.86f - sin(t * PI.toFloat()) * World.GROUND_Y * 0.72f
        grad.shader = RadialGradient(
            x, y, radius * 5.2f,
            intArrayOf(U.withAlpha(glow, 0.55f * alpha), U.withAlpha(glow, 0.16f * alpha), U.withAlpha(glow, 0f)),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(x, y, radius * 5.2f, grad)
        grad.shader = null
        fill.color = U.withAlpha(core, alpha)
        c.drawCircle(x, y, radius, fill)
        fill.color = U.withAlpha(Color.WHITE, 0.35f * alpha)
        c.drawCircle(x - radius * 0.22f, y - radius * 0.22f, radius * 0.6f, fill)
    }

    private fun drawMoon(c: Canvas, t: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        val x = U.lerp(vw * 0.14f, vw * 0.86f, t)
        val y = World.GROUND_Y * 0.84f - sin(t * PI.toFloat()) * World.GROUND_Y * 0.66f
        val r = 26f
        grad.shader = RadialGradient(
            x, y, r * 5f,
            intArrayOf(U.withAlpha(Color.parseColor("#BFD2FF"), 0.30f * alpha), U.withAlpha(Color.parseColor("#BFD2FF"), 0f)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(x, y, r * 5f, grad)
        grad.shader = null
        fill.color = U.withAlpha(Color.parseColor("#F6F3E2"), alpha)
        c.drawCircle(x, y, r, fill)
        fill.color = U.withAlpha(Color.parseColor("#E2DECB"), alpha * 0.85f)
        c.drawCircle(x + r * 0.32f, y - r * 0.18f, r * 0.20f, fill)
        c.drawCircle(x - r * 0.24f, y + r * 0.3f, r * 0.14f, fill)
        c.drawCircle(x - r * 0.05f, y - r * 0.42f, r * 0.11f, fill)
    }

    fun drawClouds(c: Canvas, camX: Float, time: Float, quality: Int) {
        val n = when (quality) { 0 -> 5; 1 -> 8; else -> 12 }
        for (i in 0 until n) {
            val speed = 5f + U.hash(i * 41 + 3) * 9f
            val span = vw + 620f
            var x = (U.hash(i * 17 + 7) * span + time * 0.001f * speed) % span - 310f
            x -= camX * 0.06f
            x = ((x % span) + span) % span - 310f
            val y = 42f + U.hash(i * 29 + 11) * World.GROUND_Y * 0.42f
            val s = 0.55f + U.hash(i * 53 + 19) * 0.9f
            val base = U.lerpColor(Color.WHITE, sky.horizon, 0.18f)
            val alpha = 0.42f + U.hash(i * 71) * 0.32f
            cloud(c, x, y, s, U.withAlpha(sky.aerial(base, 0.55f), alpha))
        }
    }

    private fun cloud(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        fill.color = color
        c.drawCircle(x, y, 34f * s, fill)
        c.drawCircle(x + 40f * s, y - 12f * s, 44f * s, fill)
        c.drawCircle(x + 88f * s, y + 2f * s, 32f * s, fill)
        c.drawCircle(x + 44f * s, y + 20f * s, 30f * s, fill)
        fill.color = U.withAlpha(Color.WHITE, Color.alpha(color) / 255f * 0.5f)
        c.drawCircle(x + 34f * s, y - 22f * s, 26f * s, fill)
    }

    // ------------------------------------------------------------- mountains

    fun drawMountains(c: Canvas, camX: Float) {
        ridge(c, camX, 0.055f, World.GROUND_Y - 118f, 132f, 0.78f, Color.parseColor("#6E7EA8"), 11)
        ridge(c, camX, 0.10f, World.GROUND_Y - 74f, 96f, 0.62f, Color.parseColor("#5E7C86"), 27)
    }

    private fun ridge(
        c: Canvas, camX: Float, parallax: Float, baseY: Float,
        amp: Float, distance: Float, color: Int, seed: Int
    ) {
        val off = camX * parallax
        path.reset()
        path.moveTo(-20f, World.GROUND_Y + 10f)
        var x = -20f
        val step = 42f
        while (x <= vw + 40f) {
            val wx = (x + off) / 190f
            val hgt = (U.noise(wx, seed) * 0.62f + U.noise(wx * 2.3f, seed + 5) * 0.38f)
            path.lineTo(x, baseY - hgt * amp)
            x += step
        }
        path.lineTo(vw + 40f, World.GROUND_Y + 10f)
        path.close()
        fill.color = sky.aerial(color, distance)
        c.drawPath(path, fill)
    }

    // ---------------------------------------------------------------- forest

    fun drawForestLayer(c: Canvas, camX: Float, parallax: Float, baseY: Float, scale: Float, distance: Float, seed: Int, spacing: Float) {
        val off = camX * parallax
        val first = ((off - 120f) / spacing).toInt()
        val last = ((off + vw + 120f) / spacing).toInt()
        val trunkC = sky.aerial(Color.parseColor("#5B4432"), distance)
        for (i in first..last) {
            val jitter = U.hash(i * 89 + seed)
            val x = i * spacing + jitter * spacing * 0.6f - off
            if (x < -140f || x > vw + 140f) continue
            val s = scale * (0.78f + U.hash(i * 131 + seed) * 0.44f)
            val kind = if (U.hash(i * 37 + seed + 3) < 0.58f) 0 else 1
            val green = if (kind == 0) Color.parseColor("#3F7A55") else Color.parseColor("#5E9A52")
            val tint = sky.aerial(U.shade(green, 0.86f + U.hash(i * 7 + seed) * 0.3f), distance)
            if (kind == 0) pine(c, x, baseY, s, tint, trunkC) else oak(c, x, baseY, s, tint, trunkC)
        }
    }

    fun pine(c: Canvas, x: Float, groundY: Float, s: Float, green: Int, trunk: Int) {
        val h = 150f * s
        fill.color = trunk
        c.drawRect(x - 6f * s, groundY - h * 0.30f, x + 6f * s, groundY, fill)
        var tierY = groundY - h * 0.24f
        var wdt = 46f * s
        for (i in 0 until 3) {
            fill.color = if (i == 0) U.shade(green, 0.88f) else green
            path.reset()
            path.moveTo(x - wdt, tierY)
            path.lineTo(x + wdt, tierY)
            path.lineTo(x, tierY - h * 0.34f)
            path.close()
            c.drawPath(path, fill)
            tierY -= h * 0.21f
            wdt *= 0.76f
        }
    }

    fun oak(c: Canvas, x: Float, groundY: Float, s: Float, green: Int, trunk: Int) {
        val h = 132f * s
        fill.color = trunk
        path.reset()
        path.moveTo(x - 8f * s, groundY)
        path.lineTo(x - 5f * s, groundY - h * 0.46f)
        path.lineTo(x + 5f * s, groundY - h * 0.46f)
        path.lineTo(x + 8f * s, groundY)
        path.close()
        c.drawPath(path, fill)
        fill.color = U.shade(green, 0.86f)
        c.drawCircle(x - 26f * s, groundY - h * 0.60f, 30f * s, fill)
        c.drawCircle(x + 26f * s, groundY - h * 0.58f, 27f * s, fill)
        fill.color = green
        c.drawCircle(x, groundY - h * 0.78f, 36f * s, fill)
        c.drawCircle(x - 20f * s, groundY - h * 0.68f, 25f * s, fill)
        fill.color = U.shade(green, 1.13f)
        c.drawCircle(x + 8f * s, groundY - h * 0.90f, 20f * s, fill)
    }

    // ---------------------------------------------------------------- ground

    /** The grass strip plus the cross-section of soil beneath it. */
    fun drawGround(c: Canvas, camX: Float, wet: Float) {
        val gy = World.GROUND_Y
        if (soilShader == null) {
            soilShader = LinearGradient(
                0f, gy, 0f, vh,
                intArrayOf(Pal.soil, U.shade(Pal.soil, 0.78f), U.shade(Pal.soilDark, 0.62f)),
                floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
            )
        }
        // soil body
        grad.shader = soilShader
        c.drawRect(0f, gy, vw, vh, grad)
        grad.shader = null
        fill.color = U.withAlpha(sky.ambient, sky.ambientStrength * 0.85f)
        c.drawRect(0f, gy, vw, vh, fill)

        // pebbles + roots scattered deterministically in world space
        val startI = ((camX - 60f) / 54f).toInt()
        val endI = ((camX + vw + 60f) / 54f).toInt()
        for (i in startI..endI) {
            val wx = i * 54f + U.hash(i * 13 + 2) * 40f
            val sx = wx - camX
            if (sx < -30f || sx > vw + 30f) continue
            val depth = gy + 34f + U.hash(i * 71 + 5) * (vh - gy - 50f)
            fill.color = U.withAlpha(Pal.stone, 0.20f + U.hash(i * 29) * 0.16f)
            val ps = 4f + U.hash(i * 47) * 8f
            c.drawOval(rr(sx - ps, depth - ps * 0.66f, sx + ps, depth + ps * 0.66f), fill)
        }

        // grass band
        fill.color = sky.aerial(Pal.grassMid, 0.06f)
        c.drawRect(0f, gy, vw, gy + 22f, fill)
        fill.color = sky.aerial(if (wet > 0.1f) U.lerpColor(Pal.grassTop, Color.parseColor("#6FA95C"), wet) else Pal.grassTop, 0.04f)
        c.drawRect(0f, gy, vw, gy + 13f, fill)

        // grass fringe
        stroke.strokeWidth = 3f
        stroke.color = sky.aerial(U.shade(Pal.grassTop, 1.08f), 0.04f)
        var i = startI
        while (i <= endI) {
            val wx = i * 54f
            for (k in 0 until 5) {
                val sx = wx + k * 11f + U.hash(i * 91 + k) * 8f - camX
                if (sx < -6f || sx > vw + 6f) continue
                val hh = 6f + U.hash(i * 17 + k * 5) * 9f
                c.drawLine(sx, gy + 2f, sx + (U.hash(i + k * 3) - 0.5f) * 5f, gy - hh, stroke)
            }
            i++
        }
    }

    /** Little flowers and stones sitting on the grass line. */
    fun drawGroundDetail(c: Canvas, camX: Float, time: Float) {
        val gy = World.GROUND_Y
        val startI = ((camX - 60f) / 96f).toInt()
        val endI = ((camX + vw + 60f) / 96f).toInt()
        for (i in startI..endI) {
            if (U.hash(i * 311 + 7) > 0.62f) continue
            val wx = i * 96f + U.hash(i * 53) * 70f
            if (wx > World.RIVER_EDGE - 30f) continue
            val sx = wx - camX
            if (sx < -20f || sx > vw + 20f) continue
            val sway = sin(time * 0.0016f + i) * 2.2f
            val col = when ((U.hash(i * 7 + 1) * 3).toInt()) {
                0 -> Color.parseColor("#E8A0C0")
                1 -> Color.parseColor("#F2D45A")
                else -> Color.parseColor("#C9A9E8")
            }
            stroke.strokeWidth = 2.4f
            stroke.color = sky.aerial(Pal.leafDeep, 0.03f)
            c.drawLine(sx, gy + 1f, sx + sway, gy - 12f, stroke)
            fill.color = sky.aerial(col, 0.03f)
            c.drawCircle(sx + sway, gy - 14f, 4.2f, fill)
            fill.color = sky.aerial(Color.parseColor("#FFF3C8"), 0.03f)
            c.drawCircle(sx + sway, gy - 14f, 1.7f, fill)
        }
    }

    /** Blurred bushes at the very front for depth. */
    fun drawForeground(c: Canvas, camX: Float, quality: Int) {
        if (quality < 1) return
        val off = camX * 1.22f
        val spacing = 340f
        val first = ((off - 200f) / spacing).toInt()
        val last = ((off + vw + 200f) / spacing).toInt()
        for (i in first..last) {
            val x = i * spacing + U.hash(i * 43 + 9) * 190f - off
            if (x < -250f || x > vw + 250f) continue
            val col = U.withAlpha(U.shade(sky.aerial(Pal.leafDeep, 0.0f), 0.42f), 0.92f)
            fill.color = col
            val y = vh + 30f
            val s = 1.1f + U.hash(i * 71 + 3) * 0.7f
            c.drawCircle(x - 70f * s, y - 60f * s, 78f * s, fill)
            c.drawCircle(x + 10f * s, y - 82f * s, 92f * s, fill)
            c.drawCircle(x + 92f * s, y - 54f * s, 70f * s, fill)
        }
    }

    // ----------------------------------------------------------------- river

    fun drawRiver(c: Canvas, camX: Float, time: Float, quality: Int, rain: Float) {
        val edge = World.RIVER_EDGE - camX
        if (edge > vw) return
        val wy = World.WATER_Y
        val left = kotlin.math.max(edge, -20f)

        // bank slope from grass down to the water
        fill.color = sky.aerial(Pal.soil, 0.05f)
        path.reset()
        path.moveTo(edge - 46f, World.GROUND_Y)
        path.lineTo(edge + 26f, wy + 6f)
        path.lineTo(edge + 26f, vh)
        path.lineTo(edge - 46f, vh)
        path.close()
        c.drawPath(path, fill)

        if (waterShader == null || waterTopC != sky.horizon) {
            waterTopC = sky.horizon
            waterShader = LinearGradient(
                0f, wy, 0f, vh,
                intArrayOf(
                    U.lerpColor(Pal.waterTop, sky.horizon, 0.45f),
                    Pal.waterMid,
                    U.shade(Pal.waterDeep, 0.72f)
                ),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
            )
        }
        grad.shader = waterShader
        c.drawRect(left, wy, vw, vh, grad)
        grad.shader = null
        fill.color = U.withAlpha(sky.ambient, sky.ambientStrength * 0.9f)
        c.drawRect(left, wy, vw, vh, fill)

        // moving highlight bands
        val bands = if (quality == 0) 5 else 10
        for (i in 0 until bands) {
            val fy = wy + 14f + i * ((vh - wy) / bands)
            val ph = time * 0.0009f * (0.6f + i * 0.12f)
            val a = 0.05f + 0.07f * (1f - i.toFloat() / bands)
            fill.color = U.withAlpha(Pal.foam, a)
            var x = left
            while (x < vw) {
                val wgl = U.wobble(x * 0.012f + ph, 1f, 2.7f)
                val len = 30f + wgl * 22f
                if (len > 12f) c.drawRoundRect(rr(x, fy, x + len, fy + 3.4f), 2f, 2f, fill)
                x += 78f
            }
        }

        // sparkles
        if (quality >= 1) {
            for (i in 0 until 26) {
                val sx = left + U.hash(i * 31 + 3) * (vw - left)
                val sy = wy + 8f + U.hash(i * 61 + 7) * (vh - wy) * 0.6f
                val tw = sin(time * 0.004f + i * 1.7f)
                if (tw < 0.55f) continue
                fill.color = U.withAlpha(Color.WHITE, (tw - 0.55f) * 1.4f)
                c.drawCircle(sx, sy, 2.4f, fill)
            }
        }

        // surface line with a soft wobble
        stroke.strokeWidth = 3.2f
        stroke.color = U.withAlpha(Pal.foam, 0.72f)
        path.reset()
        var x = left
        path.moveTo(x, wy)
        while (x < vw) {
            x += 16f
            path.lineTo(x, wy + U.wobble(x * 0.05f + time * 0.0022f, 1f, 2.3f) * 2.6f)
        }
        c.drawPath(path, stroke)

        // rain dimples
        if (rain > 0.05f) {
            for (i in 0 until (30 * rain).toInt()) {
                val t2 = (time * 0.002f + U.hash(i * 91)) % 1f
                val sx = left + U.hash(i * 17 + 5) * (vw - left)
                val sy = wy + 6f + U.hash(i * 53 + 9) * (vh - wy) * 0.5f
                stroke.strokeWidth = 1.6f
                stroke.color = U.withAlpha(Pal.foam, (1f - t2) * 0.5f * rain)
                c.drawCircle(sx, sy, 3f + t2 * 16f, stroke)
            }
        }
    }

    // ----------------------------------------------------------------- cabin

    fun drawCabin(c: Canvas, level: Int, night: Float, time: Float) {
        val gy = World.GROUND_Y
        val x = World.CABIN_X
        val warm = Color.parseColor("#FFD98A")
        // ground shadow
        fill.color = U.withAlpha(Pal.shadow, 0.20f)
        val shW = 130f + level * 34f
        c.drawOval(rr(x - shW, gy - 8f, x + shW, gy + 16f), fill)

        when (level) {
            1 -> cabinT1(c, x, gy, warm, night)
            2 -> cabinT2(c, x, gy, warm, night)
            3 -> cabinT3(c, x, gy, warm, night)
            else -> cabinT4(c, x, gy, warm, night)
        }
        chimneySmoke(c, x + 52f + level * 12f, gy - (120f + level * 34f), time)
    }

    private fun logWall(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        fill.color = sky.aerial(Pal.wood, 0.02f)
        c.drawRect(l, t, r, b, fill)
        fill.color = sky.aerial(U.shade(Pal.wood, 0.88f), 0.02f)
        var y = t + 15f
        while (y < b) {
            c.drawRect(l, y, r, y + 3.2f, fill)
            y += 16f
        }
        fill.color = sky.aerial(Pal.woodDeep, 0.02f)
        c.drawRect(l, t, l + 6f, b, fill)
        c.drawRect(r - 6f, t, r, b, fill)
    }

    private fun roof(c: Canvas, cx: Float, apexY: Float, halfW: Float, eaveY: Float, color: Int) {
        fill.color = sky.aerial(color, 0.02f)
        path.reset()
        path.moveTo(cx - halfW, eaveY)
        path.lineTo(cx, apexY)
        path.lineTo(cx + halfW, eaveY)
        path.close()
        c.drawPath(path, fill)
        // shingle rows
        fill.color = sky.aerial(U.shade(color, 1.16f), 0.02f)
        var t = 0.18f
        while (t < 1f) {
            val y = U.lerp(apexY, eaveY, t)
            val hw = halfW * t
            c.drawRect(cx - hw, y, cx + hw, y + 3.4f, fill)
            t += 0.2f
        }
        fill.color = sky.aerial(U.shade(color, 0.78f), 0.02f)
        c.drawRect(cx - halfW - 8f, eaveY, cx + halfW + 8f, eaveY + 8f, fill)
    }

    private fun window(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, warm: Int, night: Float) {
        fill.color = sky.aerial(Pal.woodDeep, 0.02f)
        c.drawRect(cx - w / 2 - 4f, cy - h / 2 - 4f, cx + w / 2 + 4f, cy + h / 2 + 4f, fill)
        val glassDay = Color.parseColor("#AFD6E8")
        fill.color = U.lerpColor(sky.aerial(glassDay, 0.02f), warm, night)
        c.drawRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, fill)
        fill.color = sky.aerial(Pal.woodDark, 0.02f)
        c.drawRect(cx - 2f, cy - h / 2, cx + 2f, cy + h / 2, fill)
        c.drawRect(cx - w / 2, cy - 2f, cx + w / 2, cy + 2f, fill)
        if (night > 0.05f) {
            grad.shader = RadialGradient(
                cx, cy, w * 2.1f,
                intArrayOf(U.withAlpha(warm, 0.42f * night), U.withAlpha(warm, 0f)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, w * 2.1f, grad)
            grad.shader = null
        }
    }

    private fun door(c: Canvas, cx: Float, baseY: Float, w: Float, h: Float) {
        fill.color = sky.aerial(Pal.woodDeep, 0.02f)
        c.drawRoundRect(rr(cx - w / 2, baseY - h, cx + w / 2, baseY), w * 0.22f, w * 0.22f, fill)
        fill.color = sky.aerial(U.shade(Pal.woodDark, 1.05f), 0.02f)
        c.drawRoundRect(rr(cx - w / 2 + 4f, baseY - h + 4f, cx + w / 2 - 4f, baseY), w * 0.18f, w * 0.18f, fill)
        fill.color = Pal.gold
        c.drawCircle(cx + w * 0.26f, baseY - h * 0.46f, 3.6f, fill)
    }

    private fun cabinT1(c: Canvas, x: Float, gy: Float, warm: Int, night: Float) {
        logWall(c, x - 92f, gy - 108f, x + 92f, gy)
        roof(c, x, gy - 174f, 116f, gy - 106f, Color.parseColor("#A85A46"))
        door(c, x + 46f, gy, 44f, 74f)
        window(c, x - 42f, gy - 68f, 40f, 36f, warm, night)
        fill.color = sky.aerial(Pal.stone, 0.02f)
        c.drawRect(x + 52f, gy - 154f, x + 78f, gy - 106f, fill)
        // step
        fill.color = sky.aerial(U.shade(Pal.stone, 0.9f), 0.02f)
        c.drawRoundRect(rr(x + 22f, gy - 4f, x + 72f, gy + 8f), 4f, 4f, fill)
    }

    private fun cabinT2(c: Canvas, x: Float, gy: Float, warm: Int, night: Float) {
        logWall(c, x - 118f, gy - 124f, x + 118f, gy)
        roof(c, x, gy - 202f, 148f, gy - 122f, Color.parseColor("#A85A46"))
        // porch
        fill.color = sky.aerial(Pal.woodDark, 0.02f)
        c.drawRect(x + 108f, gy - 16f, x + 190f, gy, fill)
        c.drawRect(x + 116f, gy - 96f, x + 124f, gy - 12f, fill)
        c.drawRect(x + 178f, gy - 96f, x + 186f, gy - 12f, fill)
        c.drawRect(x + 108f, gy - 104f, x + 194f, gy - 92f, fill)
        c.drawRect(x + 112f, gy - 48f, x + 190f, gy - 42f, fill)
        door(c, x + 62f, gy, 48f, 80f)
        window(c, x - 60f, gy - 78f, 42f, 38f, warm, night)
        window(c, x - 2f, gy - 78f, 42f, 38f, warm, night)
        fill.color = sky.aerial(Pal.stone, 0.02f)
        c.drawRect(x + 56f, gy - 184f, x + 84f, gy - 120f, fill)
        lantern(c, x + 96f, gy - 96f, night)
    }

    private fun cabinT3(c: Canvas, x: Float, gy: Float, warm: Int, night: Float) {
        // stone base
        fill.color = sky.aerial(Pal.stone, 0.02f)
        c.drawRect(x - 136f, gy - 26f, x + 136f, gy, fill)
        fill.color = sky.aerial(U.shade(Pal.stone, 0.86f), 0.02f)
        var sx = x - 132f
        while (sx < x + 132f) { c.drawRect(sx, gy - 24f, sx + 3f, gy, fill); sx += 26f }

        logWall(c, x - 132f, gy - 186f, x + 132f, gy - 24f)
        fill.color = sky.aerial(U.shade(Pal.wood, 0.92f), 0.02f)
        c.drawRect(x - 136f, gy - 106f, x + 136f, gy - 98f, fill)
        roof(c, x, gy - 284f, 168f, gy - 184f, Color.parseColor("#8C4A3A"))
        // dormer
        fill.color = sky.aerial(Pal.wood, 0.02f)
        c.drawRect(x - 40f, gy - 262f, x + 26f, gy - 210f, fill)
        roof(c, x - 7f, gy - 300f, 48f, gy - 258f, Color.parseColor("#A85A46"))
        window(c, x - 7f, gy - 236f, 34f, 30f, warm, night)

        door(c, x + 72f, gy - 24f, 50f, 86f)
        window(c, x - 74f, gy - 148f, 46f, 42f, warm, night)
        window(c, x - 4f, gy - 148f, 46f, 42f, warm, night)
        window(c, x - 74f, gy - 66f, 44f, 40f, warm, night)
        flowerBox(c, x - 74f, gy - 42f)
        flowerBox(c, x - 4f, gy - 124f)
        fill.color = sky.aerial(Pal.stone, 0.02f)
        c.drawRect(x + 82f, gy - 272f, x + 116f, gy - 182f, fill)
        lantern(c, x + 112f, gy - 118f, night)
    }

    private fun cabinT4(c: Canvas, x: Float, gy: Float, warm: Int, night: Float) {
        fill.color = sky.aerial(Pal.stone, 0.02f)
        c.drawRect(x - 172f, gy - 30f, x + 172f, gy, fill)
        fill.color = sky.aerial(U.shade(Pal.stone, 0.86f), 0.02f)
        var sx = x - 168f
        while (sx < x + 168f) { c.drawRect(sx, gy - 28f, sx + 3f, gy, fill); sx += 26f }

        logWall(c, x - 168f, gy - 214f, x + 168f, gy - 28f)
        fill.color = sky.aerial(U.shade(Pal.wood, 0.92f), 0.02f)
        c.drawRect(x - 172f, gy - 122f, x + 172f, gy - 112f, fill)
        roof(c, x, gy - 336f, 210f, gy - 212f, Color.parseColor("#7C4436"))

        // attic gable
        fill.color = sky.aerial(Pal.wood, 0.02f)
        c.drawRect(x - 52f, gy - 306f, x + 40f, gy - 240f, fill)
        roof(c, x - 6f, gy - 352f, 66f, gy - 302f, Color.parseColor("#8C4A3A"))
        window(c, x - 6f, gy - 274f, 38f, 34f, warm, night)

        // wraparound porch
        fill.color = sky.aerial(Pal.woodDark, 0.02f)
        c.drawRect(x + 156f, gy - 18f, x + 268f, gy, fill)
        for (px in intArrayOf(164, 218, 258)) {
            c.drawRect(x + px, gy - 112f, x + px + 9f, gy - 14f, fill)
        }
        c.drawRect(x + 152f, gy - 122f, x + 274f, gy - 108f, fill)
        c.drawRect(x + 158f, gy - 56f, x + 268f, gy - 48f, fill)

        door(c, x + 96f, gy - 28f, 56f, 96f)
        window(c, x - 108f, gy - 172f, 48f, 44f, warm, night)
        window(c, x - 34f, gy - 172f, 48f, 44f, warm, night)
        window(c, x + 40f, gy - 172f, 48f, 44f, warm, night)
        window(c, x - 108f, gy - 78f, 48f, 44f, warm, night)
        window(c, x - 34f, gy - 78f, 48f, 44f, warm, night)
        flowerBox(c, x - 108f, gy - 54f)
        flowerBox(c, x - 34f, gy - 54f)
        flowerBox(c, x - 34f, gy - 148f)
        fill.color = sky.aerial(Pal.stone, 0.02f)
        c.drawRect(x + 106f, gy - 322f, x + 148f, gy - 210f, fill)
        lantern(c, x + 140f, gy - 138f, night)
        lantern(c, x + 240f, gy - 130f, night)

        // weather vane
        stroke.strokeWidth = 3f
        stroke.color = sky.aerial(Pal.woodDeep, 0.02f)
        c.drawLine(x, gy - 336f, x, gy - 372f, stroke)
        fill.color = sky.aerial(Pal.gold, 0.02f)
        path.reset()
        path.moveTo(x - 2f, gy - 372f); path.lineTo(x + 26f, gy - 362f); path.lineTo(x - 2f, gy - 352f)
        path.close()
        c.drawPath(path, fill)
    }

    private fun flowerBox(c: Canvas, cx: Float, y: Float) {
        fill.color = sky.aerial(Pal.woodDeep, 0.02f)
        c.drawRoundRect(rr(cx - 26f, y, cx + 26f, y + 14f), 3f, 3f, fill)
        for (i in 0 until 5) {
            val fx = cx - 18f + i * 9f
            fill.color = sky.aerial(Pal.leaf, 0.02f)
            c.drawCircle(fx, y - 2f, 5f, fill)
            fill.color = sky.aerial(if (i % 2 == 0) Pal.berry else Pal.gold, 0.02f)
            c.drawCircle(fx, y - 6f, 3.6f, fill)
        }
    }

    private fun lantern(c: Canvas, x: Float, y: Float, night: Float) {
        fill.color = sky.aerial(Pal.woodDeep, 0.02f)
        c.drawRect(x - 2f, y - 16f, x + 2f, y - 8f, fill)
        c.drawRoundRect(rr(x - 9f, y - 9f, x + 9f, y + 10f), 3f, 3f, fill)
        val glow = U.lerpColor(Color.parseColor("#D8CBA8"), Color.parseColor("#FFD98A"), night)
        fill.color = glow
        c.drawRoundRect(rr(x - 6f, y - 6f, x + 6f, y + 7f), 2f, 2f, fill)
        if (night > 0.05f) {
            grad.shader = RadialGradient(
                x, y, 62f,
                intArrayOf(U.withAlpha(Color.parseColor("#FFC96A"), 0.36f * night), U.withAlpha(Color.parseColor("#FFC96A"), 0f)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(x, y, 62f, grad)
            grad.shader = null
        }
    }

    private fun chimneySmoke(c: Canvas, x: Float, y: Float, time: Float) {
        for (i in 0 until 6) {
            val t = ((time * 0.00035f) + i * 0.166f) % 1f
            val a = (1f - t) * 0.34f
            if (a <= 0.01f) continue
            val px = x + sin(t * 5.4f + i) * 26f * t
            val py = y - t * 120f
            fill.color = U.withAlpha(U.lerpColor(Color.WHITE, sky.horizon, 0.25f), a)
            c.drawCircle(px, py, 8f + t * 22f, fill)
        }
    }

    // ---------------------------------------------------------------- market

    fun drawMarket(c: Canvas, time: Float, night: Float) {
        val gy = World.GROUND_Y
        val x = World.MARKET_X
        fill.color = U.withAlpha(Pal.shadow, 0.18f)
        c.drawOval(rr(x - 120f, gy - 6f, x + 120f, gy + 14f), fill)

        // posts
        fill.color = sky.aerial(Pal.woodDark, 0.02f)
        c.drawRect(x - 104f, gy - 152f, x - 92f, gy, fill)
        c.drawRect(x + 92f, gy - 152f, x + 104f, gy, fill)

        // counter
        fill.color = sky.aerial(Pal.wood, 0.02f)
        c.drawRoundRect(rr(x - 112f, gy - 62f, x + 112f, gy - 46f), 5f, 5f, fill)
        fill.color = sky.aerial(U.shade(Pal.wood, 0.8f), 0.02f)
        c.drawRect(x - 100f, gy - 46f, x + 100f, gy - 4f, fill)
        fill.color = sky.aerial(U.shade(Pal.wood, 0.7f), 0.02f)
        var bx = x - 96f
        while (bx < x + 96f) { c.drawRect(bx, gy - 44f, bx + 3f, gy - 6f, fill); bx += 22f }

        // striped awning
        val stripeA = sky.aerial(Color.parseColor("#F4EAD8"), 0.02f)
        val stripeB = sky.aerial(Color.parseColor("#D0707A"), 0.02f)
        var i = 0
        var ax = x - 122f
        while (ax < x + 122f) {
            fill.color = if (i % 2 == 0) stripeA else stripeB
            val w2 = kotlin.math.min(24f, x + 122f - ax)
            path.reset()
            path.moveTo(ax, gy - 152f)
            path.lineTo(ax + w2, gy - 152f)
            path.lineTo(ax + w2, gy - 116f)
            path.quadTo(ax + w2 / 2f, gy - 104f, ax, gy - 116f)
            path.close()
            c.drawPath(path, fill)
            ax += 24f; i++
        }
        fill.color = sky.aerial(Pal.woodDeep, 0.02f)
        c.drawRect(x - 124f, gy - 158f, x + 124f, gy - 148f, fill)

        // crates of produce
        crate(c, x - 76f, gy - 46f, Color.parseColor("#E08240"))
        crate(c, x - 22f, gy - 46f, Color.parseColor("#D6564C"))
        crate(c, x + 32f, gy - 46f, Color.parseColor("#6FA45A"))

        // sign
        fill.color = sky.aerial(Pal.woodDeep, 0.02f)
        c.drawRoundRect(rr(x - 58f, gy - 214f, x + 58f, gy - 168f), 8f, 8f, fill)
        fill.color = sky.aerial(Pal.wood, 0.02f)
        c.drawRoundRect(rr(x - 52f, gy - 208f, x + 52f, gy - 174f), 6f, 6f, fill)
        signText.color = Pal.woodDeep
        signText.textSize = 24f
        c.drawText("MARKET", x, gy - 183f, signText)

        lantern(c, x - 98f, gy - 128f, night)
        lantern(c, x + 98f, gy - 128f, night)
        shopkeeper(c, x + 62f, gy - 46f, time)
    }

    private fun crate(c: Canvas, x: Float, y: Float, produce: Int) {
        fill.color = sky.aerial(Pal.woodDark, 0.02f)
        c.drawRoundRect(rr(x - 24f, y - 30f, x + 24f, y), 3f, 3f, fill)
        fill.color = sky.aerial(U.shade(Pal.woodDark, 1.14f), 0.02f)
        c.drawRect(x - 24f, y - 22f, x + 24f, y - 18f, fill)
        c.drawRect(x - 24f, y - 10f, x + 24f, y - 6f, fill)
        fill.color = sky.aerial(produce, 0.02f)
        c.drawCircle(x - 11f, y - 34f, 9f, fill)
        c.drawCircle(x + 8f, y - 33f, 8f, fill)
        c.drawCircle(x - 1f, y - 42f, 8f, fill)
    }

    /** Pip the shopkeeper: a small round fox who bobs behind the counter. */
    private fun shopkeeper(c: Canvas, x: Float, gy: Float, time: Float) {
        val bob = sin(time * 0.0022f) * 3.4f
        val y = gy - 16f + bob
        val fur = sky.aerial(Color.parseColor("#DE8B52"), 0.02f)
        val cream = sky.aerial(Color.parseColor("#F6E6CE"), 0.02f)
        fill.color = fur
        c.drawRoundRect(rr(x - 20f, y - 34f, x + 20f, y + 12f), 16f, 16f, fill)
        fill.color = cream
        c.drawRoundRect(rr(x - 11f, y - 12f, x + 11f, y + 12f), 9f, 9f, fill)
        fill.color = fur
        c.drawCircle(x, y - 44f, 20f, fill)
        path.reset()
        path.moveTo(x - 20f, y - 52f); path.lineTo(x - 8f, y - 68f); path.lineTo(x - 2f, y - 50f); path.close()
        c.drawPath(path, fill)
        path.reset()
        path.moveTo(x + 20f, y - 52f); path.lineTo(x + 8f, y - 68f); path.lineTo(x + 2f, y - 50f); path.close()
        c.drawPath(path, fill)
        fill.color = cream
        c.drawOval(rr(x - 11f, y - 44f, x + 11f, y - 30f), fill)
        fill.color = Pal.ink
        val blink = if ((time * 0.001f) % 4.6f < 0.16f) 0.2f else 1f
        c.drawOval(rr(x - 9f, y - 50f - 2f * blink, x - 4f, y - 44f + 2f * blink), fill)
        c.drawOval(rr(x + 4f, y - 50f - 2f * blink, x + 9f, y - 44f + 2f * blink), fill)
        c.drawOval(rr(x - 2.6f, y - 40f, x + 2.6f, y - 36f), fill)
    }

    // ------------------------------------------------------------- ambience

    /** A single wash of colour + vignette that binds the frame together. */
    fun drawAmbient(c: Canvas, extraDark: Float) {
        fill.shader = null
        fill.color = U.withAlpha(sky.ambient, sky.ambientStrength * 0.55f + extraDark * 0.25f)
        c.drawRect(0f, 0f, vw, vh, fill)
        grad.shader = RadialGradient(
            vw * 0.5f, vh * 0.48f, kotlin.math.max(vw, vh) * 0.74f,
            intArrayOf(Color.TRANSPARENT, U.withAlpha(Pal.shadow, 0.10f), U.withAlpha(Pal.shadow, 0.34f)),
            floatArrayOf(0.45f, 0.78f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, vw, vh, grad)
        grad.shader = null
    }

    fun softShadow(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, alpha: Float) {
        fill.shader = null
        fill.color = U.withAlpha(Pal.shadow, alpha)
        c.drawOval(rr(x - rx, y - ry, x + rx, y + ry), fill)
    }
}
