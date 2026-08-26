package com.expensesplit.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette. Indigo primary with a teal secondary reads as calm and financial without being a bank
 * cliché; the semantic colours below are tuned so a positive and a negative amount stay
 * distinguishable at AA contrast on both surfaces.
 */

val IndigoPrimary = Color(0xFF4A56C4)
val IndigoOnPrimary = Color(0xFFFFFFFF)
val IndigoContainer = Color(0xFFDFE0FF)
val IndigoOnContainer = Color(0xFF00105C)

val TealSecondary = Color(0xFF00696E)
val TealOnSecondary = Color(0xFFFFFFFF)
val TealContainer = Color(0xFF9CF1F6)
val TealOnContainer = Color(0xFF002022)

val AmberTertiary = Color(0xFF7C5800)
val AmberOnTertiary = Color(0xFFFFFFFF)
val AmberContainer = Color(0xFFFFDEA6)
val AmberOnTertiaryContainer = Color(0xFF271900)

val ErrorRed = Color(0xFFBA1A1A)
val OnErrorRed = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val SurfaceLight = Color(0xFFFBF8FD)
val OnSurfaceLight = Color(0xFF1B1B1F)
val SurfaceVariantLight = Color(0xFFE3E1EC)
val OnSurfaceVariantLight = Color(0xFF46464F)
val OutlineLight = Color(0xFF777680)

val IndigoPrimaryDark = Color(0xFFBEC2FF)
val IndigoOnPrimaryDark = Color(0xFF16218F)
val IndigoContainerDark = Color(0xFF3139AB)
val IndigoOnContainerDark = Color(0xFFDFE0FF)

val TealSecondaryDark = Color(0xFF80D4DA)
val TealOnSecondaryDark = Color(0xFF00373A)
val TealContainerDark = Color(0xFF004F53)
val TealOnContainerDark = Color(0xFF9CF1F6)

val AmberTertiaryDark = Color(0xFFF7BD48)
val AmberOnTertiaryDark = Color(0xFF412D00)
val AmberContainerDark = Color(0xFF5E4200)
val AmberOnTertiaryContainerDark = Color(0xFFFFDEA6)

val ErrorRedDark = Color(0xFFFFB4AB)
val OnErrorRedDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

val SurfaceDark = Color(0xFF131316)
val OnSurfaceDark = Color(0xFFE5E1E6)
val SurfaceVariantDark = Color(0xFF46464F)
val OnSurfaceVariantDark = Color(0xFFC7C5D0)
val OutlineDark = Color(0xFF918F9A)

/** Semantic colours that are not part of the Material scheme but need a light/dark pair. */
data class FinanceColors(
    val positive: Color,
    val negative: Color,
    val warning: Color,
    val neutral: Color,
    val chartGrid: Color,
    val overBudget: Color,
    val onTrack: Color,
)

val LightFinanceColors = FinanceColors(
    positive = Color(0xFF1B6E3C),
    negative = Color(0xFFB3261E),
    warning = Color(0xFF8A5000),
    neutral = Color(0xFF5F6368),
    chartGrid = Color(0x1A000000),
    overBudget = Color(0xFFB3261E),
    onTrack = Color(0xFF1B6E3C),
)

val DarkFinanceColors = FinanceColors(
    positive = Color(0xFF7FD69B),
    negative = Color(0xFFFFB4AB),
    warning = Color(0xFFFFCF7A),
    neutral = Color(0xFFA9ACB2),
    chartGrid = Color(0x1FFFFFFF),
    overBudget = Color(0xFFFFB4AB),
    onTrack = Color(0xFF7FD69B),
)

/**
 * Ordered palette for chart series that have no category colour of their own.
 * Chosen to stay distinguishable for the most common forms of colour vision deficiency.
 */
val ChartPalette = listOf(
    Color(0xFF4A56C4),
    Color(0xFF00897B),
    Color(0xFFEF6C00),
    Color(0xFF8E24AA),
    Color(0xFF3949AB),
    Color(0xFF00ACC1),
    Color(0xFFD81B60),
    Color(0xFF7CB342),
    Color(0xFF6D4C41),
    Color(0xFFC0CA33),
)
