package com.cozyhollow.riverside.gl

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A tiny pixel canvas. Everything the game wears is painted here, texel by texel. */
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

    /** Soft organic patches, used for moss, clumps of grass and rust. */
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
}

/**
 * Every texture in the game, painted procedurally at 32x32 so it stays crisp
 * under nearest-neighbour magnification and ships as zero asset files.
 */
object PixelTex {

    private fun c(hex: String) = android.graphics.Color.parseColor(hex)

    // cohesive cosy palette
    val grassA = c("#7BB661"); val grassB = c("#6AA455"); val grassC = c("#8CC96F"); val grassD = c("#5C9049")
    val soilA = c("#8A6242"); val soilB = c("#7A5438"); val soilC = c("#9C7350"); val soilD = c("#63432C")
    val woodA = c("#C08E58"); val woodB = c("#A87646"); val woodC = c("#D2A472"); val woodD = c("#8A5E36")
    val barkA = c("#7A5A3E"); val barkB = c("#63482F"); val barkC = c("#8C6B4A")
    val roofA = c("#B05A46"); val roofB = c("#94473A"); val roofC = c("#C66E56")
    val roofDarkA = c("#6E4A66"); val roofDarkB = c("#5A3C55")
    val stoneA = c("#A79C90"); val stoneB = c("#8F857A"); val stoneC = c("#BDB2A6")
    val waterA = c("#4A8AAB"); val waterB = c("#3E7896"); val waterC = c("#5F9FBC"); val waterD = c("#2F6580")
    val pineA = c("#3E7A55"); val pineB = c("#336647"); val pineC = c("#4C8F63")
    val oakA = c("#6BA854"); val oakB = c("#5B9247"); val oakC = c("#7EBE63")

    fun tiling32(build: (Px) -> Unit): Px = Px(32, 32).also(build)

    fun grass(): Px = tiling32 { g ->
        // kept low-contrast on purpose: strong blotches at a 1 m repeat read as
        // an obvious checkerboard once the ground stretches to the horizon
        g.seed(9137).fill(grassA)
        g.dither(grassB, 0.55f)
        g.speckle(150, grassB, grassC)
        for (i in 0 until 34) {
            val x = (g.rnd() * 32).toInt(); val y = (g.rnd() * 32).toInt()
            g.setW(x, y, grassD); g.setW(x, y - 1, grassC)
        }
    }

    fun grassDry(): Px = tiling32 { g ->
        g.seed(4471).fill(c("#9FB863"))
        g.dither(c("#8CA556"), 0.9f)
        g.blotch(6, 3, c("#B4CC78"))
        g.speckle(70, c("#7E9450"), c("#B4CC78"))
    }

    fun soil(): Px = tiling32 { g ->
        g.seed(2211).fill(soilA)
        g.dither(soilB, 0.9f)
        g.blotch(6, 3, soilC)
        g.blotch(5, 2, soilD)
        g.speckle(110, soilC, soilD, stoneB)
    }

    fun soilTilled(): Px = tiling32 { g ->
        g.seed(7781).fill(c("#5E4029"))
        g.dither(c("#523522"), 0.85f)
        // furrows running along the row
        for (y in 0 until 32 step 8) {
            g.hline(y, 0, 31, c("#6E4C31"))
            g.hline(y + 1, 0, 31, c("#4A3220"))
        }
        g.speckle(80, c("#7A5638"), c("#432D1D"))
    }

    fun soilWet(): Px = tiling32 { g ->
        g.seed(7781).fill(c("#46301F"))
        for (y in 0 until 32 step 8) {
            g.hline(y, 0, 31, c("#553B26"))
            g.hline(y + 1, 0, 31, c("#372416"))
        }
        g.speckle(70, c("#5E4229"), c("#2E1E12"))
    }

    fun sand(): Px = tiling32 { g ->
        g.seed(5521).fill(c("#C9B189"))
        g.dither(c("#BCA47C"), 0.7f)
        g.speckle(80, c("#D4BE98"), c("#AB9370"))
    }

    /** Horizontal planks with seams and grain. */
    fun planks(): Px = tiling32 { g ->
        g.seed(3313).fill(woodA)
        for (y in 0 until 32) {
            val band = y / 8
            val base = when (band) {
                0 -> woodA; 1 -> woodB; 2 -> woodC; else -> woodA
            }
            g.hline(y, 0, 31, base)
        }
        for (y in 0 until 32 step 8) {
            g.hline(y, 0, 31, woodD)
            g.hline(y + 7, 0, 31, c("#B08050"))
        }
        // grain
        for (i in 0 until 40) {
            val x = (g.rnd() * 32).toInt()
            val y = (g.rnd() * 32).toInt()
            val len = 2 + (g.rnd() * 5).toInt()
            for (k in 0 until len) g.setW(x + k, y, if (g.rnd() < 0.5f) woodD else woodC)
        }
        // nails
        for (x in intArrayOf(3, 19)) for (y in intArrayOf(3, 11, 19, 27)) g.setW(x, y, c("#6E4A2C"))
    }

    /** Stacked round logs, for the first cabin. */
    fun logs(): Px = tiling32 { g ->
        g.seed(6161).fill(woodB)
        for (row in 0 until 4) {
            val y0 = row * 8
            for (y in y0 until y0 + 8) {
                val t = (y - y0) / 7f
                val shade = when {
                    t < 0.18f -> woodC
                    t < 0.62f -> woodA
                    t < 0.86f -> woodB
                    else -> woodD
                }
                g.hline(y, 0, 31, shade)
            }
            g.hline(y0 + 7, 0, 31, c("#6E4A2C"))
        }
        for (i in 0 until 30) {
            val x = (g.rnd() * 32).toInt(); val y = (g.rnd() * 32).toInt()
            g.setW(x, y, if (g.rnd() < 0.5f) woodD else woodC)
        }
    }

    /** Scalloped shingles. */
    fun shingles(a: Int, b: Int, hi: Int): Px = tiling32 { g ->
        g.seed(8123).fill(a)
        for (row in 0 until 4) {
            val y0 = row * 8
            val off = if (row % 2 == 0) 0 else 4
            for (y in y0 until y0 + 8) g.hline(y, 0, 31, if (y < y0 + 2) hi else a)
            for (col in 0 until 4) {
                val x0 = col * 8 + off
                g.vline(x0, y0, y0 + 7, b)
                // rounded bottom of each shingle
                g.setW(x0 + 1, y0 + 7, b)
                g.setW(x0 + 7, y0 + 7, b)
            }
            g.hline(y0 + 7, 0, 31, b)
        }
        g.speckle(50, b, hi)
    }

    fun stone(): Px = tiling32 { g ->
        g.seed(1777).fill(stoneB)
        // cobbles
        for (row in 0 until 4) {
            val y0 = row * 8
            val off = if (row % 2 == 0) 0 else 5
            for (col in 0 until 3) {
                val x0 = col * 11 + off
                for (y in y0 + 1 until y0 + 7) for (x in x0 + 1 until x0 + 10) {
                    val edge = (x == x0 + 1 || x == x0 + 9 || y == y0 + 1 || y == y0 + 6)
                    g.setW(x, y, if (edge) stoneB else if (g.rnd() < 0.2f) stoneC else stoneA)
                }
            }
        }
        g.speckle(60, stoneC, stoneB)
    }

    fun water(): Px = tiling32 { g ->
        g.seed(3931).fill(waterA)
        for (y in 0 until 32) {
            val s = sin(y * 0.42f) * 2.2f
            for (x in 0 until 32) {
                val v = sin((x + s) * 0.34f) + cos(y * 0.3f) * 0.7f
                g.setW(x, y, if (v > 1.15f) waterC else if (v > -0.2f) waterA else waterB)
            }
        }
        g.speckle(18, waterC)
    }

    fun bark(): Px = tiling32 { g ->
        g.seed(2593).fill(barkA)
        for (x in 0 until 32) {
            val col = when ((x / 3) % 3) { 0 -> barkB; 1 -> barkA; else -> barkC }
            g.vline(x, 0, 31, col)
        }
        for (i in 0 until 50) {
            val x = (g.rnd() * 32).toInt(); val y = (g.rnd() * 32).toInt()
            val len = 2 + (g.rnd() * 6).toInt()
            for (k in 0 until len) g.setW(x, y + k, barkB)
        }
    }

    fun leaves(a: Int, b: Int, hi: Int): Px = tiling32 { g ->
        g.seed(4243).fill(a)
        g.dither(b, 0.95f)
        g.blotch(9, 3, hi)
        g.blotch(7, 2, b)
        for (i in 0 until 60) {
            val x = (g.rnd() * 32).toInt(); val y = (g.rnd() * 32).toInt()
            g.setW(x, y, hi); g.setW(x + 1, y + 1, b)
        }
    }

    fun pineNeedles(): Px = leaves(pineA, pineB, pineC)
    fun oakLeaves(): Px = leaves(oakA, oakB, oakC)

    /** Cream and berry awning stripes. */
    fun awning(): Px = tiling32 { g ->
        val cream = c("#F2E4CA"); val berry = c("#C4626C")
        for (x in 0 until 32) g.vline(x, 0, 31, if ((x / 8) % 2 == 0) cream else berry)
        for (x in 0 until 32 step 8) g.vline(x, 0, 31, c("#D8C8AC"))
    }

    fun crate(): Px = tiling32 { g ->
        g.seed(9091).fill(woodB)
        g.rect(0, 0, 31, 31, woodB)
        for (y in 0 until 32) g.hline(y, 0, 31, if ((y / 4) % 2 == 0) woodA else woodB)
        g.rect(0, 0, 2, 31, woodD); g.rect(29, 0, 31, 31, woodD)
        g.rect(0, 0, 31, 2, woodD); g.rect(0, 29, 31, 31, woodD)
        g.hline(15, 0, 31, woodD); g.hline(16, 0, 31, woodD)
        g.speckle(40, woodC, woodD)
    }

    /** Window: dark frame, warm panes, a cross bar. */
    fun window(): Px = tiling32 { g ->
        val frame = c("#6E4A2C"); val glass = c("#9FCDE0"); val glassHi = c("#C6E5F2")
        g.fill(frame)
        g.rect(4, 4, 27, 27, glass)
        for (y in 4..27) for (x in 4..27) if (x - 4 < 27 - y) g.set(x, y, glassHi)
        g.rect(14, 4, 17, 27, frame)
        g.rect(4, 14, 27, 17, frame)
        g.rect(0, 0, 31, 3, frame); g.rect(0, 28, 31, 31, frame)
    }

    fun windowLit(): Px = tiling32 { g ->
        val frame = c("#5C3C22"); val glow = c("#FFD98A"); val glowHi = c("#FFF0C4")
        g.fill(frame)
        g.rect(4, 4, 27, 27, glow)
        for (y in 4..27) for (x in 4..27) if (x - 4 < 27 - y) g.set(x, y, glowHi)
        g.rect(14, 4, 17, 27, frame)
        g.rect(4, 14, 27, 17, frame)
        g.rect(0, 0, 31, 3, frame); g.rect(0, 28, 31, 31, frame)
    }

    fun door(): Px = tiling32 { g ->
        g.seed(5150).fill(c("#7A5230"))
        for (x in 0 until 32) g.vline(x, 0, 31, if ((x / 6) % 2 == 0) c("#7A5230") else c("#6B4728"))
        g.rect(0, 0, 31, 2, c("#5A3A20")); g.rect(0, 29, 31, 31, c("#5A3A20"))
        g.rect(0, 0, 2, 31, c("#5A3A20")); g.rect(29, 0, 31, 31, c("#5A3A20"))
        // handle
        g.rect(24, 15, 26, 18, c("#E8B44A"))
        g.speckle(30, c("#8C6038"))
    }

    fun cloth(base: Int, shade: Int): Px = tiling32 { g ->
        g.seed(base).fill(base)
        g.dither(shade, 0.7f)
        g.speckle(40, shade)
    }

    fun skin(): Px = tiling32 { g ->
        g.seed(3111).fill(c("#F0C49C"))
        g.dither(c("#E4B389"), 0.4f)
    }

    fun straw(): Px = tiling32 { g ->
        g.seed(6412).fill(c("#E0BE79"))
        for (y in 0 until 32) g.hline(y, 0, 31, if ((y / 3) % 2 == 0) c("#E0BE79") else c("#CBA765"))
        g.speckle(70, c("#F0D79A"), c("#B8934F"))
    }

    fun fur(base: Int, shade: Int): Px = tiling32 { g ->
        g.seed(base xor 0x5A5A).fill(base)
        for (i in 0 until 90) {
            val x = (g.rnd() * 32).toInt(); val y = (g.rnd() * 32).toInt()
            g.setW(x, y, shade); g.setW(x, y + 1, shade)
        }
        g.dither(shade, 0.3f)
    }

    fun metal(): Px = tiling32 { g ->
        g.seed(8811).fill(c("#B4BAC2"))
        g.dither(c("#9AA0A8"), 0.6f)
        g.speckle(30, c("#D2D8E0"), c("#848A92"))
    }

    /** Flat colour with a touch of noise so it still reads as painted pixels. */
    fun solid(color: Int, shade: Int = 0): Px = tiling32 { g ->
        g.seed(color).fill(color)
        if (shade != 0) g.dither(shade, 0.35f)
    }


    fun tiling64(build: (Px) -> Unit): Px = Px(64, 64).also(build)

    /**
     * Ground detail, painted as light and shade only.
     *
     * Every square metre of the hollow samples this one tile and takes its
     * colour from the vertex instead: meadow green, dry gold on the ridges,
     * sand at the waterline, packed earth along the track. One texture, a
     * hundred moods, and the blends between them are smooth because they
     * happen in the vertex colour rather than between two tiling textures.
     */
    fun ground(): Px = tiling64 { g ->
        g.seed(9137).fill(c("#B9B9B9"))
        g.dither(c("#ACACAC"), 0.6f)
        g.blotch(14, 5, c("#C4C4C4"))
        g.blotch(11, 4, c("#A4A4A4"))
        g.speckle(420, c("#CFCFCF"), c("#9C9C9C"), c("#C0C0C0"))
        // little tufts, so close ground has something to catch the light
        for (i in 0 until 90) {
            val x = (g.rnd() * 64).toInt(); val y = (g.rnd() * 64).toInt()
            g.setW(x, y, c("#D2D2D2")); g.setW(x, y - 1, c("#DCDCDC"))
            g.setW(x + 1, y, c("#A8A8A8"))
        }
    }

    /** Leaf mass in luminance only, so one tile serves pine, oak and blossom. */
    fun leafPlain(): Px = tiling64 { g ->
        g.seed(4243).fill(c("#BEBEBE"))
        g.dither(c("#ADADAD"), 0.9f)
        g.blotch(16, 4, c("#D2D2D2"))
        g.blotch(14, 3, c("#9E9E9E"))
        for (i in 0 until 220) {
            val x = (g.rnd() * 64).toInt(); val y = (g.rnd() * 64).toInt()
            g.setW(x, y, c("#DADADA")); g.setW(x + 1, y + 1, c("#9A9A9A"))
        }
    }

    /** Birch: pale bark with the dark dashes. */
    fun barkBirch(): Px = tiling64 { g ->
        g.seed(1913).fill(c("#E4DED2"))
        g.dither(c("#D6CFC0"), 0.5f)
        for (i in 0 until 26) {
            val x = (g.rnd() * 64).toInt(); val y = (g.rnd() * 64).toInt()
            val len = 3 + (g.rnd() * 7).toInt()
            for (k in 0 until len) {
                g.setW(x + k, y, c("#4A4038"))
                if (g.rnd() < 0.4f) g.setW(x + k, y + 1, c("#6B5F52"))
            }
        }
        g.speckle(90, c("#C9C0B0"), c("#F2EDE2"))
    }

    /** Weathered granite for boulders and the hillside outcrops. */
    fun rock(): Px = tiling64 { g ->
        g.seed(6607).fill(c("#9A958C"))
        g.dither(c("#8B867E"), 0.75f)
        g.blotch(10, 6, c("#A8A399"))
        g.blotch(8, 4, c("#7C776F"))
        // fractures
        for (i in 0 until 14) {
            var x = (g.rnd() * 64).toInt()
            var y = (g.rnd() * 64).toInt()
            for (k in 0 until 10 + (g.rnd() * 14).toInt()) {
                g.setW(x, y, c("#6E6A63"))
                if (g.rnd() < 0.6f) x += 1 else y += 1
            }
        }
        // a little moss where the rain sits
        g.blotch(5, 3, c("#7E9464"))
        g.speckle(120, c("#B2ADA3"), c("#75706A"))
    }

    /** Straw thatch for the market roof and the beehives. */
    fun thatch(): Px = tiling64 { g ->
        g.seed(4816).fill(c("#C9A867"))
        for (y in 0 until 64) {
            val band = (y / 8) % 2
            g.hline(y, 0, 63, if (band == 0) c("#C9A867") else c("#B8975A"))
        }
        for (i in 0 until 260) {
            val x = (g.rnd() * 64).toInt(); val y = (g.rnd() * 64).toInt()
            val len = 2 + (g.rnd() * 5).toInt()
            val col = if (g.rnd() < 0.5f) c("#DCC084") else c("#9E8049")
            for (k in 0 until len) g.setW(x, y + k, col)
        }
        for (y in 0 until 64 step 8) g.hline(y, 0, 63, c("#8E7342"))
    }

    /** Lantern glass: warm, and drawn unlit by the shader when the sun is up. */
    fun lanternGlass(): Px = tiling32 { g ->
        g.seed(2277).fill(c("#FFD98A"))
        g.dither(c("#FFC85A"), 0.4f)
        g.blotch(4, 4, c("#FFF0C4"))
    }

    /** Planks worn smooth by boots: the bridge, the jetty, the porch. */
    fun plankWorn(): Px = tiling64 { g ->
        g.seed(3313).fill(c("#B08A5E"))
        for (y in 0 until 64) {
            val band = y / 16
            val base = when (band) {
                0 -> c("#B08A5E"); 1 -> c("#A07C52"); 2 -> c("#BC9668"); else -> c("#A88252")
            }
            g.hline(y, 0, 63, base)
        }
        for (y in 0 until 64 step 16) {
            g.hline(y, 0, 63, c("#7C5E3C"))
            g.hline(y + 15, 0, 63, c("#C6A278"))
        }
        for (i in 0 until 120) {
            val x = (g.rnd() * 64).toInt(); val y = (g.rnd() * 64).toInt()
            val len = 3 + (g.rnd() * 9).toInt()
            for (k in 0 until len) g.setW(x + k, y, if (g.rnd() < 0.5f) c("#95744C") else c("#C2A078"))
        }
    }

    /** Vertical gradient strip used for the sky dome. */
    fun skyRamp(): Px = Px(1, 64).also { g -> g.fill(-1) }
}

/** Extra sprites that need transparency. */
object PixelSprites {

    private fun c(hex: String) = android.graphics.Color.parseColor(hex)
    private const val CLEAR = 0

    /** A soft pixel cloud with alpha. */
    fun cloud(): Px = Px(32, 16).also { g ->
        g.seed(7311).fill(CLEAR)
        val white = c("#FFFFFF")
        val shade = c("#DCE6EE")
        fun puff(cx: Int, cy: Int, r: Int) {
            for (y in -r..r) for (x in -r..r) {
                if (x * x + y * y <= r * r) g.set(cx + x, cy + y, if (y > r / 2) shade else white)
            }
        }
        puff(9, 10, 5); puff(16, 8, 6); puff(23, 10, 4); puff(13, 11, 4)
        // flatten the base
        for (x in 0 until 32) for (y in 13 until 16) g.set(x, y, CLEAR)
        for (x in 4 until 28) g.set(x, 12, shade)
    }

    /** The farmer's face, applied as a decal on the front of the head. */
    fun face(): Px = Px(16, 16).also { g ->
        g.fill(CLEAR)
        val ink = c("#3A2A20")
        val blush = c("#E8907E")
        g.rect(4, 6, 5, 8, ink)
        g.rect(10, 6, 11, 8, ink)
        g.set(4, 6, c("#FFFFFF")); g.set(10, 6, c("#FFFFFF"))
        g.rect(2, 9, 3, 10, blush)
        g.rect(12, 9, 13, 10, blush)
        g.rect(6, 11, 9, 11, ink)
        g.set(5, 10, ink); g.set(10, 10, ink)
    }

    /** Pip the shopkeeper's face. */
    fun foxFace(): Px = Px(16, 16).also { g ->
        g.fill(CLEAR)
        val ink = c("#3A2A20")
        g.rect(3, 5, 4, 7, ink)
        g.rect(11, 5, 12, 7, ink)
        g.set(3, 5, c("#FFFFFF")); g.set(11, 5, c("#FFFFFF"))
        g.rect(7, 9, 8, 10, ink)
        g.rect(5, 11, 10, 11, ink)
        g.set(4, 10, ink); g.set(11, 10, ink)
    }

    /** A clump of grass blades on transparency, for the ground detail quads. */
    fun blade(): Px = Px(16, 16).also { g ->
        g.seed(2027).fill(CLEAR)
        val a = c("#6FA855"); val b = c("#5C9147"); val hi = c("#87C067")
        fun stalk(x: Int, h: Int, col: Int) {
            for (y in 0 until h) {
                val yy = 15 - y
                val lean = (y * x) % 3 - 1
                g.set(x + lean * (y / 6), yy, if (y > h - 3) hi else col)
            }
        }
        stalk(3, 9, b); stalk(6, 13, a); stalk(9, 11, hi); stalk(12, 8, b)
        stalk(7, 15, a)
    }

    private val BAYER = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    )

    /**
     * Ground shadow. The falloff is an ordered dither rather than an alpha ramp,
     * because the world shader alpha-tests at 0.4 and would clip a soft edge away.
     */
    fun shadow(): Px = Px(32, 32).also { g ->
        g.fill(CLEAR)
        val dark = 0xFF000000.toInt()
        for (y in 0 until 32) for (x in 0 until 32) {
            val dx = (x - 15.5f) / 15.5f
            val dy = (y - 15.5f) / 15.5f
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d >= 1f) continue
            val density = (1f - d) * 1.35f
            val threshold = (BAYER[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f
            if (density > threshold) g.set(x, y, dark)
        }
    }

    /** Four wildflowers on one sheet; each baked flower samples a quadrant. */
    fun flowers(): Px = Px(32, 32).also { g ->
        val stem = c("#5C9147")
        val heads = intArrayOf(c("#E8A0C0"), c("#F2D45A"), c("#C9A9E8"), c("#F0F0E2"))
        val cores = intArrayOf(c("#F2D45A"), c("#E8963C"), c("#F2D45A"), c("#F2D45A"))
        g.fill(CLEAR)
        for (q in 0 until 4) {
            val ox = (q % 2) * 16
            val oy = (q / 2) * 16
            // stem
            for (y in 9 until 16) g.set(ox + 8, oy + y, stem)
            g.set(ox + 7, oy + 12, stem)
            g.set(ox + 10, oy + 11, stem)
            // petals
            val h = heads[q]
            for (d in intArrayOf(-3, 0, 3)) {
                g.set(ox + 8 + d, oy + 4, h)
                g.set(ox + 8 + d, oy + 5, h)
            }
            for (dy in intArrayOf(-3, 0, 3)) {
                g.set(ox + 5, oy + 7 + dy, h)
                g.set(ox + 11, oy + 7 + dy, h)
            }
            for (y in 5 until 10) for (x in 6 until 11) g.set(ox + x, oy + y, h)
            g.set(ox + 8, oy + 7, cores[q])
            g.set(ox + 7, oy + 7, cores[q])
        }
    }


    /** A soft round shadow with a real alpha ramp. */
    fun softShadow(): Px = Px(32, 32).also { g ->
        g.fill(CLEAR)
        for (y in 0 until 32) for (x in 0 until 32) {
            val dx = (x - 15.5f) / 15.5f
            val dy = (y - 15.5f) / 15.5f
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d >= 1f) continue
            val a = ((1f - d) * (1f - d) * 235f).toInt().coerceIn(0, 255)
            g.set(x, y, (a shl 24))
        }
    }

    /** Warm halo for lanterns, the campfire and fireflies. */
    fun glow(): Px = Px(32, 32).also { g ->
        g.fill(CLEAR)
        for (y in 0 until 32) for (x in 0 until 32) {
            val dx = (x - 15.5f) / 15.5f
            val dy = (y - 15.5f) / 15.5f
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d >= 1f) continue
            val f = (1f - d)
            val a = (f * f * f * 255f).toInt().coerceIn(0, 255)
            g.set(x, y, (a shl 24) or 0xFFFFFF)
        }
    }

    /** Reeds and rushes for the water's edge. */
    fun reed(): Px = Px(16, 16).also { g ->
        g.seed(3771).fill(CLEAR)
        val a = c("#7FA35C"); val b = c("#6A8C4C"); val tip = c("#A8B96A")
        fun stalk(x: Int, h: Int, col: Int) {
            for (y in 0 until h) {
                val yy = 15 - y
                val lean = if (y > h - 5) (y - h + 5) / 2 else 0
                g.set(x + lean, yy, if (y > h - 3) tip else col)
            }
        }
        stalk(3, 14, a); stalk(6, 16, b); stalk(9, 12, a); stalk(12, 15, b)
    }

    /** A fern frond, for the shade under the trees. */
    fun fern(): Px = Px(16, 16).also { g ->
        g.fill(CLEAR)
        val a = c("#5F8F4C"); val b = c("#4E7A3E")
        for (y in 2 until 16) g.set(8, y, b)
        for (k in 0 until 6) {
            val y = 3 + k * 2
            val w = 6 - k
            for (x in 0 until w) {
                g.set(8 - 1 - x, y + x / 2, a)
                g.set(8 + 1 + x, y + x / 2, a)
            }
        }
    }

    /** A lily pad, sat flat on the pond. */
    fun lilypad(): Px = Px(16, 16).also { g ->
        g.fill(CLEAR)
        val a = c("#4E8C52"); val hi = c("#63A664")
        for (y in 0 until 16) for (x in 0 until 16) {
            val dx = (x - 7.5f) / 7.5f
            val dy = (y - 7.5f) / 7.5f
            if (dx * dx + dy * dy > 1f) continue
            // the notch
            if (dx > 0f && kotlin.math.abs(dy) < 0.22f) continue
            g.set(x, y, if (dy < -0.1f) hi else a)
        }
    }


    /**
     * One sheet holding the four ground details: a grass tuft, a clump of
     * reeds, a fern and a lily pad. Scenery draws thousands of these, so they
     * share a texture and a mesh instead of costing a draw call each.
     */
    fun detailSheet(): Px = Px(32, 32).also { g ->
        g.fill(CLEAR)
        val cells = arrayOf(blade(), reed(), fern(), lilypad())
        for (q in 0 until 4) {
            val ox = (q % 2) * 16
            val oy = (q / 2) * 16
            val src = cells[q]
            for (y in 0 until 16) for (x in 0 until 16) {
                g.set(ox + x, oy + y, src.p[y * 16 + x])
            }
        }
    }

    /** Simple white square used to tint particles any colour. */
    fun dot(): Px = Px(4, 4).also { it.fill(c("#FFFFFF")) }

    /** Ripple ring for the fishing bobber and rain on the water. */
    fun ring(): Px = Px(16, 16).also { g ->
        g.fill(CLEAR)
        val w = c("#FFFFFF")
        for (a in 0 until 64) {
            val th = a / 64f * 6.2832f
            val x = (8 + kotlin.math.cos(th) * 6.5f).toInt()
            val y = (8 + kotlin.math.sin(th) * 6.5f).toInt()
            g.set(x, y, w)
        }
    }
}
