package com.cozyhollow.riverside.gl

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A tiny pixel canvas. Everything the world wears is painted here, texel by texel. */
class Px(val w: Int, val h: Int) {
    val p = IntArray(w * h)
    private var seed = 12345

    fun rnd(): Float {
        seed = seed * 1664525 + 1013904223
        return ((seed ushr 8) and 0xFFFF) / 65535f
    }

    fun seed(s: Int): Px { seed = s or 1; return this }

    fun set(x: Int, y: Int, c: Int) {
        if (x < 0 || y < 0 || x >= w || y >= h) return
        p[y * w + x] = c
    }

    /** Wrapping write, so tiling textures stay seamless. */
    fun setW(x: Int, y: Int, c: Int) {
        p[((y % h) + h) % h * w + (((x % w) + w) % w)] = c
    }

    fun get(x: Int, y: Int): Int = p[((y % h) + h) % h * w + (((x % w) + w) % w)]

    fun fill(c: Int): Px {
        java.util.Arrays.fill(p, c); return this
    }

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int): Px {
        for (y in y0..y1) for (x in x0..x1) set(x, y, c)
        return this
    }

    fun hline(y: Int, x0: Int, x1: Int, c: Int): Px {
        for (x in x0..x1) setW(x, y, c); return this
    }

    fun vline(x: Int, y0: Int, y1: Int, c: Int): Px {
        for (y in y0..y1) setW(x, y, c); return this
    }

    /** Scatter [n] single texels of [c] across the whole tile. */
    fun speckle(n: Int, vararg c: Int): Px {
        for (i in 0 until n) {
            val x = (rnd() * w).toInt()
            val y = (rnd() * h).toInt()
            setW(x, y, c[(rnd() * c.size).toInt().coerceIn(0, c.size - 1)])
        }
        return this
    }

    /** Soft organic patches: drift, frost bloom, rust. */
    fun blotch(n: Int, radius: Int, c: Int): Px {
        for (i in 0 until n) {
            val cx = (rnd() * w).toInt()
            val cy = (rnd() * h).toInt()
            val r = 1 + (rnd() * radius).toInt()
            for (y in -r..r) for (x in -r..r) {
                if (x * x + y * y <= r * r && rnd() > 0.28f) setW(cx + x, cy + y, c)
            }
        }
        return this
    }

    /** Ordered 2x2 dither between two colours. */
    fun dither(c: Int, chance: Float): Px {
        for (y in 0 until h) for (x in 0 until w) {
            if (((x + y) and 1) == 0 && rnd() < chance) setW(x, y, c)
        }
        return this
    }

    /**
     * Box-blurs the tile in place, wrapping at the edges.
     *
     * This is what turns hand-scattered texels into the soft, almost matte
     * surfaces the winter look needs. A crisp 32x32 tile reads as pixel art;
     * the same tile blurred twice reads as flat-shaded low poly, which is the
     * whole difference between the old summer game and this one.
     */
    fun blur(passes: Int = 1): Px {
        val tmp = IntArray(w * h)
        repeat(passes) {
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val c = get(x + dx, y + dy)
                    a += (c ushr 24) and 0xFF
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                tmp[y * w + x] = ((a / 9) shl 24) or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
            }
            System.arraycopy(tmp, 0, p, 0, p.size)
        }
        return this
    }

    /** Scratchy hairlines, for cracked ice and old paint. */
    fun scratch(n: Int, len: Int, c: Int): Px {
        for (i in 0 until n) {
            var x = (rnd() * w).toInt()
            var y = (rnd() * h).toInt()
            val a = rnd() * 6.2832f
            val dx = cos(a); val dy = sin(a)
            var fx = x.toFloat(); var fy = y.toFloat()
            val steps = 2 + (rnd() * len).toInt()
            for (s in 0 until steps) {
                fx += dx; fy += dy
                x = fx.toInt(); y = fy.toInt()
                setW(x, y, c)
            }
        }
        return this
    }
}

/**
 * Every texture in the game, painted procedurally and then softened, so the
 * project still ships zero asset files.
 *
 * The winter palette is deliberately narrow: eight or nine blues and greys for
 * everything outdoors, a handful of browns for wood, and one warm orange range
 * reserved entirely for light. Nothing in the world is allowed to be orange
 * unless it is on fire or has a lamp in it.
 */
object PixelTex {

    private fun c(hex: String) = android.graphics.Color.parseColor(hex)

    // ---- the winter palette ----
    val snowA = c("#E8EEF8"); val snowB = c("#D8E2F0"); val snowC = c("#F4F8FF"); val snowD = c("#C4D2E8")
    val packA = c("#C2CCDE"); val packB = c("#AEBACE"); val packC = c("#D2DAE8")
    val soilA = c("#6E5B4C"); val soilB = c("#5C4B3E"); val soilC = c("#7E6A58"); val soilD = c("#4A3C32")
    val woodA = c("#A8825A"); val woodB = c("#8E6C48"); val woodC = c("#BE9A70"); val woodD = c("#6E5236")
    val greyWoodA = c("#8A8592"); val greyWoodB = c("#726E7C"); val greyWoodC = c("#A09AA8")
    val barkA = c("#5E4E42"); val barkB = c("#4A3E34"); val barkC = c("#70604F")
    val roofA = c("#2E2C3E"); val roofB = c("#23212F"); val roofC = c("#3C3A50")
    val stoneA = c("#8A8E9C"); val stoneB = c("#6E7280"); val stoneC = c("#A2A6B4")
    val iceA = c("#7FA8C8"); val iceB = c("#6892B4"); val iceC = c("#A8CCE2"); val iceD = c("#52789A")
    val pineA = c("#2E5A4E"); val pineB = c("#254A42"); val pineC = c("#3A6E5C")
    val rustA = c("#9A4438"); val rustB = c("#7A3428"); val rustC = c("#B4584A")

    fun tiling32(build: (Px) -> Unit): Px = Px(32, 32).also(build)
    fun tiling64(build: (Px) -> Unit): Px = Px(64, 64).also(build)

    // ------------------------------------------------------------- ground

    /**
     * Fresh snow. Almost featureless on purpose — the shading and the drift in
     * the heightfield do the work, and any real pattern at a one-metre repeat
     * turns a whole snowfield into visible wallpaper.
     */
    fun snow(): Px = tiling64 { g ->
        g.seed(6151).fill(snowA)
        g.dither(snowB, 0.35f)
        g.blotch(9, 7, snowC)
        g.blotch(6, 5, snowD)
        g.speckle(120, snowC, snowB)
        g.blur(2)
    }

    /** Where boots have gone back and forth all week. */
    fun snowPack(): Px = tiling64 { g ->
        g.seed(2287).fill(packA)
        g.dither(packB, 0.6f)
        g.blotch(12, 5, packC)
        g.blotch(8, 4, packB)
        g.speckle(180, packB, packC, soilC)
        g.blur(1)
    }

    /**
     * The ice tile. Only the red channel is read, as a noise field for cracks
     * and trapped air, so the colour of it hardly matters — but painting it in
     * real ice colours makes it easy to look at while tuning.
     */
    fun ice(): Px = tiling64 { g ->
        g.seed(9931).fill(iceB)
        g.blotch(14, 9, iceA)
        g.blotch(9, 6, iceD)
        g.blur(2)
        g.scratch(26, 22, iceC)
        g.scratch(12, 30, c("#DCEEFA"))
        g.blur(1)
    }

    fun soil(): Px = tiling32 { g ->
        g.seed(3313).fill(soilA)
        g.dither(soilB, 0.7f)
        g.speckle(90, soilC, soilD)
        g.blur(1)
    }

    fun soilTilled(): Px = tiling32 { g ->
        g.seed(1721).fill(soilB)
        for (y in 0 until 32) {
            if (y % 6 < 2) g.hline(y, 0, 31, soilD)
            if (y % 6 == 3) g.hline(y, 0, 31, soilC)
        }
        g.speckle(60, soilA, soilD)
        g.blur(1)
    }

    fun soilWet(): Px = tiling32 { g ->
        g.seed(1721).fill(c("#4C3E32"))
        for (y in 0 until 32) {
            if (y % 6 < 2) g.hline(y, 0, 31, c("#3A2E24"))
            if (y % 6 == 3) g.hline(y, 0, 31, c("#5A4A3C"))
        }
        g.speckle(50, c("#645244"))
        g.blur(1)
    }

    /** Frozen shingle at the edge of the ice. */
    fun shingle(): Px = tiling32 { g ->
        g.seed(881).fill(c("#8E8C92"))
        g.speckle(220, c("#A2A0A8"), c("#7A7880"), c("#B8BAC4"))
        g.blur(1)
    }

    fun rock(): Px = tiling64 { g ->
        g.seed(5477).fill(stoneB)
        g.blotch(16, 8, stoneA)
        g.blotch(9, 5, c("#5A5E6A"))
        g.speckle(200, stoneC, stoneB)
        // snow caught in every ledge
        g.blotch(7, 4, c("#C8D4E4"))
        g.blur(2)
    }

    // --------------------------------------------------------------- wood

    /** Sawn boards, silvered by a few winters outdoors. */
    fun planks(): Px = tiling64 { g ->
        g.seed(7717).fill(greyWoodA)
        for (i in 0 until 6) {
            val y = i * 11
            g.hline(y, 0, 63, greyWoodB)
            g.hline(y + 1, 0, 63, c("#5E5A68"))
            for (x in 0 until 64) {
                if (g.rnd() < 0.5f) g.setW(x, y + 3 + (g.rnd() * 6).toInt(), greyWoodC)
            }
        }
        g.speckle(240, greyWoodB, greyWoodC)
        g.blur(1)
    }

    fun plankWorn(): Px = tiling64 { g ->
        g.seed(6353).fill(c("#7E7886"))
        for (i in 0 until 5) {
            val y = i * 13
            g.hline(y, 0, 63, c("#66626E"))
            g.hline(y + 1, 0, 63, c("#565260"))
        }
        g.blotch(10, 5, c("#8E8896"))
        g.speckle(200, c("#8E8896"), c("#5E5A68"))
        g.blur(1)
    }

    /** The cabin wall: stacked round logs, seen end-on from outside. */
    fun logs(): Px = tiling64 { g ->
        g.seed(4483).fill(woodB)
        for (row in 0 until 5) {
            val y0 = row * 13
            for (y in y0 until y0 + 13) {
                val t = (y - y0) / 12f
                val shade = when {
                    t < 0.12f -> woodD
                    t < 0.34f -> woodC
                    t < 0.72f -> woodA
                    else -> woodB
                }
                g.hline(y, 0, 63, shade)
            }
            g.hline(y0, 0, 63, c("#4E3A26"))
            // the chinking between courses
            g.hline(y0 + 12, 0, 63, c("#5E5A60"))
        }
        for (i in 0 until 30) {
            val x = (g.rnd() * 64).toInt()
            val y = (g.rnd() * 64).toInt()
            g.setW(x, y, woodD); g.setW(x + 1, y, woodD)
        }
        g.blur(1)
    }

    fun bark(): Px = tiling32 { g ->
        g.seed(2131).fill(barkA)
        for (x in 0 until 32) {
            if (g.rnd() < 0.42f) g.vline(x, 0, 31, barkB)
            if (g.rnd() < 0.26f) g.vline(x, 0, 31, barkC)
        }
        g.speckle(120, barkB, barkC)
        g.blur(1)
    }

    fun barkBirch(): Px = tiling64 { g ->
        g.seed(9109).fill(c("#DCDEE2"))
        g.dither(c("#C6C8D0"), 0.4f)
        for (i in 0 until 16) {
            val y = (g.rnd() * 64).toInt()
            val x = (g.rnd() * 64).toInt()
            val len = 4 + (g.rnd() * 12).toInt()
            for (k in 0 until len) g.setW(x + k, y, c("#3E3E46"))
            if (g.rnd() < 0.5f) for (k in 0 until len) g.setW(x + k, y + 1, c("#5A5A64"))
        }
        g.speckle(90, c("#B4B6C0"))
        g.blur(1)
    }

    /** Roof slates, nearly black, the way a wet cedar roof reads at dusk. */
    fun shingles(a: Int, b: Int, hi: Int): Px = tiling32 { g ->
        g.seed(3529).fill(a)
        for (row in 0 until 6) {
            val y = row * 6
            val off = if (row % 2 == 0) 0 else 4
            g.hline(y, 0, 31, b)
            for (k in 0 until 4) {
                val x = off + k * 8
                g.vline(x, y, y + 5, b)
                g.setW(x + 1, y + 1, hi)
            }
        }
        g.speckle(70, hi, b)
        g.blur(1)
    }

    /** Snow lying on a pitched roof: bright, with the slate showing at the eave. */
    fun roofSnow(): Px = tiling32 { g ->
        g.seed(7639).fill(snowB)
        g.dither(snowA, 0.5f)
        g.blotch(6, 4, snowC)
        g.speckle(50, snowD)
        g.blur(2)
    }

    fun stone(): Px = tiling64 { g ->
        g.seed(1013).fill(stoneB)
        for (row in 0 until 8) {
            val y = row * 8
            val off = if (row % 2 == 0) 0 else 5
            g.hline(y, 0, 63, c("#545864"))
            for (k in 0 until 6) g.vline(off + k * 11, y, y + 7, c("#545864"))
        }
        g.blotch(14, 4, stoneA)
        g.blotch(8, 3, stoneC)
        g.speckle(160, stoneA, stoneC)
        g.blur(1)
    }

    // ------------------------------------------------------------ foliage

    fun leaves(a: Int, b: Int, hi: Int): Px = tiling32 { g ->
        g.seed(6841).fill(b)
        g.blotch(18, 5, a)
        g.blotch(10, 3, hi)
        g.speckle(150, a, hi, b)
        // dry snow caught in the needles
        g.blotch(6, 3, c("#C8D6E8"))
        g.speckle(60, c("#D8E2F0"))
        g.blur(1)
    }

    fun pineNeedles(): Px = leaves(pineA, pineB, pineC)

    fun leafPlain(): Px = tiling64 { g ->
        g.seed(2789).fill(pineB)
        g.blotch(22, 7, pineA)
        g.blotch(12, 4, pineC)
        g.speckle(240, pineA, pineC)
        g.blur(2)
    }

    /** Dead bracken and frozen reeds poking through the crust. */
    fun deadGrass(): Px = tiling32 { g ->
        g.seed(4231).fill(c("#8A7A5E"))
        g.dither(c("#74684F"), 0.6f)
        g.speckle(120, c("#A08C6C"), c("#5E5644"))
        g.blur(1)
    }

    // ----------------------------------------------------- built surfaces

    /** A cold window: dark glass with the sky in it. */
    fun window(): Px = tiling32 { g ->
        g.seed(151).fill(c("#3A4A66"))
        g.rect(2, 2, 29, 29, c("#4A5E7E"))
        g.rect(3, 3, 15, 15, c("#56708E"))
        g.rect(17, 3, 28, 15, c("#4E6684"))
        g.rect(3, 17, 15, 28, c("#425A78"))
        g.rect(17, 17, 28, 28, c("#4A6280"))
        // frame
        g.rect(0, 0, 31, 1, woodD); g.rect(0, 30, 31, 31, woodD)
        g.rect(0, 0, 1, 31, woodD); g.rect(30, 0, 31, 31, woodD)
        g.rect(15, 2, 16, 29, woodD); g.rect(2, 15, 29, 16, woodD)
        g.blur(1)
    }

    /**
     * The lit window.
     *
     * This is the single most important texture in the game: it is the orange
     * rectangle the whole frame is built around. Hot in the middle, banded
     * where the glazing bars cross it, and with a warm bloom leaking out onto
     * the frame itself.
     */
    fun windowLit(): Px = tiling32 { g ->
        g.seed(151).fill(c("#FFC061"))
        for (y in 0 until 32) for (x in 0 until 32) {
            val dx = (x - 15.5f) / 16f
            val dy = (y - 15.5f) / 16f
            val d = min(1f, kotlin.math.sqrt(dx * dx + dy * dy))
            val col = when {
                d < 0.42f -> c("#FFE7B4")
                d < 0.70f -> c("#FFC468")
                else -> c("#F09A3E")
            }
            g.set(x, y, col)
        }
        // the glazing bars, dark against all that light
        g.rect(15, 2, 16, 29, c("#8A5A2E")); g.rect(2, 15, 29, 16, c("#8A5A2E"))
        g.rect(0, 0, 31, 1, c("#7A4E28")); g.rect(0, 30, 31, 31, c("#7A4E28"))
        g.rect(0, 0, 1, 31, c("#7A4E28")); g.rect(30, 0, 31, 31, c("#7A4E28"))
        // frost creeping in at the corners
        for (i in 0 until 40) {
            val x = (g.rnd() * 32).toInt(); val y = (g.rnd() * 32).toInt()
            val dx = (x - 15.5f) / 16f; val dy = (y - 15.5f) / 16f
            if (dx * dx + dy * dy > 0.55f) g.set(x, y, c("#FFEFD4"))
        }
        g.blur(1)
    }

    /** Glasshouse panes: pale, misted, with a hint of green behind them. */
    fun glass(): Px = tiling32 { g ->
        g.seed(6113).fill(c("#9EC0CE"))
        g.blotch(10, 6, c("#B4D2DC"))
        g.blotch(6, 4, c("#86AAB8"))
        g.speckle(80, c("#C8E0E8"))
        g.rect(0, 0, 31, 1, c("#5E6A66")); g.rect(0, 30, 31, 31, c("#5E6A66"))
        g.rect(0, 0, 1, 31, c("#5E6A66")); g.rect(30, 0, 31, 31, c("#5E6A66"))
        g.blur(2)
    }

    fun door(): Px = tiling32 { g ->
        g.seed(499).fill(woodD)
        for (x in 0 until 32) {
            if (x % 7 == 0) g.vline(x, 0, 31, c("#4E3A26"))
            if (x % 7 == 3) g.vline(x, 0, 31, woodB)
        }
        g.rect(0, 3, 31, 4, c("#4E3A26"))
        g.rect(0, 27, 31, 28, c("#4E3A26"))
        // the handle
        g.rect(25, 15, 27, 17, c("#C8A050"))
        g.blur(1)
    }

    fun awning(): Px = tiling32 { g ->
        g.seed(701).fill(c("#5E6E7E"))
        for (x in 0 until 32) if ((x / 5) % 2 == 0) g.vline(x, 0, 31, c("#43505E"))
        g.blur(1)
    }

    fun crate(): Px = tiling32 { g ->
        g.seed(919).fill(woodB)
        g.rect(0, 0, 31, 2, woodD); g.rect(0, 29, 31, 31, woodD)
        g.rect(0, 0, 2, 31, woodD); g.rect(29, 0, 31, 31, woodD)
        g.rect(0, 14, 31, 16, woodD)
        g.speckle(60, woodA, woodC)
        g.blur(1)
    }

    fun lanternGlass(): Px = tiling32 { g ->
        g.seed(337).fill(c("#FFCE7A"))
        g.blotch(6, 6, c("#FFE9B8"))
        g.rect(0, 0, 31, 2, c("#4A4048")); g.rect(0, 29, 31, 31, c("#4A4048"))
        g.vline(0, 0, 31, c("#4A4048")); g.vline(31, 0, 31, c("#4A4048"))
        g.blur(1)
    }

    /** Old red paint over older rust: the truck. */
    fun truckPaint(): Px = tiling32 { g ->
        g.seed(2647).fill(rustA)
        g.blotch(9, 5, rustC)
        g.blotch(7, 4, rustB)
        g.speckle(120, c("#C46A54"), c("#66302A"))
        g.scratch(10, 8, c("#5A4A44"))
        g.blur(1)
    }

    fun metal(): Px = tiling32 { g ->
        g.seed(1279).fill(c("#6E7280"))
        g.blotch(8, 4, c("#868A98"))
        g.speckle(110, c("#565A66"), c("#9AA0AC"))
        g.blur(1)
    }

    fun rustyMetal(): Px = tiling32 { g ->
        g.seed(5231).fill(c("#6A6470"))
        g.blotch(9, 5, c("#7E5A44"))
        g.blotch(5, 3, c("#8E6A4E"))
        g.speckle(120, c("#4E4A56"), c("#96826E"))
        g.blur(1)
    }

    // ------------------------------------------------------------- fabric

    fun cloth(base: Int, shade: Int): Px = tiling32 { g ->
        g.seed(2477).fill(base)
        g.dither(shade, 0.4f)
        g.speckle(50, shade)
        g.blur(1)
    }

    /** Chunky knitting: the scarf, the hat, the mittens. */
    fun knit(base: Int, shade: Int): Px = tiling32 { g ->
        g.seed(8123).fill(base)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val v = ((x + (y / 4) * 2) / 4) % 2
                if (v == 0) g.setW(x, y, shade)
            }
        }
        for (y in 0 until 32 step 4) g.hline(y, 0, 31, base)
        g.speckle(60, base)
        g.blur(1)
    }

    fun skin(): Px = tiling32 { g ->
        g.seed(613).fill(c("#E8B694"))
        g.dither(c("#DCA684"), 0.3f)
        g.blur(1)
    }

    fun straw(): Px = tiling32 { g ->
        g.seed(883).fill(c("#D6BE7E"))
        for (i in 0 until 60) {
            val x = (g.rnd() * 32).toInt(); val y = (g.rnd() * 32).toInt()
            g.setW(x, y, c("#B8A05E")); g.setW(x + 1, y, c("#E8D49A"))
        }
        g.blur(1)
    }

    fun fur(base: Int, shade: Int): Px = tiling32 { g ->
        g.seed(1493).fill(base)
        for (i in 0 until 130) {
            val x = (g.rnd() * 32).toInt(); val y = (g.rnd() * 32).toInt()
            g.setW(x, y, shade); g.setW(x, y + 1, shade)
        }
        g.blur(1)
    }

    fun solid(color: Int, shade: Int = 0): Px = tiling32 { g ->
        g.seed(211).fill(color)
        if (shade != 0) {
            g.dither(shade, 0.22f)
            g.blur(1)
        }
    }
}

/** Sprites: soft masks, faces, tufts and flakes. All alpha, all painted. */
object PixelSprites {

    private fun c(hex: String) = android.graphics.Color.parseColor(hex)

    /** A soft round mask, used for glow, shadow and smoke. */
    private fun radial(size: Int, inner: Int, outer: Int, power: Float): Px = Px(size, size).also { g ->
        val r = size * 0.5f
        for (y in 0 until size) for (x in 0 until size) {
            val dx = (x + 0.5f - r) / r
            val dy = (y + 0.5f - r) / r
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d >= 1f) { g.set(x, y, 0); continue }
            val t = Math.pow((1f - d).toDouble(), power.toDouble()).toFloat()
            val a = (t * 255f).toInt().coerceIn(0, 255)
            val ir = (inner shr 16) and 0xFF; val ig = (inner shr 8) and 0xFF; val ib = inner and 0xFF
            val or = (outer shr 16) and 0xFF; val og = (outer shr 8) and 0xFF; val ob = outer and 0xFF
            val rr = (or + (ir - or) * t).toInt()
            val gg = (og + (ig - og) * t).toInt()
            val bb = (ob + (ib - ob) * t).toInt()
            g.set(x, y, (a shl 24) or (rr shl 16) or (gg shl 8) or bb)
        }
    }

    fun glow(): Px = radial(48, 0xFFFFFF, 0xFFB060, 2.1f)

    fun softShadow(): Px = radial(48, 0x000000, 0x000000, 1.5f)

    fun dot(): Px = radial(16, 0xFFFFFF, 0xFFFFFF, 1.0f)

    fun cloud(): Px = Px(48, 32).also { g ->
        fun puff(cx: Int, cy: Int, r: Int) {
            for (y in -r..r) for (x in -r..r) {
                val d = kotlin.math.sqrt((x * x + y * y).toFloat()) / r
                if (d >= 1f) continue
                val a = ((1f - d) * 200f).toInt().coerceIn(0, 255)
                val prev = (g.get(cx + x, cy + y) ushr 24) and 0xFF
                if (a > prev) g.setW(cx + x, cy + y, (a shl 24) or 0xFFFFFF)
            }
        }
        puff(16, 18, 11); puff(28, 16, 10); puff(22, 12, 9); puff(36, 20, 8); puff(9, 21, 8)
        g.blur(1)
    }

    /** A six-armed flake, for the big lazy foreground snow. */
    fun flake(): Px = Px(16, 16).also { g ->
        val white = (0xFF shl 24) or 0xFFFFFF
        g.set(8, 8, white)
        for (arm in 0 until 6) {
            val a = arm * 1.0472f
            val dx = cos(a); val dy = sin(a)
            for (k in 1..6) {
                val x = (8 + dx * k).toInt()
                val y = (8 + dy * k).toInt()
                g.set(x, y, white)
                if (k == 3 || k == 5) {
                    val px = cos(a + 1.0472f); val py = sin(a + 1.0472f)
                    g.set((x + px * 1.6f).toInt(), (y + py * 1.6f).toInt(), white)
                    g.set((x - px * 1.6f).toInt(), (y - py * 1.6f).toInt(), white)
                }
            }
        }
        g.blur(1)
    }

    /** An expanding ring, for a hole in the ice and for footfalls. */
    fun ring(): Px = Px(32, 32).also { g ->
        for (y in 0 until 32) for (x in 0 until 32) {
            val dx = (x + 0.5f - 16f) / 16f
            val dy = (y + 0.5f - 16f) / 16f
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val band = 1f - abs(d - 0.78f) / 0.20f
            val a = (band.coerceIn(0f, 1f) * 255f).toInt()
            g.set(x, y, (a shl 24) or 0xFFFFFF)
        }
        g.blur(1)
    }

    /** A boot print pressed into fresh snow, left behind as you walk. */
    fun bootPrint(): Px = Px(32, 32).also { g ->
        for (y in 0 until 32) for (x in 0 until 32) {
            val dx = (x + 0.5f - 16f) / 9f
            val dy = (y + 0.5f - 16f) / 14f
            val d = dx * dx + dy * dy
            if (d > 1f) { g.set(x, y, 0); continue }
            val a = ((1f - d) * 210f).toInt().coerceIn(0, 255)
            g.set(x, y, (a shl 24) or 0x6E86A8)
        }
        g.blur(1)
    }

    /** A face: two eyes and a small pleased mouth. Nothing more is needed. */
    fun face(): Px = Px(16, 16).also { g ->
        val ink = (0xFF shl 24) or 0x3A3446
        val blush = (0x8C shl 24) or 0xE08A90
        g.set(5, 7, ink); g.set(5, 8, ink)
        g.set(10, 7, ink); g.set(10, 8, ink)
        g.set(7, 11, ink); g.set(8, 11, ink)
        g.set(6, 10, ink); g.set(9, 10, ink)
        for (x in 2..4) for (y in 9..10) g.set(x, y, blush)
        for (x in 11..13) for (y in 9..10) g.set(x, y, blush)
    }

    fun foxFace(): Px = Px(16, 16).also { g ->
        val ink = (0xFF shl 24) or 0x3A2A22
        g.set(4, 7, ink); g.set(4, 8, ink)
        g.set(11, 7, ink); g.set(11, 8, ink)
        for (x in 7..8) for (y in 10..11) g.set(x, y, ink)
        g.set(6, 12, ink); g.set(9, 12, ink)
    }

    fun catFace(): Px = Px(16, 16).also { g ->
        val ink = (0xFF shl 24) or 0x2E2A38
        // shut, contented eyes
        for (x in 3..6) g.set(x, 8, ink)
        for (x in 9..12) g.set(x, 8, ink)
        g.set(7, 11, ink); g.set(8, 11, ink)
        g.set(6, 10, ink); g.set(9, 10, ink)
    }

    /** A sheet of small upright things: dead tufts, reeds, twigs. */
    fun detailSheet(): Px = Px(32, 32).also { g ->
        fun stalk(x0: Int, y0: Int, h: Int, col: Int, lean: Int) {
            for (k in 0 until h) {
                val x = x0 + (k * lean) / 6
                g.set(x, y0 - k, col)
            }
        }
        // top-left: dead grass tuft
        val dry = (0xFF shl 24) or 0x8A7A5E
        val dry2 = (0xFF shl 24) or 0xA89468
        for (i in 0 until 9) stalk(2 + i, 15, 5 + (i % 4) * 2, if (i % 2 == 0) dry else dry2, (i % 3) - 1)
        // top-right: frozen reeds
        val reed = (0xFF shl 24) or 0x9A8E70
        for (i in 0 until 6) stalk(18 + i * 2, 15, 9 + (i % 3) * 3, reed, (i % 2))
        // bottom-left: bare twigs
        val twig = (0xFF shl 24) or 0x5E5248
        for (i in 0 until 7) stalk(3 + i * 2, 31, 6 + (i % 3) * 3, twig, (i % 3) - 1)
        // bottom-right: snow-crusted tuft
        val crust = (0xFF shl 24) or 0xD8E2F0
        for (i in 0 until 8) stalk(18 + i, 31, 4 + (i % 3) * 2, if (i % 3 == 0) crust else dry, (i % 3) - 1)
    }

    /** Berry sprays and snowdrops, for the little spots of colour. */
    fun flowers(): Px = Px(32, 32).also { g ->
        val stem = (0xFF shl 24) or 0x4E6A4A
        val red = (0xFF shl 24) or 0xC8434E
        val white = (0xFF shl 24) or 0xF2F6FA
        fun spray(ox: Int, oy: Int, berry: Int) {
            for (k in 0 until 8) g.set(ox + 7, oy + 15 - k, stem)
            g.set(ox + 5, oy + 6, berry); g.set(ox + 6, oy + 6, berry)
            g.set(ox + 8, oy + 5, berry); g.set(ox + 9, oy + 5, berry)
            g.set(ox + 6, oy + 3, berry); g.set(ox + 7, oy + 3, berry)
        }
        spray(0, 0, red)
        spray(16, 0, white)
        spray(0, 16, red)
        spray(16, 16, white)
    }
}
