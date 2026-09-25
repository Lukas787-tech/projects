package com.lukas.jarvis.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A heads-up display rather than a template. Almost everything is dark glass;
// the one colour is the accent — the light of the reactor — and it is the
// user's to choose. Every surface picks up a trace of it, so a violet Jarvis is
// violet all the way through rather than a grey app with violet buttons.
//
// The palette is read through getters backed by snapshot state, so every
// screen that draws with `Accent` or `TextPrimary` recomposes the moment the
// user picks a different colour, without each call site knowing themes exist.

/** One accent: the reactor's light, and the two shades gradients are built from. */
data class AccentTone(
    val id: String,
    val label: String,
    val main: Color,
    /** Lighter, for highlights and the lit edge of glass. */
    val bright: Color,
    /** Darker, for the far end of gradients and pressed states. */
    val deep: Color
)

/** What the accent is drawn over. */
data class Backdrop(
    val id: String,
    val label: String,
    val top: Color,
    val middle: Color,
    val bottom: Color,
    /** The one surface that must not float, like the tab bar. */
    val raised: Color,
    val card: Color,
    val dialog: Color
)

object Palettes {

    val accents: List<AccentTone> = listOf(
        AccentTone("arc", "Arc reactor", Color(0xFF4FD8FF), Color(0xFFB5F0FF), Color(0xFF0A7FB0)),
        AccentTone("stark", "Stark gold", Color(0xFFFFC24B), Color(0xFFFFE6A8), Color(0xFFB0711A)),
        AccentTone("crimson", "Mark III red", Color(0xFFFF4D5E), Color(0xFFFFB0B8), Color(0xFFA3162A)),
        AccentTone("emerald", "Emerald", Color(0xFF3DDC97), Color(0xFFB3F5D8), Color(0xFF16875A)),
        AccentTone("violet", "Vision violet", Color(0xFFA88BFF), Color(0xFFDCD0FF), Color(0xFF5B3FC4)),
        AccentTone("solar", "Solar", Color(0xFFFF8A3D), Color(0xFFFFC9A3), Color(0xFFB04A0E)),
        AccentTone("rose", "Rose", Color(0xFFFF7AB6), Color(0xFFFFC6E0), Color(0xFFB02D6C)),
        AccentTone("silver", "Silver", Color(0xFFE8EAED), Color(0xFFFFFFFF), Color(0xFF8A8E96))
    )

    val backdrops: List<Backdrop> = listOf(
        Backdrop(
            "space", "Deep space",
            top = Color(0xFF0B1422), middle = Color(0xFF05080F), bottom = Color(0xFF000000),
            raised = Color(0xFF070B12), card = Color(0xFF0F1622), dialog = Color(0xFF111925)
        ),
        Backdrop(
            "oled", "Pure black",
            top = Color(0xFF000000), middle = Color(0xFF000000), bottom = Color(0xFF000000),
            raised = Color(0xFF050505), card = Color(0xFF101012), dialog = Color(0xFF141416)
        ),
        Backdrop(
            "graphite", "Graphite",
            top = Color(0xFF1C1D22), middle = Color(0xFF111215), bottom = Color(0xFF08080A),
            raised = Color(0xFF0E0F12), card = Color(0xFF18191D), dialog = Color(0xFF1B1C21)
        ),
        Backdrop(
            "midnight", "Midnight",
            top = Color(0xFF151A3A), middle = Color(0xFF0A0D22), bottom = Color(0xFF03040C),
            raised = Color(0xFF080B1C), card = Color(0xFF121733), dialog = Color(0xFF151A38)
        ),
        Backdrop(
            "nebula", "Nebula",
            top = Color(0xFF231433), middle = Color(0xFF0E0918), bottom = Color(0xFF030206),
            raised = Color(0xFF0C0814), card = Color(0xFF1A1226), dialog = Color(0xFF1D142B)
        ),
        Backdrop(
            "aurora", "Aurora",
            top = Color(0xFF0B2A2A), middle = Color(0xFF061416), bottom = Color(0xFF010404),
            raised = Color(0xFF051012), card = Color(0xFF0E1E20), dialog = Color(0xFF112325)
        ),
        Backdrop(
            "ember", "Ember",
            top = Color(0xFF2A130C), middle = Color(0xFF140907), bottom = Color(0xFF040201),
            raised = Color(0xFF100706), card = Color(0xFF1F1210), dialog = Color(0xFF241512)
        ),
        Backdrop(
            "abyss", "Abyss",
            top = Color(0xFF062238), middle = Color(0xFF03101C), bottom = Color(0xFF000307),
            raised = Color(0xFF020C15), card = Color(0xFF0B1A28), dialog = Color(0xFF0E1F2E)
        )
    )

    /**
     * Any hue the user picks, stored as "custom:210", in the same three shades
     * the presets have: the light itself, a pale highlight, a deep shadow.
     */
    fun custom(hue: Float): AccentTone {
        val h = ((hue % 360f) + 360f) % 360f
        return AccentTone(
            "custom:${h.toInt()}",
            "Your own",
            Color.hsv(h, 0.68f, 1f),
            Color.hsv(h, 0.28f, 1f),
            Color.hsv(h, 0.88f, 0.68f)
        )
    }

    fun customHue(id: String): Float? =
        if (id.startsWith("custom:")) id.removePrefix("custom:").toFloatOrNull() else null

    fun accent(id: String): AccentTone =
        customHue(id)?.let { custom(it) } ?: accents.firstOrNull { it.id == id } ?: accents.first()
    fun backdrop(id: String): Backdrop = backdrops.firstOrNull { it.id == id } ?: backdrops.first()
}

/**
 * The live look of the app. Written from settings, read everywhere.
 *
 * Kept as plain snapshot state rather than a CompositionLocal so the dozens of
 * top-level colour names below keep working unchanged: reading one inside a
 * composable subscribes that composable to the theme like any other state.
 */
object ThemeState {
    var accent: AccentTone by mutableStateOf(Palettes.accents.first())
    var backdrop: Backdrop by mutableStateOf(Palettes.backdrops.first())

    /** 0.85 .. 1.3 — text size on top of the system's own setting. */
    var textScale: Float by mutableStateOf(1f)

    /** Fewer moving parts, for battery and for anyone who finds motion tiring. */
    var reduceMotion: Boolean by mutableStateOf(false)

    fun apply(accentId: String, backdropId: String, scale: Float, calm: Boolean) {
        val nextAccent = Palettes.accent(accentId)
        val nextBackdrop = Palettes.backdrop(backdropId)
        if (nextAccent != accent) accent = nextAccent
        if (nextBackdrop != backdrop) backdrop = nextBackdrop
        val clamped = scale.coerceIn(0.85f, 1.3f)
        if (clamped != textScale) textScale = clamped
        if (calm != reduceMotion) reduceMotion = calm
    }
}

/** True black, for text and marks drawn on top of the accent. */
val Ink: Color get() = Color(0xFF000000)

/** The text colour on a filled accent surface. */
val OnAccent: Color get() = Color(0xFF02060C)

/** A hair above the page, for the one surface that must not float: the tab bar. */
val InkRaised: Color get() = ThemeState.backdrop.raised

/** The fallback solid for surfaces that cannot use a brush. */
val InkCard: Color get() = ThemeState.backdrop.card

/**
 * Glass, as layers of light over dark rather than as a colour.
 *
 * A trace of the accent is mixed into the film, so panels read as lit by the
 * reactor rather than as grey rectangles laid on top of it.
 */
val GlassTop: Color get() = tinted(0.12f)
val GlassMid: Color get() = tinted(0.07f)
val GlassBottom: Color get() = tinted(0.04f)

/** The lit top edge and the dim bottom one — the detail that sells the material. */
val GlassEdgeBright: Color get() = lerp(Color.White, ThemeState.accent.main, 0.45f).copy(alpha = 0.26f)
val GlassEdgeDim: Color get() = Color.White.copy(alpha = 0.06f)

/** Hairline separators. Translucent, so they sit correctly on any surface. */
val Hairline: Color get() = lerp(Color.White, ThemeState.accent.main, 0.25f).copy(alpha = 0.11f)

private fun tinted(alpha: Float): Color =
    lerp(Color.White, ThemeState.accent.main, 0.18f).copy(alpha = alpha)

/** The films every flat fill is made of, from barely there to lit. */
object Film {
    val faint: Color get() = tinted(0.04f)
    val resting: Color get() = tinted(0.07f)
    val lifted: Color get() = tinted(0.10f)
    val selected: Color get() = ThemeState.accent.main.copy(alpha = 0.16f)
}

/** The one opaque pane: dialogs, which float over everything and must not show through. */
val DialogPane: Color get() = ThemeState.backdrop.dialog

/** The reactor's light: the active state, and the only colour in the palette. */
val Accent: Color get() = ThemeState.accent.main

/** A step back from Accent, for secondary marks. */
val AccentSoft: Color get() = lerp(ThemeState.accent.main, Color(0xFF8A919C), 0.45f)

/** The bright core of the accent, for highlights. */
val AccentBright: Color get() = ThemeState.accent.bright

/** The deep end of the accent, for gradients. */
val AccentDeep: Color get() = ThemeState.accent.deep

/** A soft glow of the accent, for halos behind lit things. */
val AccentGlow: Color get() = ThemeState.accent.main.copy(alpha = 0.22f)

// The places where colour earns its keep beyond the accent, kept at restrained
// dark-appearance values rather than at full saturation.
val Positive: Color get() = Color(0xFF30D158)
val Caution: Color get() = Color(0xFFFFD60A)
val Negative: Color get() = Color(0xFFFF453A)

val TextPrimary: Color get() = Color(0xFFF2F5F9)
val TextSecondary: Color get() = Color(0xFFA3AAB5)
val TextFaint: Color get() = Color(0xFF6B7280)

/**
 * The spacing scale. A handful of values used everywhere, instead of whatever
 * number looked right in the moment.
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

/**
 * How things move. Three durations and one spring, so a segment, the bar and a
 * chip all settle with one feel instead of three slightly different ones.
 */
object Motion {
    const val quick = 160
    const val standard = 280
    const val slow = 520

    /** Settles without overshoot: a switch that bounces reads as a toy. */
    fun <T> glide(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}

/** The corner radii, picked from a scale rather than per call site. */
object Corner {
    val small = 12.dp
    val medium = 18.dp
    val large = 24.dp
    val card = 28.dp
}

/**
 * The page background: the backdrop's own gradient with a faint bloom of the
 * accent at the top, so glass high on the screen catches a little of it.
 */
val PageBackground: Brush
    get() {
        val b = ThemeState.backdrop
        val bloom = lerp(b.top, ThemeState.accent.deep, if (b.id == "oled") 0f else 0.10f)
        return Brush.verticalGradient(
            0f to bloom,
            0.35f to b.middle,
            1f to b.bottom
        )
    }

private fun colors() = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = lerp(InkCard, Accent, 0.25f),
    onPrimaryContainer = TextPrimary,
    inversePrimary = AccentDeep,
    secondary = AccentSoft,
    onSecondary = Ink,
    secondaryContainer = lerp(InkCard, Accent, 0.15f),
    onSecondaryContainer = TextPrimary,
    tertiary = AccentBright,
    onTertiary = Ink,
    tertiaryContainer = lerp(InkCard, Accent, 0.15f),
    onTertiaryContainer = TextPrimary,
    background = ThemeState.backdrop.bottom,
    onBackground = TextPrimary,
    surface = InkRaised,
    onSurface = TextPrimary,
    surfaceVariant = InkCard,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Accent,
    inverseSurface = TextPrimary,
    inverseOnSurface = Ink,
    error = Negative,
    onError = Ink,
    errorContainer = Color(0xFF3A1311),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Hairline,
    outlineVariant = Hairline,
    scrim = Ink,
    surfaceDim = ThemeState.backdrop.bottom,
    surfaceBright = lerp(InkCard, Color.White, 0.1f),
    surfaceContainerLowest = ThemeState.backdrop.bottom,
    surfaceContainerLow = InkRaised,
    surfaceContainer = InkCard,
    surfaceContainerHigh = DialogPane,
    surfaceContainerHighest = lerp(DialogPane, Color.White, 0.06f)
)

// Sized and tracked the way a system typeface is: large text set tight and with
// weight, small text set loose.
private val JarvisType = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 52.sp,
        lineHeight = 56.sp,
        letterSpacing = (-1.6).sp
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
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp
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
    // Read here so a change of accent or backdrop rebuilds the colour scheme.
    ThemeState.accent
    ThemeState.backdrop
    val density = LocalDensity.current
    val scale = ThemeState.textScale
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * scale)
    ) {
        MaterialTheme(
            colorScheme = colors(),
            typography = JarvisType,
            content = content
        )
    }
}
