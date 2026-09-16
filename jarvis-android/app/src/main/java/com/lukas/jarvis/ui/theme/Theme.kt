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

// One dark palette, tuned so the glow reads on an OLED panel without smearing.
val Ink = Color(0xFF05060A)
val InkRaised = Color(0xFF0B0E15)
val InkCard = Color(0xFF11151E)
val Hairline = Color(0xFF1E2431)
val Accent = Color(0xFF4EA8FF)
val AccentSoft = Color(0xFF7C5CFF)
val Positive = Color(0xFF3ED598)
val Negative = Color(0xFFFF6B6B)
val TextPrimary = Color(0xFFEDF1F7)
val TextSecondary = Color(0xFF8B95A7)
val TextFaint = Color(0xFF5A6474)

private val JarvisColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    primaryContainer = Color(0xFF15304D),
    onPrimaryContainer = Color(0xFFCCE4FF),
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
    onError = Ink
)

private val JarvisType = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        // Wide tracking on the tiny status labels keeps them legible at a glance.
        letterSpacing = 1.4.sp
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
