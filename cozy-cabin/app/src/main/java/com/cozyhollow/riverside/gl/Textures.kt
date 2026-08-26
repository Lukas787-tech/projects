package com.cozyhollow.riverside.gl

import android.graphics.Color

/**
 * All GL textures, painted at startup by [PixelTex].
 *
 * Everything is sampled with linear filtering now. The summer game magnified
 * its 32x32 tiles with nearest-neighbour on purpose, so every texel landed as
 * a hard pixel; winter wants the opposite — soft, matte, flat-shaded surfaces
 * where the light does the describing and the texture only breaks up the
 * flatness. Same tiles, different filter, completely different game.
 */
class Textures {

    private fun up(px: Px, repeat: Boolean = true, mip: Boolean = false, smooth: Boolean = true) =
        Gl.texture(px.p, px.w, px.h, repeat, mip, smooth)

    /** Anything that tiles over metres of ground gets a mip chain. */
    private fun land(px: Px) = up(px, repeat = true, mip = true)

    val snow = land(PixelTex.snow())
    val snowPack = land(PixelTex.snowPack())
    val ice = land(PixelTex.ice())
    val soil = land(PixelTex.soil())
    val tilled = land(PixelTex.soilTilled())
    val tilledWet = land(PixelTex.soilWet())
    val shingle = land(PixelTex.shingle())
    val rock = land(PixelTex.rock())
    val planks = land(PixelTex.planks())
    val plankWorn = land(PixelTex.plankWorn())
    val logs = land(PixelTex.logs())
    val roofDark = up(PixelTex.shingles(PixelTex.roofA, PixelTex.roofB, PixelTex.roofC))
    val roofSnow = land(PixelTex.roofSnow())
    val stone = land(PixelTex.stone())
    val bark = up(PixelTex.bark())
    val barkBirch = up(PixelTex.barkBirch())
    val needles = land(PixelTex.leafPlain())
    val pine = up(PixelTex.pineNeedles())
    val deadGrass = land(PixelTex.deadGrass())
    val window = up(PixelTex.window(), repeat = false)
    val windowLit = up(PixelTex.windowLit(), repeat = false)
    val glass = up(PixelTex.glass())
    val door = up(PixelTex.door(), repeat = false)
    val awning = up(PixelTex.awning())
    val crate = up(PixelTex.crate())
    val lantern = up(PixelTex.lanternGlass())
    val truckPaint = up(PixelTex.truckPaint())
    val metal = up(PixelTex.metal())
    val rusty = up(PixelTex.rustyMetal())
    val straw = up(PixelTex.straw())

    // ---- what the player is wearing ----
    val coat = up(PixelTex.cloth(Color.parseColor("#3E5A78"), Color.parseColor("#33495F")))
    val trousers = up(PixelTex.cloth(Color.parseColor("#4E4638"), Color.parseColor("#403A2E")))
    val scarf = up(PixelTex.knit(Color.parseColor("#C05060"), Color.parseColor("#A03E4E")))
    val hat = up(PixelTex.knit(Color.parseColor("#C8B48E"), Color.parseColor("#AC9A76")))
    val mitten = up(PixelTex.knit(Color.parseColor("#C05060"), Color.parseColor("#A03E4E")))
    val knitQuilt = up(PixelTex.knit(Color.parseColor("#B4707E"), Color.parseColor("#8A5464")))
    val skin = up(PixelTex.skin())
    val hair = up(PixelTex.cloth(Color.parseColor("#5A4030"), Color.parseColor("#463226")))
    val boot = up(PixelTex.cloth(Color.parseColor("#3E3830"), Color.parseColor("#302A24")))

    // ---- animals ----
    val foxFur = up(PixelTex.fur(Color.parseColor("#D8834A"), Color.parseColor("#BC6C39")))
    val foxCream = up(PixelTex.fur(Color.parseColor("#F2E4CE"), Color.parseColor("#DCCAAE")))
    val catFur = up(PixelTex.fur(Color.parseColor("#6E6A74"), Color.parseColor("#585460")))
    val catCream = up(PixelTex.fur(Color.parseColor("#E4DCD2"), Color.parseColor("#CCC4BA")))
    val deerFur = up(PixelTex.fur(Color.parseColor("#9A7A5E"), Color.parseColor("#7E6148")))
    val birdBlue = up(PixelTex.fur(Color.parseColor("#5E7EA8"), Color.parseColor("#4A6688")))

    val leafGreen = up(PixelTex.solid(Color.parseColor("#4E7A56"), Color.parseColor("#3E6446")))
    val white = up(PixelTex.solid(Color.WHITE), true)

    // ---- sprites ----
    val detail = up(PixelSprites.detailSheet(), repeat = false)
    val flowers = up(PixelSprites.flowers(), repeat = false)
    val shadow = up(PixelSprites.softShadow(), repeat = false)
    val glow = up(PixelSprites.glow(), repeat = false)
    val cloud = up(PixelSprites.cloud(), repeat = false)
    val flake = up(PixelSprites.flake(), repeat = false)
    val face = up(PixelSprites.face(), repeat = false)
    val foxFace = up(PixelSprites.foxFace(), repeat = false)
    val catFace = up(PixelSprites.catFace(), repeat = false)
    val dot = up(PixelSprites.dot(), repeat = false)
    val ring = up(PixelSprites.ring(), repeat = false)
    val boot9 = up(PixelSprites.bootPrint(), repeat = false)

    /** Produce colours get their own tiny solid textures, made on demand. */
    private val cropTex = HashMap<Int, Int>()

    fun solid(color: Int): Int = cropTex.getOrPut(color) {
        up(PixelTex.solid(color, darken(color)))
    }

    private fun darken(c: Int): Int = Color.rgb(
        (Color.red(c) * 0.82f).toInt(), (Color.green(c) * 0.82f).toInt(), (Color.blue(c) * 0.82f).toInt()
    )
}
