package com.cozyhollow.riverside.gl

import android.graphics.Color

/** All GL textures, painted at startup by [PixelTex]. */
class Textures {

    private fun up(px: Px, repeat: Boolean = true, mip: Boolean = false) =
        Gl.texture(px.p, px.w, px.h, repeat, mip)

    /** Anything that tiles over metres of ground gets a mip chain. */
    private fun land(px: Px) = up(px, repeat = true, mip = true)

    val ground = land(PixelTex.ground())
    val grass = land(PixelTex.grass())
    val soil = land(PixelTex.soil())
    val tilled = land(PixelTex.soilTilled())
    val tilledWet = land(PixelTex.soilWet())
    val sand = land(PixelTex.sand())
    val rock = land(PixelTex.rock())
    val planks = land(PixelTex.planks())
    val plankWorn = land(PixelTex.plankWorn())
    val logs = up(PixelTex.logs())
    val thatch = land(PixelTex.thatch())
    val shingleRed = up(PixelTex.shingles(PixelTex.roofA, PixelTex.roofB, PixelTex.roofC))
    val shinglePlum = up(PixelTex.shingles(PixelTex.roofDarkA, PixelTex.roofDarkB, Color.parseColor("#7E5675")))
    val stone = land(PixelTex.stone())
    val water = up(PixelTex.water(), repeat = true, mip = true)
    val bark = up(PixelTex.bark())
    val barkBirch = up(PixelTex.barkBirch())
    val leaf = land(PixelTex.leafPlain())
    val pine = up(PixelTex.pineNeedles())
    val oak = up(PixelTex.oakLeaves())
    val window = up(PixelTex.window(), repeat = false)
    val windowLit = up(PixelTex.windowLit(), repeat = false)
    val door = up(PixelTex.door(), repeat = false)
    val awning = up(PixelTex.awning())
    val crate = up(PixelTex.crate())
    val lantern = up(PixelTex.lanternGlass())
    val shirt = up(PixelTex.cloth(Color.parseColor("#6F9FD8"), Color.parseColor("#5A85BC")))
    val denim = up(PixelTex.cloth(Color.parseColor("#7C6A56"), Color.parseColor("#6A5A48")))
    val scarf = up(PixelTex.cloth(Color.parseColor("#D0707A"), Color.parseColor("#B45C66")))
    val skin = up(PixelTex.skin())
    val hair = up(PixelTex.cloth(Color.parseColor("#6B4A32"), Color.parseColor("#573B28")))
    val straw = up(PixelTex.straw())
    val boot = up(PixelTex.cloth(Color.parseColor("#5A4634"), Color.parseColor("#48372A")))
    val foxFur = up(PixelTex.fur(Color.parseColor("#DE8B52"), Color.parseColor("#C2743F")))
    val foxCream = up(PixelTex.fur(Color.parseColor("#F6E6CE"), Color.parseColor("#E2CFB2")))
    val metal = up(PixelTex.metal())
    val leafGreen = up(PixelTex.solid(Color.parseColor("#6FA45A"), Color.parseColor("#5C9049")))
    val white = up(PixelTex.solid(Color.WHITE), true)

    val detail = up(PixelSprites.detailSheet(), repeat = false)
    val blade = up(PixelSprites.blade(), repeat = false)
    val flowers = up(PixelSprites.flowers(), repeat = false)
    val reed = up(PixelSprites.reed(), repeat = false)
    val fern = up(PixelSprites.fern(), repeat = false)
    val lilypad = up(PixelSprites.lilypad(), repeat = false)
    val shadow = up(PixelSprites.softShadow(), repeat = false)
    val glow = up(PixelSprites.glow(), repeat = false)
    val cloud = up(PixelSprites.cloud(), repeat = false)
    val face = up(PixelSprites.face(), repeat = false)
    val foxFace = up(PixelSprites.foxFace(), repeat = false)
    val dot = up(PixelSprites.dot(), repeat = false)
    val ring = up(PixelSprites.ring(), repeat = false)

    /** Produce colours get their own tiny solid textures, made on demand. */
    private val cropTex = HashMap<Int, Int>()

    fun solid(color: Int): Int = cropTex.getOrPut(color) {
        up(PixelTex.solid(color, darken(color)))
    }

    private fun darken(c: Int): Int = Color.rgb(
        (Color.red(c) * 0.82f).toInt(), (Color.green(c) * 0.82f).toInt(), (Color.blue(c) * 0.82f).toInt()
    )
}
