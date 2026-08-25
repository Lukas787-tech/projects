package com.cozyhollow.riverside

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.sin

/** A tappable rounded button. Screens own the list; [Ui] draws them. */
class Btn(var tag: Int, var label: String = "") {
    var x = 0f; var y = 0f; var w = 0f; var h = 0f
    var enabled = true
    var visible = true
    var press = 0f
    var accent = 0
    var iconItem: String? = null
    var sub: String? = null
    /** 0 = normal, 1 = primary, 2 = quiet, 3 = round icon, 4 = danger */
    var style = 0

    fun set(px: Float, py: Float, pw: Float, ph: Float): Btn {
        x = px; y = py; w = pw; h = ph; return this
    }

    fun hit(tx: Float, ty: Float): Boolean =
        visible && enabled && tx >= x - 4f && tx <= x + w + 4f && ty >= y - 4f && ty <= y + h + 4f

    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f
}

class Toast(val text: String, val itemId: String?, val color: Int) {
    var life = 2.6f
    var y = 0f
}

object Ui {

    val body: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val display: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = body }
    private val rf = RectF()
    private val path = Path()

    private fun rr(l: Float, t: Float, r: Float, b: Float): RectF {
        rf.set(l, t, r, b); return rf
    }

    // ------------------------------------------------------------- text

    fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, face: Typeface = body, alpha: Float = 1f
    ) {
        txt.typeface = face
        txt.textSize = size
        txt.textAlign = align
        txt.color = U.withAlpha(color, alpha)
        txt.style = Paint.Style.FILL
        c.drawText(s, x, y, txt)
    }

    /** Title-style text with a soft cream outline so it reads over the scene. */
    fun textOut(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        outline: Int = Pal.cream, align: Paint.Align = Paint.Align.LEFT,
        face: Typeface = body, width: Float = 5f, alpha: Float = 1f
    ) {
        txt.typeface = face
        txt.textSize = size
        txt.textAlign = align
        txt.style = Paint.Style.STROKE
        txt.strokeWidth = width
        txt.strokeJoin = Paint.Join.ROUND
        txt.color = U.withAlpha(outline, alpha)
        c.drawText(s, x, y, txt)
        txt.style = Paint.Style.FILL
        txt.color = U.withAlpha(color, alpha)
        c.drawText(s, x, y, txt)
    }

    fun measure(s: String, size: Float, face: Typeface = body): Float {
        txt.typeface = face
        txt.textSize = size
        txt.textAlign = Paint.Align.LEFT
        return txt.measureText(s)
    }

    /** Word-wraps [s] into [out], returning the number of lines. */
    fun wrap(s: String, size: Float, maxW: Float, out: ArrayList<String>): Int {
        out.clear()
        val words = s.split(' ')
        val sb = StringBuilder()
        for (word in words) {
            val candidate = if (sb.isEmpty()) word else "$sb $word"
            if (measure(candidate, size) > maxW && sb.isNotEmpty()) {
                out.add(sb.toString()); sb.setLength(0); sb.append(word)
            } else {
                sb.setLength(0); sb.append(candidate)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.size
    }

    // ------------------------------------------------------------ panels

    fun scrim(c: Canvas, w: Float, h: Float, alpha: Float) {
        fill.shader = null
        fill.color = U.withAlpha(Color.parseColor("#1E1710"), alpha)
        c.drawRect(0f, 0f, w, h, fill)
    }

    fun panel(c: Canvas, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f, radius: Float = 22f) {
        // drop shadow
        fill.shader = null
        fill.color = U.withAlpha(Pal.shadow, 0.26f * alpha)
        c.drawRoundRect(rr(x + 5f, y + 8f, x + w + 5f, y + h + 8f), radius, radius, fill)
        // wooden frame
        fill.color = U.withAlpha(Pal.woodDeep, alpha)
        c.drawRoundRect(rr(x, y, x + w, y + h), radius, radius, fill)
        fill.color = U.withAlpha(Pal.woodDark, alpha)
        c.drawRoundRect(rr(x + 3f, y + 3f, x + w - 3f, y + h - 3f), radius - 3f, radius - 3f, fill)
        // paper
        grad.shader = LinearGradient(
            0f, y + 8f, 0f, y + h - 8f,
            U.withAlpha(Pal.paper, alpha), U.withAlpha(Pal.paperDeep, alpha), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rr(x + 8f, y + 8f, x + w - 8f, y + h - 8f), radius - 7f, radius - 7f, grad)
        grad.shader = null
        // inner hairline
        stroke.strokeWidth = 1.6f
        stroke.color = U.withAlpha(Pal.wood, 0.5f * alpha)
        c.drawRoundRect(rr(x + 13f, y + 13f, x + w - 13f, y + h - 13f), radius - 11f, radius - 11f, stroke)
    }

    /** A ribbon header that sits on the top edge of a panel. */
    fun ribbon(c: Canvas, cx: Float, y: Float, w: Float, title: String, alpha: Float = 1f) {
        val h = 46f
        fill.shader = null
        fill.color = U.withAlpha(Pal.shadow, 0.20f * alpha)
        c.drawRoundRect(rr(cx - w / 2 + 3f, y + 5f, cx + w / 2 + 3f, y + h + 5f), 14f, 14f, fill)
        fill.color = U.withAlpha(Pal.woodDeep, alpha)
        c.drawRoundRect(rr(cx - w / 2, y, cx + w / 2, y + h), 14f, 14f, fill)
        fill.color = U.withAlpha(U.shade(Pal.wood, 0.94f), alpha)
        c.drawRoundRect(rr(cx - w / 2 + 4f, y + 4f, cx + w / 2 - 4f, y + h - 4f), 11f, 11f, fill)
        // tails
        fill.color = U.withAlpha(Pal.woodDeep, alpha)
        for (s in intArrayOf(-1, 1)) {
            path.reset()
            path.moveTo(cx + s * (w / 2 - 2f), y + 8f)
            path.lineTo(cx + s * (w / 2 + 26f), y + 4f)
            path.lineTo(cx + s * (w / 2 + 26f), y + h - 4f)
            path.lineTo(cx + s * (w / 2 - 2f), y + h - 8f)
            path.close()
            c.drawPath(path, fill)
        }
        text(c, title, cx, y + h / 2 + 9f, 26f, Pal.cream, Paint.Align.CENTER, display, alpha)
    }

    // ----------------------------------------------------------- buttons

    fun button(c: Canvas, b: Btn, alpha: Float = 1f) {
        if (!b.visible) return
        val depth = 6f
        val dy = b.press * (depth - 1f)
        val a = if (b.enabled) alpha else alpha * 0.5f
        val base = when (b.style) {
            1 -> Pal.leaf
            2 -> Pal.paperDeep
            4 -> Pal.berry
            else -> if (b.accent != 0) b.accent else Pal.wood
        }
        val radius = if (b.style == 3) b.h / 2f else 15f

        fill.shader = null
        // shadow slab
        fill.color = U.withAlpha(U.shade(base, 0.62f), a)
        c.drawRoundRect(rr(b.x, b.y + dy, b.x + b.w, b.y + b.h + depth), radius, radius, fill)
        // face
        grad.shader = LinearGradient(
            0f, b.y + dy, 0f, b.y + b.h + dy,
            U.withAlpha(U.shade(base, 1.14f), a), U.withAlpha(base, a), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rr(b.x, b.y + dy, b.x + b.w, b.y + b.h + dy), radius, radius, grad)
        grad.shader = null
        // gloss
        fill.color = U.withAlpha(Color.WHITE, 0.16f * a)
        c.drawRoundRect(rr(b.x + 5f, b.y + dy + 4f, b.x + b.w - 5f, b.y + dy + b.h * 0.42f), radius - 4f, radius - 4f, fill)
        stroke.strokeWidth = 2f
        stroke.color = U.withAlpha(U.shade(base, 0.55f), a * 0.8f)
        c.drawRoundRect(rr(b.x + 1f, b.y + dy + 1f, b.x + b.w - 1f, b.y + b.h + dy - 1f), radius, radius, stroke)

        val labelColor = if (b.style == 2) Pal.ink else Pal.cream
        val outlineColor = if (b.style == 2) Pal.paper else U.shade(base, 0.5f)
        val icon = b.iconItem
        val ty = b.y + dy + b.h / 2f + (if (b.sub != null) -2f else 8f)
        if (icon != null) {
            val isz = kotlin.math.min(b.h * 0.62f, 46f)
            IconDraw.draw(c, Catalog.item(icon), b.x + 6f + isz * 0.62f, b.y + dy + b.h / 2f, isz, fill)
            textOut(c, b.label, b.x + isz + 14f, ty, b.h * 0.34f, labelColor, outlineColor, Paint.Align.LEFT, body, 4f, a)
        } else if (b.label.isNotEmpty()) {
            val fs = if (b.style == 3) b.h * 0.44f else kotlin.math.min(b.h * 0.42f, 28f)
            textOut(c, b.label, b.cx, ty, fs, labelColor, outlineColor, Paint.Align.CENTER, body, 4f, a)
        }
        b.sub?.let {
            text(c, it, b.cx, b.y + dy + b.h - 12f, 15f, U.withAlpha(labelColor, 0.85f), Paint.Align.CENTER, body, a)
        }
    }

    // ------------------------------------------------------------ pieces

    fun coin(c: Canvas, x: Float, y: Float, r: Float, alpha: Float = 1f) {
        fill.shader = null
        fill.color = U.withAlpha(Pal.goldDeep, alpha)
        c.drawCircle(x, y, r, fill)
        fill.color = U.withAlpha(Pal.gold, alpha)
        c.drawCircle(x, y, r * 0.82f, fill)
        fill.color = U.withAlpha(U.shade(Pal.gold, 1.2f), alpha)
        c.drawCircle(x - r * 0.24f, y - r * 0.26f, r * 0.26f, fill)
        text(c, "c", x, y + r * 0.42f, r * 1.15f, U.withAlpha(Pal.goldDeep, alpha), Paint.Align.CENTER, display)
    }

    fun pill(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, alpha: Float = 1f) {
        fill.shader = null
        fill.color = U.withAlpha(Pal.shadow, 0.20f * alpha)
        c.drawRoundRect(rr(x + 2f, y + 4f, x + w + 2f, y + h + 4f), h / 2f, h / 2f, fill)
        fill.color = U.withAlpha(color, alpha)
        c.drawRoundRect(rr(x, y, x + w, y + h), h / 2f, h / 2f, fill)
        fill.color = U.withAlpha(Color.WHITE, 0.14f * alpha)
        c.drawRoundRect(rr(x + 4f, y + 3f, x + w - 4f, y + h * 0.48f), h / 3f, h / 3f, fill)
    }

    fun bar(
        c: Canvas, x: Float, y: Float, w: Float, h: Float, value: Float,
        color: Int, back: Int = U.shade(Pal.woodDeep, 1.1f), alpha: Float = 1f
    ) {
        fill.shader = null
        fill.color = U.withAlpha(back, alpha)
        c.drawRoundRect(rr(x, y, x + w, y + h), h / 2f, h / 2f, fill)
        val v = U.clamp01(value)
        if (v > 0.001f) {
            val fw = kotlin.math.max(h, w * v)
            grad.shader = LinearGradient(
                0f, y, 0f, y + h,
                U.withAlpha(U.shade(color, 1.2f), alpha), U.withAlpha(color, alpha), Shader.TileMode.CLAMP
            )
            c.drawRoundRect(rr(x, y, x + fw, y + h), h / 2f, h / 2f, grad)
            grad.shader = null
            fill.color = U.withAlpha(Color.WHITE, 0.22f * alpha)
            c.drawRoundRect(rr(x + 3f, y + 2.5f, x + fw - 3f, y + h * 0.46f), h / 3f, h / 3f, fill)
        }
        stroke.strokeWidth = 2f
        stroke.color = U.withAlpha(Pal.woodDeep, 0.55f * alpha)
        c.drawRoundRect(rr(x, y, x + w, y + h), h / 2f, h / 2f, stroke)
    }

    fun slider(c: Canvas, x: Float, y: Float, w: Float, value: Float, color: Int) {
        val h = 12f
        fill.shader = null
        fill.color = U.shade(Pal.paperDeep, 0.86f)
        c.drawRoundRect(rr(x, y - h / 2, x + w, y + h / 2), h / 2, h / 2, fill)
        fill.color = color
        c.drawRoundRect(rr(x, y - h / 2, x + w * U.clamp01(value), y + h / 2), h / 2, h / 2, fill)
        val kx = x + w * U.clamp01(value)
        fill.color = U.withAlpha(Pal.shadow, 0.22f)
        c.drawCircle(kx, y + 3f, 17f, fill)
        fill.color = Pal.cream
        c.drawCircle(kx, y, 16f, fill)
        fill.color = color
        c.drawCircle(kx, y, 9f, fill)
    }

    fun toggle(c: Canvas, x: Float, y: Float, on: Boolean, t: Float) {
        val w = 68f; val h = 34f
        fill.shader = null
        fill.color = U.lerpColor(U.shade(Pal.paperDeep, 0.82f), Pal.leaf, t)
        c.drawRoundRect(rr(x, y - h / 2, x + w, y + h / 2), h / 2, h / 2, fill)
        stroke.strokeWidth = 2f
        stroke.color = U.withAlpha(Pal.woodDeep, 0.4f)
        c.drawRoundRect(rr(x, y - h / 2, x + w, y + h / 2), h / 2, h / 2, stroke)
        val kx = U.lerp(x + h / 2, x + w - h / 2, t)
        fill.color = U.withAlpha(Pal.shadow, 0.24f)
        c.drawCircle(kx, y + 3f, h / 2 - 3f, fill)
        fill.color = Pal.cream
        c.drawCircle(kx, y, h / 2 - 4f, fill)
    }

    /** A soft item tile used by the bag, shop and journal. */
    fun tile(
        c: Canvas, x: Float, y: Float, w: Float, h: Float,
        selected: Boolean, alpha: Float = 1f
    ) {
        fill.shader = null
        fill.color = U.withAlpha(if (selected) U.shade(Pal.gold, 1.06f) else U.shade(Pal.paperDeep, 0.94f), alpha)
        c.drawRoundRect(rr(x, y, x + w, y + h), 13f, 13f, fill)
        fill.color = U.withAlpha(if (selected) U.shade(Pal.gold, 1.16f) else Pal.paper, alpha)
        c.drawRoundRect(rr(x + 3f, y + 3f, x + w - 3f, y + h - 5f), 11f, 11f, fill)
        stroke.strokeWidth = 2f
        stroke.color = U.withAlpha(if (selected) Pal.goldDeep else U.withAlpha(Pal.wood, 0.55f), alpha)
        c.drawRoundRect(rr(x, y, x + w, y + h), 13f, 13f, stroke)
    }

    fun sparkleRing(c: Canvas, x: Float, y: Float, r: Float, time: Float, color: Int) {
        stroke.strokeWidth = 2.6f
        for (i in 0 until 3) {
            val ph = (time * 0.0011f + i * 0.333f) % 1f
            stroke.color = U.withAlpha(color, (1f - ph) * 0.5f)
            c.drawCircle(x, y, r * (0.55f + ph * 0.7f), stroke)
        }
    }

    /** Bouncing chevron used to point at whatever you can interact with. */
    fun hintArrow(c: Canvas, x: Float, y: Float, time: Float, color: Int) {
        val bob = sin(time * 0.006f) * 5f
        fill.shader = null
        fill.color = U.withAlpha(Pal.shadow, 0.2f)
        path.reset()
        path.moveTo(x - 13f, y + bob - 12f)
        path.lineTo(x + 13f, y + bob - 12f)
        path.lineTo(x, y + bob + 6f)
        path.close()
        c.drawPath(path, fill)
        fill.color = color
        path.reset()
        path.moveTo(x - 11f, y + bob - 14f)
        path.lineTo(x + 11f, y + bob - 14f)
        path.lineTo(x, y + bob + 2f)
        path.close()
        c.drawPath(path, fill)
    }

    fun toasts(c: Canvas, list: ArrayList<Toast>, cx: Float, topY: Float) {
        var y = topY
        for (t in list) {
            val a = U.clamp01(t.life * 1.8f) * U.clamp01((2.6f - t.life) * 5f)
            val label = t.text
            val hasIcon = t.itemId != null
            val tw = measure(label, 22f) + (if (hasIcon) 44f else 0f) + 44f
            val h = 44f
            pill(c, cx - tw / 2f, y, tw, h, U.withAlpha(Pal.woodDeep, 0.92f), a)
            if (hasIcon) {
                IconDraw.draw(c, Catalog.item(t.itemId!!), cx - tw / 2f + 28f, y + h / 2f, 32f, fill)
                text(c, label, cx - tw / 2f + 50f, y + h / 2f + 8f, 22f, t.color, Paint.Align.LEFT, body, a)
            } else {
                text(c, label, cx, y + h / 2f + 8f, 22f, t.color, Paint.Align.CENTER, body, a)
            }
            y += h + 10f
        }
    }
}
