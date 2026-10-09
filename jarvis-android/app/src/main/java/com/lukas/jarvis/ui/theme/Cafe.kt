package com.lukas.jarvis.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lukas.jarvis.R

// The latte café. Warm white, soft paper, steamed milk, a little caramel. Every
// colour, size, corner, shadow and spring the new screens use is defined once
// here; a screen asks for `Cafe.colors.paper` or `Cafe.space.l`, never a hex
// code or a number of its own.

/** The colour roles. Light is the latte café, dark the night café. */
@Immutable
data class CafeColors(
    val isDark: Boolean,
    /** The page itself. */
    val foam: Color,
    /** Cards: a sheet of paper on the page. */
    val paper: Color,
    /** Surfaces inside a card: chips, fields, tiles. */
    val latte: Color,
    /** Pressed and selected surfaces, and the faint lines between things. */
    val latteDeep: Color,
    /** Primary text. */
    val espresso: Color,
    /** Secondary text, still at reading contrast. */
    val cocoa: Color,
    /** The person's accent, for highlights, the character's props, a selected tab. */
    val accent: Color,
    /** The accent dark (or light) enough to be read as text. */
    val accentText: Color,
    /** The fill of a primary button. */
    val accentFill: Color,
    /** Text and icons on [accentFill]. */
    val onAccent: Color,
    /** A soft wash of the accent behind selected things. */
    val accentSoft: Color,
    val sage: Color,
    val sageText: Color,
    val sageSoft: Color,
    /** Warnings and errors: apologetic, never alarming. */
    val berry: Color,
    val berryText: Color,
    val berrySoft: Color,
    val honey: Color,
    val honeySoft: Color,
    val shadow: Color,
    /** Behind a sheet or dialog. */
    val scrim: Color
)

/** The type scale: an editorial serif for headlines and Mochi's voice, a friendly sans for the rest, pixels for tags. */
@Immutable
data class CafeType(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    /** What Mochi says, large: the line under the character. */
    val voice: TextStyle,
    val voiceSmall: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val labelSmall: TextStyle,
    val caption: TextStyle,
    /** The character's speech tags and tiny status labels. */
    val pixel: TextStyle,
    /** Timer digits. */
    val pixelLarge: TextStyle
)

/** Spacing, on a 4 dp grid. */
@Immutable
object CafeSpace {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 48.dp

    /** The page's side margin. */
    val gutter: Dp = 20.dp

    /** Nothing tappable is smaller than this. */
    val touch: Dp = 48.dp
}

/** Big, soft corners. No hard edges anywhere. */
@Immutable
object CafeShape {
    val small: Shape = RoundedCornerShape(12.dp)
    val medium: Shape = RoundedCornerShape(18.dp)
    val large: Shape = RoundedCornerShape(26.dp)
    val xlarge: Shape = RoundedCornerShape(34.dp)
    val pill: Shape = RoundedCornerShape(percent = 50)
    val sheet: Shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
}

/** How high a sheet of paper sits above the page. */
enum class Elevation(val depth: Dp) {
    Flat(0.dp),
    /** A card resting on the page. */
    Resting(3.dp),
    /** A card picked up, or something floating: the composer, a tile in your hand. */
    Lifted(10.dp),
    /** A sheet over everything. */
    Sheet(18.dp)
}

/**
 * Motion: slow and springy. Things settle; they do not snap. With reduce
 * motion on — the phone's own setting or the app's — every spec here is a cut.
 */
object CafeMotion {
    fun <T> settle(reduce: Boolean): AnimationSpec<T> =
        if (reduce) snap() else spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

    fun <T> gentle(reduce: Boolean): AnimationSpec<T> =
        if (reduce) snap() else spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)

    fun <T> quick(reduce: Boolean): AnimationSpec<T> =
        if (reduce) snap() else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

    fun <T> fade(reduce: Boolean, millis: Int = 260): AnimationSpec<T> =
        if (reduce) snap() else tween(millis)

    const val SHORT = 180
    const val MEDIUM = 320
    const val LONG = 520
}

/** Everything about the current look that is not a colour or a size. */
@Immutable
data class CafeEnv(
    val reduceMotion: Boolean,
    /** Mochi's idle stretches and sips. */
    val idleCharacter: Boolean,
    /** Small celebrations. */
    val delights: Boolean
)

val LocalCafeColors = staticCompositionLocalOf { cafeColors(dark = false, accentId = "caramel") }
val LocalCafeType = staticCompositionLocalOf { cafeType() }
val LocalCafeEnv = staticCompositionLocalOf { CafeEnv(reduceMotion = false, idleCharacter = true, delights = true) }

/** The one way a screen reads the café: `Cafe.colors.paper`, `Cafe.type.voice`, `Cafe.space.l`. */
object Cafe {
    val colors: CafeColors @Composable @ReadOnlyComposable get() = LocalCafeColors.current
    val type: CafeType @Composable @ReadOnlyComposable get() = LocalCafeType.current
    val env: CafeEnv @Composable @ReadOnlyComposable get() = LocalCafeEnv.current
    val space: CafeSpace get() = CafeSpace
    val shape: CafeShape get() = CafeShape
    val reduceMotion: Boolean @Composable @ReadOnlyComposable get() = LocalCafeEnv.current.reduceMotion
}

private fun c(argb: Int) = Color(argb)

/** The colours for one theme and one accent. */
fun cafeColors(dark: Boolean, accentId: String): CafeColors {
    val accent = Palette.accentSet(Palette.accent(accentId).color, dark)
    return if (dark) {
        val sage = Palette.accentSet(Palette.SAGE, true)
        val berry = Palette.accentSet(Palette.BERRY, true)
        CafeColors(
            isDark = true,
            foam = c(Palette.ROAST),
            paper = c(Palette.MOCHA),
            latte = c(Palette.CREMA),
            latteDeep = c(Palette.CREMA_DEEP),
            espresso = c(Palette.STEAMED),
            cocoa = c(Palette.CINNAMON),
            accent = c(accent.main),
            accentText = c(accent.text),
            accentFill = c(accent.fill),
            onAccent = c(accent.onFill),
            accentSoft = c(accent.soft),
            sage = c(Palette.SAGE),
            sageText = c(sage.text),
            sageSoft = c(sage.soft),
            berry = c(Palette.BERRY),
            berryText = c(berry.text),
            berrySoft = c(berry.soft),
            honey = c(Palette.HONEY),
            honeySoft = c(Palette.mix(Palette.HONEY, Palette.MOCHA, 0.22f)),
            shadow = Color.Black,
            scrim = Color(0x99100C0A)
        )
    } else {
        val sage = Palette.accentSet(Palette.SAGE, false)
        val berry = Palette.accentSet(Palette.BERRY, false)
        CafeColors(
            isDark = false,
            foam = c(Palette.FOAM),
            paper = c(Palette.PAPER),
            latte = c(Palette.LATTE),
            latteDeep = c(Palette.LATTE_DEEP),
            espresso = c(Palette.ESPRESSO),
            cocoa = c(Palette.COCOA),
            accent = c(accent.main),
            accentText = c(accent.text),
            accentFill = c(accent.fill),
            onAccent = c(accent.onFill),
            accentSoft = c(accent.soft),
            sage = c(Palette.SAGE),
            sageText = c(sage.text),
            sageSoft = c(sage.soft),
            berry = c(Palette.BERRY),
            berryText = c(berry.text),
            berrySoft = c(berry.soft),
            honey = c(Palette.HONEY),
            honeySoft = c(Palette.mix(Palette.HONEY, Palette.PAPER, 0.24f)),
            shadow = c(Palette.SHADOW),
            scrim = Color(0x663A2E28)
        )
    }
}

val Newsreader = FontFamily(
    Font(R.font.newsreader_regular, FontWeight.Normal),
    Font(R.font.newsreader_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.newsreader_medium, FontWeight.Medium),
    Font(R.font.newsreader_semibold, FontWeight.SemiBold)
)

val DmSans = FontFamily(
    Font(R.font.dmsans_regular, FontWeight.Normal),
    Font(R.font.dmsans_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.dmsans_medium, FontWeight.Medium),
    Font(R.font.dmsans_semibold, FontWeight.SemiBold),
    Font(R.font.dmsans_bold, FontWeight.Bold)
)

val Pixelify = FontFamily(
    Font(R.font.pixelify_regular, FontWeight.Normal),
    Font(R.font.pixelify_semibold, FontWeight.SemiBold)
)

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

fun cafeType(): CafeType = CafeType(
    display = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.01).em),
    headline = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp),
    title = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    voice = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 21.sp, lineHeight = 29.sp),
    voiceSmall = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    body = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    label = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, lineHeightStyle = Trim),
    labelSmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, lineHeightStyle = Trim),
    caption = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04.em),
    pixel = TextStyle(fontFamily = Pixelify, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.03.em),
    pixelLarge = TextStyle(fontFamily = Pixelify, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 40.sp)
)

/**
 * The café around [content].
 *
 * [themeMode] is "system", "light" or "dark"; [textScale] multiplies the
 * phone's own text size; [reduceMotion] is the app's switch, and the phone's
 * own "remove animations" counts too.
 */
@Composable
fun MochiTheme(
    themeMode: String = "system",
    accentId: String = "caramel",
    textScale: Float = 1f,
    reduceMotion: Boolean = false,
    idleCharacter: Boolean = true,
    delights: Boolean = true,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colors = remember(dark, accentId) { cafeColors(dark, accentId) }
    val type = remember { cafeType() }
    val systemCalm = systemReducesMotion()
    val env = remember(reduceMotion, systemCalm, idleCharacter, delights) {
        CafeEnv(reduceMotion = reduceMotion || systemCalm, idleCharacter = idleCharacter, delights = delights)
    }
    val density = LocalDensity.current
    val scaled = remember(density, textScale) {
        Density(density.density, density.fontScale * textScale.coerceIn(0.85f, 1.4f))
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accentFill, onPrimary = colors.onAccent,
            primaryContainer = colors.accentSoft, onPrimaryContainer = colors.espresso,
            secondary = colors.sage, onSecondary = colors.foam,
            background = colors.foam, onBackground = colors.espresso,
            surface = colors.paper, onSurface = colors.espresso,
            surfaceVariant = colors.latte, onSurfaceVariant = colors.cocoa,
            surfaceContainer = colors.paper, surfaceContainerHigh = colors.latte,
            outline = colors.latteDeep, outlineVariant = colors.latteDeep,
            error = colors.berryText, onError = colors.foam, scrim = colors.scrim
        )
    } else {
        lightColorScheme(
            primary = colors.accentFill, onPrimary = colors.onAccent,
            primaryContainer = colors.accentSoft, onPrimaryContainer = colors.espresso,
            secondary = colors.sageText, onSecondary = colors.paper,
            background = colors.foam, onBackground = colors.espresso,
            surface = colors.paper, onSurface = colors.espresso,
            surfaceVariant = colors.latte, onSurfaceVariant = colors.cocoa,
            surfaceContainer = colors.paper, surfaceContainerHigh = colors.latte,
            outline = colors.latteDeep, outlineVariant = colors.latteDeep,
            error = colors.berryText, onError = colors.paper, scrim = colors.scrim
        )
    }
    val materialType = remember(type) {
        Typography(
            displaySmall = type.display,
            headlineMedium = type.headline,
            titleLarge = type.title,
            titleMedium = type.label.copy(fontSize = 16.sp),
            bodyLarge = type.body,
            bodyMedium = type.bodySmall,
            labelLarge = type.label,
            labelMedium = type.labelSmall,
            labelSmall = type.caption
        )
    }
    CompositionLocalProvider(
        LocalCafeColors provides colors,
        LocalCafeType provides type,
        LocalCafeEnv provides env,
        LocalDensity provides scaled
    ) {
        MaterialTheme(colorScheme = scheme, typography = materialType, content = content)
    }
}

/** The phone's own "remove animations": animator duration scale set to zero. */
@Composable
private fun systemReducesMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/** A soft, warm paper shadow under [shape]. */
fun Modifier.paper(elevation: Elevation, shape: Shape, colors: CafeColors): Modifier =
    if (elevation == Elevation.Flat) this else this.shadow(
        elevation = elevation.depth,
        shape = shape,
        clip = false,
        ambientColor = colors.shadow.copy(alpha = if (colors.isDark) 0.5f else 0.22f),
        spotColor = colors.shadow.copy(alpha = if (colors.isDark) 0.6f else 0.30f)
    )
