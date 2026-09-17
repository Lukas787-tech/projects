package com.lukas.jarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Black and silver. No hue anywhere except the two status colours, because a
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

// The two places where colour earns its keep, kept at the restrained
// dark-appearance values rather than at full saturation.
val Positive = Color(0xFF30D158)
val Negative = Color(0xFFFF453A)

val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFFA1A1A6)
val TextFaint = Color(0xFF6E6E73)

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
// weight, small text set loose. The old scale ran everything at Light, which
// reads as thin rather than as refined once the screen is this dark.
private val JarvisType = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.4).sp
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
        lineHeight = 23.sp,
        letterSpacing = (-0.3).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        // Enough tracking to keep small caps legible, not so much that it turns
        // into a science-fiction title card.
        letterSpacing = 0.8.sp
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
