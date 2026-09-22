package com.lukas.jarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Black and silver. No hue anywhere except the three status colours, because a
// coloured accent is what makes an app look like a template: the moment
// everything is tinted blue, the tint is the design. Here the only bright thing
// on screen is light itself — white at a low alpha over black — so what stands
// out is whatever the user is actually doing.

/** True black. On an OLED panel these pixels are off, and glass has real depth. */
val Ink = Color(0xFF000000)

/** A hair above black, for the one surface that must not float: the tab bar. */
val InkRaised = Color(0xFF0B0B0D)

/** The fallback solid for surfaces that cannot use a brush. */
val InkCard = Color(0xFF141416)

/**
 * Glass, as layers of white over black rather than as a colour.
 *
 * Translucent white is what makes the material read: it picks up whatever is
 * behind it, so a panel over the background gradient is not the same shade at
 * the top of the screen as at the bottom, which is exactly how frosted glass
 * behaves.
 */
val GlassTop = Color(0x1FFFFFFF)
val GlassMid = Color(0x12FFFFFF)
val GlassBottom = Color(0x0AFFFFFF)

/** The lit top edge and the dim bottom one — the detail that sells the material. */
val GlassEdgeBright = Color(0x33FFFFFF)
val GlassEdgeDim = Color(0x14FFFFFF)

/** Hairline separators. Translucent, so they sit correctly on any surface. */
val Hairline = Color(0x1AFFFFFF)

/** Polished silver: the active state, and the only "bright" in the palette. */
val Accent = Color(0xFFE8EAED)

/** Brushed silver, a step back from Accent, for secondary marks. */
val AccentSoft = Color(0xFF9CA0A8)

// The places where colour earns its keep, kept at the restrained dark-appearance
// values rather than at full saturation. Caution is new: a budget three quarters
// spent is neither fine nor a failure, and drawing it in red said the wrong thing.
val Positive = Color(0xFF30D158)
val Caution = Color(0xFFFFD60A)
val Negative = Color(0xFFFF453A)

val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFFA1A1A6)
val TextFaint = Color(0xFF6E6E73)

/**
 * The spacing scale.
 *
 * Four values, used everywhere, instead of whatever number looked right in the
 * moment. Most of what reads as "cleaner" in a rebuild is not new drawing at
 * all — it is the same drawing with one gutter rather than five near-misses.
 */
object Space {
    val hair = 4.dp
    val tight = 8.dp
    val snug = 12.dp
    val step = 16.dp
    val gutter = 20.dp
    val loose = 28.dp
    val section = 36.dp
}

/** The corner radii, likewise picked from a scale rather than per call site. */
object Corner {
    val small = 12.dp
    val medium = 18.dp
    val large = 24.dp
    val card = 28.dp
}

/**
 * The page background.
 *
 * Not a flat black: a faint lift at the top gives every glass surface something
 * to pick up, so a panel near the top of the screen sits a shade brighter than
 * one near the bottom without either being told to.
 */
val PageBackground = Brush.verticalGradient(
    0f to Color(0xFF15151A),
    0.32f to Color(0xFF08080B),
    1f to Ink
)

private val JarvisColors = darkColorScheme(
    primary = Accent,
    // Silver is a light fill, so anything on top of it is black.
    onPrimary = Color(0xFF0A0A0C),
    primaryContainer = Color(0xFF2A2A2E),
    onPrimaryContainer = TextPrimary,
    secondary = AccentSoft,
    onSecondary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = InkRaised,
    onSurface = TextPrimary,
    surfaceVariant = InkCard,
    onSurfaceVariant = TextSecondary,
    outline = Hairline,
    outlineVariant = Hairline,
    error = Negative,
    onError = Color(0xFF0A0A0C)
)

// Sized and tracked the way a system typeface is: large text set tight and with
// weight, small text set loose. The scale has a proper top end now — a number on
// the dashboard is meant to be read across a room, and 34sp was the largest
// thing available for it as well as for a screen title.
private val JarvisType = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.4).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.9).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 27.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.2).sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.3).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.1).sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        // Enough tracking to keep small caps legible, not so much that it turns
        // into a science-fiction title card.
        letterSpacing = 1.1.sp
    )
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisColors,
        typography = JarvisType,
        content = content
    )
}
