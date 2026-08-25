package com.cozyhollow.riverside

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Small allocation-free math helpers used everywhere in the render loop. */
object U {

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))

    fun clamp01(v: Float): Float = clamp(v, 0f, 1f)

    fun clampI(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))

    /** Maps [v] from range [a,b] into 0..1, clamped. */
    fun norm(v: Float, a: Float, b: Float): Float =
        if (abs(b - a) < 1e-6f) 0f else clamp01((v - a) / (b - a))

    /** Smoothstep. */
    fun smooth(t: Float): Float {
        val x = clamp01(t)
        return x * x * (3f - 2f * x)
    }

    fun smoothRange(v: Float, a: Float, b: Float): Float = smooth(norm(v, a, b))

    fun easeOut(t: Float): Float = 1f - (1f - clamp01(t)).pow(3f)

    fun easeIn(t: Float): Float = clamp01(t).pow(3f)

    fun easeInOut(t: Float): Float {
        val x = clamp01(t)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3f) / 2f
    }

    /** Overshooting "pop" ease, great for menus appearing. */
    fun easeBack(t: Float): Float {
        val x = clamp01(t)
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return 1f + c3 * (x - 1f).pow(3f) + c1 * (x - 1f).pow(2f)
    }

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val f = clamp01(t)
        val ia = 1f - f
        val al = (Color.alpha(a) * ia + Color.alpha(b) * f).toInt()
        val r = (Color.red(a) * ia + Color.red(b) * f).toInt()
        val g = (Color.green(a) * ia + Color.green(b) * f).toInt()
        val bl = (Color.blue(a) * ia + Color.blue(b) * f).toInt()
        return Color.argb(al, r, g, bl)
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((clamp01(alpha) * 255f).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** Multiplies a colour's brightness, keeping alpha. */
    fun shade(color: Int, factor: Float): Int {
        val r = clampI((Color.red(color) * factor).toInt(), 0, 255)
        val g = clampI((Color.green(color) * factor).toInt(), 0, 255)
        val b = clampI((Color.blue(color) * factor).toInt(), 0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    /** Deterministic hash -> 0..1, used to scatter scenery without storing it. */
    fun hash(seed: Int): Float {
        var x = seed
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        return ((x and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat())
    }

    fun hash2(a: Int, b: Int): Float = hash(a * 73856093 xor b * 19349663)

    /** Cheap smooth 1D value noise. */
    fun noise(x: Float, seed: Int = 0): Float {
        val i = kotlin.math.floor(x).toInt()
        val f = x - i
        val a = hash2(i, seed)
        val b = hash2(i + 1, seed)
        return lerp(a, b, smooth(f))
    }

    /** Sum of two sines: cheap organic wobble for water and grass. */
    fun wobble(t: Float, f1: Float, f2: Float): Float =
        sin(t * f1) * 0.6f + sin(t * f2 + 1.3f) * 0.4f

    fun formatTime(minutes: Float): String {
        val total = minutes.toInt().coerceIn(0, 24 * 60)
        var h = total / 60
        val m = (total % 60) / 10 * 10
        val suffix = if (h < 12 || h == 24) "am" else "pm"
        var hh = h % 12
        if (hh == 0) hh = 12
        return "$hh:${if (m < 10) "0$m" else "$m"}$suffix"
    }

    fun formatCoins(v: Int): String {
        if (v < 10000) return v.toString()
        val s = v.toString()
        val sb = StringBuilder()
        for ((idx, c) in s.withIndex()) {
            if (idx > 0 && (s.length - idx) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return sb.toString()
    }
}
