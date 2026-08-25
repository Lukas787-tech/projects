package com.cozyhollow.riverside

import android.graphics.Color

/**
 * Everything colour. The world has a single ambient "mood" derived from the clock
 * and the weather; every scene layer asks [Sky] for its tint so the whole frame
 * stays harmonised instead of each object picking its own palette.
 */
object Pal {
    // --- UI / paper ---
    val paper = Color.parseColor("#FBF1DC")
    val paperDeep = Color.parseColor("#F2E2C2")
    val ink = Color.parseColor("#4A3A2C")
    val inkSoft = Color.parseColor("#7A6553")
    val wood = Color.parseColor("#B98A5A")
    val woodDark = Color.parseColor("#8C6440")
    val woodDeep = Color.parseColor("#6B4A2E")
    val gold = Color.parseColor("#E8B44A")
    val goldDeep = Color.parseColor("#C08A24")
    val leaf = Color.parseColor("#6FA45A")
    val leafDeep = Color.parseColor("#3F7A55")
    val berry = Color.parseColor("#D06A72")
    val sky = Color.parseColor("#8FC7E8")
    val cream = Color.parseColor("#FFF7E6")
    val shadow = Color.parseColor("#3A2A20")

    // --- terrain ---
    val grassTop = Color.parseColor("#8FC46A")
    val grassMid = Color.parseColor("#74AC57")
    val soil = Color.parseColor("#8A6242")
    val soilDark = Color.parseColor("#6A4830")
    val soilTilled = Color.parseColor("#5E3F2A")
    val stone = Color.parseColor("#A79C90")

    // --- water ---
    val waterDeep = Color.parseColor("#2E6E8E")
    val waterMid = Color.parseColor("#4B96B4")
    val waterTop = Color.parseColor("#7FC3D9")
    val foam = Color.parseColor("#EAF7FB")
}

/** A full-sky colour keyframe for one moment of the day. */
class SkyKey(
    val top: Int,
    val mid: Int,
    val horizon: Int,
    /** Colour multiplied over the whole world to set the mood. */
    val ambient: Int,
    /** Strength of that ambient wash, 0..1. */
    val ambientStrength: Float,
    val sunColor: Int,
    val sunGlow: Int,
    val starAlpha: Float,
    /** How much distant layers wash out toward the horizon (aerial perspective). */
    val haze: Float
)

object SkyKeys {
    // minutes-of-day anchors
    val nightDeep = SkyKey(
        top = Color.parseColor("#101A3A"),
        mid = Color.parseColor("#1E2E56"),
        horizon = Color.parseColor("#38466E"),
        ambient = Color.parseColor("#2A3C74"),
        ambientStrength = 0.52f,
        sunColor = Color.parseColor("#F2F0DC"),
        sunGlow = Color.parseColor("#9FB6E8"),
        starAlpha = 1f,
        haze = 0.30f
    )
    val dawn = SkyKey(
        top = Color.parseColor("#4E5C99"),
        mid = Color.parseColor("#B98BA6"),
        horizon = Color.parseColor("#F3C79A"),
        ambient = Color.parseColor("#9C7EA8"),
        ambientStrength = 0.30f,
        sunColor = Color.parseColor("#FFE3B0"),
        sunGlow = Color.parseColor("#F6B678"),
        starAlpha = 0.10f,
        haze = 0.33f
    )
    val morning = SkyKey(
        top = Color.parseColor("#79B7E4"),
        mid = Color.parseColor("#A8D5EE"),
        horizon = Color.parseColor("#E4F1E0"),
        ambient = Color.parseColor("#FFE9C4"),
        ambientStrength = 0.13f,
        sunColor = Color.parseColor("#FFF6D8"),
        sunGlow = Color.parseColor("#FFE9A8"),
        starAlpha = 0f,
        haze = 0.23f
    )
    val noon = SkyKey(
        top = Color.parseColor("#5FA9DF"),
        mid = Color.parseColor("#93CBEC"),
        horizon = Color.parseColor("#D7EDE4"),
        ambient = Color.parseColor("#FFFBEA"),
        ambientStrength = 0.06f,
        sunColor = Color.parseColor("#FFFBE6"),
        sunGlow = Color.parseColor("#FFF0B4"),
        starAlpha = 0f,
        haze = 0.17f
    )
    val golden = SkyKey(
        top = Color.parseColor("#6E8FC8"),
        mid = Color.parseColor("#E9A87C"),
        horizon = Color.parseColor("#F8D79A"),
        ambient = Color.parseColor("#FFC386"),
        ambientStrength = 0.26f,
        sunColor = Color.parseColor("#FFE2A6"),
        sunGlow = Color.parseColor("#FF9E5E"),
        starAlpha = 0f,
        haze = 0.30f
    )
    val dusk = SkyKey(
        top = Color.parseColor("#2E3A6B"),
        mid = Color.parseColor("#7A5A94"),
        horizon = Color.parseColor("#D98A70"),
        ambient = Color.parseColor("#7C6096"),
        ambientStrength = 0.40f,
        sunColor = Color.parseColor("#FFD8A8"),
        sunGlow = Color.parseColor("#E8724E"),
        starAlpha = 0.5f,
        haze = 0.32f
    )

    /** Interpolate the sky for a given minute of the day (0..1440). */
    fun at(minutes: Float, out: MutableSkyKey) {
        val m = minutes.coerceIn(0f, 1440f)
        when {
            m < 300f -> out.set(nightDeep, nightDeep, 0f)
            m < 390f -> out.set(nightDeep, dawn, U.smoothRange(m, 300f, 390f))
            m < 500f -> out.set(dawn, morning, U.smoothRange(m, 390f, 500f))
            m < 720f -> out.set(morning, noon, U.smoothRange(m, 500f, 720f))
            m < 1000f -> out.set(noon, morning, U.smoothRange(m, 720f, 1000f))
            m < 1110f -> out.set(morning, golden, U.smoothRange(m, 1000f, 1110f))
            m < 1200f -> out.set(golden, dusk, U.smoothRange(m, 1110f, 1200f))
            m < 1290f -> out.set(dusk, nightDeep, U.smoothRange(m, 1200f, 1290f))
            else -> out.set(nightDeep, nightDeep, 0f)
        }
    }
}

/** Mutable result holder so the render loop never allocates a SkyKey. */
class MutableSkyKey {
    var top = 0; var mid = 0; var horizon = 0
    var ambient = 0; var ambientStrength = 0f
    var sunColor = 0; var sunGlow = 0
    var starAlpha = 0f; var haze = 0f

    fun set(a: SkyKey, b: SkyKey, t: Float) {
        top = U.lerpColor(a.top, b.top, t)
        mid = U.lerpColor(a.mid, b.mid, t)
        horizon = U.lerpColor(a.horizon, b.horizon, t)
        ambient = U.lerpColor(a.ambient, b.ambient, t)
        ambientStrength = U.lerp(a.ambientStrength, b.ambientStrength, t)
        sunColor = U.lerpColor(a.sunColor, b.sunColor, t)
        sunGlow = U.lerpColor(a.sunGlow, b.sunGlow, t)
        starAlpha = U.lerp(a.starAlpha, b.starAlpha, t)
        haze = U.lerp(a.haze, b.haze, t)
    }

    /** Apply weather on top of the time-of-day mood. */
    fun applyWeather(weather: Int, wetness: Float) {
        if (weather == Weather.CLEAR) return
        val grey = Color.parseColor("#7C8794")
        val amount = if (weather == Weather.RAIN) 0.55f * wetness else 0.28f * wetness
        top = U.lerpColor(top, U.shade(grey, 0.72f), amount)
        mid = U.lerpColor(mid, grey, amount)
        horizon = U.lerpColor(horizon, U.shade(grey, 1.12f), amount * 0.8f)
        ambient = U.lerpColor(ambient, Color.parseColor("#8895A6"), amount)
        ambientStrength = U.lerp(ambientStrength, 0.30f, amount * 0.75f)
        haze = U.lerp(haze, 0.62f, amount)
        starAlpha *= (1f - amount)
    }

    /** Tint a scenery colour by distance so far layers melt into the sky. */
    fun aerial(color: Int, distance: Float): Int {
        val washed = U.lerpColor(color, horizon, haze * distance)
        // the global wash in Scene.drawAmbient already tints everything once,
        // so keep this second pass gentle or the whole frame goes grey
        return U.lerpColor(washed, ambient, ambientStrength * 0.6f * (1f - distance * 0.35f))
    }
}

object Weather {
    const val CLEAR = 0
    const val CLOUDY = 1
    const val RAIN = 2

    fun name(w: Int): String = when (w) {
        RAIN -> "Rainy"
        CLOUDY -> "Cloudy"
        else -> "Clear"
    }

    fun roll(day: Int): Int {
        val r = U.hash(day * 977 + 13)
        return when {
            r < 0.22f -> RAIN
            r < 0.45f -> CLOUDY
            else -> CLEAR
        }
    }
}
