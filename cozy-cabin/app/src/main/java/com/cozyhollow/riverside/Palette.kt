package com.cozyhollow.riverside

import android.graphics.Color

/**
 * Everything colour.
 *
 * The hollow is deep in winter, and the whole game is graded for it. Daylight
 * is short, low and blue; the sun never climbs far, so the snow spends most of
 * the day in that long lilac shadow you only get in January. Against all that
 * cold, every warm thing in the world — a window, a lantern, a fire, a mug —
 * is the only source of orange on screen, which is what makes it read as cosy
 * rather than merely pretty.
 */
object Pal {
    // --- UI / paper ---
    val paper = Color.parseColor("#F6EFE4")
    val paperDeep = Color.parseColor("#E6DBC9")
    val ink = Color.parseColor("#3C3A45")
    val inkSoft = Color.parseColor("#6E6B7C")
    val wood = Color.parseColor("#9A7351")
    val woodDark = Color.parseColor("#6E5039")
    val woodDeep = Color.parseColor("#3A3348")
    val gold = Color.parseColor("#F0B45E")
    val goldDeep = Color.parseColor("#C4832E")
    val ember = Color.parseColor("#F08340")
    val emberDeep = Color.parseColor("#C2542A")
    val pine = Color.parseColor("#3E6B5A")
    val pineDeep = Color.parseColor("#274C42")
    val berry = Color.parseColor("#C85E6E")
    val sky = Color.parseColor("#9FBEE0")
    val cream = Color.parseColor("#FFF6E8")
    val shadow = Color.parseColor("#1A1E34")
    val frost = Color.parseColor("#CFE2F2")
    val frostDeep = Color.parseColor("#7C9CC2")

    // --- kept under the old names so the rest of the game reads the same ---
    val leaf = pine
    val leafDeep = pineDeep

    // --- terrain ---
    val snowTop = Color.parseColor("#E8EEF8")
    val snowMid = Color.parseColor("#C9D6EA")
    val snowShade = Color.parseColor("#A9BBD8")
    val soil = Color.parseColor("#6A5A4E")
    val soilDark = Color.parseColor("#4E4238")
    val stone = Color.parseColor("#8E93A2")
    val woodDeepGrain = Color.parseColor("#4A3A2E")

    // --- ice ---
    val iceDeep = Color.parseColor("#3E6B92")
    val iceMid = Color.parseColor("#6E9CBE")
    val iceTop = Color.parseColor("#A8CBE0")
    val iceCrack = Color.parseColor("#E4F2FB")

    /** Warm light colours, shared by every lamp, fire and window in the world. */
    val lampWarm = Color.parseColor("#FFB25C")
    val fireWarm = Color.parseColor("#FF8A3C")
    val windowWarm = Color.parseColor("#FFA83E")
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
    // Winter clock. The sun clears the ridge around 7:40 and is gone by 16:40,
    // so most of a day is either blue hour or dark — which is the whole point.
    val nightDeep = SkyKey(
        top = Color.parseColor("#080D24"),
        mid = Color.parseColor("#121B3E"),
        horizon = Color.parseColor("#243358"),
        ambient = Color.parseColor("#2A3A6E"),
        ambientStrength = 0.55f,
        sunColor = Color.parseColor("#EAF0FF"),
        sunGlow = Color.parseColor("#8FA6D8"),
        starAlpha = 1f,
        haze = 0.26f
    )
    val blueHour = SkyKey(
        top = Color.parseColor("#16224C"),
        mid = Color.parseColor("#34477E"),
        horizon = Color.parseColor("#6E7EA8"),
        ambient = Color.parseColor("#4A5C94"),
        ambientStrength = 0.46f,
        sunColor = Color.parseColor("#F6E6D2"),
        sunGlow = Color.parseColor("#A08CB4"),
        starAlpha = 0.42f,
        haze = 0.34f
    )
    val dawn = SkyKey(
        top = Color.parseColor("#31447E"),
        mid = Color.parseColor("#7E7FA8"),
        horizon = Color.parseColor("#E8B892"),
        ambient = Color.parseColor("#8C7EA4"),
        ambientStrength = 0.32f,
        sunColor = Color.parseColor("#FFDCB0"),
        sunGlow = Color.parseColor("#F0A874"),
        starAlpha = 0.08f,
        haze = 0.36f
    )
    val morning = SkyKey(
        top = Color.parseColor("#5A87C4"),
        mid = Color.parseColor("#93B4DA"),
        horizon = Color.parseColor("#DCE6F0"),
        ambient = Color.parseColor("#D6E2F4"),
        ambientStrength = 0.18f,
        sunColor = Color.parseColor("#FFF4E0"),
        sunGlow = Color.parseColor("#FFE2B8"),
        starAlpha = 0f,
        haze = 0.30f
    )
    val noon = SkyKey(
        top = Color.parseColor("#4C7CBE"),
        mid = Color.parseColor("#8CAFD8"),
        horizon = Color.parseColor("#E2EAF2"),
        ambient = Color.parseColor("#EAF1FA"),
        ambientStrength = 0.12f,
        sunColor = Color.parseColor("#FFFAEC"),
        sunGlow = Color.parseColor("#FFECC8"),
        starAlpha = 0f,
        haze = 0.24f
    )
    val golden = SkyKey(
        top = Color.parseColor("#3E5A9C"),
        mid = Color.parseColor("#9E8CB4"),
        horizon = Color.parseColor("#F2C08E"),
        ambient = Color.parseColor("#E8B48C"),
        ambientStrength = 0.28f,
        sunColor = Color.parseColor("#FFE0AC"),
        sunGlow = Color.parseColor("#FF9C5E"),
        starAlpha = 0f,
        haze = 0.34f
    )
    val dusk = SkyKey(
        top = Color.parseColor("#141F4A"),
        mid = Color.parseColor("#3A4A80"),
        horizon = Color.parseColor("#96768E"),
        ambient = Color.parseColor("#4E5A92"),
        ambientStrength = 0.46f,
        sunColor = Color.parseColor("#FFD2A4"),
        sunGlow = Color.parseColor("#D0705E"),
        starAlpha = 0.55f,
        haze = 0.34f
    )

    /** Interpolate the sky for a given minute of the day (0..1440). */
    fun at(minutes: Float, out: MutableSkyKey) {
        val m = minutes.coerceIn(0f, 1440f)
        when {
            m < 380f -> out.set(nightDeep, nightDeep, 0f)
            m < 440f -> out.set(nightDeep, blueHour, U.smoothRange(m, 380f, 440f))
            m < 500f -> out.set(blueHour, dawn, U.smoothRange(m, 440f, 500f))
            m < 600f -> out.set(dawn, morning, U.smoothRange(m, 500f, 600f))
            m < 750f -> out.set(morning, noon, U.smoothRange(m, 600f, 750f))
            m < 880f -> out.set(noon, morning, U.smoothRange(m, 750f, 880f))
            m < 970f -> out.set(morning, golden, U.smoothRange(m, 880f, 970f))
            m < 1030f -> out.set(golden, dusk, U.smoothRange(m, 970f, 1030f))
            m < 1090f -> out.set(dusk, blueHour, U.smoothRange(m, 1030f, 1090f))
            m < 1170f -> out.set(blueHour, nightDeep, U.smoothRange(m, 1090f, 1170f))
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

    /**
     * Apply weather on top of the time-of-day mood.
     *
     * Snow does not grey the sky out the way rain does — it *lifts* it. The
     * cloud deck bounces the ground light straight back down, so an overcast
     * snowy afternoon is paler and flatter than a clear one, not darker.
     */
    fun applyWeather(weather: Int, wetness: Float) {
        if (weather == Weather.CLEAR) return
        val pale = Color.parseColor("#B4C2D6")
        val amount = when (weather) {
            Weather.BLIZZARD -> 0.68f
            Weather.SNOW -> 0.46f
            else -> 0.30f
        } * wetness
        top = U.lerpColor(top, U.shade(pale, 0.78f), amount)
        mid = U.lerpColor(mid, pale, amount)
        horizon = U.lerpColor(horizon, U.shade(pale, 1.10f), amount * 0.9f)
        ambient = U.lerpColor(ambient, Color.parseColor("#C2CEE0"), amount)
        ambientStrength = U.lerp(ambientStrength, 0.34f, amount * 0.8f)
        haze = U.lerp(haze, if (weather == Weather.BLIZZARD) 0.85f else 0.60f, amount)
        starAlpha *= (1f - amount)
    }

    /** Tint a scenery colour by distance so far layers melt into the sky. */
    fun aerial(color: Int, distance: Float): Int {
        val washed = U.lerpColor(color, horizon, haze * distance)
        return U.lerpColor(washed, ambient, ambientStrength * 0.6f * (1f - distance * 0.35f))
    }
}

object Weather {
    const val CLEAR = 0
    const val OVERCAST = 1
    const val SNOW = 2
    const val BLIZZARD = 3

    fun name(w: Int): String = when (w) {
        BLIZZARD -> "Blizzard"
        SNOW -> "Snowfall"
        OVERCAST -> "Overcast"
        else -> "Crisp & Clear"
    }

    /** A one-line read on the day, shown under the clock. */
    fun blurb(w: Int): String = when (w) {
        BLIZZARD -> "Wild out there. Keep the stove fed."
        SNOW -> "Big soft flakes, no wind at all."
        OVERCAST -> "Low grey sky, hushed and still."
        else -> "Cold, bright, and very quiet."
    }

    /** How fast the cold bites, per weather. */
    fun chill(w: Int): Float = when (w) {
        BLIZZARD -> 2.1f
        SNOW -> 1.25f
        OVERCAST -> 1.0f
        else -> 1.35f      // clear nights are the coldest of all
    }

    fun roll(day: Int): Int {
        val r = U.hash(day * 977 + 13)
        return when {
            r < 0.10f -> BLIZZARD
            r < 0.44f -> SNOW
            r < 0.66f -> OVERCAST
            else -> CLEAR
        }
    }
}
