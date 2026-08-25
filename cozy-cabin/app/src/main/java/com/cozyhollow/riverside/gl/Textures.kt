package com.cozyhollow.riverside.gl

import android.graphics.Color

/** All GL textures, painted at startup by [PixelTex]. */
class Textures {

    private fun up(px: Px, repeat: Boolean = true) = Gl.texture(px.p, px.w, px.h, repeat)

    val grass = up(PixelTex.grass())
    val grassDry = up(PixelTex.grassDry())
    val soil = up(PixelTex.soil())
    val tilled = up(PixelTex.soilTilled())
    val tilledWet = up(PixelTex.soilWet())
    val sand = up(PixelTex.sand())
    val planks = up(PixelTex.planks())
    val logs = up(PixelTex.logs())
    val shingleRed = up(PixelTex.shingles(PixelTex.roofA, PixelTex.roofB, PixelTex.roofC))
    val shinglePlum = up(PixelTex.shingles(PixelTex.roofDarkA, PixelTex.roofDarkB, Color.parseColor("#7E5675")))
    val stone = up(PixelTex.stone())
    val water = up(PixelTex.water())
    val bark = up(PixelTex.bark())
    val pine = up(PixelTex.pineNeedles())
    val oak = up(PixelTex.oakLeaves())
    val window = up(PixelTex.window(), repeat = false)
    val windowLit = up(PixelTex.windowLit(), repeat = false)
    val door = up(PixelTex.door(), repeat = false)
    val awning = up(PixelTex.awning())
    val crate = up(PixelTex.crate())
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

    val blade = up(PixelSprites.blade(), repeat = false)
    val flowers = up(PixelSprites.flowers(), repeat = false)
    val shadow = up(PixelSprites.shadow(), repeat = false)
    val cloud = up(PixelSprites.cloud(), repeat = false)
    val face = up(PixelSprites.face(), repeat = false)
    val foxFace = up(PixelSprites.foxFace(), repeat = false)
    val dot = up(PixelSprites.dot(), repeat = false)
    val ring = up(PixelSprites.ring(), repeat = false)

    /** Crop produce colours get their own tiny solid textures. */
    private val cropTex = HashMap<Int, Int>()

    fun solid(color: Int): Int = cropTex.getOrPut(color) {
        up(PixelTex.solid(color, darken(color)))
    }

    private fun darken(c: Int): Int = Color.rgb(
        (Color.red(c) * 0.82f).toInt(), (Color.green(c) * 0.82f).toInt(), (Color.blue(c) * 0.82f).toInt()
    )
}
