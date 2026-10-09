package com.lukas.jarvis.ui.theme

import kotlin.math.pow

/**
 * The latte café's colours, and the arithmetic that keeps them readable.
 *
 * Colours here are plain ARGB ints rather than Compose colours, so the rules —
 * every text colour at least 4.5:1 against what it sits on, in both themes and
 * for every accent — are checked by a plain JVM test.
 */
object Palette {

    // ---------------------------------------------------------- latte (light)
    const val FOAM = 0xFFFBF7F1.toInt()
    const val PAPER = 0xFFFFFDF9.toInt()
    const val LATTE = 0xFFEFE4D6.toInt()
    const val LATTE_DEEP = 0xFFE3D3C0.toInt()
    const val ESPRESSO = 0xFF3A2E28.toInt()
    const val COCOA = 0xFF6F5E53.toInt()
    const val CARAMEL = 0xFFC58A5B.toInt()
    const val SAGE = 0xFF8FA68E.toInt()
    const val BERRY = 0xFFB8605F.toInt()
    const val HONEY = 0xFFE3B65C.toInt()
    /** Shadows are warm brown, never grey: paper on a wooden table, not a UI on glass. */
    const val SHADOW = 0xFF5A3E2B.toInt()

    // ------------------------------------------------------ night café (dark)
    const val ROAST = 0xFF1F1815.toInt()
    const val MOCHA = 0xFF2B221E.toInt()
    const val CREMA = 0xFF382D27.toInt()
    const val CREMA_DEEP = 0xFF46392F.toInt()
    const val STEAMED = 0xFFF3E9DD.toInt()
    const val CINNAMON = 0xFFC9B8A6.toInt()

    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()

    /** The minimum for any text a person has to read. */
    const val TEXT_CONTRAST = 4.5

    /** An accent the person can choose. Warm ones first; the café is warm. */
    data class Accent(val id: String, val label: String, val color: Int)

    val ACCENTS: List<Accent> = listOf(
        Accent("caramel", "Caramel", CARAMEL),
        Accent("honey", "Honey", HONEY),
        Accent("apricot", "Apricot", 0xFFE39A6B.toInt()),
        Accent("berry", "Berry", BERRY),
        Accent("rose", "Rose", 0xFFD98C8C.toInt()),
        Accent("sage", "Sage", SAGE),
        Accent("matcha", "Matcha", 0xFF9DB06A.toInt()),
        Accent("lavender", "Lavender", 0xFFA99BC9.toInt()),
        Accent("sky", "Sky", 0xFF7FA7C4.toInt()),
        Accent("stone", "Stone", 0xFF9C9188.toInt())
    )

    /** "custom:210" is a hue from the rainbow slider; anything unknown is caramel. */
    fun accent(id: String): Accent {
        customHue(id)?.let { hue -> return Accent(id, "Your own", hsv(hue, 0.45f, 0.82f)) }
        return ACCENTS.firstOrNull { it.id == id } ?: ACCENTS.first()
    }

    fun customHue(id: String): Float? =
        if (id.startsWith("custom:")) id.removePrefix("custom:").toFloatOrNull()?.let { ((it % 360f) + 360f) % 360f } else null

    /**
     * Everything derived from one accent for one theme: the colour itself for
     * highlights, a shade that reads as text, a fill a label reads on, the
     * label's colour, and a soft wash for selected things.
     */
    data class AccentSet(val main: Int, val text: Int, val fill: Int, val onFill: Int, val soft: Int)

    fun accentSet(color: Int, dark: Boolean): AccentSet = if (dark) {
        val text = towards(color, WHITE, MOCHA)
        val fill = towards(color, WHITE, ROAST)
        AccentSet(main = color, text = text, fill = fill, onFill = ROAST, soft = mix(color, MOCHA, 0.78f))
    } else {
        val text = towards(color, BLACK, PAPER)
        val fill = towards(color, BLACK, WHITE)
        AccentSet(main = color, text = text, fill = fill, onFill = WHITE, soft = mix(color, PAPER, 0.80f))
    }

    /**
     * [color] moved towards [toward] (black or white) just far enough that it
     * reads at [TEXT_CONTRAST] against [background] — so a pale honey still
     * reads, and a deep caramel is left as it is.
     */
    fun towards(color: Int, toward: Int, background: Int, ratio: Double = TEXT_CONTRAST): Int {
        var t = 0f
        var candidate = color
        while (contrast(candidate, background) < ratio && t < 1f) {
            t += 0.02f
            candidate = mix(color, toward, 1f - t)
        }
        return candidate
    }

    /** [a] weighted by [weightA], the rest [b]. */
    fun mix(a: Int, b: Int, weightA: Float): Int {
        val w = weightA.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca * w + cb * (1 - w)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** WCAG relative luminance. */
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG contrast ratio, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun hsv(hue: Float, saturation: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs(h % 2 - 1))
        val m = value - c
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun to255(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
    }
}
