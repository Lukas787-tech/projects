package com.lukas.jarvis

import com.lukas.jarvis.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every colour a person has to read reaches 4.5:1, in both cafés, for every accent. */
class PaletteTest {

    private fun readable(fg: Int, bg: Int, what: String) {
        val ratio = Palette.contrast(fg, bg)
        assertTrue("$what is only ${"%.2f".format(ratio)}:1", ratio >= Palette.TEXT_CONTRAST)
    }

    @Test fun textReadsOnEverySurface() {
        listOf(Palette.FOAM, Palette.PAPER, Palette.LATTE).forEach { bg ->
            readable(Palette.ESPRESSO, bg, "espresso")
            readable(Palette.COCOA, bg, "cocoa")
        }
        listOf(Palette.ROAST, Palette.MOCHA, Palette.CREMA).forEach { bg ->
            readable(Palette.STEAMED, bg, "steamed milk")
            readable(Palette.CINNAMON, bg, "cinnamon")
        }
    }

    @Test fun everyAccentReadsInBothCafes() {
        val accents = Palette.ACCENTS.map { it.color } + (0 until 360 step 30).map { Palette.accent("custom:$it").color }
        accents.forEach { color ->
            val light = Palette.accentSet(color, dark = false)
            readable(light.text, Palette.PAPER, "accent text on paper")
            readable(light.onFill, light.fill, "button label")
            val dark = Palette.accentSet(color, dark = true)
            readable(dark.text, Palette.MOCHA, "accent text on mocha")
            readable(dark.onFill, dark.fill, "dark button label")
        }
    }

    @Test fun warmPresetsComeFirstAndUnknownIsCaramel() {
        assertEquals("caramel", Palette.ACCENTS.first().id)
        assertEquals("caramel", Palette.accent("arc").id)
        assertEquals(210f, Palette.customHue("custom:210")!!, 0f)
    }
}
